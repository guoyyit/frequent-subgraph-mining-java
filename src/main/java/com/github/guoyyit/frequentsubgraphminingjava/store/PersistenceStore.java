package com.github.guoyyit.frequentsubgraphminingjava.store;

/**
 * 基于本地文件的崩溃安全持久化存储。
 *
 * <p>目录提交与挖掘域追赶采用“先持久化 WAL、原子提交标记，再更新内存”的
 * 协议；快照采用临时文件 + 原子改名。恢复时重放未压缩到快照中的已提交条目，
 * 崩溃在提交之前的事务不会被重放（不会重复计数、不会丢失已提交变更）。
 */
public interface PersistenceStore {

    /**
     * 启动时从最近一致状态恢复图目录。
     */
    CatalogState loadCatalog();

    /**
     * 将一次图集合变更持久化为已提交事务（WAL + 提交标记，落盘后返回）。
     */
    void commitCatalogChange(CatalogChange change);

    /**
     * 启动或按需加载某个挖掘域的持久化状态（不存在时返回 null）。
     */
    DomainState loadDomain(String domainSignature);

    /**
     * 将某个挖掘域追赶处理一次目录变更的结果持久化为已提交事务。
     */
    void commitDomainCatchUp(String domainSignature, long appliedCatalogVersion, DomainState newState);

    /**
     * 将某个挖掘域的当前全量状态压缩为快照并截断已压缩的 WAL。
     */
    void compactDomain(String domainSignature, DomainState state);

    /**
     * 将图目录当前全量状态压缩为快照并截断已压缩的 WAL。
     */
    void compactCatalog(CatalogState state);
}
