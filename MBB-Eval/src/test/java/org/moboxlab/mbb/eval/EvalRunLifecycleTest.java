package org.moboxlab.mbb.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.runner.EvalRunRecovery;
import org.moboxlab.mbb.eval.runner.EvalRunner;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * run 生命周期用例（规格 §4.4 建行与终态 CAS、§13.4 收尾失败语义、§13.5 init 孤儿回收）。
 *
 * <p>使用真实临时 SQLite 库（{@link EvalStorageFake}），验证：</p>
 * <ul>
 *   <li>建行写入九键快照、config_snapshot_json、baseline_id_at_run、calls_budget 与 started_at；</li>
 *   <li>COMPLETED / FAILED / ABORTED 三条终态的落库字段；</li>
 *   <li>CAS 返回 -1 记 WARN 且不重试、返回 0 按幂等成功且不覆盖终态；</li>
 *   <li>孤儿回收只处理 started_at 早于本次进程启动时刻的 RUNNING 行，不删子行、失败只告警。</li>
 * </ul>
 */
public class EvalRunLifecycleTest {

    /** §8.2 九键快照列名。 */
    private static final String[] NINE_KEYS = {
            "bank_hash", "rubric_version", "persona_hash", "config_fingerprint",
            "eval_profile", "judge_profile", "judge_model", "generation_model", "eval_api_version"
    };

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private EvalStorageFake storage;
    private RecordingLogger logger;
    private EvalStore store;
    private EvalRunner runner;

    @Before
    public void setUp() throws Exception {
        File dbFile = temporaryFolder.newFile("eval-lifecycle.db");
        storage = new EvalStorageFake(dbFile);
        assertTrue("建表失败", new EvalSchemaService(storage, null).ensureSchema());
        logger = new RecordingLogger();
        store = new EvalStore(storage, logger);
        runner = new EvalRunner(store, EvalConfig.load(null), null, logger, "V0.1.0.0.1824");
    }

