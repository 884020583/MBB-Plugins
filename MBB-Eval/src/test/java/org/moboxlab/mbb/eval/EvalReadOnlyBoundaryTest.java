package org.moboxlab.mbb.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.moboxlab.mbb.eval.bank.EvalBankLocation;
import org.moboxlab.mbb.eval.bank.EvalBankLocator;
import org.moboxlab.mbb.eval.bank.EvalBankReleaseResult;
import org.moboxlab.mbb.eval.bank.EvalBankReleaser;
import org.moboxlab.mbb.eval.bank.EvalBankResources;
import org.moboxlab.mbb.eval.bank.EvalLegacyFileGuard;
import org.moboxlab.mbb.eval.store.EvalReadOnlyBoundary;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * 只读边界与旧题库文件封禁用例（I-4）。
 *
 * <p>覆盖 docs/MBB-Eval.md §5.1（路径与释放方）、§5.8.1 第 7 条（不得改写或删除旧 roleplay-evals.json）、
 * §5.8.2（不回退读旧文件）、§11.4（SQL 必须含 plugin_ 且不得含 bot_）、§16.10 I-4 与 §15.9 验收 I5 / I6 / I7：</p>
 *
 * <ul>
 *   <li>允许写的表仅四张 eval 表，SQL 不得写 Roleplay 表、不得写 trace、不得读线上 usage 表；</li>
 *   <li>允许写的目录仅插件 dataFolder（含 reports/）与 MBB-Roleplay/evals；</li>
 *   <li>旧 roleplay-evals.json 只按文件名拒绝，不读内容，也不改写；</li>
 *   <li>题库释放只在缺失时发生，既有文件不被覆盖（复用 B-2 的释放器，不改其文件）。</li>
 * </ul>
 */
public class EvalReadOnlyBoundaryTest {

    private static final List<String> EVAL_TABLES = Arrays.asList(
            "plugin_mbb_eval_run", "plugin_mbb_eval_case_result",
            "plugin_mbb_eval_call", "plugin_mbb_eval_baseline");

