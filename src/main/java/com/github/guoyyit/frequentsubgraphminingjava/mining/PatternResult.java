package com.github.guoyyit.frequentsubgraphminingjava.mining;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个频繁模式的挖掘结果：规范编码、支持计数与嵌入列表。
 */
public final class PatternResult {

    private String canonicalCode;
    private int support;
    private int edgeCount;
    private List<Embedding> embeddings = new ArrayList<>();

    public PatternResult() {
    }

    public String getCanonicalCode() {
        return canonicalCode;
    }

    public void setCanonicalCode(String canonicalCode) {
        this.canonicalCode = canonicalCode;
    }

    public int getSupport() {
        return support;
    }

    public void setSupport(int support) {
        this.support = support;
    }

    public int getEdgeCount() {
        return edgeCount;
    }

    public void setEdgeCount(int edgeCount) {
        this.edgeCount = edgeCount;
    }

    public List<Embedding> getEmbeddings() {
        return embeddings;
    }

    public void setEmbeddings(List<Embedding> embeddings) {
        this.embeddings = embeddings;
    }
}
