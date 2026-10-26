package org.moboxlab.mbb.eval.report;

import com.alibaba.fastjson.JSONObject;
import org.moboxlab.mbb.eval.metrics.EvalAiUsageEvidence;
import org.moboxlab.mbb.eval.metrics.EvalMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 报表第 7 节「性能」与第 8 节性能相关告警的渲染器（E-3：规格 §7、§8.3、§8.5）。
 *
 * <p>纯渲染：只读 {@link EvalReportData} 与其指标快照，不读库、不记日志；同一输入重复
 * 渲染得到同一文本。所有数值统一保留一位小数：延迟与耗时为「秒」，输出吞吐为
 * 「token/s」，比例与成功率/重试率为百分数（一位小数）。不可得值一律写固定文案，
 * 不补 0、不用猜测值（§7.5、§7.6）。</p>
 *
 * <p>输出吞吐与评测自身开销需要逐调用耗时，指标快照（E-1）未冻结这两个口径，因此本类
 * 直接消费报表数据中的只读 call 行，按 §7.2 的同一过滤条件重新计算；不改变指标快照
 * 的任何字段。</p>
 *
 * <p>隐私：只输出聚合数值与 stage/error_type 名称，不输出 prompt、回复原文、昵称或
 * userID（§15.9 I2）。</p>
 */
public final class EvalReportPerformanceRenderer {

    /** 第 7 节标题，与 {@link EvalReportWriter} 的九节顺序一致（§8.3）。 */
    static final String HEADING = "## 7. 性能";

    /** 延迟分位最小样本数：n &lt; 20 只出 mean（§7.2）。 */
    private static final int LATENCY_SAMPLE_FLOOR = 20;

    /** p95 最小样本数：n ≥ 60 才出（§7.2）。 */
    private static final int P95_SAMPLE_FLOOR = 60;

    /** error_type 的固定展示顺序（与指标快照的封闭八值一致）。 */
    private static final String[] ERROR_TYPE_ORDER = {
        "disabled", "invalid-param", "history-too-long", "profile-invalid",
        "ai-missing", "busy", "timeout", "internal"
    };

    private EvalReportPerformanceRenderer() {
    }

    /**
     * 渲染完整的第 7 节（含标题与结尾空行），供 {@link EvalReportWriter} 直接拼接。
     *
     * @param data 报表数据；null 按「指标未装配」处理，不抛异常
     * @return 以换行结尾的 Markdown 片段
     */
    public static String render(EvalReportData data) {
        EvalMetrics metrics = data == null ? null : data.metrics();
        List<JSONObject> callRows = data == null
                ? Collections.<JSONObject>emptyList() : data.callRows();

        StringBuilder sb = new StringBuilder(2048);
        sb.append(HEADING).append('\n');

        if (metrics == null) {
            appendMissingMetrics(sb);
        } else {
            appendLatency(sb, metrics.latency());
            appendThroughput(sb, throughput(callRows));
            appendRetrySubset(sb, retryLatency(callRows), metrics.calls().retryCount());
            appendCalls(sb, metrics);
            appendStage(sb, metrics.calls());
            appendSamples(sb, metrics.samples());
            appendTokens(sb, metrics);
            appendErrorTypes(sb, metrics.calls());
            appendCaseStats(sb, metrics.cases());
        }

        appendRunDuration(sb, data);
        appendOverhead(sb, data, callRows);

        sb.append("- 延迟可信度：中（非首 token，含排队）").append('\n');
        sb.append("- 吞吐可信度：低（非 ITL/TPOT）").append('\n');
        sb.append("- TTFT/ITL/TPS/RPS：不可得或不适用").append('\n');
        appendFixedLimits(sb);
        sb.append("- L3 旁证：仅比数量级；不同口径，不可换算").append('\n');
        appendAiUsage(sb, data);
        sb.append("- 口径隔离：性能与成本不进总分、不设门禁（E14）").append('\n');
        sb.append('\n');
        return sb.toString();
    }

