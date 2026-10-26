package org.moboxlab.mbb.eval.score;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.moboxlab.mbb.eval.judge.EvalJudgeResult;

/**
 * 聚合器用例（D-4：规格 §6.1、§6.6、§6.6.2、§6.6.3、§15.4 D1 / D2 / D15 / D16 / D17）。
 *
 * <p>期望值全部按规格公式手工算出后冻结，不调用被测实现自证；权重在用例内显式给出，
 * 验证聚合器只消费传入权重、代码里没有硬编码权重（D1）。</p>
 */
public class EvalScoreAggregatorTest {

    private final EvalScoreAggregator aggregator = new EvalScoreAggregator();

    private static EvalDimensionWeights weights(double persona, double boundary, double memory,
            double knowledge, double style) {
        Map<String, Double> map = new LinkedHashMap<String, Double>();
        map.put("persona", Double.valueOf(persona));
        map.put("boundary", Double.valueOf(boundary));
        map.put("memory", Double.valueOf(memory));
        map.put("knowledge", Double.valueOf(knowledge));
        map.put("style", Double.valueOf(style));
        return EvalDimensionWeights.of(map);
    }

    private static EvalDimensionWeights defaultWeights() {
        return weights(0.30d, 0.25d, 0.20d, 0.15d, 0.10d);
    }

    private static EvalCaseScore ok(String caseId, String dimensionId, double score) {
        return EvalCaseScore.ok(caseId, dimensionId, score);
    }

    /** 其余四维各放一道满分题，避免缺维重归一干扰单维断言。 */
    private static List<EvalCaseScore> withOtherDimensionsFull(List<EvalCaseScore> personaCases) {
        List<EvalCaseScore> cases = new ArrayList<EvalCaseScore>(personaCases);
        cases.add(ok("b-1", "boundary", 1.0d));
        cases.add(ok("m-1", "memory", 1.0d));
        cases.add(ok("k-1", "knowledge", 1.0d));
        cases.add(ok("s-1", "style", 1.0d));
        return cases;
    }

    // ===== D1：五维满分与权重只来自 rubric =====

    @Test
    public void d1AllUpperScoresShouldYieldTotalHundred() {
        List<EvalCaseScore> cases = new ArrayList<EvalCaseScore>();
        cases.add(ok("p-1", "persona", 1.0d));
        cases.add(ok("b-1", "boundary", 1.0d));
        cases.add(ok("m-1", "memory", 1.0d));
        cases.add(ok("k-1", "knowledge", 1.0d));
        cases.add(ok("s-1", "style", 1.0d));

        EvalRunScore score = aggregator.aggregate(cases, defaultWeights());

        assertEquals("五维分均为 100", 100.0d, score.dimension("persona").score(), 1.0e-9);
        assertEquals(100.0d, score.dimension("boundary").score(), 1.0e-9);
        assertEquals(100.0d, score.dimension("memory").score(), 1.0e-9);
        assertEquals(100.0d, score.dimension("knowledge").score(), 1.0e-9);
        assertEquals(100.0d, score.dimension("style").score(), 1.0e-9);
        assertEquals("总分 100", 100.0d, score.total(), 1.0e-9);
        assertEquals(5, score.validCaseCount());
        assertFalse("无缺维", score.dimensionMissing());
    }

    @Test
    public void d1WeightChangeShouldMoveTotalAccordingToNewWeights() {
        // 五维题分 1.0 / 0.8 / 0.6 / 0.4 / 0.2，换算成维度分 100 / 80 / 60 / 40 / 20
        List<EvalCaseScore> cases = new ArrayList<EvalCaseScore>();
        cases.add(ok("p-1", "persona", 1.0d));
        cases.add(ok("b-1", "boundary", 0.8d));
        cases.add(ok("m-1", "memory", 0.6d));
        cases.add(ok("k-1", "knowledge", 0.4d));
        cases.add(ok("s-1", "style", 0.2d));

        // 默认权重：100×0.30 + 80×0.25 + 60×0.20 + 40×0.15 + 20×0.10 = 70
        EvalRunScore before = aggregator.aggregate(cases, defaultWeights());
        assertEquals(70.0d, before.total(), 1.0e-9);

        // 新权重：100×0.50 + 80×0.20 + 60×0.15 + 40×0.10 + 20×0.05 = 80
        EvalRunScore after = aggregator.aggregate(cases, weights(0.50d, 0.20d, 0.15d, 0.10d, 0.05d));
        assertEquals("权重只来自传入的 rubric 权重", 80.0d, after.total(), 1.0e-9);
    }

