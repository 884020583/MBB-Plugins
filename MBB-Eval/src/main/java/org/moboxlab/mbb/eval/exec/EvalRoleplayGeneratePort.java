package org.moboxlab.mbb.eval.exec;

import org.moboxlab.mbb.eval.gateway.EvalGenerateRequest;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;
import org.moboxlab.mbb.eval.gateway.RoleplayEvalGateway;

/**
 * 生产用生成端口：只把 {@link EvalCaseExecutor} 拼好的请求转给 MBB-Roleplay。
 *
 * <p>预算占位、重试/重采与 {@code _call} 落行都在 {@link org.moboxlab.mbb.eval.call.EvalCallInvoker}
 * 内完成，本适配器不得再记账，否则同一次调用会被计两次（§9.5）。</p>
 */
public final class EvalRoleplayGeneratePort implements EvalCaseGeneratePort {

    private final RoleplayEvalGateway gateway;

    public EvalRoleplayGeneratePort(RoleplayEvalGateway gateway) {
        if (gateway == null) {
            throw new IllegalArgumentException("生成端口缺少 RoleplayEvalGateway");
        }
        this.gateway = gateway;
    }

    @Override
    public EvalGenerateResult generate(EvalGenerateRequest request) {
        if (request == null) {
            return EvalGenerateResult.error(EvalGenerateResult.ERROR_INVALID_PARAM, false,
                    "未提供 eval-generate 请求");
        }
        return gateway.generate(request);
    }
}
