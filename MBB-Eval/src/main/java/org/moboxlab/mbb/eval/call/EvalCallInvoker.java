package org.moboxlab.mbb.eval.call;

import org.moboxlab.mbb.eval.gateway.EvalGenerateRequest;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;
import org.moboxlab.mbb.eval.judge.EvalJudgeResult;

/**
 * 调用器：把预算占位、实际调用、_call 落行、重试与 cached 重采串成一段纯逻辑
 * （规格 §4.4、§4.7、§9.5、§10.6、§13.1、§13.2、§15.5 H4/H5/H6/H9）。
 *
 * <p>生成入口的顺序固定为：consume 占位 → 发起调用 → 无论成败落一行 _call →
 * 按 {@link EvalCallPolicy} 决定采信、重试或重采。每次物理调用（包括失败与重试）
 * 都各自占一次预算、各自落一行，行 attempt 是本次样本内的物理调用序号（0 起），
 * 因此 H4 的两行可以用 attempt 区分，calls_used 也恰好为 2。预算 STOP 时不发起调用、
 * 不补行，直接返回 {@link GenerateKind#BUDGET_STOPPED}，绝不超卖。</p>
 *
 * <p>重试与重采都会换成新的 sessionId：重试用 attempt 位递增（0、1、2，到 2 后回 0），
 * 重采用 sampleIndex 位递增（到 99 后回 0），两者合起来保证相邻调用 sessionId 不同，
 * 同时不越过 EvalGenerateRequest 的 attempt 上限。逻辑样本序号不变，落库行按逻辑序号归组。</p>
 *
 * <p>judge 入口只做预算占位、一次调用与一行落库：D-3 的 EvalJudge 内部自带传输/解析重试
 * 与 cached 重采，其真实调用次数通过 {@link EvalJudgeResult#attempts()} 上报。judge 行的
 * call_id 由 (run, case, stage, sampleIndex, attempt) 派生、不含维度，因此调用方必须保证
 * 同一 (case, sampleIndex) 内 attempt 唯一（通常按维度内计数），否则后写的行会覆盖先写的行。
 * 若要让 judge 的每次物理调用都逐条计入 calls_used，需要 D-3 在 chat 客户端层注入预算占位，
 * 不在本切片范围内。</p>
 */
public final class EvalCallInvoker {

    /** 生成调用的物理执行接口；测试可注入假实现。 */
    public interface GenerateCall {
        EvalGenerateResult call(GenerateTarget target);
    }

    /** 判分调用的物理执行接口；测试可注入假实现。 */
    public interface JudgeCall {
        EvalJudgeResult call();
    }

    /** 退避等待接口；测试注入空实现即可免等待。 */
    public interface Sleeper {
        void sleepSeconds(int seconds);
    }

    /** 一次生成调用的会话定位参数：逻辑样本序号用于落库，另外两位用于拼 sessionId。 */
    public static final class GenerateTarget {

        private final String runId;
        private final String caseId;
        private final int sampleIndex;
        private final int sessionSampleIndex;
        private final int attempt;

        private GenerateTarget(String runId, String caseId, int sampleIndex, int sessionSampleIndex, int attempt) {
            this.runId = runId;
            this.caseId = caseId;
            this.sampleIndex = sampleIndex;
            this.sessionSampleIndex = sessionSampleIndex;
            this.attempt = attempt;
        }

        public String runId() {
            return runId;
        }

        public String caseId() {
            return caseId;
        }

        /** 逻辑样本序号，落库行按它归组，重试与重采都不变。 */
        public int sampleIndex() {
            return sampleIndex;
        }

        /** 写入 sessionId 的采样位，cached 重采时递增。 */
        public int sessionSampleIndex() {
            return sessionSampleIndex;
        }

        /** 写入 sessionId 的尝试位，重试时递增。 */
        public int attempt() {
            return attempt;
        }

        @Override
        public String toString() {
            return "GenerateTarget{sampleIndex=" + sampleIndex + ", sessionSampleIndex=" + sessionSampleIndex
                    + ", attempt=" + attempt + "}";
        }
    }

    /** 生成入口的结果类别。 */
    public enum GenerateKind {
        /** 采信本次生成结果。 */
        OK,
        /** 本样本判 error（不可重试、重试用尽或 cached 重采用尽）。 */
        SAMPLE_ERROR,
        /** 预算触顶中止，未发起调用。 */
        BUDGET_STOPPED
    }

