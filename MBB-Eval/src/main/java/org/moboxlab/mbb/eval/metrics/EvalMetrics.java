package org.moboxlab.mbb.eval.metrics;

import com.alibaba.fastjson.JSONObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 run 的指标快照（E-1：规格 §7.2–§7.4）。
 *
 * <p>不可变值对象：所有集合在构造时复制并只读，字段只能经 getter 读取；{@link #toJson()}
 * 每次返回新的 JSONObject，调用方修改返回值不会影响快照。字段名与 §4.2 的列名一致，
 * 报表侧（E-3）直接消费 JSON。</p>
 *
 * <p>不可得口径统一用 {@code null} 表示，并尽量附原因码；报表不得把 null 当成 0
 * （§7.5 采集失败不补 0）。</p>
 */
public final class EvalMetrics {

    /** 延迟样本为空：mean 与全部分位均不可得。 */
    public static final String REASON_NO_SAMPLES = "NO_SAMPLES";
    /** 0 &lt; n &lt; 20：只出 mean，分位不可得。 */
    public static final String REASON_SAMPLE_BELOW_20 = "SAMPLE_BELOW_20";
    /** 20 ≤ n &lt; 60：出 p50/p90/max，p95 不可得。 */
    public static final String REASON_SAMPLE_BELOW_60 = "SAMPLE_BELOW_60";

    /** 单 run 调用预算硬顶（§7.3：分母恒为 100，不用计划值 79）。 */
    public static final int HARD_CALL_BUDGET = 100;

    private final Latency latency;
    private final Calls calls;
    private final Samples samples;
    private final Cases cases;
    private final Tokens tokens;
    private final Budget budget;

    EvalMetrics(Latency latency, Calls calls, Samples samples, Cases cases,
            Tokens tokens, Budget budget) {
        this.latency = latency;
        this.calls = calls;
        this.samples = samples;
        this.cases = cases;
        this.tokens = tokens;
        this.budget = budget;
    }

    public Latency latency() {
        return latency;
    }

    public Calls calls() {
        return calls;
    }

    public Samples samples() {
        return samples;
    }

    public Cases cases() {
        return cases;
    }

    public Tokens tokens() {
        return tokens;
    }

    public Budget budget() {
        return budget;
    }

    /** 冻结的 JSON 形状；null 表示该口径下不可得（原因见各自 reasonCode / n）。 */
    public JSONObject toJson() {
        JSONObject root = new JSONObject(true);
        root.put("latency", latency.toJson());
        root.put("calls", calls.toJson());
        root.put("samples", samples.toJson());
        root.put("cases", cases.toJson());
        root.put("tokens", tokens.toJson());
        root.put("budget", budget.toJson());
        return root;
    }

    private static Map<String, Long> copyLongs(Map<String, Long> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<String, Long>(source));
    }

    private static Map<String, Double> copyDoubles(Map<String, Double> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<String, Double>(source));
    }

    /**
     * 生成延迟分位（§7.2）：只取 {@code stage=gen}、{@code status=ok}、{@code cached=0}
     * 且 {@code elapsed_ms} 非空的调用；错误、缓存命中与缺失耗时单独计数，不混入分位。
     * n &lt; 20 只出 mean，n ≥ 60 才出 p95。
     */
    public static final class Latency {
        private final long n;
        private final Double mean;
        private final Double p50;
        private final Double p90;
        private final Double p95;
        private final Double max;
        private final String reasonCode;
        private final int excludedError;
        private final int excludedCached;
        private final int excludedMissingElapsed;

        Latency(long n, Double mean, Double p50, Double p90, Double p95, Double max,
                String reasonCode, int excludedError, int excludedCached, int excludedMissingElapsed) {
            this.n = n;
            this.mean = mean;
            this.p50 = p50;
            this.p90 = p90;
            this.p95 = p95;
            this.max = max;
            this.reasonCode = reasonCode;
            this.excludedError = excludedError;
            this.excludedCached = excludedCached;
            this.excludedMissingElapsed = excludedMissingElapsed;
        }

        public long n() {
            return n;
        }

        public Double mean() {
            return mean;
        }

        public Double p50() {
            return p50;
        }

        public Double p90() {
            return p90;
        }

        public Double p95() {
            return p95;
        }

        public Double max() {
            return max;
        }

        public String reasonCode() {
            return reasonCode;
        }

        public int excludedError() {
            return excludedError;
        }

        public int excludedCached() {
            return excludedCached;
        }

        public int excludedMissingElapsed() {
            return excludedMissingElapsed;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject(true);
            json.put("n", n);
            json.put("mean", mean);
            json.put("p50", p50);
            json.put("p90", p90);
            json.put("p95", p95);
            json.put("max", max);
            json.put("reasonCode", reasonCode);
            json.put("excludedError", excludedError);
            json.put("excludedCached", excludedCached);
            json.put("excludedMissingElapsed", excludedMissingElapsed);
            return json;
        }
    }

    /**
     * 调用级指标（§7.3、§7.4）：每次 attempt 计 1，含重试、缓存命中与失败调用。
     * 成功率分母为全部调用行；错误类型分布只统计 {@code status=error} 行。
     */
    public static final class Calls {
        private final long total;
        private final long ok;
        private final long error;
        private final Map<String, Long> byStage;
        private final Double successRate;
        private final long cached;
        private final Double cacheHitRate;
        private final long retryCount;
        private final Map<String, Long> errorTypes;
        private final Map<String, Double> errorTypeRatios;

        Calls(long total, long ok, long error, Map<String, Long> byStage, Double successRate,
                long cached, Double cacheHitRate, long retryCount,
                Map<String, Long> errorTypes, Map<String, Double> errorTypeRatios) {
            this.total = total;
            this.ok = ok;
            this.error = error;
            this.byStage = copyLongs(byStage);
            this.successRate = successRate;
            this.cached = cached;
            this.cacheHitRate = cacheHitRate;
            this.retryCount = retryCount;
            this.errorTypes = copyLongs(errorTypes);
            this.errorTypeRatios = copyDoubles(errorTypeRatios);
        }

        public long total() {
            return total;
        }

        public long ok() {
            return ok;
        }

        public long error() {
            return error;
        }

        /** 按 stage 的调用计数；gen / judge / calib 恒有键（无调用时为 0）。 */
        public Map<String, Long> byStage() {
            return byStage;
        }

        public Double successRate() {
            return successRate;
        }

        public long cached() {
            return cached;
        }

        public Double cacheHitRate() {
            return cacheHitRate;
        }

        /** Σ attempt（§7.4）：attempt 从 0 起，重试一次即加 1。 */
        public long retryCount() {
            return retryCount;
        }

        /** 封闭八值错误码恒有键（无对应错误时为 0），未知码追加在末尾。 */
        public Map<String, Long> errorTypes() {
            return errorTypes;
        }

        /** 各错误码占 error 行数的比例；无 error 行时恒为 0。 */
        public Map<String, Double> errorTypeRatios() {
            return errorTypeRatios;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject(true);
            json.put("total", total);
            json.put("ok", ok);
            json.put("error", error);
            json.put("successRate", successRate);
            json.put("byStage", new JSONObject(new LinkedHashMap<String, Object>(byStage)));
            json.put("cached", cached);
            json.put("cacheHitRate", cacheHitRate);
            json.put("retryCount", retryCount);
            json.put("errorTypes", new JSONObject(new LinkedHashMap<String, Object>(errorTypes)));
            json.put("errorTypeRatios", new JSONObject(new LinkedHashMap<String, Object>(errorTypeRatios)));
            return json;
        }
    }

    /**
     * 样本级指标（§7.4）：样本 = gen 阶段一次独立采样，键为
     * {@code (case_id, sample_index)}；最终 attempt 的 status 决定成功与否。
     * {@code cached=1} 样本作废重采，不计入样本总数（§6.9.1、E9）。
     */
    public static final class Samples {
        private final long total;
        private final long ok;
        private final long failed;
        private final long attempts;
        private final Double successRate;
        private final long retried;
        private final Double retryRate;
        private final Double averageAttempts;

        Samples(long total, long ok, long failed, long attempts, Double successRate,
                long retried, Double retryRate, Double averageAttempts) {
            this.total = total;
            this.ok = ok;
            this.failed = failed;
            this.attempts = attempts;
            this.successRate = successRate;
            this.retried = retried;
            this.retryRate = retryRate;
            this.averageAttempts = averageAttempts;
        }

        public long total() {
            return total;
        }

        public long ok() {
            return ok;
        }

        public long failed() {
            return failed;
        }

        /** 参与样本统计的 gen 调用行数（排除 cached 样本），即平均尝试次数的分子。 */
        public long attempts() {
            return attempts;
        }

        public Double successRate() {
            return successRate;
        }

        /** 存在 attempt ≥ 1 的样本数（§7.4）。 */
        public long retried() {
            return retried;
        }

        public Double retryRate() {
            return retryRate;
        }

        /** 参与统计的 gen 调用行数 / 样本数；无样本时为 null。 */
        public Double averageAttempts() {
            return averageAttempts;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject(true);
            json.put("total", total);
            json.put("ok", ok);
            json.put("failed", failed);
            json.put("attempts", attempts);
            json.put("successRate", successRate);
            json.put("retried", retried);
            json.put("retryRate", retryRate);
            json.put("averageAttempts", averageAttempts);
            return json;
        }
    }

    /**
     * 题级计数（§7.4）：{@code invalid} 与 {@code error} 分开计数；
     * 非 ok 率按规格把两者相加，但另给 errorRate / invalidRate，禁止合成单一失败率。
     */
    public static final class Cases {
        private final long total;
        private final long ok;
        private final long vetoed;
        private final long error;
        private final long invalid;
        private final long valid;
        private final Double errorRate;
        private final Double invalidRate;
        private final Double nonOkRate;

        Cases(long total, long ok, long vetoed, long error, long invalid, long valid,
                Double errorRate, Double invalidRate, Double nonOkRate) {
            this.total = total;
            this.ok = ok;
            this.vetoed = vetoed;
            this.error = error;
            this.invalid = invalid;
            this.valid = valid;
            this.errorRate = errorRate;
            this.invalidRate = invalidRate;
            this.nonOkRate = nonOkRate;
        }

        public long total() {
            return total;
        }

        public long ok() {
            return ok;
        }

        public long vetoed() {
            return vetoed;
        }

        public long error() {
            return error;
        }

        public long invalid() {
            return invalid;
        }

        /** 有效题数 = ok + vetoed（§7.1 的 valid_case_count）。 */
        public long valid() {
            return valid;
        }

        public Double errorRate() {
            return errorRate;
        }

        public Double invalidRate() {
            return invalidRate;
        }

        public Double nonOkRate() {
            return nonOkRate;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject(true);
            json.put("total", total);
            json.put("ok", ok);
            json.put("vetoed", vetoed);
            json.put("error", error);
            json.put("invalid", invalid);
            json.put("valid", valid);
            json.put("errorRate", errorRate);
            json.put("invalidRate", invalidRate);
            json.put("nonOkRate", nonOkRate);
            return json;
        }
    }

    /** 单列 token 汇总；n 为该列非空样本数，null 字段不参与。 */
    public static final class TokenColumn {
        private final long n;
        private final long sum;
        private final Double mean;

        TokenColumn(long n, long sum, Double mean) {
            this.n = n;
            this.sum = sum;
            this.mean = mean;
        }

        public long n() {
            return n;
        }

        public long sum() {
            return sum;
        }

        public Double mean() {
            return mean;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject(true);
            json.put("n", n);
            json.put("sum", sum);
            json.put("mean", mean);
            return json;
        }
    }

    /**
     * token 汇总（§7.3）：只统计 {@code status=ok} 且 {@code cached=0} 的调用；
     * cached=1 调用返回原 usage，累加会重复计，单列计数并从总量剔除（#33）。
     */
    public static final class Tokens {
        private final TokenColumn prompt;
        private final TokenColumn completion;
        private final TokenColumn total;
        private final TokenColumn cached;
        private final long cachedCallsExcluded;

        Tokens(TokenColumn prompt, TokenColumn completion, TokenColumn total, TokenColumn cached,
                long cachedCallsExcluded) {
            this.prompt = prompt;
            this.completion = completion;
            this.total = total;
            this.cached = cached;
            this.cachedCallsExcluded = cachedCallsExcluded;
        }

        public TokenColumn prompt() {
            return prompt;
        }

        public TokenColumn completion() {
            return completion;
        }

        public TokenColumn total() {
            return total;
        }

        /** {@code usage_cached}（cachedPromptTokens）列，与 cached=1 调用计数是两回事。 */
        public TokenColumn cached() {
            return cached;
        }

        public long cachedCallsExcluded() {
            return cachedCallsExcluded;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject(true);
            json.put("prompt", prompt.toJson());
            json.put("completion", completion.toJson());
            json.put("total", total.toJson());
            json.put("cached", cached.toJson());
            json.put("cachedCallsExcluded", cachedCallsExcluded);
            return json;
        }
    }

    /** 预算消耗率（§7.3）：{@code calls_used / 100}，分子为调用行数，分母恒为硬顶。 */
    public static final class Budget {
        private final long used;
        private final long budget;
        private final double ratio;

        Budget(long used, long budget, double ratio) {
            this.used = used;
            this.budget = budget;
            this.ratio = ratio;
        }

        public long used() {
            return used;
        }

        public long budget() {
            return budget;
        }

        public double ratio() {
            return ratio;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject(true);
            json.put("used", used);
            json.put("budget", budget);
            json.put("ratio", ratio);
            return json;
        }
    }
}
