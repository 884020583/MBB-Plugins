package org.moboxlab.mbb.eval.judge;

import org.moboxlab.mbb.eval.judge.EvalJudgeFailureTracker.Decision;

/**
 * 一次判分调用的处置结果：判分结果、是否进分母、熔断决策与 run 是否应中止。
 *
 * <p>{@code countedInDenominator()} 只在 {@code OK} 时为真；{@code invalid} 与 {@code error}
 * 都剔除维度分母并单列，区别只是 invalid 来自解析失败或截断，error 来自传输失败重试后仍失败。</p>
 */
public final class EvalJudgeOutcome {

    private final EvalJudgeResult result;
    private final Decision decision;
    private final boolean skipped;
    private final boolean aborted;
    private final String abortReason;
    private final int failStreak;

    private EvalJudgeOutcome(EvalJudgeResult result, Decision decision, boolean skipped,
            boolean aborted, String abortReason, int failStreak) {
        this.result = result;
        this.decision = decision;
        this.skipped = skipped;
        this.aborted = aborted;
        this.abortReason = abortReason;
        this.failStreak = failStreak;
    }

    static EvalJudgeOutcome of(EvalJudgeResult result, Decision decision,
            EvalJudgeFailureTracker tracker) {
        boolean aborted = decision == Decision.ABORT || tracker.isAborted();
        return new EvalJudgeOutcome(result, decision, false, aborted,
                tracker.abortReason(), tracker.streak());
    }

    /** 已熔断时短路：没有判分结果，也不再发起调用。 */
    static EvalJudgeOutcome skipped(EvalJudgeFailureTracker tracker) {
        return new EvalJudgeOutcome(null, Decision.ABORT, true, true,
                tracker.abortReason(), tracker.streak());
    }

    public EvalJudgeResult result() {
        return result;
    }

    /** 是否因已熔断而跳过判分调用。 */
    public boolean skipped() {
        return skipped;
    }

    /** 是否进维度分母；只有 OK 进分母。 */
    public boolean countedInDenominator() {
        return result != null && result.isOk();
    }

    /** 解析失败或截断，重试后仍不合法。 */
    public boolean invalid() {
        return result != null && result.isInvalid();
    }

    /** 传输失败，重试后仍失败。 */
    public boolean judgeError() {
        return result != null && result.isError();
    }

    public Decision decision() {
        return decision;
    }

    /** run 是否应中止；由 runner 接线时转换为终态原因。 */
    public boolean runShouldAbort() {
        return decision == Decision.ABORT;
    }

    public String abortReason() {
        return abortReason;
    }

    public int failStreak() {
        return failStreak;
    }

    public boolean aborted() {
        return aborted;
    }
}
