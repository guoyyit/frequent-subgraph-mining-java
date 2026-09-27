package com.github.guoyyit.frequentsubgraphminingjava.store;

import com.github.guoyyit.frequentsubgraphminingjava.graph.PropertyGraph;

/**
 * 一次图集合变更：操作类型、图名、新图内容以及变更前的旧图内容。
 * 旧图内容用于增量重算（崩溃恢复时同样需要）。
 */
public final class CatalogChange {

    private long catalogVersion;
    private String operation;
    private String graphName;
    private PropertyGraph newGraph;
    private PropertyGraph oldGraph;

    public CatalogChange() {
    }

    public long getCatalogVersion() {
        return catalogVersion;
    }

    public void setCatalogVersion(long catalogVersion) {
        this.catalogVersion = catalogVersion;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getGraphName() {
        return graphName;
    }

    public void setGraphName(String graphName) {
        this.graphName = graphName;
    }

    public PropertyGraph getNewGraph() {
        return newGraph;
    }

    public void setNewGraph(PropertyGraph newGraph) {
        this.newGraph = newGraph;
    }

    public PropertyGraph getOldGraph() {
        return oldGraph;
    }

    public void setOldGraph(PropertyGraph oldGraph) {
        this.oldGraph = oldGraph;
    }
}
