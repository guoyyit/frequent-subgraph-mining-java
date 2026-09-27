package com.github.guoyyit.frequentsubgraphminingjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 挖掘服务可配置项（前缀 mining.*）。
 */
@ConfigurationProperties(prefix = "mining")
public class MiningProperties {

    /** 持久化目录。 */
    private String storageDir = "data/mining";

    /** 默认最小支持度。 */
    private int defaultMinSupport = 1;

    /** 默认最大模式边数。 */
    private int defaultMaxPatternEdges = 5;

    /** 默认结果数量上限。 */
    private int defaultMaxResults = 1000;

    /** 默认每模式保留嵌入上限。 */
    private int defaultMaxEmbeddingsPerPattern = 100;

    /** 单图节点数上限。 */
    private int maxNodesPerGraph = 50;

    /** 单图边数上限。 */
    private int maxEdgesPerGraph = 200;

    /** 默认执行时间上限（毫秒）。 */
    private long defaultTimeoutMillis = 10_000L;

    /** WAL 累积多少个已提交事务后触发一次快照压缩。 */
    private int compactThreshold = 50;

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public int getDefaultMinSupport() {
        return defaultMinSupport;
    }

    public void setDefaultMinSupport(int defaultMinSupport) {
        this.defaultMinSupport = defaultMinSupport;
    }

    public int getDefaultMaxPatternEdges() {
        return defaultMaxPatternEdges;
    }

    public void setDefaultMaxPatternEdges(int defaultMaxPatternEdges) {
        this.defaultMaxPatternEdges = defaultMaxPatternEdges;
    }

    public int getDefaultMaxResults() {
        return defaultMaxResults;
    }

    public void setDefaultMaxResults(int defaultMaxResults) {
        this.defaultMaxResults = defaultMaxResults;
    }

    public int getDefaultMaxEmbeddingsPerPattern() {
        return defaultMaxEmbeddingsPerPattern;
    }

    public void setDefaultMaxEmbeddingsPerPattern(int defaultMaxEmbeddingsPerPattern) {
        this.defaultMaxEmbeddingsPerPattern = defaultMaxEmbeddingsPerPattern;
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

    public long getDefaultTimeoutMillis() {
        return defaultTimeoutMillis;
    }

    public void setDefaultTimeoutMillis(long defaultTimeoutMillis) {
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    public int getCompactThreshold() {
        return compactThreshold;
    }

    public void setCompactThreshold(int compactThreshold) {
        this.compactThreshold = compactThreshold;
    }
}
