package org.moboxlab.mbb.eval.judge;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;

/**
 * MBB-AI {@code chat} 的出参模型（规格 §6.3.4、§10.3 错误面）。
 *
 * <p>{@code errorType} 复用 {@link EvalGenerateResult} 的封闭集合，与 §10.6.1 的处置口径
 * 保持一致；是否重试只由 {@code retryable} 决定，本类不按 errorType 猜测。
 * {@code finishReason=length} 表示 judge 输出被截断，按 §6.9.1 不重试直接 invalid。</p>
 */
public final class EvalJudgeChatResponse {

    /** judge 输出完整结束。 */
    public static final String FINISH_STOP = "stop";
    /** judge 输出被截断。 */
    public static final String FINISH_LENGTH = "length";

    private static final Set<String> KNOWN_ERROR_TYPES = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList(EvalGenerateResult.ERROR_DISABLED, EvalGenerateResult.ERROR_INVALID_PARAM,
                    EvalGenerateResult.ERROR_HISTORY_TOO_LONG, EvalGenerateResult.ERROR_PROFILE_INVALID,
                    EvalGenerateResult.ERROR_AI_MISSING, EvalGenerateResult.ERROR_BUSY,
                    EvalGenerateResult.ERROR_TIMEOUT, EvalGenerateResult.ERROR_INTERNAL)));

    private final boolean ok;
    private final String content;
    private final String finishReason;
    private final boolean cached;
    private final String errorType;
    private final boolean retryable;
    private final String message;

    private EvalJudgeChatResponse(boolean ok, String content, String finishReason, boolean cached,
            String errorType, boolean retryable, String message) {
        this.ok = ok;
        this.content = content;
        this.finishReason = finishReason;
        this.cached = cached;
        this.errorType = errorType;
        this.retryable = retryable;
        this.message = message;
    }

    /** 成功响应；{@code cached=true} 表示命中 MBB-AI 本地缓存，样本必须作废重采。 */
    public static EvalJudgeChatResponse ok(String content, String finishReason, boolean cached) {
        return new EvalJudgeChatResponse(true, content, finishReason, cached, null, false, null);
    }

    /** 成功响应，默认未命中缓存。 */
    public static EvalJudgeChatResponse ok(String content, String finishReason) {
        return ok(content, finishReason, false);
    }

    /** 失败响应；未知 errorType 收敛为 internal，不新增闭集值。 */
    public static EvalJudgeChatResponse error(String errorType, boolean retryable, String message) {
        String code = errorType == null || !KNOWN_ERROR_TYPES.contains(errorType)
                ? EvalGenerateResult.ERROR_INTERNAL : errorType;
        return new EvalJudgeChatResponse(false, null, null, false, code, retryable, message);
    }

    public boolean ok() {
        return ok;
    }

    /** judge 原始返回文本；失败时可能为 null。 */
    public String content() {
        return content;
    }

    public String finishReason() {
        return finishReason;
    }

    public boolean cached() {
        return cached;
    }

    /** §10.3 封闭集合中的错误类型；成功时为 null。 */
    public String errorType() {
        return errorType;
    }

    /** 是否值得重试：只读上游 retryable（§13.1.2）。 */
    public boolean retryable() {
        return retryable;
    }

    public String message() {
        return message;
    }

    /** judge 输出是否因长度上限被截断（§6.9.1）。 */
    public boolean isTruncated() {
        return FINISH_LENGTH.equals(finishReason);
    }
}
