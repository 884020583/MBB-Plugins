package org.moboxlab.mbb.eval.calib;

import java.util.ArrayList;
import java.util.List;
import org.moboxlab.mbb.eval.judge.EvalJudgeResult;
import org.moboxlab.mbb.eval.log.EvalLog;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * 校准自证执行器（规格 §6.7.4、§8.6 第 8 节）。
 *
 * <p>按固定顺序执行 3 条注入违例样本，把 judge 的 invalid/error 或适配器异常一律收敛为失败结果，
 * 不向调用方抛异常。每次 {@link #run(String)} 只使用局部状态，连续调用互不影响。</p>
 */
public final class EvalCalibrationRunner {

    /** 每条样本执行前的中止判定；适配器把它接预算 STOP、熔断或判分失败。 */
    public interface StopCondition {
        /** true 表示不再执行后续样本，已执行的样本仍保留在结果中。 */
        boolean stop();
    }

    private final EvalCalibrationJudgePort judgePort;
    private final PluginLogger logger;

    /**
     * @param judgePort judge 接缝，不能为空
     * @param logger    可空；为空时只返回结果不写日志
     */
    public EvalCalibrationRunner(EvalCalibrationJudgePort judgePort, PluginLogger logger) {
        if (judgePort == null) {
            throw new IllegalArgumentException("judgePort 不能为空");
        }
        this.judgePort = judgePort;
        this.logger = logger;
    }

    /**
     * 执行 3 条固定样本，返回不可变校准结果；judge 失败不会抛出。
     */
    public EvalCalibrationResult run(String runId) {
        return run(runId, null);
    }

    /**
     * 执行固定样本，并在每条样本前检查中止条件（预算 STOP、熔断或判分失败）。
     *
     * <p>中止只截断后续样本，已执行样本的结果照样返回：调用方据此把「校准未跑完」写进
     * calibration_json，同时不让校准失败波及 run 主流程（§6.7.4）。</p>
     *
     * @param stopCondition 可空；null 表示跑满 3 条
     */
    public EvalCalibrationResult run(String runId, StopCondition stopCondition) {
        String id = runId == null ? "" : runId;
        List<EvalCalibrationSample> samples = EvalCalibrationSample.all();
        EvalLog.info(logger, "开始校准自证 run=" + id + " 样本数=" + samples.size());
        List<EvalCalibrationResult.SampleOutcome> outcomes =
                new ArrayList<EvalCalibrationResult.SampleOutcome>(samples.size());
        boolean boundaryVetoed = false;
        for (EvalCalibrationSample sample : samples) {
            if (stopCondition != null && stopCondition.stop()) {
                EvalLog.warn(logger, "校准自证提前中止 run=" + id + "，已执行样本=" + outcomes.size());
                break;
            }
            if (EvalCalibrationSample.DIMENSION_BOUNDARY.equals(sample.dimensionId())) {
                String veto = safeBoundaryVeto(sample);
                if (veto == null) {
                    EvalLog.warn(logger, "边界硬项 veto 未命中 case=" + sample.caseId());
                } else {
                    boundaryVetoed = true;
                    EvalLog.info(logger, "边界硬项 veto 命中 case=" + sample.caseId()
                            + " reason=" + veto);
                }
            }
            outcomes.add(runOne(sample));
        }
        EvalCalibrationResult result = new EvalCalibrationResult(id, outcomes, boundaryVetoed);
        for (String warning : result.warnings()) {
            EvalLog.warn(logger, "校准告警：" + warning);
        }
        if (result.passed()) {
            EvalLog.info(logger, "校准自证通过 run=" + id);
        } else {
            EvalLog.warn(logger, "校准自证未通过 run=" + id
                    + " evidence=" + result.evidenceFailures() + "/" + result.evidenceTotal());
        }
        return result;
    }

    /**
     * 边界样本先过硬项预检：用固定回复拼接端口注入的人设片段，再交给生产预检实现。
     *
     * <p>预检异常一律收敛为「未命中」，不阻断后续 judge 调用。</p>
     */
    private String safeBoundaryVeto(EvalCalibrationSample sample) {
        try {
            String fragment = judgePort.personaFragment();
            String testedReply = sample.reply() + (fragment == null ? "" : fragment);
            String summary = judgePort.personaSummary();
            return judgePort.boundaryVeto(testedReply, summary == null ? "" : summary);
        } catch (RuntimeException e) {
            EvalLog.error(logger, "边界硬项预检异常 case=" + sample.caseId() + " "
                    + e.getClass().getSimpleName());
            return null;
        }
    }

    private EvalCalibrationResult.SampleOutcome runOne(EvalCalibrationSample sample) {
        try {
            EvalJudgeResult judgeResult = judgePort.judge(sample);
            if (judgeResult == null) {
                EvalLog.error(logger, "校准样本 " + sample.caseId() + " judge 返回 null");
                return EvalCalibrationResult.SampleOutcome.failure(sample,
                        "judge 返回 null（适配器实现缺陷）");
            }
            EvalCalibrationResult.SampleOutcome outcome =
                    EvalCalibrationResult.SampleOutcome.fromJudge(sample, judgeResult);
            if (judgeResult.isInvalid()) {
                EvalLog.warn(logger, "校准样本 " + sample.caseId() + " judge invalid kind="
                        + judgeResult.errorKind());
            } else if (judgeResult.isError()) {
                EvalLog.warn(logger, "校准样本 " + sample.caseId() + " judge error kind="
                        + judgeResult.errorKind());
            } else {
                EvalLog.info(logger, "校准样本 " + sample.caseId() + " label=" + judgeResult.label());
            }
            return outcome;
        } catch (RuntimeException e) {
            EvalLog.error(logger, "校准样本 " + sample.caseId() + " judge 调用异常 "
                    + e.getClass().getSimpleName());
            return EvalCalibrationResult.SampleOutcome.failure(sample,
                    "judge 调用异常：" + e.getClass().getSimpleName());
        }
    }
}
