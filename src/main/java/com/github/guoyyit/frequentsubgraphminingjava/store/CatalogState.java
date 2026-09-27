package com.github.guoyyit.frequentsubgraphminingjava.store;

import com.github.guoyyit.frequentsubgraphminingjava.graph.PropertyGraph;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图目录的已提交状态：单调版本号 + 当前图集合。
 */
public final class CatalogState {

    private long version;
    private Map<String, PropertyGraph> graphs = new LinkedHashMap<>();

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Map<String, PropertyGraph> getGraphs() {
        return graphs;
    }

    public void setGraphs(Map<String, PropertyGraph> graphs) {
        this.graphs = graphs;
    }
}
