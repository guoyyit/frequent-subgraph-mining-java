package com.github.guoyyit.frequentsubgraphminingjava.model;

import java.util.Set;

/**
 * Mining constraints: minimum support, maximum pattern edge count and allowed label sets.
 * An empty/null allowed set means no restriction.
 */
public final class MiningConfig {

    private final int minSupport;
    private final int maxEdges;
    private final Set<String> allowedNodeLabels;
    private final Set<String> allowedEdgeLabels;

    public MiningConfig(int minSupport, int maxEdges,
                        Set<String> allowedNodeLabels, Set<String> allowedEdgeLabels) {
        this.minSupport = minSupport;
        this.maxEdges = maxEdges;
        this.allowedNodeLabels = allowedNodeLabels;
        this.allowedEdgeLabels = allowedEdgeLabels;
    }

    public int getMinSupport() {
        return minSupport;
    }

    public int getMaxEdges() {
        return maxEdges;
    }

    public Set<String> getAllowedNodeLabels() {
        return allowedNodeLabels;
    }

    public Set<String> getAllowedEdgeLabels() {
        return allowedEdgeLabels;
    }

    public static MiningConfig defaults() {
        return new MiningConfig(1, 5, Set.of(), Set.of());
    }

    /**
     * Config used when maintaining the persistent unpruned pattern index:
     * no support/label filtering. The edge depth is deliberately bounded so that
     * combinatorial growth in a single huge graph cannot exhaust memory; the bound
     * is intentionally modest ({@value #UNPRUNED_MAX_EDGES} edges), matching the
     * default query depth. A hard per-graph candidate budget is additionally applied
     * by the service; when hit, the graph is flagged as truncated.
     */
    public static final int UNPRUNED_MAX_EDGES = 5;

    public static MiningConfig unpruned() {
        return new MiningConfig(1, UNPRUNED_MAX_EDGES, Set.of(), Set.of());
    }
}
