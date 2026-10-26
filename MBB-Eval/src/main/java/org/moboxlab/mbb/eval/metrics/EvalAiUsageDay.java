package org.moboxlab.mbb.eval.metrics;

/**
 * 一天一个 profile 的线上用量快照（E-4：规格 §7.5 的 L3 层）。
 *
 * <p>只承载 MBB-AI 统计表 {@code plugin_mbb_ai_usage} 的只读聚合值，字段与表列同名：
 * {@code requests} / {@code successes} / {@code failures} / 三类 token / 累计耗时 /
 * 缓存命中。本类不含任何评测分数或指标字段，也不提供换算方法（§7.5「不同口径，不可换算」）。</p>
 *
 * <p>计数为负时按 0 归并，避免脏数据把汇总拉负；平均耗时在 requests 为 0 时返回
 * {@code null}，不补 0。</p>
 */
public final class EvalAiUsageDay {

    private final String statDate;
    private final String profile;
    private final long requests;
    private final long successes;
    private final long failures;
    private final long promptTokens;
    private final long completionTokens;
    private final long totalTokens;
    private final long totalLatencyMs;
    private final long cachedHits;

    public EvalAiUsageDay(String statDate, String profile, long requests, long successes,
            long failures, long promptTokens, long completionTokens, long totalTokens,
            long totalLatencyMs, long cachedHits) {
        this.statDate = trim(statDate);
        this.profile = trim(profile);
        this.requests = nonNegative(requests);
        this.successes = nonNegative(successes);
        this.failures = nonNegative(failures);
        this.promptTokens = nonNegative(promptTokens);
        this.completionTokens = nonNegative(completionTokens);
        this.totalTokens = nonNegative(totalTokens);
        this.totalLatencyMs = nonNegative(totalLatencyMs);
        this.cachedHits = nonNegative(cachedHits);
    }

    /** 统计日期，格式 yyyy-MM-dd。 */
    public String statDate() {
        return statDate;
    }

    /** 该行对应的 profile 名，例如 eval / judge。 */
    public String profile() {
        return profile;
    }

    public long requests() {
        return requests;
    }

    public long successes() {
        return successes;
    }

    public long failures() {
        return failures;
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

    public long totalLatencyMs() {
        return totalLatencyMs;
    }

    public long cachedHits() {
        return cachedHits;
    }

    /** 线上口径平均耗时；requests 为 0 时不可得（null），不补 0。 */
    public Double averageLatencyMs() {
        if (requests <= 0L) {
            return null;
        }
        return Double.valueOf(totalLatencyMs / (double) requests);
    }

    private static long nonNegative(long value) {
        return value < 0L ? 0L : value;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
