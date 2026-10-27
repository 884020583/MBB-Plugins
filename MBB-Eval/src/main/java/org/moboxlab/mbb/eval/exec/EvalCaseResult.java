package org.moboxlab.mbb.eval.exec;

import org.moboxlab.mbb.eval.score.EvalCaseScore;
import org.moboxlab.mbb.eval.store.EvalStore;

/**
 * 单 case 执行结果（规格 §6.6.1、§13.1.3）。
 *
 * <p>终态与 {@link EvalCaseScore.Status} 对齐：{@code ok} / {@code vetoed} 计入分母，
 * {@code error} / {@code invalid} 不计入分母但进 error_rate。命中停止点、预算触顶或
 * eval-generate 返回 disabled 时整个 run 应中止，此时本 case 没有终态（{@link #status()}
 * 返回 null），由 {@link #abortRun()} 与 {@link #abortReason()} 表达，且不写结果行。</p>
 */
public final class EvalCaseResult {

    private final String caseId;
    private final String dimension;
    private final EvalCaseScore.Status status;
    private final Double caseScore;
    private final String label;
    private final String scale;
    private final String evidence;
    private final int calls;
    private final boolean abortRun;
    private final String abortReason;
    private final boolean stopped;
    private final EvalStore.WriteResult writeResult;
    private final long durationMs;

    EvalCaseResult(String caseId, String dimension, EvalCaseScore.Status status, Double caseScore,
            String label, String scale, String evidence, int calls, boolean abortRun,
            String abortReason, boolean stopped, EvalStore.WriteResult writeResult, long durationMs) {
        this.caseId = caseId;
        this.dimension = dimension;
        this.status = status;
        this.caseScore = caseScore;
        this.label = label;
        this.scale = scale;
        this.evidence = evidence;
        this.calls = calls;
        this.abortRun = abortRun;
        this.abortReason = abortReason;
        this.stopped = stopped;
        this.writeResult = writeResult;
        this.durationMs = durationMs;
    }

    public String caseId() {
        return caseId;
    }

    public String dimension() {
        return dimension;
    }

    /** 终态；中止（停止点 / 预算 / disabled）时为 null。 */
    public EvalCaseScore.Status status() {
        return status;
    }

    /** 0..1 的题分；error / invalid 与中止时为 null。 */
    public Double caseScore() {
        return caseScore;
    }

    /** 百分制题分；无分时为 null。 */
    public Double scorePercent() {
        return caseScore == null ? null : Double.valueOf(caseScore.doubleValue() * 100.0D);
    }

    /** 落库 label：确定性维 hit/miss，LLM 维中位档 0/1/2，vetoed 为 veto。 */
    public String label() {
        return label;
    }

    /** 落库 scale：0/1 或 0/1/2。 */
    public String scale() {
        return scale;
    }

    public String evidence() {
        return evidence;
    }

    /** 本 case 实际发生的物理调用次数（生成 + 判分）。 */
    public int calls() {
        return calls;
    }

    /** 是否应中止整个 run（停止点命中、预算触顶或 disabled）。 */
    public boolean abortRun() {
        return abortRun;
    }

    /** 中止原因；不中止时为 null。 */
    public String abortReason() {
        return abortReason;
    }

    /** 是否因停止点 / 预算在写库前中止（此时必然没有写行）。 */
    public boolean stopped() {
        return stopped;
    }

    /** 落库三态；未尝试写库时为 null。 */
    public EvalStore.WriteResult writeResult() {
        return writeResult;
    }

    /** 结果行是否已成功写入。 */
    public boolean written() {
        return EvalStore.WriteResult.APPLIED == writeResult;
    }

    public long durationMs() {
        return durationMs;
    }
}
