package org.moboxlab.mbb.eval.baseline;

import java.util.Arrays;
import java.util.Random;

/**
 * bootstrap 置信区间计算器（规格 #50 §8.4、§15.6 F16）。
 *
 * <p>冻结参数：1000 次重采样、种子 41。参数可注入，只为测试边界与复现；
 * 生产默认值必须保持 {@link #DEFAULT_ITERATIONS} 与 {@link #DEFAULT_SEED}，
 * 禁止使用时间戳或随机设备作种子。</p>
 *
 * <p>输入是一组同题配对的题分差（单位：0..100 分）。算法：</p>
 * <pre>
 * delta      = mean(diff)
 * for b in 1..iterations:
 *     delta_b = mean(resample(diff, n))   // 有放回重采样，java.util.Random(seed)
 * ci_low     = quantile(2.5%)
 * ci_high    = quantile(97.5%)
 * significant = (ci_low &gt; 0 || ci_high &lt; 0) &amp;&amp; abs(delta) &gt;= 100 / n
 * </pre>
 *
 * <p>{@code n} 是本次参与 bootstrap 的配对题数；维度各自用自己的配对题数计算，因此
 * 最小步长按维度粒度生效。总分不调用本类：总分只报点值，不做区间与显著性（§8.4）。</p>
 */
public final class EvalDeltaCalculator {

    /** 冻结的重采样次数（§8.4）。 */
    public static final int DEFAULT_ITERATIONS = 1000;
    /** 冻结的随机种子（§8.4）。 */
    public static final long DEFAULT_SEED = 41L;
    /** 样本不足门槛：可比 run 对至少 20 道有效配对题才出区间（§8.4）。 */
    public static final int MIN_PAIRS = 20;
    /** 下分位：2.5%。 */
    public static final double LOWER_QUANTILE = 0.025d;
    /** 上分位：97.5%。 */
    public static final double UPPER_QUANTILE = 0.975d;

    private final int iterations;
    private final long seed;

    /** 生产默认参数：1000 次、种子 41。 */
    public EvalDeltaCalculator() {
        this(DEFAULT_ITERATIONS, DEFAULT_SEED);
    }

    /**
     * 可注入参数。
     *
     * @param iterations 重采样次数，必须为正
     * @param seed       随机种子
     */
    public EvalDeltaCalculator(int iterations, long seed) {
        if (iterations <= 0) {
            throw new IllegalArgumentException("bootstrap 次数必须为正：" + iterations);
        }
        this.iterations = iterations;
        this.seed = seed;
    }

    public int iterations() {
        return iterations;
    }

    public long seed() {
        return seed;
    }

    /**
     * 对一组配对题分差计算点估计与 bootstrap 区间。
     *
     * @param diffs 同题配对题分差，单位 0..100；不能为空
     * @return 点估计、区间、样本量与参数快照
     */
    public Estimate estimate(double[] diffs) {
        if (diffs == null || diffs.length == 0) {
            throw new IllegalArgumentException("配对题分差不能为空");
        }
        double[] values = Arrays.copyOf(diffs, diffs.length);
        int n = values.length;
        double sum = 0.0d;
        for (double value : values) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("配对题分差必须是有限数值：" + value);
            }
            sum += value;
        }
        double delta = sum / n;

        // 固定种子：同一份数据任何时候都得到同一区间，禁止外部传入随机源
        Random random = new Random(seed);
        double[] boot = new double[iterations];
        for (int b = 0; b < iterations; b++) {
            double resampled = 0.0d;
            for (int i = 0; i < n; i++) {
                resampled += values[random.nextInt(n)];
            }
            boot[b] = resampled / n;
        }
        Arrays.sort(boot);
        double ciLow = quantile(boot, LOWER_QUANTILE);
        double ciHigh = quantile(boot, UPPER_QUANTILE);
        return new Estimate(delta, ciLow, ciHigh, n, iterations, seed);
    }

    /** 最小步长 100/n：小于它的 delta 一律不标显著（§8.4）。 */
    public static double minimumStep(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("配对题数必须为正：" + n);
        }
        return 100.0d / n;
    }

    /**
     * 显著性判定：区间不跨 0 且点估计绝对值不小于最小步长。
     *
     * <p>只对区间存在的估计调用；不可比 / 样本不足时调用方不得用本方法下结论。</p>
     */
    public static boolean significant(Estimate estimate) {
        if (estimate == null) {
            return false;
        }
        boolean intervalExcludesZero = estimate.ciLow() > 0.0d || estimate.ciHigh() < 0.0d;
        return intervalExcludesZero
                && Math.abs(estimate.delta()) >= minimumStep(estimate.pairs());
    }

    /**
     * 分位数（线性插值）：{@code position = q * (n - 1)}，在相邻两个已排序样本间插值。
     *
     * <p>固定定义保证同一份数据、同一参数下结果逐位一致；不依赖外部统计库。</p>
     */
    static double quantile(double[] sorted, double q) {
        if (sorted == null || sorted.length == 0) {
            throw new IllegalArgumentException("分位数输入不能为空");
        }
        if (q < 0.0d || q > 1.0d) {
            throw new IllegalArgumentException("分位数必须在 [0, 1]：" + q);
        }
        if (sorted.length == 1) {
            return sorted[0];
        }
        double position = q * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower];
        }
        double fraction = position - lower;
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction;
    }

    /** 一次 bootstrap 的完整结果；不可变值对象。 */
    public static final class Estimate {

        private final double delta;
        private final double ciLow;
        private final double ciHigh;
        private final int pairs;
        private final int iterations;
        private final long seed;

        private Estimate(double delta, double ciLow, double ciHigh, int pairs,
                         int iterations, long seed) {
            this.delta = delta;
            this.ciLow = ciLow;
            this.ciHigh = ciHigh;
            this.pairs = pairs;
            this.iterations = iterations;
            this.seed = seed;
        }

        /** 点估计：配对题分差的均值，单位 0..100 分。 */
        public double delta() {
            return delta;
        }

        /** 2.5% 分位。 */
        public double ciLow() {
            return ciLow;
        }

        /** 97.5% 分位。 */
        public double ciHigh() {
            return ciHigh;
        }

        /** 参与本估计的配对题数。 */
        public int pairs() {
            return pairs;
        }

        public int iterations() {
            return iterations;
        }

        public long seed() {
            return seed;
        }

        /** 便利方法：按 §8.4 判定显著。 */
        public boolean significant() {
            return EvalDeltaCalculator.significant(this);
        }
    }
}
