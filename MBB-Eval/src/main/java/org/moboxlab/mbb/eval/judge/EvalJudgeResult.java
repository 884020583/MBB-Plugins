package org.moboxlab.mbb.eval.judge;

/**
 * 单维 judge 调用的结果（规格 §6.3.4、§6.9.1、§10.6.1）。
 *
 * <p>三种终态：{@link State#OK} 通过校验拿到 label 与 evidence；{@link State#INVALID}
 * 模型返回了内容但不合约（解析失败 / 截断），样本保留 raw_content 后剔除分母并单独计数；
 * {@link State#ERROR} 调用本身失败（传输、缓存、配置、promptVersion 不匹配），同样剔除分母。</p>
 */
public final class EvalJudgeResult {

    /** 终态；不做 vetoed，硬项否决属于预检切片。 */
    public enum State {
        OK,
        INVALID,
        ERROR
    }

    /** 传输 / 调用失败，errorType 取上游 §10.3 闭集值。 */
    public static final String KIND_TRANSPORT = "transport";
    /** 解析失败：缺字段、label 非闭集、evidence 不合法、非 JSON。 */
    public static final String KIND_PARSE = "parse";
    /** judge 输出被截断（finishReason=length），不重试直接 invalid。 */
    public static final String KIND_TRUNCATED = "truncated";
    /** 上游 rubric 的 promptVersion 与本实现不一致，拒绝调用。 */
    public static final String KIND_PROMPT_VERSION = "prompt-version";
    /** 反复命中 MBB-AI 缓存，换 sessionId 重采后仍 cached。 */
    public static final String KIND_CACHE = "cache";
    /** 判分 profile 解析为空，属配置问题，不重试。 */
    public static final String KIND_PROFILE = "profile";

    private final State state;
    private final String dimensionId;
    private final int label;
    private final String evidence;
    private final int attempts;
    private final String errorKind;
    private final String errorType;
    private final boolean retryable;
    private final String raw;
    private final String message;
    private final String sessionId;
    private final int cacheHits;

    private EvalJudgeResult(State state, String dimensionId, int label, String evidence, int attempts,
            String errorKind, String errorType, boolean retryable, String raw, String message,
            String sessionId, int cacheHits) {
        this.state = state;
        this.dimensionId = dimensionId;
        this.label = label;
        this.evidence = evidence;
        this.attempts = attempts;
        this.errorKind = errorKind;
        this.errorType = errorType;
        this.retryable = retryable;
        this.raw = raw;
        this.message = message;
        this.sessionId = sessionId;
        this.cacheHits = cacheHits;
    }

    public static EvalJudgeResult ok(String dimensionId, int label, String evidence, int attempts,
            String raw, String sessionId, int cacheHits) {
        if (label < 0 || label > 2) {
            throw new IllegalArgumentException("label 越界：" + label);
        }
        return new EvalJudgeResult(State.OK, dimensionId, label, evidence, attempts, null, null, false,
                raw, null, sessionId, cacheHits);
    }

    public static EvalJudgeResult invalid(String dimensionId, String errorKind, int attempts, String raw,
            String message, String sessionId, int cacheHits) {
        return new EvalJudgeResult(State.INVALID, dimensionId, -1, "", attempts, errorKind, null, false,
                raw, message, sessionId, cacheHits);
    }

    public static EvalJudgeResult error(String dimensionId, String errorKind, String errorType,
            boolean retryable, int attempts, String raw, String message, String sessionId, int cacheHits) {
        return new EvalJudgeResult(State.ERROR, dimensionId, -1, "", attempts, errorKind, errorType,
                retryable, raw, message, sessionId, cacheHits);
    }

    public State state() {
        return state;
    }

    public boolean isOk() {
        return state == State.OK;
    }

    public boolean isInvalid() {
        return state == State.INVALID;
    }

    public boolean isError() {
        return state == State.ERROR;
    }

    public String dimensionId() {
        return dimensionId;
    }

    /** 三档 label；非 OK 恒为 -1。 */
    public int label() {
        return label;
    }

    /** 证据原文；非 OK 为空串。 */
    public String evidence() {
        return evidence;
    }

    /** 实际 chat 调用次数，含缓存重采与重试。 */
    public int attempts() {
        return attempts;
    }

    public String errorKind() {
        return errorKind;
    }

    /** §10.3 错误码；只有传输类失败与 profile 失败才有值。 */
    public String errorType() {
        return errorType;
    }

    public boolean retryable() {
        return retryable;
    }

    /** 最后一次收到的原始内容，便于排查与落库。 */
    public String raw() {
        return raw;
    }

    public String message() {
        return message;
    }

    /** 最后一次调用的 sessionId，便于复盘缓存与重采。 */
    public String sessionId() {
        return sessionId;
    }

    /** 累计命中 cached=true 的次数。 */
    public int cacheHits() {
        return cacheHits;
    }

    /** 单题归一化分：median(label)/2，即 label / 2.0；仅 OK 可调用（§6.1）。 */
    public double normalizedScore() {
        if (state != State.OK) {
            throw new IllegalStateException("只有 OK 结果有归一化分，当前 " + state);
        }
        return label / 2.0d;
    }

    /** 打档理由即 evidence，对齐上游 reasons 语义。 */
    public String reason() {
        return evidence;
    }
}
