package org.moboxlab.mbb.eval.score;

import org.moboxlab.mbb.eval.runner.EvalPreflightPlan;

/**
 * 调用预算余量（D-4：规格 §15.4 D19）。
 *
 * <p>full 档预检计划为生成 40 + judge ≤36 + 校准 3 = 79 次，硬顶 100 次，因此默认预留
 * 21 次重试余量。本类不消耗预算，只做记账校验：已用不得超过硬顶；达到硬顶后
 * {@link #canCall()} 为 false，未判题只能记 error，不得再发起模型调用。</p>
 */
public final class EvalBudgetMargin {

    private final int used;
    private final int planned;
    private final int hardCap;

    private EvalBudgetMargin(int used, int planned, int hardCap) {
        this.used = used;
        this.planned = planned;
        this.hardCap = hardCap;
    }

    /** full 档默认计划 79、硬顶 100。 */
    public static EvalBudgetMargin full(int used) {
        return of(used, EvalPreflightPlan.PLANNED_CALLS_FULL, EvalPreflightPlan.MAX_CALL_BUDGET);
    }

    /**
     * 校验一份预算台账。
     *
     * @param used    已用调用次数，不得为负、不得超过硬顶
     * @param planned 预检计划调用次数，不得为负且不得超过硬顶
     * @param hardCap 硬顶，必须为正
     */
    public static EvalBudgetMargin of(int used, int planned, int hardCap) {
        if (hardCap <= 0) {
            throw new IllegalArgumentException("硬顶必须为正：" + hardCap);
        }
        if (planned < 0 || planned > hardCap) {
            throw new IllegalArgumentException("计划调用次必须在 [0, " + hardCap + "]：" + planned);
        }
        if (used < 0 || used > hardCap) {
            throw new IllegalArgumentException("已用调用次必须在 [0, " + hardCap + "]：" + used);
        }
        return new EvalBudgetMargin(used, planned, hardCap);
    }

    public int used() {
        return used;
    }

    public int planned() {
        return planned;
    }

    public int hardCap() {
        return hardCap;
    }

    /** 计划内余量 = planned - used；超计划时为负。 */
    public int remaining() {
        return planned - used;
    }

    /** 距硬顶还剩多少次调用，不会为负。 */
    public int remainingBeforeHardCap() {
        return hardCap - used;
    }

    /** 是否仍在预检计划内。 */
    public boolean withinPlan() {
        return used <= planned;
    }

    /** 是否未超过硬顶。 */
    public boolean withinHardCap() {
        return used <= hardCap;
    }

    /** 是否还允许发起下一次调用；达到硬顶即 false（D19）。 */
    public boolean canCall() {
        return used < hardCap;
    }

    /** 中文记账摘要，供报表与日志直接使用。 */
    public String summary() {
        if (withinPlan()) {
            return "预算 " + planned + "，已用 " + used + "，余量 " + remaining();
        }
        return "预算 " + planned + "，已用 " + used + "，超计划 " + (-remaining())
                + "（距硬顶 " + remainingBeforeHardCap() + "）";
    }
}
