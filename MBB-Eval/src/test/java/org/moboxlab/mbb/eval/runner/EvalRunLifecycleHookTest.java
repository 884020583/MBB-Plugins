package org.moboxlab.mbb.eval.runner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * H13 / H14 / H15 / H19：卸载收口、孤儿回收幂等、终态 CAS 0/-1、重启不续跑
 * （规格 §13.4、§13.5、§16.9 H-4）。
 */
public class EvalRunLifecycleHookTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private EvalStorageFake storage;
    private RecordingLogger logger;
    private EvalStore store;
    private EvalRunner runner;

    @Before
    public void setUp() throws Exception {
        File dbFile = temporaryFolder.newFile("eval-lifecycle-hook.db");
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

    /** H13：卸载时尽力把 active run 收口为 ABORTED(shutdown)，第二次卸载无事可做。 */
    @Test
    public void shutdownShouldBestEffortAbortActiveRun() {
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_SMOKE);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
        String runId = accepted.runId();
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(caseRow(runId, "case-1")));

        EvalRunner.ShutdownResult result = runner.shutdown();
        assertTrue("卸载应报告有一个进行中的 run", result.hasActiveRun());
        assertEquals(EvalRunner.ShutdownKind.ABORTED, result.kind());
        assertEquals(runId, result.runId());
        assertEquals(EvalStore.ABORT_REASON_SHUTDOWN, result.reason());

        JSONObject saved = store.findRun(runId);
        assertEquals(EvalStore.STATUS_ABORTED, saved.getString("status"));
        assertEquals("原因码写入 error_summary", EvalStore.ABORT_REASON_SHUTDOWN,
                saved.getString("error_summary"));
        assertNull("ABORTED 不生成报表", saved.getString("report_path"));
        assertNotNull(saved.getString("ended_at"));
        assertEquals("已写 case 行保留", 1, store.findCaseResults(runId).size());
        assertEquals(EvalStore.STATUS_ABORTED, runner.snapshot(runId).status());

        EvalRunner.ShutdownResult again = runner.shutdown();
        assertEquals(EvalRunner.ShutdownKind.NO_ACTIVE_RUN, again.kind());
        assertFalse(again.hasActiveRun());
        assertNull(again.runId());
        assertEquals("第二次卸载不再改写行", saved.getString("ended_at"),
                store.findRun(runId).getString("ended_at"));
    }

    /** H13：卸载 CAS 未执行时只记 WARN、不重试，行留给下次启动的孤儿清理收敛。 */
    @Test
    public void shutdownShouldWarnAndLeaveRowWhenCasNotExecuted() {
        storage.failUpdateContaining("SET status = 'ABORTED'", 1);
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_SMOKE);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
        String runId = accepted.runId();

        EvalRunner.ShutdownResult result = runner.shutdown();
        // 正常情况下由 shutdown 自己撞上 -1；占位循环抢先收口时同样只 WARN 并把行留给孤儿清理。
        assertTrue("不承诺成功：" + result.kind(),
                result.needsOrphanRecovery() || result.kind() == EvalRunner.ShutdownKind.NO_ACTIVE_RUN);
        if (result.needsOrphanRecovery()) {
            assertEquals(EvalStore.ABORT_REASON_SHUTDOWN, result.reason());
            assertNotNull("失败必须带原因说明", result.message());
            assertFalse(result.casApplied());
        }
        assertTrue("CAS 失败必须记 WARN：" + logger.warns(),
                anyWarningContains("不重试") || anyWarningContains("孤儿清理"));

        JSONObject saved = store.findRun(runId);
        assertEquals("不得用内存状态覆盖数据库", EvalStore.STATUS_RUNNING, saved.getString("status"));
        assertNull("未执行 CAS 不写 ended_at", saved.getString("ended_at"));
        assertEquals("只 CAS 一次、不重试", 1, countUpdateSql("SET status = 'ABORTED'"));

        EvalRunRecovery.RecoveryResult recovery = new EvalRunRecovery(store, logger,
                System.currentTimeMillis() + 60_000L).recoverOrphans();
        assertEquals("卸载失败后由孤儿清理收敛", 1, recovery.scanned());
        assertEquals(1, recovery.recovered());
        assertEquals(EvalStore.STATUS_ABORTED, store.findRun(runId).getString("status"));
        assertEquals(EvalRunRecovery.ORPHAN_SUMMARY, store.findRun(runId).getString("error_summary"));
    }

    /** H14：首次置 orphan，第二次回收无变化；不按 35 分钟阈值；不删 run/case 行。 */
    @Test
    public void recoverOrphansShouldBeIdempotentAndUseProcessStart() {
        long now = System.currentTimeMillis();
        String oldRunId = "r-20261026001001-smoke-0001";
        String recentRunId = "r-20261026001002-smoke-0002";
        String futureRunId = "r-20261026001003-smoke-0003";
        assertTrue(store.insertRunning(runRow(oldRunId, EvalStore.formatTime(now - 3_600_000L))));
        assertTrue(store.insertRunning(runRow(recentRunId, EvalStore.formatTime(now - 120_000L))));
        assertTrue(store.insertRunning(runRow(futureRunId, EvalStore.formatTime(now + 120_000L))));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(caseRow(oldRunId, "case-1")));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCall(callRow(oldRunId, "case-1")));

        EvalRunRecovery first = new EvalRunRecovery(store, logger, now);
        EvalRunRecovery.RecoveryResult firstResult = first.recoverOrphans();
        assertEquals("1 小时前与 2 分钟前的行都早于本次进程启动", 2, firstResult.scanned());
        assertEquals(2, firstResult.recovered());
        assertEquals(0, firstResult.failed());
        assertEquals(0, firstResult.unchanged());
        assertEquals("启动之后的 RUNNING 行不动", EvalStore.STATUS_RUNNING,
                store.findRun(futureRunId).getString("status"));
        assertEquals("2 分钟前的行也不受 35 分钟阈值保护", EvalStore.STATUS_ABORTED,
                store.findRun(recentRunId).getString("status"));

        JSONObject afterFirst = store.findRun(oldRunId);
        assertEquals(EvalStore.STATUS_ABORTED, afterFirst.getString("status"));
        assertEquals(EvalRunRecovery.ORPHAN_SUMMARY, afterFirst.getString("error_summary"));
        String endedAt = afterFirst.getString("ended_at");
        long duration = afterFirst.getLongValue("duration_ms");

        EvalRunRecovery.RecoveryResult secondResult = first.recoverOrphans();
        assertEquals("第二次回收无变化", 0, secondResult.scanned());
        assertEquals(0, secondResult.recovered());
        assertEquals(0, secondResult.failed());
        assertEquals(0, secondResult.unchanged());

        JSONObject afterSecond = store.findRun(oldRunId);
        assertNotNull("不删 run 行", afterSecond);
        assertEquals(EvalStore.STATUS_ABORTED, afterSecond.getString("status"));
        assertEquals("不重复改写终态", endedAt, afterSecond.getString("ended_at"));
        assertEquals(duration, afterSecond.getLongValue("duration_ms"));
        assertEquals("不重复改写原因", EvalRunRecovery.ORPHAN_SUMMARY, afterSecond.getString("error_summary"));
        assertEquals("不删 case 行", 1, store.findCaseResults(oldRunId).size());
        assertEquals("不删 call 行", 1, store.findCalls(oldRunId).size());
    }

    /** H14：forCurrentProcess 用 RuntimeMXBean 启动时刻，能回收上一个进程遗留的行。 */
    @Test
    public void forCurrentProcessShouldRecoverEarlierProcessRows() {
        String runId = "r-20261026002001-smoke-0001";
        assertTrue(store.insertRunning(runRow(runId,
                EvalStore.formatTime(System.currentTimeMillis() - 86_400_000L))));

        EvalRunRecovery.RecoveryResult result = EvalRunRecovery.forCurrentProcess(store, logger)
                .recoverOrphans();
        assertEquals(1, result.scanned());
        assertEquals(1, result.recovered());
        assertEquals(EvalStore.STATUS_ABORTED, store.findRun(runId).getString("status"));
        assertEquals(EvalRunRecovery.ORPHAN_SUMMARY, store.findRun(runId).getString("error_summary"));
    }

    /** H15：CAS 返回 0 只 WARN、不重试、不覆盖数据库已有终态。 */
    @Test
    public void casZeroShouldOnlyWarnAndKeepExistingTerminal() {
        String runId = "r-20261026003001-smoke-0001";
        assertTrue(store.insertRunning(runRow(runId, EvalStore.formatTime(System.currentTimeMillis()))));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(caseRow(runId, "case-1")));
        assertEquals(1, store.casAborted(runId, EvalStore.nowText(), 100L, EvalStore.ABORT_REASON_USER));

        int updatesBefore = countUpdateSql("SET status = 'ABORTED'");
        EvalRunner.FinalizeResult result = EvalRunner.casTerminal(store, logger, runId,
                EvalStore.STATUS_ABORTED, 999L, null, EvalStore.ABORT_REASON_SHUTDOWN);
        assertEquals(EvalRunner.FinalizeKind.ALREADY_TERMINAL, result.kind());
        assertEquals(0, result.updateReturn());
        assertEquals("只 CAS 一次、不重试", updatesBefore + 1, countUpdateSql("SET status = 'ABORTED'"));

        JSONObject saved = store.findRun(runId);
        assertEquals(EvalStore.STATUS_ABORTED, saved.getString("status"));
        assertEquals("不覆盖已有原因", EvalStore.ABORT_REASON_USER, saved.getString("error_summary"));
        assertEquals("不覆盖已有 duration", 100L, saved.getLongValue("duration_ms"));
        assertNull(saved.getString("report_path"));
        assertEquals("不删 case 行", 1, store.findCaseResults(runId).size());
        assertTrue("CAS 0 必须留 WARN：" + logger.warns(), anyWarningContains("幂等"));
    }

    /** H15：CAS 返回 -1 只 WARN、不重试，后续孤儿清理不被中断。 */
    @Test
    public void casNotExecutedShouldWarnNotRetryAndNotBlockLaterCleanup() {
        String runId = "r-20261026004001-smoke-0001";
        assertTrue(store.insertRunning(runRow(runId, EvalStore.formatTime(System.currentTimeMillis()))));
        storage.failUpdateContaining("SET status = 'ABORTED'", 1);

        int updatesBefore = countUpdateSql("SET status = 'ABORTED'");
        EvalRunner.FinalizeResult result = EvalRunner.casTerminal(store, logger, runId,
                EvalStore.STATUS_ABORTED, 50L, null, EvalStore.ABORT_REASON_USER);
        assertEquals(EvalRunner.FinalizeKind.NOT_EXECUTED, result.kind());
        assertEquals(-1, result.updateReturn());
        assertEquals("不重试", updatesBefore + 1, countUpdateSql("SET status = 'ABORTED'"));

        JSONObject saved = store.findRun(runId);
        assertEquals("不得用内存状态覆盖数据库", EvalStore.STATUS_RUNNING, saved.getString("status"));
        assertNull(saved.getString("ended_at"));
        assertNull(saved.getString("error_summary"));
        assertTrue("CAS -1 必须留 WARN：" + store.warnings(), anyWarningContains("终态 CAS"));
        assertTrue("runner 必须留 WARN：" + logger.warns(), anyWarningContains("不重试"));

        EvalRunRecovery.RecoveryResult recovery = new EvalRunRecovery(store, logger,
                System.currentTimeMillis() + 60_000L).recoverOrphans();
        assertEquals("后续清理不中断", 1, recovery.scanned());
        assertEquals(1, recovery.recovered());
        assertEquals(EvalStore.STATUS_ABORTED, store.findRun(runId).getString("status"));
        assertEquals(EvalRunRecovery.ORPHAN_SUMMARY, store.findRun(runId).getString("error_summary"));
    }

    /** H19：重启后不续跑、不补发摘要，旧行保留；重跑只能由新的 /eval run 产生新 run_id。 */
    @Test
    public void restartShouldNotResumeRunOrReplaySummary() {
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_SMOKE);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
        String oldRunId = accepted.runId();
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(caseRow(oldRunId, "case-1")));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCall(callRow(oldRunId, "case-1")));

        EvalRunRecovery.RecoveryResult recovery = new EvalRunRecovery(store, logger,
                System.currentTimeMillis() + 60_000L).recoverOrphans();
        assertEquals("重启视为上次进程遗留", 1, recovery.recovered());
        assertEquals(EvalStore.STATUS_ABORTED, store.findRun(oldRunId).getString("status"));
        String endedAt = store.findRun(oldRunId).getString("ended_at");

        EvalRunner restarted = new EvalRunner(store, EvalConfig.load(null), null, logger,
                "V0.1.0.0.1824");
        try {
            assertNull("重启后没有 active run，不重新进入执行", restarted.stopPoint());
            EvalRunner.RunSnapshot oldSnapshot = restarted.snapshot(oldRunId);
            assertNotNull(oldSnapshot);
            assertEquals(EvalStore.STATUS_ABORTED, oldSnapshot.status());
            assertFalse(oldSnapshot.running());
            assertEquals("不补发摘要：原因保持孤儿回收", EvalRunRecovery.ORPHAN_SUMMARY,
                    store.findRun(oldRunId).getString("error_summary"));
            assertEquals("孤儿收口不写报表", null, store.findRun(oldRunId).getString("report_path"));
            assertEquals("旧 case 行保留", 1, store.findCaseResults(oldRunId).size());
            assertEquals("旧 call 行保留", 1, store.findCalls(oldRunId).size());

            sleepQuietly(300L);
            assertEquals("重启后不续跑旧 run", 1, store.findCaseResults(oldRunId).size());
            assertEquals("重启后不补写调用行", 1, store.findCalls(oldRunId).size());
            assertEquals("旧终态不被改写", endedAt, store.findRun(oldRunId).getString("ended_at"));
            assertEquals(EvalStore.STATUS_ABORTED, store.findRun(oldRunId).getString("status"));

            EvalRunner.AcceptResult next = restarted.accept(EvalStore.TIER_SMOKE);
            assertEquals("重跑必须走新的 /eval run", EvalRunner.AcceptKind.ACCEPTED, next.kind());
            assertNotEquals("重跑产生新 run_id", oldRunId, next.runId());
            assertEquals("旧 run 不因重跑被续跑", 1, store.findCaseResults(oldRunId).size());
            assertEquals(EvalStore.STATUS_ABORTED, store.findRun(oldRunId).getString("status"));

            EvalRunner.ShutdownResult shutdown = restarted.shutdown();
            assertEquals(EvalRunner.ShutdownKind.ABORTED, shutdown.kind());
            assertEquals(next.runId(), shutdown.runId());
        } finally {
            restarted.shutdown();
        }
    }

    /** §8.2：受理建行即写九键快照；缺生产者的键写空串并逐键 WARN，不阻断受理。 */
    @Test
    public void acceptShouldPersistNineKeysSnapshotAndWarnMissingValues() {
        EvalRunner snapshotRunner = new EvalRunner(store, EvalConfig.load(null), null, logger,
                "V0.1.0.0.1824", null, new EvalRunSnapshotSource() {
                    @Override
                    public JSONObject snapshot() {
                        JSONObject row = new JSONObject();
                        row.put("bank_file", "bank.json");
                        row.put("bank_version", "1.0.0");
                        row.put("bank_hash", "abc123456789");
                        row.put("persona_id", "p-1");
                        row.put("persona_version", "V3");
                        row.put("persona_hash", "hash-1");
                        row.put("rubric_version", "r-1");
                        row.put("config_fingerprint", "cfg-1");
                        row.put("eval_profile", "default");
                        row.put("judge_profile", "judge-default");
                        row.put("eval_api_version", "2");
                        return row;
                    }
                }, null);
        try {
            EvalRunner.AcceptResult accepted = snapshotRunner.accept(EvalStore.TIER_SMOKE);
            assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
            JSONObject saved = store.findRun(accepted.runId());
            assertNotNull(saved);
            String[] keys = {"bank_file", "bank_version", "bank_hash", "persona_id",
                    "persona_version", "persona_hash", "rubric_version", "config_fingerprint",
                    "eval_profile", "judge_profile", "eval_api_version", "judge_model",
                    "generation_model", "sample_k", "baseline_id_at_run"};
            for (String key : keys) {
                assertNotNull("受理行缺列：" + key, saved.getString(key));
            }
            assertEquals("bank.json", saved.getString("bank_file"));
            assertEquals("abc123456789", saved.getString("bank_hash"));
            assertEquals("V3", saved.getString("persona_version"));
            assertEquals("1", saved.getString("sample_k"));
            assertEquals("缺生产者的模型名写空串", "", saved.getString("judge_model"));
            assertEquals("缺生产者的模型名写空串", "", saved.getString("generation_model"));
            assertEquals("无激活基线写空串", "", saved.getString("baseline_id_at_run"));
            assertTrue("缺键必须留 WARN：" + logger.warns(), anyWarningContains("judge_model 未取得"));
            assertTrue("缺键必须留 WARN：" + logger.warns(), anyWarningContains("generation_model 未取得"));
        } finally {
            snapshotRunner.shutdown();
        }
    }

    private JSONObject runRow(String runId, String startedAt) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("tier", EvalStore.TIER_SMOKE);
        row.put("started_at", startedAt);
        row.put("calls_budget", Integer.valueOf(100));
        row.put("eval_plugin_version", "V0.1.0.0.1824");
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
        row.put("created_at", EvalStore.nowText());
        return row;
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

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
}
