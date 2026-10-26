package org.moboxlab.mbb.eval.report;

import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** E-3 性能节渲染测试（§7.2–§7.6、§8.3 第 7/8 节、§8.5、E1/E11/E12/E14、§15.9 I2）。 */
public class EvalReportPerformanceRendererTest {

    private static final String RUN = "r-20261025230000-full-p3rf";
    private static final String HIGH_LATENCY_RUN = "r-20261025230000-full-p3rg";
    private static final String CACHED_RUN = "r-20261025230000-full-p3rc";
    private static final String EXHAUSTED_RUN = "r-20261025230000-full-p3rx";
    private static final String SECRET_SNAPSHOT = "{\"apiKey\":\"sk-secret-9f2\","
            + "\"nickname\":\"小明\",\"prompt\":\"完整提示词：你是莫莫\"}";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;

    @Before
    public void setUp() throws IOException {
        storage = new EvalStorageFake(new File(folder.getRoot(), "eval-perf.db"));
        new EvalSchemaService(storage, null).ensureSchema();
        store = new EvalStore(storage, null);
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    public void shouldRenderFixedDecimalPerformanceSection() {
        seedRun(RUN, 5, 720000L);
        for (int i = 0; i < 5; i++) {
            seedCall(RUN, "case-" + (i + 1), "gen", "ok", 0, 0,
                    Integer.valueOf(50), Long.valueOf(3200L + i * 400L));
        }
        EvalReportData data = EvalReportData.assemble(store, RUN, TestReportWeights.map(), null, null);
        String text = EvalReportPerformanceRenderer.render(data);

        assertTrue(text.startsWith("## 7. 性能\n"));
        assertTrue(text.contains("- 生成延迟：mean 4.0 秒；p50/p90/max：样本不足（n=5，需 ≥20）"));
        assertTrue(text.contains("- p95：样本不足（n=5，需 ≥60）"));
        assertTrue(text.contains("- 延迟排除：error 0；cached 0；缺耗时 0"));
        assertTrue(text.contains("- 输出吞吐：mean 12.8 token/s；p50：样本不足（n=5，需 ≥20）"));
        assertTrue(text.contains("- 重试子集：n=0（无重试调用）"));
        assertTrue(text.contains("- 调用：5/100（5.0%）；调用级成功率 100.0%"));
        assertTrue(text.contains("- stage 分布：gen 5；judge 0；calib 0"));
        assertTrue(text.contains("- 样本：total 5；样本级成功率 100.0%；重试率 0.0%；平均尝试 1.0"));
        assertTrue(text.contains("- 缓存命中率：0.0%（cached 单列 0）"));
        assertTrue(text.contains("- 总 token：prompt 500；completion 250；total 750；usage_cached 0"));
        assertTrue(text.contains("- error_type 分布：无"));
        assertTrue(text.contains("- 题级：ok 0；vetoed 0；error 0；invalid 0；非 ok 率 数据缺失"));
        assertTrue(text.contains("- run 总时长：720.0 秒"));
        assertTrue(text.contains("- 评测自身开销：700.0 秒（低可信，含调用间隙）"));
        assertTrue(text.contains("- 延迟可信度：中（非首 token，含排队）"));
        assertTrue(text.contains("- 吞吐可信度：低（非 ITL/TPOT）"));
        assertTrue(text.endsWith("\n"));
    }

    @Test
    public void shouldRenderPercentilesOnlyWhenSampleIsEnough() {
        seedRun(RUN, 20, 300000L);
        for (int i = 0; i < 20; i++) {
            seedCall(RUN, "case-" + (i + 1), "gen", "ok", 0, 0,
                    Integer.valueOf(100), Long.valueOf((i + 1) * 1000L));
        }
        String text = EvalReportPerformanceRenderer.render(EvalReportData.assemble(store, RUN, TestReportWeights.map(), null, null));

        assertTrue(text.contains("- 生成延迟：n=20；mean 10.5 秒；p50 10.0 秒；"
                + "p90 18.0 秒；max 20.0 秒"));
        assertTrue(text.contains("- p95：样本不足（n=20，需 ≥60）"));
        assertTrue(text.contains("- 输出吞吐：n=20；mean 18.0 token/s；p50 9.1 token/s"));
        assertTrue(text.contains("- 评测自身开销：90.0 秒（低可信，含调用间隙）"));
    }

    @Test
    public void unavailableMetricsShouldUseFixedLabelsWithoutGuessedNumbers() {
        String text = EvalReportPerformanceRenderer.render(EvalReportData.missing(RUN, "测试"));

        assertTrue(text.contains("- 生成延迟：数据缺失（指标未装配）"));
        assertTrue(text.contains("- p95：数据缺失"));
        assertTrue(text.contains("- 延迟排除：数据缺失"));
        assertTrue(text.contains("- 输出吞吐：数据缺失"));
        assertTrue(text.contains("- 重试子集：数据缺失"));
        assertTrue(text.contains("- 调用：数据缺失"));
        assertTrue(text.contains("- stage 分布：数据缺失"));
        assertTrue(text.contains("- 样本：数据缺失"));
        assertTrue(text.contains("- 缓存命中率：数据缺失"));
        assertTrue(text.contains("- 总 token：数据缺失"));
        assertTrue(text.contains("- error_type 分布：数据缺失"));
        assertTrue(text.contains("- 题级：数据缺失"));
        assertTrue(text.contains("- TTFT：不可得（阻塞式调用，非流式）"));
        assertTrue(text.contains("- ITL/TPOT：不可得（非流式，无逐 token 时间）"));
        assertTrue(text.contains("- run 级 TPS/RPS：不适用（非服务化压测）"));
        assertTrue(text.contains("- 工具轮数：恒 0（toolEnable=false）"));
        assertTrue(text.contains("- 金额：不报（无计价口径）"));
        assertTrue(text.contains("- L3 旁证：仅比数量级；不同口径，不可换算"));
        assertTrue(text.contains("- 口径隔离：性能与成本不进总分、不设门禁（E14）"));
        assertFalse(text.contains("null"));
    }

    @Test
    public void emptyRunShouldReportUnavailableThroughputAndOverhead() {
        seedRun(RUN, 0, 720000L);
        String text = EvalReportPerformanceRenderer.render(EvalReportData.assemble(store, RUN, TestReportWeights.map(), null, null));

        assertTrue(text.contains("- 生成延迟：数据缺失（无样本）"));
        assertTrue(text.contains("- 输出吞吐：数据缺失（无成功样本）"));
        assertTrue(text.contains("- 重试子集：n=0（无重试调用）"));
        assertTrue(text.contains("- error_type 分布：数据缺失（无调用）"));
        assertTrue(text.contains("- 题级：ok 0；vetoed 0；error 0；invalid 0；非 ok 率 数据缺失"));
        assertTrue(text.contains("- run 总时长：720.0 秒"));
        assertTrue(text.contains("- 评测自身开销：数据缺失（无 AI 调用）"));
    }

    @Test
    public void cachedCallShouldTriggerCacheConfigurationWarning() {
        seedRun(CACHED_RUN, 1, 10000L);
        seedCall(CACHED_RUN, "case-1", "gen", "ok", 1, 0, Integer.valueOf(50), Long.valueOf(1000L));

        EvalReportData data = EvalReportData.assemble(store, CACHED_RUN, TestReportWeights.map(), null, null);
        List<String> warnings = EvalReportPerformanceRenderer.performanceWarnings(data);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("缓存命中率 100.0% 不为 0，缓存键疑似配置错误"));
        assertTrue(warnings.get(0).contains("性能与 token 不可用（§8.5）"));
        assertTrue(EvalReportWriter.render(data).contains("缓存键疑似配置错误"));
    }

