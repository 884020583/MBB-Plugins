package org.moboxlab.mbb.eval.exec;

import org.moboxlab.mbb.eval.gateway.EvalGenerateRequest;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;

/**
 * 单 case 执行器的生成端口（规格 §10.2）。
 *
 * <p>执行器只负责拼装 {@link EvalGenerateRequest}，真正把请求发给 MBB-Roleplay 的
 * {@code eval-generate} 动作由本端口实现。生产装配直接适配
 * {@code gateway.RoleplayEvalGateway#generate(EvalGenerateRequest)}；测试注入假件，
 * 从而不联网、不依赖插件宿主。</p>
 */
public interface EvalCaseGeneratePort {

    /**
     * 发起一次 {@code eval-generate} 物理调用。
     *
     * @param request 已按 §10.2 填好入参的请求
     * @return 网关解析后的结果；不得返回 null（返回 null 由调用器按内部错误处理）
     */
    EvalGenerateResult generate(EvalGenerateRequest request);
}
