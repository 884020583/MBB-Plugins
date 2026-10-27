package org.moboxlab.mbb.eval;

import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.bank.EvalBankCache;
import org.moboxlab.mbb.eval.bank.EvalBankLocator;
import org.moboxlab.mbb.eval.bank.EvalBankValidator;
import org.moboxlab.mbb.eval.baseline.EvalBaselineService;
import org.moboxlab.mbb.eval.command.EvalCommand;
import org.moboxlab.mbb.eval.command.EvalReadOnlyCommand;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.report.EvalReportWriter;
import org.moboxlab.mbb.eval.runner.EvalRunService;
import org.moboxlab.mbb.eval.runner.EvalRunner;
import org.moboxlab.mbb.eval.score.EvalDimensionWeights;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.Command.CommandPermission;
import org.moboxlab.moboxbot.API.Command.CommandSender;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 只读命令验收（规格 #50 §9.3、§9.6、§9.7，G5 / G6 / G7 / G8）。
 *
 * <p>每个用例都用真实的 {@link EvalStore} + 真 SQLite 假存储 + 真实内置题库缓存，只在测试
 * 准备阶段写库；命令执行后断言：</p>
 * <ul>
 *   <li>{@code updateSql} 条数不变——没有任何 INSERT/UPDATE/DELETE/DDL；</li>
 *   <li>新增的 {@code executedSql} 全部以 SELECT 开头——只读；</li>
 *   <li>注入的 {@link EvalRunService} 任何方法都会抛 {@link AssertionError}——不建 run、不调 AI。</li>
 * </ul>
 */
public class EvalReadOnlyCommandTest {

    private static final String RUNNING = "r-20261025230000-smoke-aaaa";
    private static final String COMPLETED = "r-20261025231000-full-bbbb";
    private static final String COMPLETED_LATEST = "r-20261025232000-smoke-cccc";
    private static final String ABORTED = "r-20261025233000-full-dddd";
    private static final String BASELINE_OLD = "r-20261025234000-full-eeee";
    private static final String BASELINE_NEW = "r-20261025235000-full-ffff";
    private static final String UNKNOWN = "r-20261025235900-smoke-zzzz";
    private static final String STARTED_AT = "2026-10-25 23:00:00";
    private static final String ENDED_AT = "2026-10-25 23:12:00";
    private static final String[] DIMENSIONS = {"persona", "boundary", "memory", "knowledge", "style"};

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;
    private EvalConfig config;
    private File dataFolder;
    private File reportsDir;
    private EvalBankCache bank;
    private int updatesBefore;
    private int executedBefore;