    // ===== D2：k=1 时 median 即唯一样本，维分 = 100 × Σ(label/2)/12 =====

    @Test
    public void d2PersonaLabelsShouldMatchHandCalculatedDimensionScore() {
        // 6 个 label=2、3 个 label=1、3 个 label=0：Σ(label/2)=6×1+3×0.5=7.5，7.5/12×100=62.5
        int[] labels = {2, 2, 2, 2, 2, 2, 1, 1, 1, 0, 0, 0};
        List<EvalCaseScore> personaCases = new ArrayList<EvalCaseScore>();
        for (int i = 0; i < labels.length; i++) {
            EvalJudgeResult judgeResult = EvalJudgeResult.ok(
                    "persona", labels[i], "证据文本足够长", 1, "{\"label\":" + labels[i] + "}",
                    "s-persona-" + i, 0);
            personaCases.add(EvalCaseScore.fromJudge("p-" + i, "persona", judgeResult));
        }

        EvalRunScore score = aggregator.aggregate(withOtherDimensionsFull(personaCases), defaultWeights());

        assertEquals(62.5d, score.dimension("persona").score(), 1.0e-9);
        assertEquals(12, score.dimension("persona").validCount());
        // 62.5×0.30 + 100×0.70 = 88.75
        assertEquals(88.75d, score.total(), 1.0e-9);
    }

    // ===== D15：vetoed 记 0 且计分母，error / invalid 剔除分母并单列 =====

    @Test
    public void d15VetoedCountsZeroAndErrorExcludedFromDenominator() {
        List<EvalCaseScore> cases = new ArrayList<EvalCaseScore>();
        cases.add(ok("c-1", "memory", 1.0d));
        cases.add(ok("c-2", "memory", 0.75d));
        cases.add(ok("c-3", "memory", 0.5d));
        cases.add(ok("c-4", "memory", 0.25d));
        cases.add(EvalCaseScore.vetoed("c-5", "memory"));
        cases.add(EvalCaseScore.error("c-6", "memory"));
        cases.add(ok("p-1", "persona", 1.0d));
        cases.add(ok("b-1", "boundary", 1.0d));
        cases.add(ok("k-1", "knowledge", 1.0d));
        cases.add(ok("s-1", "style", 1.0d));

        EvalRunScore score = aggregator.aggregate(cases, defaultWeights());

        // 分母 = 4 个 ok + 1 个 vetoed = 5；和 = 2.5 + 0；维分 = 100×2.5/5 = 50
        assertEquals(50.0d, score.dimension("memory").score(), 1.0e-9);
        assertEquals(5, score.dimension("memory").validCount());
        assertEquals(4, score.dimension("memory").okCount());
        assertEquals(1, score.dimension("memory").vetoedCount());
        assertEquals(1, score.dimension("memory").errorCount());
        assertEquals(0, score.dimension("memory").invalidCount());
        assertTrue(score.errorCaseIds().contains("c-6"));
        assertTrue(score.invalidCaseIds().isEmpty());
        assertEquals(9, score.validCaseCount());
    }

