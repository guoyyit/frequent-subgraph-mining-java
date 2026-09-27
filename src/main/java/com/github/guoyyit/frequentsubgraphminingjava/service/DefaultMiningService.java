package com.github.guoyyit.frequentsubgraphminingjava.service;

import com.github.guoyyit.frequentsubgraphminingjava.config.MiningProperties;
import com.github.guoyyit.frequentsubgraphminingjava.graph.GraphCollection;
import com.github.guoyyit.frequentsubgraphminingjava.graph.LabeledEdge;
import com.github.guoyyit.frequentsubgraphminingjava.graph.LabeledNode;
import com.github.guoyyit.frequentsubgraphminingjava.graph.PropertyGraph;
import com.github.guoyyit.frequentsubgraphminingjava.mining.CanonicalCode;
import com.github.guoyyit.frequentsubgraphminingjava.mining.Deadline;
import com.github.guoyyit.frequentsubgraphminingjava.mining.Embedding;
import com.github.guoyyit.frequentsubgraphminingjava.mining.EnumeratedPattern;
import com.github.guoyyit.frequentsubgraphminingjava.mining.MiningConfig;
import com.github.guoyyit.frequentsubgraphminingjava.mining.MiningLimitsExceededException;
import com.github.guoyyit.frequentsubgraphminingjava.mining.MiningOutcome;
import com.github.guoyyit.frequentsubgraphminingjava.mining.MiningStatus;
import com.github.guoyyit.frequentsubgraphminingjava.mining.PatternEnumerator;
import com.github.guoyyit.frequentsubgraphminingjava.mining.PatternLimitException;
import com.github.guoyyit.frequentsubgraphminingjava.mining.PatternResult;
import com.github.guoyyit.frequentsubgraphminingjava.mining.MiningTimeoutException;
import com.github.guoyyit.frequentsubgraphminingjava.store.CatalogChange;
import com.github.guoyyit.frequentsubgraphminingjava.store.CatalogState;
import com.github.guoyyit.frequentsubgraphminingjava.store.DomainState;
import com.github.guoyyit.frequentsubgraphminingjava.store.PatternEntry;
import com.github.guoyyit.frequentsubgraphminingjava.store.PersistenceStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * {@link MiningService} 默认实现。
 *
 * <p>一致性模型（快照隔离）：
 * <ul>
 *   <li>变更（ADD/UPDATE/DELETE）在写锁下串行处理：先把变更写入持久化 WAL
 *       并确认 commit，随后在同一把写锁下把所有已激活挖掘域增量追赶并持久化，
 *       最后原子更新内存目录并发布新版本；</li>
 *   <li>挖掘查询只在读锁下读取某个不可变快照与其已提交域状态，要么看到变更前
 *       的完整结果，要么看到变更后的完整结果，绝不会看到部分混合计数。</li>
 * </ul>
 *
 * <p>增量语义：每个模式空间（配置域 domainSignature = 最大边数 + 允许标签集）
 * 维护一份增量状态 {@link DomainState}：模式 -> 有序支持图集合。变更后只重新
 * 枚举受影响图（旧图与新图）涉及的模式并调整支持集合，其结果与对当前图集合
 * 全量挖掘一致。尚未激活的域在首次查询时基于当前快照全量初始化。
 *
 * <p>崩溃恢复：构造时从持久化存储恢复已提交目录；域在首次查询时加载，
 * 若持久化域版本落后于目录（崩溃发生在目录提交后、域追赶前），则按当前
 * 快照全量重建——这与从当前图集合全量挖掘一致，不重复计数、不丢变更。
 */
public final class DefaultMiningService implements MiningService {

    private final PersistenceStore store;
    private final MiningProperties properties;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 当前已提交目录（仅写锁内可变）。 */
    private final CatalogState catalog;

    /** 已激活挖掘域：签名 -> 运行时（含配置对象与已提交状态，仅写锁内可变）。 */
    private final Map<String, DomainRuntime> activeDomains = new HashMap<>();

