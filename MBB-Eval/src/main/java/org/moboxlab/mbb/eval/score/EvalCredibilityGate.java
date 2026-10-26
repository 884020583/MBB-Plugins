package org.moboxlab.mbb.eval.score;

/**
 * 可信度两条门槛的统一判定（规格 §6.6.3、§8.5，§15.5 H11）。
 *
 * <p>与 {@link EvalScoreAggregator} 保持同一口径：有效题占比低于 80%、非 ok 占比超过 10%
 * 是两条彼此独立的标注，互不替代；恰好 80% 与恰好 10% 都不触发。rubric 变化只增加
 * 可比性说明，不影响两条门槛的结论。这里单独成类，供报表以外的调用方（如接线层）复用。</p>
 */
public final class EvalCredibilityGate {

    private EvalCredibilityGate() {
    }

    /**
     * 判定可信度。
     *
     * @param validCaseCount 有效题数（ok 与 vetoed）
     * @param caseCount      参与统计的题数
     * @param errorCount     传输失败且重试未成功的题数
     * @param invalidCount   解析失败或截断的题数
     * @param rubricChanged  rubric 是否相对基线变化
     */
    public static EvalCredibility judge(int validCaseCount, int caseCount, int errorCount,
            int invalidCount, boolean rubricChanged) {
        int nonOk = errorCount + invalidCount;
        if (validCaseCount < 0 || caseCount < 0 || errorCount < 0 || invalidCount < 0) {
            throw new IllegalArgumentException("题数不能为负");
        }
        if (validCaseCount > caseCount) {
            throw new IllegalArgumentException("有效题数不能超过总题数");
        }
        if (nonOk > caseCount) {
            throw new IllegalArgumentException("非 ok 题数不能超过总题数");
        }
        if (validCaseCount + nonOk > caseCount) {
            throw new IllegalArgumentException("有效题与非 ok 题之和不能超过总题数");
        }

        boolean validCasesSufficient = caseCount > 0 && validCaseCount * 5L >= caseCount * 4L;
        double nonOkRate = caseCount == 0 ? 0.0d : (double) nonOk / (double) caseCount;
        boolean nonOkRateAcceptable = nonOk * 10L <= (long) caseCount;
        return new EvalCredibility(validCaseCount, caseCount, nonOkRate,
                validCasesSufficient, nonOkRateAcceptable, rubricChanged);
    }
}
