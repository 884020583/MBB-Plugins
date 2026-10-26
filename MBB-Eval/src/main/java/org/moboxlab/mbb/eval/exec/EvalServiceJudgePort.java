package org.moboxlab.mbb.eval.exec;

import org.moboxlab.mbb.eval.judge.EvalJudgeFailureTracker;
import org.moboxlab.mbb.eval.judge.EvalJudgeOutcome;
import org.moboxlab.mbb.eval.judge.EvalJudgeRequest;
import org.moboxlab.mbb.eval.judge.EvalJudgeResult;
import org.moboxlab.mbb.eval.judge.EvalJudgeService;
import org.moboxlab.mbb.eval.rubric.EvalRubric;

/**
 * 生产用判分端口：把 {@link EvalCaseJudgeInput} 转成 {@link EvalJudgeRequest} 交给 EvalJudgeService。
 *
 * <p>判分服务的失败计数、退避与熔断由 {@link EvalJudgeFailureTracker} 在 service 内维护；
 * 本适配器只做输入转换与结果透传，不重复计数、不落表（落行由 invoker 完成，§9.5）。</p>
 */
public final class EvalServiceJudgePort implements EvalCaseJudgePort {

    private final EvalJudgeService service;
    private final EvalRubric rubric;

    public EvalServiceJudgePort(EvalJudgeService service, EvalRubric rubric) {
        if (service == null) {
            throw new IllegalArgumentException("判分端口缺少 EvalJudgeService");
        }
        if (rubric == null) {
            throw new IllegalArgumentException("判分端口缺少 rubric");
        }
        this.service = service;
        this.rubric = rubric;
    }

    @Override
    public EvalJudgeResult judge(EvalCaseJudgeInput input) {
        if (input == null) {
            return EvalJudgeResult.error("persona", EvalJudgeResult.KIND_TRANSPORT, "internal", false,
                    0, null, "未提供判分输入", null, 0);
        }
        String dimensionId = input.dimensionId();
        EvalRubric.Dimension dimension = rubric.dimension(dimensionId);
        if (dimension == null) {
            return EvalJudgeResult.invalid(dimensionId, EvalJudgeResult.KIND_PROFILE, 0, null,
                    "题目维度不在 rubric 中：" + dimensionId, null, 0);
        }
        if (service.runAborted()) {
            return EvalJudgeResult.error(dimensionId, EvalJudgeResult.KIND_TRANSPORT,
                    EvalJudgeFailureTracker.ABORT_REASON_AI_UNAVAILABLE, false, 0, null,
                    "判分已熔断（ai-unavailable）", null, 0);
        }
        EvalJudgeRequest request;
        try {
            request = EvalJudgeRequest.builder(input.runId(), input.caseId())
                    .sampleIndex(input.sampleIndex())
                    .attempt(input.attempt())
                    .dimension(dimension)
                    .personaSummary(input.personaSummary())
                    .history(input.history())
                    .evidenceTurns(input.evidenceTurns())
                    .instruction(input.instruction())
                    .focus(input.focus())
                    .replyContent(input.replyContent())
                    .softObservations(input.softObservations())
                    .promptVersion(input.promptVersion())
                    .build();
        } catch (RuntimeException e) {
            return EvalJudgeResult.invalid(dimensionId, EvalJudgeResult.KIND_PROFILE, 0, null,
                    "判分入参校验失败：" + e.getMessage(), null, 0);
        }
        EvalJudgeOutcome outcome;
        try {
            outcome = service.evaluate(request);
        } catch (RuntimeException e) {
            return EvalJudgeResult.error(dimensionId, EvalJudgeResult.KIND_TRANSPORT, "internal", false,
                    0, null, "判分调用异常：" + e.getClass().getSimpleName(), null, 0);
        }
        if (outcome == null || outcome.result() == null) {
            return EvalJudgeResult.error(dimensionId, EvalJudgeResult.KIND_TRANSPORT,
                    EvalJudgeFailureTracker.ABORT_REASON_AI_UNAVAILABLE, false, 0, null,
                    "判分服务未返回结果（判定 AI 不可用）", null, 0);
        }
        return outcome.result();
    }

    @Override
    public boolean aborted() {
        return service.runAborted();
    }

    @Override
    public String abortReason() {
        return service.runAborted() ? EvalJudgeFailureTracker.ABORT_REASON_AI_UNAVAILABLE : null;
    }
}