    @Before
    public void setUp() throws Exception {
        storage = new EvalStorageFake(new File(folder.getRoot(), "eval-readonly.db"));
        assertTrue("建表失败", new EvalSchemaService(storage, null).ensureSchema());
        store = new EvalStore(storage, null);
        config = EvalConfig.load(null);
        dataFolder = TestEvalAssets.dataFolder(folder.getRoot());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        TestEvalAssets.write(evals, TestEvalAssets.BANK_FILE_NAME, TestEvalAssets.builtinBankBytes());
        bank = EvalBankCache.load(
                EvalBankLocator.locate(dataFolder, TestEvalAssets.BANK_FILE_NAME),
                EvalBankValidator.Options.defaults(), null, null, false);
        assertTrue("内置题库必须可加载：" + bank.code() + " " + bank.message(), bank.ok());
        reportsDir = new File(dataFolder, "reports");
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    // ------------------------------------------------------------ G5 status

    @Test
    public void statusWithoutArgumentShouldShowCurrentRunningRun() {
        insertRun(RUNNING, EvalStore.TIER_SMOKE, null, 25);
        insertCase(RUNNING, "case-1", "persona", "ok", 90);
        insertCase(RUNNING, "case-2", "boundary", "error", 0);
        insertCase(RUNNING, "case-3", "memory", "invalid", 0);
        mark();

        RecordingSender sender = run("status");

        String joined = sender.joined();
        assertTrue("无参 status 取当前 RUNNING：" + joined, joined.contains("runId=" + RUNNING));
        assertTrue(joined.contains("tier=smoke"));
        assertTrue(joined.contains("status=RUNNING"));
        assertTrue("进度含已完成数/题库总数：" + joined, joined.contains("进度：3/12 已完成 case"));
        assertTrue(joined.contains("调用：calls_used=0/calls_budget=25"));
        assertTrue(joined.contains("失败分布：error=1，invalid=1"));
        assertTrue(joined.contains("开始于：" + STARTED_AT));
        assertTrue("剩余墙钟由 started_at 与上限推算：" + joined,
                joined.contains("剩余墙钟约 1740 秒"));
        assertReadOnly();
    }

    @Test
    public void statusWithRunIdShouldShowCompletedRunDetails() {
        insertRun(COMPLETED, EvalStore.TIER_FULL, EvalStore.STATUS_COMPLETED, 25);
        insertCase(COMPLETED, "case-1", "persona", "ok", 90);
        insertCase(COMPLETED, "case-2", "boundary", "error", 0);
        assertEquals(EvalStore.WriteResult.APPLIED, store.incrementCallsUsed(COMPLETED));
        assertEquals(EvalStore.WriteResult.APPLIED, store.incrementCallsUsed(COMPLETED));
        assertEquals(EvalStore.WriteResult.APPLIED, store.incrementCallsUsed(COMPLETED));
        assertEquals(EvalStore.WriteResult.APPLIED, store.incrementCallsUsed(COMPLETED));
        assertEquals(EvalStore.WriteResult.APPLIED, store.incrementCallsUsed(COMPLETED));
        mark();

        RecordingSender sender = run("status", COMPLETED);

        String joined = sender.joined();
        assertTrue(joined.contains("runId=" + COMPLETED));
        assertTrue(joined.contains("tier=full"));
        assertTrue(joined.contains("status=COMPLETED"));
        assertTrue("进度含已完成数/题库总数：" + joined, joined.contains("进度：2/40 已完成 case"));
        assertTrue(joined.contains("calls_used=5/calls_budget=25"));
        assertTrue(joined.contains("失败分布：error=1，invalid=0"));
        assertTrue(joined.contains("剩余墙钟：不适用"));
        assertReadOnly();
    }

    @Test
    public void statusWithoutRunningShouldFallBackToLatestRun() {
        insertRun(COMPLETED, EvalStore.TIER_FULL, EvalStore.STATUS_COMPLETED, 25);
        insertCase(COMPLETED, "case-1", "persona", "ok", 90);
        insertRun(COMPLETED_LATEST, EvalStore.TIER_SMOKE, EvalStore.STATUS_COMPLETED, 25);
        insertCase(COMPLETED_LATEST, "case-1", "persona", "ok", 88);
        mark();

        String joined = run("status").joined();

        assertTrue("没有 RUNNING 时取最近一次：" + joined, joined.contains("runId=" + COMPLETED_LATEST));
        assertFalse(joined.contains("runId=" + COMPLETED + " "));
        assertReadOnly();
    }

    @Test
    public void statusShouldRejectMalformedAndMissingRunId() {
        mark();
        String malformed = run("status", "not-a-run").joined();
        assertTrue(malformed.contains("runId 格式不正确"));
        assertTrue(malformed.contains("r-<yyyyMMddHHmmss>-<smoke|full>-<4 位随机>"));
        assertReadOnly();

        mark();
        String missing = run("status", UNKNOWN).joined();
        assertTrue("不存在按无此 run 处理：" + missing, missing.contains("无此 run：" + UNKNOWN));
        assertTrue("必须提示无参形态：" + missing, missing.contains("/eval status（无参）"));
        assertReadOnly();
    }

    // ------------------------------------------------------------ G6 report

    @Test
    public void reportShouldWriteCompletedRunFileAndShortPlainSummary() throws Exception {
        insertRun(COMPLETED, EvalStore.TIER_FULL, EvalStore.STATUS_COMPLETED, 25);
        insertFiveCases(COMPLETED);
        mark();

        RecordingSender sender = run("report");
        List<String> lines = unprefixed(sender);

        assertTrue("首行回报表路径：" + lines, lines.get(0).equals(
                "report_path=./MoBoxBot/plugins/MBB-Eval/reports/" + EvalReportWriter.fileName(COMPLETED)));
        assertTrue("路径行必须指向规范目录：" + lines.get(0),
                lines.get(0).startsWith("report_path=./MoBoxBot/plugins/MBB-Eval/reports/run-"));
        File report = new File(reportsDir, EvalReportWriter.fileName(COMPLETED));
        assertTrue("报表文件必须写出：" + report.getAbsolutePath(), report.isFile());
        String markdown = new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8);
        assertTrue("报表内容应包含 runId", markdown.contains(COMPLETED));
        assertTrue("摘要不超过 15 行（含路径行）：" + lines.size(), lines.size() <= EvalReadOnlyCommand.MAX_REPORT_LINES);
        assertTrue("摘要至少含总分/五维等核心行：" + lines, lines.size() >= 5);
        for (String line : lines.subList(1, lines.size())) {
            assertFalse("纯文本摘要不得含 Markdown 标记：" + line, line.contains("**") || line.contains("##"));
        }
        assertFalse("纯文本摘要不得含 emoji", markdown.contains("😀"));
        assertReadOnly();

        // 幂等：重复 report 只从库重建并同名覆盖，不新增库写入。
        mark();
        RecordingSender repeated = run("report", COMPLETED);
        assertEquals("重复 report 仍返回同一路径", lines.get(0), unprefixed(repeated).get(0));
        assertReadOnly();
    }

