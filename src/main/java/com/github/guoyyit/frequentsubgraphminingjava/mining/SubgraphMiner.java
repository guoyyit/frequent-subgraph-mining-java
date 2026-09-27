package com.github.guoyyit.frequentsubgraphminingjava.mining;

import com.github.guoyyit.frequentsubgraphminingjava.model.Embedding;
import com.github.guoyyit.frequentsubgraphminingjava.model.Graph;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningBudget;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningConfig;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningResult;
import com.github.guoyyit.frequentsubgraphminingjava.model.Pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Full frequent-subgraph mining over an immutable snapshot of the graph collection.
 *
 * <p>Each graph is enumerated independently with {@link SingleGraphPatterns}; the
 * {@link PatternIndex} aggregates per-graph occurrences. The same index is what the
 * incremental service maintains, so incremental results are identical to a full
 * re-mining by construction. Graphs are processed in id order and patterns returned
 * in canonical-key order for deterministic output.
 */
public final class SubgraphMiner {

    /**
     * @param candidateSink optional per-graph enumeration counter output wrapper;
     *                      may be {@code null}
     */
    public MiningResult mine(Map<String, Graph> graphs,
                             MiningConfig config,
                             MiningBudget budget) {
        long start = System.nanoTime();
        PatternIndex index = new PatternIndex();
        SingleGraphPatterns enumerator = new SingleGraphPatterns();

        List<String> graphIds = new ArrayList<>(graphs.keySet());
        java.util.Collections.sort(graphIds);

        boolean truncated = false;
        String limitReason = null;
        long scannedCandidates = 0;

        for (String graphId : graphIds) {
            Graph graph = graphs.get(graphId);
            Map<Pattern, List<Embedding>> patterns = enumerator.enumerate(graph, config, budget);
            scannedCandidates += patterns.size();
            index = index.withGraphReplaced(graph, patterns);

            if (enumerator.limitReached()) {
                truncated = true;
                limitReason = "single-graph enumeration budget exhausted (maxCandidates/timeout/maxResultPatterns)";
                break;
            }
            if (index.patternCount() > budget.maxResultPatterns()) {
                truncated = true;
                limitReason = "maxResultPatterns exceeded";
                break;
            }
        }

        List<MiningResult.PatternMatch> matches = new ArrayList<>();
        for (Pattern pattern : index.allPatterns()) {
            if (pattern.getEdgeCount() > config.getMaxEdges()) {
                continue;
            }
            int support = index.support(pattern);
            if (support < config.getMinSupport()) {
                continue;
            }
            List<Embedding> embeddings = index.allEmbeddings(pattern);
            matches.add(new MiningResult.PatternMatch(pattern, support, embeddings));
        }

        if (matches.size() > budget.maxResultPatterns()) {
            matches = new ArrayList<>(matches.subList(0, budget.maxResultPatterns()));
            truncated = true;
            limitReason = "maxResultPatterns exceeded";
        }

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        return new MiningResult(List.copyOf(matches), graphs.size(), config,
                truncated, limitReason, (int) Math.min(scannedCandidates, Integer.MAX_VALUE),
                elapsedMillis);
    }
}
