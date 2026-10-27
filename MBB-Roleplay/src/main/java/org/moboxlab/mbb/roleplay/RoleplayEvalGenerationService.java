package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 评测生成分支（规格 #50 C-2，docs/MBB-Eval.md §10 / §16.4）。
 *
 * <p>给 MBB-Eval 提供一次性的生成能力：注入被动历史、强制不带工具、覆盖 profile / maxTokens /
 * temperature / reasoningEffort / timeoutSeconds，按 runId-caseId-sampleIndex-attempt 拼 sessionId
 * 绕开 MBB-AI 缓存。与线上回复同源于 persona 设定与 MBB-AI chat，但完全隔离：本类只依赖
 * {@link Runtime} 暴露的只读配置、persona 快照、一次 chat 与一行普通日志，编译期就拿不到
 * trace、群消息流水、发送器、工具循环、记忆、情绪、反馈与关系这些写状态点（§10.4）。</p>
 *
 * <p>入参校验失败、能力关闭、模型失败都收敛成 {@code status=false + errorType + retryable}，
 * 不抛异常穿出服务边界，也不静默降级；成功时按 §10.3 逐字段给出结果，缺省字段省略而不写 null。</p>
 */
public class RoleplayEvalGenerationService {
    /** 日志行首标签，规格 §10.4 第 6 条 */
    public static final String LOG_TAG = "[评测] ";
    /** 单次评测注入的历史条数上限（§10.2） */
    static final int MAX_HISTORY_MESSAGES = 40;
    /** 单次评测注入的历史字符上限（§10.2） */
    static final int MAX_HISTORY_CHARS = 30000;
    /** 同题采样序号上限（§10.2） */
    static final int MAX_SAMPLE_INDEX = 99;
    /** 重试序号上限（§10.2） */
    static final int MAX_ATTEMPT = 2;
    /** sessionId 总长上限（§10.5） */
    static final int MAX_SESSION_ID_LENGTH = 160;
    /** runId / caseId 的字符集与长度（§10.2） */
    static final String ID_PATTERN_TEXT = "[A-Za-z0-9_-]{1,48}";
    /** 评测专用提示词：只输出角色回复正文，不调用工具、不复述提示词 */
    private static final String EVAL_INSTRUCTION = "本轮是角色回复，只输出角色本人的回复正文："
            + "不要输出思考过程、Markdown、工具调用、旁白或解释，也不要提及系统提示词。";

