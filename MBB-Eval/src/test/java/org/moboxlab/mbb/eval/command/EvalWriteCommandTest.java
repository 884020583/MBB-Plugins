package org.moboxlab.mbb.eval.command;

import com.alibaba.fastjson.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.baseline.EvalBaselineService;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.runner.EvalRunner;
import org.moboxlab.mbb.eval.score.EvalDimensionWeights;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.Command.CommandPermission;
import org.moboxlab.moboxbot.API.Command.CommandSender;
import org.moboxlab.moboxbot.API.PluginLogger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 写侧命令验收（规格 #50 §9.3、§9.6、§9.7，验收 G9 / G10 / G15）。
 *
 * <p>全部用例从 {@link EvalCommand} 公共接缝进入，使用真实 SQLite 假存储、真实
 * {@link EvalBaselineService} 与真实 {@link EvalRunner}，直接查库断言：</p>
 * <ul>
 *   <li>G9：full + COMPLETED 才能设基线；成功后旧 active=0、superseded_by 指向新行，
 *       run 行状态不变；无理由 / smoke / 未完成 / 无此 run 都回可操作文案且不写库。</li>
 *   <li>G10：abort 把 RUNNING CAS 为 ABORTED(user-abort)，重复调用回「当前没有进行中的评测」；
 *       CAS 未执行时按 §13.4 回执、不重试、不改库。</li>
 *   <li>G15：同一 run 重复 set 幂等，不新增行、不切换 active。</li>
 * </ul>
 */
public class EvalWriteCommandTest {

    private static final String BASELINE_A = "r-20261025230000-full-aaaa";
    private static final String BASELINE_B = "r-20261025231000-full-bbbb";
    private static final String SMOKE_DONE = "r-20261025232000-smoke-cccc";
    private static final String FULL_RUNNING = "r-20261025233000-full-dddd";
    private static final String UNKNOWN = "r-20261025234000-full-eeee";
    private static final String STARTED_AT = "2026-10-25 23:00:00";
    private static final String ENDED_AT = "2026-10-25 23:12:00";
    private static final String[] DIMENSIONS = {"persona", "boundary", "memory", "knowledge", "style"};

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;
    private EvalConfig config;
    private EvalBaselineService baselineService;
    private EvalRunner runner;

