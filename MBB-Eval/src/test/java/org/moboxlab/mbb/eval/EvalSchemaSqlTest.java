package org.moboxlab.mbb.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.moboxlab.mbb.eval.store.EvalSchemaService;

/**
 * SQL 文本约束用例（A-5：SQL 文本；对应 A8）。
 *
 * <p>只读断言，不连数据库：四表七索引齐全、索引名与唯一性与部分谓词按 §4.2 冻结值逐条锁定、
 * 每句 SQL 都含 {@code plugin_} 且不含 {@code bot_}、无 DROP / RENAME。DDL 一律经
 * {@code update()} 执行由 {@link EvalSchemaService#ensureSchema()} 的实现保证，测试从源码侧守住文本边界。</p>
 */
public class EvalSchemaSqlTest {

    private static final String[] TABLES = {
            "plugin_mbb_eval_run",
            "plugin_mbb_eval_case_result",
            "plugin_mbb_eval_call",
            "plugin_mbb_eval_baseline"
    };

    /** §4.2.1–§4.2.5 冻结的 7 个索引名。 */
    private static final List<String> INDEX_NAMES = Arrays.asList(
            "idx_eval_run_started",
            "idx_eval_run_status",
            "idx_eval_case_uniq",
            "idx_eval_case_dim",
            "idx_eval_call_run",
            "idx_eval_call_stage",
            "idx_eval_baseline_active");

    @Test
    public void fourTablesAndSevenIndexesShouldBeRegistered() {
        assertEquals(4, EvalSchemaService.tableNames().size());
        assertEquals(4, EvalSchemaService.createTableSql().size());
        assertEquals(7, EvalSchemaService.createIndexSql().size());
        for (String table : TABLES) {
            assertTrue("缺少表：" + table, EvalSchemaService.tableNames().contains(table));
        }
    }

    @Test
    public void indexNamesAndUniquenessShouldBeFrozen() {
        Map<String, String> byName = new java.util.LinkedHashMap<String, String>();
        for (String sql : EvalSchemaService.createIndexSql()) {
            for (String name : INDEX_NAMES) {
                if (sql.contains(" " + name + " ")) {
                    byName.put(name, sql);
                }
            }
        }
        assertEquals("7 个索引名应逐个命中 §4.2", 7, byName.size());
        assertUnique(byName, "idx_eval_case_uniq", true);
        assertUnique(byName, "idx_eval_baseline_active", true);
        assertUnique(byName, "idx_eval_run_started", false);
        assertUnique(byName, "idx_eval_run_status", false);
        assertUnique(byName, "idx_eval_case_dim", false);
        assertUnique(byName, "idx_eval_call_run", false);
        assertUnique(byName, "idx_eval_call_stage", false);
        assertTrue("case 唯一索引应为 (run_id, case_id)："
                        + byName.get("idx_eval_case_uniq"),
                byName.get("idx_eval_case_uniq").contains("(run_id, case_id)")
                        || byName.get("idx_eval_case_uniq").contains("(run_id,case_id)"));
        assertTrue("baseline 活动索引应带部分谓词："
                        + byName.get("idx_eval_baseline_active"),
                byName.get("idx_eval_baseline_active").contains("WHERE active = 1"));
    }

    private static void assertUnique(Map<String, String> byName, String indexName, boolean unique) {
        String sql = byName.get(indexName);
        assertNotNull("缺少索引：" + indexName, sql);
        boolean actualUnique = sql.contains("CREATE UNIQUE INDEX");
        assertEquals("索引唯一性应匹配 §4.2：" + indexName, unique, actualUnique);
    }

    @Test
    public void everySqlShouldStayInPluginScope() {
        List<String> all = EvalSchemaService.allSql();
        int columnCount = 0;
        for (Map<String, String> definitions : EvalSchemaService.expectedColumns().values()) {
            columnCount += definitions.size();
        }
        assertEquals("四表 + 七索引 + 全部补列定义",
                4 + 7 + columnCount, all.size());
        for (String sql : all) {
            assertTrue("SQL 必须含 plugin_ 且不得含 bot_：" + sql, EvalSchemaService.isPluginScopedSql(sql));
            assertFalse("SQL 不得出现 bot_：" + sql, sql.toLowerCase().contains("bot_"));
        }
    }

    @Test
    public void createTableShouldBeIdempotentAndComplete() {
        for (String sql : EvalSchemaService.createTableSql()) {
            assertTrue("建表必须 IF NOT EXISTS：" + sql, sql.startsWith("CREATE TABLE IF NOT EXISTS "));
            assertFalse("建表不得 DROP：" + sql, sql.toUpperCase().contains("DROP "));
            assertFalse("建表不得 RENAME：" + sql, sql.toUpperCase().contains("RENAME "));
        }
        for (String sql : EvalSchemaService.createIndexSql()) {
            String upper = sql.toUpperCase();
            assertTrue("建索引必须 IF NOT EXISTS：" + sql, upper.startsWith("CREATE INDEX IF NOT EXISTS ")
                    || upper.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS "));
            assertTrue("索引名必须以 idx_eval_ 开头：" + sql, sql.contains("idx_eval_"));
        }
    }

    @Test
    public void frozenColumnsShouldKeepSpecDefaults() {
        Map<String, Map<String, String>> columns = EvalSchemaService.expectedColumns();
        assertEquals(4, columns.size());
        Map<String, String> run = columns.get("plugin_mbb_eval_run");
        assertNotNull("缺少 run 表列定义", run);
        assertTrue("run 表缺 calls_budget", run.containsKey("calls_budget"));
        assertTrue("calls_budget 默认值应为 100：" + run.get("calls_budget"),
                run.get("calls_budget").contains("DEFAULT 100"));
        assertTrue("run 表缺 sample_k", run.containsKey("sample_k"));
        Map<String, String> caseResult = columns.get("plugin_mbb_eval_case_result");
        assertTrue("case 表缺 max_score", caseResult.containsKey("max_score"));
        assertTrue("max_score 默认值应为 100：" + caseResult.get("max_score"),
                caseResult.get("max_score").contains("DEFAULT 100"));
        Map<String, String> baseline = columns.get("plugin_mbb_eval_baseline");
        assertTrue("baseline 表缺 active", baseline.containsKey("active"));
        assertTrue("active 默认值应为 1：" + baseline.get("active"),
                baseline.get("active").contains("DEFAULT 1"));
        assertEquals("run_id", EvalSchemaService.primaryKeyColumn("plugin_mbb_eval_run"));
        assertEquals("case_result_id", EvalSchemaService.primaryKeyColumn("plugin_mbb_eval_case_result"));
        assertEquals("call_id", EvalSchemaService.primaryKeyColumn("plugin_mbb_eval_call"));
        assertEquals("baseline_id", EvalSchemaService.primaryKeyColumn("plugin_mbb_eval_baseline"));
        assertNull(EvalSchemaService.primaryKeyColumn("plugin_mbb_unknown"));
    }
}
