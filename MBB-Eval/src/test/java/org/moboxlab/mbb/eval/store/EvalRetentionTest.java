package org.moboxlab.mbb.eval.store;

import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginLogger;
import org.moboxlab.moboxbot.API.Storage.StorageService;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 保留清理服务测试（规格 §4.5、§4.7 ST7、§15.6 F6、§16.7 F-3）。
 *
 * <p>数值口径：evalKeepRuns 默认 30、有效范围 5..200（§12.1）；清理只删终态行，
 * 被任何基线引用的 run 与仍在 RUNNING 的行永不删除；删除顺序为子表 → run，
 * 单条删除返回 0 行或失败即中止本轮清理并记 WARN（§4.5）。</p>
 */
public class EvalRetentionTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;
    private EvalConfig config;

    @Before
    public void setUp() throws Exception {
        File dbFile = temporaryFolder.newFile("eval-retention.db");
        storage = new EvalStorageFake(dbFile);
        assertTrue("建表失败", new EvalSchemaService(storage, null).ensureSchema());
        store = new EvalStore(storage, null);
        config = EvalConfig.load(null);
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    private String runId(int seq) {
        return String.format("r-20261026%06d-smoke-%04d", seq, seq);
    }

    private JSONObject caseRow(String runId, String caseId) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("case_id", caseId);
        row.put("dimension", "persona");
        row.put("status", "ok");
        row.put("score", Double.valueOf(80.0));
        row.put("max_score", Double.valueOf(100.0));
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
        row.put("created_at", EvalStore.nowText());
        return row;
    }

    /**
     * 准备一条带子行的终态 run。
     *
     * @param status COMPLETED / FAILED / ABORTED
     */
    private void seedTerminalRun(int seq, String status, String startedAt, String reportPath) {
        String runId = runId(seq);
        assertTrue("准备运行行：" + runId,
                store.insertRunning(runId, EvalStore.TIER_SMOKE, startedAt, 100, null));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(caseRow(runId, "case-1")));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCall(callRow(runId, "case-1")));
        if (EvalStore.STATUS_COMPLETED.equals(status)) {
            assertEquals(1, store.casCompleted(runId, startedAt, 1000L, reportPath));
        } else if (EvalStore.STATUS_FAILED.equals(status)) {
            assertEquals(1, store.casFailed(runId, startedAt, 1000L, "测试失败"));
        } else if (EvalStore.STATUS_ABORTED.equals(status)) {
            assertEquals(1, store.casAborted(runId, startedAt, 1000L, "测试中止"));
        }
    }

    private JSONObject baselineRow(String baselineId, String runId, int active) {
        JSONObject row = new JSONObject();
        row.put("baseline_id", baselineId);
        row.put("run_id", runId);
        row.put("active", Integer.valueOf(active));
        row.put("dimension_scores_json", "{}");
        row.put("total_score", Double.valueOf(80.0));
        row.put("valid_case_count", Integer.valueOf(1));
        row.put("created_at", EvalStore.nowText());
        row.put("created_by", "tester");
        row.put("reason", "保留清理测试");
        return row;
    }

    /** 统计某条 SQL 在 update 序列中的位置，用于断言「子表先删、run 后删」。 */
    private List<Integer> positionsOf(String fragment) {
        List<Integer> positions = new ArrayList<Integer>();
        List<String> statements = storage.updateSql();
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).contains(fragment)) {
                positions.add(Integer.valueOf(i));
            }
        }
        return positions;
    }

    /** 保留 2 条时，最旧的 3 条终态 run 连同子行一起被删，保留窗口内的不动。 */
    @Test
    public void shouldDeleteOldestTerminalRunsBeyondKeepWindow() {
        for (int i = 0; i < 5; i++) {
            seedTerminalRun(i, EvalStore.STATUS_COMPLETED,
                    String.format("2026-10-26 12:00:%02d", i), "reports/run-" + runId(i) + ".md");
        }

        EvalRetentionService service = new EvalRetentionService(store, config, null);
        EvalRetentionService.RetentionResult result = service.retain(2);

        assertEquals("应删除最旧 3 条", 3, result.deletedCount());
        assertEquals(0, result.skippedCount());
        assertEquals(0, result.failedCount());
        for (int i = 0; i < 3; i++) {
            assertNull("最旧终态 run 应被删除：" + runId(i), store.findRun(runId(i)));
            assertEquals("子行应随 run 删除：" + runId(i), 0, store.findCaseResults(runId(i)).size());
            assertEquals("调用行应随 run 删除：" + runId(i), 0, store.findCalls(runId(i)).size());
        }
        for (int i = 3; i < 5; i++) {
            assertNotNull("保留窗口内的 run 不得删除：" + runId(i), store.findRun(runId(i)));
            assertEquals("保留窗口内的子行不得删除：" + runId(i), 1,
                    store.findCaseResults(runId(i)).size());
        }

        List<Integer> casePositions = positionsOf("DELETE FROM "
                + EvalSchemaService.TABLE_CASE_RESULT);
        List<Integer> callPositions = positionsOf("DELETE FROM " + EvalSchemaService.TABLE_CALL);
        List<Integer> runPositions = positionsOf("DELETE FROM " + EvalSchemaService.TABLE_RUN);
        assertEquals(3, casePositions.size());
        assertEquals(3, callPositions.size());
        assertEquals(3, runPositions.size());
        for (int i = 0; i < 3; i++) {
            assertTrue("用例结果必须先于调用行删除", casePositions.get(i) < callPositions.get(i));
            assertTrue("调用行必须先于 run 行删除", callPositions.get(i) < runPositions.get(i));
        }
    }

    /** 只收集日志的假件：不走主程序日志，避免测试写出日志文件。 */
    private static final class RecordingLogger extends PluginLogger {
        private final List<String> infos = new ArrayList<String>();
        private final List<String> warns = new ArrayList<String>();
        private final List<String> errors = new ArrayList<String>();

        private RecordingLogger() {
            super("test", null);
        }

        @Override
        public void sendInfo(String message) {
            infos.add(message);
        }

        @Override
        public void sendWarn(String message) {
            warns.add(message);
        }

        @Override
        public void sendError(String message) {
            errors.add(message);
        }

        private List<String> infos() {
            return Collections.unmodifiableList(infos);
        }

        private boolean anyWarnContains(String fragment) {
            for (String warn : warns) {
                if (warn.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 被任意基线行引用的 run 永不删除，它不占用删除名额，也不阻断同轮其它 run 的清理。 */
    @Test
    public void shouldSkipRunsReferencedByBaseline() {
        for (int i = 0; i < 5; i++) {
            seedTerminalRun(i, EvalStore.STATUS_COMPLETED,
                    String.format("2026-10-26 12:00:%02d", i), null);
        }
        assertEquals("基线锚定最旧终态 run", EvalStore.WriteResult.APPLIED,
                store.insertBaseline(baselineRow("b-old", runId(0), 1)));

        RecordingLogger logger = new RecordingLogger();
        EvalRetentionService service = new EvalRetentionService(store, config, logger);
        EvalRetentionService.RetentionResult result = service.retain(2);

        assertEquals("最旧的 3 条里只有 2 条可删", 2, result.deletedCount());
        assertEquals("被基线引用的 run 计入跳过", 1, result.skippedCount());
        assertEquals(0, result.failedCount());
        assertFalse("跳过不是失败，本轮不中止", result.isAborted());
        assertNotNull("被基线引用的 run 永不删除", store.findRun(runId(0)));
        assertEquals("被基线引用的子行也不删", 1, store.findCaseResults(runId(0)).size());
        assertEquals("基线行永不清理", 1L,
                storage.count("SELECT COUNT(*) FROM " + EvalSchemaService.TABLE_BASELINE));
        assertNull(store.findRun(runId(1)));
        assertNull(store.findRun(runId(2)));
        assertTrue("跳过必须记 WARN", logger.anyWarnContains("基线"));
        assertTrue("清理结束必须记 Info", logger.infos().size() > 0);
    }

    /** RUNNING 不在删除范围：既不计入保留窗口，也不因清理被删。 */
    @Test
    public void shouldNeverDeleteRunningRuns() {
        String runningRunId = runId(9);
        assertTrue(store.insertRunning(runningRunId, EvalStore.TIER_SMOKE,
                "2026-10-26 11:00:00", 100, null));
        for (int i = 0; i < 5; i++) {
            seedTerminalRun(i, EvalStore.STATUS_COMPLETED,
                    String.format("2026-10-26 12:00:%02d", i), null);
        }

        RecordingLogger logger = new RecordingLogger();
        EvalRetentionService service = new EvalRetentionService(store, config, logger);
        EvalRetentionService.RetentionResult result = service.retain(2);

        assertEquals(3, result.deletedCount());
        assertNotNull("RUNNING 行不得删除", store.findRun(runningRunId));
        assertEquals(EvalStore.STATUS_RUNNING, store.findRun(runningRunId).getString("status"));
        assertNotNull("保留窗口内最新两条仍在", store.findRun(runId(3)));
        assertNotNull(store.findRun(runId(4)));
        assertNull("窗口外的终态照删", store.findRun(runId(2)));
    }

    /** 级联删报表文件：report_path 优先，缺失时回退 reports/run-<run_id>.md；只删被删 run 的文件。 */
    @Test
    public void shouldCascadeDeleteReportFile() throws Exception {
        File dataFolder = temporaryFolder.newFolder("data");
        File reportsDir = new File(dataFolder, "reports");
        assertTrue(reportsDir.mkdirs());
        // run1 用显式 report_path，run0 缺 report_path 走固定路径回退，run2 在保留窗口内
        assertTrue(new File(reportsDir, "run-" + runId(1) + ".md").createNewFile());
        assertTrue(new File(reportsDir, "run-" + runId(0) + ".md").createNewFile());
        assertTrue(new File(reportsDir, "run-" + runId(2) + ".md").createNewFile());
        seedTerminalRun(0, EvalStore.STATUS_COMPLETED, "2026-10-26 12:00:00", null);
        seedTerminalRun(1, EvalStore.STATUS_COMPLETED, "2026-10-26 12:00:01",
                "reports/run-" + runId(1) + ".md");
        seedTerminalRun(2, EvalStore.STATUS_COMPLETED, "2026-10-26 12:00:02",
                "reports/run-" + runId(2) + ".md");

        EvalRetentionService service = new EvalRetentionService(store, config, null, dataFolder);
        EvalRetentionService.RetentionResult result = service.retain(1);

        assertEquals(2, result.deletedCount());
        assertFalse("显式 report_path 的报表应删除",
                new File(reportsDir, "run-" + runId(1) + ".md").exists());
        assertFalse("缺 report_path 时按固定路径回退删除",
                new File(reportsDir, "run-" + runId(0) + ".md").exists());
        assertTrue("保留窗口内的报表不动",
                new File(reportsDir, "run-" + runId(2) + ".md").exists());
    }

    /** 报表删除失败只记 WARN：库内删除照常完成，不中止本轮。 */
    @Test
    public void shouldWarnButContinueWhenReportFileCannotBeDeleted() throws Exception {
        File dataFolder = temporaryFolder.newFolder("data");
        File reportsDir = new File(dataFolder, "reports");
        assertTrue(reportsDir.mkdirs());
        File stuck = new File(reportsDir, "run-" + runId(0) + ".md");
        assertTrue(stuck.mkdirs());
        assertTrue(new File(stuck, "child.txt").createNewFile());
        seedTerminalRun(0, EvalStore.STATUS_COMPLETED, "2026-10-26 12:00:00",
                "reports/run-" + runId(0) + ".md");
        seedTerminalRun(1, EvalStore.STATUS_COMPLETED, "2026-10-26 12:00:01", null);

        RecordingLogger logger = new RecordingLogger();
        EvalRetentionService service = new EvalRetentionService(store, config, logger, dataFolder);
        EvalRetentionService.RetentionResult result = service.retain(1);

        assertEquals(1, result.deletedCount());
        assertFalse("报表删除失败不得中止本轮", result.isAborted());
        assertTrue("报表删除失败必须记 WARN", logger.anyWarnContains("报表文件删除失败"));
        assertTrue(stuck.exists());
    }

    /** 子表删除返回 0 行即中止本轮：后续候选不再处理，run 行保持原状。 */
    @Test
    public void shouldAbortRoundWhenChildDeleteMatchesNoRows() {
        for (int i = 0; i < 3; i++) {
            seedTerminalRun(i, EvalStore.STATUS_COMPLETED,
                    String.format("2026-10-26 12:00:%02d", i), null);
        }
        // 人为制造半清理状态：最旧 run 的子行已不存在
        storage.update("DELETE FROM " + EvalSchemaService.TABLE_CASE_RESULT + " WHERE run_id = ?",
                runId(0));
        storage.update("DELETE FROM " + EvalSchemaService.TABLE_CALL + " WHERE run_id = ?",
                runId(0));

        RecordingLogger logger = new RecordingLogger();
        EvalRetentionService service = new EvalRetentionService(store, config, logger);
        EvalRetentionService.RetentionResult result = service.retain(1);

        assertEquals("0 行删除不算已删", 0, result.deletedCount());
        assertEquals(1, result.failedCount());
        assertTrue(result.isAborted());
        assertNotNull("0 行时必须保留 run 行", store.findRun(runId(0)));
        assertNotNull("中止后不再处理下一条候选", store.findRun(runId(1)));
        assertEquals("下一条候选的子行也不得被删", 1, store.findCaseResults(runId(1)).size());
        assertTrue("必须记 WARN", logger.anyWarnContains("中止"));
    }

    /** 配置里的 evalKeepRuns 越界按 5..200 钳制后使用；程序内传入值也做防御性钳制。 */
    @Test
    public void shouldClampKeepRuns() {
        Map<String, String> raw = new LinkedHashMap<String, String>();
        raw.put("evalKeepRuns", "2");
        EvalConfig clamped = EvalConfig.fromValues(raw);
        assertEquals("配置下限 5", 5, clamped.keepRuns());

        for (int i = 0; i < 7; i++) {
            seedTerminalRun(i, EvalStore.STATUS_COMPLETED,
                    String.format("2026-10-26 13:00:%02d", i), null);
        }
        RecordingLogger logger = new RecordingLogger();
        EvalRetentionService service = new EvalRetentionService(store, clamped, logger);
        EvalRetentionService.RetentionResult result = service.retain();
        assertEquals("7 条终态只保留钳制后的 5 条", 2, result.deletedCount());
        assertNotNull(store.findRun(runId(2)));
        assertNull(store.findRun(runId(1)));

        RecordingLogger lowLogger = new RecordingLogger();
        EvalRetentionService lowService = new EvalRetentionService(store, config, lowLogger);
        EvalRetentionService.RetentionResult lowResult = lowService.retain(0);
        assertEquals("非正数按 1 保留", 1, lowResult.keepRuns());
        assertTrue(lowLogger.anyWarnContains("非法"));
        assertNotNull("按 1 保留时最新一条仍在", store.findRun(runId(6)));

        RecordingLogger highLogger = new RecordingLogger();
        EvalRetentionService highService = new EvalRetentionService(store, config, highLogger);
        EvalRetentionService.RetentionResult highResult = highService.retain(500);
        assertEquals("超过 200 按 200 保留", 200, highResult.keepRuns());
        assertEquals("库内行数不足 200 时不删", 0, highResult.deletedCount());
        assertTrue(highLogger.anyWarnContains("上限"));
    }

    /** 基线引用查询失败时按「已引用」保守跳过并记 WARN，不得误删、也不得中止。 */
    @Test
    public void shouldTreatBaselineLookupFailureAsReferenced() {
        for (int i = 0; i < 4; i++) {
            seedTerminalRun(i, EvalStore.STATUS_COMPLETED,
                    String.format("2026-10-26 14:00:%02d", i), null);
        }
        EvalStore failingStore = new EvalStore(new FailingBaselineStorage(storage), null);
        RecordingLogger logger = new RecordingLogger();
        EvalRetentionService service = new EvalRetentionService(failingStore, config, logger);

        EvalRetentionService.RetentionResult result = service.retain(2);

        assertEquals("基线查询失败时不得删除", 0, result.deletedCount());
        assertEquals(2, result.skippedCount());
        assertFalse("保守跳过不算失败", result.isAborted());
        assertNotNull(store.findRun(runId(0)));
        assertEquals(1, store.findCaseResults(runId(0)).size());
        assertTrue(logger.anyWarnContains("基线"));
    }

    /** 基线引用查询抛异常的存储包装：只让基线 SQL 失败，其余委托给真实假存储。 */
    private static final class FailingBaselineStorage implements StorageService {
        private final StorageService delegate;

        private FailingBaselineStorage(StorageService delegate) {
            this.delegate = delegate;
        }

        @Override
        public String get(Plugin plugin, String key) {
            return delegate.get(plugin, key);
        }

        @Override
        public void set(Plugin plugin, String key, String value) {
            delegate.set(plugin, key, value);
        }

        @Override
        public void remove(Plugin plugin, String key) {
            delegate.remove(plugin, key);
        }

        @Override
        public List<JSONObject> query(String sql, Object... params) {
            if (sql.contains(EvalSchemaService.TABLE_BASELINE)) {
                throw new IllegalStateException("模拟基线查询失败");
            }
            return delegate.query(sql, params);
        }

        @Override
        public JSONObject queryOne(String sql, Object... params) {
            if (sql.contains(EvalSchemaService.TABLE_BASELINE)) {
                throw new IllegalStateException("模拟基线查询失败");
            }
            return delegate.queryOne(sql, params);
        }

        @Override
        public int update(String sql, Object... params) {
            return delegate.update(sql, params);
        }

        @Override
        public long insert(String sql, Object... params) {
            return delegate.insert(sql, params);
        }
    }
}