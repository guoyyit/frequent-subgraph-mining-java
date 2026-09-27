package com.github.guoyyit.frequentsubgraphminingjava.graph;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 带标签的有向边（source -> target）。
 */
public final class LabeledEdge {

    private final String source;
    private final String target;
    private final String label;

    @JsonCreator
    public LabeledEdge(@JsonProperty("source") String source,
                       @JsonProperty("target") String target,
                       @JsonProperty("label") String label) {
        this.source = source;
        this.target = target;
        this.label = label;
    }

    public String getSource() {
        return source;
    }

    public String getTarget() {
        return target;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LabeledEdge that)) {
            return false;
        }
        return Objects.equals(source, that.source)
                && Objects.equals(target, that.target)
                && Objects.equals(label, that.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, target, label);
    }

    @Override
    public String toString() {
        return source + " -[" + label + "]-> " + target;
    }
}
