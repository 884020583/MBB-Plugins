package org.moboxlab.mbb.eval.judge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.gateway.EvalGenerateRequest;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;
import org.moboxlab.mbb.eval.rubric.EvalRubric;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * 单维 judge 调用器（规格 §6.3.1、§6.3.4、§6.9.1、§15.4 D8/D10/D21）。
 *
 * <p>每次只判一条 rubric：固定 temperature=0、maxTokens=512、retryCount=0，超时取配置；
 * sessionId 必须包含 run_id / case_id / dimension_id / sample_index 且带独立前缀，避免命中
 * MBB-AI 缓存。传输失败按出参 retryable 重试一次，解析失败按原 prompt 重试一次，截断不重试。
 * 解析只认唯一定义的 JSON 对象，多余字段忽略，缺维 / 越界 / 非数字 / evidence 不合法一律违约。</p>
 */
public final class EvalJudge {

    /** 判分 sessionId 前缀，与生成侧区分（§10.5）。 */
    public static final String SESSION_PREFIX = "eval-judge-";

    public static final int MIN_TIMEOUT_SECONDS = 20;
    public static final int MAX_TIMEOUT_SECONDS = 120;
    public static final int MIN_RETRIES = 0;
    public static final int MAX_RETRIES = 1;
    public static final int MIN_CACHED_RESAMPLES = 1;
    public static final int MAX_CACHED_RESAMPLES = 3;

    /** 证据至少引用的原文长度（码点，§6.9.1）。 */
    public static final int MIN_EVIDENCE_CHARS = 6;
    /** 回复未涉及任何内容的显式写法。 */
    public static final String EVIDENCE_ABSENT = "回复未涉及";

    private static final Pattern QUOTED = Pattern.compile("[「\"“]([^」\"”]+)[」\"”]");

    private final EvalJudgeChatClient client;
    private final String judgeProfile;
    private final String evalProfile;
    private final String aiProfile;
    private final int timeoutSeconds;
    private final int retries;
    private final int cachedResamples;

