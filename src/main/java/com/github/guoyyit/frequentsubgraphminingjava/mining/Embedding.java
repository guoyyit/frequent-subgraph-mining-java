package com.github.guoyyit.frequentsubgraphminingjava.mining;

import java.util.Collections;
import java.util.Map;

/**
 * 单个模式在单个数据图中的一次嵌入：模式节点 id 到数据图节点 id 的映射。
 */
public final class Embedding {

    private final String graphName;
    private final Map<String, String> nodeMapping;

    public Embedding(String graphName, Map<String, String> nodeMapping) {
        this.graphName = graphName;
        this.nodeMapping = nodeMapping;
    }

    public String getGraphName() {
        return graphName;
    }

    public Map<String, String> getNodeMapping() {
        return nodeMapping == null ? Collections.emptyMap() : nodeMapping;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Embedding that)) {
            return false;
        }
        return graphName.equals(that.graphName) && getNodeMapping().equals(that.getNodeMapping());
    }

    @Override
    public int hashCode() {
        return 31 * graphName.hashCode() + getNodeMapping().hashCode();
    }

    @Override
    public String toString() {
        return graphName + ":" + getNodeMapping();
    }
}
