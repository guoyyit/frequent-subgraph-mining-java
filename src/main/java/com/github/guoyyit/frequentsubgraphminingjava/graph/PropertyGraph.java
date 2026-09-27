package com.github.guoyyit.frequentsubgraphminingjava.graph;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 属性图：节点与边均带标签。当前挖掘只使用节点标签与边标签，
 * 其他属性可在后续版本扩展。
 */
public final class PropertyGraph {

    private final String name;
    private final List<LabeledNode> nodes = new ArrayList<>();
    private final List<LabeledEdge> edges = new ArrayList<>();

    public PropertyGraph(String name) {
        this.name = name;
    }

    @JsonCreator
    public PropertyGraph(@JsonProperty("name") String name,
                         @JsonProperty("nodes") List<LabeledNode> nodes,
                         @JsonProperty("edges") List<LabeledEdge> edges) {
        this.name = name;
        if (nodes != null) {
            this.nodes.addAll(nodes);
        }
        if (edges != null) {
            this.edges.addAll(edges);
        }
    }

    public String getName() {
        return name;
    }

    public List<LabeledNode> getNodes() {
        return nodes;
    }

    public List<LabeledEdge> getEdges() {
        return edges;
    }

    public LabeledNode node(String id) {
        for (LabeledNode node : nodes) {
            if (node.getId().equals(id)) {
                return node;
            }
        }
        return null;
    }

    public void addNode(LabeledNode node) {
        if (node == null || node.getId() == null || node.getLabel() == null) {
            throw new IllegalArgumentException("节点及其 id、label 均不能为空");
        }
        if (node(node.getId()) != null) {
            throw new IllegalArgumentException("图 " + name + " 中已存在节点 " + node.getId());
        }
        nodes.add(node);
    }

    public void addEdge(LabeledEdge edge) {
        if (edge == null || edge.getSource() == null || edge.getTarget() == null || edge.getLabel() == null) {
            throw new IllegalArgumentException("边及其端点、label 均不能为空");
        }
        if (node(edge.getSource()) == null || node(edge.getTarget()) == null) {
            throw new IllegalArgumentException(
                    "图 " + name + " 中边的端点不存在: " + edge.getSource() + " -> " + edge.getTarget());
        }
        edges.add(edge);
    }

    /**
     * 深拷贝，保证快照与持久化对象不受后续修改影响。
     */
    public PropertyGraph copy() {
        PropertyGraph clone = new PropertyGraph(name);
        for (LabeledNode node : nodes) {
            clone.nodes.add(new LabeledNode(node.getId(), node.getLabel()));
        }
        for (LabeledEdge edge : edges) {
            clone.edges.add(new LabeledEdge(edge.getSource(), edge.getTarget(), edge.getLabel()));
        }
        return clone;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PropertyGraph that)) {
            return false;
        }
        return Objects.equals(name, that.name)
                && Objects.equals(nodes, that.nodes)
                && Objects.equals(edges, that.edges);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, nodes, edges);
    }
}