    /** 判分入口的结果类别。 */
    public enum JudgeKind {
        /** 判分成功。 */
        OK,
        /** 判分输出非法（截断、解析失败等），按样本 error 处理。 */
        INVALID,
        /** 判分调用失败。 */
        ERROR,
        /** 预算触顶中止，未发起调用。 */
        BUDGET_STOPPED
    }

    /** 生成入口的完整结果，供 runner 汇总与中止判断。 */
    public static final class GenerateOutcome {

        private final GenerateKind kind;
        private final EvalGenerateResult result;
        private final String message;
        private final int calls;
        private final int retries;
        private final int cachedHits;
        private final int budgetUsed;
        private final int budgetLimit;
        private final String abortReason;

        private GenerateOutcome(GenerateKind kind, EvalGenerateResult result, String message, int calls,
                int retries, int cachedHits, int budgetUsed, int budgetLimit, String abortReason) {
            this.kind = kind;
            this.result = result;
            this.message = message;
            this.calls = calls;
            this.retries = retries;
            this.cachedHits = cachedHits;
            this.budgetUsed = budgetUsed;
            this.budgetLimit = budgetLimit;
            this.abortReason = abortReason;
        }

        public GenerateKind kind() {
            return kind;
        }

        /** 最后一次物理调用的出参；预算止步时为 null。 */
        public EvalGenerateResult result() {
            return result;
        }

        public String message() {
            return message;
        }

        /** 本样本实际物理调用次数（含失败与重试）。 */
        public int calls() {
            return calls;
        }

        public int retries() {
            return retries;
        }

        public int cachedHits() {
            return cachedHits;
        }

        public int budgetUsed() {
            return budgetUsed;
        }

        public int budgetLimit() {
            return budgetLimit;
        }

        /** 仅预算止步时非空，取值为 EvalStore 的中止原因常量。 */
        public String abortReason() {
            return abortReason;
        }

        public boolean isBudgetStopped() {
            return kind == GenerateKind.BUDGET_STOPPED;
        }

        @Override
        public String toString() {
            return "GenerateOutcome{" + kind + ", calls=" + calls + ", retries=" + retries
                    + ", cachedHits=" + cachedHits + ", budget=" + budgetUsed + "/" + budgetLimit
                    + ", message=" + message + "}";
        }
    }

    /** 判分入口的完整结果。 */
    public static final class JudgeOutcome {

        private final JudgeKind kind;
        private final EvalJudgeResult result;
        private final String message;
        private final long elapsedMs;
        private final int budgetUsed;
        private final int budgetLimit;
        private final String abortReason;

        private JudgeOutcome(JudgeKind kind, EvalJudgeResult result, String message, long elapsedMs,
                int budgetUsed, int budgetLimit, String abortReason) {
            this.kind = kind;
            this.result = result;
            this.message = message;
            this.elapsedMs = elapsedMs;
            this.budgetUsed = budgetUsed;
            this.budgetLimit = budgetLimit;
            this.abortReason = abortReason;
        }

        public JudgeKind kind() {
            return kind;
        }

        /** 判分出参；预算止步时为 null。 */
        public EvalJudgeResult result() {
            return result;
        }

        public String message() {
            return message;
        }

        /** 判分墙钟耗时，预算止步时为 0。 */
        public long elapsedMs() {
            return elapsedMs;
        }

        public int budgetUsed() {
            return budgetUsed;
        }

        public int budgetLimit() {
            return budgetLimit;
        }

        public String abortReason() {
            return abortReason;
        }

        public boolean isBudgetStopped() {
            return kind == JudgeKind.BUDGET_STOPPED;
        }

        @Override
        public String toString() {
            return "JudgeOutcome{" + kind + ", elapsedMs=" + elapsedMs + ", budget=" + budgetUsed + "/"
                    + budgetLimit + ", message=" + message + "}";
        }
    }

