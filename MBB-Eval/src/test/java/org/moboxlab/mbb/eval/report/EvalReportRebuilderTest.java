package org.moboxlab.mbb.eval.report;

import com.alibaba.fastjson.JSONObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.score.EvalDimensionWeights;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * H-5 报表重建测试（H10 / H11 / H16 / H17）。
 *
 * <p>覆盖：删报表后从库重建且内容一致、report_path 指向实际文件、手改内容被覆盖、
 * ABORTED / RUNNING 拒绝重建、不可信标注透传、重建不破坏既有 case / call 行。</p>
 */
public class EvalReportRebuilderTest {

    private static final String RUN = "r-20261025230000-full-7k2m";
    private static final String BASE_RUN = "r-20261024230000-full-aaaa";
    private static final String STARTED_AT = "2026-10-25 23:00:00";
    private static final String ENDED_AT = "2026-10-25 23:12:00";
    private static final String[] DIMENSIONS = {"persona", "boundary", "memory", "knowledge", "style"};

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;
    private File reportsDir;
    private EvalReportRebuilder rebuilder;

    @Before
    public void setUp() throws Exception {
        storage = new EvalStorageFake(new File(folder.getRoot(), "eval-rebuild.db"));
        new EvalSchemaService(storage, null).ensureSchema();
        store = new EvalStore(storage, null);
        reportsDir = new File(folder.getRoot(), EvalReportRebuilder.REPORTS_DIR_NAME);
        rebuilder = new EvalReportRebuilder(store, weights(), reportsDir, null);
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    // ------------------------------------------------------------ H17

    @Test
    public void deletedReportShouldBeRebuiltWithSameContentAndActualPath() throws IOException {
        seedBaseline();
        insertRunningRun(RUN);
        insertOkCases(RUN, 40, 70.0d, 0);
        assertEquals(1, store.casCompleted(RUN, ENDED_AT, 720000L, null));

        EvalReportRebuilder.RebuildResult first = rebuilder.rebuild(RUN);
        assertTrue(first.reason(), first.rebuilt());
        assertNotNull(first.file());
        assertTrue(first.file().isFile());
        assertEquals("reports/" + EvalReportWriter.fileName(RUN), first.relativePath());
        assertTrue(first.deltaApplied());
        assertTrue(first.reportPathUpdated());

        File target = new File(reportsDir, EvalReportWriter.fileName(RUN));
        assertTrue(target.isFile());
        String firstText = read(target);
        assertFalse("delta 已接线，不应再有未计算占位", firstText.contains("未计算"));
        assertTrue(firstText.contains("| 维度 | 基线分 | 本次分 | delta | ci_low | ci_high | 结论 |"));
        assertTrue(firstText.contains("- 题级：变好 "));

        // 模拟报表被删除：从库重建，内容逐字一致（报表不含生成时间戳）
        assertTrue(target.delete());
        EvalReportRebuilder.RebuildResult second = rebuilder.rebuild(RUN);
        assertTrue(second.reason(), second.rebuilt());
        String secondText = read(target);
        assertEquals(firstText, secondText);

        JSONObject run = store.findRun(RUN);
        assertEquals("reports/" + EvalReportWriter.fileName(RUN), run.getString("report_path"));
        assertTrue(new File(reportsDir, EvalReportWriter.fileName(RUN)).isFile());

        // 手改后重建：内容被覆盖回库中事实，而不是保留手改
        Files.write(target.toPath(), "手工改动".getBytes(StandardCharsets.UTF_8));
        EvalReportRebuilder.RebuildResult third = rebuilder.rebuild(RUN);
        assertTrue(third.reason(), third.rebuilt());
        assertEquals(firstText, read(target));
    }

    // ------------------------------------------------------------ H10

    @Test
    public void abortedAndRunningRunsShouldBeRejectedWithReason() {
        insertRunningRun(RUN);
        assertEquals(1, store.casAborted(RUN, ENDED_AT, 720000L,
                EvalStore.ABORT_REASON_DEADLINE));

        EvalReportRebuilder.RebuildResult aborted = rebuilder.rebuild(RUN);
        assertFalse(aborted.rebuilt());
        assertNull(aborted.file());
        assertTrue(aborted.reason(), aborted.reason().contains("ABORTED"));
        assertTrue(aborted.reason(), aborted.reason().contains("/eval status"));
        assertFalse(new File(reportsDir, EvalReportWriter.fileName(RUN)).isFile());

        String runningId = "r-20261025230100-full-bbbb";
        insertRunningRun(runningId);
        EvalReportRebuilder.RebuildResult running = rebuilder.rebuild(runningId);
        assertFalse(running.rebuilt());
        assertTrue(running.reason(), running.reason().contains("RUNNING"));
        assertTrue(running.reason(), running.reason().contains("/eval status"));
        assertFalse(new File(reportsDir, EvalReportWriter.fileName(runningId)).isFile());
    }

    // ------------------------------------------------------------ H11

    @Test
    public void untrustedRunShouldCarryAnnotationsIntoSectionFive() throws IOException {
        seedBaseline();
        insertRunningRun(RUN);
        insertOkCases(RUN, 30, 70.0d, 0);
        insertErrorCases(RUN, 10, 30);
        assertEquals(1, store.casCompleted(RUN, ENDED_AT, 720000L, null));

        EvalReportRebuilder.RebuildResult result = rebuilder.rebuild(RUN);
        assertTrue(result.reason(), result.rebuilt());
        String text = read(result.file());

        // run 仍为 COMPLETED，两条不可信标注各自独立出现在第 5 节
        assertEquals("COMPLETED", store.findRun(RUN).getString("status"));
        assertTrue(text.contains("不可信：有效题 30/40（低于 80%）"));
        assertTrue(text.contains("判分不可信：非 ok 率 25.0% 超过 10%"));
        assertTrue(text.contains("- 标注："));
    }

    // ------------------------------------------------------------ H16

    @Test
    public void rebuildShouldKeepExistingCaseAndCallRows() {
        seedBaseline();
        insertRunningRun(RUN);
        insertOkCases(RUN, 5, 70.0d, 0);
        insertCall(RUN, "case-1", 0, 0, "ok");
        insertCall(RUN, "case-2", 0, 0, "error");
        assertEquals(1, store.casCompleted(RUN, ENDED_AT, 720000L, null));

        long casesBefore = storage.count("SELECT COUNT(*) AS total FROM plugin_mbb_eval_case_result"
                + " WHERE run_id = ?", RUN);
        long callsBefore = storage.count("SELECT COUNT(*) AS total FROM plugin_mbb_eval_call"
                + " WHERE run_id = ?", RUN);
        assertEquals(5L, casesBefore);
        assertEquals(2L, callsBefore);

        EvalReportRebuilder.RebuildResult result = rebuilder.rebuild(RUN);
        assertTrue(result.reason(), result.rebuilt());

        assertEquals(casesBefore, storage.count("SELECT COUNT(*) AS total FROM"
                + " plugin_mbb_eval_case_result WHERE run_id = ?", RUN));
        assertEquals(callsBefore, storage.count("SELECT COUNT(*) AS total FROM"
                + " plugin_mbb_eval_call WHERE run_id = ?", RUN));
        assertEquals("COMPLETED", store.findRun(RUN).getString("status"));
        assertNotNull(store.findCaseResult(RUN, "case-1"));
        assertNotNull(store.findCall(EvalStore.deriveCallId(RUN, "case-2", "generate",
                Integer.valueOf(0), Integer.valueOf(0))));
    }

    // ------------------------------------------------------------ 辅助

    private void seedBaseline() {
        insertRunningRun(BASE_RUN);
        insertOkCases(BASE_RUN, 40, 60.0d, 0);
        assertEquals(1, store.casCompleted(BASE_RUN, ENDED_AT, 720000L, null));

        JSONObject baseline = new JSONObject();
        baseline.put("baseline_id", "b-1");
        baseline.put("run_id", BASE_RUN);
        baseline.put("active", Integer.valueOf(1));
        baseline.put("total_score", Double.valueOf(60.0d));
        baseline.put("valid_case_count", Integer.valueOf(40));
        baseline.put("dimension_scores_json", "{\"persona\":60.0,\"boundary\":60.0,"
                + "\"memory\":60.0,\"knowledge\":60.0,\"style\":60.0}");
        baseline.put("bank_hash", "bank-hash-1");
        baseline.put("rubric_version", "rubric-1");
        baseline.put("persona_hash", "persona-hash-1");
        baseline.put("config_fingerprint", "fingerprint-1");
        baseline.put("eval_profile", "eval-profile-1");
        baseline.put("judge_profile", "judge-profile-1");
        baseline.put("judge_model", "judge-1");
        baseline.put("generation_model", "gen-1");
        baseline.put("eval_api_version", "api-1");
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertBaseline(baseline));
    }

