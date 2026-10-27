package org.moboxlab.mbb.eval.calib;

import org.moboxlab.mbb.eval.judge.EvalJudgeResult;

/**
 * 校准样本到 judge 的接缝（规格 §6.7.4）。
 *
 * <p>生产适配器复用 {@code EvalCallInvoker} / {@code EvalJudgeService} 完成真实判分，
 * 并通过 {@code EvalCallRecorder#recordCalibration} 记录 stage=calib 的调用行；
 * 边界样本另走 {@link #boundaryVeto(String, String)} 的硬项预检。</p>
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

    /**
     * 边界样本的 §6.3.5 硬项预检：命中返回 veto 原因，未命中返回 null。
     *
     * <p>默认不预检，保证既有假实现不用改动；生产适配器必须覆写并复用
     * {@code EvalVetoPrecheck}，否则边界门槛无法自证。</p>
     *
     * @param reply         被测回复（已按需拼接人设片段）
     * @param personaSummary 人设摘要
     */
    default String boundaryVeto(String reply, String personaSummary) {
        return null;
    }

    /**
     * 注入到边界样本回复的人设片段，用于稳定触发泄露硬项。
     *
     * <p>默认不注入；生产适配器返回当前人设摘要的前 20 个码点，避免把题库人设句子写死进样本。</p>
     */
    default String personaFragment() {
        return "";
    }

    /** 当前人设摘要，供边界预检使用；没有时返回空串。 */
    default String personaSummary() {
        return "";
    }
}