    /** 生产默认退避：直接睡当前线程，被中断时保留中断标记并交给上层停止重试。 */
    private static final class ThreadSleeper implements Sleeper {
        @Override
        public void sleepSeconds(int seconds) {
            if (seconds <= 0) {
                return;
            }
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final EvalBudgetLedger ledger;
    private final EvalCallRecorder recorder;
    private final EvalCallPolicy policy;
    private final Sleeper sleeper;

    public EvalCallInvoker(EvalBudgetLedger ledger, EvalCallRecorder recorder, EvalCallPolicy policy) {
        this(ledger, recorder, policy, new ThreadSleeper());
    }

    public EvalCallInvoker(EvalBudgetLedger ledger, EvalCallRecorder recorder, EvalCallPolicy policy,
            Sleeper sleeper) {
        if (ledger == null) {
            throw new IllegalArgumentException("预算记账器不能为空");
        }
        if (recorder == null) {
            throw new IllegalArgumentException("调用行采集器不能为空");
        }
        if (policy == null) {
            throw new IllegalArgumentException("调用策略不能为空");
        }
        if (sleeper == null) {
            throw new IllegalArgumentException("退避等待器不能为空");
        }
        this.ledger = ledger;
        this.recorder = recorder;
        this.policy = policy;
        this.sleeper = sleeper;
    }

    /**
     * 执行一次样本生成，内部完成预算占位、重试与 cached 重采。
     *
     * @param runId       运行编号
     * @param caseId      样本编号
     * @param sampleIndex 逻辑样本序号，0..99
     * @param call        真实生成调用
     * @return 生成结果；预算止步时 result 为 null 且 kind 为 BUDGET_STOPPED
     */
    public GenerateOutcome generate(String runId, String caseId, int sampleIndex, GenerateCall call) {
        if (call == null) {
            throw new IllegalArgumentException("生成调用不能为空");
        }
        requireIds(runId, caseId);
        if (sampleIndex < 0 || sampleIndex > EvalGenerateRequest.MAX_SAMPLE_INDEX) {
            throw new IllegalArgumentException("sampleIndex 越界：" + sampleIndex
                    + "，取值 0.." + EvalGenerateRequest.MAX_SAMPLE_INDEX);
        }

        int rowAttempt = 0;
        int sessionAttempt = 0;
        int sessionSampleIndex = sampleIndex;
        int calls = 0;
        int retries = 0;
        int cachedHits = 0;
        EvalGenerateResult last = null;

        while (true) {
            if (ledger.consume() == EvalBudgetLedger.Decision.STOP) {
                return budgetStopped(last, calls, retries, cachedHits);
            }
            calls++;
            EvalGenerateResult result = invoke(runId, caseId, sampleIndex, sessionSampleIndex, sessionAttempt, call);
            recorder.recordGenerate(runId, caseId, sampleIndex, rowAttempt, result);
            last = result;
            if (result.cached()) {
                cachedHits++;
            }

            EvalCallPolicy.Decision decision = policy.decide(result, retries, cachedHits);
            switch (decision.action()) {
                case ACCEPT:
                    return new GenerateOutcome(GenerateKind.OK, result, decision.reason(), calls, retries,
                            cachedHits, ledger.used(), ledger.limit(), null);
                case FAIL:
                    return new GenerateOutcome(GenerateKind.SAMPLE_ERROR, result, decision.reason(), calls,
                            retries, cachedHits, ledger.used(), ledger.limit(), null);
                case RETRY:
                    retries++;
                    sleeper.sleepSeconds(decision.backoffSeconds());
                    if (Thread.currentThread().isInterrupted()) {
                        return new GenerateOutcome(GenerateKind.SAMPLE_ERROR, result, "调用线程被中断，停止重试",
                                calls, retries, cachedHits, ledger.used(), ledger.limit(), null);
                    }
                    break;
                case RESAMPLE:
                    break;
                default:
                    throw new IllegalStateException("未知调用动作：" + decision.action());
            }

            rowAttempt++;
            sessionAttempt = sessionAttempt >= EvalGenerateRequest.MAX_ATTEMPT ? 0 : sessionAttempt + 1;
            sessionSampleIndex = sessionSampleIndex >= EvalGenerateRequest.MAX_SAMPLE_INDEX
                    ? 0 : sessionSampleIndex + 1;
        }
    }

    /**
     * 执行一次判分调用：占 1 次预算、调用一次、落 1 行。
     *
     * @param attempt 同一 (run, case, sampleIndex) 内的行序号，由调用方保证唯一
     * @return 判分结果；预算止步时 result 为 null 且 kind 为 BUDGET_STOPPED
     */
    public JudgeOutcome judge(String runId, String caseId, int sampleIndex, int attempt, JudgeCall call) {
        return judgeInternal(runId, caseId, sampleIndex, attempt, call, false);
    }

    /**
     * 执行一次校准自证判分：与 {@link #judge} 同样占 1 次预算、调用一次、落 1 行，但行的
     * {@code stage} 写 {@code calib}（§6.7.4、§6.8.1）。
     *
     * <p>校准的 3 次调用计入同一预算与 calls_used，因此预算触顶时同样返回
     * {@link JudgeKind#BUDGET_STOPPED}，由调用方中止校准步骤而不是中止 run。</p>
     *
     * @param sampleIndex 校准样本序号；当前调用方固定传 0，行唯一性由 case_id 保证
     * @param attempt 同一样本的重试序号；当前调用方固定传 0，行唯一性由 case_id 保证
     * @return 判分结果；预算止步时 result 为 null 且 kind 为 BUDGET_STOPPED
     */
    public JudgeOutcome judgeCalibration(String runId, String caseId, int sampleIndex, int attempt,
            JudgeCall call) {
        return judgeInternal(runId, caseId, sampleIndex, attempt, call, true);
    }

    private JudgeOutcome judgeInternal(String runId, String caseId, int sampleIndex, int attempt,
            JudgeCall call, boolean calibration) {
        if (call == null) {
            throw new IllegalArgumentException("判分调用不能为空");
        }
        requireIds(runId, caseId);
        if (sampleIndex < 0 || sampleIndex > EvalGenerateRequest.MAX_SAMPLE_INDEX) {
            throw new IllegalArgumentException("sampleIndex 越界：" + sampleIndex
                    + "，取值 0.." + EvalGenerateRequest.MAX_SAMPLE_INDEX);
        }
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt 不能为负：" + attempt);
        }
        if (ledger.consume() == EvalBudgetLedger.Decision.STOP) {
            return new JudgeOutcome(JudgeKind.BUDGET_STOPPED, null, "预算已触顶，未发起判分调用", 0L,
                    ledger.used(), ledger.limit(), ledger.abortReason());
        }

        long startedAt = System.currentTimeMillis();
        EvalJudgeResult result;
        try {
            result = call.call();
        } catch (RuntimeException e) {
            result = EvalJudgeResult.error("", EvalJudgeResult.KIND_TRANSPORT,
                    EvalGenerateResult.ERROR_INTERNAL, false, 0, null,
                    "判分调用抛出异常：" + e.getMessage(), null, 0);
        }
        if (result == null) {
            result = EvalJudgeResult.error("", EvalJudgeResult.KIND_TRANSPORT,
                    EvalGenerateResult.ERROR_INTERNAL, false, 0, null, "判分调用返回 null", null, 0);
        }
        long elapsedMs = System.currentTimeMillis() - startedAt;
        if (calibration) {
            recorder.recordCalibration(runId, caseId, sampleIndex, attempt, elapsedMs, result);
        } else {
            recorder.recordJudge(runId, caseId, sampleIndex, attempt, elapsedMs, result);
        }

        JudgeKind kind = result.isOk() ? JudgeKind.OK : (result.isInvalid() ? JudgeKind.INVALID : JudgeKind.ERROR);
        return new JudgeOutcome(kind, result, result.message(), elapsedMs, ledger.used(), ledger.limit(), null);
    }

