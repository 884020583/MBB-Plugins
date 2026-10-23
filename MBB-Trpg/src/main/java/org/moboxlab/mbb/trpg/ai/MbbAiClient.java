package org.moboxlab.mbb.trpg.ai;

import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginService;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * MBB-AI {@code chat} 服务的插件侧适配：把 {@link AiRequest} 翻译成消息与参数，再把响应映射成
 * {@link AiResult}。
 *
 * <p>只通过公开的 {@link PluginService#call(String, JSONObject)} 访问 MBB-AI，不触碰主程序内部类。
 * 网络与参数异常统一折叠成失败结果，不向上抛异常。</p>
 */
public final class MbbAiClient implements AiClient {

    /** MBB-AI 服务名。 */
    public static final String SERVICE_NAME = "MBB-AI";

    private final Plugin plugin;

    public MbbAiClient(Plugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("插件实例不能为空");
        }
        this.plugin = plugin;
    }

    @Override
    public AiResult chat(AiRequest request) {
        long startTime = System.currentTimeMillis();
        if (request == null) {
            return AiResult.failure(AiErrorTypes.PARAMS, false, 0L);
        }
        PluginService ai = plugin.getServer().getPluginManager().getService(SERVICE_NAME);
        if (ai == null) {
            return AiResult.failure(AiErrorTypes.DISABLED, false, elapsed(startTime));
        }
        JSONObject params = new JSONObject(true);
        params.put("messages", messages(request));
        if (request.timeoutSeconds() > 0) {
            params.put("timeoutSeconds", request.timeoutSeconds());
        }
        if (request.maxTokens() > 0) {
            params.put("maxTokens", request.maxTokens());
        }
        if (request.temperature() >= 0.0D) {
            params.put("temperature", request.temperature());
        }
        params.put("retryCount", 0);
        JSONObject response;
        try {
            response = ai.call("chat", params);
        } catch (RuntimeException error) {
            return AiResult.failure(AiErrorTypes.UNKNOWN, true, elapsed(startTime));
        }
        long elapsed = elapsed(startTime);
        if (response == null) {
            return AiResult.failure(AiErrorTypes.UNKNOWN, true, elapsed);
        }
        if (response.getBooleanValue("status")) {
            JSONObject usage = response.getJSONObject("usage");
            return AiResult.success(response.getString("content"),
                    response.getString("finishReason"),
                    usage == null ? "" : usage.toJSONString(),
                    response.getBooleanValue("cached"), elapsed);
        }
        String code = response.getString("code");
        boolean retryable = response.getBooleanValue("retryable");
        return AiResult.failure(mapError(code), retryable, elapsed);
    }

    private static JSONArray messages(AiRequest request) {
        JSONArray messages = new JSONArray();
        if (!request.stablePrefix().isEmpty()) {
            messages.add(message("system", request.stablePrefix()));
        }
        StringBuilder userText = new StringBuilder();
        if (!request.cacheable().isEmpty()) {
            userText.append(request.cacheable());
        }
        if (!request.dynamic().isEmpty()) {
            if (userText.length() > 0) {
                userText.append('\n');
            }
            userText.append(request.dynamic());
        }
        if (userText.length() > 0) {
            messages.add(message("user", userText.toString()));
        }
        return messages;
    }

    private static JSONObject message(String role, String content) {
        JSONObject message = new JSONObject(true);
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    /**
     * 把 MBB-AI 的错误码折叠成插件侧六类错误里的底层类型。
     *
     * @param code MBB-AI 返回的 code，可能为空
     */
    public static String mapError(String code) {
        if (code == null || code.isEmpty()) {
            return AiErrorTypes.UNKNOWN;
        }
        if ("disabled".equals(code)) {
            return AiErrorTypes.DISABLED;
        }
        if ("busy".equals(code)) {
            return AiErrorTypes.BUSY;
        }
        if ("interrupted".equals(code) || "timeout".equals(code)) {
            return AiErrorTypes.TIMEOUT;
        }
        if ("params".equals(code)) {
            return AiErrorTypes.PARAMS;
        }
        return AiErrorTypes.UNKNOWN;
    }

    private static long elapsed(long startTime) {
        return System.currentTimeMillis() - startTime;
    }
}
