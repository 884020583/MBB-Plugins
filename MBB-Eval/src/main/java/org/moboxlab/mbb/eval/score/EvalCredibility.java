package org.moboxlab.mbb.eval.score;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可信度门槛结果（D-4：规格 §6.6.3、§8.5、§15.4 D17）。
 *
 * <p>三条门槛只改报告标注与结论强度，不改分数：有效题不足 80%（full 档即 &lt; 32/40）、
 * 非 ok（error + invalid）占比超过 10%、rubric 版本与基线不同。前两条决定
 * {@link #credible()}；rubric 已变单独标注为不可比，不与前两条互相替代。</p>
 */
public final class EvalCredibility {

    /** 有效题占比下限：80%，即 full 档 32/40（§6.6.3）。 */
    public static final double MIN_VALID_RATIO = 0.80d;
    /** 非 ok 占比上限：10%（严格大于才触发，§6.6.3）。 */
    public static final double MAX_NON_OK_RATIO = 0.10d;

    private final int validCaseCount;
    private final int caseCount;
    private final double nonOkRate;
    private final boolean validCasesSufficient;
    private final boolean nonOkRateAcceptable;
    private final boolean rubricChanged;

    EvalCredibility(int validCaseCount, int caseCount, double nonOkRate,
            boolean validCasesSufficient, boolean nonOkRateAcceptable, boolean rubricChanged) {
        this.validCaseCount = validCaseCount;
        this.caseCount = caseCount;
        this.nonOkRate = nonOkRate;
        this.validCasesSufficient = validCasesSufficient;
        this.nonOkRateAcceptable = nonOkRateAcceptable;
        this.rubricChanged = rubricChanged;
    }

    public int validCaseCount() {
        return validCaseCount;
    }

    public int caseCount() {
        return caseCount;
    }

    /** 非 ok 率 = (error + invalid) / 总题数；分母为 0 时为 0.0。 */
    public double nonOkRate() {
        return nonOkRate;
    }

    /** 有效题占比是否达到 80%。 */
    public boolean validCasesSufficient() {
        return validCasesSufficient;
    }

    /** 非 ok 率是否未超过 10%。 */
    public boolean nonOkRateAcceptable() {
        return nonOkRateAcceptable;
    }

    /** rubric 版本是否与基线不同；只影响可比性，不单独判定「不可信」。 */
    public boolean rubricChanged() {
        return rubricChanged;
    }

    /** 整份报告是否可信：有效题达标且非 ok 率达标。 */
    public boolean credible() {
        return validCasesSufficient && nonOkRateAcceptable;
    }

    /** 中文标注，供报表 §8 与群摘要直接使用。 */
    public List<String> annotations() {
        List<String> notes = new ArrayList<String>();
        if (!validCasesSufficient) {
            notes.add("不可信：有效题 " + validCaseCount + "/" + caseCount + "（低于 80%）");
        }
        if (!nonOkRateAcceptable) {
            notes.add("判分不可信：非 ok 率 " + EvalScoreFormat.percentOneDecimal(nonOkRate) + " 超过 10%");
        }
        if (rubricChanged) {
            notes.add("rubric 已变，不与历史直接比较");
        }
        return Collections.unmodifiableList(notes);
    }
}
