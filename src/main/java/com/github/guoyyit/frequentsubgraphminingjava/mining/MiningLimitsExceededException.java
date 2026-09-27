package com.github.guoyyit.frequentsubgraphminingjava.mining;

/**
 * 输入规模超过配置上限时抛出。
 */
public class MiningLimitsExceededException extends RuntimeException {

    public MiningLimitsExceededException(String message) {
        super(message);
    }
}
