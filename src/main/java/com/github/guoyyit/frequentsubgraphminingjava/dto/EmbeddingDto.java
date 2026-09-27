package com.github.guoyyit.frequentsubgraphminingjava.dto;

import java.util.Map;

public record EmbeddingDto(String graphId, Map<String, String> nodeMapping) {
}
