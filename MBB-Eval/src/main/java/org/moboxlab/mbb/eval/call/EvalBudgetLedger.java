package org.moboxlab.mbb.eval.call;

import org.moboxlab.mbb.eval.runner.EvalRunBudget;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * 预算硬顶记账（规格 §4.4、§9.5、§13.3）。
 *
 * <p>每次 AI 调用前调用 {@link #consume()}：内部复用 {@link EvalRunBudget} 的原子计数，
 * 成功占位后立即把 calls_used 写穿到 run 行；预算用尽后返回 {@link Decision#STOP}
 * 并标记中止原因 budget，由调用方（EvalRunner）统一写 ABORTED(budget)。</p>
 *
 * <p>语义与 EvalRunBudget 保持一致：恰好允许 limit 次调用，第 limit + 1 次起返回 STOP。
 * 重试与 cached 重采都各自占一次预算。</p>
 */
public final class EvalBudgetLedger {

    /** calls_used 写穿接口；返回 false 表示本次写库未生效（由实现方决定重试策略）。 */
    public interface CallsUsedWriter {
        boolean incrementCallsUsed(String runId);
    }

    /** 调用前占位结果：CONSUMED 表示可以发起调用，STOP 表示已达硬顶必须停止。 */
    public enum Decision {
        CONSUMED,
        STOP
    }

    private final String runId;
    private final EvalRunBudget budget;
    private final CallsUsedWriter writer;
    private final PluginLogger logger;

    /**
     * 直接注入写穿器（测试与自定义装配）。
     *
     * @throws IllegalArgumentException run_id 为空或写穿器为空
     */
    public EvalBudgetLedger(String runId, int planned, int limit, long deadlineAtMillis,
            CallsUsedWriter writer, PluginLogger logger) {
        if (runId == null || runId.trim().isEmpty()) {
            throw new IllegalArgumentException("run_id 不能为空");
        }
        if (writer == null) {
            throw new IllegalArgumentException("calls_used 写穿器不能为空");
        }
        this.runId = runId;
        this.budget = new EvalRunBudget(planned, limit, deadlineAtMillis);
        this.writer = writer;
        this.logger = logger;
    }

    /** 以 EvalStore 作为写穿目标的生产装配。 */
    public EvalBudgetLedger(String runId, int planned, int limit, long deadlineAtMillis,
            EvalStore store, PluginLogger logger) {
        this(runId, planned, limit, deadlineAtMillis, storeWriter(store), logger);
    }

    /**
     * 为一次即将发起的调用占预算。
     *
     * <p>先原子加一，再写穿 calls_used；写穿失败只记 WARN，不阻断本次调用（计数已在内存中，
     * 下一次写穿会继续累加，不会把预算放大）。预算已用尽时返回 STOP，并在尚未中止时
     * 把中止原因标记为 budget。</p>
     */
    public Decision consume() {
        if (budget.tryConsume()) {
            writeThrough();
            return Decision.CONSUMED;
        }
        if (!budget.isAborted()) {
            budget.abort(EvalStore.ABORT_REASON_BUDGET);
        }
        return Decision.STOP;
    }

    public int used() {
        return budget.used();
    }

    public int limit() {
        return budget.limit();
    }

    public int planned() {
        return budget.planned();
    }

    public int remaining() {
        int remaining = budget.limit() - budget.used();
        return remaining < 0 ? 0 : remaining;
    }

    public boolean isAborted() {
        return budget.isAborted();
    }

    public String abortReason() {
        return budget.abortReason();
    }

    public boolean deadlineExceeded(long nowMillis) {
        return budget.deadlineExceeded(nowMillis);
    }

    /** 与 EvalRunBudget 一致：首次原因生效，后续调用幂等。 */
    public void abort(String reason) {
        budget.abort(reason);
    }

    private void writeThrough() {
        try {
            if (!writer.incrementCallsUsed(runId)) {
                warn("calls_used 写穿未生效（run_id=" + runId + "），按内存计数继续");
            }
        } catch (RuntimeException e) {
            warn("calls_used 写穿异常（run_id=" + runId + "）：" + e.getMessage());
        }
    }

    private static CallsUsedWriter storeWriter(final EvalStore store) {
        if (store == null) {
            throw new IllegalArgumentException("EvalStore 不可用，无法写穿 calls_used");
        }
        return new CallsUsedWriter() {
            @Override
            public boolean incrementCallsUsed(String runId) {
                return store.incrementCallsUsed(runId) == EvalStore.WriteResult.APPLIED;
            }
        };
    }

    private void warn(String message) {
        if (logger != null) {
            logger.sendWarn("[MBB-Eval] " + message);
        }
    }
}
