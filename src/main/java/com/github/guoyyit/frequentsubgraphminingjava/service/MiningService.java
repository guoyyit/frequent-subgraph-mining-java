package com.github.guoyyit.frequentsubgraphminingjava.service;

import com.github.guoyyit.frequentsubgraphminingjava.graph.GraphCollection;
import com.github.guoyyit.frequentsubgraphminingjava.mining.MiningConfig;
import com.github.guoyyit.frequentsubgraphminingjava.mining.MiningOutcome;
import com.github.guoyyit.frequentsubgraphminingjava.store.PersistenceStore;

/**
 * 频繁子图挖掘服务：图集合变更、增量挖掘查询、快照一致性与崩溃恢复。
 */
public interface MiningService {

    /**
     * 提交一次图集合变更（ADD / UPDATE / DELETE），持久化后原子生效。
     */
    long commitChange(GraphChangeRequest request);

    /**
     * 在当前一致快照上执行挖掘查询；首次查询或有未追赶变更时增量补齐。
     */
    MiningOutcome query(MiningQuery query);

    /**
     * 当前图集合的一致快照副本。
     */
    GraphCollection snapshot();

    /**
     * 当前已提交目录版本。
     */
    long currentVersion();

    /**
     * 使用给定配置构建挖掘配置对象（应用默认值并校验）。
     */
    MiningConfig resolveConfig(MiningQuery query);
}