    @After
    public void tearDown() {
        if (runner != null) {
            runner.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
    }

    /** §4.4：建行写 RUNNING、九键快照、config_snapshot_json、baseline_id_at_run、预算与启动时间。 */
    @Test
    public void insertRunningShouldWriteSnapshotAndRunMetadata() {
        String runId = runId(1);
        JSONObject row = fullRunRow(runId, "2026-10-26 12:20:00");
        assertTrue("全量建行应成功", store.insertRunning(row));

        JSONObject saved = store.findRun(runId);
        assertNotNull(saved);
        assertEquals(EvalStore.STATUS_RUNNING, saved.getString("status"));
        assertEquals("2026-10-26 12:20:00", saved.getString("started_at"));
        assertNull("建行不写 ended_at", saved.getString("ended_at"));
        assertNull("建行不写 report_path", saved.getString("report_path"));
        assertNull("建行不写 error_summary", saved.getString("error_summary"));
        assertEquals("默认 calls_used=0", 0, saved.getIntValue("calls_used"));
        assertEquals(120, saved.getIntValue("calls_budget"));
        assertEquals(3, saved.getIntValue("sample_k"));
        assertEquals("baseline-20261026", saved.getString("baseline_id_at_run"));
        assertEquals("{\"evalCallBudget\":120}", saved.getString("config_snapshot_json"));

        for (String key : NINE_KEYS) {
            assertEquals("九键快照字段 " + key, row.getString(key), saved.getString(key));
        }
        assertEquals("bank.json", saved.getString("bank_file"));
        assertEquals("V2", saved.getString("bank_version"));
        assertEquals("p-1", saved.getString("persona_id"));
        assertEquals("V3", saved.getString("persona_version"));
        assertTrue("正常建行不产生告警", store.warnings().isEmpty());
    }

    /** §4.2 默认值：calls_budget=100、sample_k=1；只给必填列的建行也能落库。 */
    @Test
    public void insertRunningShouldApplyColumnDefaults() {
        String runId = runId(2);
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("tier", EvalStore.TIER_SMOKE);
        row.put("started_at", "2026-10-26 12:20:00");
        assertTrue(store.insertRunning(row));

        JSONObject saved = store.findRun(runId);
        assertEquals(100, saved.getIntValue("calls_budget"));
        assertEquals(1, saved.getIntValue("sample_k"));
        assertNull("快照缺失时保持 null，不伪造九键", saved.getString("bank_hash"));
        assertNull(saved.getString("baseline_id_at_run"));
    }

    /** 既有 6 参重载保持兼容（F-1 调用方与单测不动）。 */
    @Test
    public void legacyInsertRunningShouldKeepWorking() {
        String runId = runId(3);
        assertTrue(store.insertRunning(runId, EvalStore.TIER_FULL, "2026-10-26 12:20:00", 100, "V0.1.0.0.1824"));
        JSONObject saved = store.findRun(runId);
        assertEquals(EvalStore.STATUS_RUNNING, saved.getString("status"));
        assertEquals(EvalStore.TIER_FULL, saved.getString("tier"));
        assertEquals(100, saved.getIntValue("calls_budget"));
        assertEquals("V0.1.0.0.1824", saved.getString("eval_plugin_version"));
    }

    /** §4.3：run_id 形状与档位封闭，非法输入在写库前拒绝。 */
    @Test
    public void insertRunningShouldRejectInvalidKey() {
        JSONObject row = fullRunRow("not-a-run-id", "2026-10-26 12:20:00");
        try {
            store.insertRunning(row);
            fail("非法 run_id 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("run_id"));
        }
    }

    /** §4.3：档位只能 smoke/full。 */
    @Test
    public void insertRunningShouldRejectUnknownTier() {
        JSONObject row = fullRunRow(runId(4), "2026-10-26 12:20:00");
        row.put("tier", "quick");
        try {
            store.insertRunning(row);
            fail("未知档位应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("档位"));
        }
    }

    /** §4.4 COMPLETED：写 ended_at、duration_ms、report_path。 */
    @Test
    public void finalizeCompletedShouldPersistReportPathAndDuration() {
        String runId = runId(5);
        assertTrue(store.insertRunning(fullRunRow(runId, "2026-10-26 12:20:00")));

        EvalRunner.FinalizeResult result = runner.finalizeRun(runId, EvalStore.STATUS_COMPLETED,
                1234L, "reports/" + runId + ".md", null);
        assertEquals(EvalRunner.FinalizeKind.APPLIED, result.kind());
        assertEquals(1, result.updateReturn());
        assertEquals(runId, result.runId());
        assertEquals(EvalStore.STATUS_COMPLETED, result.status());

        JSONObject saved = store.findRun(runId);
        assertEquals(EvalStore.STATUS_COMPLETED, saved.getString("status"));
        assertEquals(1234L, saved.getLongValue("duration_ms"));
        assertEquals("reports/" + runId + ".md", saved.getString("report_path"));
        assertNotNull("终态必须写 ended_at", saved.getString("ended_at"));
        assertNull("COMPLETED 不写 error_summary", saved.getString("error_summary"));
    }

    /** §13.1.3 FAILED：失败摘要写 error_summary，不写 report_path。 */
    @Test
    public void finalizeFailedShouldPersistErrorSummary() {
        String runId = runId(6);
        assertTrue(store.insertRunning(fullRunRow(runId, "2026-10-26 12:20:00")));

        EvalRunner.FinalizeResult result = runner.finalizeRun(runId, EvalStore.STATUS_FAILED,
                5000L, null, "AI 连续不可用，评测失败");
        assertEquals(EvalRunner.FinalizeKind.APPLIED, result.kind());

        JSONObject saved = store.findRun(runId);
        assertEquals(EvalStore.STATUS_FAILED, saved.getString("status"));
        assertEquals(5000L, saved.getLongValue("duration_ms"));
        assertEquals("AI 连续不可用，评测失败", saved.getString("error_summary"));
        assertNull(saved.getString("report_path"));
    }

    /** §13.1.3 ABORTED：原因码写入 error_summary。 */
    @Test
    public void finalizeAbortedShouldPersistReasonCode() {
        String runId = runId(7);
        assertTrue(store.insertRunning(fullRunRow(runId, "2026-10-26 12:20:00")));

        EvalRunner.FinalizeResult result = runner.finalizeRun(runId, EvalStore.STATUS_ABORTED,
                800L, null, EvalStore.ABORT_REASON_USER);
        assertEquals(EvalRunner.FinalizeKind.APPLIED, result.kind());

        JSONObject saved = store.findRun(runId);
        assertEquals(EvalStore.STATUS_ABORTED, saved.getString("status"));
        assertEquals(EvalStore.ABORT_REASON_USER, saved.getString("error_summary"));
        assertEquals(800L, saved.getLongValue("duration_ms"));
    }

    /** §13.4：CAS 返回 -1 记 WARN、不重试，行仍按数据库现行状态保留 RUNNING。 */
    @Test
    public void casNotExecutedShouldWarnAndNotRetry() {
        String runId = runId(8);
        assertTrue(store.insertRunning(fullRunRow(runId, "2026-10-26 12:20:00")));
        storage.failUpdateContaining("SET status = 'COMPLETED'", 1);

        EvalRunner.FinalizeResult result = runner.finalizeRun(runId, EvalStore.STATUS_COMPLETED,
                10L, "reports/x.md", null);
        assertEquals("语句未执行必须返回 NOT_EXECUTED", EvalRunner.FinalizeKind.NOT_EXECUTED, result.kind());
        assertEquals(-1, result.updateReturn());

        JSONObject saved = store.findRun(runId);
        assertEquals("不得用内存状态覆盖数据库", EvalStore.STATUS_RUNNING, saved.getString("status"));
        assertNull(saved.getString("report_path"));
        assertNull(saved.getString("ended_at"));
        assertTrue("必须记 WARN：" + store.warnings(), anyWarningContains("终态 CAS"));
        assertEquals("只 CAS 一次、不重试", 1, countUpdateSql("SET status = 'COMPLETED'"));
        assertTrue("runner 必须记 WARN：" + logger.warns(), anyWarningContains("不重试"));
    }

    /** §13.4：CAS 返回 0 按幂等成功处理，不覆盖已有终态、不写第二份报表。 */
    @Test
    public void casAlreadyTerminalShouldBeIdempotent() {
        String runId = runId(9);
        assertTrue(store.insertRunning(fullRunRow(runId, "2026-10-26 12:20:00")));
        EvalRunner.FinalizeResult first = runner.finalizeRun(runId, EvalStore.STATUS_COMPLETED,
                100L, "reports/first.md", null);
        assertEquals(EvalRunner.FinalizeKind.APPLIED, first.kind());

        EvalRunner.FinalizeResult again = runner.finalizeRun(runId, EvalStore.STATUS_COMPLETED,
                999L, "reports/second.md", null);
        assertEquals("没有 RUNNING 行按幂等成功处理", EvalRunner.FinalizeKind.ALREADY_TERMINAL, again.kind());
        assertEquals(0, again.updateReturn());

        JSONObject saved = store.findRun(runId);
        assertEquals("reports/first.md", saved.getString("report_path"));
        assertEquals("duration_ms 不被第二次调用改写", 100L, saved.getLongValue("duration_ms"));
        assertTrue("CAS 0 也要留痕：" + logger.warns(), anyWarningContains("幂等"));
    }

    /** §4.4 + §13.4：受理后由 finalizeActive 收口，释放单飞锁。 */
    @Test
    public void finalizeActiveShouldCloseAcceptedRun() {
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_SMOKE);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
        String runId = accepted.runId();

        EvalRunner.FinalizeResult result = runner.finalizeActive(EvalStore.STATUS_COMPLETED,
                "reports/" + runId + ".md", null);
        assertNotNull(result);
        assertEquals(EvalRunner.FinalizeKind.APPLIED, result.kind());

        JSONObject saved = store.findRun(runId);
        assertEquals(EvalStore.STATUS_COMPLETED, saved.getString("status"));
        assertEquals("reports/" + runId + ".md", saved.getString("report_path"));
        assertTrue("duration_ms 不应为负", saved.getLongValue("duration_ms") >= 0L);
        assertNull("没有 active 时再次收口应返回 null", runner.finalizeActive(EvalStore.STATUS_COMPLETED, null, null));

        EvalRunner.AcceptResult next = runner.accept(EvalStore.TIER_SMOKE);
        assertEquals("终态收口后必须能受理新 run", EvalRunner.AcceptKind.ACCEPTED, next.kind());
        assertFalse(runId.equals(next.runId()));
    }

    /** §13.5：只回收 started_at 早于本次进程启动时刻的 RUNNING 行，幂等且不删子行。 */
    @Test
    public void recoverShouldAbortOnlyRunsStartedBeforeProcessStart() {
        String oldRun = runId(11);
        String boundaryRun = runId(12);
        String exactRun = runId(13);
        String newerRun = runId(14);
        assertTrue(store.insertRunning(fullRunRow(oldRun, "2026-10-26 12:00:00")));
        assertTrue(store.insertRunning(fullRunRow(boundaryRun, "2026-10-26 12:10:00")));
        assertTrue(store.insertRunning(fullRunRow(exactRun, "2026-10-26 12:20:00")));
        assertTrue(store.insertRunning(fullRunRow(newerRun, "2026-10-26 12:40:00")));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(caseRow(oldRun, "case-1")));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCall(callRow(oldRun, "case-1")));

        EvalRunRecovery.RecoveryResult result = recoverAt("2026-10-26 12:20:00");
        assertEquals("只扫描早于启动时刻的 RUNNING 行", 2, result.scanned());
        assertEquals(2, result.recovered());
        assertEquals(0, result.failed());

        assertOrphan(oldRun);
        assertOrphan(boundaryRun);
        assertEquals("恰好等于启动时刻不算孤儿", EvalStore.STATUS_RUNNING,
                store.findRun(exactRun).getString("status"));
        assertEquals("启动之后的 RUNNING 行不动", EvalStore.STATUS_RUNNING,
                store.findRun(newerRun).getString("status"));
        assertEquals("不删 case 行", 1, store.findCaseResults(oldRun).size());
        assertEquals("不删 call 行", 1, store.findCalls(oldRun).size());
        assertTrue("回收要留 WARN：" + store.warnings(), anyWarningContains("孤儿"));

        String endedAt = store.findRun(oldRun).getString("ended_at");
        EvalRunRecovery.RecoveryResult second = recoverAt("2026-10-26 12:20:00");
        assertEquals("第二次启动无变化", 0, second.scanned());
        assertEquals(0, second.recovered());
        assertEquals("不重复改写已回收行", endedAt, store.findRun(oldRun).getString("ended_at"));
    }

