package org.moboxlab.mbb.eval.report;

import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.baseline.EvalDeltaResult;
import org.moboxlab.mbb.eval.metrics.EvalAiUsageDay;
import org.moboxlab.mbb.eval.metrics.EvalAiUsageEvidence;
import org.moboxlab.mbb.eval.metrics.EvalAiUsageSource;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * W-2 报表数据接线测试：§5 F-6 delta、§6/§7 L3 旁证、群摘要附属文件（§8.4、§8.6、§7.5）。
 *
 * <p>覆盖三类接线：assemble 自动挂接 active baseline 的 delta（显著 / 不显著 / 不可比），
 * 可注入的 L3 只读旁证（有数据 / 不可得 / 读失败），以及 writeToFile 同时落盘完整报表
 * 与 ≤15 行的群摘要纯文本，且摘要不含隐私字段。</p>
 */
public class EvalReportDataWiringTest {

    private static final String BASE_RUN = "r-20261024230000-full-aaaa";
    private static final String RUN = "r-20261025230000-full-bbbb";
    private static final String RUBRIC = "rubric-1";
    private static final String STARTED_AT = "2026-10-25 23:00:00";
    private static final String ENDED_AT = "2026-10-25 23:12:00";
    private static final String SECRET_SNAPSHOT = "{\"apiKey\":\"sk-secret-9f2\","
            + "\"nickname\":\"小明\",\"prompt\":\"完整提示词：你是莫莫\"}";
    private static final String[] DIMENSIONS = {"persona", "boundary", "memory", "knowledge", "style"};

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;

