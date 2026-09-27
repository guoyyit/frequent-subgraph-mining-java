package com.github.guoyyit.frequentsubgraphminingjava.mining;

import com.github.guoyyit.frequentsubgraphminingjava.graph.GraphCollection;
import com.github.guoyyit.frequentsubgraphminingjava.graph.PropertyGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 频繁子图挖掘器：对图集合执行全量挖掘，聚合每个模式的图级支持度。
 *
 * <p>支持度采用图级支持度（graph-level support）：一个模式在同一张数据图中
 * 无论出现多少次，只计 1；支持计数 = 出现该模式的不同数据图数量。
 *
 * <p>结果按（边数升序、规范字符串升序）稳定排序，保证相同输入与配置下
 * 多次执行结果完全一致。达到结果数量上限或超时时返回明确的
 * {@link MiningStatus}，结果可能截断但不会无限制消耗资源。
 */
public final class FrequentSubgraphMiner {

    private final MiningConfig config;

    public FrequentSubgraphMiner(MiningConfig config) {
        this.config = config;
        config.validate();
    }

    public MiningOutcome mine(GraphCollection collection) {
        return mine(collection, Deadline.after(config.getTimeoutMillis()));
    }

    public MiningOutcome mine(GraphCollection collection, Deadline deadline) {
        long start = System.nanoTime();
        Map<String, Aggregated> aggregated = new LinkedHashMap<>();
        boolean resultLimited = false;
        boolean timedOut = false;

        // GraphCollection 以 LinkedHashMap 维护，迭代顺序 = 插入顺序（提交顺序），保证稳定
        try {
            for (PropertyGraph graph : collection.getGraphs().values()) {
                validateGraphSize(graph);
                deadline.checkpoint();
                Map<String, EnumeratedPattern> found;
                try {
                    found = new PatternEnumerator(graph, config).enumerate(deadline);
                } catch (PatternLimitException e) {
                    resultLimited = true;
                    break;
                }
                for (EnumeratedPattern ep : found.values()) {
                    Aggregated agg = aggregated.get(ep.getCode().getCode());
                    if (agg == null) {
                        if (aggregated.size() >= config.getMaxResults()) {
                            // 不同模式数量已达结果上限：停止进一步扩展
                            resultLimited = true;
                            continue;
                        }
                        agg = new Aggregated(ep.getCode());
                        aggregated.put(ep.getCode().getCode(), agg);
                    }
                    // 图级支持度：同一张图只计一次
                    if (agg.supportingGraphs.add(graph.getName())) {
                        for (Map<String, String> mapping : ep.getMappings()) {
                            if (agg.embeddings.size() >= config.getMaxEmbeddingsPerPattern()) {
                                break;
                            }
                            agg.embeddings.add(new Embedding(graph.getName(), new LinkedHashMap<>(mapping)));
                        }
                    }
                }
            }
        } catch (MiningTimeoutException e) {
            timedOut = true;
        }

        // 过滤满足最小支持度的模式
        List<Aggregated> frequent = new ArrayList<>();
        for (Aggregated agg : aggregated.values()) {
            if (agg.supportingGraphs.size() >= config.getMinSupport()) {
                frequent.add(agg);
            }
        }
        // 稳定排序：边数升序，规范字符串升序
        frequent.sort((a, b) -> {
            if (a.code.getEdgeCount() != b.code.getEdgeCount()) {
                return Integer.compare(a.code.getEdgeCount(), b.code.getEdgeCount());
            }
            return a.code.getCode().compareTo(b.code.getCode());
        });

        List<PatternResult> patterns = new ArrayList<>(frequent.size());
        for (Aggregated agg : frequent) {
            PatternResult pr = new PatternResult();
            pr.setCanonicalCode(agg.code.getCode());
            pr.setEdgeCount(agg.code.getEdgeCount());
            pr.setSupport(agg.supportingGraphs.size());
            // 嵌入按图名、映射内容稳定排序，保证可复现
            agg.embeddings.sort((x, y) -> {
                int c = x.getGraphName().compareTo(y.getGraphName());
                if (c != 0) {
                    return c;
                }
                return x.getNodeMapping().toString().compareTo(y.getNodeMapping().toString());
            });
            pr.setEmbeddings(agg.embeddings);
            patterns.add(pr);
        }

        MiningOutcome outcome = new MiningOutcome();
        outcome.setPatterns(patterns);
        outcome.setGraphCount(collection.size());
        outcome.setElapsedMillis((System.nanoTime() - start) / 1_000_000L);
        if (timedOut) {
            outcome.setStatus(MiningStatus.TIMEOUT);
            outcome.setMessage("达到执行时间上限，结果可能不完整");
        } else if (resultLimited) {
            outcome.setStatus(MiningStatus.RESULT_LIMIT_REACHED);
            outcome.setMessage("达到结果数量或枚举规模上限，结果可能不完整");
        } else {
            outcome.setStatus(MiningStatus.COMPLETE);
            outcome.setMessage("OK");
        }
        return outcome;
    }

    /**
     * 校验单图节点/边规模上限，超限直接给出明确错误，避免组合爆炸。
     */
    private void validateGraphSize(PropertyGraph graph) {
        if (graph.getNodes().size() > config.getMaxNodesPerGraph()) {
            throw new MiningLimitsExceededException(
                    "图 " + graph.getName() + " 节点数 " + graph.getNodes().size()
                            + " 超过上限 " + config.getMaxNodesPerGraph());
        }
        if (graph.getEdges().size() > config.getMaxEdgesPerGraph()) {
            throw new MiningLimitsExceededException(
                    "图 " + graph.getName() + " 边数 " + graph.getEdges().size()
                            + " 超过上限 " + config.getMaxEdgesPerGraph());
        }
    }

    /** 聚合中的模式累加器。 */
    private static final class Aggregated {
        private final CanonicalCode code;
        private final java.util.Set<String> supportingGraphs = new java.util.LinkedHashSet<>();
        private final List<Embedding> embeddings = new ArrayList<>();

        private Aggregated(CanonicalCode code) {
            this.code = code;
        }
    }
}