    private File tempDir;
    private File pluginsRoot;
    private File dataFolder;
    private File evalsDir;
    private EvalReadOnlyBoundary boundary;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("mbb-eval-boundary").toFile();
        pluginsRoot = new File(tempDir, "MoBoxBot/plugins");
        dataFolder = new File(pluginsRoot, "MBB-Eval");
        assertTrue(dataFolder.mkdirs());
        evalsDir = new File(pluginsRoot, "MBB-Roleplay/evals");
        boundary = new EvalReadOnlyBoundary(dataFolder);
    }

    @After
    public void tearDown() {
        deleteRecursively(tempDir);
    }

    @Test
    public void allowedTablesShouldBeExactlyTheFourEvalTables() {
        assertEquals(4, EvalReadOnlyBoundary.allowedTables().size());
        assertEquals(EvalSchemaService.tableNames(), EvalReadOnlyBoundary.allowedTables());
        for (String table : EVAL_TABLES) {
            assertTrue("四张 eval 表必须放行：" + table, EvalReadOnlyBoundary.isAllowedTable(table));
        }
        assertFalse(EvalReadOnlyBoundary.isAllowedTable("plugin_mbb_roleplay_message"));
        assertFalse(EvalReadOnlyBoundary.isAllowedTable("plugin_mbb_roleplay_trace"));
        assertFalse(EvalReadOnlyBoundary.isAllowedTable("plugin_mbb_eval_run_extra"));
        assertFalse(EvalReadOnlyBoundary.isAllowedTable(null));
        assertFalse(EvalReadOnlyBoundary.isAllowedTable(""));
    }

    @Test
    public void fourEvalTablesShouldPassSqlBoundary() {
        EvalReadOnlyBoundary.assertSql("SELECT * FROM plugin_mbb_eval_run WHERE run_id = ?",
                "plugin_mbb_eval_run");
        EvalReadOnlyBoundary.assertSql("INSERT INTO plugin_mbb_eval_case_result (run_id) VALUES (?)",
                "plugin_mbb_eval_case_result");
        EvalReadOnlyBoundary.assertSql("UPDATE plugin_mbb_eval_call SET status = ? WHERE call_id = ?",
                "plugin_mbb_eval_call");
        EvalReadOnlyBoundary.assertSql("SELECT * FROM plugin_mbb_eval_baseline WHERE active = 1",
                "plugin_mbb_eval_baseline");
        assertEquals("", EvalReadOnlyBoundary.sqlViolation(
                "DELETE FROM plugin_mbb_eval_run WHERE run_id = ?", "plugin_mbb_eval_run"));
    }

    @Test
    public void roleplayTraceAndUnknownTablesShouldBeRejected() {
        assertSqlRejected("INSERT INTO plugin_mbb_roleplay_trace (id) VALUES (?)",
                "plugin_mbb_roleplay_trace", "plugin_mbb_roleplay_trace");
        assertSqlRejected("SELECT * FROM plugin_mbb_roleplay_message WHERE id = ?",
                "plugin_mbb_roleplay_message", "plugin_mbb_roleplay_message");
        assertSqlRejected("SELECT * FROM plugin_mbb_roleplay_usage", "", "plugin_mbb_roleplay_usage");
        assertSqlRejected("INSERT INTO plugin_mbb_eval_run_archive (run_id) VALUES (?)",
                "plugin_mbb_eval_run_archive", "plugin_mbb_eval_run_archive");
        // 声明表本身不在白名单时也要拒绝，避免调用方用合法语句洗白非法表名。
        assertSqlRejected("SELECT * FROM plugin_mbb_eval_run", "plugin_mbb_roleplay_trace",
                "plugin_mbb_roleplay_trace");
    }

    @Test
    public void sqlWithoutPluginPrefixOrWithBotPrefixShouldBeRejected() {
        assertFalse("SQL 必须含 plugin_", EvalReadOnlyBoundary.sqlViolation(
                "SELECT * FROM users", "").isEmpty());
        assertFalse("SQL 不得含 bot_", EvalReadOnlyBoundary.sqlViolation(
                "SELECT * FROM plugin_mbb_eval_run JOIN bot_state ON 1 = 1",
                "plugin_mbb_eval_run").isEmpty());
        assertFalse("空语句应拒绝", EvalReadOnlyBoundary.sqlViolation(null, "plugin_mbb_eval_run").isEmpty());
        assertFalse("空白语句应拒绝", EvalReadOnlyBoundary.sqlViolation("   ", "").isEmpty());
        try {
            EvalReadOnlyBoundary.assertSql("SELECT * FROM users", "");
            fail("无 plugin_ 前缀的 SQL 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
    }

    @Test
    public void declaredTableMustAppearInStatement() {
        assertSqlRejected("SELECT * FROM plugin_mbb_eval_run WHERE run_id = ?",
                "plugin_mbb_eval_baseline", "plugin_mbb_eval_baseline");
    }

    @Test
    public void pathInsideDataFolderOrEvalsShouldBeAllowed() {
        assertTrue(boundary.pathInBoundary(new File(dataFolder, "reports/run-r-1.md")));
        assertTrue(boundary.pathInBoundary(new File(dataFolder, "config.json")));
        assertTrue(boundary.pathInBoundary(new File(evalsDir, "bank-1.0.0.json")));
        assertTrue(boundary.pathInBoundary(new File(evalsDir, "rubric-1.1.0.json")));
        assertEquals("", boundary.pathViolation(new File(dataFolder, "reports/run-r-1.md")));
    }

    @Test
    public void pathOutsideBoundaryShouldBeRejected() {
        assertFalse(boundary.pathInBoundary(null));
        assertFalse(boundary.pathInBoundary(new File(pluginsRoot, "MBB-AI/config.json")));
        assertFalse(boundary.pathInBoundary(new File(tempDir, "elsewhere/out.md")));
        assertFalse(boundary.pathInBoundary(new File(dataFolder, "../other/secret.txt")));
        assertFalse(boundary.pathInBoundary(new File(dataFolder, "../../../escape.txt")));
        assertFalse("空边界必须 fail-closed", new EvalReadOnlyBoundary(null)
                .pathInBoundary(new File(dataFolder, "reports/run-r-1.md")));
        assertFalse(boundary.pathViolation(new File(tempDir, "elsewhere/out.md")).isEmpty());
    }

    @Test
    public void onlyBankFilesDirectlyUnderEvalsAreWritable() {
        assertTrue(boundary.writableBankTarget(new File(evalsDir, "bank-1.0.0.json")));
        assertTrue(boundary.writableBankTarget(new File(evalsDir, "bank-2.1.0.json")));
        assertFalse("rubric 由人工/发布包维护，MBB-Eval 不写",
                boundary.writableBankTarget(new File(evalsDir, "rubric-1.1.0.json")));
        assertFalse("旧文件不得改写",
                boundary.writableBankTarget(new File(evalsDir, "roleplay-evals.json")));
        assertFalse("不写 evals 之外的 bank 文件",
                boundary.writableBankTarget(new File(dataFolder, "bank-1.0.0.json")));
        assertFalse("只在 evals 根目录释放，不写子目录",
                boundary.writableBankTarget(new File(new File(evalsDir, "sub"), "bank-1.0.0.json")));
    }

    @Test
    public void reportsDirectoryShouldBeUnderPluginDataFolder() {
        assertEquals(new File(dataFolder, "reports").getAbsoluteFile(),
                boundary.reportsDirectory().getAbsoluteFile());
        assertEquals("./MoBoxBot/plugins/MBB-Eval/reports/", EvalReadOnlyBoundary.REPORT_BASE_PATH);
        assertEquals(EvalBankLocator.evalsDirectory(dataFolder).getAbsoluteFile(),
                boundary.evalsDirectory().getAbsoluteFile());
    }

    @Test
    public void legacyFileShouldBeRejectedByFileNameWithoutReadingContent() throws IOException {
        assertTrue(evalsDir.mkdirs());
        File legacy = new File(evalsDir, "roleplay-evals.json");
        // 内容故意不是合法 JSON：只判文件名，绝不读内容。
        Files.write(legacy.toPath(), "not-json".getBytes(StandardCharsets.UTF_8));
        assertEquals("not-json", new String(Files.readAllBytes(legacy.toPath()), StandardCharsets.UTF_8));

        assertTrue(EvalLegacyFileGuard.isLegacyFileName("roleplay-evals.json"));
        assertTrue(EvalLegacyFileGuard.isLegacyFileName("./MBB-Roleplay/evals/Roleplay-Evals.JSON"));
        assertFalse(EvalLegacyFileGuard.isLegacyFileName("bank-1.0.0.json"));
        assertFalse(EvalLegacyFileGuard.isLegacyFileName(null));

        EvalLegacyFileGuard.Decision decision = EvalLegacyFileGuard.check(legacy);
        assertFalse(decision.allowed());
        assertTrue(decision.message().contains("roleplay-evals.json"));
        assertTrue(decision.message().contains("不读"));
        assertFalse(boundary.readableBankFile(legacy));
        assertTrue(boundary.legacyBankFile(legacy));
        assertTrue(EvalLegacyFileGuard.check(evalsDir).allowed());
        assertTrue(EvalLegacyFileGuard.check((File) null).allowed());

        EvalBankLocation location = EvalBankLocator.locate(dataFolder, "roleplay-evals.json");
        assertFalse("旧文件必须拒绝加载", location.valid());
        assertTrue(location.invalidReason().contains("roleplay-evals.json"));
        // 拒绝加载不改写、不删除旧文件。
        assertEquals("not-json", new String(Files.readAllBytes(legacy.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void legacyConfiguredValueShouldBeRejectedEvenWithRelativePrefix() {
        assertFalse(EvalLegacyFileGuard.check("./roleplay-evals.json").allowed());
        assertFalse(EvalLegacyFileGuard.check("roleplay-evals.json").allowed());
        assertTrue(EvalLegacyFileGuard.check("bank-1.0.0.json").allowed());
        try {
            EvalLegacyFileGuard.assertNotLegacy(new File(evalsDir, "roleplay-evals.json"));
            fail("旧文件应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("roleplay-evals.json"));
        }
    }

    @Test
    public void pathStyleShouldRejectDataPluginsAndMissingDotPrefix() {
        assertEquals("", EvalReadOnlyBoundary.pathStyleViolation(
                "./MoBoxBot/plugins/MBB-Roleplay/evals/bank-1.0.0.json"));
        assertEquals("", EvalReadOnlyBoundary.pathStyleViolation(
                "./MoBoxBot/plugins/MBB-Eval/reports/run.md"));
        assertEquals("", EvalReadOnlyBoundary.pathStyleViolation("bank-1.0.0.json"));
        assertEquals("", EvalReadOnlyBoundary.pathStyleViolation(""));
        assertFalse("不得写 data/plugins/...", EvalReadOnlyBoundary.pathStyleViolation(
                "data/plugins/MBB-Roleplay/evals/bank-1.0.0.json").isEmpty());
        assertFalse("不得写无前缀 plugins/...", EvalReadOnlyBoundary.pathStyleViolation(
                "plugins/MBB-Roleplay/evals/bank-1.0.0.json").isEmpty());
        assertFalse("不得写无 ./ 前缀的 MoBoxBot/plugins/...", EvalReadOnlyBoundary.pathStyleViolation(
                "MoBoxBot/plugins/MBB-Roleplay/evals/bank-1.0.0.json").isEmpty());
        assertFalse("不得写绝对路径", EvalReadOnlyBoundary.pathStyleViolation(
                "C:/MoBoxBot/plugins/MBB-Roleplay/evals/bank-1.0.0.json").isEmpty());
        try {
            EvalReadOnlyBoundary.assertPathStyle("data/plugins/MBB-Roleplay/evals/bank-1.0.0.json");
            fail("越界路径写法应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("data/plugins"));
        }
    }

    @Test
    public void existingBankFileShouldNeverBeOverwrittenDuringRelease() throws IOException {
        assertTrue(evalsDir.mkdirs());
        File existing = new File(evalsDir, "bank-1.0.0.json");
        byte[] userContent = "{\"version\":\"1.0.0\",\"note\":\"user edited\"}".getBytes(StandardCharsets.UTF_8);
        Files.write(existing.toPath(), userContent);
        long modified = existing.lastModified();
        FakeResources resources = new FakeResources("{\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8),
                "bank/bank-1.0.0.json");
        RecordingLogger logger = new RecordingLogger();

        EvalBankLocation location = EvalBankLocator.locate(dataFolder, "");
        EvalBankReleaseResult result = EvalBankReleaser.release(location, resources, logger);

        assertEquals(EvalBankReleaseResult.Status.SKIPPED_EXISTS, result.status());
        assertEquals(0, resources.reads);
        assertTrue(Arrays.equals(userContent, Files.readAllBytes(existing.toPath())));
        assertEquals(modified, existing.lastModified());
        assertTrue(logger.errors.isEmpty());
        assertNoTempFiles();
    }

    private static void assertSqlRejected(String statement, String table, String expectedToken) {
        try {
            EvalReadOnlyBoundary.assertSql(statement, table);
            fail("越界 SQL 应抛 IllegalArgumentException：" + statement);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
            assertTrue("提示应包含越界表名：" + expected.getMessage(),
                    expected.getMessage().contains(expectedToken));
        }
    }

    private void assertNoTempFiles() {
        File[] files = evalsDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            assertFalse("残留临时文件：" + name, name.endsWith(".tmp") || name.startsWith(".bank-"));
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    /** 伪造 JAR 资源读取器：只对约定路径返回内容，其余路径按「资源不存在」返回 null。 */
    private static final class FakeResources implements EvalBankResources {
        private final byte[] payload;
        private final String expectedPath;
        private int reads;

        private FakeResources(byte[] payload, String expectedPath) {
            this.payload = payload;
            this.expectedPath = expectedPath;
        }

        @Override
        public byte[] read(String resourcePath) {
            reads++;
            return expectedPath.equals(resourcePath) ? payload : null;
        }
    }

    /** 内存日志收集器，避免测试依赖真实插件日志输出。 */
    private static final class RecordingLogger extends PluginLogger {
        private final List<String> infos = new ArrayList<String>();
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
        }

        @Override
        public void sendError(String message) {
            errors.add(message);
        }
    }
}