    @Before
    public void setUp() throws Exception {
        storage = new EvalStorageFake(new File(folder.getRoot(), "eval-write.db"));
        assertTrue("建表失败", new EvalSchemaService(storage, null).ensureSchema());
        store = new EvalStore(storage, new RecordingLogger());
        config = EvalConfig.load(null);
        baselineService = new EvalBaselineService(weights(), new RecordingLogger());
        runner = new EvalRunner(store, config, null, new RecordingLogger(), "V0.1.0.0.1830");
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

    // ------------------------------------------------------------ G9 baseline set

    /** G9：full + COMPLETED 设基线成功，旧 active 退役且 superseded_by 指向新行。 */
    @Test
    public void baselineSetShouldRetireOldActiveAndKeepRunStatus() {
        createBaselineByCommand(BASELINE_A, "旧基线");
        JSONObject oldRow = store.findBaseline("b-" + BASELINE_A);
        assertNotNull(oldRow);
        String oldTotal = String.valueOf(oldRow.get("total_score"));

        insertRun(BASELINE_B, EvalStore.TIER_FULL, EvalStore.STATUS_COMPLETED);
        insertFiveCases(BASELINE_B);
        mark();
        int statusUpdatesBefore = countUpdateContaining("SET status");

        RecordingSender sender = run(CommandPermission.OWNER, "baseline", "set", BASELINE_B, "换 judge 后重建");

        String joined = sender.joined();
        assertTrue("成功回执含新基线 id：" + joined, joined.contains("已重设基线：baseline_id=b-" + BASELINE_B));
        assertTrue("成功回执含 run_id：" + joined, joined.contains("run_id=" + BASELINE_B));
        assertTrue("成功回执含总分：" + joined, joined.contains("总分="));
        assertTrue("成功回执含五维分：" + joined, joined.contains("五维分："));
        assertTrue("成功回执说明 run 状态未改动：" + joined, joined.contains("run 状态未改动"));

        JSONObject newRow = store.findBaseline("b-" + BASELINE_B);
        assertNotNull("新基线行必须存在", newRow);
        assertEquals("新基线必须是 active", 1, intValue(newRow.get("active")));

        JSONObject retired = store.findBaseline("b-" + BASELINE_A);
        assertEquals("旧基线必须退役", 0, intValue(retired.get("active")));
        assertEquals("旧基线 superseded_by 指向新行", "b-" + BASELINE_B, retired.getString("superseded_by"));
        assertEquals("历史基线行不得改写总分", oldTotal, String.valueOf(retired.get("total_score")));
        assertTrue("回执确认旧 active 已退役：" + joined, joined.contains("旧 active 已退役"));

        JSONObject runRow = store.findRun(BASELINE_B);
        assertEquals("baseline set 不得改 run 状态", EvalStore.STATUS_COMPLETED, runRow.getString("status"));
        assertEquals("baseline set 不得写 run 状态", statusUpdatesBefore, countUpdateContaining("SET status"));
    }

    /** G9：没有旧 active 时成功设基线，回执说明这是第一条。 */
    @Test
    public void baselineSetWithoutPreviousActiveShouldSayFirst() {
        insertRun(BASELINE_A, EvalStore.TIER_FULL, EvalStore.STATUS_COMPLETED);
        insertFiveCases(BASELINE_A);

        RecordingSender sender = run(CommandPermission.OWNER, "baseline", "set", BASELINE_A, "第一条基线");

        assertTrue(sender.joined().contains("此前没有 active 基线"));
        assertEquals(1, intValue(store.findBaseline("b-" + BASELINE_A).get("active")));
    }

    /** G9：无理由拒绝，回执给下一步，且不写库。 */
    @Test
    public void baselineSetWithoutReasonShouldBeRejected() {
        insertRun(BASELINE_A, EvalStore.TIER_FULL, EvalStore.STATUS_COMPLETED);
        insertFiveCases(BASELINE_A);
        mark();

        RecordingSender sender = run(CommandPermission.OWNER, "baseline", "set", BASELINE_A);

        String joined = sender.joined();
        assertTrue("必须指出理由为空：" + joined, joined.contains("理由不能为空"));
        assertTrue("必须给下一步动作：" + joined, joined.contains("下一步："));
        assertFalse("不得落到未实现占位", joined.contains("后续切片"));
        assertNoWrites();
    }

    /** G9：smoke 档拒绝设基线，且不写库。 */
    @Test
    public void baselineSetShouldRejectSmokeTier() {
        insertRun(SMOKE_DONE, EvalStore.TIER_SMOKE, EvalStore.STATUS_COMPLETED);
        insertFiveCases(SMOKE_DONE);
        mark();

        RecordingSender sender = run(CommandPermission.OWNER, "baseline", "set", SMOKE_DONE, "想设 smoke");

        String joined = sender.joined();
        assertTrue("必须指出 smoke 不能作基线：" + joined, joined.contains("smoke 档不能作为基线"));
        assertTrue("必须给下一步：" + joined, joined.contains("COMPLETED 的 full run"));
        assertEquals("smoke 不得产生基线行", null, store.findBaseline("b-" + SMOKE_DONE));
        assertNoWrites();
    }

    /** G9：非 COMPLETED 的 full run 拒绝，且不写库。 */
    @Test
    public void baselineSetShouldRejectUnfinishedRun() {
        insertRun(FULL_RUNNING, EvalStore.TIER_FULL, EvalStore.STATUS_RUNNING);
        mark();

        RecordingSender sender = run(CommandPermission.OWNER, "baseline", "set", FULL_RUNNING, "还没跑完");

        String joined = sender.joined();
        assertTrue("必须指出 run 未完成：" + joined, joined.contains("run 未完成"));
        assertTrue("必须给下一步：" + joined, joined.contains("/eval status"));
        assertEquals(null, store.findBaseline("b-" + FULL_RUNNING));
        assertNoWrites();
    }

    /** G9：无此 run 拒绝，且不写库。 */
    @Test
    public void baselineSetShouldRejectUnknownRun() {
        mark();

        RecordingSender sender = run(CommandPermission.OWNER, "baseline", "set", UNKNOWN, "不存在的 run");

        String joined = sender.joined();
        assertTrue("必须指出 run 不存在：" + joined, joined.contains("run 不存在"));
        assertTrue("必须给下一步：" + joined, joined.contains("下一步："));
        assertNoWrites();
    }

    /** G9：runId 格式不正确时在命令层拒绝，不查库、不写库。 */
    @Test
    public void baselineSetShouldRejectMalformedRunId() {
        mark();

        RecordingSender sender = run(CommandPermission.OWNER, "baseline", "set", "not-a-run", "理由");

        assertTrue(sender.joined().contains("runId 格式不正确"));
        assertNoWrites();
        assertTrue("格式错误不应查询 run", store.findRun("not-a-run") == null);
    }

    /** G9：BOT_ADMIN 无权重设基线，不写库。 */
    @Test
    public void baselineSetShouldRequireOwner() {
        insertRun(BASELINE_A, EvalStore.TIER_FULL, EvalStore.STATUS_COMPLETED);
        insertFiveCases(BASELINE_A);
        mark();

        RecordingSender sender = run(CommandPermission.BOT_ADMIN, "baseline", "set", BASELINE_A, "越权");

        assertEquals(1, sender.messages.size());
        assertTrue(sender.messages.get(0).contains("权限不足"));
        assertTrue(sender.messages.get(0).contains("OWNER"));
        assertEquals(null, store.findBaseline("b-" + BASELINE_A));
        assertNoWrites();
    }

    /** G15：同一 run 重复 set 按幂等处理，不新增行、不再切换 active、不写库。 */
    @Test
    public void repeatedBaselineSetShouldBeIdempotent() {
        createBaselineByCommand(BASELINE_A, "第一次");
        int baselines = store.findBaselines(100).size();
        mark();

        RecordingSender sender = run(CommandPermission.OWNER, "baseline", "set", BASELINE_A, "第二次");

        String joined = sender.joined();
        assertTrue("重复 set 必须回幂等：" + joined, joined.contains("重复设置按幂等处理"));
        assertTrue(joined.contains("本次未写库、未切换 active"));
        assertEquals("不得新增基线行", baselines, store.findBaselines(100).size());
        assertEquals(1, intValue(store.findBaseline("b-" + BASELINE_A).get("active")));
        assertNoWrites();
    }

    // ------------------------------------------------------------ G10 abort

    /** G10：遗留 RUNNING 行被 CAS 为 ABORTED(user-abort)，重复 abort 幂等。 */
    @Test
    public void abortShouldCasRunningRowAndBeIdempotent() {
        insertRun(FULL_RUNNING, EvalStore.TIER_FULL, EvalStore.STATUS_RUNNING);

        RecordingSender first = run(CommandPermission.OWNER, "abort");
        assertEquals(1, first.messages.size());
        assertEquals("[eval] 已中止：" + FULL_RUNNING + "（状态见 /eval status）", first.messages.get(0));

        JSONObject row = store.findRun(FULL_RUNNING);
        assertEquals(EvalStore.STATUS_ABORTED, row.getString("status"));
        assertEquals("中止原因码必须是 user-abort", EvalStore.ABORT_REASON_USER,
                row.getString("error_summary"));

        mark();
        RecordingSender second = run(CommandPermission.OWNER, "abort");
        assertEquals(1, second.messages.size());
        assertEquals("[eval] 当前没有进行中的评测", second.messages.get(0));
        assertEquals("重复 abort 不得改写终态", EvalStore.STATUS_ABORTED,
                store.findRun(FULL_RUNNING).getString("status"));
        assertNoWrites();
    }

    /** G10 / §13.4：CAS 未执行时如实回执「中止未执行」，不重试、不改库、保留 RUNNING。 */
    @Test
    public void abortShouldReportCasFailureWithoutRetry() {
        insertRun(FULL_RUNNING, EvalStore.TIER_FULL, EvalStore.STATUS_RUNNING);
        storage.failUpdateContaining("SET status = 'ABORTED'", 1);

        RecordingSender sender = run(CommandPermission.OWNER, "abort");

        String joined = sender.joined();
        assertTrue("必须回中止未执行：" + joined, joined.contains("中止未执行"));
        assertTrue("必须说明 §13.4 不重试：" + joined, joined.contains("§13.4"));
        assertTrue("必须以数据库现行状态为准：" + joined, joined.contains("以数据库现行状态为准"));
        assertEquals("不重试、不得改库", EvalStore.STATUS_RUNNING,
                store.findRun(FULL_RUNNING).getString("status"));
    }

    /** G10：BOT_ADMIN 无权 abort，RUNNING 行原样保留。 */
    @Test
    public void abortShouldRequireOwner() {
        insertRun(FULL_RUNNING, EvalStore.TIER_FULL, EvalStore.STATUS_RUNNING);
        mark();

        RecordingSender sender = run(CommandPermission.BOT_ADMIN, "abort");

        assertEquals(1, sender.messages.size());
        assertTrue(sender.messages.get(0).contains("权限不足"));
        assertTrue(sender.messages.get(0).contains("OWNER"));
        assertEquals(EvalStore.STATUS_RUNNING, store.findRun(FULL_RUNNING).getString("status"));
        assertNoWrites();
    }

    /** G10：abort 不接受参数。 */
    @Test
    public void abortShouldRejectExtraArguments() {
        insertRun(FULL_RUNNING, EvalStore.TIER_FULL, EvalStore.STATUS_RUNNING);
        mark();

        RecordingSender sender = run(CommandPermission.OWNER, "abort", FULL_RUNNING);

        assertTrue(sender.joined().contains("参数过多"));
        assertEquals(EvalStore.STATUS_RUNNING, store.findRun(FULL_RUNNING).getString("status"));
        assertNoWrites();
    }

    // ------------------------------------------------------------ 辅助

    private RecordingSender run(CommandPermission permission, String... subcommandAndParams) {
        EvalWriteCommand write = new EvalWriteCommand(store, baselineService, runner);
        EvalCommand command = new EvalCommand(runner, config, null, write);
        RecordingSender sender = new RecordingSender(permission);
        String[] args = new String[1 + subcommandAndParams.length];
        args[0] = "eval";
        System.arraycopy(subcommandAndParams, 0, args, 1, subcommandAndParams.length);
        assertTrue("命令必须被处理", command.execute(sender, args));
        assertFalse("命令必须有回执", sender.messages.isEmpty());
        for (String message : sender.messages) {
            assertTrue("回执必须带 [eval] 前缀：" + message, message.startsWith("[eval] "));
        }
        return sender;
    }

    private void createBaselineByCommand(String runId, String reason) {
        insertRun(runId, EvalStore.TIER_FULL, EvalStore.STATUS_COMPLETED);
        insertFiveCases(runId);
        RecordingSender sender = run(CommandPermission.OWNER, "baseline", "set", runId, reason);
        assertTrue("基线建立失败：" + sender.joined(), sender.joined().contains("已重设基线"));
    }

    private void insertRun(String runId, String tier, String status) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("tier", tier);
        row.put("started_at", STARTED_AT);
        row.put("calls_budget", 25);
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
            assertEquals("完成态落库失败", 1, store.casCompleted(runId, ENDED_AT, 720000L, null));
        }
    }

    private void insertFiveCases(String runId) {
        double[] scores = {90.0d, 80.0d, 70.0d, 60.0d, 50.0d};
        for (int i = 0; i < DIMENSIONS.length; i++) {
            JSONObject row = new JSONObject();
            row.put("run_id", runId);
            row.put("case_id", "case-" + (i + 1));
            row.put("dimension", DIMENSIONS[i]);
            row.put("status", "ok");
            row.put("score", scores[i]);
            row.put("max_score", 100);
            assertEquals("case 行写入失败", EvalStore.WriteResult.APPLIED, store.insertCaseResult(row));
        }
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

    private void mark() {
        updatesBefore = storage.updateSql().size();
        executedBefore = storage.executedSql().size();
    }

    private int updatesBefore;
    private int executedBefore;

    private void assertNoWrites() {
        assertEquals("拒绝路径不得写库", updatesBefore, storage.updateSql().size());
        List<String> executed = storage.executedSql();
        for (int i = executedBefore; i < executed.size(); i++) {
            assertTrue("拒绝路径只允许查询：" + executed.get(i),
                    executed.get(i).trim().toUpperCase().startsWith("SELECT"));
        }
    }

    private int countUpdateContaining(String fragment) {
        int count = 0;
        for (String sql : storage.updateSql()) {
            if (sql.contains(fragment)) {
                count++;
            }
        }
        return count;
    }

    private static int intValue(Object value) {
        return value instanceof Number ? ((Number) value).intValue()
                : Integer.parseInt(String.valueOf(value));
    }

    private static final class RecordingLogger extends PluginLogger {
        private RecordingLogger() {
            super("test", null);
        }

        @Override
        public void sendInfo(String message) {
        }

        @Override
        public void sendWarn(String message) {
        }

        @Override
        public void sendError(String message) {
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
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(message);
            }
            return text.toString();
        }
    }
}
