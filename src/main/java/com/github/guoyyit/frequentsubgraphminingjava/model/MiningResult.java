package com.github.guoyyit.frequentsubgraphminingjava.model;

import java.util.List;
import java.util.Map;

/**
 * Result of a mining query: frequent patterns with support and embeddings,
 * plus status flags indicating whether resource limits were hit.
 */
public record MiningResult(List<PatternMatch> patterns,
                           int totalGraphs,
                           MiningConfig config,
                           boolean truncated,
                           String limitReason,
                           int scannedCandidates,
                           long elapsedMillis) {

    public record PatternMatch(Pattern pattern,
                               int support,
                               List<Embedding> embeddings) implements Comparable<PatternMatch> {

        @Override
        public int compareTo(PatternMatch other) {
            int byKey = this.pattern.compareTo(other.pattern);
            if (byKey != 0) {
                return byKey;
            }
            return Integer.compare(this.support, other.support);
        }
    }
}
