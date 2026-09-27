package com.github.guoyyit.frequentsubgraphminingjava.dto;

import java.util.Map;

public record GraphDto(String id,
                       Map<String, String> nodeLabels,
                       java.util.List<EdgeDto> edges) {
}
