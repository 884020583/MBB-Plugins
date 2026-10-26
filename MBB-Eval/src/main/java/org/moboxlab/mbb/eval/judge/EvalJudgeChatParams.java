package org.moboxlab.mbb.eval.judge;

/**
 * judge 单次调用的参数快照（规格 §6.3.4）。
 *
 * <p>固定口径：temperature 必须显式传 0（覆盖 profile 默认 0.7）、maxTokens 512、
 * reasoningEffort 留空（不得传 none）、retryCount 0（重试由评测侧计数，避免预算漏算）、
 * timeoutSeconds 取配置 evalJudgeTimeoutSecond。sessionId 必须包含 run_id、case_id、
 * dimension_id 与 sample_index，用于绕开 MBB-AI 本地缓存（§6.3.4、§10.5）。</p>
 */
public final class EvalJudgeChatParams {

    /** §6.3.4：judge 必须显式使用 temperature=0。 */
    public static final double TEMPERATURE = 0.0d;
    /** §6.3.4：judge 输出只含 evidence 与 label。 */
    public static final int MAX_TOKENS = 512;
    /** §6.3.4：MBB-AI 内部不得重试，每次重试由评测侧计数。 */
    public static final int RETRY_COUNT = 0;

    private final String profile;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;
    private final String sessionId;
    private final int retryCount;
    private final String reasoningEffort;

    /** 按规格固定参数构造：temperature=0、maxTokens=512、retryCount=0、reasoningEffort 留空。 */
    public EvalJudgeChatParams(String profile, int timeoutSeconds, String sessionId) {
        this(profile, TEMPERATURE, MAX_TOKENS, timeoutSeconds, sessionId, RETRY_COUNT, null);
    }

    public EvalJudgeChatParams(String profile, double temperature, int maxTokens, int timeoutSeconds,
            String sessionId, int retryCount, String reasoningEffort) {
        this.profile = profile;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
        this.sessionId = sessionId;
        this.retryCount = retryCount;
        this.reasoningEffort = reasoningEffort;
    }

    public String profile() {
        return profile;
    }

    public double temperature() {
        return temperature;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public String sessionId() {
        return sessionId;
    }

    public int retryCount() {
        return retryCount;
    }

    /** 按规格恒为 {@code null}；调用方不得把它替换成 {@code none}。 */
    public String reasoningEffort() {
        return reasoningEffort;
    }

    /** 是否需要向 MBB-AI 传 reasoningEffort；规格要求留空，因此恒为 false。 */
    public boolean hasReasoningEffort() {
        return reasoningEffort != null && !reasoningEffort.isEmpty();
    }
}
