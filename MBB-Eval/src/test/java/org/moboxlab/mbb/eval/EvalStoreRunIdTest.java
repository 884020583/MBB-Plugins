package org.moboxlab.mbb.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.Storage.StorageService;

import com.alibaba.fastjson.JSONObject;

/**
 * run_id 正则与 CAS SQL 用例（A-5：run_id 正则）。
 *
 * <p>覆盖 §4.3 的格式、字符集与长度，§4.3 的主键冲突重生成，以及 §4.4 终态 CAS 的 SQL 形状
 * 与返回值语义（-1 未执行 / 0 前置不满足 / 正数已更新）。用可配置假存储避免测试依赖真实数据库。</p>
 */
public class EvalStoreRunIdTest {

    private static final String RUN_ID_PATTERN = "r-\\d{14}-(smoke|full)-[a-z0-9]{4}";

    /** §4.3 冻结形状的合法 run_id，供 CAS / 建行用例复用。 */
    private static final String SAMPLE_RUN_ID = "r-20261026153000-smoke-ab12";

    @Test
    public void validRunIdsShouldPass() {
        assertTrue(EvalStore.isValidRunId("r-20261026153000-smoke-ab12"));
        assertTrue(EvalStore.isValidRunId("r-20261026153000-full-0012"));
        assertTrue("长度上限 48 内应通过", EvalStore.isValidRunId("r-20261026153000-full-abcd"));
    }

    @Test
    public void invalidRunIdsShouldFail() {
        assertFalse(EvalStore.isValidRunId(null));
        assertFalse(EvalStore.isValidRunId(""));
        assertFalse("缺档位", EvalStore.isValidRunId("r-20261026153000-ab12"));
        assertFalse("档位大小写敏感", EvalStore.isValidRunId("r-20261026153000-SMOKE-ab12"));
        assertFalse("随机段长度必须为 4", EvalStore.isValidRunId("r-20261026153000-smoke-ab1"));
        assertFalse("随机段只用小写与数字", EvalStore.isValidRunId("r-20261026153000-smoke-AB12"));
        assertFalse("时间戳必须 14 位", EvalStore.isValidRunId("r-2026102615-smoke-ab12"));
        assertFalse("不得含空格", EvalStore.isValidRunId("r-20261026153000-smoke-ab 2"));
        StringBuilder tooLong = new StringBuilder("r-20261026153000-smoke-");
        for (int i = 0; i < 40; i++) {
            tooLong.append('a');
        }
        assertFalse("总长不得超过 48", EvalStore.isValidRunId(tooLong.toString()));
    }

