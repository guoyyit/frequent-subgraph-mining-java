package com.github.guoyyit.frequentsubgraphminingjava.service;

import java.util.Set;

/**
 * 挖掘查询参数；为空的字段使用服务默认值。
 */
public final class MiningQuery {

    private Integer minSupport;
    private Integer maxPatternEdges;
    private Set<String> allowedNodeLabels;
    private Set<String> allowedEdgeLabels;
    private Integer maxResults;
    private Integer maxEmbeddingsPerPattern;
    private Long timeoutMillis;

    public Integer getMinSupport() {
        return minSupport;
    }

    public void setMinSupport(Integer minSupport) {
        this.minSupport = minSupport;
    }

    public Integer getMaxPatternEdges() {
        return maxPatternEdges;
    }

    public void setMaxPatternEdges(Integer maxPatternEdges) {
        this.maxPatternEdges = maxPatternEdges;
    }

    public Set<String> getAllowedNodeLabels() {
        return allowedNodeLabels;
    }

    public void setAllowedNodeLabels(Set<String> allowedNodeLabels) {
        this.allowedNodeLabels = allowedNodeLabels;
    }

    public Set<String> getAllowedEdgeLabels() {
        return allowedEdgeLabels;
    }

    public void setAllowedEdgeLabels(Set<String> allowedEdgeLabels) {
        this.allowedEdgeLabels = allowedEdgeLabels;
    }

    public Integer getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }

    public Integer getMaxEmbeddingsPerPattern() {
        return maxEmbeddingsPerPattern;
    }

    public void setMaxEmbeddingsPerPattern(Integer maxEmbeddingsPerPattern) {
        this.maxEmbeddingsPerPattern = maxEmbeddingsPerPattern;
    }

    public Long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(Long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }
}
