package com.github.guoyyit.frequentsubgraphminingjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fsm")
public class MiningProperties {

    private int minSupport = 2;
    private int maxEdges = 5;
    private long snapshotEvery = 100;
    private String dataDir = "data/fsm";

    public int getMinSupport() {
        return minSupport;
    }

    public void setMinSupport(int minSupport) {
        this.minSupport = minSupport;
    }

    public int getMaxEdges() {
        return maxEdges;
    }

    public void setMaxEdges(int maxEdges) {
        this.maxEdges = maxEdges;
    }

    public long getSnapshotEvery() {
        return snapshotEvery;
    }

    public void setSnapshotEvery(long snapshotEvery) {
        this.snapshotEvery = snapshotEvery;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }
}
