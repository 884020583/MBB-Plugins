package org.moboxlab.mbb.eval.rubric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * rubric 加载结果：成功时带不可变模型，失败时带问题清单。
 *
 * <p>沿用配置读取的「返回告警/问题而非抛异常」口径（{@code EvalConfig}），
 * 让预检层可以一次性展示缺失项与版本差异，不必捕获异常（§13.2）。</p>
 */
public final class EvalRubricLoadResult {

    private final EvalRubric rubric;
    private final List<EvalRubricProblem> problems;
    private final List<String> warnings;

    private EvalRubricLoadResult(EvalRubric rubric, List<EvalRubricProblem> problems, List<String> warnings) {
        this.rubric = rubric;
        this.problems = Collections.unmodifiableList(new ArrayList<EvalRubricProblem>(problems));
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    static EvalRubricLoadResult ok(EvalRubric rubric, List<String> warnings) {
        return new EvalRubricLoadResult(rubric, Collections.<EvalRubricProblem>emptyList(), warnings);
    }

    static EvalRubricLoadResult failed(List<EvalRubricProblem> problems, List<String> warnings) {
        return new EvalRubricLoadResult(null, problems, warnings);
    }

    public boolean ok() {
        return rubric != null;
    }

    /** 加载成功时的 rubric；失败时返回 null。 */
    public EvalRubric rubric() {
        return rubric;
    }

    /** 加载问题；成功时为空列表。 */
    public List<EvalRubricProblem> problems() {
        return problems;
    }

    /** 加载期告警；失败时也可能有（例如未知字段）。 */
    public List<String> warnings() {
        return warnings;
    }
}
