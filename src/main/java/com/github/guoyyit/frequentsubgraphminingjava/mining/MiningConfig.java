package com.github.guoyyit.frequentsubgraphminingjava.mining;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 挖掘配置：最小支持度、最大模式边数、允许的节点/边标签集合及规模上限。
 *
 * <p>允许的标签集合为空表示不限制（接受任意标签）。
 */
public final class MiningConfig {

    private int minSupport = 1;
    private int maxPatternEdges = 5;
    private Set<String> allowedNodeLabels = new HashSet<>();
    private Set<String> allowedEdgeLabels = new HashSet<>();
    private int maxResults = 1000;
    private int maxEmbeddingsPerPattern = 100;
    private int maxNodesPerGraph = 50;
    private int maxEdgesPerGraph = 200;
    private long timeoutMillis = 10_000L;

    public int getMinSupport() {
        return minSupport;
    }

    public void setMinSupport(int minSupport) {
        this.minSupport = minSupport;
    }

    public int getMaxPatternEdges() {
        return maxPatternEdges;
    }

    public void setMaxPatternEdges(int maxPatternEdges) {
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

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    public int getMaxEmbeddingsPerPattern() {
        return maxEmbeddingsPerPattern;
    }

    public void setMaxEmbeddingsPerPattern(int maxEmbeddingsPerPattern) {
        this.maxEmbeddingsPerPattern = maxEmbeddingsPerPattern;
    }

    public int getMaxNodesPerGraph() {
        return maxNodesPerGraph;
    }

    public void setMaxNodesPerGraph(int maxNodesPerGraph) {
        this.maxNodesPerGraph = maxNodesPerGraph;
    }

    public int getMaxEdgesPerGraph() {
        return maxEdgesPerGraph;
    }

    public void setMaxEdgesPerGraph(int maxEdgesPerGraph) {
        this.maxEdgesPerGraph = maxEdgesPerGraph;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public void validate() {
        if (minSupport < 1) {
            throw new IllegalArgumentException("minSupport 必须 >= 1");
        }
        if (maxPatternEdges < 1) {
            throw new IllegalArgumentException("maxPatternEdges 必须 >= 1");
        }
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults 必须 >= 1");
        }
        if (maxEmbeddingsPerPattern < 1) {
            throw new IllegalArgumentException("maxEmbeddingsPerPattern 必须 >= 1");
        }
        if (maxNodesPerGraph < 1) {
            throw new IllegalArgumentException("maxNodesPerGraph 必须 >= 1");
        }
        if (maxEdgesPerGraph < 0) {
            throw new IllegalArgumentException("maxEdgesPerGraph 必须 >= 0");
        }
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeoutMillis 必须 >= 1");
        }
    }

    /**
     * 判断节点标签是否被允许（允许集合为空表示不限制）。
     */
    public boolean nodeLabelAllowed(String label) {
        return allowedNodeLabels == null || allowedNodeLabels.isEmpty() || allowedNodeLabels.contains(label);
    }

    /**
     * 判断边标签是否被允许（允许集合为空表示不限制）。
     */
    public boolean edgeLabelAllowed(String label) {
        return allowedEdgeLabels == null || allowedEdgeLabels.isEmpty() || allowedEdgeLabels.contains(label);
    }

    /**
     * 用于持久化隔离不同配置域的稳定签名。
     *
     * <p>只有影响模式集合语义的字段（支持度以外的模式空间定义）参与签名：
     * 最大模式边数与允许标签集合。minSupport 只影响过滤阈值，不参与，
     * 这样同一模式空间不同支持度的查询可以复用同一份增量状态。
     */
    public String domainSignature() {
        String nodes = sortedJsonArray(allowedNodeLabels);
        String edges = sortedJsonArray(allowedEdgeLabels);
        return "mpe=" + maxPatternEdges + "|nl=" + nodes + "|el=" + edges;
    }

    /**
     * 以 JSON 数组形式编码排序后的标签集合，保证含逗号、竖线等字符的标签
     * 不会造成签名碰撞；空集合编码为 []，与 null 语义一致（均表示不限制）。
     */
    private static String sortedJsonArray(Set<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String label : new TreeSet<>(labels)) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(label));
        }
        return sb.append(']').toString();
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(ch);
            }
        }
        return sb.append('"').toString();
    }

    /**
     * 基于本配置生成一份深拷贝（避免外部集合被修改后污染配置域键）。
     */
    public MiningConfig copy() {
        MiningConfig c = new MiningConfig();
        c.minSupport = minSupport;
        c.maxPatternEdges = maxPatternEdges;
        c.allowedNodeLabels = allowedNodeLabels == null ? new HashSet<>() : new HashSet<>(allowedNodeLabels);
        c.allowedEdgeLabels = allowedEdgeLabels == null ? new HashSet<>() : new HashSet<>(allowedEdgeLabels);
        c.maxResults = maxResults;
        c.maxEmbeddingsPerPattern = maxEmbeddingsPerPattern;
        c.maxNodesPerGraph = maxNodesPerGraph;
        c.maxEdgesPerGraph = maxEdgesPerGraph;
        c.timeoutMillis = timeoutMillis;
        return c;
    }
}
