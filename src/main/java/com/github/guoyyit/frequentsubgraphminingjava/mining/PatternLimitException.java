package com.github.guoyyit.frequentsubgraphminingjava.mining;

/**
 * 模式枚举数量超过配置上限（组合爆炸保护）。
 */
public class PatternLimitException extends RuntimeException {

    public PatternLimitException(String message) {
        super(message);
    }
}
