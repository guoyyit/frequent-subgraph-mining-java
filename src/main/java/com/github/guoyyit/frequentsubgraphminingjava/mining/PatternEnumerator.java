package com.github.guoyyit.frequentsubgraphminingjava.mining;

import com.github.guoyyit.frequentsubgraphminingjava.graph.LabeledEdge;
import com.github.guoyyit.frequentsubgraphminingjava.graph.LabeledNode;
import com.github.guoyyit.frequentsubgraphminingjava.graph.PropertyGraph;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在单个数据图内枚举满足配置约束（标签集合、最大边数）的所有
 * 连通子图模式，按规范编码去重，并收集每个模式的嵌入映射。
 *
 * <p>枚举方法：从每个允许标签的节点出发，逐边生长连通边集（每条边最多被
 * 加入一次，用位图去重），每个连通边集恰由其“生长起点”唯一生成，
 * 不会重复。每个边集经 {@link Canonicalizer} 规范化，同构的边集
 * （例如不同嵌入顺序得到的相同模式）合并到同一规范键下，嵌入映射按
 * 自同构展开后用节点映射集合去重。
 *
 * <p>节点数为 0 边的单节点模式也会被枚举（edgeCount = 0）。
 */
public final class PatternEnumerator {

    private final PropertyGraph graph;
    private final MiningConfig config;

    private final List<LabeledNode> nodes;
    private final List<LabeledEdge> edges;
    /** 节点 id -> 下标。 */
    private final Map<String, Integer> nodeIndex;
    /** 邻接表：含所有与该节点相连的有向边（出边与入边）。 */
    private final List<List<Integer>> incident;

    /** 枚举过程中单图产生的不同模式总数上限（达到即抛 {@link PatternLimitException}）。 */
    private int distinctPatternBudget;
    /** 每个模式保留的去重嵌入映射上限。 */
    private final int embeddingsPerPatternLimit;

