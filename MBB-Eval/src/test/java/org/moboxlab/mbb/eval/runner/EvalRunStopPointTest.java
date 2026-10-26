package org.moboxlab.mbb.eval.runner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * H12：中止后的停止点、已写行保留、不生成报表（规格 §9.6、§13.4、§16.9 H-4）。
 *
 * <p>验收点：</p>
 * <ul>
 *   <li>abort 在下一个写库点生效：每个 case 落库前、每次调用前检查，命中后不再写新行；</li>
 *   <li>已完成的 case/call 行保留可查，run 行保留且 report_path 为空；</li>
 *   <li>数据库已被其他路径收口时以 DB 为准，停止点只释放内存持有，不用内存覆盖 DB。</li>
 * </ul>
 */
public class EvalRunStopPointTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private EvalStorageFake storage;
    private RecordingLogger logger;
    private EvalStore store;
    private EvalRunner runner;

    @Before
    public void setUp() throws Exception {
        File dbFile = temporaryFolder.newFile("eval-stop-point.db");
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

    /** H12：abort 后下一个 case 写库点停止，已完成的 case/call 行保留，不生成报表。 */
    @Test
    public void abortShouldStopAtNextCaseWriteAndKeepFinishedRows() {
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_SMOKE);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
        String runId = accepted.runId();

        EvalRunStopPoint stopPoint = runner.stopPoint();
        assertNotNull("受理后必须有停止点", stopPoint);
        assertEquals(runId, stopPoint.runId());
        assertFalse("刚受理时不应停止", stopPoint.beforeCaseWrite().stop());

        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(caseRow(runId, "case-1")));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCall(callRow(runId, "case-1")));

        stopPoint.requestStop(EvalStore.ABORT_REASON_USER);
        EvalRunStopPoint.Decision caseDecision = stopPoint.beforeCaseWrite();
        assertTrue("中止后必须停止", caseDecision.stop());
        assertEquals(EvalRunStopPoint.StopKind.ABORT_REQUESTED, caseDecision.kind());
        assertEquals(EvalStore.ABORT_REASON_USER, caseDecision.reason());
        assertTrue("停止说明应带原因码：" + caseDecision.message(),
                caseDecision.message().contains(EvalStore.ABORT_REASON_USER));

        // 模拟 runner 循环：命中停止点后不再写第二条 case 行。
        if (!caseDecision.stop()) {
            store.insertCaseResult(caseRow(runId, "case-2"));
        }

        EvalRunStopPoint.Decision callDecision = stopPoint.beforeCall();
        assertTrue("每次调用前也要检查中止标志", callDecision.stop());

        EvalRunner.AbortResult abortResult = runner.abort();
        assertEquals(EvalRunner.AbortKind.ABORTED, abortResult.kind());
        assertEquals(runId, abortResult.runId());
        assertNull("中止后释放 active，不再有停止点", runner.stopPoint());

        JSONObject saved = store.findRun(runId);
        assertNotNull(saved);
        assertEquals(EvalStore.STATUS_ABORTED, saved.getString("status"));
        assertEquals(EvalStore.ABORT_REASON_USER, saved.getString("error_summary"));
        assertNull("ABORTED 不生成报表", saved.getString("report_path"));
        assertNotNull("终态必须写 ended_at", saved.getString("ended_at"));

        EvalRunner.RunSnapshot snapshot = runner.snapshot(runId);
        assertEquals(EvalStore.STATUS_ABORTED, snapshot.status());
        assertFalse(snapshot.running());

        assertEquals("已完成的 case 行保留可查", 1, store.findCaseResults(runId).size());
        assertNotNull(store.findCaseResult(runId, "case-1"));
        assertNull("停止后不写新的 case 行", store.findCaseResult(runId, "case-2"));
        assertEquals("已完成的 call 行保留可查", 1, store.findCalls(runId).size());
    }

    /** H12：数据库已被其他路径收口时，停止点以 DB 为准，且不覆盖已有终态。 */
    @Test
    public void stopPointShouldHonorTerminalStateWrittenByOtherPath() {
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_SMOKE);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
        String runId = accepted.runId();
        EvalRunStopPoint stopPoint = runner.stopPoint();
        assertNotNull(stopPoint);
        assertFalse(stopPoint.beforeCall().stop());

        assertEquals("外部路径先把行收口", 1, store.casAborted(runId, EvalStore.nowText(), 0L,
                EvalStore.ABORT_REASON_USER));

        EvalRunStopPoint.Decision decision = stopPoint.beforeCall();
        assertTrue("数据库已终态时必须停止", decision.stop());
        assertEquals(EvalRunStopPoint.StopKind.TERMINAL_IN_STORE, decision.kind());
        assertNull("外部终态不带内存原因码", decision.reason());
        assertTrue("说明应以数据库为准：" + decision.message(),
                decision.message().contains("数据库"));

        // 占位循环最多 200ms 轮询一次：等它观察到 DB 终态并释放内存持有。
        assertTrue("占位循环应观察到外部终态并停止", waitUntilNoActiveRun(runner, 3000L));
        assertTrue("应记 WARN 说明以其他路径收口为准：" + logger.warns(),
                anyWarningContains("其他路径收口"));

        JSONObject saved = store.findRun(runId);
        assertEquals(EvalStore.STATUS_ABORTED, saved.getString("status"));
        assertEquals("不得用内存原因覆盖数据库已有终态", EvalStore.ABORT_REASON_USER,
                saved.getString("error_summary"));
        assertEquals("外部终态不被改写为 shutdown/aborted", 0L, saved.getLongValue("duration_ms"));
        assertNull("ABORTED 不生成报表", saved.getString("report_path"));

        EvalRunner.AbortResult again = runner.abort();
        assertEquals("没有 RUNNING 行时 abort 返回 NONE", EvalRunner.AbortKind.NONE, again.kind());
    }

    /** 停止点自身：首因生效、幂等、空白 run_id 拒绝、探针异常不误停、终态只查一次。 */
    @Test
    public void stopPointShouldKeepFirstReasonAndTolerateProbeFailure() {
        try {
            new EvalRunStopPoint("  ");
            fail("空白 run_id 应拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("run_id"));
        }

        final int[] probeCalls = new int[1];
        EvalRunStopPoint stopPoint = new EvalRunStopPoint("r-20261026000001-smoke-0001",
                new EvalRunStopPoint.RunStatusProbe() {
                    @Override
                    public boolean terminal() {
                        probeCalls[0]++;
                        return false;
                    }
                });
        assertFalse(stopPoint.stopRequested());
        assertTrue(stopPoint.beforeCaseWrite().kind() == EvalRunStopPoint.StopKind.CONTINUE);
        int afterFirstCheck = probeCalls[0];
        assertEquals("写库点必须看数据库现行状态", EvalRunStopPoint.StopKind.CONTINUE,
                stopPoint.beforeCall().kind());
        assertTrue("写库点不因限频跳过查库", probeCalls[0] > afterFirstCheck);
        int beforePoll = probeCalls[0];
        assertEquals(EvalRunStopPoint.StopKind.CONTINUE, stopPoint.poll().kind());
        assertEquals("轮询探针限频：1 秒内不重复查库", beforePoll, probeCalls[0]);

        stopPoint.requestStop(EvalStore.ABORT_REASON_BUDGET);
        stopPoint.requestStop(EvalStore.ABORT_REASON_USER);
        assertEquals("首次原因生效", EvalStore.ABORT_REASON_BUDGET, stopPoint.requestedReason());
        int callsBefore = probeCalls[0];
        assertEquals(EvalRunStopPoint.StopKind.ABORT_REQUESTED, stopPoint.beforeCall().kind());
        assertEquals("已中止时不再查库", callsBefore, probeCalls[0]);

        EvalRunStopPoint emptyReason = new EvalRunStopPoint("r-20261026000002-smoke-0002");
        emptyReason.requestStop(null);
        assertEquals("空白原因按 user-abort 记录（§13.1.3 封闭集合）",
                EvalStore.ABORT_REASON_USER, emptyReason.requestedReason());

        EvalRunStopPoint failingProbe = new EvalRunStopPoint("r-20261026000003-smoke-0003",
                new EvalRunStopPoint.RunStatusProbe() {
                    @Override
                    public boolean terminal() {
                        throw new IllegalStateException("查询抖动");
                    }
                });
        assertFalse("探针异常不得误停", failingProbe.check().stop());

        final int[] terminalCalls = new int[1];
        EvalRunStopPoint terminalProbe = new EvalRunStopPoint("r-20261026000004-smoke-0004",
                new EvalRunStopPoint.RunStatusProbe() {
                    @Override
                    public boolean terminal() {
                        terminalCalls[0]++;
                        return true;
                    }
                });
        assertEquals(EvalRunStopPoint.StopKind.TERMINAL_IN_STORE, terminalProbe.check().kind());
        assertEquals(EvalRunStopPoint.StopKind.TERMINAL_IN_STORE, terminalProbe.check().kind());
        assertEquals("观察到终态后不重复查库", 1, terminalCalls[0]);
        assertTrue(terminalProbe.terminalInStore());
    }

    private boolean waitUntilNoActiveRun(EvalRunner target, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (target.stopPoint() == null) {
                return true;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return target.stopPoint() == null;
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
