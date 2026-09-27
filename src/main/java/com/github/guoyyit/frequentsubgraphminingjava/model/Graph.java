package com.github.guoyyit.frequentsubgraphminingjava.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable labeled property graph (only node/edge labels are used by the miner).
 */
public final class Graph {

    private final String id;
    private final Map<String, String> nodeLabels;
    private final List<Edge> edges;

    public Graph(String id, Map<String, String> nodeLabels, List<Edge> edges) {
        this.id = id;
        this.nodeLabels = nodeLabels == null ? new LinkedHashMap<>() : new LinkedHashMap<>(nodeLabels);
        this.edges = edges == null ? new ArrayList<>() : new ArrayList<>(edges);
    }

    public String getId() {
        return id;
    }

    public Map<String, String> getNodeLabels() {
        return Collections.unmodifiableMap(nodeLabels);
    }

    public List<Edge> getEdges() {
        return Collections.unmodifiableList(edges);
    }

    public static Graph empty(String id) {
        return new Graph(id, Map.of(), List.of());
    }

    /** A directed labeled edge. */
    public record Edge(String source, String target, String label) {
    }
}
