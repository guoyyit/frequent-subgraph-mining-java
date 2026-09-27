# 频繁子图挖掘服务（Frequent Subgraph Mining）

在已有的属性图载入/遍历能力之上，本服务提供**跨图集合的本地频繁子图挖掘与模式计数**。
输入为带**节点标签**与**边标签**的有向图集合，输出满足支持度与规模约束的频繁模式
及其**嵌入映射（embedding）**，并支持**增量更新、并发快照隔离、崩溃恢复、规模上限
保护与结果确定性**。

- 运行环境：Java 17、Spring Boot 3.4、本地文件持久化（无外部数据库依赖）
- 构建与测试：`mvn test`

---

## 1. 匹配语义

| 主题 | 语义 |
| --- | --- |
| 图模型 | 节点、边均带标签；边为**有向边**；允许同一对节点之间存在多条**平行边** |
| 模式 | 非空的**连通**子图，包含 0 边的单节点模式，边数 1..`maxPatternEdges` 的连通模式 |
| 同构判定 | 采用规范编码（canonical code）：枚举顶点排列取字典序最小表示；同构模式（忽略节点 id、录入顺序、边顺序）得到相同编码，有向边方向与边标签参与判定 |
| 支持度 | **图级支持度（graph-level support）**：一个模式在同一张数据图中无论出现多少次只计 1；`support = 出现该模式的不同数据图数量` |
| 嵌入 | 模式规范节点（`p0`,`p1`,...）到数据图节点 id 的映射；同一模式在同一图中的自同构映射会去重 |
| 标签过滤 | `allowedNodeLabels` / `allowedEdgeLabels` 为空表示**不限制**；否则只枚举标签在白名单内的模式 |
| 规模约束 | `minSupport`、`maxPatternEdges`；超过边数上限的模式不会被枚举 |

> 增量结果与对**当前图集合全量挖掘**得到的结果严格一致（见
> `IncrementalEquivalenceTest`：固定随机种子下 新增/修改/删除 混合序列逐模式比对支持度与嵌入）。

---

## 2. 参数

查询参数（`POST /api/mining/query`，均可选，缺省取服务默认值，见
`MiningProperties` / `application.properties`）：

| 参数 | 含义 | 默认值 |
| --- | --- | --- |
| `minSupport` | 最小图级支持度（>=1） | `1` |
| `maxPatternEdges` | 模式最大边数（>=1） | `5` |
| `allowedNodeLabels` | 允许的节点标签集合，空=不限制 | 空 |
| `allowedEdgeLabels` | 允许的边标签集合，空=不限制 | 空 |
| `maxResults` | 返回模式数量上限 | `1000` |
| `maxEmbeddingsPerPattern` | 每个模式保留的嵌入数量上限 | `100` |
| `timeoutMillis` | 执行时间上限（毫秒） | `10000` |

全局保护参数（`mining.*`）：

| 参数 | 含义 | 默认值 |
| --- | --- | --- |
| `mining.max-nodes-per-graph` | 单图节点数硬上限，超限返回 422 | `50` |
| `mining.max-edges-per-graph` | 单图边数硬上限，超限返回 422 | `200` |
| `mining.compact-threshold` | 每个挖掘域累计多少次已提交增量事务后做快照压缩 | `50` |
| `mining.storage-dir` | 持久化目录 | `./data/mining` |

达到 `maxResults` / 枚举预算时终止并返回 `status=RESULT_LIMIT_REACHED`；
达到 `timeoutMillis` 时返回 `status=TIMEOUT`（带已算出的部分结果）。
两种情况下结果都**可能不完整**，但状态字段明确可辨，不会因组合爆炸导致进程无响应或 OOM。

---

## 3. REST API

### 提交一次图集合变更
```
POST /api/mining/graphs
{
  "operation": "ADD | UPDATE | DELETE",
  "graphName": "g1",
  "nodes": [ {"id": "a", "label": "A"}, {"id": "b", "label": "B"} ],
  "edges": [ {"source": "a", "target": "b", "label": "knows"} ]
}
```
返回 `{"version": <新版本号>}`。变更在持久化确认后原子生效；重复 ADD / 对不存在图
UPDATE|DELETE 返回 `400 BAD_REQUEST`。

### 执行挖掘查询
```
POST /api/mining/query
{ "minSupport": 2, "maxPatternEdges": 3, "allowedEdgeLabels": ["knows"] }
```
返回：
```json
{
  "status": "COMPLETE | RESULT_LIMIT_REACHED | TIMEOUT",
  "catalogVersion": 7,
  "graphCount": 3,
  "elapsedMillis": 2,
  "message": "OK",
  "patterns": [
    {
      "canonicalCode": "v[A,B]e[0-knows-1]",
      "edgeCount": 1,
      "support": 2,
      "embeddings": [
        { "graphName": "g1", "nodeMapping": {"p0": "a", "p1": "b"} }
      ]
    }
  ]
}
```