    /**
     * L3 用量旁证（E-4：规格 §7.5）：有采集结果时原样缩进挂出。
     *
     * <p>数据源未接入时不追加任何行（保留上方固定口径行）；数据源读表失败或无行时
     * 由 {@link EvalAiUsageEvidence#text()} 自己写「不可得」与原因，不补数、不换算。</p>
     */
    private static void appendAiUsage(StringBuilder sb, EvalReportData data) {
        EvalAiUsageEvidence evidence = data == null ? null : data.aiUsage();
        if (evidence == null) {
            return;
        }
        String[] lines = evidence.text().split("\n", -1);
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            sb.append("  ").append(line).append('\n');
        }
    }

    /**
     * §8.5 中与性能直接相关的告警子集：缓存命中率非 0、调用预算耗尽。
     *
     * <p>不设 p95 延迟阈值：规格未给出阈值，本类不自造门禁。有效题不足、非 ok 比例、
     * rubric 变化等告警仍由 {@link EvalReportWriter} 统一判定。</p>
     *
     * @param data 报表数据；null 或指标未装配时返回空列表
     * @return 可直接逐条写入第 8 节的告警文案；顺序固定
     */
    public static List<String> performanceWarnings(EvalReportData data) {
        List<String> warnings = new ArrayList<String>();
        if (data == null || data.metrics() == null) {
            return warnings;
        }
        EvalMetrics.Calls calls = data.metrics().calls();
        Double cacheHitRate = calls.cacheHitRate();
        if (calls.cached() > 0L && cacheHitRate != null && cacheHitRate.doubleValue() > 0D) {
            warnings.add(String.format(Locale.ROOT,
                    "缓存命中率 %s 不为 0，缓存键疑似配置错误，性能与 token 不可用（§8.5）",
                    percent(cacheHitRate)));
        }
        if (data.callsBudget() > 0 && data.callsUsed() >= data.callsBudget()) {
            warnings.add(String.format(Locale.ROOT,
                    "预算耗尽：calls_used=%d / calls_budget=%d，样本不完整（§8.5）",
                    Integer.valueOf(data.callsUsed()), Integer.valueOf(data.callsBudget())));
        }
        return warnings;
    }

    // ===== 第 7 节各条目 =====

    private static void appendMissingMetrics(StringBuilder sb) {
        sb.append("- 生成延迟：数据缺失（指标未装配）").append('\n');
        sb.append("- p95：数据缺失").append('\n');
        sb.append("- 延迟排除：数据缺失").append('\n');
        sb.append("- 输出吞吐：数据缺失").append('\n');
        sb.append("- 重试子集：数据缺失").append('\n');
        sb.append("- 调用：数据缺失").append('\n');
        sb.append("- stage 分布：数据缺失").append('\n');
        sb.append("- 样本：数据缺失").append('\n');
        sb.append("- 缓存命中率：数据缺失").append('\n');
        sb.append("- 总 token：数据缺失").append('\n');
        sb.append("- error_type 分布：数据缺失").append('\n');
        sb.append("- 题级：数据缺失").append('\n');
    }

    private static void appendLatency(StringBuilder sb, EvalMetrics.Latency latency) {
        long n = latency.n();
        if (n <= 0L) {
            sb.append("- 生成延迟：数据缺失（无样本）").append('\n');
        } else if (n < LATENCY_SAMPLE_FLOOR) {
            sb.append("- 生成延迟：mean ").append(seconds(latency.mean()))
                    .append("；p50/p90/max：").append(sampleShort(n, LATENCY_SAMPLE_FLOOR))
                    .append('\n');
        } else {
            sb.append("- 生成延迟：n=").append(n)
                    .append("；mean ").append(seconds(latency.mean()))
                    .append("；p50 ").append(seconds(latency.p50()))
                    .append("；p90 ").append(seconds(latency.p90()))
                    .append("；max ").append(seconds(latency.max())).append('\n');
        }
        if (n >= P95_SAMPLE_FLOOR) {
            sb.append("- p95：").append(seconds(latency.p95())).append('\n');
        } else if (n <= 0L) {
            sb.append("- p95：数据缺失").append('\n');
        } else {
            sb.append("- p95：").append(sampleShort(n, P95_SAMPLE_FLOOR)).append('\n');
        }
        sb.append("- 延迟排除：error ").append(latency.excludedError())
                .append("；cached ").append(latency.excludedCached())
                .append("；缺耗时 ").append(latency.excludedMissingElapsed()).append('\n');
    }

    /** 输出吞吐：usage_completion ÷ (elapsed_ms / 1000)，只取 gen/ok/非 cached 样本。 */
    private static void appendThroughput(StringBuilder sb, Throughput throughput) {
        if (throughput.n <= 0) {
            sb.append("- 输出吞吐：数据缺失（无成功样本）").append('\n');
        } else if (throughput.n < LATENCY_SAMPLE_FLOOR) {
            sb.append("- 输出吞吐：mean ").append(tps(throughput.mean))
                    .append("；p50：").append(sampleShort(throughput.n, LATENCY_SAMPLE_FLOOR))
                    .append('\n');
        } else {
            sb.append("- 输出吞吐：n=").append(throughput.n)
                    .append("；mean ").append(tps(throughput.mean))
                    .append("；p50 ").append(tps(throughput.p50)).append('\n');
        }
    }

    /** 重试子集（attempt ≥ 1）的延迟 n、mean 与 p50；与主分位同口径，不混入失败/缓存。 */
    private static void appendRetrySubset(StringBuilder sb, RetryLatency retry, long retryCount) {
        if (retry.n <= 0) {
            sb.append("- 重试子集：n=0（")
                    .append(retryCount <= 0L ? "无重试调用" : "无成功重试样本")
                    .append("）").append('\n');
        } else if (retry.n < LATENCY_SAMPLE_FLOOR) {
            sb.append("- 重试子集：n=").append(retry.n)
                    .append("；mean ").append(seconds(retry.mean))
                    .append("；p50：").append(sampleShort(retry.n, LATENCY_SAMPLE_FLOOR))
                    .append('\n');
        } else {
            sb.append("- 重试子集：n=").append(retry.n)
                    .append("；mean ").append(seconds(retry.mean))
                    .append("；p50 ").append(seconds(retry.p50)).append('\n');
        }
    }

    private static void appendCalls(StringBuilder sb, EvalMetrics metrics) {
        EvalMetrics.Calls calls = metrics.calls();
        EvalMetrics.Budget budget = metrics.budget();
        sb.append("- 调用：").append(budget.used()).append("/").append(budget.budget())
                .append("（").append(percent(budget.ratio())).append("）；调用级成功率 ")
                .append(percent(calls.successRate())).append('\n');
    }

    private static void appendStage(StringBuilder sb, EvalMetrics.Calls calls) {
        sb.append("- stage 分布：gen ").append(stage(calls, "gen"))
                .append("；judge ").append(stage(calls, "judge"))
                .append("；calib ").append(stage(calls, "calib")).append('\n');
    }

    private static void appendSamples(StringBuilder sb, EvalMetrics.Samples samples) {
        sb.append("- 样本：total ").append(samples.total())
                .append("；样本级成功率 ").append(percent(samples.successRate()))
                .append("；重试率 ").append(percent(samples.retryRate()))
                .append("；平均尝试 ").append(numOrMissing(samples.averageAttempts()))
                .append('\n');
    }

    private static void appendTokens(StringBuilder sb, EvalMetrics metrics) {
        EvalMetrics.Tokens tokens = metrics.tokens();
        sb.append("- 缓存命中率：").append(percent(metrics.calls().cacheHitRate()))
                .append("（cached 单列 ").append(tokens.cached().sum()).append("）").append('\n');
        sb.append("- 总 token：prompt ").append(tokens.prompt().sum())
                .append("；completion ").append(tokens.completion().sum())
                .append("；total ").append(tokens.total().sum())
                .append("；usage_cached ").append(tokens.cached().sum()).append('\n');
    }

    private static void appendErrorTypes(StringBuilder sb, EvalMetrics.Calls calls) {
        if (calls.total() <= 0L) {
            sb.append("- error_type 分布：数据缺失（无调用）").append('\n');
            return;
        }
        List<String> parts = new ArrayList<String>();
        for (String errorType : ERROR_TYPE_ORDER) {
            Long count = calls.errorTypes().get(errorType);
            if (count != null && count.longValue() > 0L) {
                parts.add(errorType + " " + count.longValue());
            }
        }
        sb.append("- error_type 分布：").append(parts.isEmpty() ? "无" : join(parts, "；"))
                .append('\n');
    }

    /** 题级统计：error 与 invalid 分列，不合成单一失败率（§7.4）。 */
    private static void appendCaseStats(StringBuilder sb, EvalMetrics.Cases cases) {
        sb.append("- 题级：ok ").append(cases.ok())
                .append("；vetoed ").append(cases.vetoed())
                .append("；error ").append(cases.error())
                .append("；invalid ").append(cases.invalid())
                .append("；非 ok 率 ").append(percent(cases.nonOkRate())).append('\n');
    }

    private static void appendRunDuration(StringBuilder sb, EvalReportData data) {
        Long durationMs = data == null ? null : data.durationMs();
        if (durationMs == null) {
            sb.append("- run 总时长：数据缺失").append('\n');
        } else {
            sb.append("- run 总时长：").append(seconds(millis(durationMs))).append('\n');
        }
    }

    /**
     * 评测自身开销 = run 总时长 − Σ 所有 AI 调用耗时；含调用间隙，只作低可信参考。
     * 缺 run 时长或任一有效调用缺 elapsed_ms 时固定标注数据缺失，不按部分样本求和。
     */
    private static void appendOverhead(StringBuilder sb, EvalReportData data,
                                       List<JSONObject> callRows) {
        Long durationMs = data == null ? null : data.durationMs();
        if (durationMs == null) {
            sb.append("- 评测自身开销：数据缺失（无 run 总时长）").append('\n');
            return;
        }
        if (callRows.isEmpty()) {
            sb.append("- 评测自身开销：数据缺失（无 AI 调用）").append('\n');
            return;
        }
        long sum = 0L;
        int missingElapsed = 0;
        for (JSONObject row : callRows) {
            Long elapsed = longValue(row, "elapsed_ms");
            if (elapsed == null) {
                missingElapsed++;
            } else {
                sum += Math.max(0L, elapsed.longValue());
            }
        }
        if (missingElapsed > 0) {
            sb.append("- 评测自身开销：数据缺失（").append(missingElapsed)
                    .append(" 条调用缺 elapsed_ms）").append('\n');
            return;
        }
        long overhead = Math.max(0L, durationMs.longValue() - sum);
        sb.append("- 评测自身开销：").append(seconds(millis(overhead)))
                .append("（低可信，含调用间隙）").append('\n');
    }

    /** §7.6 / E12：v1 不采集或不适用的指标必须固定标注，且不给出具体数值。 */
    private static void appendFixedLimits(StringBuilder sb) {
        sb.append("- TTFT：不可得（阻塞式调用，非流式）").append('\n');
        sb.append("- ITL/TPOT：不可得（非流式，无逐 token 时间）").append('\n');
        sb.append("- run 级 TPS/RPS：不适用（非服务化压测）").append('\n');
        sb.append("- 工具轮数：恒 0（toolEnable=false）").append('\n');
        sb.append("- 金额：不报（无计价口径）").append('\n');
    }

    // ===== 逐调用派生口径 =====

    private static Throughput throughput(List<JSONObject> callRows) {
        List<Double> values = new ArrayList<Double>();
        for (JSONObject row : callRows) {
            if (row == null || !"gen".equals(text(row, "stage"))
                    || !"ok".equals(text(row, "status")) || isCached(row)) {
                continue;
            }
            Long elapsedMs = longValue(row, "elapsed_ms");
            Long completion = longValue(row, "usage_completion");
            if (elapsedMs == null || elapsedMs.longValue() <= 0L || completion == null) {
                continue;
            }
            values.add(Double.valueOf(completion.doubleValue() * 1000.0D
                    / elapsedMs.doubleValue()));
        }
        int n = values.size();
        Double mean = null;
        Double p50 = null;
        if (n > 0) {
            double sum = 0D;
            for (Double value : values) {
                sum += value.doubleValue();
            }
            mean = Double.valueOf(sum / n);
        }
        if (n >= LATENCY_SAMPLE_FLOOR) {
            p50 = percentile(values, 0.50D);
        }
        return new Throughput(n, mean, p50);
    }

    private static RetryLatency retryLatency(List<JSONObject> callRows) {
        List<Double> values = new ArrayList<Double>();
        for (JSONObject row : callRows) {
            if (row == null || !"gen".equals(text(row, "stage"))
                    || !"ok".equals(text(row, "status")) || isCached(row)) {
                continue;
            }
            Long attempt = longValue(row, "attempt");
            Long elapsedMs = longValue(row, "elapsed_ms");
            if (attempt == null || attempt.longValue() < 1L
                    || elapsedMs == null || elapsedMs.longValue() <= 0L) {
                continue;
            }
            values.add(Double.valueOf(elapsedMs.doubleValue()));
        }
        int n = values.size();
        Double mean = null;
        Double p50 = null;
        if (n > 0) {
            double sum = 0D;
            for (Double value : values) {
                sum += value.doubleValue();
            }
            mean = Double.valueOf(sum / n);
        }
        if (n >= LATENCY_SAMPLE_FLOOR) {
            p50 = percentile(values, 0.50D);
        }
        return new RetryLatency(n, mean, p50);
    }

    /** 最近秩分位（与 EvalMetricsService 同口径）：rank = ceil(p × n)，夹到 1..n。 */
    private static Double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        int rank = (int) Math.ceil(p * n);
        if (rank < 1) {
            rank = 1;
        }
        if (rank > n) {
            rank = n;
        }
        return sorted.get(rank - 1);
    }

    private static long stage(EvalMetrics.Calls calls, String stage) {
        Long count = calls.byStage().get(stage);
        return count == null ? 0L : count.longValue();
    }

    private static Double millis(long value) {
        return Double.valueOf((double) value);
    }

    private static String seconds(Double millis) {
        return millis == null ? EvalReportData.MISSING
                : String.format(Locale.ROOT, "%.1f 秒", Double.valueOf(millis.doubleValue() / 1000.0D));
    }

    private static String tps(Double value) {
        return value == null ? EvalReportData.MISSING
                : String.format(Locale.ROOT, "%.1f token/s", value);
    }

    private static String percent(Double value) {
        return value == null ? EvalReportData.MISSING
                : String.format(Locale.ROOT, "%.1f%%", Double.valueOf(value.doubleValue() * 100.0D));
    }

    private static String numOrMissing(Double value) {
        return value == null ? EvalReportData.MISSING
                : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String sampleShort(long n, int need) {
        return String.format(Locale.ROOT, "样本不足（n=%d，需 ≥%d）", Long.valueOf(n),
                Integer.valueOf(need));
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static boolean isCached(JSONObject row) {
        Long cached = longValue(row, "cached");
        return cached != null && cached.longValue() == 1L;
    }

    private static String text(JSONObject row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Long longValue(JSONObject row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue() ? Long.valueOf(1L) : Long.valueOf(0L);
        }
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        try {
            return Long.valueOf(Long.parseLong(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class Throughput {
        private final int n;
        private final Double mean;
        private final Double p50;

        private Throughput(int n, Double mean, Double p50) {
            this.n = n;
            this.mean = mean;
            this.p50 = p50;
        }
    }

    private static final class RetryLatency {
        private final int n;
        private final Double mean;
        private final Double p50;

        private RetryLatency(int n, Double mean, Double p50) {
            this.n = n;
            this.mean = mean;
            this.p50 = p50;
        }
    }
}
