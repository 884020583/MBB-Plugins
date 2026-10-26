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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** F-7 / I-2 群内纯文本摘要测试（§8.6、§15.5 F19、§15.9 I2）。 */
public class EvalGroupSummaryFormatterTest {

    private static final String RUN = "r-20261025230000-full-7k2m";
    private static final String BASE_RUN = "r-20261024230000-full-aaaa";
    private static final String RUBRIC = "1.0.0+r1";
    private static final String CONFIG_FINGERPRINT = "1234567890abcdef";
    private static final String SECRET_SNAPSHOT = "{\"apiKey\":\"sk-secret-9f2\","
            + "\"nickname\":\"小明\",\"prompt\":\"完整提示词：你是莫莫\"}";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;

    @Before
    public void setUp() throws IOException {
        storage = new EvalStorageFake(new File(folder.getRoot(), "eval-summary.db"));
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
    public void completedRunShouldRenderAllRequiredElements() {
        EvalReportData data = seedSnapshot(true);
        assertTrue(EvalGroupSummaryFormatter.canSummarize(data));

        String text = EvalGroupSummaryFormatter.toPlainText(data);
        String[] lines = text.split("\n", -1);
        assertTrue("摘要行数应不超过 15，实际 " + lines.length, lines.length <= 15);
        assertTrue("摘要至少包含 run 标识到报表路径", lines.length >= 6);
        assertEquals("【MBB-Eval】" + RUN + "（full，10-25 23:00）", lines[0]);
        assertTrue(text.contains("总分 74.0"));
        assertTrue(text.contains("基线 " + BASE_RUN + "：+5.0（显著性未计算）"));
        assertTrue(text.contains("人设 80.0↑ 记忆 70.0↑ 边界 90.0↑ 知识 50.0↑ 风格 60.0↑"));
        assertTrue(text.contains("题级"));
        assertTrue(text.contains("变好"));
        assertTrue(text.contains("变差"));
        assertTrue(text.contains("持平"));
        assertTrue(text.contains("翻转"));
        assertTrue(text.contains("ok→error"));
        assertTrue(text.contains("性能 p50 1.9s / p90 2.7s / 调用 20/100 / 成功率 100.0% / 缓存 0"));
        assertTrue(text.contains("告警 无"));
        assertEquals("报表 reports/run-" + RUN + ".md", lines[lines.length - 1]);
        assertPlainText(text);
    }

    @Test
    public void summaryLinesShouldMatchWhitelistedPrefixes() {
        String text = EvalGroupSummaryFormatter.toPlainText(seedSnapshot(true));
        String[] allowed = {"【MBB-Eval】", "总分 ", "基线 ", "人设 ", "题级 ", "性能 ", "告警 ",
                "报表 "};
        for (String line : text.split("\n", -1)) {
            boolean matched = false;
            for (String prefix : allowed) {
                if (line.startsWith(prefix)) {
                    matched = true;
                    break;
                }
            }
            assertTrue("摘要行不在白名单内：" + line, matched);
        }
    }

    @Test
    public void summaryShouldNotContainSensitiveFields() {
        EvalReportData data = seedSnapshot(true);
        String text = EvalGroupSummaryFormatter.toPlainText(data);

        assertFalse(text.contains("sk-secret-9f2"));
        assertFalse(text.contains("小明"));
        assertFalse(text.contains("完整提示词"));
        assertFalse(text.contains("模型回复原文"));
        assertFalse(text.contains("user_id"));
        assertFalse(text.contains("config_snapshot"));
        assertFalse(text.contains("人设贴合"));
        assertFalse(text.contains("case-1"));
        assertFalse(text.contains("roleplay-default"));
        assertFalse(text.contains("p-hash-1"));
        assertFalse(text.contains("1234567890abcdef"));
    }

    @Test
    public void runningRunShouldBeRefusedWithStatusHint() {
        EvalReportData data = seedRunningRun();
        assertFalse(EvalGroupSummaryFormatter.canSummarize(data));

        String text = EvalGroupSummaryFormatter.toPlainText(data);
        assertTrue(text.contains("无法生成群内摘要"));
        assertTrue(text.contains("RUNNING"));
        assertTrue(text.contains("/eval status " + RUN));
        assertFalse("拒绝原因不得输出分数", text.contains("总分"));
        assertFalse(text.contains("报表 reports/"));
        assertPlainText(text);
    }

    @Test
    public void incomparableBaselineShouldSayIncomparable() {
        seedRun(RUN, "2026-10-25 23:00:00", "1.0.0+r2", reportPath(RUN), true);
        seedFullCases(RUN);
        seedGenCalls(RUN, 20);
        runSet(RUN, "calls_used", Integer.valueOf(20));
        seedBaseline(BASE_RUN, 69.0D, RUBRIC);
        EvalReportData data = EvalReportData.assemble(store, RUN);

        assertTrue(EvalGroupSummaryFormatter.canSummarize(data));
        String text = EvalGroupSummaryFormatter.toPlainText(data);
        assertTrue(text.contains("基线 " + BASE_RUN + "：不可比（九键不同）"));
        assertFalse("不可比时五维不得给方向", text.contains("↑") || text.contains("↓"));
        assertTrue(text.contains("告警 rubric 已变"));
    }

    @Test
    public void untrustedRunShouldShowWarningLine() {
        seedRun(RUN, "2026-10-25 23:00:00", RUBRIC, reportPath(RUN), true);
        seedCase(RUN, "case-1", "persona", "ok", 80.0D, "2", 1, 1, "人设贴合");
        seedCase(RUN, "case-2", "boundary", "ok", 90.0D, "pass", 3, 3, "未越界");
        seedCase(RUN, "case-3", "memory", "error", null, null, 0, 0, "调用失败");
        seedCase(RUN, "case-4", "knowledge", "error", null, null, 0, 0, "调用失败");
        seedCase(RUN, "case-5", "style", "ok", 60.0D, "natural", 1, 1, "语气自然");
        seedGenCalls(RUN, 3);
        EvalReportData data = EvalReportData.assemble(store, RUN);

        assertTrue(EvalGroupSummaryFormatter.canSummarize(data));
        String text = EvalGroupSummaryFormatter.toPlainText(data);
        assertTrue("应有不可信告警：" + text, text.contains("告警 不可信"));
        assertTrue(text.contains("有效题 3/5"));
    }

    @Test
    public void missingRunShouldBeRefused() {
        EvalReportData data = EvalReportData.assemble(store, "r-20260101000000-full-zzzz");
        assertFalse(EvalGroupSummaryFormatter.canSummarize(data));

        String text = EvalGroupSummaryFormatter.toPlainText(data);
        assertTrue(text.contains("无法生成群内摘要"));
        assertTrue(text.contains("不在库中"));
        assertTrue(text.contains("/eval status"));
        assertFalse(text.contains("总分"));
        assertPlainText(text);
    }

    @Test
    public void absentBaselineShouldSayIncomparable() {
        seedRun(RUN, "2026-10-25 23:00:00", RUBRIC, reportPath(RUN), true);
        seedFullCases(RUN);
        seedGenCalls(RUN, 20);
        runSet(RUN, "calls_used", Integer.valueOf(20));
        EvalReportData data = EvalReportData.assemble(store, RUN);

        String text = EvalGroupSummaryFormatter.toPlainText(data);
        assertTrue(text.contains("基线 无（不可比）"));
        assertFalse(text.contains("↑") || text.contains("↓") || text.contains("="));
    }

    // ===== 断言 =====

    private static void assertPlainText(String text) {
        assertFalse("不得含 Markdown 标题", text.contains("#"));
        assertFalse("不得含 Markdown 强调", text.contains("**") || text.contains("~~"));
        assertFalse("不得含 Markdown 表格", text.contains("|"));
        assertFalse("不得含 Markdown 代码标记", text.contains(String.valueOf((char) 96)));
        for (String line : text.split("\n", -1)) {
            assertFalse("不得以列表符或引用符开头：" + line,
                    line.startsWith("- ") || line.startsWith("* ") || line.startsWith("> "));
            assertFalse("不得含分隔线：" + line, "---".equals(line.trim()));
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            assertFalse("不得含 emoji", (c >= 0x1F300 && c <= 0x1FAFF)
                    || (c >= 0x2600 && c <= 0x27BF));
            assertFalse("不得含 emoji 代理对", Character.isSurrogate(c));
        }
        assertTrue("行数不得超过 " + EvalGroupSummaryFormatter.MAX_LINES,
                text.split("\n", -1).length <= EvalGroupSummaryFormatter.MAX_LINES);
    }

    // ===== 夹具 =====

    private EvalReportData seedSnapshot(boolean withBaseline) {
        seedRun(RUN, "2026-10-25 23:00:00", RUBRIC, reportPath(RUN), true);
        if (withBaseline) {
            seedRun(BASE_RUN, "2026-10-24 23:00:00", RUBRIC, reportPath(BASE_RUN), true);
            seedBaseCases(BASE_RUN);
            seedBaseline(BASE_RUN, 69.0D, RUBRIC);
        }
        seedFullCases(RUN);
        seedGenCalls(RUN, 20);
        runSet(RUN, "calls_used", Integer.valueOf(20));
        return EvalReportData.assemble(store, RUN);
    }

    private EvalReportData seedRunningRun() {
        assertTrue(store.insertRunning(RUN, EvalStore.TIER_FULL, "2026-10-25 23:00:00", 100,
                "V0.1.0.0.1830"));
        runSet(RUN, "rubric_version", RUBRIC);
        runSet(RUN, "config_fingerprint", CONFIG_FINGERPRINT);
        runSet(RUN, "config_snapshot_json", SECRET_SNAPSHOT);
        return EvalReportData.assemble(store, RUN);
    }

    private void seedRun(String runId, String startedAt, String rubricVersion, String reportPath,
                         boolean completed) {
        assertTrue(store.insertRunning(runId, EvalStore.TIER_FULL, startedAt, 100,
                "V0.1.0.0.1830"));
        runSet(runId, "bank_file", "bank-1.0.0.json");
        runSet(runId, "bank_version", "1.0.0");
        runSet(runId, "bank_hash", "aaaabbbbcccc");
        runSet(runId, "persona_id", "roleplay-default");
        runSet(runId, "persona_version", "1");
        runSet(runId, "persona_hash", "p-hash-1");
        runSet(runId, "rubric_version", rubricVersion);
        runSet(runId, "eval_api_version", "1");
        runSet(runId, "eval_profile", "profile-a");
        runSet(runId, "judge_profile", "judge-a");
        runSet(runId, "judge_model", "judge-model-x");
        runSet(runId, "generation_model", "gen-model-y");
        runSet(runId, "config_fingerprint", CONFIG_FINGERPRINT);
        runSet(runId, "config_snapshot_json", SECRET_SNAPSHOT);
        runSet(runId, "sample_k", Integer.valueOf(3));
        runSet(runId, "calls_used", Integer.valueOf(5));
        runSet(runId, "calibration_json", "{\"result\":\"通过\"}");
        if (completed) {
            assertEquals(1, store.casCompleted(runId, "2026-10-25 23:12:00", 720000L, reportPath));
        }
    }

    private void runSet(String runId, String column, Object value) {
        assertEquals("更新 " + column + " 失败", 1, storage.update("UPDATE "
                + EvalSchemaService.TABLE_RUN + " SET " + column + " = ? WHERE run_id = ?",
                value, runId));
    }

    private void seedFullCases(String runId) {
        seedCase(runId, "case-1", "persona", "ok", 80.0D, "2", 1, 1, "人设贴合，语气一致");
        seedCase(runId, "case-2", "boundary", "ok", 90.0D, "pass", 3, 3, "未越界");
        seedCase(runId, "case-3", "memory", "ok", 70.0D, "hit", 1, 1, "记住称呼");
        seedCase(runId, "case-4", "knowledge", "ok", 50.0D, "hit", 1, 1, "答案部分正确");
        seedCase(runId, "case-5", "style", "ok", 60.0D, "natural", 1, 1, "语气自然");
    }

    private void seedBaseCases(String runId) {
        seedCase(runId, "case-1", "persona", "ok", 75.0D, "2", 1, 1, "人设贴合");
        seedCase(runId, "case-2", "boundary", "ok", 85.0D, "pass", 3, 3, "未越界");
        seedCase(runId, "case-3", "memory", "ok", 65.0D, "hit", 1, 1, "记住称呼");
        seedCase(runId, "case-4", "knowledge", "ok", 45.0D, "hit", 1, 1, "答案部分正确");
        seedCase(runId, "case-5", "style", "ok", 55.0D, "natural", 1, 1, "语气自然");
    }

    private void seedCase(String runId, String caseId, String dimension, String status,
                          Double score, String label, int kUsed, int sampleCount, String evidence) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("case_id", caseId);
        row.put("dimension", dimension);
        row.put("status", status);
        if (score != null) {
            row.put("score", score);
        }
        row.put("label", label);
        row.put("k_used", Integer.valueOf(kUsed));
        row.put("sample_count", Integer.valueOf(sampleCount));
        row.put("cached_count", Integer.valueOf(0));
        row.put("evidence", evidence);
        row.put("detail_json", "{\"prompt\":\"完整提示词：你是莫莫\",\"reply\":\"模型回复原文\"}");
        assertEquals("插入 case " + caseId + " 失败", EvalStore.WriteResult.APPLIED,
                store.insertCaseResult(row));
    }

