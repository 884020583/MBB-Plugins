package org.moboxlab.mbb.eval.metrics;

import com.alibaba.fastjson.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标计算纯函数（E-1：规格 §7.2–§7.4）。
 *
 * <p>输入从 {@code _call} / {@code _case_result} 读出的行集（§4.2 列名），输出不可变的
 * {@link EvalMetrics}。本类不读库、不写库、不记日志，同一行集重复计算得到同一结果。</p>
 *
 * <p>口径要点：</p>
 * <ul>
 *   <li>延迟分位只取 gen + ok + cached=0 + elapsed_ms 非空；n 阈值见 §7.2。</li>
 *   <li>调用级指标覆盖全部 attempt；样本级指标按 (case_id, sample_index) 聚合，
 *       cached=1 样本作废重采、不计入样本数。</li>
 *   <li>token 只统计 ok 且非 cached 调用；cached=1 调用单列并从总量剔除。</li>
 *   <li>不可得值一律 null，不补 0（§7.5）。</li>
 * </ul>
 */
public final class EvalMetricsService {

    private static final String[] STAGES = {"gen", "judge", "calib"};

    /** §7.4 封闭八值错误码。 */
    private static final String[] ERROR_TYPES = {
        "disabled", "invalid-param", "history-too-long", "profile-invalid",
        "ai-missing", "busy", "timeout", "internal"
    };

    private static final String UNKNOWN = "unknown";

    /**
     * 计算一次 run 的全部指标。
     *
     * @param callRows {@code _call} 行集，字段名取 §4.2.4；null 视为空集
     * @param caseRows {@code _case_result} 行集，字段名取 §4.2.2；null 视为空集
     */
    public EvalMetrics compute(List<JSONObject> callRows, List<JSONObject> caseRows) {
        List<JSONObject> calls = callRows == null
                ? Collections.<JSONObject>emptyList() : callRows;
        List<JSONObject> cases = caseRows == null
                ? Collections.<JSONObject>emptyList() : caseRows;
        return new EvalMetrics(
                computeLatency(calls),
                computeCalls(calls),
                computeSamples(calls),
                computeCases(cases),
                computeTokens(calls),
                computeBudget(calls));
    }

    private EvalMetrics.Latency computeLatency(List<JSONObject> calls) {
        List<Double> samples = new ArrayList<Double>();
        int excludedError = 0;
        int excludedCached = 0;
        int excludedMissingElapsed = 0;

        for (JSONObject row : calls) {
            if (row == null || !"gen".equals(text(row, "stage"))) {
                continue;
            }
            String status = text(row, "status");
            if (!"ok".equals(status)) {
                if ("error".equals(status)) {
                    excludedError++;
                }
                continue;
            }
            if (isCached(row)) {
                excludedCached++;
                continue;
            }
            Long elapsed = longValue(row, "elapsed_ms");
            if (elapsed == null) {
                excludedMissingElapsed++;
                continue;
            }
            samples.add(elapsed.doubleValue());
        }

        if (samples.isEmpty()) {
            return new EvalMetrics.Latency(0L, null, null, null, null, null,
                    EvalMetrics.REASON_NO_SAMPLES, excludedError, excludedCached, excludedMissingElapsed);
        }

        Collections.sort(samples);
        int n = samples.size();
        double sum = 0.0;
        for (Double sample : samples) {
            sum += sample;
        }
        Double mean = sum / n;

        String reason;
        Double p50 = null;
        Double p90 = null;
        Double p95 = null;
        Double max = null;
        if (n < 20) {
            reason = EvalMetrics.REASON_SAMPLE_BELOW_20;
        } else {
            p50 = percentile(samples, 0.50);
            p90 = percentile(samples, 0.90);
            max = samples.get(n - 1);
            if (n < 60) {
                reason = EvalMetrics.REASON_SAMPLE_BELOW_60;
            } else {
                p95 = percentile(samples, 0.95);
                reason = null;
            }
        }
        return new EvalMetrics.Latency(n, mean, p50, p90, p95, max, reason,
                excludedError, excludedCached, excludedMissingElapsed);
    }

