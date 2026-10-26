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
}