### 查看当前已提交版本
`GET /api/mining/version` → `{"version": 7}`

---

## 4. 一致性、增量与恢复保证

### 4.1 快照隔离（并发）
- 变更（ADD/UPDATE/DELETE）在同一把**写锁**下串行处理：
  1. 变更写入持久化 WAL 并确认 `commit`；
  2. 已激活的挖掘配置域做**精确增量追赶**并持久化（只重枚举受影响图的旧图/新图涉及的模式，调整“模式→支持图集合”）；
  3. 内存目录原子切换到新版本。
- 查询只在读锁下读取某个**不可变快照**。因此一次查询要么看到变更前的完整状态，
  要么看到变更后的完整结果，**绝不出现部分变更混合的计数**。

### 4.2 增量更新
- 模式空间按配置域隔离：`domainSignature = maxPatternEdges + 允许节点/边标签集合`
  （`minSupport` 只是过滤阈值，不参与域划分，可复用同一份增量状态）。
- 每个域维护 `模式 -> 有序支持图列表`。修改/删除时先从旧图模式集合移除支持，
  再向新图模式集合加入支持；支持数降为 0 的模式被删除。
- 尚未激活的域在首次查询时基于当前快照全量初始化。

### 4.3 崩溃恢复（不重复计数 / 不丢已提交变更）
磁盘布局：
```
<storage-dir>/
  catalog/
    snapshot.json
    wal/<seq>/{payload.json, commit}
  domains/<configSignature>/
    snapshot.json
    wal/<seq>/{payload.json, commit}
```
提交协议是**先写 payload、后创建 commit 标记并落盘**，快照采用临时文件 + 原子改名：

- 崩溃在某事务 `commit` 标记之前：恢复时忽略该事务目录，内存也尚未生效 → 等同事务未发生；
- 崩溃在 `commit` 之后：恢复时按序号重放，应用逻辑对版本号幂等（`<= 当前版本` 跳过）→ 不重复计数；
- 若崩溃在“目录已提交、某域增量追赶之前”，该域重启后检测到版本落后，按当前快照
  **全量重建**，结果等价于从当前图集合全量挖掘 → 不丢已提交变更。

### 4.4 确定性
在相同输入与相同配置下，结果稳定复现：
- 图按提交顺序（`LinkedHashMap`）迭代；
- 模式按 `(edgeCount 升序, canonicalCode 升序)` 排序；
- 支持图列表有序；嵌入按 `(graphName, 映射内容)` 排序。

---

## 5. 代码结构

```
graph/      LabeledNode / LabeledEdge / PropertyGraph / GraphCollection
mining/     Canonicalizer（规范同构编码）
            PatternEnumerator（单图连通模式枚举 + 嵌入去重）
            FrequentSubgraphMiner（全量挖掘、图级支持聚合、上限/超时状态）
            MiningConfig / MiningQuery 参数、PatternResult / Embedding / MiningOutcome
store/      PersistenceStore 接口、FilePersistenceStore（快照 + 事务 WAL + 恢复）
service/    MiningService / DefaultMiningService（锁、增量、恢复、配置解析）
web/        MiningController、GlobalExceptionHandler
config/     MiningProperties、Bean 装配
```

## 6. 测试覆盖

| 测试类 | 覆盖点 |
| --- | --- |
| `CanonicalizerTest` | 同构等价、有向方向、标签序列差异 |
| `PatternEnumeratorTest` | 模式枚举数量、嵌入、标签过滤、自同构去重、平行边 |
| `FrequentSubgraphMinerTest` | 图级支持度、支持度过滤、稳定排序、结果上限、图规模上限、超时状态 |
| `FilePersistenceStoreTest` | WAL 重放、未提交事务丢弃、幂等、快照压缩、域状态恢复 |
| `DefaultMiningServiceTest` | 增/改/删一致性、约束过滤、配置域隔离、确定性、崩溃恢复、非法操作、结果上限 |
| `IncrementalEquivalenceTest` | 随机增删改序列下“增量结果 == 全量结果”（支持度 + 嵌入） |
| `MiningConcurrencyTest` | 并发变更与查询只观察到完整版本的计数 |
| `MiningControllerTest` | REST 变更/查询/版本流程与错误状态码 |
