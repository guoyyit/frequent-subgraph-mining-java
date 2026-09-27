package com.github.guoyyit.frequentsubgraphminingjava.mining;

import java.util.List;
import java.util.Objects;

/**
 * 模式的规范表示：规范字符串 + 按规范顺序排列的节点标签与边。
 * 同构模式拥有相同的规范字符串。
 */
public final class CanonicalCode {

    private final String code;
    private final List<String> nodeLabels;
    private final List<CanonicalEdge> edges;

    public CanonicalCode(String code, List<String> nodeLabels, List<CanonicalEdge> edges) {
        this.code = code;
        this.nodeLabels = nodeLabels;
        this.edges = edges;
    }

    public String getCode() {
        return code;
    }

    public List<String> getNodeLabels() {
        return nodeLabels;
    }

    public List<CanonicalEdge> getEdges() {
        return edges;
    }

    public int getEdgeCount() {
        return edges == null ? 0 : edges.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CanonicalCode that)) {
            return false;
        }
        return Objects.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(code);
    }

    @Override
    public String toString() {
        return code;
    }
}
