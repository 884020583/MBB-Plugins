package org.moboxlab.mbb.eval.score;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * 可信度两条门槛用例（规格 §6.6.3、§8.5、§15.5 H11）。
 *
 * <p>有效题占比与非 ok 占比各自独立判定：恰好 80% 与恰好 10% 均不触发；
 * 两条门槛同时不满足时两条标注都要出现，不得互相替代；rubric 变化只增加可比性标注。</p>
 */
public class EvalCredibilityGateTest {

    @Test
    public void onlyValidRatioBelowThresholdIsAnnotated() {
        EvalCredibility credibility = EvalCredibilityGate.judge(30, 40, 0, 0, false);

        assertFalse(credibility.validCasesSufficient());
        assertTrue("非 ok 率为 0，第二条门槛应通过", credibility.nonOkRateAcceptable());
        assertFalse(credibility.credible());
        assertEquals(1, credibility.annotations().size());
        assertEquals("不可信：有效题 30/40（低于 80%）", credibility.annotations().get(0));
    }

    @Test
    public void onlyNonOkRateAboveThresholdIsAnnotated() {
        EvalCredibility credibility = EvalCredibilityGate.judge(33, 40, 5, 2, false);

        assertTrue("有效题 33/40 达标", credibility.validCasesSufficient());
        assertFalse(credibility.nonOkRateAcceptable());
        assertFalse(credibility.credible());
        assertEquals(0.175d, credibility.nonOkRate(), 1.0e-12d);
        assertEquals(1, credibility.annotations().size());
        assertEquals("判分不可信：非 ok 率 17.5% 超过 10%", credibility.annotations().get(0));
    }

    @Test
    public void bothThresholdsAreAnnotatedIndependently() {
        EvalCredibility credibility = EvalCredibilityGate.judge(30, 40, 4, 6, false);

        assertFalse(credibility.validCasesSufficient());
        assertFalse(credibility.nonOkRateAcceptable());
        assertFalse(credibility.credible());
        assertEquals("两条门槛各自成条，不得互相替代", 2, credibility.annotations().size());
        assertEquals("不可信：有效题 30/40（低于 80%）", credibility.annotations().get(0));
        assertEquals("判分不可信：非 ok 率 25.0% 超过 10%", credibility.annotations().get(1));
    }

    @Test
    public void exactBoundariesDoNotTrigger() {
        EvalCredibility exactValidRatio = EvalCredibilityGate.judge(32, 40, 0, 0, false);
        assertTrue("恰好 80% 不触发有效题门槛", exactValidRatio.validCasesSufficient());
        assertTrue(exactValidRatio.nonOkRateAcceptable());
        assertTrue(exactValidRatio.credible());
        assertTrue(exactValidRatio.annotations().isEmpty());

        EvalCredibility exactNonOkRate = EvalCredibilityGate.judge(36, 40, 2, 2, false);
        assertEquals(0.1d, exactNonOkRate.nonOkRate(), 1.0e-12d);
        assertTrue("恰好 10% 不触发非 ok 门槛", exactNonOkRate.nonOkRateAcceptable());
        assertTrue(exactNonOkRate.validCasesSufficient());
        assertTrue(exactNonOkRate.credible());
        assertTrue(exactNonOkRate.annotations().isEmpty());
    }

    @Test
    public void rubricChangedOnlyAddsComparabilityNote() {
        EvalCredibility credibility = EvalCredibilityGate.judge(36, 40, 0, 0, true);

        assertTrue(credibility.credible());
        assertEquals(1, credibility.annotations().size());
        assertEquals("rubric 已变，不与历史直接比较", credibility.annotations().get(0));
    }

    @Test
    public void zeroCasesFailValidRatioButNotNonOkRate() {
        EvalCredibility credibility = EvalCredibilityGate.judge(0, 0, 0, 0, false);

        assertFalse(credibility.validCasesSufficient());
        assertTrue(credibility.nonOkRateAcceptable());
        assertEquals(0.0d, credibility.nonOkRate(), 0.0d);
        assertEquals(1, credibility.annotations().size());
    }

    @Test
    public void rejectsIllegalCounts() {
        assertThrows(IllegalArgumentException.class, () -> EvalCredibilityGate.judge(-1, 40, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> EvalCredibilityGate.judge(30, -1, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> EvalCredibilityGate.judge(41, 40, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> EvalCredibilityGate.judge(30, 40, 0, -1, false));
        assertThrows(IllegalArgumentException.class, () -> EvalCredibilityGate.judge(30, 40, 6, 5, false));
        assertThrows(IllegalArgumentException.class, () -> EvalCredibilityGate.judge(30, 40, 0, 11, false));
    }

    @Test
    public void matchesAggregatorBoundaries() {
        EvalDimensionWeights weights = weights();
        EvalScoreAggregator aggregator = new EvalScoreAggregator();

        assertMatches(aggregator, weights, 32, 0, 0);
        assertMatches(aggregator, weights, 36, 2, 2);
        assertMatches(aggregator, weights, 30, 5, 5);
        assertMatches(aggregator, weights, 30, 4, 6);
        assertMatches(aggregator, weights, 0, 0, 0);
    }

    // ---------- 辅助 ----------

    private static void assertMatches(EvalScoreAggregator aggregator, EvalDimensionWeights weights,
            int okCount, int errorCount, int invalidCount) {
        List<EvalCaseScore> rows = new ArrayList<EvalCaseScore>();
        for (int i = 0; i < okCount; i++) {
            rows.add(EvalCaseScore.ok("c" + rows.size(), "persona", 0.8d));
        }
        for (int i = 0; i < errorCount; i++) {
            rows.add(EvalCaseScore.error("c" + rows.size(), "persona"));
        }
        for (int i = 0; i < invalidCount; i++) {
            rows.add(EvalCaseScore.invalid("c" + rows.size(), "persona"));
        }
        EvalCredibility expected = aggregator.aggregate(rows, weights).credibility();
        EvalCredibility actual = EvalCredibilityGate.judge(okCount, rows.size(),
                errorCount, invalidCount, false);

        assertEquals(expected.validCasesSufficient(), actual.validCasesSufficient());
        assertEquals(expected.nonOkRateAcceptable(), actual.nonOkRateAcceptable());
        assertEquals(expected.credible(), actual.credible());
        assertEquals(expected.nonOkRate(), actual.nonOkRate(), 1.0e-12d);
        assertEquals(expected.annotations(), actual.annotations());
    }

    private static EvalDimensionWeights weights() {
        Map<String, Double> weights = new HashMap<String, Double>();
        weights.put("persona", Double.valueOf(0.30d));
        weights.put("boundary", Double.valueOf(0.25d));
        weights.put("memory", Double.valueOf(0.20d));
        weights.put("knowledge", Double.valueOf(0.15d));
        weights.put("style", Double.valueOf(0.10d));
        return EvalDimensionWeights.of(weights);
    }
}
