package com.github.guoyyit.frequentsubgraphminingjava.dto;

import java.util.Set;

public record MiningQueryDto(Integer minSupport,
                             Integer maxEdges,
                             Set<String> allowedNodeLabels,
                             Set<String> allowedEdgeLabels,
                             Integer maxResultPatterns,
                             Integer maxCandidates,
                             Long timeoutMillis) {
}
