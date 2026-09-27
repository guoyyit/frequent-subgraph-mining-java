package com.github.guoyyit.frequentsubgraphminingjava.mining;

/**
 * 挖掘执行超过时间上限。
 */
public class MiningTimeoutException extends RuntimeException {

    public MiningTimeoutException(String message) {
        super(message);
    }
}