    private void insertRunningRun(String runId) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("tier", EvalStore.TIER_FULL);
        row.put("started_at", STARTED_AT);
        row.put("bank_hash", "bank-hash-1");
        row.put("rubric_version", "rubric-1");
        row.put("persona_hash", "persona-hash-1");
        row.put("config_fingerprint", "fingerprint-1");
        row.put("eval_profile", "eval-profile-1");
        row.put("judge_profile", "judge-profile-1");
        row.put("judge_model", "judge-1");
        row.put("generation_model", "gen-1");
        row.put("eval_api_version", "api-1");
        assertTrue("run 行写入失败：" + runId, store.insertRunning(row));
    }

    private void insertOkCases(String runId, int count, double score, int startIndex) {
        for (int i = 1; i <= count; i++) {
            insertCase(runId, "case-" + (startIndex + i), dimensionOf(startIndex + i), "ok", score);
        }
    }

    private void insertErrorCases(String runId, int count, int startIndex) {
        for (int i = 1; i <= count; i++) {
            insertCase(runId, "case-" + (startIndex + i), dimensionOf(startIndex + i), "error", null);
        }
    }

    private void insertCase(String runId, String caseId, String dimension, String status,
                            Double score) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("case_id", caseId);
        row.put("dimension", dimension);
        row.put("status", status);
        if (score != null) {
            row.put("score", score);
        }
        row.put("max_score", Integer.valueOf(100));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(row));
    }

    private void insertCall(String runId, String caseId, int sampleIndex, int attempt,
                            String status) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("case_id", caseId);
        row.put("stage", "generate");
        row.put("sample_index", Integer.valueOf(sampleIndex));
        row.put("attempt", Integer.valueOf(attempt));
        row.put("status", status);
        if (!"ok".equals(status)) {
            row.put("error_type", "timeout");
        }
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCall(row));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String dimensionOf(int indexOneBased) {
        return DIMENSIONS[(indexOneBased - 1) % DIMENSIONS.length];
    }

    private static EvalDimensionWeights weights() {
        Map<String, Double> map = new LinkedHashMap<String, Double>();
        map.put("persona", 0.30d);
        map.put("boundary", 0.25d);
        map.put("memory", 0.20d);
        map.put("knowledge", 0.15d);
        map.put("style", 0.10d);
        return EvalDimensionWeights.of(map);
    }
}
