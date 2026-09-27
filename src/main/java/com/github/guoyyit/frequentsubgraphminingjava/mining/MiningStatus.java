package com.github.guoyyit.frequentsubgraphminingjava.mining;

/**
 * 挖掘执行结束状态。
 */
public enum MiningStatus {

    /** 正常完成，结果完整。 */
    COMPLETE,

    /** 达到结果数量或枚举上限而提前终止，结果可能不完整。 */
    RESULT_LIMIT_REACHED,

    /** 达到执行时间上限而提前终止，结果可能不完整。 */
    TIMEOUT
}