    @Test
    public void exhaustedBudgetShouldTriggerIncompleteSampleWarning() {
        seedRun(EXHAUSTED_RUN, 100, 10000L);
        EvalReportData data = EvalReportData.assemble(store, EXHAUSTED_RUN, TestReportWeights.map(), null, null);

        List<String> warnings = EvalReportPerformanceRenderer.performanceWarnings(data);
        assertEquals(1, warnings.size());
        assertEquals("预算耗尽：calls_used=100 / calls_budget=100，样本不完整（§8.5）",
                warnings.get(0));
        assertTrue(EvalReportWriter.render(data).contains("预算耗尽"));
    }

    @Test
    public void highLatencyShouldNotInventP95ThresholdWarning() {
        seedRun(HIGH_LATENCY_RUN, 60, 20000000L);
        for (int i = 0; i < 60; i++) {
            seedCall(HIGH_LATENCY_RUN, "case-" + (i + 1), "gen", "ok", 0, 0,
                    Integer.valueOf(100), Long.valueOf(200000L));
        }
        EvalReportData data = EvalReportData.assemble(store, HIGH_LATENCY_RUN, TestReportWeights.map(), null, null);
        String text = EvalReportPerformanceRenderer.render(data);

        assertTrue(text.contains("- p95：200.0 秒"));
        assertTrue("规格未给 p95 阈值，不得自造告警",
                EvalReportPerformanceRenderer.performanceWarnings(data).isEmpty());
        assertFalse(text.contains("p95 超"));
    }

