package com.github.guoyyit.frequentsubgraphminingjava.store;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个挖掘域中一个模式的持久化状态：支持图列表（图级支持度）。
 */
public final class PatternEntry {

    private String canonicalCode;
    private int edgeCount;
    private List<String> supportingGraphs = new ArrayList<>();

    public PatternEntry() {
    }

    public String getCanonicalCode() {
        return canonicalCode;
    }

    public void setCanonicalCode(String canonicalCode) {
        this.canonicalCode = canonicalCode;
    }

    public int getEdgeCount() {
        return edgeCount;
    }

    public void setEdgeCount(int edgeCount) {
        this.edgeCount = edgeCount;
    }

    public List<String> getSupportingGraphs() {
        return supportingGraphs;
    }

    public void setSupportingGraphs(List<String> supportingGraphs) {
        this.supportingGraphs = supportingGraphs;
    }
}
