package com.github.guoyyit.frequentsubgraphminingjava.dto;

import java.util.List;

public record PatternMatchDto(String canonicalKey,
                              int nodeCount,
                              int edgeCount,
                              int support,
                              List<EmbeddingDto> embeddings) {
}
