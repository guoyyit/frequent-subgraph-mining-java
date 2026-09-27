package com.github.guoyyit.frequentsubgraphminingjava.mining;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次挖掘查询的完整返回：状态、模式列表与规模统计。
 */
public final class MiningOutcome {

    private MiningStatus status = MiningStatus.COMPLETE;
    private List<PatternResult> patterns = new ArrayList<>();
    private long catalogVersion;
    private int graphCount;
    private long elapsedMillis;
    private String message;

    public MiningStatus getStatus() {
        return status;
    }

    public void setStatus(MiningStatus status) {
        this.status = status;
    }

    public List<PatternResult> getPatterns() {
        return patterns;
    }

    public void setPatterns(List<PatternResult> patterns) {
        this.patterns = patterns;
    }

    public int getGraphCount() {
        return graphCount;
    }

    public void setGraphCount(int graphCount) {
        this.graphCount = graphCount;
    }

    public long getCatalogVersion() {
        return catalogVersion;
    }

    public void setCatalogVersion(long catalogVersion) {
        this.catalogVersion = catalogVersion;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public void setElapsedMillis(long elapsedMillis) {
        this.elapsedMillis = elapsedMillis;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