    @Test
    public void reportShouldRefuseAbortedRunAndPointToStatus() {
        insertRun(ABORTED, EvalStore.TIER_FULL, null, 25);
        insertFiveCases(ABORTED);
        assertEquals("中止落库失败", 1, store.casAborted(ABORTED, ENDED_AT, 60000L,
                EvalStore.ABORT_REASON_USER));
        mark();

        List<String> lines = unprefixed(run("report", ABORTED));

        assertEquals("拒绝只回一句", 1, lines.size());
        assertTrue("说明拒绝原因：" + lines.get(0), lines.get(0).contains("状态为 ABORTED"));
        assertTrue("指向 status：" + lines.get(0), lines.get(0).contains("/eval status " + ABORTED));
        assertFalse("拒绝时不得写报表", new File(reportsDir, EvalReportWriter.fileName(ABORTED)).exists());
        assertReadOnly();
    }

    @Test
    public void reportWithoutCompletedRunShouldExplainAndNotWrite() {
        insertRun(ABORTED, EvalStore.TIER_FULL, null, 25);
        assertEquals(1, store.casAborted(ABORTED, ENDED_AT, 60000L, EvalStore.ABORT_REASON_USER));
        mark();

        List<String> lines = unprefixed(run("report"));

        assertEquals(1, lines.size());
        assertTrue("无 COMPLETED 时说明最近一次与状态：" + lines.get(0),
                lines.get(0).contains("最近没有 COMPLETED 的 run"));
        assertTrue(lines.get(0).contains(ABORTED));
        assertTrue(lines.get(0).contains("/eval status " + ABORTED));
        assertReadOnly();
    }

    // ------------------------------------------------------------ G7 list

    @Test
    public void listShouldShowSmokeTwelveAndFullFortyWithoutCalls() {
        mark();
        List<String> smoke = unprefixed(run("list", "smoke"));
        assertTrue("题库头声明 0 调用、不建 run：" + smoke.get(0),
                smoke.get(0).contains("0 调用、不建 run"));
        assertTrue(smoke.get(0).contains("档位 smoke"));
        assertEquals("smoke 必须 12 条", 12, countCaseLines(smoke));
        assertCaseLineShape(smoke);
        assertReadOnly();

        mark();
        List<String> full = unprefixed(run("list", "full"));
        assertTrue(full.get(0).contains("档位 full"));
        assertEquals("full 必须 40 条", 40, countCaseLines(full));
        assertCaseLineShape(full);
        assertReadOnly();

        mark();
        List<String> all = unprefixed(run("list"));
        assertEquals("无参列出全部 40 条", 40, countCaseLines(all));
        assertTrue(all.get(0).contains("档位 all"));
        assertReadOnly();
    }

    @Test
    public void listShouldReportBankErrorWhenNotReady() {
        EvalBankCache missing = EvalBankCache.load(
                EvalBankLocator.locate(dataFolder, "bank-9.9.9.json"),
                EvalBankValidator.Options.defaults(), null, null, false);
        assertFalse(missing.ok());
        EvalReadOnlyCommand service = new EvalReadOnlyCommand(store, config, missing, reportsDir);
        mark();

        List<String> lines = service.list(new String[] {"smoke"});

        assertTrue("题库未就绪必须给原因：" + lines, lines.get(0).contains("题库未就绪"));
        assertTrue(lines.get(0).contains(missing.code()));
        assertTrue(lines.toString().contains("0 调用、不建 run"));
        assertReadOnly();
    }

    // ------------------------------------------------------------ G8 baseline

    @Test
    public void baselineShouldListActiveAndHistory() {
        createBaseline(BASELINE_OLD, "旧基线");
        createBaseline(BASELINE_NEW, "新基线");
        mark();

        String joined = run("baseline").joined();

        assertTrue("列出 active 基线：" + joined, joined.contains("active 基线：b-" + BASELINE_NEW));
        assertTrue(joined.contains("run " + BASELINE_NEW));
        assertTrue("列出历史条目：" + joined, joined.contains("b-" + BASELINE_OLD));
        assertTrue(joined.contains("最近历史：1 条"));
        assertReadOnly();
    }

