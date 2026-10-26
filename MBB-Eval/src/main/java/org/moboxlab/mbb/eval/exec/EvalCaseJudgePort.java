package org.moboxlab.mbb.eval.exec;

import org.moboxlab.mbb.eval.judge.EvalJudgeResult;

/**
 * 单 case 执行器的判分端口（规格 §6.3、§6.6）。
 *
 * <p>生产装配由 {@code judge.EvalJudgeService} 适配：用 rubric 把
 * {@link EvalCaseJudgeInput#dimensionId()} 解析成维度对象、构造
 * {@code EvalJudgeRequest}，再调用 {@code evaluate(...)} 并回报是否需要中止整 run。
 * 测试注入假件即可覆盖判分成功、invalid、error 与熔断中止。</p>
 */
public interface EvalCaseJudgePort {

    /**
     * 发起一次 judge 物理调用。
     *
     * @param input 判分入参，各字段已按 §6.3.3 裁剪好上下文
     * @return judge 结果；不得返回 null（返回 null 由调用器按内部错误处理）
     */
    EvalJudgeResult judge(EvalCaseJudgeInput input);

    /** 判分失败熔断是否已触发（§6.8、H-3）；触发后执行器应停止整 run。 */
    boolean aborted();

    /** 熔断中止原因，例如 {@code ai-unavailable}；未熔断时返回 null。 */
    String abortReason();
}