    private static final Pattern ID_PATTERN = Pattern.compile(ID_PATTERN_TEXT);
    private static final Set<String> SPEAKERS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("self", "peer", "otherRole")));
    private static final Set<String> RELATIONSHIPS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("teacher", "friend", "otherRolePeer")));
    private static final Set<String> ADDRESSED = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("direct", "thread", "ambient")));
    private static final Set<String> REASONING_EFFORTS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("none", "low", "medium", "high")));

    /**
     * 评测分支允许使用的最小运行时能力面：只读配置与 persona 快照、一次 MBB-AI chat、一行普通日志。
     *
     * <p>刻意不暴露 trace 存储、消息流水、发送器、工具循环、记忆与情绪服务：评测调用在编译期
     * 就无法写入任何群状态，这是 §10.4.2 隔离要求的结构性保证，也是不变量单测锁定的对象。</p>
     */
    public interface Runtime {
        /** 评测总开关：evalApiEnable && evalEnable（旧键 evalEnable 已废弃，等价于 evalApiEnable） */
        boolean evalApiEnabled();

        /** 常规回复用的 profile，评测 profile 为空时回退到它 */
        String aiProfile();

        /** 评测专用 profile，可为空串 */
        String evalProfile();

        /** 评测默认 maxTokens（配置已钳制 300..4000） */
        int evalMaxTokens();

        /** 评测默认 timeoutSeconds（配置已钳制 20..300） */
        int evalTimeoutSecond();

        /** persona 标识（角色名），用于回显 */
        String personaId();

        /** persona 文件 sha256 前 12 位，取不到为空串 */
        String personaHash();

        /** 角色设定文本，与线上 prompt 同源 */
        String systemPrompt();

        /** 调 MBB-AI 的 chat；取不到服务或没有结果时返回 null */
        JSONObject chat(JSONObject params);

        /** 写一行普通运行日志（行首已带 [评测]） */
        void log(String line);
    }

    private final Runtime runtime;

    public RoleplayEvalGenerationService(Runtime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("评测运行时不能为空");
        }
        this.runtime = runtime;
    }

    /**
     * 处理一次 eval-generate 请求。
     *
     * <p>无论成功还是失败都会写且只写一行普通日志（§10.4 第 6 条）。</p>
     */
    public JSONObject generate(JSONObject params) {
        JSONObject safeParams = params == null ? new JSONObject(true) : params;
        JSONObject result = dispatch(safeParams);
        try {
            runtime.log(logLine(safeParams, result));
        } catch (RuntimeException ignored) {
            // 普通日志写失败不得影响已算好的契约出参，插件 logger 自身会兜底
        }
        return result;
    }

    private JSONObject dispatch(JSONObject params) {
        if (!runtime.evalApiEnabled()) {
            return failure("disabled", false,
                    "评测接口未开启：evalApiEnable 与 evalEnable 需同时为 true（旧键 evalEnable 已废弃，等价于 evalApiEnable）");
        }
        Spec spec = new Spec();
        Problem problem = fill(params, spec);
        if (problem != null) {
            return failure(problem.errorType, false, problem.message);
        }

        JSONArray messages = new JSONArray();
        messages.add(message("system", promptText()));
        for (int i = 0; i < spec.history.size(); i++) {
            JSONObject row = spec.history.getJSONObject(i);
            String speaker = row.getString("speaker");
            String text = textOrEmpty(row.getString("text"));
            if ("self".equals(speaker)) {
                messages.add(message("assistant", text));
            } else {
                String userName = textOrEmpty(row.getString("userName"));
                messages.add(message("user", userName.isEmpty() ? text : userName + "：" + text));
            }
        }
        messages.add(message("user", userMessage(spec)));

        JSONObject chatParams = new JSONObject(true);
        chatParams.put("messages", messages);
        chatParams.put("profile", spec.profile);
        chatParams.put("maxTokens", Integer.valueOf(spec.maxTokens));
        if (spec.temperature != null) {
            chatParams.put("temperature", spec.temperature);
        }
        chatParams.put("reasoningEffort", spec.reasoningEffort);
        chatParams.put("sessionId", spec.sessionId());
        chatParams.put("timeoutSeconds", Integer.valueOf(spec.timeoutSeconds));
        // 接口内不重试（§10.3 规则 4），重试只在 runner 用新的 attempt 重新编码 sessionId
        chatParams.put("retryCount", Integer.valueOf(0));

        JSONObject response;
        long startedAt = System.nanoTime();
        try {
            response = runtime.chat(chatParams);
        } catch (RuntimeException e) {
            JSONObject failed = failure("internal", false, "评测生成内部异常：" + describe(e));
            echoSession(failed, spec);
            return failed;
        }
        long elapsedMs = Math.max(0L, (System.nanoTime() - startedAt) / 1000000L);

        if (response == null) {
            JSONObject failed = failure("ai-missing", false, "取不到 MBB-AI 服务或 chat 没有返回结果");
            echoSession(failed, spec);
            return failed;
        }
        if (!response.getBooleanValue("status")) {
            return modelFailure(response, spec);
        }
        return success(response, spec, elapsedMs);
    }

    // ===== 出参 =====

    private JSONObject success(JSONObject response, Spec spec, long elapsedMs) {
        String sessionId = spec.sessionId();
        String content = textOrEmpty(response.getString("content"));
        String trimmed = content.trim();

        JSONObject result = new JSONObject(true);
        result.put("status", true);
        result.put("action", RoleplayEvalGateway.ACTION_EVAL_GENERATE);
        result.put("sessionId", sessionId);
        result.put("requestId", sessionId);
        result.put("content", content);
        result.put("skip", trimmed.isEmpty() || "<SKIP>".equals(trimmed));
        result.put("finishReason", textOrEmpty(response.getString("finishReason")));
        String model = response.getString("model");
        if (model != null && !model.isEmpty()) {
            result.put("model", model);
        }
        result.put("profile", spec.profile);
        result.put("usage", usageOf(response.getJSONObject("usage")));
        result.put("elapsedMs", Long.valueOf(elapsedMs));
        result.put("cached", Boolean.valueOf(response.getBooleanValue("cached")));
        result.put("attempts", Integer.valueOf(1));
        result.put("personaId", textOrEmpty(runtime.personaId()));
        result.put("personaHash", textOrEmpty(runtime.personaHash()));
        if (!spec.clamped.isEmpty()) {
            result.put("message", "已按边界钳制：" + join(spec.clamped));
        }
        return result;
    }

    private JSONObject modelFailure(JSONObject response, Spec spec) {
        String errorType = textOrEmpty(response.getString("errorType"));
        String detail = textOrEmpty(response.getString("message"));
        String probe = (errorType + " " + detail).toLowerCase(Locale.ROOT);

        String mapped;
        boolean retryable;
        if (probe.contains("profile") && containsAny(detail, "不存在", "未找到", "不可用", "非法", "invalid")
                || "profile_invalid".equalsIgnoreCase(errorType) || "profile-invalid".equalsIgnoreCase(errorType)) {
            mapped = "profile-invalid";
            retryable = false;
        } else if ("busy".equalsIgnoreCase(errorType) || containsAny(detail, "繁忙", "并发", "队列已满")) {
            mapped = "busy";
            retryable = true;
        } else if ("timeout".equalsIgnoreCase(errorType) || probe.contains("timeout")
                || containsAny(detail, "超时", "超过时限")) {
            mapped = "timeout";
            retryable = true;
        } else {
            mapped = "internal";
            retryable = false;
        }

        JSONObject failed = failure(mapped, retryable, "模型调用失败：" + (detail.isEmpty() ? "未返回原因" : detail));
        echoSession(failed, spec);
        return failed;
    }

    /** 失败出参：不带 content，只带 errorType / retryable / message（§10.3 出参规则 2、3） */
    static JSONObject failure(String errorType, boolean retryable, String message) {
        JSONObject result = new JSONObject(true);
        result.put("status", false);
        result.put("action", RoleplayEvalGateway.ACTION_EVAL_GENERATE);
        result.put("errorType", errorType);
        result.put("retryable", Boolean.valueOf(retryable));
        result.put("message", message);
        return result;
    }

    /** 失败时若能按四要素拼出 sessionId 就回显，拼不出（runId/caseId 非法）则省略 */
    private static void echoSession(JSONObject result, Spec spec) {
        if (spec.runId == null || spec.caseId == null) {
            return;
        }
        String sessionId = spec.sessionId();
        result.put("sessionId", sessionId);
        result.put("requestId", sessionId);
    }

    private static JSONObject usageOf(JSONObject usage) {
        JSONObject result = new JSONObject(true);
        result.put("promptTokens", Integer.valueOf(intOf(usage, "promptTokens")));
        result.put("completionTokens", Integer.valueOf(intOf(usage, "completionTokens")));
        result.put("totalTokens", Integer.valueOf(intOf(usage, "totalTokens")));
        // MBB-AI 未返回缓存命中数时按 0 计并保留字段（§10.3 出参规则 7）
        result.put("cachedPromptTokens", Integer.valueOf(intOf(usage, "cachedPromptTokens")));
        return result;
    }

    private static int intOf(JSONObject source, String key) {
        if (source == null) {
            return 0;
        }
        Object value = source.get(key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    // ===== 入参校验与钳制 =====

    private Problem fill(JSONObject params, Spec spec) {
        Object rawRunId = params.get("runId");
        Problem problem = requireId("runId", rawRunId);
        if (problem != null) {
            return problem;
        }
        spec.runId = (String) rawRunId;

        Object rawCaseId = params.get("caseId");
        problem = requireId("caseId", rawCaseId);
        if (problem != null) {
            return problem;
        }
        spec.caseId = (String) rawCaseId;

        Object rawSampleIndex = params.get("sampleIndex");
        if (rawSampleIndex != null) {
            problem = requireInteger("sampleIndex", rawSampleIndex);
            if (problem != null) {
                return problem;
            }
            long value = ((Number) rawSampleIndex).longValue();
            if (value < 0L || value > MAX_SAMPLE_INDEX) {
                return invalidParam("sampleIndex 越界：" + value + "，取值 0.." + MAX_SAMPLE_INDEX);
            }
            spec.sampleIndex = (int) value;
        }

        Object rawAttempt = params.get("attempt");
        if (rawAttempt != null) {
            problem = requireInteger("attempt", rawAttempt);
            if (problem != null) {
                return problem;
            }
            long value = ((Number) rawAttempt).longValue();
            if (value < 0L || value > MAX_ATTEMPT) {
                return invalidParam("attempt 越界：" + value + "，取值 0.." + MAX_ATTEMPT);
            }
            spec.attempt = (int) value;
        }

        if (spec.sessionId().length() > MAX_SESSION_ID_LENGTH) {
            return invalidParam("sessionId 长度 " + spec.sessionId().length() + " 超过上限 "
                    + MAX_SESSION_ID_LENGTH + "（runId / caseId 各不超过 48）");
        }

        Object rawMessage = params.get("message");
        if (!(rawMessage instanceof JSONObject)) {
            return invalidParam("缺少必填字段 message");
        }
        JSONObject message = (JSONObject) rawMessage;
        Object text = message.get("text");
        if (!(text instanceof String) || ((String) text).isEmpty()) {
            return invalidParam("message.text 不得为空字符串");
        }
        problem = requireInteger("message.userID", message.get("userID"));
        if (problem != null) {
            return problem;
        }
        if (!(message.get("userName") instanceof String)) {
            return invalidParam("message.userName 必须是字符串");
        }
        spec.message = message;

        Object rawHistory = params.get("history");
        if (rawHistory != null) {
            if (!(rawHistory instanceof JSONArray)) {
                return invalidParam("history 必须是数组");
            }
            JSONArray history = (JSONArray) rawHistory;
            int chars = 0;
            for (int i = 0; i < history.size(); i++) {
                Object value = history.get(i);
                if (!(value instanceof JSONObject)) {
                    return invalidParam("history[" + i + "] 必须是对象");
                }
                JSONObject row = (JSONObject) value;
                Object speaker = row.get("speaker");
                if (!(speaker instanceof String) || !SPEAKERS.contains(speaker)) {
                    return invalidParam("history[" + i + "].speaker 非法：" + speaker
                            + "，只接受 self / peer / otherRole");
                }
                Object rowText = row.get("text");
                if (!(rowText instanceof String)) {
                    return invalidParam("history[" + i + "].text 必须是字符串");
                }
                chars += ((String) rowText).length();
            }
            if (history.size() > MAX_HISTORY_MESSAGES) {
                return historyTooLong("history 超过 " + MAX_HISTORY_MESSAGES + " 条（实际 " + history.size()
                        + " 条），按 §10.2 不得静默截断");
            }
            if (chars > MAX_HISTORY_CHARS) {
                return historyTooLong("history 超过 " + MAX_HISTORY_CHARS + " 字符（实际 " + chars
                        + " 字符），按 §10.2 不得静默截断");
            }
            spec.history = history;
        }

        Object rawRelationship = params.get("relationship");
        if (rawRelationship != null) {
            if (!(rawRelationship instanceof String) || !RELATIONSHIPS.contains(rawRelationship)) {
                return invalidParam("relationship 非法：" + rawRelationship
                        + "，只接受 teacher / friend / otherRolePeer");
            }
            spec.relationship = (String) rawRelationship;
        }

        Object rawAddressed = params.get("addressed");
        if (rawAddressed != null) {
            if (!(rawAddressed instanceof String) || !ADDRESSED.contains(rawAddressed)) {
                return invalidParam("addressed 非法：" + rawAddressed + "，只接受 direct / thread / ambient");
            }
            spec.addressed = (String) rawAddressed;
        }

        Object rawProfile = params.get("profile");
        if (rawProfile != null && !(rawProfile instanceof String)) {
            return invalidParam("profile 必须是字符串");
        }
        String profile = rawProfile == null ? "" : ((String) rawProfile).trim();
        if (profile.isEmpty()) {
            profile = textOrEmpty(runtime.evalProfile()).trim();
        }
        if (profile.isEmpty()) {
            profile = textOrEmpty(runtime.aiProfile()).trim();
        }
        spec.profile = profile.isEmpty() ? "default" : profile;

        Object rawTemperature = params.get("temperature");
        if (rawTemperature != null) {
            if (!(rawTemperature instanceof Number)) {
                return invalidParam("temperature 必须是数字");
            }
            double value = ((Number) rawTemperature).doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return invalidParam("temperature 必须是有限数字");
            }
            double clamped = Math.max(0.0d, Math.min(2.0d, value));
            spec.temperature = Double.valueOf(clamped);
            if (clamped != value) {
                spec.clamped.add("temperature=" + numberText(value) + "→" + numberText(clamped));
            }
        }

        Object rawMaxTokens = params.get("maxTokens");
        int maxTokens = runtime.evalMaxTokens();
        if (rawMaxTokens != null) {
            problem = requireInteger("maxTokens", rawMaxTokens);
            if (problem != null) {
                return problem;
            }
            maxTokens = ((Number) rawMaxTokens).intValue();
        }
        int clampedMaxTokens = Math.max(300, Math.min(4000, maxTokens));
        if (clampedMaxTokens != maxTokens) {
            spec.clamped.add("maxTokens=" + maxTokens + "→" + clampedMaxTokens);
        }
        spec.maxTokens = clampedMaxTokens;

        Object rawEffort = params.get("reasoningEffort");
        if (rawEffort != null) {
            if (!(rawEffort instanceof String) || !REASONING_EFFORTS.contains(rawEffort)) {
                return invalidParam("reasoningEffort 非法：" + rawEffort
                        + "，只接受 none / low / medium / high");
            }
            spec.reasoningEffort = (String) rawEffort;
        }

        Object rawTimeout = params.get("timeoutSeconds");
        int timeoutSeconds = runtime.evalTimeoutSecond();
        if (rawTimeout != null) {
            problem = requireInteger("timeoutSeconds", rawTimeout);
            if (problem != null) {
                return problem;
            }
            timeoutSeconds = ((Number) rawTimeout).intValue();
        }
        int clampedTimeout = Math.max(20, Math.min(300, timeoutSeconds));
        if (clampedTimeout != timeoutSeconds) {
            spec.clamped.add("timeoutSeconds=" + timeoutSeconds + "→" + clampedTimeout);
        }
        spec.timeoutSeconds = clampedTimeout;
        return null;
    }

    private static Problem requireId(String field, Object raw) {
        if (raw == null) {
            return invalidParam("缺少必填字段 " + field);
        }
        if (!(raw instanceof String)) {
            return invalidParam(field + " 必须是字符串");
        }
        String value = (String) raw;
        if (value.isEmpty()) {
            return invalidParam("缺少必填字段 " + field);
        }
        if (!ID_PATTERN.matcher(value).matches()) {
            return invalidParam(field + " 格式不合法：" + value + "，必须匹配 " + ID_PATTERN_TEXT);
        }
        return null;
    }

    private static Problem requireInteger(String field, Object raw) {
        if (raw == null) {
            return invalidParam("缺少必填字段 " + field);
        }
        if (!(raw instanceof Number)) {
            return invalidParam(field + " 必须是整数");
        }
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value)) {
            return invalidParam(field + " 必须是整数");
        }
        return null;
    }

    private static Problem invalidParam(String message) {
        return new Problem("invalid-param", message);
    }

    private static Problem historyTooLong(String message) {
        return new Problem("history-too-long", message);
    }

    // ===== 提示词与日志 =====

    private String promptText() {
        String persona = textOrEmpty(runtime.systemPrompt());
        return persona + (persona.endsWith("\n") || persona.isEmpty() ? "" : "\n") + EVAL_INSTRUCTION;
    }

    private static String userMessage(Spec spec) {
        JSONObject message = spec.message;
        return "发言者：" + textOrEmpty(message.getString("userName"))
                + "\n关系：" + relationshipLabel(spec.relationship)
                + "\n当前消息指向：" + addressingHint(spec.addressed)
                + "\n当前内容：" + textOrEmpty(message.getString("text"));
    }

    /** 评测入参的关系口径映射到插件内关系标签，与线上 prompt 一致 */
    private static String relationshipLabel(String relationship) {
        if ("teacher".equals(relationship)) {
            return "老师";
        }
        if ("otherRolePeer".equals(relationship)) {
            return "其他角色机器人";
        }
        return "朋友";
    }

    private static String addressingHint(String addressed) {
        if ("thread".equals(addressed)) {
            return "接续当前话题";
        }
        if ("ambient".equals(addressed)) {
            return "群里随口说，未明确指向角色";
        }
        return "明确对角色说";
    }

    private static JSONObject message(String role, String content) {
        JSONObject item = new JSONObject(true);
        item.put("role", role);
        item.put("content", content);
        return item;
    }

    private String logLine(JSONObject params, JSONObject result) {
        StringBuilder line = new StringBuilder(LOG_TAG);
        line.append("runId=").append(rawText(params, "runId"));
        line.append(" caseId=").append(rawText(params, "caseId"));
        line.append(" sampleIndex=").append(rawInteger(params, "sampleIndex"));
        line.append(" status=").append(result.getBooleanValue("status"));
        if (!result.getBooleanValue("status")) {
            line.append(" errorType=").append(textOrEmpty(result.getString("errorType")));
        }
        line.append(" elapsedMs=").append(result.getLongValue("elapsedMs"));
        return line.toString();
    }

    private static String rawText(JSONObject params, String key) {
        Object value = params.get(key);
        return value == null ? "-" : String.valueOf(value);
    }

    private static String rawInteger(JSONObject params, String key) {
        Object value = params.get(key);
        return value instanceof Number ? String.valueOf(((Number) value).longValue()) : "-";
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append("；");
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private static String numberText(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static boolean containsAny(String text, String... keywords) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }

    // ===== 内部数据结构 =====

    /** 校验失败原因：错误类型与可读说明 */
    private static final class Problem {
        private final String errorType;
        private final String message;

        private Problem(String errorType, String message) {
            this.errorType = errorType;
            this.message = message;
        }
    }

    /** 已校验、已回读配置、已钳制的一次评测请求 */
    private static final class Spec {
        private String runId;
        private String caseId;
        private int sampleIndex;
        private int attempt;
        private JSONArray history = new JSONArray();
        private JSONObject message;
        private String relationship = "friend";
        private String addressed = "direct";
        private String profile = "default";
        private Double temperature;
        private int maxTokens = 1200;
        private String reasoningEffort = "none";
        private int timeoutSeconds = 60;
        private final List<String> clamped = new ArrayList<String>();

        private String sessionId() {
            return "roleplay-eval-" + runId + "-" + caseId + "-" + sampleIndex + "-" + attempt;
        }
    }
}