    private EvalGenerateResult invoke(String runId, String caseId, int sampleIndex, int sessionSampleIndex,
            int sessionAttempt, GenerateCall call) {
        GenerateTarget target = new GenerateTarget(runId, caseId, sampleIndex, sessionSampleIndex, sessionAttempt);
        try {
            EvalGenerateResult result = call.call(target);
            if (result != null) {
                return result;
            }
            return EvalGenerateResult.error(EvalGenerateResult.ERROR_INTERNAL, false, "生成调用返回 null");
        } catch (RuntimeException e) {
            return EvalGenerateResult.error(EvalGenerateResult.ERROR_INTERNAL, false,
                    "生成调用抛出异常：" + e.getMessage());
        }
    }

    private GenerateOutcome budgetStopped(EvalGenerateResult last, int calls, int retries, int cachedHits) {
        return new GenerateOutcome(GenerateKind.BUDGET_STOPPED, last, "预算已触顶，未发起调用", calls,
                retries, cachedHits, ledger.used(), ledger.limit(), ledger.abortReason());
    }

    private static void requireIds(String runId, String caseId) {
        if (runId == null || runId.trim().isEmpty()) {
            throw new IllegalArgumentException("run_id 不能为空");
        }
        if (caseId == null || caseId.trim().isEmpty()) {
            throw new IllegalArgumentException("case_id 不能为空");
        }
    }
}