    @Before
    public void setUp() throws IOException {
        storage = new EvalStorageFake(new File(folder.getRoot(), "eval-wiring.db"));
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
    public void assembleShouldAttachSignificantDeltaFromActiveBaseline() {
        seedBaselineRun();
        seedRun(RUN, RUBRIC, improvingCases(), 40);
        seedGenCalls(RUN, 20);

        EvalReportData data = EvalReportData.assemble(store, RUN);

        assertNotNull("§5 必须挂上 F-6 delta", data.delta());
        assertTrue(data.delta().available());
        assertTrue(data.delta().comparable());
        assertEquals(40, data.delta().pairedCount());
        assertEquals(21.5D, data.delta().totalDelta().doubleValue(), 1.0E-9D);

        String text = EvalReportWriter.render(data);
        assertTrue(text.contains("- 两侧可信度：基线 可信 / 本次 可信"));
        assertTrue("维度结论应来自 delta 显著性：" + text, text.contains("显著↑"));
        assertTrue(text.contains("- 题级：变好 40 / 变差 0 / 持平 0"));
        assertTrue(text.contains("- 注：维度区间与显著性由 bootstrap 计算（1000 次、种子 41；§8.4）"));
    }

    @Test
    public void assembleShouldAttachFlatDeltaWhenScoresUnchanged() {
        seedBaselineRun();
        seedRun(RUN, RUBRIC, flatCases(60.0D, 40), 40);
        seedGenCalls(RUN, 20);

        EvalReportData data = EvalReportData.assemble(store, RUN);

        assertNotNull(data.delta());
        assertTrue(data.delta().comparable());
        assertEquals(0.0D, data.delta().totalDelta().doubleValue(), 1.0E-9D);

        String text = EvalReportWriter.render(data);
        assertTrue("不显著应写固定标签：" + text, text.contains("持平（不显著）"));
        assertTrue(text.contains("- 题级：变好 0 / 变差 0 / 持平 40"));
        assertFalse(text.contains("显著↑"));
        assertFalse(text.contains("显著↓"));
    }

    @Test
    public void assembleShouldAttachIncomparableDeltaWhenRubricChanged() {
        seedBaselineRun();
        seedRun(RUN, "rubric-2", flatCases(60.0D, 40), 40);
        seedGenCalls(RUN, 20);

        EvalReportData data = EvalReportData.assemble(store, RUN);

        assertNotNull(data.delta());
        assertFalse(data.delta().comparable());
        assertNull("rubric 已变时不出总分 delta", data.delta().totalDelta());

        String text = EvalReportWriter.render(data);
        assertTrue(text.contains("- 判定：不可比（九键不同）"));
        assertTrue(text.contains("- 总分 delta：不可比（rubric 已变）"));
        assertTrue(text.contains("- 差异字段：rubric_version"));
        assertFalse(text.contains("显著↑"));
        assertFalse(text.contains("显著↓"));
    }

    @Test
    public void assembleShouldRenderUnavailableL3UsageWithoutFailing() {
        seedBaselineRun();
        seedRun(RUN, RUBRIC, flatCases(60.0D, 40), 40);
        seedGenCalls(RUN, 20);
        EvalAiUsageSource empty = new FakeSource(Collections.<EvalAiUsageDay>emptyList());

        EvalReportData data = EvalReportData.assemble(store, RUN, null, empty);
        String text = EvalReportWriter.render(data);

        assertNotNull("有数据源时必须产出 L3 旁证对象", data.aiUsage());
        assertFalse(data.aiUsage().available());
        assertEquals(EvalAiUsageEvidence.MISSING_TEXT, "不可得");
        assertTrue(text.contains("L3 旁证（plugin_mbb_ai_usage，只读）"));
        assertTrue(text.contains("- 数据：不可得"));
        assertTrue(text.contains(EvalAiUsageEvidence.CALIBER_NOTE));
        assertNotNull("L3 缺失不影响 §5 delta", data.delta());
    }

    @Test
    public void assembleShouldRenderL3UsageRowsWhenSourceHasData() {
        seedBaselineRun();
        seedRun(RUN, RUBRIC, flatCases(60.0D, 40), 40);
        seedGenCalls(RUN, 20);
        FakeSource source = new FakeSource(Arrays.asList(
                new EvalAiUsageDay("2026-10-25", "eval-profile-1", 100L, 96L, 4L,
                        1000L, 500L, 1500L, 25000L, 3L)));

        EvalReportData data = EvalReportData.assemble(store, RUN, null, source);
        String text = EvalReportWriter.render(data);

        assertTrue(data.aiUsage().available());
        assertEquals("eval-profile-1", source.lastProfile);
        assertEquals("2026-10-25", source.lastStartDate);
        assertEquals("2026-10-25", source.lastEndDate);
        assertTrue(text.contains("2026-10-25：requests 100"));
        assertTrue(text.contains("- 仅比数量级：不写回评测表，不换算为逐调用口径"));
    }

    @Test
    public void assembleShouldTreatL3SourceFailureAsUnavailable() {
        seedBaselineRun();
        seedRun(RUN, RUBRIC, flatCases(60.0D, 40), 40);
        seedGenCalls(RUN, 20);
        EvalAiUsageSource broken = new EvalAiUsageSource() {
            @Override
            public List<EvalAiUsageDay> queryDailyUsage(String profile, String startDate,
                    String endDate) {
                throw new IllegalStateException("统计表不存在");
            }
        };

        EvalReportData data = EvalReportData.assemble(store, RUN, null, broken);

        assertNotNull(data.aiUsage());
        assertFalse("读失败按不可得兜底，不抛错", data.aiUsage().available());
        assertTrue(EvalReportWriter.render(data).contains("- 数据：不可得"));
    }

    @Test
    public void assembleWithoutUsageSourceShouldKeepLegacyL3Line() {
        seedBaselineRun();
        seedRun(RUN, RUBRIC, flatCases(60.0D, 40), 40);
        seedGenCalls(RUN, 20);

        EvalReportData data = EvalReportData.assemble(store, RUN);

        assertNull(data.aiUsage());
        assertNotNull(data.delta());
        String text = EvalReportWriter.render(data);
        assertTrue(text.contains("- L3 旁证：仅比数量级；不同口径，不可换算"));
        assertFalse(text.contains("L3 旁证（plugin_mbb_ai_usage，只读）"));
    }

    @Test
    public void writeToFileShouldWritePlainTextSummaryNextToReport() throws IOException {
        seedBaselineRun();
        seedRun(RUN, RUBRIC, flatCases(60.0D, 40), 40);
        seedGenCalls(RUN, 20);
        EvalReportData data = EvalReportData.assemble(store, RUN);
        File reportsDir = new File(folder.getRoot(), "reports");

        File report = EvalReportWriter.writeToFile(data, reportsDir);
        File summary = new File(reportsDir, EvalReportWriter.summaryFileName(RUN));

        assertEquals("run-" + RUN + ".md", report.getName());
        assertTrue("群摘要必须与完整报表同目录落盘：" + summary, summary.isFile());
        String text = new String(Files.readAllBytes(summary.toPath()), StandardCharsets.UTF_8);
        assertEquals(EvalGroupSummaryFormatter.toPlainText(data), text);
        assertTrue("摘要不得超过 15 行",
                text.split("\n", -1).length <= EvalGroupSummaryFormatter.MAX_LINES);
        assertTrue(text.startsWith("【MBB-Eval】" + RUN));
        assertTrue(text.endsWith("报表 reports/run-" + RUN + ".md"));
    }

    @Test
    public void summaryFileShouldNotLeakPrivacyFields() throws IOException {
        seedBaselineRun();
        seedRun(RUN, RUBRIC, flatCases(60.0D, 40), 40);
        seedGenCalls(RUN, 20);
        EvalReportData data = EvalReportData.assemble(store, RUN);

        File reportsDir = new File(folder.getRoot(), "reports");
        EvalReportWriter.writeToFile(data, reportsDir);
        String text = new String(Files.readAllBytes(
                new File(reportsDir, EvalReportWriter.summaryFileName(RUN)).toPath()),
                StandardCharsets.UTF_8);

        String[] forbidden = {"sk-secret-9f2", "小明", "完整提示词", "模型回复原文", "case-1",
                "人设贴合", "config_snapshot", "persona_hash", "p-hash-1", "evidence"};
        for (String token : forbidden) {
            assertFalse("摘要不得含隐私字段：" + token, text.contains(token));
        }
        assertFalse("摘要不得是 Markdown", text.contains("#") || text.contains("|"));
    }

    @Test
    public void writeToFileShouldSkipSummaryForRunningRun() throws IOException {
        seedBaselineRun();
        JSONObject row = new JSONObject();
        row.put("run_id", RUN);
        row.put("tier", EvalStore.TIER_FULL);
        row.put("started_at", STARTED_AT);
        row.put("rubric_version", RUBRIC);
        assertTrue(store.insertRunning(row));
        EvalReportData data = EvalReportData.assemble(store, RUN);
        assertFalse(EvalGroupSummaryFormatter.canSummarize(data));

        File reportsDir = new File(folder.getRoot(), "reports");
        EvalReportWriter.writeToFile(data, reportsDir);

        assertTrue(new File(reportsDir, "run-" + RUN + ".md").isFile());
        assertFalse("非 COMPLETED 不写群摘要",
                new File(reportsDir, EvalReportWriter.summaryFileName(RUN)).exists());
        assertEquals(1, reportsDir.listFiles().length);
    }

    // ===== 夹具 =====

    /** 冻结基线 run：九键与本次一致，40 题全 60 分。 */
    private void seedBaselineRun() {
        seedRun(BASE_RUN, RUBRIC, flatCases(60.0D, 40), 0);
        JSONObject baseline = new JSONObject();
        baseline.put("baseline_id", "b-1");
        baseline.put("run_id", BASE_RUN);
        baseline.put("active", Integer.valueOf(1));
        baseline.put("dimension_scores_json", "{\"persona\":60.0,\"boundary\":60.0,"
                + "\"memory\":60.0,\"knowledge\":60.0,\"style\":60.0}");
        baseline.put("total_score", Double.valueOf(60.0D));
        baseline.put("bank_hash", "bank-hash-1");
        baseline.put("rubric_version", RUBRIC);
        baseline.put("persona_hash", "persona-hash-1");
        baseline.put("config_fingerprint", "fingerprint-1");
        baseline.put("eval_profile", "eval-profile-1");
        baseline.put("judge_profile", "judge-profile-1");
        baseline.put("judge_model", "judge-1");
        baseline.put("generation_model", "gen-1");
        baseline.put("eval_api_version", "api-1");
        baseline.put("created_at", "2026-10-24 23:20:00");
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertBaseline(baseline));
    }

