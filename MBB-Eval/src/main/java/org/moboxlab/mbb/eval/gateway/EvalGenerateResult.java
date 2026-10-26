package org.moboxlab.mbb.eval.gateway;

import com.alibaba.fastjson.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * {@code eval-generate} 的出参模型（规格 §10.3）。
 *
 * <p>{@link #parse(JSONObject, String)} 负责「出参必需字段校验」：缺字段、类型不符、
 * {@code status=false} 却带 {@code content}、{@code attempts} 不是 1、回显 sessionId 与本侧
 * 编码不一致等契约违例，一律收敛为本侧 {@code internal} 错误并给出可读原因，
 * 不做「尽力读取」式的字段探测（§10.1 规则 3 的同一原则）。</p>
 *
 * <p>{@code errorType} 只承载 §10.3 的封闭集合；是否重试以出参 {@code retryable} 为准
 * （§10.6.1），{@link #disposition()} 只是把错误分到评测侧的处置类别，不替代重试判定。</p>
 */
public final class EvalGenerateResult {

    /** errorType 封闭集合（§10.3），不得新增值。 */
    public static final String ERROR_DISABLED = "disabled";
    public static final String ERROR_INVALID_PARAM = "invalid-param";
    public static final String ERROR_HISTORY_TOO_LONG = "history-too-long";
    public static final String ERROR_PROFILE_INVALID = "profile-invalid";
    public static final String ERROR_AI_MISSING = "ai-missing";
    public static final String ERROR_BUSY = "busy";
    public static final String ERROR_TIMEOUT = "timeout";
    public static final String ERROR_INTERNAL = "internal";

    private static final Set<String> KNOWN_ERROR_TYPES = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList(ERROR_DISABLED, ERROR_INVALID_PARAM, ERROR_HISTORY_TOO_LONG, ERROR_PROFILE_INVALID,
                    ERROR_AI_MISSING, ERROR_BUSY, ERROR_TIMEOUT, ERROR_INTERNAL)));

    /** 评测侧处置类别（§10.6.1）：仅用于决定停止 run、记 ERROR 或提示配置，不用于决定重试。 */
    public enum Disposition {
        /** 接口被关闭：停止本次 run，重试无意义。 */
        STOP_RUN,
        /** 可重试类错误：仍以出参 retryable 为准。 */
        RETRY_ALLOWED,
        /** 实现或题面缺陷：不重试，记 ERROR。 */
        ERROR_LOG,
        /** 配置问题：提示修改 evalProfile / evalJudgeProfile。 */
        CONFIG_HINT,
        /** 依赖缺失：提示安装或启用 MBB-AI。 */
        INSTALL_HINT
    }

    /** usage 四字段（§10.3 规则 7：cachedPromptTokens 缺失按 0 计并保留字段）。 */
    public static final class Usage {
        private final long promptTokens;
        private final long completionTokens;
        private final long totalTokens;
        private final long cachedPromptTokens;

        public Usage(long promptTokens, long completionTokens, long totalTokens, long cachedPromptTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
            this.cachedPromptTokens = cachedPromptTokens;
        }

        public long promptTokens() {
            return promptTokens;
        }

        public long completionTokens() {
            return completionTokens;
        }

        public long totalTokens() {
            return totalTokens;
        }

        public long cachedPromptTokens() {
            return cachedPromptTokens;
        }
    }

    private final boolean status;
    private final String errorType;
    private final boolean retryable;
    private final String message;
    private final String content;
    private final boolean skip;
    private final String finishReason;
    private final String model;
    private final String profile;
    private final Usage usage;
    private final long elapsedMs;
    private final boolean cached;
    private final int attempts;
    private final String sessionId;
    private final String requestId;
    private final String personaId;
    private final String personaHash;

    private EvalGenerateResult(boolean status, String errorType, boolean retryable, String message, String content,
                               boolean skip, String finishReason, String model, String profile, Usage usage,
                               long elapsedMs, boolean cached, int attempts, String sessionId, String requestId,
                               String personaId, String personaHash) {
        this.status = status;
        this.errorType = errorType;
        this.retryable = retryable;
        this.message = message;
        this.content = content;
        this.skip = skip;
        this.finishReason = finishReason;
        this.model = model;
        this.profile = profile;
        this.usage = usage;
        this.elapsedMs = elapsedMs;
        this.cached = cached;
        this.attempts = attempts;
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.personaId = personaId;
        this.personaHash = personaHash;
    }

    /**
     * 构造失败结果；{@code status=false} 时不携带 content（§10.3 规则 2）。
     */
    public static EvalGenerateResult error(String errorType, boolean retryable, String message) {
        String code = errorType == null || !KNOWN_ERROR_TYPES.contains(errorType) ? ERROR_INTERNAL : errorType;
        return new EvalGenerateResult(false, code, retryable, message, null, false, null, null, null, null,
                0L, false, 1, null, null, null, null);
    }

    /** 本侧契约违例：统一收敛为 internal，不重试，并保留可读原因便于排查。 */
    public static EvalGenerateResult contractError(String message) {
        return error(ERROR_INTERNAL, false, message);
    }

    /**
     * 校验并解析出参。
     *
     * @param wire             Roleplay 公开动作返回的 JSON
     * @param expectedSessionId 本侧按 §10.5 编码出的 sessionId，用于核对回显
     * @return 合法响应的结果模型；契约违例返回 {@code internal} 失败结果
     */
    public static EvalGenerateResult parse(JSONObject wire, String expectedSessionId) {
        if (wire == null) {
            return contractError("eval-generate 返回为空");
        }
        try {
            return parseChecked(wire, expectedSessionId);
        } catch (RuntimeException e) {
            return contractError("解析 eval-generate 出参异常：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()));
        }
    }

    private static EvalGenerateResult parseChecked(JSONObject wire, String expectedSessionId) {
        Object statusValue = wire.get("status");
        if (!(statusValue instanceof Boolean)) {
            return contractError("出参缺少布尔字段 status");
        }
        boolean ok = ((Boolean) statusValue).booleanValue();
        Object actionValue = wire.get("action");
        if (!(actionValue instanceof String) || !"eval-generate".equals(actionValue)) {
            return contractError("出参 action 必须是 eval-generate，实际为 " + actionValue);
        }
        if (!ok) {
            return parseFailure(wire);
        }
        return parseSuccess(wire, expectedSessionId);
    }

    private static EvalGenerateResult parseFailure(JSONObject wire) {
        Object errorTypeValue = wire.get("errorType");
        if (!(errorTypeValue instanceof String)) {
            return contractError("status=false 但缺少 errorType");
        }
        String code = (String) errorTypeValue;
        if (!KNOWN_ERROR_TYPES.contains(code)) {
            return contractError("未知 errorType：" + code + "（§10.3 为封闭集合，不得新增值）");
        }
        Object retryableValue = wire.get("retryable");
        if (!(retryableValue instanceof Boolean)) {
            return contractError("status=false 但缺少布尔字段 retryable");
        }
        if (wire.containsKey("content")) {
            return contractError("status=false 时不得返回 content（§10.3 规则 2）");
        }
        String reason = wire.getString("message");
        return error(code, ((Boolean) retryableValue).booleanValue(), reason);
    }

    private static EvalGenerateResult parseSuccess(JSONObject wire, String expectedSessionId) {
        if (wire.containsKey("errorType") || wire.containsKey("retryable")) {
            return contractError("status=true 时不得出现 errorType / retryable（§10.3 规则 2）");
        }
        if (!(wire.get("content") instanceof String)) {
            return contractError("status=true 但缺少字段 content");
        }
        Object skipValue = wire.get("skip");
        if (!(skipValue instanceof Boolean)) {
            return contractError("status=true 但缺少布尔字段 skip");
        }
        Object usageValue = wire.get("usage");
        if (!(usageValue instanceof JSONObject)) {
            return contractError("status=true 但缺少对象字段 usage");
        }
        JSONObject usageJson = (JSONObject) usageValue;
        if (!isNumber(usageJson.get("promptTokens")) || !isNumber(usageJson.get("completionTokens"))
                || !isNumber(usageJson.get("totalTokens"))) {
            return contractError("usage 缺少 promptTokens / completionTokens / totalTokens");
        }
        long cachedPromptTokens = isNumber(usageJson.get("cachedPromptTokens"))
                ? usageJson.getLongValue("cachedPromptTokens") : 0L;
        if (!isNumber(wire.get("elapsedMs"))) {
            return contractError("status=true 但缺少数值字段 elapsedMs");
        }
        Object cachedValue = wire.get("cached");
        if (!(cachedValue instanceof Boolean)) {
            return contractError("status=true 但缺少布尔字段 cached");
        }
        if (!isNumber(wire.get("attempts"))) {
            return contractError("status=true 但缺少数值字段 attempts");
        }
        int attempts = wire.getIntValue("attempts");
        if (attempts != 1) {
            return contractError("attempts 必须是 1（§10.3 规则 4），实际为 " + attempts);
        }
        String sessionId = wire.getString("sessionId");
        if (sessionId == null) {
            return contractError("status=true 但缺少字段 sessionId");
        }
        if (expectedSessionId != null && !expectedSessionId.equals(sessionId)) {
            return contractError("回显 sessionId 与本侧编码不一致：期望 " + expectedSessionId
                    + "，实际 " + sessionId);
        }
        String requestId = wire.getString("requestId");
        if (requestId == null) {
            return contractError("status=true 但缺少字段 requestId");
        }
        if (!sessionId.equals(requestId)) {
            return contractError("requestId 与 sessionId 不一致：" + requestId + " != " + sessionId);
        }
        if (!(wire.get("finishReason") instanceof String)) {
            return contractError("status=true 但缺少字段 finishReason");
        }
        // model 允许缺省（C14：MBB-AI 未返回时出参省略该字段），出现时必须是字符串
        Object modelValue = wire.get("model");
        if (modelValue != null && !(modelValue instanceof String)) {
            return contractError("status=true 但 model 不是字符串");
        }
        if (!(wire.get("profile") instanceof String) || ((String) wire.get("profile")).isEmpty()) {
            return contractError("status=true 但缺少字段 profile");
        }
        if (!(wire.get("personaId") instanceof String)) {
            return contractError("status=true 但缺少字段 personaId");
        }
        if (!(wire.get("personaHash") instanceof String)) {
            return contractError("status=true 但缺少字段 personaHash");
        }
        return new EvalGenerateResult(true, null, false, wire.getString("message"), wire.getString("content"),
                ((Boolean) skipValue).booleanValue(), wire.getString("finishReason"), wire.getString("model"),
                wire.getString("profile"), new Usage(usageJson.getLongValue("promptTokens"),
                        usageJson.getLongValue("completionTokens"), usageJson.getLongValue("totalTokens"),
                        cachedPromptTokens), wire.getLongValue("elapsedMs"),
                ((Boolean) cachedValue).booleanValue(), attempts, sessionId, requestId,
                wire.getString("personaId"), wire.getString("personaHash"));
    }

    private static boolean isNumber(Object value) {
        return value instanceof Number;
    }

    public boolean status() {
        return status;
    }

    public String errorType() {
        return errorType;
    }

    /** 是否值得重试：只读 Roleplay 出参的 retryable（§10.6.1），本侧不按 errorType 猜测。 */
    public boolean retryable() {
        return retryable;
    }

    public String message() {
        return message;
    }

    public String content() {
        return content;
    }

    public boolean skip() {
        return skip;
    }

    public String finishReason() {
        return finishReason;
    }

    public String model() {
        return model;
    }

    public String profile() {
        return profile;
    }

    public Usage usage() {
        return usage;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    public boolean cached() {
        return cached;
    }

    public int attempts() {
        return attempts;
    }

    public String sessionId() {
        return sessionId;
    }

    public String requestId() {
        return requestId;
    }

    public String personaId() {
        return personaId;
    }

    public String personaHash() {
        return personaHash;
    }

    /**
     * 按 §10.6.1 把 errorType 映射到评测侧处置类别。
     *
     * @return 成功结果为 {@code null}
     */
    public Disposition disposition() {
        if (status || errorType == null) {
            return null;
        }
        if (ERROR_DISABLED.equals(errorType)) {
            return Disposition.STOP_RUN;
        }
        if (ERROR_BUSY.equals(errorType) || ERROR_TIMEOUT.equals(errorType)) {
            return Disposition.RETRY_ALLOWED;
        }
        if (ERROR_PROFILE_INVALID.equals(errorType)) {
            return Disposition.CONFIG_HINT;
        }
        if (ERROR_AI_MISSING.equals(errorType)) {
            return Disposition.INSTALL_HINT;
        }
        return Disposition.ERROR_LOG;
    }

}