    /** §13.4：回收 CAS 失败只告警，行保留 RUNNING，等下次启动再回收。 */
    @Test
    public void recoverShouldWarnAndKeepRowRunningWhenCasFails() {
        String firstRun = runId(21);
        String secondRun = runId(22);
        assertTrue(store.insertRunning(fullRunRow(firstRun, "2026-10-26 12:00:00")));
        assertTrue(store.insertRunning(fullRunRow(secondRun, "2026-10-26 12:10:00")));
        storage.failUpdateContaining("SET status = 'ABORTED'", 1);

        EvalRunRecovery.RecoveryResult result = recoverAt("2026-10-26 12:20:00");
        assertEquals(2, result.scanned());
        assertEquals("失败一条不阻塞其余行", 1, result.recovered());
        assertEquals(1, result.failed());

        JSONObject failedRow = store.findRun(firstRun);
        assertEquals("失败的行保持 RUNNING", EvalStore.STATUS_RUNNING, failedRow.getString("status"));
        assertNull(failedRow.getString("ended_at"));
        assertEquals(EvalStore.STATUS_ABORTED, store.findRun(secondRun).getString("status"));
        assertTrue("回收失败必须告警：" + store.warnings(), anyWarningContains("孤儿回收"));
    }

    private EvalRunRecovery.RecoveryResult recoverAt(String processStartText) {
        return new EvalRunRecovery(store, logger, millis(processStartText)).recoverOrphans();
    }

