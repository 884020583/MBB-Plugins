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
        String id = runId == null ? "" : runId;
        List<EvalCalibrationSample> samples = EvalCalibrationSample.all();
        EvalLog.info(logger, "开始校准自证 run=" + id + " 样本数=" + samples.size());
        List<EvalCalibrationResult.SampleOutcome> outcomes =
                new ArrayList<EvalCalibrationResult.SampleOutcome>(samples.size());
        for (EvalCalibrationSample sample : samples) {
            outcomes.add(runOne(sample));
        }
        EvalCalibrationResult result = new EvalCalibrationResult(id, outcomes, false);
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