    public EvalJudge(EvalJudgeChatClient client, String judgeProfile, String evalProfile, String aiProfile,
            int timeoutSeconds, int retries, int cachedResamples) {
        if (client == null) {
            throw new IllegalArgumentException("client 不得为空");
        }
        this.client = client;
        this.judgeProfile = trimToEmpty(judgeProfile);
        this.evalProfile = trimToEmpty(evalProfile);
        this.aiProfile = trimToEmpty(aiProfile);
        this.timeoutSeconds = clamp(timeoutSeconds, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
        this.retries = clamp(retries, MIN_RETRIES, MAX_RETRIES);
        this.cachedResamples = clamp(cachedResamples, MIN_CACHED_RESAMPLES, MAX_CACHED_RESAMPLES);
    }

    /** 从配置接线：profile 回退链、超时、重试与缓存重采上限都取 eval 配置。 */
    public static EvalJudge fromConfig(EvalJudgeChatClient client, EvalConfig config, String aiProfile) {
        if (config == null) {
            throw new IllegalArgumentException("config 不得为空");
        }
        return new EvalJudge(client, config.judgeProfile(), config.profile(), aiProfile,
                config.judgeTimeoutSecond(), config.generateRetries(), config.cachedResamples());
    }

    /** 判分 profile 回退链：evalJudgeProfile → evalProfile → aiProfile，首个非空白生效。 */
    public static String resolveProfile(String judgeProfile, String evalProfile, String aiProfile) {
        String judge = trimToEmpty(judgeProfile);
        if (!judge.isEmpty()) {
            return judge;
        }
        String eval = trimToEmpty(evalProfile);
        if (!eval.isEmpty()) {
            return eval;
        }
        return trimToEmpty(aiProfile);
    }

    /**
     * 执行一次单维判分。请求已通过 {@link EvalJudgeRequest.Builder#build()}，此方法不再抛参数异常。
     */
    public EvalJudgeResult judge(EvalJudgeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不得为空");
        }
        String dimensionId = request.dimensionId();
        if (!EvalJudgePrompt.PROMPT_VERSION.equals(request.promptVersion())) {
            return EvalJudgeResult.error(dimensionId, EvalJudgeResult.KIND_PROMPT_VERSION, null, false, 0,
                    null, "promptVersion 不匹配：期望 " + EvalJudgePrompt.PROMPT_VERSION
                            + "，上游为 " + request.promptVersion(), null, 0);
        }
        String profile = resolveProfile(judgeProfile, evalProfile, aiProfile);
        if (profile.isEmpty()) {
            return EvalJudgeResult.error(dimensionId, EvalJudgeResult.KIND_PROFILE,
                    EvalGenerateResult.ERROR_PROFILE_INVALID, false, 0, null,
                    "没有可用的判分 profile：evalJudgeProfile / evalProfile / aiProfile 均为空", null, 0);
        }
        EvalJudgePrompt prompt = EvalJudgePrompt.build(request);
        int attempts = 0;
        int cacheHits = 0;
        int retryIndex = 0;
        int callIndex = 0;
        String sessionId = null;
        String raw = null;
        while (true) {
            sessionId = buildSessionId(request, callIndex);
            EvalJudgeChatParams params = new EvalJudgeChatParams(profile, EvalJudgeChatParams.TEMPERATURE,
                    EvalJudgeChatParams.MAX_TOKENS, timeoutSeconds, sessionId,
                    EvalJudgeChatParams.RETRY_COUNT, null);
            attempts++;
            callIndex++;
            EvalJudgeChatResponse response;
            try {
                response = client.chat(prompt, params);
            } catch (RuntimeException ex) {
                response = EvalJudgeChatResponse.error(EvalGenerateResult.ERROR_INTERNAL, false,
                        "judge 调用抛出异常：" + ex.getMessage());
            }
            if (response == null) {
                response = EvalJudgeChatResponse.error(EvalGenerateResult.ERROR_INTERNAL, false,
                        "judge 调用返回 null");
            }
            raw = response.content();
            if (!response.ok()) {
                if (response.retryable() && retryIndex < retries) {
                    retryIndex++;
                    continue;
                }
                return EvalJudgeResult.error(dimensionId, EvalJudgeResult.KIND_TRANSPORT,
                        response.errorType(), response.retryable(), attempts, raw, response.message(),
                        sessionId, cacheHits);
            }
            if (response.cached()) {
                cacheHits++;
                if (cacheHits <= cachedResamples) {
                    continue;
                }
                return EvalJudgeResult.error(dimensionId, EvalJudgeResult.KIND_CACHE, null, false, attempts,
                        raw, "judge 反复命中 MBB-AI 缓存，已换 sessionId 重采 " + cachedResamples + " 次仍 cached",
                        sessionId, cacheHits);
            }
            if (response.isTruncated()) {
                return EvalJudgeResult.invalid(dimensionId, EvalJudgeResult.KIND_TRUNCATED, attempts, raw,
                        "judge 回复被截断（finishReason=length），不重试直接作废", sessionId, cacheHits);
            }
            Parsed parsed = parse(response.content(), request.replyContent());
            if (parsed.error != null) {
                if (retryIndex < retries) {
                    retryIndex++;
                    continue;
                }
                return EvalJudgeResult.invalid(dimensionId, EvalJudgeResult.KIND_PARSE, attempts, raw,
                        parsed.error, sessionId, cacheHits);
            }
            return EvalJudgeResult.ok(dimensionId, parsed.label.intValue(), parsed.evidence, attempts, raw,
                    sessionId, cacheHits);
        }
    }

    /**
     * 拼 sessionId：run_id + case_id + dimension_id + sample_index + attempt + 调用序号。
     * 调用序号同时覆盖重试与缓存重采，保证每次请求都不会复用上一个 sessionId。
     */
    public static String buildSessionId(EvalJudgeRequest request, int callIndex) {
        if (request == null) {
            throw new IllegalArgumentException("request 不得为空");
        }
        String sessionId = SESSION_PREFIX + request.runId() + "-" + request.caseId() + "-"
                + request.dimensionId() + "-" + request.sampleIndex() + "-" + request.attempt() + "-"
                + callIndex;
        if (sessionId.length() > EvalGenerateRequest.MAX_SESSION_ID_LENGTH) {
            throw new IllegalArgumentException("sessionId 超长 " + sessionId.length() + "，上限 "
                    + EvalGenerateRequest.MAX_SESSION_ID_LENGTH);
        }
        return sessionId;
    }

