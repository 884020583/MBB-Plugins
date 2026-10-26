package org.moboxlab.mbb.eval.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.moboxlab.mbb.eval.judge.EvalJudgeRequest;

/**
 * 一次 judge 调用的中性入参（规格 §6.3.1、§6.3.3）。
 *
 * <p>执行器不直接构造 {@link EvalJudgeRequest}：该请求要求传入
 * {@code rubric.EvalRubric.Dimension}，而维度对象只能由 rubric 加载器创建。这里只带
 * 维度 id 与判分上下文，生产适配器再用 rubric 把 id 解析成 {@code Dimension} 并调用
 * {@link EvalJudgeRequest.Builder#build()}。这样才能在测试里用假 judge，而不必先造出
 * 一份合法的 rubric 文件。</p>
 */
public final class EvalCaseJudgeInput {

    private final String runId;
    private final String caseId;
    private final int sampleIndex;
    private final int attempt;
    private final String dimensionId;
    private final String personaSummary;
    private final List<EvalJudgeRequest.Turn> history;
    private final List<Integer> evidenceTurns;
    private final String instruction;
    private final String focus;
    private final String replyContent;
    private final List<String> softObservations;
    private final String promptVersion;

    public EvalCaseJudgeInput(String runId, String caseId, int sampleIndex, int attempt,
            String dimensionId, String personaSummary, List<EvalJudgeRequest.Turn> history,
            List<Integer> evidenceTurns, String instruction, String focus, String replyContent,
            List<String> softObservations, String promptVersion) {
        this.runId = runId;
        this.caseId = caseId;
        this.sampleIndex = sampleIndex;
        this.attempt = attempt;
        this.dimensionId = dimensionId;
        this.personaSummary = personaSummary;
        this.history = Collections.unmodifiableList(new ArrayList<EvalJudgeRequest.Turn>(
                history == null ? Collections.<EvalJudgeRequest.Turn>emptyList() : history));
        this.evidenceTurns = Collections.unmodifiableList(new ArrayList<Integer>(
                evidenceTurns == null ? Collections.<Integer>emptyList() : evidenceTurns));
        this.instruction = instruction;
        this.focus = focus;
        this.replyContent = replyContent;
        this.softObservations = Collections.unmodifiableList(new ArrayList<String>(
                softObservations == null ? Collections.<String>emptyList() : softObservations));
        this.promptVersion = promptVersion;
    }

    public String runId() {
        return runId;
    }

    public String caseId() {
        return caseId;
    }

    public int sampleIndex() {
        return sampleIndex;
    }

    public int attempt() {
        return attempt;
    }

    /** 维度 id，取值 persona / boundary / memory。 */
    public String dimensionId() {
        return dimensionId;
    }

    public String personaSummary() {
        return personaSummary;
    }

    public List<EvalJudgeRequest.Turn> history() {
        return history;
    }

    public List<Integer> evidenceTurns() {
        return evidenceTurns;
    }

    public String instruction() {
        return instruction;
    }

    public String focus() {
        return focus;
    }

    public String replyContent() {
        return replyContent;
    }

    public List<String> softObservations() {
        return softObservations;
    }

    public String promptVersion() {
        return promptVersion;
    }
}
