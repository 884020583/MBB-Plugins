package org.moboxlab.mbb.eval.bank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 题库校验结果（规格 §5.7.1：全有或全无；错误最多回显 5 条，其余只计数）。
 *
 * <p>{@link #problems()} 是按稳定顺序排好的全部问题；{@link #visibleProblems()} 是前 5 条；
 * {@link #suppressedCount()} 是其余被折叠的数量。告警不参与 ok 判定。</p>
 */
public final class EvalBankCheckResult {

    /** §5.7.1 单次最多回显的错误条数。 */
    public static final int MAX_VISIBLE_PROBLEMS = 5;

    private final List<EvalBankProblem> problems;
    private final List<EvalBankProblem> visibleProblems;
    private final List<String> warnings;
    private final int suppressedCount;

    private EvalBankCheckResult(List<EvalBankProblem> problems, List<String> warnings) {
        this.problems = Collections.unmodifiableList(new ArrayList<EvalBankProblem>(problems));
        int visible = Math.min(this.problems.size(), MAX_VISIBLE_PROBLEMS);
        this.visibleProblems = this.problems.subList(0, visible);
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
        this.suppressedCount = this.problems.size() - visible;
    }

    /** 汇总问题（排序）与告警；列表为空表示通过。 */
    public static EvalBankCheckResult of(List<EvalBankProblem> problems, List<String> warnings) {
        List<EvalBankProblem> sorted = new ArrayList<EvalBankProblem>();
        if (problems != null) {
            sorted.addAll(problems);
            Collections.sort(sorted);
        }
        List<String> warningCopy = warnings == null
                ? new ArrayList<String>() : new ArrayList<String>(warnings);
        return new EvalBankCheckResult(sorted, warningCopy);
    }

    /** 通过结果，附带告警。 */
    public static EvalBankCheckResult ok(List<String> warnings) {
        return of(Collections.<EvalBankProblem>emptyList(), warnings);
    }

    /** 单条失败结果（文件级早退用）。 */
    public static EvalBankCheckResult failed(EvalBankProblem problem) {
        List<EvalBankProblem> problems = new ArrayList<EvalBankProblem>();
        problems.add(problem);
        return of(problems, Collections.<String>emptyList());
    }

    public boolean ok() {
        return problems.isEmpty();
    }

    /** 全部问题，已按稳定顺序排序。 */
    public List<EvalBankProblem> problems() {
        return problems;
    }

    /** 前 5 条问题。 */
    public List<EvalBankProblem> visibleProblems() {
        return visibleProblems;
    }

    /** 被折叠、未回显的问题数量。 */
    public int suppressedCount() {
        return suppressedCount;
    }

    /** 告警（未知字段、evidenceTurns 运行期越界等），不参与 ok 判定。 */
    public List<String> warnings() {
        return warnings;
    }

    /** 中文摘要，供日志直接输出。 */
    public String summary() {
        StringBuilder text = new StringBuilder();
        if (ok()) {
            text.append("题库校验通过");
            if (!warnings.isEmpty()) {
                text.append("，").append(warnings.size()).append(" 条告警");
            }
            return text.toString();
        }
        text.append("题库校验失败：共 ").append(problems.size()).append(" 个错误");
        if (suppressedCount > 0) {
            text.append("，仅回显前 ").append(MAX_VISIBLE_PROBLEMS).append(" 条");
        }
        for (EvalBankProblem problem : visibleProblems) {
            text.append(System.lineSeparator()).append("  ").append(problem.display());
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return summary();
    }
}
