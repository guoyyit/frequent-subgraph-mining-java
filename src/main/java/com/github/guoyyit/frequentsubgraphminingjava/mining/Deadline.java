package com.github.guoyyit.frequentsubgraphminingjava.mining;

/**
 * 执行截止时间，用于组合爆炸场景下的超时终止控制。
 */
public final class Deadline {

    private final long deadlineNanos;

    private Deadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public static Deadline after(long millis) {
        long bounded = Math.max(millis, 1L);
        return new Deadline(System.nanoTime() + bounded * 1_000_000L);
    }

    public boolean isExpired() {
        return System.nanoTime() >= deadlineNanos;
    }

    public long remainingMillis() {
        return Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
    }

    /**
     * 在枚举热点路径上周期性调用，超时即抛出 {@link MiningTimeoutException}。
     */
    public void checkpoint() {
        if (isExpired()) {
            throw new MiningTimeoutException("挖掘执行超过时间上限");
        }
    }
}
