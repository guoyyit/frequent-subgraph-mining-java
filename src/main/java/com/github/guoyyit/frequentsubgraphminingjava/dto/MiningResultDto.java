package com.github.guoyyit.frequentsubgraphminingjava.dto;

import java.util.List;

public record MiningResultDto(long version,
                              int totalGraphs,
                              boolean truncated,
                              String limitReason,
                              int scannedCandidates,
                              long elapsedMillis,
                              List<PatternMatchDto> patterns) {
}
