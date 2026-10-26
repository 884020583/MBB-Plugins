package org.moboxlab.mbb.eval.runner;

/**
 * 命令层可见的 run 受理面（规格 §9.4、§16.8 G-2）。
 *
 * <p>只暴露命令线程需要的四个动作：受理（受理、建行、加锁、提交 executor 全部由实现负责）、
 * 中止、最近一次快照、按 runId 取快照。命令层不得通过本接口做任何 AI 调用，也不得轮询等待
 * run 结束；受理与中止都必须立即返回一个结果供命令层拼回执。</p>
 *
 * <p>实现方为 {@link EvalRunner}。接口单独存在的目的是让命令层的用例可以注入假实现，
 * 在公共接缝上断言「命令线程只委派一次、不自行收尾」。</p>
 */
public interface EvalRunService {

    /**
     * 受理一次 /eval run。
     *
     * @param tier {@code smoke}/{@code full}，null 取 {@code evalDefaultTier}
     * @return 受理结果；BUSY 时带上当前 runId 与进度，命令层据此回当前 runId，不排队、不新建行
     */
    EvalRunner.AcceptResult accept(String tier);

    /**
     * 中止当前 run（§9.6：终态 CAS，回执不等在途调用收尾）。
     *
     * @return 中止结果；没有进行中的 run 时返回 {@link EvalRunner.AbortKind#NONE}
     */
    EvalRunner.AbortResult abort();

    /** 最近一次 run 的快照；没有任何记录返回 null。 */
    EvalRunner.RunSnapshot snapshot();

    /** 指定 run 的快照；不存在返回 null。 */
    EvalRunner.RunSnapshot snapshot(String runId);
}
