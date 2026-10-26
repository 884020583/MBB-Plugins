package org.moboxlab.mbb.eval.score;

import org.moboxlab.mbb.eval.judge.EvalJudgeResult;

/**
 * 单题判分输入（D-4：规格 §6.1、§6.6.1）。
 *
 * <p>只承载聚合所需的三件事：题号、所属维度、四态状态与归一化题分。题分域为 [0, 1]，
 * 由 {@code deterministic/} 的确定性题分或 {@code judge/} 的
 * {@link EvalJudgeResult#normalizedScore()} 产出；聚合器不重新判分。</p>
 *
 * <p>状态封闭四值：{@code ok} / {@code vetoed} / {@code error} / {@code invalid}。
 * {@code vetoed} 记 0 分且计入维度分母；{@code error} 与 {@code invalid} 从分母剔除并单列
 * （§6.6.1）。{@link #of(String, String, Status, Double)} 允许传入不完整行，聚合器会把
 * 「状态 ok 但题分为空或越界」按 {@code invalid} 处理，避免把缺失分静默折成 0。</p>
 */
public final class EvalCaseScore {

    /** 单题状态封闭集合，与 {@code case_result.status} 一致（§6.6.1）。 */
    public enum Status {
        OK,
        VETOED,
        ERROR,
        INVALID;

        /** 是否计入维度分母：只有 ok 与 vetoed 计入（§6.6.1）。 */
        public boolean countsInDenominator() {
            return this == OK || this == VETOED;
        }

        /** 是否计入非 ok（error + invalid）；vetoed 不算（§6.6.3）。 */
        public boolean isNonOk() {
            return this == ERROR || this == INVALID;
        }
    }

    private final String caseId;
    private final String dimensionId;
    private final Status status;
    private final Double caseScore;

    private EvalCaseScore(String caseId, String dimensionId, Status status, Double caseScore) {
        this.caseId = caseId;
        this.dimensionId = dimensionId;
        this.status = status;
        this.caseScore = caseScore;
    }

    /**
     * 通用工厂：允许保留存储层的原始行。
     *
     * <p>{@code vetoed} 的题分恒为 0；{@code error} / {@code invalid} 的题分为 null。
     * {@code ok} 的题分原样保存，可能为 null，由聚合器按 §6.6.1 降级为 invalid。</p>
     */
    public static EvalCaseScore of(String caseId, String dimensionId, Status status, Double caseScore) {
        if (caseId == null || caseId.trim().isEmpty()) {
            throw new IllegalArgumentException("caseId 不能为空");
        }
        if (dimensionId == null || dimensionId.trim().isEmpty()) {
            throw new IllegalArgumentException("dimensionId 不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        if (status == Status.VETOED) {
            return new EvalCaseScore(caseId, dimensionId, status, Double.valueOf(0.0d));
        }
        if (status == Status.ERROR || status == Status.INVALID) {
            return new EvalCaseScore(caseId, dimensionId, status, null);
        }
        return new EvalCaseScore(caseId, dimensionId, status, caseScore);
    }

    /** 正常判分完成；题分必须落在 [0, 1]。 */
    public static EvalCaseScore ok(String caseId, String dimensionId, double caseScore) {
        if (caseScore < 0.0d || caseScore > 1.0d) {
            throw new IllegalArgumentException("题分必须在 [0, 1]：" + caseScore);
        }
        return new EvalCaseScore(caseId, dimensionId, Status.OK, Double.valueOf(caseScore));
    }

    /** 命中硬项否决：题分记 0，且计入分母（被测失败，不是判分失败）。 */
    public static EvalCaseScore vetoed(String caseId, String dimensionId) {
        return of(caseId, dimensionId, Status.VETOED, null);
    }

    /** 判分调用失败：题分不可得，剔除分母。 */
    public static EvalCaseScore error(String caseId, String dimensionId) {
        return of(caseId, dimensionId, Status.ERROR, null);
    }

    /** 解析失败 / 截断 / 证据不合法：题分不可得，剔除分母并单独计数。 */
    public static EvalCaseScore invalid(String caseId, String dimensionId) {
        return of(caseId, dimensionId, Status.INVALID, null);
    }

    /** 由 judge 结果映射：ok → normalizedScore，invalid / error 原样保留。 */
    public static EvalCaseScore fromJudge(String caseId, String dimensionId, EvalJudgeResult result) {
        if (result == null) {
            throw new IllegalArgumentException("judge 结果不能为空");
        }
        switch (result.state()) {
            case OK:
                return ok(caseId, dimensionId, result.normalizedScore());
            case INVALID:
                return invalid(caseId, dimensionId);
            case ERROR:
                return error(caseId, dimensionId);
            default:
                throw new IllegalArgumentException("未知 judge 终态：" + result.state());
        }
    }

    /** 由确定性题分映射；确定性维没有 error / invalid 以外的采样噪声。 */
    public static EvalCaseScore fromDeterministic(String caseId, String dimensionId, double caseScore) {
        return ok(caseId, dimensionId, caseScore);
    }

    public String caseId() {
        return caseId;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public Status status() {
        return status;
    }

    /** 归一化题分；error / invalid 为 null，ok 行也可能因数据不完整为 null。 */
    public Double caseScore() {
        return caseScore;
    }
}