    @Test
    public void retrySubsetShouldReportItsOwnSample() {
        seedRun(RUN, 3, 100000L);
        seedCall(RUN, "case-1", "gen", "ok", 0, 0, Integer.valueOf(50), Long.valueOf(2000L));
        seedCall(RUN, "case-2", "gen", "ok", 0, 1, Integer.valueOf(50), Long.valueOf(4000L));
        seedCall(RUN, "case-2", "gen", "ok", 0, 2, Integer.valueOf(50), Long.valueOf(6000L));
        String text = EvalReportPerformanceRenderer.render(EvalReportData.assemble(store, RUN, TestReportWeights.map(), null, null));

        assertTrue(text.contains("- 重试子集：n=2；mean 5.0 秒；p50：样本不足（n=2，需 ≥20）"));
    }

    @Test
    public void performanceSectionShouldNotLeakPrivateFields() {
        seedRun(RUN, 0, 720000L);
        storage.update("UPDATE " + EvalSchemaService.TABLE_RUN
                + " SET config_snapshot_json = ? WHERE run_id = ?", SECRET_SNAPSHOT, RUN);
        String text = EvalReportPerformanceRenderer.render(EvalReportData.assemble(store, RUN, TestReportWeights.map(), null, null));

        assertFalse(text.contains("sk-secret-9f2"));
        assertFalse(text.contains("小明"));
        assertFalse(text.contains("完整提示词"));
        assertFalse(text.contains("apiKey"));
        assertFalse(text.contains("nickname"));
    }

    @Test
    public void nineSectionsShouldStayInOrderWithPerformanceRenderer() {
        seedRun(RUN, 0, 720000L);
        String text = EvalReportWriter.render(EvalReportData.assemble(store, RUN, TestReportWeights.map(), null, null));
        String[] headings = {
            "# MBB-Eval 评测报表",
            "## 1. 头部与结论",
            "## 2. 快照",
            "## 3. 五维",
            "## 4. 总分",
            "## 5. 与基线对比",
            "## 6. 题级明细",
            "## 7. 性能",
            "## 8. 校准与告警",
            "## 9. 附录",
        };
        int previous = -1;
        for (String heading : headings) {
            int index = text.indexOf(heading + "\n");
            assertTrue("缺少标题：" + heading, index >= 0);
            assertTrue("标题顺序错误：" + heading, index > previous);
            previous = index;
        }
    }

    // ===== 夹具 =====

    private void seedRun(String runId, int callsUsed, long durationMs) {
        assertTrue(store.insertRunning(runId, EvalStore.TIER_FULL, "2026-10-25 23:00:00",
                100, "V0.1.0.0.1830"));
        storage.update("UPDATE " + EvalSchemaService.TABLE_RUN
                + " SET calls_used = ?, calibration_json = ? WHERE run_id = ?",
                Integer.valueOf(callsUsed), "{\"result\":\"通过\"}", runId);
        assertEquals(1, store.casCompleted(runId, "2026-10-25 23:12:00", durationMs,
                "./reports/run-" + runId + ".md"));
    }

    private void seedCall(String runId, String caseId, String stage, String status,
                          int cached, int attempt, Integer completion, Long elapsedMs) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("case_id", caseId);
        row.put("stage", stage);
        row.put("sample_index", Integer.valueOf(1));
        row.put("attempt", Integer.valueOf(attempt));
        row.put("status", status);
        row.put("cached", Integer.valueOf(cached));
        row.put("usage_prompt", Integer.valueOf(100));
        row.put("usage_completion", completion);
        row.put("usage_total", Integer.valueOf(150));
        row.put("usage_cached", Integer.valueOf(0));
        row.put("elapsed_ms", elapsedMs);
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCall(row));
    }
}
