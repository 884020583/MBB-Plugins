package org.moboxlab.mbb.eval.baseline;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * bootstrap 计算器单测（§8.4、§15.6 F16）。
 *
 * <p>覆盖冻结参数、固定种子复现、最小步长门槛、显著性边界与分位数定义。</p>
 */
public class EvalDeltaCalculatorTest {

    @Test
    public void defaultParametersShouldBeFrozen() {
        EvalDeltaCalculator calculator = new EvalDeltaCalculator();

        assertEquals(1000, calculator.iterations());
        assertEquals(41L, calculator.seed());
        assertEquals(1000, EvalDeltaCalculator.DEFAULT_ITERATIONS);
        assertEquals(41L, EvalDeltaCalculator.DEFAULT_SEED);
        assertEquals(20, EvalDeltaCalculator.MIN_PAIRS);
    }

    @Test
    public void sameDataShouldProduceIdenticalEstimate() {
        double[] diffs = variedDiffs();
        EvalDeltaCalculator first = new EvalDeltaCalculator();
        EvalDeltaCalculator second = new EvalDeltaCalculator(1000, 41L);

        EvalDeltaCalculator.Estimate a = first.estimate(diffs);
        EvalDeltaCalculator.Estimate b = second.estimate(diffs);
        EvalDeltaCalculator.Estimate c = first.estimate(diffs);

        assertEquals(a.delta(), b.delta(), 0.0d);
        assertEquals(a.ciLow(), b.ciLow(), 0.0d);
        assertEquals(a.ciHigh(), b.ciHigh(), 0.0d);
        assertEquals(a.delta(), c.delta(), 0.0d);
        assertEquals(a.ciLow(), c.ciLow(), 0.0d);
        assertEquals(a.ciHigh(), c.ciHigh(), 0.0d);
        assertEquals(40, a.pairs());
        assertEquals(1000, a.iterations());
        assertEquals(41L, a.seed());
    }

    @Test
    public void estimateShouldKeepInjectedParameters() {
        EvalDeltaCalculator.Estimate estimate =
                new EvalDeltaCalculator(50, 7L).estimate(new double[]{1.0d, 2.0d, 3.0d});

        assertEquals(50, estimate.iterations());
        assertEquals(7L, estimate.seed());
        assertEquals(3, estimate.pairs());
        assertEquals(2.0d, estimate.delta(), 1.0e-12d);
    }

    @Test
    public void minimumStepShouldBeHundredOverN() {
        assertEquals(5.0d, EvalDeltaCalculator.minimumStep(20), 1.0e-12d);
        assertEquals(2.5d, EvalDeltaCalculator.minimumStep(40), 1.0e-12d);
        try {
            EvalDeltaCalculator.minimumStep(0);
            fail("n 为 0 必须拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("配对题数"));
        }
    }

    @Test
    public void significanceShouldRequireMinimumStep() {
        double[] justBelow = new double[20];
        double[] justOn = new double[20];
        for (int i = 0; i < 20; i++) {
            justBelow[i] = 4.9d;
            justOn[i] = 5.0d;
        }

        // 区间都排除 0，但 4.9 小于最小步长 5，不能标显著
        assertFalse(new EvalDeltaCalculator().estimate(justBelow).significant());
        assertTrue(new EvalDeltaCalculator().estimate(justOn).significant());
    }

    @Test
    public void negativeDirectionShouldAlsoBeSignificant() {
        double[] negative = new double[20];
        for (int i = 0; i < 20; i++) {
            negative[i] = -5.0d;
        }

        EvalDeltaCalculator.Estimate estimate = new EvalDeltaCalculator().estimate(negative);

        assertTrue(estimate.ciHigh() < 0.0d);
        assertTrue(estimate.significant());
    }

    @Test
    public void quantileShouldInterpolateLinearly() {
        double[] sorted = {0.0d, 10.0d};

        assertEquals(0.0d, EvalDeltaCalculator.quantile(sorted, 0.0d), 1.0e-12d);
        assertEquals(2.5d, EvalDeltaCalculator.quantile(sorted, 0.25d), 1.0e-12d);
        assertEquals(10.0d, EvalDeltaCalculator.quantile(sorted, 1.0d), 1.0e-12d);
        assertEquals(7.0d, EvalDeltaCalculator.quantile(new double[]{7.0d}, 0.5d), 1.0e-12d);
    }

    @Test
    public void invalidInputShouldBeRejected() {
        EvalDeltaCalculator calculator = new EvalDeltaCalculator();
        try {
            calculator.estimate(null);
            fail("null 必须拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("不能为空"));
        }
        try {
            calculator.estimate(new double[0]);
            fail("空数组必须拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("不能为空"));
        }
        try {
            calculator.estimate(new double[]{1.0d, Double.NaN});
            fail("NaN 必须拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("有限数值"));
        }
        try {
            new EvalDeltaCalculator(0, 41L);
            fail("次数为 0 必须拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("次数"));
        }
    }

    private static double[] variedDiffs() {
        double[] diffs = new double[40];
        for (int i = 0; i < diffs.length; i++) {
            diffs[i] = 20.0d + (i % 4);
        }
        return diffs;
    }
}