    private void assertOrphan(String runId) {
        JSONObject saved = store.findRun(runId);
        assertNotNull("回收不删除行：" + runId, saved);
        assertEquals(EvalStore.STATUS_ABORTED, saved.getString("status"));
        assertEquals("ABORTED 必须带原因码与进程中断说明",
                EvalRunRecovery.ORPHAN_SUMMARY, saved.getString("error_summary"));
        assertEquals(0L, saved.getLongValue("duration_ms"));
        assertNotNull(saved.getString("ended_at"));
    }

    private String runId(int seq) {
        return String.format("r-20261026%06d-smoke-%04d", seq, seq);
    }

    /** 九键快照齐全的建行数据（§8.2）。 */
    private JSONObject fullRunRow(String runId, String startedAt) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("tier", EvalStore.TIER_SMOKE);
        row.put("started_at", startedAt);
        row.put("bank_file", "bank.json");
        row.put("bank_version", "V2");
        row.put("bank_hash", "sha256:bank");
        row.put("persona_id", "p-1");
        row.put("persona_version", "V3");
        row.put("persona_hash", "sha256:persona");
        row.put("rubric_version", "rubric-v3");
        row.put("eval_api_version", "eval-api-v1");
        row.put("eval_plugin_version", "V0.1.0.0.1824");
        row.put("roleplay_plugin_version", "V0.2.0.0.1000");
        row.put("java_version", "1.8.0_412");
        row.put("os", "Windows 11");
        row.put("eval_profile", "profile-a");
        row.put("judge_profile", "judge-b");
        row.put("judge_model", "judge-model");
        row.put("generation_model", "generation-model");
        row.put("config_fingerprint", "fp-9");
        row.put("config_snapshot_json", "{\"evalCallBudget\":120}");
        row.put("sample_k", Integer.valueOf(3));
        row.put("calls_budget", Integer.valueOf(120));
        row.put("baseline_id_at_run", "baseline-20261026");
        return row;
    }

    private JSONObject caseRow(String runId, String caseId) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("case_id", caseId);
        row.put("dimension", "persona");
        row.put("status", "ok");
        row.put("score", Double.valueOf(80.0));
        row.put("max_score", Double.valueOf(100));
        row.put("evidence", "证据");
        row.put("detail_json", "{}");
        row.put("created_at", EvalStore.nowText());
        return row;
    }

    private JSONObject callRow(String runId, String caseId) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("case_id", caseId);
        row.put("stage", "judge");
        row.put("sample_index", Integer.valueOf(1));
        row.put("attempt", Integer.valueOf(1));
        row.put("status", "ok");
        row.put("cached", Integer.valueOf(0));
        row.put("profile", "judge");
        row.put("model", "test-model");
        row.put("usage_prompt", Integer.valueOf(10));
        row.put("usage_completion", Integer.valueOf(20));
        row.put("usage_total", Integer.valueOf(30));
        row.put("elapsed_ms", Long.valueOf(120));
        row.put("created_at", EvalStore.nowText());
        return row;
    }

    /** store 的降级告警与插件日志的 WARN 都算「已告警」。 */
    private boolean anyWarningContains(String fragment) {
        List<String> all = new ArrayList<String>(store.warnings());
        all.addAll(logger.warns());
        for (String warning : all) {
            if (warning.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private int countUpdateSql(String fragment) {
        int count = 0;
        List<String> statements = storage.updateSql();
        for (String sql : statements) {
            if (sql.contains(fragment)) {
                count++;
            }
        }
        return count;
    }

    /** 只收集日志的假件：不走主程序日志，避免测试写出日志文件。 */
    private static final class RecordingLogger extends PluginLogger {
        private final List<String> warns = new ArrayList<String>();

        private RecordingLogger() {
            super("test", null);
        }

        @Override
        public void sendInfo(String message) {
        }

        @Override
        public void sendWarn(String message) {
            warns.add(message);
        }

        @Override
        public void sendError(String message) {
        }

        private List<String> warns() {
            return Collections.unmodifiableList(warns);
        }
    }

    private static long millis(String text) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        format.setLenient(false);
        try {
            return format.parse(text).getTime();
        } catch (ParseException e) {
            throw new IllegalStateException("测试时间格式非法：" + text, e);
        }
    }
}
