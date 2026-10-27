package org.moboxlab.mbb.eval.call;

import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;

/**
 * 生成调用重试与 cached 重采判定（规格 §10.6、§13.1、§13.2、§15.5 H4/H5/H6）。
 *
 * <p>判定只看生成出参，不按错误码猜可重试性：只有 {@code retryable=true} 才退避重试，
 * 其余失败立即按样本 error 返回。{@code cached=true} 视为上游缓存命中，必须换会话重采；
 * 重采用尽后仍 cached 的样本判 error，由上层决定是否计入分母。</p>
 *
 * <p>重试次数与重采次数按规格收紧到封闭区间：generateRetries 只允许 0..1，
 * cachedResamples 只允许 1..3。配置越界时静默夹取，避免配置错误放大调用量。
 * 退避秒数可注入，生产默认 2、5 秒，测试注入 0 免等待。</p>
 */
public final class EvalCallPolicy {

    /** 生产默认退避秒数：第一次重试 2 秒，第二次 5 秒（规格 §10.6 退避口径）。 */
    public static final int[] DEFAULT_BACKOFF_SECONDS = {2, 5};

    /** 生成重试次数下限，0 表示不重试。 */
    public static final int MIN_RETRIES = 0;

    /** 生成重试次数上限，规格只允许最多重试一次。 */
    public static final int MAX_RETRIES = 1;

    /** cached 重采次数下限，至少允许换一次会话。 */
    public static final int MIN_CACHED_RESAMPLES = 1;

    /** cached 重采次数上限，防止缓存持续命中时无限重采。 */
    public static final int MAX_CACHED_RESAMPLES = 3;

    /** 单次调用后的处置动作。 */
    public enum Action {
        /** 采信本次结果。 */
        ACCEPT,
        /** 退避后重试本次样本。 */
        RETRY,
        /** 换 sessionId 重新采样。 */
        RESAMPLE,
        /** 本次样本判 error，交由上层决定是否计入分母。 */
        FAIL
    }

    /** 一次判定结果：动作、退避秒数与可读原因。 */
    public static final class Decision {

        private final Action action;
        private final int backoffSeconds;
        private final String reason;

        private Decision(Action action, int backoffSeconds, String reason) {
            this.action = action;
            this.backoffSeconds = backoffSeconds;
            this.reason = reason;
        }

        static Decision accept() {
            return new Decision(Action.ACCEPT, 0, "生成成功且未命中缓存");
        }

        static Decision retry(int backoffSeconds) {
            return new Decision(Action.RETRY, backoffSeconds, "可重试失败，退避后重试");
        }

        static Decision resample() {
            return new Decision(Action.RESAMPLE, 0, "命中上游缓存，换会话重采");
        }

        static Decision fail(String reason) {
            return new Decision(Action.FAIL, 0, reason);
        }

        public Action action() {
            return action;
        }

        public int backoffSeconds() {
            return backoffSeconds;
        }

        public String reason() {
            return reason;
        }

        @Override
        public String toString() {
            return "Decision{" + action + ", backoff=" + backoffSeconds + ", reason=" + reason + "}";
        }
    }

    private final int maxRetries;
    private final int maxCachedResamples;
    private final int[] backoffSeconds;

    /**
     * @param maxRetries        生成重试次数，夹取到 0..1
     * @param maxCachedResamples cached 重采次数，夹取到 1..3
     * @param backoffSeconds    每次重试的退避秒数，为空时使用 {@link #DEFAULT_BACKOFF_SECONDS}
     */
    public EvalCallPolicy(int maxRetries, int maxCachedResamples, int[] backoffSeconds) {
        this.maxRetries = clamp(maxRetries, MIN_RETRIES, MAX_RETRIES);
        this.maxCachedResamples = clamp(maxCachedResamples, MIN_CACHED_RESAMPLES, MAX_CACHED_RESAMPLES);
        this.backoffSeconds = normalizeBackoff(backoffSeconds);
    }

    /** 按 EvalConfig 的生成重试与 cached 重采配置装配，退避取生产默认值。 */
    public static EvalCallPolicy fromConfig(EvalConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("EvalConfig 不能为空");
        }
        return new EvalCallPolicy(config.generateRetries(), config.cachedResamples(), DEFAULT_BACKOFF_SECONDS);
    }

    /**
     * 判定本次生成调用的下一步动作。
     *
     * @param result      生成出参，不能为空
     * @param retriesUsed 已重试次数（不含本次）
     * @param cachedHits  已含本次的 cached 命中次数
     */
    public Decision decide(EvalGenerateResult result, int retriesUsed, int cachedHits) {
        if (result == null) {
            throw new IllegalArgumentException("生成结果不能为空");
        }
        if (result.status()) {
            if (!result.cached()) {
                return Decision.accept();
            }
            if (cachedHits <= maxCachedResamples) {
                return Decision.resample();
            }
            return Decision.fail("cached 重采已达上限 " + maxCachedResamples + " 次，样本作废");
        }
        if (result.retryable() && retriesUsed < maxRetries) {
            int index = Math.min(Math.max(retriesUsed, 0), backoffSeconds.length - 1);
            return Decision.retry(backoffSeconds[index]);
        }
        if (result.retryable()) {
            return Decision.fail("重试次数已达上限 " + maxRetries + " 次，按样本 error 处理");
        }
        return Decision.fail("不可重试错误（error_type=" + result.errorType() + "），按样本 error 处理");
    }

    public int maxRetries() {
        return maxRetries;
    }

    public int maxCachedResamples() {
        return maxCachedResamples;
    }

    /** 返回退避秒数副本，避免调用方改动内部状态。 */
    public int[] backoffSeconds() {
        return backoffSeconds.clone();
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static int[] normalizeBackoff(int[] raw) {
        if (raw == null || raw.length == 0) {
            return DEFAULT_BACKOFF_SECONDS.clone();
        }
        int[] normalized = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            normalized[i] = Math.max(raw[i], 0);
        }
        return normalized;
    }
}
