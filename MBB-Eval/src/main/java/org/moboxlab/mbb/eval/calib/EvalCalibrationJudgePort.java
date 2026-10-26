package org.moboxlab.mbb.eval.calib;

import org.moboxlab.mbb.eval.judge.EvalJudgeResult;

/**
 * 校准样本到 judge 的单方法端口（规格 §6.7.4）。
 *
 * <p>本切片只定义接缝，方便用假实现做单元测试；接线阶段由 Lead 用
 * {@code EvalCallInvoker} / {@code EvalJudgeService} 适配真实 judge 调用，
 * 并复用 {@code EvalCallRecorder#recordCalibration} 记录 stage=calib 的调用行。</p>
 */
public interface EvalCalibrationJudgePort {

    /**
     * 送一条固定校准样本给 judge，返回其原始判定结果。
     *
     * <p>实现可以返回 invalid/error；不得把 judge 失败折成 label=0。</p>
     */
    EvalJudgeResult judge(EvalCalibrationSample sample);

    /**
     * 带 run_id 的校准判定入口。
     *
     * <p>接口本身没有 run 上下文，生产适配器覆写本方法把真实 run_id 写进
     * {@code EvalJudgeRequest}；只实现 {@link #judge(EvalCalibrationSample)} 的假实现沿用默认
     * 退化路径，便于既有单元测试不感知 run 维度。</p>
     */
    default EvalJudgeResult judge(String runId, EvalCalibrationSample sample) {
        return judge(sample);
    }
}
