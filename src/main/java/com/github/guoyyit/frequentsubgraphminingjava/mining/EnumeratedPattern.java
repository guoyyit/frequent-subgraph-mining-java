package com.github.guoyyit.frequentsubgraphminingjava.mining;

import java.util.List;
import java.util.Map;

/**
 * 在单个数据图中枚举出的一个模式：规范编码与全部（去重后的）节点映射。
 * 映射的 key 为规范模式节点 id（p0、p1……），value 为数据图节点 id。
 */
public final class EnumeratedPattern {

    private final CanonicalCode code;
    private final List<Map<String, String>> mappings;

    public EnumeratedPattern(CanonicalCode code, List<Map<String, String>> mappings) {
        this.code = code;
        this.mappings = mappings;
    }

    public CanonicalCode getCode() {
        return code;
    }

    public List<Map<String, String>> getMappings() {
        return mappings;
    }
}
