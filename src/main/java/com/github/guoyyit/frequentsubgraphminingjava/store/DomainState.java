package com.github.guoyyit.frequentsubgraphminingjava.store;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个挖掘域（配置签名）的已提交增量状态：
 * 域已处理到的目录版本 + 模式 -> 支持图集合。
 */
public final class DomainState {

    private String domainSignature;
    private long catalogVersion;
    private Map<String, PatternEntry> patterns = new LinkedHashMap<>();

    public String getDomainSignature() {
        return domainSignature;
    }

    public void setDomainSignature(String domainSignature) {
        this.domainSignature = domainSignature;
    }

    public long getCatalogVersion() {
        return catalogVersion;
    }

    public void setCatalogVersion(long catalogVersion) {
        this.catalogVersion = catalogVersion;
    }

    public Map<String, PatternEntry> getPatterns() {
        return patterns;
    }

    public void setPatterns(Map<String, PatternEntry> patterns) {
        this.patterns = patterns;
    }
}