    /**
     * 最近秩分位（nearest-rank）：排序后取第 {@code ceil(p*n)} 个样本（1 基），
     * 不插值。规格未指定插值口径，选最近秩以保证分位值一定来自真实调用样本。
     */
    private static Double percentile(List<Double> sorted, double p) {
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

    private EvalMetrics.Calls computeCalls(List<JSONObject> calls) {
        long total = 0L;
        long ok = 0L;
        long error = 0L;
        long cached = 0L;
        long retryCount = 0L;

        Map<String, Long> byStage = new LinkedHashMap<String, Long>();
        for (String stage : STAGES) {
            byStage.put(stage, Long.valueOf(0L));
        }
        Map<String, Long> errorTypes = new LinkedHashMap<String, Long>();
        for (String errorType : ERROR_TYPES) {
            errorTypes.put(errorType, Long.valueOf(0L));
        }

        for (JSONObject row : calls) {
            if (row == null) {
                continue;
            }
            total++;

            String status = text(row, "status");
            if ("ok".equals(status)) {
                ok++;
            } else if ("error".equals(status)) {
                error++;
            }

            String stage = text(row, "stage");
            increment(byStage, stage == null ? UNKNOWN : stage);

            if (isCached(row)) {
                cached++;
            }

            Long attempt = longValue(row, "attempt");
            if (attempt != null && attempt.longValue() > 0L) {
                retryCount += attempt.longValue();
            }

            if ("error".equals(status)) {
                String errorType = text(row, "error_type");
                increment(errorTypes, errorType == null ? UNKNOWN : errorType);
            }
        }

        Map<String, Double> errorTypeRatios = new LinkedHashMap<String, Double>();
        for (Map.Entry<String, Long> entry : errorTypes.entrySet()) {
            double ratio = error == 0L ? 0.0 : entry.getValue().longValue() / (double) error;
            errorTypeRatios.put(entry.getKey(), Double.valueOf(ratio));
        }

        Double successRate = total == 0L ? null : Double.valueOf(ok / (double) total);
        Double cacheHitRate = total == 0L ? null : Double.valueOf(cached / (double) total);
        return new EvalMetrics.Calls(total, ok, error, byStage, successRate,
                cached, cacheHitRate, retryCount, errorTypes, errorTypeRatios);
    }

    /**
     * 样本级统计：只认 gen 阶段、未命中缓存、且 (case_id, sample_index) 完整的调用。
     * 同一键下 attempt 最大者为最终尝试，其 status 决定样本成败。
     */
    private EvalMetrics.Samples computeSamples(List<JSONObject> calls) {
        Map<String, SampleGroup> groups = new LinkedHashMap<String, SampleGroup>();

        for (JSONObject row : calls) {
            if (row == null || !"gen".equals(text(row, "stage")) || isCached(row)) {
                continue;
            }
            String caseId = text(row, "case_id");
            Long sampleIndex = longValue(row, "sample_index");
            if (caseId == null || sampleIndex == null) {
                continue;
            }
            long attempt = valueOrZero(longValue(row, "attempt"));
            if (attempt < 0L) {
                attempt = 0L;
            }

            String key = caseId + "\u0000" + sampleIndex.longValue();
            SampleGroup group = groups.get(key);
            if (group == null) {
                group = new SampleGroup();
                groups.put(key, group);
            }
            group.callCount++;
            if (attempt >= group.maxAttempt) {
                group.maxAttempt = attempt;
                group.finalStatus = text(row, "status");
            }
            if (attempt >= 1L) {
                group.retried = true;
            }
        }

        long total = groups.size();
        long ok = 0L;
        long attempts = 0L;
        long retried = 0L;
        for (SampleGroup group : groups.values()) {
            if ("ok".equals(group.finalStatus)) {
                ok++;
            }
            attempts += group.callCount;
            if (group.retried) {
                retried++;
            }
        }

        long failed = total - ok;
        Double successRate = total == 0L ? null : Double.valueOf(ok / (double) total);
        Double retryRate = total == 0L ? null : Double.valueOf(retried / (double) total);
        Double averageAttempts = total == 0L ? null : Double.valueOf(attempts / (double) total);
        return new EvalMetrics.Samples(total, ok, failed, attempts, successRate,
                retried, retryRate, averageAttempts);
    }

    private EvalMetrics.Cases computeCases(List<JSONObject> caseRows) {
        long total = 0L;
        long ok = 0L;
        long vetoed = 0L;
        long error = 0L;
        long invalid = 0L;

        for (JSONObject row : caseRows) {
            if (row == null) {
                continue;
            }
            total++;
            String status = text(row, "status");
            if ("ok".equals(status)) {
                ok++;
            } else if ("vetoed".equals(status)) {
                vetoed++;
            } else if ("error".equals(status)) {
                error++;
            } else if ("invalid".equals(status)) {
                invalid++;
            }
        }

        long valid = ok + vetoed;
        Double errorRate = total == 0L ? null : Double.valueOf(error / (double) total);
        Double invalidRate = total == 0L ? null : Double.valueOf(invalid / (double) total);
        Double nonOkRate = total == 0L ? null : Double.valueOf((error + invalid) / (double) total);
        return new EvalMetrics.Cases(total, ok, vetoed, error, invalid, valid,
                errorRate, invalidRate, nonOkRate);
    }

    /**
     * token 汇总：只累计 status=ok 且 cached!=1 的调用；cached=1 调用的 usage
     * 是缓存命中返回的原值，重复累加会虚增，单列计数后从总量剔除（#33）。
     */
    private EvalMetrics.Tokens computeTokens(List<JSONObject> calls) {
        long promptSum = 0L;
        long promptN = 0L;
        long completionSum = 0L;
        long completionN = 0L;
        long totalSum = 0L;
        long totalN = 0L;
        long cachedSum = 0L;
        long cachedN = 0L;
        long cachedCallsExcluded = 0L;

        for (JSONObject row : calls) {
            if (row == null || !"ok".equals(text(row, "status"))) {
                continue;
            }
            if (isCached(row)) {
                cachedCallsExcluded++;
                continue;
            }

            Long prompt = longValue(row, "usage_prompt");
            if (prompt != null) {
                promptSum += prompt.longValue();
                promptN++;
            }
            Long completion = longValue(row, "usage_completion");
            if (completion != null) {
                completionSum += completion.longValue();
                completionN++;
            }
            Long total = longValue(row, "usage_total");
            if (total != null) {
                totalSum += total.longValue();
                totalN++;
            }
            Long cached = longValue(row, "usage_cached");
            if (cached != null) {
                cachedSum += cached.longValue();
                cachedN++;
            }
        }

        return new EvalMetrics.Tokens(
                column(promptN, promptSum),
                column(completionN, completionSum),
                column(totalN, totalSum),
                column(cachedN, cachedSum),
                cachedCallsExcluded);
    }

    private static EvalMetrics.TokenColumn column(long n, long sum) {
        Double mean = n == 0L ? null : Double.valueOf(sum / (double) n);
        return new EvalMetrics.TokenColumn(n, sum, mean);
    }

    private EvalMetrics.Budget computeBudget(List<JSONObject> calls) {
        long used = 0L;
        for (JSONObject row : calls) {
            if (row != null) {
                used++;
            }
        }
        long budget = EvalMetrics.HARD_CALL_BUDGET;
        double ratio = budget == 0L ? 0.0 : used / (double) budget;
        return new EvalMetrics.Budget(used, budget, ratio);
    }

    private static void increment(Map<String, Long> counts, String key) {
        Long previous = counts.get(key);
        counts.put(key, Long.valueOf(previous == null ? 1L : previous.longValue() + 1L));
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private static boolean isCached(JSONObject row) {
        Integer cached = integer(row, "cached");
        return cached != null && cached.intValue() == 1;
    }

    private static String text(JSONObject row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer integer(JSONObject row, String key) {
        Long value = longValue(row, key);
        if (value == null) {
            return null;
        }
        return Integer.valueOf(value.intValue());
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
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        if ("true".equalsIgnoreCase(text)) {
            return Long.valueOf(1L);
        }
        if ("false".equalsIgnoreCase(text)) {
            return Long.valueOf(0L);
        }
        try {
            return Long.valueOf(Long.parseLong(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 单样本聚合态：最大 attempt、该 attempt 的 status、是否重试过、调用行数。 */
    private static final class SampleGroup {
        private long maxAttempt = -1L;
        private String finalStatus;
        private boolean retried;
        private long callCount;
    }
}
