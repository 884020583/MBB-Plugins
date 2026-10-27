package org.moboxlab.mbb.eval.gateway;

import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginLogger;
import org.moboxlab.moboxbot.API.PluginService;

/**
 * 对 MBB-Roleplay 公开动作的封装（规格 §10、§11.1）。
 *
 * <p>只经 {@code API.PluginManager.getService("MBB-Roleplay")} 取服务，调用
 * {@code capabilities}（版本协商）与 {@code eval-generate}（生成一条评测回复）两个动作；
 * 不 import MBB-Roleplay 的任何内部类，不复刻提示词装配，不读 Roleplay 的状态表与 trace 表。</p>
 *
 * <p>角色侧接口缺失时给出明确错误而不是静默降级：服务取不到、动作未实现（旧版 Roleplay
 * 对未知动作回「不支持的动作」）、出参缺必需字段、回显 sessionId 与本地编码不一致，
 * 都收敛为 {@link EvalGenerateResult#contractError(String)} 的 {@code internal} 且不可重试，
 * 本类按 §13.6 判1 记 ERROR，调用方按样本失败处理（§10.6.1）。</p>
 */
public final class RoleplayEvalGateway {

    /** MBB-Roleplay 注册的服务名。 */
    public static final String SERVICE_NAME = "MBB-Roleplay";
    /** 版本协商动作名（§10.1）。 */
    public static final String ACTION_CAPABILITIES = "capabilities";
    /** 生成动作名（§10.2、§10.3）。 */
    public static final String ACTION_EVAL_GENERATE = "eval-generate";

    private final Plugin plugin;
    private final PluginLogger logger;

    /**
     * @param plugin 用于每次调用时重新取服务（Roleplay 重载后不会持有旧实例）
     * @param logger 契约违例告警出口，可为 {@code null}
     */
    public RoleplayEvalGateway(Plugin plugin, PluginLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    /**
     * 取 {@code capabilities} 原始出参。
     *
     * <p>服务缺失、插件未启用或调用抛异常时返回 {@code null}，不在这里写日志：
     * 预检侧统一按 §10.1 的「三选一」给出可读拒绝原因，并保证 A11 的「日志仅一次 WARN」。</p>
     */
    public JSONObject fetchCapabilities() {
        PluginService service = roleplayService();
        if (service == null) {
            return null;
        }
        try {
            return service.call(ACTION_CAPABILITIES, new JSONObject());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 调用 {@code eval-generate} 生成一条评测回复。
     *
     * <p>本地先做入参校验（§10.2 规则 4、5），失败不发调用；随后校验出参必需字段
     * （§10.3）并核对回显 sessionId。返回结果中的 {@code retryable} 直接来自角色侧出参，
     * 本方法不做重试、不落表、不写 trace（并发/超时/重试/落表属 runner 的 H-2）。</p>
     */
    public EvalGenerateResult generate(EvalGenerateRequest request) {
        if (request == null) {
            return contractError("未提供 eval-generate 请求");
        }
        EvalGenerateRequest.Problem problem = request.validate();
        if (problem != null) {
            // 本地校验失败属实现缺陷（§13.6 判1：invalid-param / history-too-long 记 ERROR）
            logError("eval-generate 入参校验失败：" + problem.message());
            return EvalGenerateResult.error(problem.errorType(), false, problem.message());
        }
        PluginService service = roleplayService();
        if (service == null) {
            return contractError("MBB-Roleplay 服务不可用（插件缺失或未启用），无法调用 eval-generate；"
                    + "请安装并启用 MBB-Roleplay 后重试");
        }
        JSONObject wire;
        try {
            wire = service.call(ACTION_EVAL_GENERATE, request.toParams());
        } catch (RuntimeException e) {
            return contractError("调用 eval-generate 异常：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()));
        }
        if (wire == null) {
            return contractError("eval-generate 返回为空");
        }
        if (Boolean.FALSE.equals(wire.get("status")) && wire.get("errorType") == null) {
            String reason = wire.getString("message");
            String detail = reason == null || reason.isEmpty() ? "" : "：" + reason;
            if (reason != null && reason.contains("不支持的动作")) {
                // 旧版 Roleplay：未知动作只回 status=false + message
                return contractError("MBB-Roleplay 未提供 eval-generate 动作（actions 未声明该动作）" + detail
                        + "；请升级 MBB-Roleplay 到支持 evalApiVersion=1 的版本并开启 evalApiEnable");
            }
            // 其他缺 errorType 的失败出参属契约违例（§10.3 规则 1：status=false 必须带 errorType）
            return contractError("eval-generate 失败但未提供 errorType" + detail
                    + "；请升级 MBB-Roleplay 到支持 evalApiVersion=1 的版本");
        }
        EvalGenerateResult result = EvalGenerateResult.parse(wire, request.sessionId());
        if (!result.status() && EvalGenerateResult.ERROR_INTERNAL.equals(result.errorType())) {
            logError("eval-generate 契约异常：" + result.message());
        }
        return result;
    }

    /** 每次调用重新取服务：Roleplay 被重载或停用后不会命中旧实例。 */
    private PluginService roleplayService() {
        if (plugin == null || plugin.getServer() == null) {
            return null;
        }
        return plugin.getServer().getPluginManager().getService(SERVICE_NAME);
    }

    private EvalGenerateResult contractError(String message) {
        logError("eval-generate 契约异常：" + message);
        return EvalGenerateResult.contractError(message);
    }

    /** 接口层实现缺陷与契约违例按 §13.6 判1 记 ERROR（预检侧的 WARN 由 EvalPreflight 负责）。 */
    private void logError(String message) {
        if (logger != null) {
            logger.sendError("[MBB-Eval] " + message);
        }
    }
}
