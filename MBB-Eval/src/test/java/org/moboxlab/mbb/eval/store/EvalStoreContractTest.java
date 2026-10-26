package org.moboxlab.mbb.eval.store;

import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * EvalStore 对外门面的契约测试（规格 §4.7 ST1–ST10、§15.6 F1/F7/F8/F12）。
 *
 * <p>每个用例对应一条 ST 契约，使用真实临时 SQLite 库（{@link EvalStorageFake}），
 * 验证失败语义（update=-1、部分行、唯一性冲突）被收敛成明确返回值或警告，而不是静默丢数据。</p>
 */
public class EvalStoreContractTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;

    @Before
    public void setUp() throws Exception {
        File dbFile = temporaryFolder.newFile("eval-contract.db");
        storage = new EvalStorageFake(dbFile);
        assertTrue("建表失败", new EvalSchemaService(storage, null).ensureSchema());
        store = new EvalStore(storage, null);
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

    private JSONObject runRow(int seq) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId(seq));
        row.put("tier", EvalStore.TIER_SMOKE);
        row.put("started_at", "2026-10-26 12:00:00");
        row.put("calls_budget", Integer.valueOf(100));
        row.put("eval_plugin_version", "V0.1.0.0.1618");
        return row;
    }

    private JSONObject caseRow(int seq, String caseId, double score) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId(seq));
        row.put("case_id", caseId);
        row.put("dimension", "persona");
        row.put("status", "ok");
        row.put("score", Double.valueOf(score));
        row.put("max_score", Double.valueOf(100));
        row.put("evidence", "证据-" + caseId);
        row.put("detail_json", "{}");
        row.put("created_at", EvalStore.nowText());
        return row;
    }

    private JSONObject callRow(int seq, String caseId, String stage, String status) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId(seq));
        row.put("case_id", caseId);
        row.put("stage", stage);
        row.put("sample_index", Integer.valueOf(1));
        row.put("attempt", Integer.valueOf(1));
        row.put("status", status);
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

    private JSONObject baselineRow(String baselineId, int seq, int active) {
        JSONObject row = new JSONObject();
        row.put("baseline_id", baselineId);
        row.put("run_id", runId(seq));
        row.put("active", Integer.valueOf(active));
        row.put("dimension_scores_json", "{}");
        row.put("total_score", Double.valueOf(80.0));
        row.put("valid_case_count", Integer.valueOf(5));
        row.put("bank_hash", "bank-hash");
        row.put("created_at", EvalStore.nowText());
        row.put("created_by", "tester");
        row.put("reason", "契约测试");
        return row;
    }

    /** 读出表结构的稳定文本，用于断言 DDL 幂等。 */
    private List<String> columnsOf(String table) {
        List<JSONObject> rows = storage.query("PRAGMA table_info(" + table + ")");
        assertFalse("读不到表结构：" + table, rows.isEmpty());
        List<String> columns = new ArrayList<String>();
        for (JSONObject row : rows) {
            columns.add(row.getString("name") + "|" + row.getString("type") + "|"
                    + row.getIntValue("notnull") + "|" + row.getString("dflt_value") + "|"
                    + row.getIntValue("pk"));
        }
        return columns;
    }

    private boolean anySqlContains(List<String> statements, String fragment) {
        for (String sql : statements) {
            if (sql.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyWarningContains(String fragment) {
        for (String warning : store.warnings()) {
            if (warning.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** ST1：DDL 幂等，两次建表结构不变，且 DDL 全部走 update（§4.1、§4.6）。 */
    @Test
    public void st1SchemaShouldBeIdempotentAndUseUpdate() {
        List<String> runBefore = columnsOf(EvalSchemaService.TABLE_RUN);
        List<String> caseBefore = columnsOf(EvalSchemaService.TABLE_CASE_RESULT);
        List<String> callBefore = columnsOf(EvalSchemaService.TABLE_CALL);
        List<String> baselineBefore = columnsOf(EvalSchemaService.TABLE_BASELINE);

        assertTrue("第二次建表应成功", new EvalSchemaService(storage, null).ensureSchema());

        assertEquals("run 表结构被改变", runBefore, columnsOf(EvalSchemaService.TABLE_RUN));
        assertEquals("case_result 表结构被改变", caseBefore, columnsOf(EvalSchemaService.TABLE_CASE_RESULT));
        assertEquals("call 表结构被改变", callBefore, columnsOf(EvalSchemaService.TABLE_CALL));
        assertEquals("baseline 表结构被改变", baselineBefore, columnsOf(EvalSchemaService.TABLE_BASELINE));

        assertTrue("DDL 必须走 update：CREATE TABLE plugin_mbb_eval_run",
                anySqlContains(storage.updateSql(), "CREATE TABLE IF NOT EXISTS " + EvalSchemaService.TABLE_RUN));
        assertTrue("DDL 必须走 update：CREATE INDEX idx_eval_case_uniq",
                anySqlContains(storage.updateSql(), "idx_eval_case_uniq"));
        assertFalse("DDL 不得走 query",
                anySqlContains(storage.querySql(), "CREATE TABLE"));
        for (String sql : storage.updateSql()) {
            assertTrue("DDL 必须含 plugin_ 且不得含 bot_：" + sql,
                    EvalSchemaService.isPluginScopedSql(sql));
        }

        // 注入 update 失败后建表必须报失败，证明 DDL 确实经由 update 生效
        storage.failNextUpdate(1);
        assertFalse("update 返回 -1 时建表必须报失败",
                new EvalSchemaService(storage, null).ensureSchema());
    }

    /** ST2：RUNNING→COMPLETED CAS 成功，重复 CAS 返回 0 且不改写终态字段。 */
    @Test
    public void st2CompletedCasShouldBeIdempotent() {
        String runId = runId(1);
        assertTrue(store.insertRunning(runId, EvalStore.TIER_SMOKE, "2026-10-26 12:00:00", 100,
                "V0.1.0.0.1618"));

        assertEquals(1, store.casCompleted(runId, "2026-10-26 12:00:10", 10000L, "reports/a.md"));
        JSONObject row = store.findRun(runId);
        assertNotNull(row);
        assertEquals(EvalStore.STATUS_COMPLETED, row.getString("status"));
        assertEquals("2026-10-26 12:00:10", row.getString("ended_at"));
        assertEquals(10000L, row.getLongValue("duration_ms"));
        assertEquals("reports/a.md", row.getString("report_path"));

        assertEquals("重复 CAS 必须返回 0", 0,
                store.casCompleted(runId, "2026-10-26 13:00:00", 99999L, "reports/b.md"));
        JSONObject again = store.findRun(runId);
        assertEquals("重复 CAS 不得改写 ended_at", "2026-10-26 12:00:10", again.getString("ended_at"));
        assertEquals("重复 CAS 不得改写 duration_ms", 10000L, again.getLongValue("duration_ms"));
        assertEquals("重复 CAS 不得改写 report_path", "reports/a.md", again.getString("report_path"));
    }

    /** ST3：CAS 的 update 返回 -1 时不标终态，记 WARN 等下一轮重试；重试成功。 */
    @Test
    public void st3NotExecutedCasShouldKeepRunning() {
        String runId = runId(2);
        assertTrue(store.insertRunning(runId, EvalStore.TIER_SMOKE, "2026-10-26 12:00:00", 100, null));

        storage.failUpdateContaining("status = 'COMPLETED'", 1);
        assertEquals("update=-1 必须原样返回", -1,
                store.casCompleted(runId, "2026-10-26 12:00:10", 10000L, "reports/a.md"));
        assertEquals("语句未执行时不得标终态", EvalStore.STATUS_RUNNING,
                store.findRun(runId).getString("status"));
        assertTrue("未执行的 CAS 必须记 WARN", anyWarningContains("终态 CAS 未执行"));

        assertEquals("下一轮重试应成功", 1,
                store.casCompleted(runId, "2026-10-26 12:00:11", 11000L, "reports/a.md"));
        assertEquals(EvalStore.STATUS_COMPLETED, store.findRun(runId).getString("status"));
    }

    /** ST4：同一 (run_id, case_id) 二次插入失败，旧行不被覆盖（§4.4）。 */
    @Test
    public void st4DuplicateCaseResultShouldNotOverwrite() {
        String runId = runId(3);
        assertTrue(store.insertRunning(runId, EvalStore.TIER_SMOKE, "2026-10-26 12:00:00", 100, null));

        JSONObject first = caseRow(3, "case-1", 88.0);
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(first));

        JSONObject second = caseRow(3, "case-1", 10.0);
        second.put("evidence", "第二份证据");
        EvalStore.WriteResult result = store.insertCaseResult(second);
        // SQLite 把唯一键冲突上报为语句失败，因此失败可能是 NO_MATCH 或 NOT_EXECUTED
        assertFalse("二次插入必须失败", result == EvalStore.WriteResult.APPLIED);

        JSONObject row = store.findCaseResult(runId, "case-1");
        assertNotNull(row);
        assertEquals("旧行 score 不得被覆盖", 88.0, row.getDoubleValue("score"), 0.001);
        assertEquals("旧行证据不得被覆盖", "证据-case-1", row.getString("evidence"));
        assertEquals("同 run 下只允许一行", 1L,
                storage.count("SELECT COUNT(*) FROM " + EvalSchemaService.TABLE_CASE_RESULT
                        + " WHERE run_id = ? AND case_id = ?", runId, "case-1"));
    }

    /** ST5：每次模型调用即落 _call，失败调用与 cached 调用都要留行（§4.4）。 */
    @Test
    public void st5EveryCallShouldBePersisted() {
        String runId = runId(4);
        assertTrue(store.insertRunning(runId, EvalStore.TIER_SMOKE, "2026-10-26 12:00:00", 100, null));

        JSONObject ok = callRow(4, "case-1", "judge", "ok");
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCall(ok));
        assertEquals("首次调用应立即落库", 1, store.findCalls(runId).size());

        JSONObject failed = callRow(4, "case-1", "generation", "error");
        failed.put("error_type", "timeout");
        failed.put("retryable", Integer.valueOf(1));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCall(failed));

        JSONObject cached = callRow(4, "case-2", "judge", "ok");
        cached.put("cached", Integer.valueOf(1));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCall(cached));

        EvalStore.RowSet calls = store.findCalls(runId);
        assertEquals("失败调用与 cached 调用都必须留行", 3, calls.size());
        assertTrue(calls.isComplete());
        int cachedRows = 0;
        int failedRows = 0;
        for (JSONObject row : calls.rows()) {
            if (row.getIntValue("cached") == 1) {
                cachedRows++;
            }
            if ("error".equals(row.getString("status"))) {
                failedRows++;
            }
        }
        assertEquals(1, cachedRows);
        assertEquals(1, failedRows);
    }

    /** ST6：只允许一条 active=1 基线；历史行 active=0 可多条；切换走「先退役、再激活」。 */
    @Test
    public void st6OnlyOneActiveBaselineAllowed() {
        assertTrue(store.insertRunning(runId(5), EvalStore.TIER_SMOKE, "2026-10-26 12:00:00", 100, null));
        assertTrue(store.insertRunning(runId(6), EvalStore.TIER_SMOKE, "2026-10-26 12:00:00", 100, null));

        assertEquals("第一条 active=1 应写入成功",
                EvalStore.WriteResult.APPLIED, store.insertBaseline(baselineRow("b-1", 5, 1)));
        EvalStore.WriteResult second = store.insertBaseline(baselineRow("b-2", 6, 1));
        assertFalse("第二条 active=1 必须失败", second == EvalStore.WriteResult.APPLIED);
        assertEquals("active=1 只允许一行", 1L,
                storage.count("SELECT COUNT(*) FROM " + EvalSchemaService.TABLE_BASELINE
                        + " WHERE active = 1"));
        assertNull("失败的基线行不得落库", store.findBaseline("b-2"));

        assertEquals("active=0 可多条（一）", EvalStore.WriteResult.APPLIED,
                store.insertBaseline(baselineRow("b-3", 5, 0)));
        assertEquals("active=0 可多条（二）", EvalStore.WriteResult.APPLIED,
                store.insertBaseline(baselineRow("b-4", 6, 0)));
        assertEquals(3L, storage.count("SELECT COUNT(*) FROM " + EvalSchemaService.TABLE_BASELINE));
        assertEquals("历史基线按 created_at 倒序可查", 3, store.findBaselines(10).size());

        // §8.1 无事务切换：① 新行 active=0 已写入；② 退役旧 active 并写 superseded_by；③ 激活新行
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertBaseline(baselineRow("b-5", 6, 0)));
        assertEquals(EvalStore.WriteResult.APPLIED, store.supersedeActiveBaseline("b-5"));
        JSONObject retired = store.findBaseline("b-1");
        assertEquals(0, retired.getIntValue("active"));
        assertEquals("b-5", retired.getString("superseded_by"));

        assertEquals(EvalStore.WriteResult.APPLIED, store.activateBaseline("b-5"));
        JSONObject active = store.findActiveBaseline();
        assertNotNull("切换后必须有唯一 active 基线", active);
        assertEquals("b-5", active.getString("baseline_id"));
        assertEquals("b-5", store.findBaseline("b-5").getString("baseline_id"));
        assertEquals("active=1 仍只允许一行", 1L,
                storage.count("SELECT COUNT(*) FROM " + EvalSchemaService.TABLE_BASELINE
                        + " WHERE active = 1"));
    }

    /** ST7：超额时最旧终态 run 进入清理候选；被基线引用者与 RUNNING 者不受影响。 */
    @Test
    public void st7RetentionShouldSpareBaselineAndRunning() {
        for (int i = 0; i < 12; i++) {
            String runId = runId(i);
            String startedAt = String.format("2026-10-26 12:00:%02d", i);
            assertTrue("准备终态 run：" + runId,
                    store.insertRunning(runId, EvalStore.TIER_SMOKE, startedAt, 100, null));
            assertEquals(1, store.casCompleted(runId, startedAt, 1000L, "reports/" + runId + ".md"));
        }
        String runningRunId = runId(20);
        assertTrue(store.insertRunning(runningRunId, EvalStore.TIER_SMOKE, "2026-10-26 13:00:00", 100, null));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(caseRow(1, "case-1", 80.0)));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCall(callRow(1, "case-1", "judge", "ok")));
        assertEquals("基线锚定最旧终态 run", EvalStore.WriteResult.APPLIED,
                store.insertBaseline(baselineRow("b-old", 0, 1)));

        List<JSONObject> candidates = store.findTerminalRunsBeyond(3);
        assertEquals("12 条终态行、保留 3 条，候选应为 9 条", 9, candidates.size());
        for (JSONObject row : candidates) {
            assertFalse("RUNNING 行不得进入清理候选", EvalStore.STATUS_RUNNING.equals(row.getString("status")));
        }
        assertEquals("候选按 started_at 由新到旧，最旧者排最后", runId(0),
                candidates.get(candidates.size() - 1).getString("run_id"));

        // 基线豁免：删除被引用的 run 必须不生效，行仍在
        assertTrue(store.isRunReferencedByBaseline(runId(0)));
        assertFalse(store.isRunReferencedByBaseline(runId(1)));
        assertEquals(EvalStore.WriteResult.NO_MATCH, store.deleteRun(runId(0)));
        assertNotNull("被基线引用的 run 永不删除", store.findRun(runId(0)));

        // 正常清理：先删子行，再删父行
        assertEquals(EvalStore.WriteResult.APPLIED, store.deleteCaseResults(runId(1)));
        assertEquals(EvalStore.WriteResult.APPLIED, store.deleteCalls(runId(1)));
        assertEquals(EvalStore.WriteResult.APPLIED, store.deleteRun(runId(1)));
        assertNull("终态 run 应被删除", store.findRun(runId(1)));

        // RUNNING 行不受清理影响
        assertEquals(EvalStore.WriteResult.NO_MATCH, store.deleteRun(runningRunId));
        assertNotNull("RUNNING 行不得删除", store.findRun(runningRunId));
        assertEquals("基线行本身永不清理", 1L,
                storage.count("SELECT COUNT(*) FROM " + EvalSchemaService.TABLE_BASELINE));
    }

    /** ST8：query 只返回部分行时给出明确信号，缺失 case 不得静默计 0。 */
    @Test
    public void st8PartialQueryShouldBeExposedNotScoredAsZero() {
        assertTrue(store.insertRunning(runId(7), EvalStore.TIER_SMOKE, "2026-10-26 12:00:00", 100, null));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(caseRow(7, "case-1", 90.0)));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(caseRow(7, "case-2", 60.0)));
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(caseRow(7, "case-3", 30.0)));

        storage.partialNextQuery(1);
        EvalStore.RowSet rows = store.findCaseResults(runId(7));
        assertEquals("只读到 1 行", 1, rows.size());
        assertEquals("库中应有 3 行", 3, rows.expectedCount());
        assertFalse("部分行必须标记为不完整", rows.isComplete());
        assertFalse("部分行不是查询异常", rows.isQueryFailed());
        assertTrue("部分行必须记 WARN", anyWarningContains("部分行"));
        assertEquals("不得用 0 分补行，缺失项由调用方标数据缺失", 1, rows.size());
    }

    /** ST9：detail_json 非法时按空对象降级并记 WARN，行仍可读；合法 JSON 正常解析。 */
    @Test
    public void st9InvalidDetailJsonShouldDegrade() {
        assertTrue(store.insertRunning(runId(8), EvalStore.TIER_SMOKE, "2026-10-26 12:00:00", 100, null));
        JSONObject broken = caseRow(8, "case-1", 70.0);
        broken.put("detail_json", "{不是 JSON");
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(broken));
        JSONObject valid = caseRow(8, "case-2", 80.0);
        valid.put("detail_json", "{\"samples\":[{\"score\":80}]}");
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(valid));

        JSONObject brokenRow = store.findCaseResult(runId(8), "case-1");
        assertNotNull("非法 detail_json 的行必须仍可读", brokenRow);
        assertEquals("{不是 JSON", brokenRow.getString("detail_json"));
        JSONObject degraded = store.parseDetailJson(brokenRow);
        assertNotNull("降级结果不能为 null", degraded);
        assertTrue("非法 JSON 按空对象降级", degraded.isEmpty());
        assertTrue("降级必须记 WARN", anyWarningContains("detail_json"));

        JSONObject parsed = store.parseDetailJson(store.findCaseResult(runId(8), "case-2"));
        assertEquals(1, parsed.getJSONArray("samples").size());
        assertEquals(80, parsed.getJSONArray("samples").getJSONObject(0).getIntValue("score"));
    }

    /** ST10：早于 35 分钟的 RUNNING 行可被选出并置 ABORTED，行本身仍在（§4.5 init 回收）。 */
    @Test
    public void st10StaleRunningRowsShouldBeSelectableAndAborted() {
        String oldRunId = runId(9);
        assertTrue(store.insertRunning(oldRunId, EvalStore.TIER_SMOKE, "2026-10-26 11:00:00", 100, null));
        assertTrue(store.insertRunning(runId(10), EvalStore.TIER_SMOKE, "2026-10-26 12:10:00", 100, null));

        assertEquals("最近运行行含 RUNNING", 2, store.findRecentRuns(5).size());

        List<JSONObject> stale = store.findRunningRunsStartedBefore("2026-10-26 11:35:00");
        assertEquals("只选出早于阈值的 RUNNING 行", 1, stale.size());
        assertEquals(oldRunId, stale.get(0).getString("run_id"));

        assertEquals(1, store.casAborted(oldRunId, EvalStore.nowText(), 0L, "进程中断回收"));
        JSONObject aborted = store.findRun(oldRunId);
        assertNotNull("回收只置 ABORTED，不删除行", aborted);
        assertEquals(EvalStore.STATUS_ABORTED, aborted.getString("status"));
        assertEquals("进程中断回收", aborted.getString("error_summary"));
        assertEquals("已回收的行不再进入候选", 0,
                store.findRunningRunsStartedBefore("2026-10-26 11:35:00").size());
    }
}
