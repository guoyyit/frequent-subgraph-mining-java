package com.github.guoyyit.frequentsubgraphminingjava.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图集合：按名字索引的属性图集合。内部使用 LinkedHashMap 保证迭代顺序稳定。
 */
public final class GraphCollection {

    private final Map<String, PropertyGraph> graphs = new LinkedHashMap<>();

    public Map<String, PropertyGraph> getGraphs() {
        return graphs;
    }

    public PropertyGraph get(String name) {
        return graphs.get(name);
    }

    public void put(PropertyGraph graph) {
        if (graph == null || graph.getName() == null) {
            throw new IllegalArgumentException("图及其名字不能为空");
        }
        graphs.put(graph.getName(), graph);
    }

    public PropertyGraph remove(String name) {
        return graphs.remove(name);
    }

    public int size() {
        return graphs.size();
    }

    /**
     * 深拷贝为不可变集合，用于对外发布一致快照。
     */
    public GraphCollection immutableCopy() {
        GraphCollection copy = new GraphCollection();
        for (PropertyGraph graph : graphs.values()) {
            copy.graphs.put(graph.getName(), graph.copy());
        }
        return copy;
    }

    public Map<String, PropertyGraph> immutableGraphs() {
        return Collections.unmodifiableMap(graphs);
    }
}