    @Test
    public void baselineShowShouldRenderFrozenScoresAndNineKeys() {
        createBaseline(BASELINE_OLD, "旧基线");
        createBaseline(BASELINE_NEW, "新基线");
        mark();

        List<String> lines = unprefixed(run("baseline", "show"));
        String joined = lines.toString();

        assertTrue("无参 show 取 active：" + joined, joined.contains("baseline_id=b-" + BASELINE_NEW));
        assertTrue(joined.contains("run_id=" + BASELINE_NEW));
        assertTrue("显示总分：" + joined, joined.contains("总分="));
        assertTrue("显示五维分：" + joined, joined.contains("五维分："));
        for (String dimension : DIMENSIONS) {
            assertTrue("五维缺少 " + dimension + "：" + joined,
                    joined.contains(dimension + "（"));
        }
        assertTrue(joined.contains("九键快照："));
        for (String key : new String[] {"bank_hash", "rubric_version", "persona_hash",
                "config_fingerprint", "eval_profile", "judge_profile", "judge_model",
                "generation_model", "eval_api_version"}) {
            assertTrue("九键缺少 " + key + "：" + joined, joined.contains(key + "="));
        }
        assertTrue(joined.contains("bank-hash-" + BASELINE_NEW));
        assertReadOnly();

        mark();
        String old = run("baseline", "show", BASELINE_OLD).joined();
        assertTrue("有参 show 显示该 run 的基线：" + old, old.contains("baseline_id=b-" + BASELINE_OLD));
        assertTrue(old.contains("run_id=" + BASELINE_OLD));
        assertReadOnly();
    }

    @Test
    public void baselineShowShouldRejectRunNotReferencedByAnyBaseline() {
        createBaseline(BASELINE_OLD, "旧基线");
        mark();

        String joined = run("baseline", "show", UNKNOWN).joined();

        assertTrue("未被基线引用必须拒绝：" + joined, joined.contains("未被任何 baseline 引用"));
        assertTrue(joined.contains(UNKNOWN));
        assertReadOnly();
    }

    // ------------------------------------------------------------ 辅助

    /** 通过 EvalCommand 走完整权限与分派链路；注入的 runner 一旦被调用立即失败。 */
    private RecordingSender run(String subcommand, String... params) {
        EvalReadOnlyCommand readOnly = new EvalReadOnlyCommand(
                store, config, bank, reportsDir, fixedClock());
        EvalCommand command = new EvalCommand(new ForbiddenRunner(), config, readOnly);
        RecordingSender sender = new RecordingSender(CommandPermission.BOT_ADMIN);
        String[] args = new String[2 + params.length];
        args[0] = "eval";
        args[1] = subcommand;
        System.arraycopy(params, 0, args, 2, params.length);
        assertTrue("命令必须被处理", command.execute(sender, args));
        assertFalse("命令必须有回执", sender.messages.isEmpty());
        for (String message : sender.messages) {
            assertTrue("回执必须带 [eval] 前缀：" + message, message.startsWith("[eval] "));
        }
        return sender;
    }

    private static Clock fixedClock() {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(2026, Calendar.OCTOBER, 25, 23, 1, 0);
        return Clock.fixed(calendar.getTime().toInstant(), ZoneId.systemDefault());
    }