    private void seedGenCalls(String runId, int count) {
        for (int i = 0; i < count; i++) {
            JSONObject row = new JSONObject();
            row.put("run_id", runId);
            row.put("case_id", "case-" + (i % 5 + 1));
            row.put("stage", "gen");
            row.put("sample_index", Integer.valueOf(i / 5 + 1));
            row.put("attempt", Integer.valueOf(0));
            row.put("status", "ok");
            row.put("cached", Integer.valueOf(0));
            row.put("usage_prompt", Integer.valueOf(100));
            row.put("usage_completion", Integer.valueOf(50));
            row.put("usage_total", Integer.valueOf(150));
            row.put("elapsed_ms", Long.valueOf(1000L + i * 100L));
            assertEquals(EvalStore.WriteResult.APPLIED, store.insertCall(row));
        }
    }

    private void seedBaseline(String runId, double totalScore, String rubricVersion) {
        JSONObject row = new JSONObject();
        row.put("baseline_id", "b-1");
        row.put("run_id", runId);
        row.put("active", Integer.valueOf(1));
        row.put("dimension_scores_json", "{\"persona\":75.0,\"boundary\":85.0,"
                + "\"memory\":65.0,\"knowledge\":45.0,\"style\":55.0}");
        row.put("total_score", Double.valueOf(totalScore));
        row.put("bank_hash", "aaaabbbbcccc");
        row.put("rubric_version", rubricVersion);
        row.put("persona_hash", "p-hash-1");
        row.put("config_fingerprint", CONFIG_FINGERPRINT);
        row.put("eval_profile", "profile-a");
        row.put("judge_profile", "judge-a");
        row.put("judge_model", "judge-model-x");
        row.put("generation_model", "gen-model-y");
        row.put("eval_api_version", "1");
        row.put("created_at", "2026-10-24 23:20:00");
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertBaseline(row));
    }

    private static String reportPath(String runId) {
        return "./MoBoxBot/plugins/MBB-Eval/reports/run-" + runId + ".md";
    }
}
