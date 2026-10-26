package org.moboxlab.mbb.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.command.EvalCommand;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.runner.EvalPreflightPlan;
import org.moboxlab.mbb.eval.runner.EvalRunService;
import org.moboxlab.mbb.eval.runner.EvalRunner;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.Command.CommandPermission;
import org.moboxlab.moboxbot.API.Command.CommandSender;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * run 受理与异步单飞用例（规格 §9.3.1 回执三行、§9.4 命令线程职责、§9.6 中止语义、§9.7 重复命令、§15.7 G3/G4/G10/G11/G15）。
 *
 * <p>使用真实临时 SQLite 库与真实 {@link EvalRunner}，从 {@link EvalCommand} 这一公共接缝进入：
 * 命令回执逐字断言，run 行状态与调用记录直接查库，避免读实现内部字段。</p>
 *
 * <p>口径：回执每行都保留 {@code [eval] } 前缀（与 G-1 的回执约定一致）；规格 §9.3.1 的 fenced
 * 示例只给第一行加前缀，本用例按实现约定逐字冻结三行。</p>
 */
public class EvalRunServiceTest {

    private static final String PLUGIN_VERSION = "V0.1.0.0.1830";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;
    private EvalConfig config;
    private EvalRunner runner;

    @Before
    public void setUp() throws Exception {
        File dbFile = temporaryFolder.newFile("eval-run-service.db");
        storage = new EvalStorageFake(dbFile);
        assertTrue("建表失败", new EvalSchemaService(storage, null).ensureSchema());
        store = new EvalStore(storage, new RecordingLogger());
        config = EvalConfig.load(null);
        runner = new EvalRunner(store, config, null, new RecordingLogger(), PLUGIN_VERSION);
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

    /** §9.3.1 + G3：回执三行逐字、第二行取自预检计划回执，返回时 run 仍是 RUNNING。 */
    @Test
    public void runShouldEmitThreeLineReceiptAndKeepRowRunning() {
        RecordingSender sender = new RecordingSender(CommandPermission.OWNER, false);
        long begin = System.currentTimeMillis();
        assertTrue(new EvalCommand(runner, config).execute(sender, new String[] { "eval", "run", "smoke" }));
        long elapsed = System.currentTimeMillis() - begin;

        JSONObject row = store.findRunningRun();
        assertNotNull("受理后必须有 RUNNING 行", row);
        String runId = row.getString("run_id");
        assertEquals(EvalStore.STATUS_RUNNING, row.getString("status"));
        assertEquals(EvalStore.TIER_SMOKE, row.getString("tier"));

        assertEquals("回执必须恰好三行：" + sender.messages, 3, sender.messages.size());
        assertEquals("[eval] 已受理：" + runId, sender.messages.get(0));
        assertEquals("[eval] " + EvalPreflightPlan.of(EvalStore.TIER_SMOKE)
                .receipt(config.callBudget()).summary(), sender.messages.get(1));
        assertEquals("[eval] 状态 /eval status；中止 /eval abort", sender.messages.get(2));

        // 命令线程立即返回：库中仍无终态、也没有任何调用行。
        assertEquals("命令线程不得自行收尾 run", 0, countUpdateSql("SET status"));
        assertTrue("受理阶段不得发起调用", store.findCalls(runId).rows().isEmpty());
        assertTrue("命令线程不应等待 run 结束，实际耗时 " + elapsed + "ms", elapsed < 2000L);
        assertAllPrefixed(sender);
    }

    /** G3：不带档位时按 evalDefaultTier 受理。 */
    @Test
    public void runWithoutTierShouldUseDefaultTier() {
        RecordingSender sender = new RecordingSender(CommandPermission.OWNER, false);
        assertTrue(new EvalCommand(runner, config).execute(sender, new String[] { "eval", "run" }));

        JSONObject row = store.findRunningRun();
        assertNotNull(row);
        assertEquals("默认档位应与 evalDefaultTier 一致", config.defaultTier(), row.getString("tier"));
        assertEquals(3, sender.messages.size());
        assertEquals("[eval] " + EvalPreflightPlan.of(config.defaultTier())
                .receipt(config.callBudget()).summary(), sender.messages.get(1));
    }

    /** §9.7 + G4：第二次 run 回当前 runId 与进度，不建第二行、不排队、不报错。 */
    @Test
    public void secondRunShouldReportRunningRunWithoutInsertingRow() {
        RecordingSender first = new RecordingSender(CommandPermission.OWNER, false);
        assertTrue(new EvalCommand(runner, config).execute(first, new String[] { "eval", "run", "smoke" }));
        JSONObject row = store.findRunningRun();
        assertNotNull(row);
        String runId = row.getString("run_id");

        RecordingSender second = new RecordingSender(CommandPermission.OWNER, false);
        assertTrue(new EvalCommand(runner, config).execute(second, new String[] { "eval", "run", "smoke" }));

        assertEquals("重复 run 只回一行：" + second.messages, 1, second.messages.size());
        assertEquals("[eval] 已在进行中：" + runId + "，当前进度 0/" + config.callBudget()
                + "；可发 /eval status 查看进度，或 /eval abort 中止该 run", second.messages.get(0));
        assertEquals("不得新建第二行 RUNNING", 1, store.findRecentRuns(10).size());
        assertEquals(runId, store.findRunningRun().getString("run_id"));
        assertAllPrefixed(second);
    }

    /** §9.7 + §9.4：库里有遗留 RUNNING 行（本进程无 active）时不受理，并指向 /eval abort。 */
    @Test
    public void leftoverRunningRowShouldBlockRunAndPointToAbort() {
        String leftoverId = "r-20261026003412-smoke-a1b2";
        assertTrue("预置遗留 RUNNING 行", store.insertRunning(leftoverId, EvalStore.TIER_SMOKE,
                EvalStore.nowText(), config.callBudget(), PLUGIN_VERSION));

        RecordingSender blocked = new RecordingSender(CommandPermission.OWNER, false);
        assertTrue(new EvalCommand(runner, config).execute(blocked, new String[] { "eval", "run", "smoke" }));

        assertEquals("遗留 RUNNING 时只回一行：" + blocked.messages, 1, blocked.messages.size());
        assertEquals("[eval] 已在进行中：" + leftoverId + "，当前进度 0/" + config.callBudget()
                + "；该行为上次运行遗留，本进程无法收尾，需先发 /eval abort 中止，再 /eval status 查看状态",
                blocked.messages.get(0));
        assertEquals("遗留 RUNNING 时不得新建行", 1, store.findRecentRuns(10).size());
        assertEquals(leftoverId, store.findRunningRun().getString("run_id"));

        // 先用 abort 收掉遗留行，之后才能重新受理。
        RecordingSender aborter = new RecordingSender(CommandPermission.OWNER, false);
        assertTrue(new EvalCommand(runner, config).execute(aborter, new String[] { "eval", "abort" }));
        assertEquals("[eval] 已中止：" + leftoverId + "（状态见 /eval status）", aborter.messages.get(0));
        assertEquals(EvalStore.STATUS_ABORTED, store.findRun(leftoverId).getString("status"));

        RecordingSender accepted = new RecordingSender(CommandPermission.OWNER, false);
        assertTrue(new EvalCommand(runner, config).execute(accepted, new String[] { "eval", "run", "smoke" }));
        assertEquals(3, accepted.messages.size());
        assertNotEquals("收掉遗留行后应新建 run", leftoverId, store.findRunningRun().getString("run_id"));
    }

    /** G10 + G15：中止成功后第二次 abort 回「当前没有进行中的评测」，不报错、不改库。 */
    @Test
    public void abortShouldBeIdempotent() {
        RecordingSender runnerSender = new RecordingSender(CommandPermission.OWNER, false);
        assertTrue(new EvalCommand(runner, config).execute(runnerSender,
                new String[] { "eval", "run", "smoke" }));
        String runId = store.findRunningRun().getString("run_id");

        RecordingSender first = new RecordingSender(CommandPermission.OWNER, false);
        assertTrue(new EvalCommand(runner, config).execute(first, new String[] { "eval", "abort" }));
        assertEquals("[eval] 已中止：" + runId + "（状态见 /eval status）", first.messages.get(0));
        JSONObject aborted = store.findRun(runId);
        assertEquals(EvalStore.STATUS_ABORTED, aborted.getString("status"));
        assertEquals("中止原因落在 error_summary", EvalStore.ABORT_REASON_USER,
                aborted.getString("error_summary"));

        RecordingSender second = new RecordingSender(CommandPermission.OWNER, false);
        assertTrue(new EvalCommand(runner, config).execute(second, new String[] { "eval", "abort" }));
        assertEquals("[eval] 当前没有进行中的评测", second.messages.get(0));
        assertEquals("重复 abort 不得改写终态", EvalStore.STATUS_ABORTED,
                store.findRun(runId).getString("status"));
        assertAllPrefixed(second);
    }

    /** §9.6：abort 回执不等 runner 线程收尾，命令线程直接返回；全程不产生调用行。 */
    @Test
    public void abortShouldReturnWithoutWaitingForRunThread() {
        RecordingSender runnerSender = new RecordingSender(CommandPermission.OWNER, false);
        assertTrue(new EvalCommand(runner, config).execute(runnerSender,
                new String[] { "eval", "run", "smoke" }));
        String runId = store.findRunningRun().getString("run_id");

        long begin = System.currentTimeMillis();
        RecordingSender aborter = new RecordingSender(CommandPermission.OWNER, false);
        assertTrue(new EvalCommand(runner, config).execute(aborter, new String[] { "eval", "abort" }));
        long elapsed = System.currentTimeMillis() - begin;

        assertEquals("[eval] 已中止：" + runId + "（状态见 /eval status）", aborter.messages.get(0));
        assertEquals(EvalStore.STATUS_ABORTED, store.findRun(runId).getString("status"));
        assertTrue("abort 命令线程不应等待 runner 线程，实际耗时 " + elapsed + "ms", elapsed < 2000L);
        assertTrue("中止路径不得发起调用", store.findCalls(runId).rows().isEmpty());
    }

    /** G11：受理回执发回发起会话，不依赖群上下文；群聊与私聊各自只收到自己那份。 */
    @Test
    public void receiptShouldGoBackToOriginatingConversation() {
        RecordingSender group = new RecordingSender(CommandPermission.OWNER, true);
        assertTrue(new EvalCommand(runner, config).execute(group, new String[] { "eval", "run", "smoke" }));
        assertEquals(3, group.messages.size());
        String groupRunId = store.findRunningRun().getString("run_id");
        assertEquals("[eval] 已受理：" + groupRunId, group.messages.get(0));

        assertTrue("结束群聊那次 run", runner.finalizeActive(EvalStore.STATUS_COMPLETED, null, null) != null);
        assertEquals(EvalStore.STATUS_COMPLETED, store.findRun(groupRunId).getString("status"));

        RecordingSender priv = new RecordingSender(CommandPermission.OWNER, false);
        assertTrue(new EvalCommand(runner, config).execute(priv, new String[] { "eval", "run", "smoke" }));
        assertEquals(3, priv.messages.size());
        String privRunId = store.findRunningRun().getString("run_id");
        assertEquals("[eval] 已受理：" + privRunId, priv.messages.get(0));
        assertNotEquals(groupRunId, privRunId);
        assertEquals("群聊回执不应混入私聊", 3, group.messages.size());
        assertAllPrefixed(group);
        assertAllPrefixed(priv);
    }

    /** §9.4：命令线程只做一次受理委派，不轮询、不中止、不自行收尾。 */
    @Test
    public void commandShouldDelegateAcceptExactlyOnce() {
        FakeRunService fake = new FakeRunService();
        fake.acceptResult = EvalRunner.AcceptResult.accepted("r-20261026003412-smoke-a1b2",
                EvalStore.TIER_SMOKE, 12, 25, 100);
        RecordingSender sender = new RecordingSender(CommandPermission.OWNER, false);

        assertTrue(new EvalCommand(fake, config).execute(sender, new String[] { "eval", "run", "smoke" }));

        assertEquals("受理只委派一次", 1, fake.accepts);
        assertEquals("smoke", fake.lastTier);
        assertEquals("受理必须在命令线程上执行", Thread.currentThread(), fake.acceptThread);
        assertEquals("命令层不得自行中止", 0, fake.aborts);
        assertEquals("命令层不得轮询快照", 0, fake.snapshots);
        assertEquals(3, sender.messages.size());
        assertEquals("[eval] 已受理：r-20261026003412-smoke-a1b2", sender.messages.get(0));
        assertEquals("[eval] " + EvalPreflightPlan.of(EvalStore.TIER_SMOKE).receipt(100).summary(),
                sender.messages.get(1));
        assertEquals("[eval] 状态 /eval status；中止 /eval abort", sender.messages.get(2));
    }

    /** §9.7：BUSY 只由服务返回的事实拼一行，命令层不补查状态、不新建 run。 */
    @Test
    public void commandShouldRenderBusyFromServiceResult() {
        FakeRunService fake = new FakeRunService();
        fake.acceptResult = EvalRunner.AcceptResult.busy("r-20261026003412-smoke-a1b2",
                EvalStore.TIER_SMOKE, "7/25", "可发 /eval status 查看进度，或 /eval abort 中止该 run");
        RecordingSender sender = new RecordingSender(CommandPermission.OWNER, false);

        assertTrue(new EvalCommand(fake, config).execute(sender, new String[] { "eval", "run", "smoke" }));

        assertEquals(1, fake.accepts);
        assertEquals(1, sender.messages.size());
        assertEquals("[eval] 已在进行中：r-20261026003412-smoke-a1b2，当前进度 7/25"
                + "；可发 /eval status 查看进度，或 /eval abort 中止该 run", sender.messages.get(0));
        assertEquals(0, fake.aborts);
        assertEquals(0, fake.snapshots);
    }

    /** §9.6：abort 只委派一次，回执直接取自服务结果，不等 runner 线程。 */
    @Test
    public void abortShouldDelegateExactlyOnce() {
        FakeRunService fake = new FakeRunService();
        fake.abortResult = EvalRunner.AbortResult.aborted("r-20261026003412-smoke-a1b2");
        RecordingSender sender = new RecordingSender(CommandPermission.OWNER, false);

        assertTrue(new EvalCommand(fake, config).execute(sender, new String[] { "eval", "abort" }));

        assertEquals(1, fake.aborts);
        assertEquals(0, fake.accepts);
        assertEquals("中止由命令线程直接发起", Thread.currentThread(), fake.abortThread);
        assertEquals(1, sender.messages.size());
        assertEquals("[eval] 已中止：r-20261026003412-smoke-a1b2（状态见 /eval status）",
                sender.messages.get(0));
    }

    /** 假 run 服务：只记录调用次数与线程，不启动任何后台任务（用来断言语义）。 */
    private static final class FakeRunService implements EvalRunService {
        private int accepts;
        private int aborts;
        private int snapshots;
        private String lastTier;
        private Thread acceptThread;
        private Thread abortThread;
        private EvalRunner.AcceptResult acceptResult;
        private EvalRunner.AbortResult abortResult = EvalRunner.AbortResult.none();

        @Override
        public EvalRunner.AcceptResult accept(String tier) {
            accepts++;
            lastTier = tier;
            acceptThread = Thread.currentThread();
            return acceptResult;
        }

        @Override
        public EvalRunner.AbortResult abort() {
            aborts++;
            abortThread = Thread.currentThread();
            return abortResult;
        }

        @Override
        public EvalRunner.RunSnapshot snapshot() {
            snapshots++;
            return null;
        }

        @Override
        public EvalRunner.RunSnapshot snapshot(String runId) {
            snapshots++;
            return null;
        }
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

    private static void assertAllPrefixed(RecordingSender sender) {
        for (String message : sender.messages) {
            assertTrue("所有回执必须带 [eval] 前缀：" + message, message.startsWith("[eval] "));
        }
    }

    /** 只收集日志的假件：不走主程序日志，避免测试写出日志文件。 */
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

    /** 记录回执的发送方；isGroup 可配，用于证明回执不依赖群上下文（G11）。 */
    private static final class RecordingSender implements CommandSender {
        private final Set<CommandPermission> granted = new LinkedHashSet<CommandPermission>();
        private final List<String> messages = new ArrayList<String>();
        private final boolean group;

        private RecordingSender(CommandPermission permission, boolean group) {
            granted.add(permission);
            this.group = group;
        }

        @Override
        public boolean isGroup() {
            return group;
        }

        @Override
        public long getGroupID() {
            return group ? 90001L : 0L;
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
    }
}