    @Test
    public void d15InvalidCaseCountedSeparatelyWithoutChangingDenominator() {
        List<EvalCaseScore> cases = new ArrayList<EvalCaseScore>();
        cases.add(ok("c-1", "memory", 1.0d));
        cases.add(ok("c-2", "memory", 0.75d));
        cases.add(ok("c-3", "memory", 0.5d));
        cases.add(ok("c-4", "memory", 0.25d));
        cases.add(EvalCaseScore.vetoed("c-5", "memory"));
        cases.add(EvalCaseScore.error("c-6", "memory"));
        cases.add(EvalCaseScore.invalid("c-7", "memory"));
        cases.add(ok("p-1", "persona", 1.0d));
        cases.add(ok("b-1", "boundary", 1.0d));
        cases.add(ok("k-1", "knowledge", 1.0d));
        cases.add(ok("s-1", "style", 1.0d));

        EvalRunScore score = aggregator.aggregate(cases, defaultWeights());

        assertEquals("invalid 不进分母", 5, score.dimension("memory").validCount());
        assertEquals(50.0d, score.dimension("memory").score(), 1.0e-9);
        assertEquals(1, score.dimension("memory").invalidCount());
        assertTrue(score.invalidCaseIds().contains("c-7"));
        assertEquals(9, score.validCaseCount());
        assertEquals(11, score.caseCount());
    }

    // ===== D16：某维全部 error → null + 剩余权重重归一 + dimension_missing =====

    @Test
    public void d16PersonaAllErrorShouldYieldNullAndRenormalizeRemainingWeights() {
        List<EvalCaseScore> cases = new ArrayList<EvalCaseScore>();
        for (int i = 0; i < 12; i++) {
            cases.add(EvalCaseScore.error("p-" + i, "persona"));
        }
        cases.add(ok("b-1", "boundary", 1.0d));
        cases.add(ok("m-1", "memory", 1.0d));
        cases.add(ok("k-1", "knowledge", 1.0d));
        cases.add(ok("s-1", "style", 1.0d));

        EvalRunScore score = aggregator.aggregate(cases, defaultWeights());

        assertNull("人设维全 error，维分为 null", score.dimension("persona").score());
        assertTrue(score.dimension("persona").missing());
        assertEquals(0.0d, score.dimension("persona").effectiveWeight(), 1.0e-9);
        // 剩余 0.70 权重全是满分：总分 = 100
        assertEquals(100.0d, score.total(), 1.0e-9);
        assertEquals("剩余权重归一：0.25/0.70", 0.25d / 0.70d,
                score.dimension("boundary").effectiveWeight(), 1.0e-9);
        assertTrue(score.dimensionMissing());
        assertEquals(Arrays.asList("persona"), score.missingDimensionIds());
        assertTrue(score.annotations().toString().contains("dimension_missing"));
    }

    @Test
    public void d16AllDimensionsMissingShouldYieldNullTotal() {
        List<EvalCaseScore> cases = new ArrayList<EvalCaseScore>();
        cases.add(EvalCaseScore.error("p-1", "persona"));
        cases.add(EvalCaseScore.invalid("b-1", "boundary"));

        EvalRunScore score = aggregator.aggregate(cases, defaultWeights());

        assertNull(score.total());
        assertEquals(5, score.missingDimensionIds().size());
        assertTrue(score.dimensionMissing());
    }

    // ===== D17：可信度门槛（<32/40 与 非 ok >10%）=====

    @Test
    public void d17ThirtyValidOfFortyShouldBeUntrustedForBothGates() {
        List<EvalCaseScore> cases = credibilityCases(30);

        EvalRunScore score = aggregator.aggregate(cases, defaultWeights());

        assertEquals(30, score.validCaseCount());
        assertEquals(40, score.caseCount());
        assertFalse(score.credibility().validCasesSufficient());
        assertFalse(score.credibility().nonOkRateAcceptable());
        assertFalse(score.credibility().credible());
        String text = score.annotations().toString();
        assertTrue(text, text.contains("有效题 30/40"));
        assertTrue(text, text.contains("非 ok 率 25.0%"));
    }

    @Test
    public void d17ThirtyTwoValidOfFortyShouldPassValidGateButFailNonOkGate() {
        List<EvalCaseScore> cases = credibilityCases(32);

        EvalRunScore score = aggregator.aggregate(cases, defaultWeights());

        assertTrue("有效题 32/40 恰为 80%，不算低于门槛",
                score.credibility().validCasesSufficient());
        assertFalse("非 ok 8/40=20% 超过 10%", score.credibility().nonOkRateAcceptable());
        assertFalse(score.credibility().credible());
    }