    private void insertRun(String runId, String tier, String status, int callsBudget) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("tier", tier);
        row.put("started_at", STARTED_AT);
        row.put("calls_budget", callsBudget);
        row.put("bank_hash", "bank-hash-" + runId);
        row.put("rubric_version", "rubric-1");
        row.put("persona_hash", "persona-hash-1");
        row.put("config_fingerprint", "fingerprint-1");
        row.put("eval_profile", "eval-profile-1");
        row.put("judge_profile", "judge-profile-1");
        row.put("judge_model", "judge-1");
        row.put("generation_model", "gen-1");
        row.put("eval_api_version", "api-1");
        row.put("config_snapshot_json", "{\"maxTokens\":512}");
        assertTrue("run 行写入失败：" + runId, store.insertRunning(row));
        if (EvalStore.STATUS_COMPLETED.equals(status)) {
            assertEquals("完成态落库失败", 1,
                    store.casCompleted(runId, ENDED_AT, 720000L, null));
        }
    }

    private void insertCase(String runId, String caseId, String dimension, String status, double score) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("case_id", caseId);
        row.put("dimension", dimension);
        row.put("status", status);
        row.put("score", score);
        row.put("max_score", 100);
        assertEquals("case 行写入失败：" + caseId, EvalStore.WriteResult.APPLIED, store.insertCaseResult(row));
    }

    private void insertFiveCases(String runId) {
        double[] scores = {90.0d, 80.0d, 70.0d, 60.0d, 50.0d};
        for (int i = 0; i < DIMENSIONS.length; i++) {
            insertCase(runId, "case-" + (i + 1), DIMENSIONS[i], "ok", scores[i]);
        }
    }

    private void createBaseline(String runId, String reason) {
        insertRun(runId, EvalStore.TIER_FULL, EvalStore.STATUS_COMPLETED, 25);
        insertFiveCases(runId);
        EvalBaselineService service = new EvalBaselineService(weights());
        EvalBaselineService.BaselineOutcome outcome = service.explicitSet(store, runId, reason);
        assertTrue("基线建立失败：" + outcome.note(), outcome.applied());
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

    /** 命令执行前的写库计数快照。 */
    private void mark() {
        updatesBefore = storage.updateSql().size();
        executedBefore = storage.executedSql().size();
    }

    /** 只读断言：无新 update，新增语句全部 SELECT。 */
    private void assertReadOnly() {
        assertEquals("只读命令不得写库（update 数变化）", updatesBefore, storage.updateSql().size());
        List<String> executed = storage.executedSql();
        for (int i = executedBefore; i < executed.size(); i++) {
            String sql = executed.get(i).trim().toUpperCase(Locale.ROOT);
            assertTrue("只读命令只能发 SELECT：" + executed.get(i), sql.startsWith("SELECT"));
        }
    }

    private static List<String> unprefixed(RecordingSender sender) {
        List<String> lines = new ArrayList<String>();
        for (String message : sender.messages) {
            assertTrue(message.startsWith("[eval] "));
            lines.add(message.substring("[eval] ".length()));
        }
        return lines;
    }

    private static int countCaseLines(List<String> lines) {
        int count = 0;
        for (String line : lines) {
            if (line.startsWith("case ")) {
                count++;
            }
        }
        return count;
    }

    private static void assertCaseLineShape(List<String> lines) {
        for (String line : lines) {
            if (!line.startsWith("case ")) {
                continue;
            }
            assertTrue("每条 case 必须含维度：" + line, line.contains(" 维度="));
            assertTrue("每条 case 必须含 tier：" + line, line.contains(" tier="));
            assertTrue("每条 case 必须含 focus：" + line, line.contains(" focus="));
            String focus = line.substring(line.indexOf(" focus=") + " focus=".length()).trim();
            assertFalse("focus 不得为空：" + line, focus.isEmpty());
        }
    }

    /** 只读命令不应触碰的 runner；任何调用都立即失败。 */
    private static final class ForbiddenRunner implements EvalRunService {
        @Override
        public EvalRunner.AcceptResult accept(String tier) {
            throw new AssertionError("只读命令不得受理 run");
        }

        @Override
        public EvalRunner.AbortResult abort() {
            throw new AssertionError("只读命令不得中止 run");
        }

        @Override
        public EvalRunner.RunSnapshot snapshot() {
            throw new AssertionError("只读命令不得读取 runner 内存快照");
        }

        @Override
        public EvalRunner.RunSnapshot snapshot(String runId) {
            throw new AssertionError("只读命令不得读取 runner 内存快照：" + runId);
        }
    }

    private static final class RecordingSender implements CommandSender {
        private final Set<CommandPermission> granted = new LinkedHashSet<CommandPermission>();
        private final List<String> messages = new ArrayList<String>();

        private RecordingSender(CommandPermission... permissions) {
            granted.addAll(Arrays.asList(permissions));
        }

        @Override
        public boolean isGroup() {
            return false;
        }

        @Override
        public long getGroupID() {
            return 0L;
        }

        @Override
        public long getUserID() {
            return 10001L;
        }

        @Override
        public String getName() {
            return "tester";
        }

        @Override
        public String getCommandPrefix() {
            return "/";
        }

        @Override
        public boolean hasPermission(CommandPermission permission) {
            return permission == CommandPermission.EVERYONE || granted.contains(permission);
        }

        @Override
        public void sendMessage(String message) {
            messages.add(message);
        }

        @Override
        public void reply(String message) {
            messages.add(message);
        }

        private String joined() {
            StringBuilder text = new StringBuilder();
            for (String message : messages) {
                text.append(message).append('\n');
            }
            return text.toString();
        }
    }
}
