package org.moboxlab.mbb.eval.judge;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginLogger;
import org.moboxlab.moboxbot.API.PluginService;

/**
 * 生产用判分 chat 客户端：经 MBB-AI 公开服务执行一次 chat（规格 §6.3、O34 接线）。
 *
 * <p>只调用 MBB-AI 的 {@code chat} 动作，不 import 其内部类、不直接访问供应商配置。
 * 服务缺失、动作失败或出参缺字段都收敛为 {@link EvalJudgeChatResponse#error}，
 * 由 EvalJudge 按 errorType/retryable 决定重试，绝不在这里抛异常打断 run。</p>
 */
public final class EvalAiChatClient implements EvalJudgeChatClient {

    /** MBB-AI 注册的服务名。 */
    public static final String SERVICE_NAME = "MBB-AI";
    /** chat 动作名。 */
    public static final String ACTION_CHAT = "chat";

    private final Plugin plugin;
    private final PluginLogger logger;

    public EvalAiChatClient(Plugin plugin, PluginLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    @Override
    public EvalJudgeChatResponse chat(EvalJudgePrompt prompt, EvalJudgeChatParams params) {
        if (prompt == null || params == null) {
            return EvalJudgeChatResponse.error("params", false, "未提供判分提示词或调用参数");
        }
        PluginService service = aiService();
        if (service == null) {
            return EvalJudgeChatResponse.error("internal", false,
                    "MBB-AI 服务不可用（插件缺失或未启用），无法执行判分");
        }
        JSONObject wire;
        try {
            wire = service.call(ACTION_CHAT, buildParams(prompt, params));
        } catch (RuntimeException e) {
            return EvalJudgeChatResponse.error("internal", false,
                    "调用 MBB-AI chat 异常：" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : "：" + e.getMessage()));
        }
        if (wire == null) {
            return EvalJudgeChatResponse.error("internal", false, "MBB-AI chat 返回为空");
        }
        if (!wire.getBooleanValue("status")) {
            String errorType = wire.getString("errorType");
            String message = wire.getString("message");
            return EvalJudgeChatResponse.error(errorType == null ? "internal" : errorType,
                    wire.getBooleanValue("retryable"),
                    message == null || message.isEmpty() ? "MBB-AI chat 失败" : message);
        }
        String content = wire.getString("content");
        if (content == null) {
            return EvalJudgeChatResponse.error("internal", false, "MBB-AI chat 成功但缺少 content");
        }
        String finishReason = wire.getString("finishReason");
        return EvalJudgeChatResponse.ok(content,
                finishReason == null || finishReason.isEmpty()
                        ? EvalJudgeChatResponse.FINISH_STOP : finishReason,
                wire.getBooleanValue("cached"));
    }

    private JSONObject buildParams(EvalJudgePrompt prompt, EvalJudgeChatParams params) {
        JSONArray messages = new JSONArray();
        messages.add(message("system", prompt.system()));
        messages.add(message("user", prompt.user()));
        JSONObject wire = new JSONObject(true);
        wire.put("messages", messages);
        if (params.profile() != null && !params.profile().trim().isEmpty()) {
            wire.put("profile", params.profile());
        }
        wire.put("temperature", Double.valueOf(params.temperature()));
        wire.put("maxTokens", Integer.valueOf(params.maxTokens()));
        wire.put("timeoutSeconds", Integer.valueOf(params.timeoutSeconds()));
        wire.put("retryCount", Integer.valueOf(params.retryCount()));
        return wire;
    }

    private JSONObject message(String role, String content) {
        JSONObject message = new JSONObject(true);
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private PluginService aiService() {
        if (plugin == null || plugin.getServer() == null) {
            return null;
        }
        return plugin.getServer().getPluginManager().getService(SERVICE_NAME);
    }

    /** 便于排查：只在客户端被显式构造时输出一次装配日志。 */
    public void logWired() {
        if (logger != null) {
            logger.sendInfo("[MBB-Eval] 判分 chat 客户端已接入 MBB-AI（动作 " + ACTION_CHAT + "）");
        }
    }
}
