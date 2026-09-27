package com.github.guoyyit.frequentsubgraphminingjava.store;

/**
 * 持久化层故障（磁盘写入失败、快照损坏等）。
 */
public class PersistenceException extends RuntimeException {

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
