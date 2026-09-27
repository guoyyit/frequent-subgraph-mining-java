package com.github.guoyyit.frequentsubgraphminingjava.model;

import java.util.Collections;
import java.util.Map;

/**
 * One embedding of a pattern inside a host graph:
 * maps canonical pattern node index -> host graph node id.
 */
public record Embedding(String graphId, Map<Integer, String> nodeMapping) {

    public Embedding {
        nodeMapping = Collections.unmodifiableMap(nodeMapping);
    }
}