    /** 解析结果；error 非空表示违约。 */
    static final class Parsed {
        private final Integer label;
        private final String evidence;
        private final String error;

        private Parsed(Integer label, String evidence, String error) {
            this.label = label;
            this.evidence = evidence;
            this.error = error;
        }
    }

    /**
     * 取首个 {\...} 对象解析：多余字段忽略；label 只认 "0"/"1"/"2" 或整数 0..2；
     * evidence 必须是非空字符串，且引用被测回复原文（≥6 字）或明确写「回复未涉及」。
     */
    static Parsed parse(String content, String replyContent) {
        String json = extractJsonObject(content);
        if (json == null) {
            return new Parsed(null, null, "judge 输出中没有 JSON 对象");
        }
        JSONObject object;
        try {
            object = JSON.parseObject(json);
        } catch (RuntimeException ex) {
            return new Parsed(null, null, "judge 输出 JSON 解析失败：" + ex.getMessage());
        }
        if (object == null) {
            return new Parsed(null, null, "judge 输出 JSON 为空");
        }
        if (!object.containsKey("label")) {
            return new Parsed(null, null, "judge 输出缺少 label");
        }
        Object labelValue = object.get("label");
        Integer label = toLabel(labelValue);
        if (label == null) {
            return new Parsed(null, null, "judge 输出 label 非法：" + labelValue + "，只接受 0 / 1 / 2");
        }
        if (!object.containsKey("evidence")) {
            return new Parsed(null, null, "judge 输出缺少 evidence");
        }
        Object evidenceValue = object.get("evidence");
        if (!(evidenceValue instanceof String)) {
            return new Parsed(null, null, "judge 输出 evidence 不是字符串");
        }
        String evidence = ((String) evidenceValue).trim();
        if (evidence.isEmpty()) {
            return new Parsed(null, null, "judge 输出 evidence 为空");
        }
        if (!isEvidenceValid(evidence, replyContent)) {
            return new Parsed(null, null, "judge 输出 evidence 未引用被测回复原文（至少 "
                    + MIN_EVIDENCE_CHARS + " 字）：" + evidence);
        }
        return new Parsed(label, evidence, null);
    }

    /** label 只认三档闭集；不就近取整，不接受 3 / 小数 / 中文。 */
    static Integer toLabel(Object value) {
        if (value instanceof String) {
            String text = ((String) value).trim();
            if ("0".equals(text)) {
                return Integer.valueOf(0);
            }
            if ("1".equals(text)) {
                return Integer.valueOf(1);
            }
            if ("2".equals(text)) {
                return Integer.valueOf(2);
            }
            return null;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            long label = ((Number) value).longValue();
            return label >= 0 && label <= 2 ? Integer.valueOf((int) label) : null;
        }
        return null;
    }

    /** evidence 校验：显式未涉及，或引号内至少 6 字且真实出现在被测回复中。 */
    static boolean isEvidenceValid(String evidence, String replyContent) {
        if (evidence.contains(EVIDENCE_ABSENT)) {
            return true;
        }
        if (replyContent == null || replyContent.isEmpty()) {
            return false;
        }
        Matcher matcher = QUOTED.matcher(evidence);
        while (matcher.find()) {
            String quote = matcher.group(1).trim();
            if (codePoints(quote) >= MIN_EVIDENCE_CHARS && replyContent.contains(quote)) {
                return true;
            }
        }
        return codePoints(evidence) >= MIN_EVIDENCE_CHARS && replyContent.contains(evidence);
    }

    /** 取首个配对成功的 {...}，跳过字符串内部的大括号与转义。 */
    static String extractJsonObject(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escape = false;
            for (int i = start; i < content.length(); i++) {
                char c = content.charAt(i);
                if (inString) {
                    if (escape) {
                        escape = false;
                    } else if (c == '\\') {
                        escape = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return content.substring(start, i + 1);
                    }
                }
            }
            start = content.indexOf('{', start + 1);
        }
        return null;
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
