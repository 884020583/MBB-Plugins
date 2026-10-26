package org.moboxlab.mbb.eval.exec;

/**
 * 单 case 执行器的时间源（便于测试固定耗时）。
 *
 * <p>只用于计算 case 的墙钟耗时 {@code duration_ms}；预算截止时间由
 * {@code call.EvalBudgetLedger} 自己掌握，不经过这里。</p>
 */
public interface EvalCaseClock {

    /** 当前毫秒时间戳。 */
    long currentTimeMillis();
}