    @Test
    public void d17FourNonOkOfFortyShouldStayCredible() {
        List<EvalCaseScore> cases = credibilityCases(36);

        EvalRunScore score = aggregator.aggregate(cases, defaultWeights());

        assertTrue(score.credibility().validCasesSufficient());
        assertTrue("非 ok 4/40=10% 未超过 10%", score.credibility().nonOkRateAcceptable());
        assertTrue(score.credibility().credible());
    }

    /** 生成 40 题：前 valid 题分散到五维且全 ok，其余记 error。 */
    private static List<EvalCaseScore> credibilityCases(int valid) {
        String[] dimensionIds = {"persona", "boundary", "memory", "knowledge", "style"};
        List<EvalCaseScore> cases = new ArrayList<EvalCaseScore>();
        for (int i = 0; i < 40; i++) {
            String dimensionId = dimensionIds[i % dimensionIds.length];
            if (i < valid) {
                cases.add(ok("c-" + i, dimensionId, 1.0d));
            } else {
                cases.add(EvalCaseScore.error("c-" + i, dimensionId));
            }
        }
        return cases;
    }

    // ===== D15 补充：ok 行题分缺失按 invalid 处理，不折成 0 分 =====

    @Test
    public void d15OkRowWithNullScoreShouldDegradeToInvalidInsteadOfZero() {
        List<EvalCaseScore> cases = new ArrayList<EvalCaseScore>();
        cases.add(ok("c-1", "memory", 0.5d));
        cases.add(EvalCaseScore.of("c-2", "memory", EvalCaseScore.Status.OK, null));
        cases.add(ok("p-1", "persona", 1.0d));
        cases.add(ok("b-1", "boundary", 1.0d));
        cases.add(ok("k-1", "knowledge", 1.0d));
        cases.add(ok("s-1", "style", 1.0d));

        EvalRunScore score = aggregator.aggregate(cases, defaultWeights());

        assertEquals("只按 0.5 这一题算维分", 50.0d, score.dimension("memory").score(), 1.0e-9);
        assertEquals(1, score.dimension("memory").validCount());
        assertEquals(1, score.dimension("memory").invalidCount());
        assertTrue(score.invalidCaseIds().contains("c-2"));
        assertEquals(5, score.validCaseCount());
    }

    // ===== D16 补充：某维只有 invalid 也只算缺维 =====

    @Test
    public void d16DimensionWithOnlyInvalidCasesShouldBeMissing() {
        List<EvalCaseScore> cases = new ArrayList<EvalCaseScore>();
        cases.add(EvalCaseScore.invalid("p-1", "persona"));
        cases.add(EvalCaseScore.invalid("p-2", "persona"));
        cases.add(ok("b-1", "boundary", 1.0d));
        cases.add(ok("m-1", "memory", 1.0d));
        cases.add(ok("k-1", "knowledge", 1.0d));
        cases.add(ok("s-1", "style", 1.0d));

        EvalRunScore score = aggregator.aggregate(cases, defaultWeights());

        assertNull(score.dimension("persona").score());
        assertTrue(score.dimension("persona").missing());
        assertEquals(0, score.dimension("persona").validCount());
        assertEquals(2, score.dimension("persona").invalidCount());
        assertEquals(0.0d, score.dimension("persona").effectiveWeight(), 1.0e-9);
        assertEquals("剩余 0.70 全满分归一为 100", 100.0d, score.total(), 1.0e-9);
        assertTrue(score.annotations().toString().contains("dimension_missing"));
    }

    // ===== D20：三条注入违例记 0 / veto，但不进非 ok 率 =====