    public PatternEnumerator(PropertyGraph graph, MiningConfig config) {
        this.graph = graph;
        this.config = config;
        this.nodes = graph.getNodes();
        this.edges = graph.getEdges();
        this.nodeIndex = new HashMap<>(nodes.size() * 2);
        for (int i = 0; i < nodes.size(); i++) {
            nodeIndex.put(nodes.get(i).getId(), i);
        }
        this.incident = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            incident.add(new ArrayList<>());
        }
        for (int e = 0; e < edges.size(); e++) {
            LabeledEdge le = edges.get(e);
            incident.get(nodeIndex.get(le.getSource())).add(e);
            incident.get(nodeIndex.get(le.getTarget())).add(e);
        }
        this.distinctPatternBudget = config.getMaxResults() * 10 + 64;
        this.embeddingsPerPatternLimit = config.getMaxEmbeddingsPerPattern();
    }

    /**
     * 枚举该图中出现的所有不同模式（含 0 边单节点模式）。
     *
     * @param deadline 超时控制
     * @return key 为规范字符串，value 为模式及其去重后的嵌入映射
     */
    public Map<String, EnumeratedPattern> enumerate(Deadline deadline) {
        Map<String, MutablePattern> patterns = new LinkedHashMap<>();

        // 0 边模式：每个允许标签的节点
        for (int v = 0; v < nodes.size(); v++) {
            String label = nodes.get(v).getLabel();
            if (!config.nodeLabelAllowed(label)) {
                continue;
            }
            CanonicalCode code = Canonicalizer.canonicalize(
                    List.of(label), List.of()).getCode();
            MutablePattern mp = patterns.computeIfAbsent(code.getCode(), k -> new MutablePattern(code));
            Map<String, String> mapping = new HashMap<>(2);
            mapping.put(Canonicalizer.patternNodeId(0), nodes.get(v).getId());
            mp.addMapping(mapping, embeddingsPerPatternLimit);
        }

        // 从每条允许的边出发做连通生长。
        // 规范生成规则：边集的“种子”是其中最小下标的边，后续加入的边下标必须
        // 严格大于种子，因此每个连通边集恰被生成一次（不同种子的路径会被跳过）。
        for (int startEdge = 0; startEdge < edges.size(); startEdge++) {
            LabeledEdge le = edges.get(startEdge);
            if (!config.edgeLabelAllowed(le.getLabel())) {
                continue;
            }
            int s = nodeIndex.get(le.getSource());
            int t = nodeIndex.get(le.getTarget());
            if (!config.nodeLabelAllowed(nodes.get(s).getLabel())
                    || !config.nodeLabelAllowed(nodes.get(t).getLabel())) {
                continue;
            }
            BitSet usedEdges = new BitSet(edges.size());
            usedEdges.set(startEdge);
            BitSet frontier = new BitSet(nodes.size());
            frontier.set(s);
            frontier.set(t);
            grow(startEdge, usedEdges, frontier, 1, patterns, deadline);
        }

        Map<String, EnumeratedPattern> result = new LinkedHashMap<>(patterns.size());
        for (MutablePattern mp : patterns.values()) {
            result.put(mp.code.getCode(),
                    new EnumeratedPattern(mp.code, new ArrayList<>(mp.mappings)));
        }
        return result;
    }

    /**
     * 连通边集 DFS 生长。
     *
     * @param seedEdge  种子边（当前边集最小下标边）
     * @param usedEdges 已在当前边集中的边位图
     * @param frontier  当前边集覆盖的节点位图
     * @param edgeCount 当前边数
     */
    private void grow(int seedEdge, BitSet usedEdges, BitSet frontier, int edgeCount,
                      Map<String, MutablePattern> patterns, Deadline deadline) {
        if ((edgeCount & 63) == 0) {
            deadline.checkpoint();
        }
        recordPattern(usedEdges, patterns);
        if (edgeCount >= config.getMaxPatternEdges()) {
            return;
        }

        // 只考虑与当前覆盖节点相连、下标严格大于种子、边标签被允许、
        // 且引入的节点标签被允许的边（下标 <= seed 的边要么是种子，要么会破坏唯一种子规则）
        BitSet candidateEdges = new BitSet(edges.size());
        for (int v = frontier.nextSetBit(0); v >= 0; v = frontier.nextSetBit(v + 1)) {
            for (int eIdx : incident.get(v)) {
                if (eIdx <= seedEdge || usedEdges.get(eIdx) || candidateEdges.get(eIdx)) {
                    continue;
                }
                LabeledEdge le = edges.get(eIdx);
                if (!config.edgeLabelAllowed(le.getLabel())) {
                    continue;
                }
                int s = nodeIndex.get(le.getSource());
                int t = nodeIndex.get(le.getTarget());
                if (!config.nodeLabelAllowed(nodes.get(s).getLabel())
                        || !config.nodeLabelAllowed(nodes.get(t).getLabel())) {
                    continue;
                }
                candidateEdges.set(eIdx);
            }
        }

        // 任意顺序扩展都保持“最小边为种子”，同一连通边集不会再由其他种子生成
        for (int e = candidateEdges.nextSetBit(0); e >= 0; e = candidateEdges.nextSetBit(e + 1)) {
            BitSet nextUsed = (BitSet) usedEdges.clone();
            nextUsed.set(e);
            BitSet nextFrontier = (BitSet) frontier.clone();
            LabeledEdge le = edges.get(e);
            nextFrontier.set(nodeIndex.get(le.getSource()));
            nextFrontier.set(nodeIndex.get(le.getTarget()));
            grow(seedEdge, nextUsed, nextFrontier, edgeCount + 1, patterns, deadline);
        }
    }

    /**
     * 将一个边集规范化并登记；同构边集合并，嵌入按自同构展开去重。
     */
    private void recordPattern(BitSet usedEdges, Map<String, MutablePattern> patterns) {
        // 收集边集覆盖的局部顶点（数据图下标），局部下标取边集中最小顺序
        BitSet vertices = new BitSet(nodes.size());
        List<Canonicalizer.InputEdge> inputEdges = new ArrayList<>();
        for (int e = usedEdges.nextSetBit(0); e >= 0; e = usedEdges.nextSetBit(e + 1)) {
            LabeledEdge le = edges.get(e);
            int s = nodeIndex.get(le.getSource());
            int t = nodeIndex.get(le.getTarget());
            vertices.set(s);
            vertices.set(t);
            inputEdges.add(new Canonicalizer.InputEdge(s, t, le.getLabel()));
        }

        // 压缩为局部连续下标
        List<Integer> localVertex = new ArrayList<>();
        int[] localIndex = new int[nodes.size()];
        java.util.Arrays.fill(localIndex, -1);
        for (int v = vertices.nextSetBit(0); v >= 0; v = vertices.nextSetBit(v + 1)) {
            localIndex[v] = localVertex.size();
            localVertex.add(v);
        }
        List<String> labels = new ArrayList<>(localVertex.size());
        for (int v : localVertex) {
            labels.add(nodes.get(v).getLabel());
        }
        List<Canonicalizer.InputEdge> localEdges = new ArrayList<>(inputEdges.size());
        for (Canonicalizer.InputEdge ie : inputEdges) {
            localEdges.add(new Canonicalizer.InputEdge(
                    localIndex[ie.fromLocal()], localIndex[ie.toLocal()], ie.label()));
        }

        Canonicalizer.Result result = Canonicalizer.canonicalize(labels, localEdges);
        CanonicalCode code = result.getCode();
        MutablePattern mp = patterns.get(code.getCode());
        if (mp == null) {
            if (patterns.size() >= distinctPatternBudget) {
                throw new PatternLimitException(
                        "单图枚举产生的不同模式数超过预算（" + distinctPatternBudget
                                + "），请收紧 maxPatternEdges / 标签集合 / 图规模上限");
            }
            mp = new MutablePattern(code);
            patterns.put(code.getCode(), mp);
        }

        // 对每个获胜排列（含自同构）生成 p# -> 数据节点 id 的映射
        for (int[] order : result.getWinningOrders()) {
            if (mp.mappings.size() >= embeddingsPerPatternLimit) {
                return;
            }
            Map<String, String> mapping = new HashMap<>(order.length * 2);
            for (int canonical = 0; canonical < order.length; canonical++) {
                int dataVertex = localVertex.get(order[canonical]);
                mapping.put(Canonicalizer.patternNodeId(canonical), nodes.get(dataVertex).getId());
            }
            mp.addMapping(mapping, embeddingsPerPatternLimit);
        }
    }

    /** 可变模式累加器。 */
    private static final class MutablePattern {
        private final CanonicalCode code;
        private final java.util.Set<Map<String, String>> mappings = new java.util.LinkedHashSet<>();

        private MutablePattern(CanonicalCode code) {
            this.code = code;
        }

        private void addMapping(Map<String, String> mapping, int limit) {
            if (mappings.size() < limit) {
                mappings.add(mapping);
            }
        }
    }
}
