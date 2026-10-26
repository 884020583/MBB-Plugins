package org.moboxlab.mbb.eval.metrics;

import java.io.File;
import java.util.List;
import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.store.EvalStorageFake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * L3 只读旁证数据源的存储用例（E-4：规格 §7.5、§11.4、§15.5 E11）。
 *
 * <p>用真实临时 SQLite 库与 {@link EvalStorageFake} 验证三件事：SQL 文本只含
 * {@code plugin_}、不含 {@code bot_}、只有 SELECT；查询按 profile×日期聚合；
 * 取数过程不产生任何 update（只读）。</p>
 */
public class EvalAiUsageStorageSourceTest {

    /** 复刻 MBB-AI 的统计表结构（测试自建，不依赖 MBB-AI 插件）。 */
    private static final String CREATE_USAGE_TABLE = "CREATE TABLE IF NOT EXISTS plugin_mbb_ai_usage ("
            + "ID INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "statDate TEXT NOT NULL, profile TEXT NOT NULL, model TEXT NOT NULL,"
            + "action TEXT NOT NULL, requests INTEGER NOT NULL DEFAULT 0,"
            + "successes INTEGER NOT NULL DEFAULT 0, failures INTEGER NOT NULL DEFAULT 0,"
            + "promptTokens INTEGER NOT NULL DEFAULT 0, completionTokens INTEGER NOT NULL DEFAULT 0,"
            + "totalTokens INTEGER NOT NULL DEFAULT 0, totalLatencyMs INTEGER NOT NULL DEFAULT 0,"
            + "cachedHits INTEGER NOT NULL DEFAULT 0, cachedPromptTokens INTEGER NOT NULL DEFAULT 0,"
            + "lastRequestTime INTEGER NOT NULL DEFAULT 0, updateTime INTEGER NOT NULL DEFAULT 0,"
            + "UNIQUE(statDate, profile, model, action))";

    private static final String INSERT_USAGE = "INSERT INTO plugin_mbb_ai_usage"
            + " (statDate, profile, model, action, requests, successes, failures, promptTokens,"
            + " completionTokens, totalTokens, totalLatencyMs, cachedHits)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalAiUsageStorageSource source;

    @Before
    public void setUp() throws Exception {
        File dbFile = temporaryFolder.newFile("ai-usage.db");
        storage = new EvalStorageFake(dbFile);
        assertEquals(0, storage.update(CREATE_USAGE_TABLE));
        source = new EvalAiUsageStorageSource(storage);
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    public void shouldKeepOnlyReadOnlyPluginSql() {
        String sql = EvalAiUsageStorageSource.SQL_DAILY_USAGE;
        String lower = sql.toLowerCase(Locale.ROOT);

        assertEquals("", EvalAiUsageStorageSource.sqlViolation(sql));
        assertTrue(lower.contains("plugin_"));
        assertTrue(lower.contains("plugin_mbb_ai_usage"));
        assertFalse(lower.contains("bot_"));
        assertTrue(lower.trim().startsWith("select"));

        assertFalse("必须拒绝 bot_ 表",
                EvalAiUsageStorageSource.sqlViolation("SELECT * FROM bot_ai_usage").isEmpty());
        assertFalse("必须拒绝无 plugin_ 的 SQL",
                EvalAiUsageStorageSource.sqlViolation("SELECT * FROM ai_usage").isEmpty());
        assertFalse("必须拒绝写语句",
                EvalAiUsageStorageSource.sqlViolation(
                        "UPDATE plugin_mbb_ai_usage SET requests = 0").isEmpty());
    }

    @Test
    public void shouldAggregateByProfileAndDateWithoutWriting() {
        insert("2026-10-01", "eval", "model-a", "chat", 60, 58, 2, 600, 300, 900, 12000, 1);
        insert("2026-10-01", "eval", "model-b", "chat", 40, 38, 2, 400, 200, 600, 13000, 2);
        insert("2026-10-02", "eval", "model-a", "chat", 5, 5, 0, 50, 25, 75, 500, 0);
        insert("2026-10-01", "judge", "model-a", "judge", 99, 99, 0, 990, 0, 990, 1000, 0);
        int updatesBeforeQuery = storage.updateSql().size();

        EvalAiUsageEvidence evidence = new EvalAiUsageEvidenceService(source)
                .collect("eval", "2026-10-01", "2026-10-02");

        assertTrue(evidence.available());
        assertEquals(2, evidence.rows().size());
        EvalAiUsageDay first = evidence.rows().get(0);
        assertEquals("2026-10-01", first.statDate());
        assertEquals(100L, first.requests());
        assertEquals(96L, first.successes());
        assertEquals(4L, first.failures());
        assertEquals(1000L, first.promptTokens());
        assertEquals(500L, first.completionTokens());
        assertEquals(1500L, first.totalTokens());
        assertEquals(25000L, first.totalLatencyMs());
        assertEquals(3L, first.cachedHits());
        assertEquals("2026-10-02", evidence.rows().get(1).statDate());
        assertTrue(evidence.text().contains("不同口径，不可换算"));

        assertEquals("取数不得写库", updatesBeforeQuery, storage.updateSql().size());
        List<String> queries = storage.querySql();
        assertFalse(queries.isEmpty());
        for (String sql : queries) {
            assertTrue("只读旁证只允许 SELECT：" + sql,
                    sql.trim().toLowerCase(Locale.ROOT).startsWith("select"));
            assertTrue("SQL 必须含 plugin_：" + sql, sql.contains("plugin_"));
            assertFalse("SQL 不得含 bot_：" + sql, sql.toLowerCase(Locale.ROOT).contains("bot_"));
        }
    }

    @Test
    public void shouldReportUnavailableWhenTableIsMissing() {
        assertEquals(0, storage.update("DROP TABLE IF EXISTS plugin_mbb_ai_usage"));

        EvalAiUsageEvidence evidence = new EvalAiUsageEvidenceService(source)
                .collect("eval", "2026-10-01");

        assertFalse(evidence.available());
        assertTrue(evidence.rows().isEmpty());
        assertTrue(evidence.text().contains("不可得"));
        assertTrue(evidence.text().contains("不同口径，不可换算"));
    }

    @Test
    public void shouldExcludeOtherProfilesWithinSameDate() {
        insert("2026-10-01", "eval", "model-a", "chat", 3, 3, 0, 30, 15, 45, 300, 0);
        insert("2026-10-01", "judge", "model-a", "judge", 7, 6, 1, 70, 35, 105, 700, 0);

        EvalAiUsageEvidence evidence = new EvalAiUsageEvidenceService(source)
                .collect("judge", "2026-10-01");

        assertEquals(1, evidence.rows().size());
        assertEquals("judge", evidence.rows().get(0).profile());
        assertEquals(7L, evidence.rows().get(0).requests());
    }

    private void insert(String statDate, String profile, String model, String action,
            int requests, int successes, int failures, int promptTokens, int completionTokens,
            int totalTokens, int totalLatencyMs, int cachedHits) {
        assertEquals(1, storage.update(INSERT_USAGE, statDate, profile, model, action,
                Integer.valueOf(requests), Integer.valueOf(successes), Integer.valueOf(failures),
                Integer.valueOf(promptTokens), Integer.valueOf(completionTokens),
                Integer.valueOf(totalTokens), Integer.valueOf(totalLatencyMs),
                Integer.valueOf(cachedHits)));
    }
}
