package org.moboxlab.mbb.eval.exec;

import java.util.Collections;

import org.moboxlab.mbb.eval.calib.EvalCalibrationJudgePort;
import org.moboxlab.mbb.eval.calib.EvalCalibrationSample;
import org.moboxlab.mbb.eval.judge.EvalJudgeFailureTracker;
import org.moboxlab.mbb.eval.judge.EvalJudgeOutcome;
import org.moboxlab.mbb.eval.judge.EvalJudgeRequest;
import org.moboxlab.mbb.eval.judge.EvalJudgeResult;
import org.moboxlab.mbb.eval.judge.EvalJudgeService;
import org.moboxlab.mbb.eval.rubric.EvalRubric;

/**
 * 生产用校准判分端口：把固定校准样本转成 {@link EvalJudgeRequest} 交给 EvalJudgeService。
 *
 * <p>本适配器只做样本到判分请求的转换与结果透传；预算占位、stage=calib 调用行与
 * calls_used 自增由 {@code EvalCallInvoker#judgeCalibration} 在调用侧完成（§6.7.4、§9.5）。</p>
 *
 * <p>边界样本的 veto 判定当前没有独立通道（§6.3.5 硬项尚未产出 veto 标记）：本适配器只把
 * boundary 样本照常送 judge，由 {@code EvalCalibrationResult} 用 label==0 兜底判定门槛。
 * 这是 §6.7.4 的 v1 近似，硬项 veto 接线后应改为读取 veto 结果。</p>
 */
public final class EvalServiceCalibrationJudgePort implements EvalCalibrationJudgePort {

    /** 接口的默认退化路径没有 run 上下文；这里用一个合法占位 id，落库仍用调用方传入的真实 run_id。 */
    private static final String PLACEHOLDER_RUN_ID = "calib-run";

    private final EvalJudgeService service;
    private final EvalRubric rubric;
    private final String personaSummary;

    /**
     * @param service       判分服务，不能为空
     * @param rubric        当前 rubric，不能为空
     * @param personaSummary 角色档案摘要，不能为空（judge 请求校验要求 1–400 码点）
     */
    public EvalServiceCalibrationJudgePort(EvalJudgeService service, EvalRubric rubric,
            String personaSummary) {
        if (service == null) {
            throw new IllegalArgumentException("校准判分端口缺少 EvalJudgeService");
        }
        if (rubric == null) {
            throw new IllegalArgumentException("校准判分端口缺少 rubric");
        }
        if (personaSummary == null || personaSummary.trim().isEmpty()) {
            throw new IllegalArgumentException("校准判分端口缺少角色档案摘要");
        }
        this.service = service;
        this.rubric = rubric;
        this.personaSummary = personaSummary.trim();
    }

    @Override
    public EvalJudgeResult judge(EvalCalibrationSample sample) {
        return judge(PLACEHOLDER_RUN_ID, sample);
    }

    @Override
    public EvalJudgeResult judge(String runId, EvalCalibrationSample sample) {
        if (sample == null) {
            return EvalJudgeResult.error("persona", EvalJudgeResult.KIND_TRANSPORT, "internal", false,
                    0, null, "未提供校准样本", null, 0);
        }
        String dimensionId = sample.dimensionId();
        EvalRubric.Dimension dimension = rubric.dimension(dimensionId);
        if (dimension == null) {
            return EvalJudgeResult.invalid(dimensionId, EvalJudgeResult.KIND_PROFILE, 0, null,
                    "校准维度不在 rubric 中：" + dimensionId, null, 0);
        }
        if (service.runAborted()) {
            return EvalJudgeResult.error(dimensionId, EvalJudgeResult.KIND_TRANSPORT,
                    EvalJudgeFailureTracker.ABORT_REASON_AI_UNAVAILABLE, false, 0, null,
                    "判分已熔断（ai-unavailable）", null, 0);
        }
        String effectiveRunId = runId == null || runId.trim().isEmpty() ? PLACEHOLDER_RUN_ID : runId;
        EvalJudgeRequest request;
        try {
            request = EvalJudgeRequest.builder(effectiveRunId, sample.caseId())
                    .sampleIndex(0)
                    .attempt(0)
                    .dimension(dimension)
                    .personaSummary(personaSummary)
                    .history(sample.history())
                    .evidenceTurns(evidenceTurns(sample))
                    .instruction(sample.instruction())
                    .focus(sample.violation())
                    .replyContent(sample.reply())
                    .softObservations(Collections.<String>emptyList())
                    .promptVersion(rubric.promptVersion())
                    .build();
        } catch (RuntimeException e) {
            return EvalJudgeResult.invalid(dimensionId, EvalJudgeResult.KIND_PROFILE, 0, null,
                    "校准判分入参校验失败：" + e.getMessage(), null, 0);
        }
        EvalJudgeOutcome outcome;
        try {
            outcome = service.evaluate(request);
        } catch (RuntimeException e) {
            return EvalJudgeResult.error(dimensionId, EvalJudgeResult.KIND_TRANSPORT, "internal", false,
                    0, null, "校准判分调用异常：" + e.getClass().getSimpleName(), null, 0);
        }
        if (outcome == null || outcome.result() == null) {
            return EvalJudgeResult.error(dimensionId, EvalJudgeResult.KIND_TRANSPORT,
                    EvalJudgeFailureTracker.ABORT_REASON_AI_UNAVAILABLE, false, 0, null,
                    "校准判分服务未返回结果（判定 AI 不可用）", null, 0);
        }
        return outcome.result();
    }

    /**
     * 记忆维的证据轮：校准样本固定把末轮当作事实证据；其他维度没有证据轮。
     */
    private static java.util.List<Integer> evidenceTurns(EvalCalibrationSample sample) {
        if (!EvalCalibrationSample.DIMENSION_MEMORY.equals(sample.dimensionId())
                || sample.history().isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(Integer.valueOf(sample.history().size() - 1));
    }
}