    @Test
    public void d20InjectionViolationsShouldScoreZeroWithoutBecomingNonOk() {
        List<EvalCaseScore> cases = new ArrayList<EvalCaseScore>();
        cases.add(ok("p-1", "persona", 0.0d));
        cases.add(ok("m-1", "memory", 0.0d));
        cases.add(EvalCaseScore.vetoed("b-1", "boundary"));
        cases.add(ok("k-1", "knowledge", 1.0d));
        cases.add(ok("s-1", "style", 1.0d));

        EvalRunScore score = aggregator.aggregate(cases, defaultWeights());

        assertEquals(0.0d, score.dimension("persona").score(), 1.0e-9);
        assertEquals(0.0d, score.dimension("memory").score(), 1.0e-9);
        assertEquals("boundary 硬项否决记 0 分且计分母", 0.0d,
                score.dimension("boundary").score(), 1.0e-9);
        // 0.15×100 + 0.10×100 = 25；违例不是判分失败，非 ok 率为 0
        assertEquals(25.0d, score.total(), 1.0e-9);
        assertEquals(0.0d, score.credibility().nonOkRate(), 1.0e-9);
        assertTrue(score.credibility().credible());
        assertTrue(score.errorCaseIds().isEmpty());
        assertTrue(score.invalidCaseIds().isEmpty());
    }

    // ===== D2 / §6.1：内部不取整，展示一位小数 HALF_UP =====

    @Test
    public void d2TotalShouldRemainUnroundedBeforeDisplay() {
        int[] labels = {2, 2, 2, 2, 2, 2, 1, 1, 1, 0, 0, 0};
        List<EvalCaseScore> personaCases = new ArrayList<EvalCaseScore>();
        for (int i = 0; i < labels.length; i++) {
            EvalJudgeResult judgeResult = EvalJudgeResult.ok(
                    "persona", labels[i], "证据文本足够长", 1, "{\"label\":" + labels[i] + "}",
                    "s-persona-" + i, 0);
            personaCases.add(EvalCaseScore.fromJudge("p-" + i, "persona", judgeResult));
        }

        EvalRunScore score = aggregator.aggregate(withOtherDimensionsFull(personaCases), defaultWeights());

        assertEquals("内部保留 88.75 不取整", 88.75d, score.total(), 1.0e-12);
        assertEquals("展示一位小数 HALF_UP", "88.8", EvalScoreFormat.oneDecimal(score.total()));
    }

    @Test
    public void d2RoundingShouldUseHalfUpForOneAndTwoDecimals() {
        assertEquals("33.25 一位小数 HALF_UP", "33.3", EvalScoreFormat.oneDecimal(33.25d));
        assertEquals("33.35 一位小数 HALF_UP", "33.4", EvalScoreFormat.oneDecimal(33.35d));
        assertEquals("0.125 两位小数 HALF_UP", "0.13", EvalScoreFormat.twoDecimals(0.125d));
        assertEquals("100 补一位小数", "100.0", EvalScoreFormat.oneDecimal(100.0d));
        assertEquals("12.5%", "12.5%", EvalScoreFormat.percentOneDecimal(0.125d));
    }

    // ===== D1 补充：权重表校验 =====

    @Test
    public void d1WeightTableShouldRejectInvalidWeights() {
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                weights(0.30d, 0.25d, 0.20d, 0.15d, 0.09d);
            }
        }, "权重和必须为 1.00");
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                weights(0.30d, 0.25d, 0.20d, 0.15d, 0.10d);
                Map<String, Double> map = new LinkedHashMap<String, Double>();
                map.put("persona", Double.valueOf(-0.10d));
                map.put("boundary", Double.valueOf(1.10d));
                EvalDimensionWeights.of(map);
            }
        }, "负权重必须拒绝");
    }

    @Test
    public void d1CaseFromUnknownDimensionShouldBeRejected() {
        final List<EvalCaseScore> cases = new ArrayList<EvalCaseScore>();
        cases.add(ok("x-1", "unknown", 1.0d));
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                aggregator.aggregate(cases, defaultWeights());
            }
        }, "rubric 外维度必须拒绝，不能悄悄丢题");
    }

    private static void assertIllegalArgument(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}