    @Test
    public void generatedRunIdShouldMatchFormatAndBeUnique() {
        FakeStorageService storage = new FakeStorageService();
        EvalStore store = new EvalStore(storage, null);
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < 20; i++) {
            String smoke = store.generateRunId(EvalStore.TIER_SMOKE);
            assertTrue("run_id 格式：" + smoke, smoke.matches(RUN_ID_PATTERN));
            assertTrue("run_id 应唯一：" + smoke, seen.add(smoke));
            String full = store.generateRunId(EvalStore.TIER_FULL);
            assertTrue("run_id 格式：" + full, full.matches(RUN_ID_PATTERN));
            assertTrue("run_id 应唯一：" + full, seen.add(full));
        }
        assertEquals("每个 run_id 生成后都要查重一次", 40, storage.queryCount);
        assertNotNull(EvalStore.nowText());
        assertTrue("时间列格式应可比较", EvalStore.nowText().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    public void generateRunIdShouldRegenerateWhenTaken() {
        FakeStorageService storage = new FakeStorageService();
        storage.conflictsRemaining = 2;
        EvalStore store = new EvalStore(storage, null);
        String runId = store.generateRunId(EvalStore.TIER_SMOKE);
        assertTrue("冲突后仍应产出合法 run_id：" + runId, EvalStore.isValidRunId(runId));
        assertEquals("每次冲突都要重新查询", 3, storage.queryCount);
    }

    @Test
    public void generateRunIdShouldGiveUpAfterMaxAttempts() {
        FakeStorageService storage = new FakeStorageService();
        storage.conflictsRemaining = 100;
        EvalStore store = new EvalStore(storage, null);
        try {
            store.generateRunId(EvalStore.TIER_SMOKE);
            fail("冲突持续时应放弃并抛异常");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("run_id"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownTierShouldThrow() {
        new EvalStore(new FakeStorageService(), null).generateRunId("bench");
    }

    @Test
    public void insertRunningShouldReportStorageEffect() {
        FakeStorageService storage = new FakeStorageService();
        EvalStore store = new EvalStore(storage, null);
        assertTrue("update 返回 1 视为建行成功",
                store.insertRunning(SAMPLE_RUN_ID, EvalStore.TIER_SMOKE, EvalStore.nowText(), 100, "V0.1.0.0.1528"));
        assertTrue("INSERT 必须落 plugin_mbb_eval_run：" + storage.lastSql,
                storage.lastSql.startsWith("INSERT INTO plugin_mbb_eval_run"));
        assertEquals(SAMPLE_RUN_ID, storage.lastParams[0]);
        assertEquals(EvalStore.TIER_SMOKE, storage.lastParams[1]);
        assertEquals(100, ((Integer) storage.lastParams[3]).intValue());

        storage.updateResult = 0;
        assertFalse("update 返回 0 视为未写入", store.insertRunning(
                "r-20261026153000-smoke-ab34", EvalStore.TIER_SMOKE, EvalStore.nowText(), 100, "V0.1.0.0.1528"));
        storage.updateResult = -1;
        assertFalse("update 返回 -1 视为未执行、效果未知", store.insertRunning(
                "r-20261026153000-smoke-ab56", EvalStore.TIER_SMOKE, EvalStore.nowText(), 100, "V0.1.0.0.1528"));
    }

    @Test
    public void casShouldPassThroughStorageResult() {
        FakeStorageService storage = new FakeStorageService();
        EvalStore store = new EvalStore(storage, null);
        storage.updateResult = 1;
        assertEquals(1, store.casCompleted(SAMPLE_RUN_ID, EvalStore.nowText(), 1234L, null));
        assertTrue("CAS 必须带 RUNNING 前置条件", storage.lastSql.contains("WHERE run_id = ? AND status = 'RUNNING'"));
        storage.updateResult = 0;
        assertEquals("0 表示前置条件不满足（已终态）", 0, store.casAborted(SAMPLE_RUN_ID, null, 0L, EvalStore.ABORT_REASON_SHUTDOWN));
        storage.updateResult = -1;
        assertEquals("-1 表示未执行、效果未知", -1, store.casFailed(SAMPLE_RUN_ID, null, 0L, "boom"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidRunIdShouldBeRejectedOnInsert() {
        new EvalStore(new FakeStorageService(), null)
                .insertRunning("bad-id", EvalStore.TIER_SMOKE, EvalStore.nowText(), 100, "V0.1.0.0.1528");
    }

    @Test
    public void casSqlShouldMatchSpecShape() {
        List<String> statements = EvalStore.sqlStatements();
        assertTrue("应包含建行 SQL", contains(statements, "INSERT INTO plugin_mbb_eval_run"));
        assertTrue("应包含终态 CAS 的前置条件", contains(statements, "WHERE run_id = ? AND status = 'RUNNING'"));
        assertTrue("应包含 run_id 查重", contains(statements, "SELECT run_id FROM plugin_mbb_eval_run WHERE run_id = ?"));
        assertTrue("应包含三态 CAS", contains(statements, "status = 'COMPLETED'")
                && contains(statements, "status = 'FAILED'")
                && contains(statements, "status = 'ABORTED'"));
        for (String sql : statements) {
            assertTrue("SQL 必须含 plugin_ 且不得含 bot_：" + sql, EvalSchemaService.isPluginScopedSql(sql));
        }
        assertEquals("user-abort", EvalStore.ABORT_REASON_USER);
        assertEquals("budget", EvalStore.ABORT_REASON_BUDGET);
        assertEquals("deadline", EvalStore.ABORT_REASON_DEADLINE);
        assertEquals("shutdown", EvalStore.ABORT_REASON_SHUTDOWN);
    }

    private static boolean contains(List<String> statements, String needle) {
        for (String sql : statements) {
            if (sql.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 可配置假存储：update 返回值、查重冲突次数可调，并记录最后一次 SQL 与参数。 */
    private static final class FakeStorageService implements StorageService {
        private int updateResult = 1;
        private int conflictsRemaining = 0;
        private int queryCount = 0;
        private String lastSql = "";
        private Object[] lastParams = new Object[0];

        @Override
        public String get(Plugin plugin, String key) {
            return null;
        }

        @Override
        public void set(Plugin plugin, String key, String value) {
        }

        @Override
        public void remove(Plugin plugin, String key) {
        }

        @Override
        public List<JSONObject> query(String sql, Object... params) {
            queryCount++;
            List<JSONObject> rows = new ArrayList<JSONObject>();
            if (conflictsRemaining > 0) {
                conflictsRemaining--;
                JSONObject row = new JSONObject();
                row.put("run_id", "r-20261026153000-smoke-zzzz");
                rows.add(row);
            }
            return rows;
        }

        @Override
        public JSONObject queryOne(String sql, Object... params) {
            return null;
        }

        @Override
        public int update(String sql, Object... params) {
            lastSql = sql;
            lastParams = params;
            return updateResult;
        }

        @Override
        public long insert(String sql, Object... params) {
            return 1;
        }
    }
}