    /** 每个域自上次压缩后累计的追赶事务数。 */
    private final Map<String, Integer> pendingCompaction = new HashMap<>();

    public DefaultMiningService(PersistenceStore store, MiningProperties properties) {
        this.store = store;
        this.properties = properties;
        CatalogState recovered = store.loadCatalog();
        this.catalog = recovered != null ? recovered : new CatalogState();
    }

    // ------------------------------------------------------------------
    // 变更提交
    // ------------------------------------------------------------------

    @Override
    public long commitChange(GraphChangeRequest request) {
        validateChangeRequest(request);
        lock.writeLock().lock();
        try {
            long newVersion = catalog.getVersion() + 1;
            PropertyGraph current = catalog.getGraphs().get(request.getGraphName());
            PropertyGraph oldGraph = current == null ? null : current.copy();

            String op = request.getOperation().trim().toUpperCase();
            PropertyGraph newGraph;
            switch (op) {
                case "ADD" -> {
                    if (current != null) {
                        throw new IllegalArgumentException("图 " + request.getGraphName() + " 已存在，无法 ADD");
                    }
                    newGraph = buildGraph(request);
                }
                case "UPDATE" -> {
                    if (current == null) {
                        throw new IllegalArgumentException("图 " + request.getGraphName() + " 不存在，无法 UPDATE");
                    }
                    newGraph = buildGraph(request);
                }
                case "DELETE" -> {
                    if (current == null) {
                        throw new IllegalArgumentException("图 " + request.getGraphName() + " 不存在，无法 DELETE");
                    }
                    newGraph = null;
                }
                default -> throw new IllegalArgumentException("未知操作: " + request.getOperation());
            }
            validateGraphSize(newGraph);

            CatalogChange change = new CatalogChange();
            change.setCatalogVersion(newVersion);
            change.setOperation(op);
            change.setGraphName(request.getGraphName());
            change.setNewGraph(newGraph);
            change.setOldGraph(oldGraph);

            // 0) 先在内存中计算所有已激活域的新状态（含单图枚举）。
            //    这一步可能因规模/超时失败；失败则直接抛出，不写任何持久化、
            //    不改变目录，整体停留在旧版本，避免“部分域已追赶”的不一致。
            Map<String, DomainState> updatedDomains = new HashMap<>();
            for (DomainRuntime runtime : activeDomains.values()) {
                DomainState updated = applySingleChange(runtime.state, runtime.config, change);
                updatedDomains.put(runtime.signature, updated);
            }

            // 1) 目录变更先持久化（崩溃点之后：重启仍可看到该变更）
            store.commitCatalogChange(change);

            // 2) 已激活域在同一写事务内持久化已算好的新状态。
            //    若此处崩溃：重启后域版本落后，会按快照全量重建，结果一致。
            for (Map.Entry<String, DomainState> e : updatedDomains.entrySet()) {
                store.commitDomainCatchUp(e.getKey(), newVersion, e.getValue());
                activeDomains.get(e.getKey()).state = e.getValue();
                int count = pendingCompaction.merge(e.getKey(), 1, Integer::sum);
                if (count >= properties.getCompactThreshold()) {
                    store.compactDomain(e.getKey(), e.getValue());
                    pendingCompaction.put(e.getKey(), 0);
                }
            }

            // 3) 内存目录原子切换（此后查询看到新版本）
            if (newGraph != null) {
                catalog.getGraphs().put(request.getGraphName(), newGraph);
            } else {
                catalog.getGraphs().remove(request.getGraphName());
            }
            catalog.setVersion(newVersion);
            return newVersion;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public MiningOutcome query(MiningQuery query) {
        MiningConfig config = resolveConfig(query);
        MiningConfig domainConfig = toDomainConfig(config);
        String signature = domainConfig.domainSignature();

        // 快速路径：域已激活，读锁内拿到版本一致的不可变快照
        lock.readLock().lock();
        try {
            DomainRuntime runtime = activeDomains.get(signature);
            if (runtime != null && runtime.state.getCatalogVersion() == catalog.getVersion()) {
                return buildOutcome(config, immutableSnapshot(), runtime.state, catalog.getVersion());
            }
        } finally {
            lock.readLock().unlock();
        }

        // 慢速路径：首次使用该域，或崩溃恢复后域落后 -> 写锁内加载/全量重建
        lock.writeLock().lock();
        try {
            long version = catalog.getVersion();
            DomainState state = initializeDomainIfAbsent(domainConfig, version);
            GraphCollection snapshot = immutableSnapshot();
            return buildOutcome(config, snapshot, state, version);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public GraphCollection snapshot() {
        lock.readLock().lock();
        try {
            return immutableSnapshot();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public long currentVersion() {
        lock.readLock().lock();
        try {
            return catalog.getVersion();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    @Override
    public MiningConfig resolveConfig(MiningQuery query) {
        MiningConfig c = new MiningConfig();
        c.setMinSupport(orDefault(query == null ? null : query.getMinSupport(),
                properties.getDefaultMinSupport()));
        c.setMaxPatternEdges(orDefault(query == null ? null : query.getMaxPatternEdges(),
                properties.getDefaultMaxPatternEdges()));
        c.setAllowedNodeLabels(query != null && query.getAllowedNodeLabels() != null
                ? new HashSet<>(query.getAllowedNodeLabels()) : new HashSet<>());
        c.setAllowedEdgeLabels(query != null && query.getAllowedEdgeLabels() != null
                ? new HashSet<>(query.getAllowedEdgeLabels()) : new HashSet<>());
        c.setMaxResults(orDefault(query == null ? null : query.getMaxResults(),
                properties.getDefaultMaxResults()));
        c.setMaxEmbeddingsPerPattern(orDefault(query == null ? null : query.getMaxEmbeddingsPerPattern(),
                properties.getDefaultMaxEmbeddingsPerPattern()));
        c.setMaxNodesPerGraph(properties.getMaxNodesPerGraph());
        c.setMaxEdgesPerGraph(properties.getMaxEdgesPerGraph());
        c.setTimeoutMillis(orDefault(query == null ? null : query.getTimeoutMillis(),
                properties.getDefaultTimeoutMillis()));
        c.validate();
        return c;
    }

    private static <T extends Number> T orDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }

    /**
     * 构造用于“模式空间枚举”的域配置：只保留决定模式空间的字段
     * （最大边数、允许标签集）以及规模/时间上限；minSupport 与结果数量
     * 不影响模式空间。域在首次激活时绑定此配置并随运行时持有。
     */
    private MiningConfig toDomainConfig(MiningConfig queryConfig) {
        MiningConfig c = new MiningConfig();
        c.setMinSupport(1);
        c.setMaxPatternEdges(queryConfig.getMaxPatternEdges());
        c.setAllowedNodeLabels(new HashSet<>(queryConfig.getAllowedNodeLabels()));
        c.setAllowedEdgeLabels(new HashSet<>(queryConfig.getAllowedEdgeLabels()));
        c.setMaxResults(queryConfig.getMaxResults());
        c.setMaxEmbeddingsPerPattern(queryConfig.getMaxEmbeddingsPerPattern());
        c.setMaxNodesPerGraph(properties.getMaxNodesPerGraph());
        c.setMaxEdgesPerGraph(properties.getMaxEdgesPerGraph());
        c.setTimeoutMillis(properties.getDefaultTimeoutMillis());
        return c;
    }

    // ------------------------------------------------------------------
    // 增量 / 全量
    // ------------------------------------------------------------------

    /** 写锁内：域不存在时从持久化加载或从当前快照全量初始化。 */
    private DomainState initializeDomainIfAbsent(MiningConfig config, long currentVersion) {
        String signature = config.domainSignature();
        DomainRuntime runtime = activeDomains.get(signature);
        if (runtime != null && runtime.state.getCatalogVersion() == currentVersion) {
            return runtime.state;
        }
        DomainState loaded = store.loadDomain(signature);
        if (loaded == null || loaded.getCatalogVersion() < currentVersion) {
            // 首次使用或崩溃恢复后落后：按当前快照全量重建（等价于全量挖掘结果）
            loaded = rebuildFromSnapshot(config, currentVersion);
            store.commitDomainCatchUp(signature, currentVersion, loaded);
        }
        activeDomains.put(signature, new DomainRuntime(signature, config, loaded));
        return loaded;
    }

    /** 精确应用一次目录变更到域状态（返回新的不可变逻辑副本）。 */
    private DomainState applySingleChange(DomainState state, MiningConfig config, CatalogChange change) {
        DomainState copy = deepCopyDomain(state);
        copy.setCatalogVersion(change.getCatalogVersion());
        String graph = change.getGraphName();

        if (change.getOldGraph() != null) {
            validateGraphSize(change.getOldGraph());
            Map<String, EnumeratedPattern> oldPatterns = enumerate(change.getOldGraph(), config);
            removeSupport(copy, graph, oldPatterns.keySet());
        }
        if (change.getNewGraph() != null) {
            validateGraphSize(change.getNewGraph());
            Map<String, EnumeratedPattern> newPatterns = enumerate(change.getNewGraph(), config);
            addSupport(copy, graph, newPatterns);
        }
        return copy;
    }

    /**
     * 枚举单图模式。增量状态必须完整，若超时或单图枚举超预算，
     * 统一转换为明确的规模超限错误（由 Web 层映射为 422）。
     */
    private Map<String, EnumeratedPattern> enumerate(PropertyGraph graph, MiningConfig config) {
        try {
            return new PatternEnumerator(graph, config)
                    .enumerate(Deadline.after(config.getTimeoutMillis()));
        } catch (MiningTimeoutException | PatternLimitException e) {
            throw new MiningLimitsExceededException(
                    "图 " + graph.getName() + " 的模式枚举超过时间或规模上限：" + e.getMessage());
        }
    }

    private void removeSupport(DomainState state, String graphName, Set<String> patternCodes) {
        for (String code : patternCodes) {
            PatternEntry entry = state.getPatterns().get(code);
            if (entry != null) {
                entry.getSupportingGraphs().remove(graphName);
                if (entry.getSupportingGraphs().isEmpty()) {
                    state.getPatterns().remove(code);
                }
            }
        }
    }

    private void addSupport(DomainState state, String graphName,
                            Map<String, EnumeratedPattern> patterns) {
        for (EnumeratedPattern ep : patterns.values()) {
            CanonicalCode code = ep.getCode();
            PatternEntry entry = state.getPatterns().get(code.getCode());
            if (entry == null) {
                entry = new PatternEntry();
                entry.setCanonicalCode(code.getCode());
                entry.setEdgeCount(code.getEdgeCount());
                entry.setSupportingGraphs(new ArrayList<>());
                state.getPatterns().put(code.getCode(), entry);
            }
            List<String> supporters = entry.getSupportingGraphs();
            int idx = Collections.binarySearch(supporters, graphName);
            if (idx < 0) {
                supporters.add(-idx - 1, graphName);
            }
        }
    }

    /** 从当前目录全量构建域状态（调用方持写锁，直接读取 catalog 内容）。 */
    private DomainState rebuildFromSnapshot(MiningConfig config, long version) {
        DomainState state = new DomainState();
        state.setDomainSignature(config.domainSignature());
        state.setCatalogVersion(version);
        for (PropertyGraph graph : catalog.getGraphs().values()) {
            validateGraphSize(graph);
            Map<String, EnumeratedPattern> patterns = enumerate(graph, config);
            addSupport(state, graph.getName(), patterns);
        }
        return state;
    }

    // ------------------------------------------------------------------
    // 结果组装
    // ------------------------------------------------------------------

    private MiningOutcome buildOutcome(MiningConfig config, GraphCollection snapshot,
                                       DomainState state, long version) {
        long start = System.nanoTime();
        List<PatternResult> results = new ArrayList<>();
        for (PatternEntry entry : state.getPatterns().values()) {
            if (entry.getSupportingGraphs().size() < config.getMinSupport()) {
                continue;
            }
            PatternResult pr = new PatternResult();
            pr.setCanonicalCode(entry.getCanonicalCode());
            pr.setEdgeCount(entry.getEdgeCount());
            pr.setSupport(entry.getSupportingGraphs().size());
            pr.setEmbeddings(new ArrayList<>());
            results.add(pr);
        }
        // 稳定排序：边数升序，规范字符串升序
        results.sort((a, b) -> {
            if (a.getEdgeCount() != b.getEdgeCount()) {
                return Integer.compare(a.getEdgeCount(), b.getEdgeCount());
            }
            return a.getCanonicalCode().compareTo(b.getCanonicalCode());
        });

        boolean truncated = false;
        if (results.size() > config.getMaxResults()) {
            results = new ArrayList<>(results.subList(0, config.getMaxResults()));
            truncated = true;
        }

        // 嵌入填充属于结果展示：超时或单图枚举超预算时给出明确状态，而非抛出 500
        boolean embedTimeout = false;
        boolean embedLimit = false;
        try {
            populateEmbeddings(results, config, snapshot, start);
        } catch (MiningTimeoutException e) {
            embedTimeout = true;
        } catch (PatternLimitException e) {
            embedLimit = true;
        }

        MiningOutcome outcome = new MiningOutcome();
        outcome.setPatterns(results);
        outcome.setCatalogVersion(version);
        outcome.setGraphCount(snapshot.size());
        outcome.setElapsedMillis((System.nanoTime() - start) / 1_000_000L);
        if (embedTimeout) {
            outcome.setStatus(MiningStatus.TIMEOUT);
            outcome.setMessage("填充嵌入时达到执行时间上限，嵌入可能不完整");
        } else if (truncated || embedLimit) {
            outcome.setStatus(MiningStatus.RESULT_LIMIT_REACHED);
            outcome.setMessage(embedLimit
                    ? "嵌入枚举达到规模上限，嵌入可能不完整"
                    : "达到结果数量上限，结果可能不完整");
        } else {
            outcome.setStatus(MiningStatus.COMPLETE);
            outcome.setMessage("OK");
        }
        return outcome;
    }

    private void populateEmbeddings(List<PatternResult> results, MiningConfig config,
                                    GraphCollection snapshot, long startNanos) {
        Set<String> wantedCodes = new HashSet<>();
        for (PatternResult pr : results) {
            wantedCodes.add(pr.getCanonicalCode());
        }
        Map<String, PatternResult> byCode = new HashMap<>();
        for (PatternResult pr : results) {
            byCode.put(pr.getCanonicalCode(), pr);
        }
        Deadline deadline = Deadline.after(config.getTimeoutMillis());
        for (PropertyGraph graph : snapshot.getGraphs().values()) {
            deadline.checkpoint();
            Map<String, EnumeratedPattern> found =
                    new PatternEnumerator(graph, config).enumerate(deadline);
            for (String code : wantedCodes) {
                EnumeratedPattern ep = found.get(code);
                if (ep == null) {
                    continue;
                }
                PatternResult pr = byCode.get(code);
                List<Embedding> embeddings = pr.getEmbeddings();
                for (Map<String, String> mapping : ep.getMappings()) {
                    if (embeddings.size() >= config.getMaxEmbeddingsPerPattern()) {
                        break;
                    }
                    embeddings.add(new Embedding(graph.getName(), new LinkedHashMap<>(mapping)));
                }
            }
        }
        // 嵌入稳定排序
        for (PatternResult pr : results) {
            pr.getEmbeddings().sort((x, y) -> {
                int c = x.getGraphName().compareTo(y.getGraphName());
                if (c != 0) {
                    return c;
                }
                return x.getNodeMapping().toString().compareTo(y.getNodeMapping().toString());
            });
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private GraphCollection immutableSnapshot() {
        GraphCollection collection = new GraphCollection();
        for (PropertyGraph g : catalog.getGraphs().values()) {
            collection.put(g.copy());
        }
        return collection;
    }

    private void validateChangeRequest(GraphChangeRequest request) {
        if (request == null || request.getOperation() == null || request.getGraphName() == null
                || request.getGraphName().isBlank()) {
            throw new IllegalArgumentException("operation 与 graphName 不能为空");
        }
    }

    private PropertyGraph buildGraph(GraphChangeRequest request) {
        PropertyGraph graph = new PropertyGraph(request.getGraphName());
        List<GraphChangeRequest.NodeDto> nodes =
                request.getNodes() == null ? List.of() : request.getNodes();
        List<GraphChangeRequest.EdgeDto> edges =
                request.getEdges() == null ? List.of() : request.getEdges();
        Set<String> nodeIds = new HashSet<>();
        for (GraphChangeRequest.NodeDto n : nodes) {
            if (n.getId() == null || n.getLabel() == null) {
                throw new IllegalArgumentException("节点 id 与 label 不能为空");
            }
            if (!nodeIds.add(n.getId())) {
                throw new IllegalArgumentException("重复节点 id: " + n.getId());
            }
            graph.addNode(new LabeledNode(n.getId(), n.getLabel()));
        }
        for (GraphChangeRequest.EdgeDto e : edges) {
            if (e.getSource() == null || e.getTarget() == null || e.getLabel() == null) {
                throw new IllegalArgumentException("边端点与 label 不能为空");
            }
            graph.addEdge(new LabeledEdge(e.getSource(), e.getTarget(), e.getLabel()));
        }
        return graph;
    }

    private void validateGraphSize(PropertyGraph graph) {
        if (graph == null) {
            return;
        }
        if (graph.getNodes().size() > properties.getMaxNodesPerGraph()) {
            throw new MiningLimitsExceededException(
                    "图 " + graph.getName() + " 节点数 " + graph.getNodes().size()
                            + " 超过上限 " + properties.getMaxNodesPerGraph());
        }
        if (graph.getEdges().size() > properties.getMaxEdgesPerGraph()) {
            throw new MiningLimitsExceededException(
                    "图 " + graph.getName() + " 边数 " + graph.getEdges().size()
                            + " 超过上限 " + properties.getMaxEdgesPerGraph());
        }
    }

    private static DomainState deepCopyDomain(DomainState source) {
        DomainState copy = new DomainState();
        copy.setDomainSignature(source.getDomainSignature());
        copy.setCatalogVersion(source.getCatalogVersion());
        for (PatternEntry e : source.getPatterns().values()) {
            PatternEntry c = new PatternEntry();
            c.setCanonicalCode(e.getCanonicalCode());
            c.setEdgeCount(e.getEdgeCount());
            c.setSupportingGraphs(new ArrayList<>(new TreeSet<>(e.getSupportingGraphs())));
            copy.getPatterns().put(c.getCanonicalCode(), c);
        }
        return copy;
    }

    /** 挖掘域运行时：签名 + 模式空间配置 + 已提交状态。 */
    private static final class DomainRuntime {
        private final String signature;
        private final MiningConfig config;
        private DomainState state;

        private DomainRuntime(String signature, MiningConfig config, DomainState state) {
            this.signature = signature;
            this.config = config;
            this.state = state;
        }
    }
}