    private void seedRun(String runId, String rubricVersion, List<CaseSpec> cases, int callCount) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("tier", EvalStore.TIER_FULL);
        row.put("started_at", STARTED_AT);
        row.put("bank_hash", "bank-hash-1");
        row.put("rubric_version", rubricVersion);
        row.put("persona_hash", "persona-hash-1");
        row.put("config_fingerprint", "fingerprint-1");
        row.put("eval_profile", "eval-profile-1");
        row.put("judge_profile", "judge-profile-1");
        row.put("judge_model", "judge-1");
        row.put("generation_model", "gen-1");
        row.put("eval_api_version", "api-1");
        row.put("calls_used", Integer.valueOf(callCount));
        row.put("config_snapshot_json", SECRET_SNAPSHOT);
        assertTrue("run 行写入失败：" + runId, store.insertRunning(row));

        for (CaseSpec spec : cases) {
            JSONObject caseRow = new JSONObject();
            caseRow.put("run_id", runId);
            caseRow.put("case_id", spec.caseId);
            caseRow.put("dimension", spec.dimension);
            caseRow.put("status", spec.status);
            if (spec.score != null) {
                caseRow.put("score", spec.score);
            }
            caseRow.put("max_score", Integer.valueOf(100));
            caseRow.put("k_used", Integer.valueOf(1));
            caseRow.put("sample_count", Integer.valueOf(1));
            caseRow.put("cached_count", Integer.valueOf(0));
            caseRow.put("evidence", "人设贴合");
            caseRow.put("detail_json", "{\"prompt\":\"完整提示词：你是莫莫\","
                    + "\"reply\":\"模型回复原文\"}");
            assertEquals("用例写入失败：" + spec.caseId, EvalStore.WriteResult.APPLIED,
                    store.insertCaseResult(caseRow));
        }
        assertEquals(1, store.casCompleted(runId, ENDED_AT, 720000L,
                EvalReportWriter.relativeReportPath(runId)));
    }

    private void seedGenCalls(String runId, int count) {
        for (int i = 0; i < count; i++) {
            JSONObject call = new JSONObject();
            call.put("run_id", runId);
            call.put("case_id", "case-" + (i % 5 + 1));
            call.put("stage", "gen");
            call.put("sample_index", Integer.valueOf(i / 5 + 1));
            call.put("attempt", Integer.valueOf(0));
            call.put("status", "ok");
            call.put("cached", Integer.valueOf(0));
            call.put("usage_prompt", Integer.valueOf(100));
            call.put("usage_completion", Integer.valueOf(50));
            call.put("usage_total", Integer.valueOf(150));
            call.put("elapsed_ms", Long.valueOf(1000L + i * 100L));
            assertEquals(EvalStore.WriteResult.APPLIED, store.insertCall(call));
        }
    }

    private static List<CaseSpec> flatCases(double score, int count) {
        List<CaseSpec> cases = new ArrayList<CaseSpec>();
        for (int i = 1; i <= count; i++) {
            cases.add(new CaseSpec("case-" + i, dimensionOf(i), score));
        }
        return cases;
    }

    private static List<CaseSpec> improvingCases() {
        List<CaseSpec> cases = new ArrayList<CaseSpec>();
        for (int i = 1; i <= 40; i++) {
            cases.add(new CaseSpec("case-" + i, dimensionOf(i), 80.0D + (i % 4)));
        }
        return cases;
    }

    private static String dimensionOf(int indexOneBased) {
        return DIMENSIONS[(indexOneBased - 1) % DIMENSIONS.length];
    }

    /** 一条 ok 用例结果。 */
    private static final class CaseSpec {

        private final String caseId;
        private final String dimension;
        private final String status;
        private final Double score;

        private CaseSpec(String caseId, String dimension, double score) {
            this.caseId = caseId;
            this.dimension = dimension;
            this.status = "ok";
            this.score = Double.valueOf(score);
        }
    }

    /** L3 假数据源：记录入参并原样返回固定行。 */
    private static final class FakeSource implements EvalAiUsageSource {

        private final List<EvalAiUsageDay> rows;
        private String lastProfile;
        private String lastStartDate;
        private String lastEndDate;

        private FakeSource(List<EvalAiUsageDay> rows) {
            this.rows = new ArrayList<EvalAiUsageDay>(rows);
        }

        @Override
        public List<EvalAiUsageDay> queryDailyUsage(String profile, String startDate,
                String endDate) {
            this.lastProfile = profile;
            this.lastStartDate = startDate;
            this.lastEndDate = endDate;
            return new ArrayList<EvalAiUsageDay>(rows);
        }
    }
}
