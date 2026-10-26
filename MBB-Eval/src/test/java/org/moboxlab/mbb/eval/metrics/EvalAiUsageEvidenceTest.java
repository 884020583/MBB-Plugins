package org.moboxlab.mbb.eval.metrics;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * L3 只读旁证服务用例（E-4：规格 §7.5、§15.5 E11、§16.6 E-4）。
 *
 * <p>数据源可注入，用例全部使用内存假源，不连库、不写库。断言集中在 E11 的四点：
 * 只取指定 profile×日期、不参与指标、固定标注「不同口径，不可换算」、缺失时明确「不可得」。</p>
 */
public class EvalAiUsageEvidenceTest {

    @Test
    public void shouldQueryOnlyRequestedProfileAndDateRange() {
        FakeSource source = new FakeSource(Arrays.asList(
                day("2026-10-01", "eval", 60L, 58L, 2L, 600L, 300L, 900L, 12000L, 1L),
                day("2026-10-01", "eval", 40L, 38L, 2L, 400L, 200L, 600L, 13000L, 2L),
                day("2026-10-02", "eval", 5L, 5L, 0L, 50L, 25L, 75L, 500L, 0L),
                day("2026-10-01", "judge", 99L, 99L, 0L, 990L, 0L, 990L, 1000L, 0L),
                day("2026-09-30", "eval", 7L, 7L, 0L, 70L, 35L, 105L, 700L, 0L)));
        EvalAiUsageEvidenceService service = new EvalAiUsageEvidenceService(source);

        EvalAiUsageEvidence evidence = service.collect("eval", "2026-10-01", "2026-10-02");

        assertEquals("eval", source.lastProfile);
        assertEquals("2026-10-01", source.lastStartDate);
        assertEquals("2026-10-02", source.lastEndDate);
        assertEquals(1, source.calls);

        assertTrue(evidence.available());
        assertEquals(2, evidence.rows().size());
        EvalAiUsageDay first = evidence.rows().get(0);
        assertEquals("2026-10-01", first.statDate());
        assertEquals("eval", first.profile());
        assertEquals(100L, first.requests());
        assertEquals(96L, first.successes());
        assertEquals(4L, first.failures());
        assertEquals(1000L, first.promptTokens());
        assertEquals(500L, first.completionTokens());
        assertEquals(1500L, first.totalTokens());
        assertEquals(25000L, first.totalLatencyMs());
        assertEquals(3L, first.cachedHits());
        assertEquals(250.0D, first.averageLatencyMs().doubleValue(), 0.0001D);

        EvalAiUsageDay second = evidence.rows().get(1);
        assertEquals("2026-10-02", second.statDate());
        assertEquals(5L, second.requests());
    }

    @Test
    public void shouldAlwaysCarryFixedCaliberNote() {
        EvalAiUsageEvidenceService filled = new EvalAiUsageEvidenceService(new FakeSource(
                Collections.singletonList(day("2026-10-01", "eval", 1L, 1L, 0L, 1L, 1L, 2L, 10L, 0L))));
        EvalAiUsageEvidenceService empty = new EvalAiUsageEvidenceService(
                new FakeSource(Collections.<EvalAiUsageDay>emptyList()));

        assertEquals("不同口径，不可换算", EvalAiUsageEvidence.CALIBER_NOTE);
        assertTrue(filled.collect("eval", "2026-10-01").text().contains("不同口径，不可换算"));
        assertTrue(empty.collect("eval", "2026-10-01").text().contains("不同口径，不可换算"));
    }

    @Test
    public void shouldMarkMissingUsageAsUnavailable() {
        EvalAiUsageEvidenceService service = new EvalAiUsageEvidenceService(
                new FakeSource(Collections.<EvalAiUsageDay>emptyList()));

        EvalAiUsageEvidence evidence = service.collect("judge", "2026-10-03");

        assertFalse(evidence.available());
        assertTrue(evidence.rows().isEmpty());
        assertNotNull(evidence.missingReason());
        String text = evidence.text();
        assertTrue(text.contains("不可得"));
        assertTrue(text.contains("judge"));
        assertTrue(text.contains("2026-10-03"));
        assertTrue(text.contains(EvalAiUsageEvidence.CALIBER_NOTE));
    }

    @Test
    public void shouldNotProduceMetricsOrPercentiles() {
        EvalAiUsageEvidenceService service = new EvalAiUsageEvidenceService(new FakeSource(
                Collections.singletonList(day("2026-10-01", "eval", 10L, 9L, 1L, 100L, 50L, 150L, 2000L, 0L))));

        String text = service.collect("eval", "2026-10-01").text();

        String[] forbidden = {"p50", "p90", "p95", "分位", "总分", "得分", "成功率"};
        for (String token : forbidden) {
            assertFalse("L3 旁证不得输出 " + token, text.contains(token));
        }
        for (Method method : EvalAiUsageEvidence.class.getMethods()) {
            assertFalse("L3 旁证不得暴露指标快照：" + method.getName(),
                    EvalMetrics.class.equals(method.getReturnType()));
        }
    }

    @Test
    public void shouldTreatSourceFailureAsUnavailable() {
        EvalAiUsageSource broken = new EvalAiUsageSource() {
            @Override
            public List<EvalAiUsageDay> queryDailyUsage(String profile, String startDate, String endDate) {
                throw new IllegalStateException("统计表不存在");
            }
        };
        EvalAiUsageEvidenceService service = new EvalAiUsageEvidenceService(broken);

        EvalAiUsageEvidence evidence = service.collect("eval", "2026-10-01");

        assertFalse(evidence.available());
        assertTrue(evidence.text().contains("不可得"));
        assertTrue(evidence.text().contains(EvalAiUsageEvidence.CALIBER_NOTE));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectBlankProfile() {
        new EvalAiUsageEvidenceService(new FakeSource(Collections.<EvalAiUsageDay>emptyList()))
                .collect("  ", "2026-10-01");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectMalformedDate() {
        new EvalAiUsageEvidenceService(new FakeSource(Collections.<EvalAiUsageDay>emptyList()))
                .collect("eval", "2026-13-01");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectReversedDateRange() {
        new EvalAiUsageEvidenceService(new FakeSource(Collections.<EvalAiUsageDay>emptyList()))
                .collect("eval", "2026-10-02", "2026-10-01");
    }

    private static EvalAiUsageDay day(String statDate, String profile, long requests, long successes,
            long failures, long promptTokens, long completionTokens, long totalTokens,
            long totalLatencyMs, long cachedHits) {
        return new EvalAiUsageDay(statDate, profile, requests, successes, failures,
                promptTokens, completionTokens, totalTokens, totalLatencyMs, cachedHits);
    }

    /** 内存假数据源：记录入参并原样返回固定行集，便于断言服务侧的过滤与聚合。 */
    private static final class FakeSource implements EvalAiUsageSource {
        private final List<EvalAiUsageDay> rows;
        private String lastProfile;
        private String lastStartDate;
        private String lastEndDate;
        private int calls;

        private FakeSource(List<EvalAiUsageDay> rows) {
            this.rows = new ArrayList<EvalAiUsageDay>(rows);
        }

        @Override
        public List<EvalAiUsageDay> queryDailyUsage(String profile, String startDate, String endDate) {
            this.lastProfile = profile;
            this.lastStartDate = startDate;
            this.lastEndDate = endDate;
            this.calls++;
            return new ArrayList<EvalAiUsageDay>(rows);
        }
    }
}
