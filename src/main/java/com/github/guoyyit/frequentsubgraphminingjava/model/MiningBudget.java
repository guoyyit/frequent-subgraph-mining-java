package com.github.guoyyit.frequentsubgraphminingjava.model;

/**
 * Hard resource limits for a mining run: result count, candidate count and wall time.
 */
public record MiningBudget(int maxResultPatterns, int maxCandidates, long timeoutMillis) {

    public static MiningBudget unbounded() {
        return new MiningBudget(Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE);
    }
}
