package org.moboxlab.mbb.eval.exec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.moboxlab.mbb.eval.TestEvalAssets;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.gateway.EvalGenerateRequest;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;
import org.moboxlab.mbb.eval.judge.EvalJudgeResult;
import org.moboxlab.mbb.eval.metrics.EvalAiUsageDay;
import org.moboxlab.mbb.eval.metrics.EvalAiUsageSource;
import org.moboxlab.mbb.eval.report.EvalGroupSummaryFormatter;
import org.moboxlab.mbb.eval.report.EvalReportData;
import org.moboxlab.mbb.eval.rubric.EvalRubric;
import org.moboxlab.mbb.eval.rubric.EvalRubricLoadResult;
import org.moboxlab.mbb.eval.rubric.EvalRubricLoader;
import org.moboxlab.mbb.eval.runner.EvalCompletionSink;
import org.moboxlab.mbb.eval.runner.EvalRunBudget;
import org.moboxlab.mbb.eval.runner.EvalRunner;
import org.moboxlab.mbb.eval.runner.EvalRunStopPoint;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.PluginLogger;

import com.alibaba.fastjson.JSONObject;

/** run 主循环：档位子集、题间中止、聚合与终态/报表接线（规格 §9.4–§9.6、§13.4、§16.7）。 */
public final class EvalRunLoopTest {

    private static final String RUN_ID = "r-20261026220000-smoke-abcd";
    private static final String PROMPT_VERSION = "p1";

    private File tempDir;
    private TestLogger logger;
    private EvalStorageFake storage;
    private EvalStore store;
    private EvalRubric rubric;
    private JSONObject bank;

    @Before
    public void setUp() throws Exception {
        tempDir = createTempDir();
        logger = new TestLogger();
        storage = new EvalStorageFake(new File(tempDir, "eval.db"));
        store = new EvalStore(storage, logger);
        assertTrue(new EvalSchemaService(storage, logger).ensureSchema());
        assertTrue(store.insertRunning(RUN_ID, EvalStore.TIER_SMOKE, EvalStore.nowText(), 100,
                "V0.1.0.0.0000"));
        bank = TestEvalAssets.builtinBank();
        File rubricFile = new File(tempDir, TestEvalAssets.RUBRIC_FILE_NAME);
        write(rubricFile, TestEvalAssets.builtinRubricBytes());
        EvalRubricLoadResult loaded = EvalRubricLoader.load(rubricFile,
                TestEvalAssets.builtinRubric().getString("promptVersion"));
        assertTrue(loaded.problems().toString(), loaded.ok());
        rubric = loaded.rubric();
    }

    @After
    public void tearDown() {
        storage.close();
    }

    @Test
    public void smokeTierShouldRunTwelveCasesAndPersistResults() {
        FakeGeneratePort generate = new FakeGeneratePort();
        EvalRunLoop.Result result = newLoop(generate, new FakeJudgePort(), new File(tempDir, "reports"))
                .run(request(futureBudget(25, 100), newStopPoint()));

        assertEquals(EvalRunLoop.Status.COMPLETED, result.status());
        assertFalse(result.terminalInStore());
        assertNull(result.abortReason());
        assertEquals(12, result.plannedCases());
        assertEquals(12, result.executedCases());
        // 生成侧每题一次（6 个 judge 维用例）+ judge 侧 10 次（persona 2 + memory 2 + boundary 2×3）。
        assertEquals(16, result.callsUsed());
        // §9.5：smoke 计划调用 25 次（12 生成 + 10 judge + 3 校准），实际不得超计划。
        assertTrue("smoke 调用数 " + result.callsUsed() + " 超过计划 25",
                result.callsUsed() <= 25);
        assertEquals(12, store.findCaseResults(RUN_ID).size());
        assertEquals(16, store.findCalls(RUN_ID).size());
        assertEquals(16, store.findRun(RUN_ID).getIntValue("calls_used"));
        assertNotNull(result.score());
        assertNotNull(result.score().total());
        assertTrue("摘要不超过 15 行", result.summaryLines().size() <= 15);
        assertTrue(result.summaryText().contains("COMPLETED"));
        // 主循环本身不写终态，终态由 runner 收口（§4.4）。
        assertEquals(EvalStore.STATUS_RUNNING, store.findRun(RUN_ID).getString("status"));
    }

    @Test
    public void budgetAbortShouldStopBeforeOverrun() {
        FakeGeneratePort generate = new FakeGeneratePort();
        EvalRunLoop.Result result = newLoop(generate, new FakeJudgePort(), new File(tempDir, "reports"))
                .run(request(futureBudget(25, 1), newStopPoint()));

        assertEquals(EvalRunLoop.Status.ABORTED, result.status());
        assertEquals(EvalStore.ABORT_REASON_BUDGET, result.abortReason());
        assertEquals(1, result.callsUsed());
        assertEquals(0, store.findCaseResults(RUN_ID).size());
        assertEquals(1, store.findRun(RUN_ID).getIntValue("calls_used"));
    }

    @Test
    public void deadlineAbortShouldStopBeforeFirstCase() {
        EvalRunBudget budget = new EvalRunBudget(25, 100, System.currentTimeMillis() - 1000L);
        EvalRunLoop.Result result = newLoop(new FakeGeneratePort(), new FakeJudgePort(),
                new File(tempDir, "reports")).run(request(budget, newStopPoint()));

        assertEquals(EvalRunLoop.Status.ABORTED, result.status());
        assertEquals(EvalStore.ABORT_REASON_DEADLINE, result.abortReason());
        assertEquals(0, result.executedCases());
        assertEquals(0, result.callsUsed());
        assertEquals(0, store.findCaseResults(RUN_ID).size());
        assertTrue(budget.isAborted());
    }

    @Test
    public void stopPointShouldAbortBetweenCases() {
        EvalRunStopPoint stopPoint = newStopPoint();
        stopPoint.requestStop(EvalStore.ABORT_REASON_USER);
        EvalRunLoop.Result result = newLoop(new FakeGeneratePort(), new FakeJudgePort(),
                new File(tempDir, "reports")).run(request(futureBudget(25, 100), stopPoint));

        assertEquals(EvalRunLoop.Status.ABORTED, result.status());
        assertEquals(EvalStore.ABORT_REASON_USER, result.abortReason());
        assertEquals(0, result.executedCases());
        assertEquals(0, store.findCalls(RUN_ID).size());
    }

    @Test
    public void judgeBreakerShouldAbortWithAiUnavailable() {
        FakeJudgePort judge = new FakeJudgePort();
        judge.aborted = true;
        judge.abortReason = "ai-unavailable";
        EvalRunLoop.Result result = newLoop(new FakeGeneratePort(), judge, new File(tempDir, "reports"))
                .run(request(futureBudget(25, 100), newStopPoint()));

        assertEquals(EvalRunLoop.Status.ABORTED, result.status());
        assertEquals("ai-unavailable", result.abortReason());
        assertEquals(0, store.findCaseResults(RUN_ID).size());
    }

    @Test
    public void terminalStateInStoreShouldStopWithoutSecondFinalize() {
        assertEquals(1, store.casAborted(RUN_ID, EvalStore.nowText(), 0L, EvalStore.ABORT_REASON_USER));
        EvalRunLoop.Result result = newLoop(new FakeGeneratePort(), new FakeJudgePort(),
                new File(tempDir, "reports")).run(request(futureBudget(25, 100), probedStopPoint()));

        assertTrue(result.terminalInStore());
        assertEquals(EvalRunLoop.Status.ABORTED, result.status());
        assertEquals(0, result.executedCases());
        assertEquals(EvalStore.STATUS_ABORTED, store.findRun(RUN_ID).getString("status"));
        assertEquals(0, store.findCaseResults(RUN_ID).size());
    }

    @Test
    public void fullTierShouldRunAllFortyCases() {
        EvalRunLoop.Result result = newLoop(new FakeGeneratePort(), new FakeJudgePort(),
                new File(tempDir, "reports")).run(EvalRunLoop.Request.of(RUN_ID, EvalStore.TIER_FULL,
                        new EvalRunBudget(79, 200, System.currentTimeMillis() + 600000L), newStopPoint()));

        assertEquals(EvalRunLoop.Status.COMPLETED, result.status());
        assertEquals(40, result.plannedCases());
        assertEquals(40, result.executedCases());
        // 生成 24 次（judge 维 24 题各一次）+ judge 36 次（12×1 + 6×1 + 6×3）。
        assertEquals(60, result.callsUsed());
        assertEquals(40, store.findCaseResults(RUN_ID).size());
    }

    /** F-8b/§6.1/§7.5：终态报表必须用 rubric 权重与注入的 L3 旁证源。 */
    @Test
    public void reportShouldUseInjectedWeightsAndUsageSource() throws Exception {
        final List<String> queries = new ArrayList<String>();
        final Map<String, Double> weights = customWeights();
        EvalRunLoop loop = new EvalRunLoop(new EvalRunLoop.Deps()
                .store(store)
                .config(EvalConfig.load(null))
                .bank(bank)
                .rubric(rubric)
                .generatePort(new FakeGeneratePort())
                .judgePort(new FakeJudgePort())
                .reportsDirectory(new File(tempDir, "reports"))
                .logger(logger)
                .reportWeights(weights)
                .aiUsageSource(new EvalAiUsageSource() {
                    @Override
                    public List<EvalAiUsageDay> queryDailyUsage(String profile, String startDate,
                            String endDate) {
                        queries.add(profile + "@" + startDate + "~" + endDate);
                        return new ArrayList<EvalAiUsageDay>();
                    }
                }));
        EvalRunLoop.Result result = loop.run(request(futureBudget(25, 100), newStopPoint()));
        assertEquals(EvalRunLoop.Status.COMPLETED, result.status());

        // 主循环不写终态；补上 profile 与结束时间，让报表能按 §7.5 取旁证。
        Statement statement = storage.connection().createStatement();
        try {
            statement.executeUpdate("UPDATE plugin_mbb_eval_run SET eval_profile='default',"
                    + " ended_at='" + EvalStore.nowText() + "' WHERE run_id='" + RUN_ID + "'");
        } finally {
            statement.close();
        }

        EvalReportData data = result.reportData(store);
        assertEquals("报表必须采用 rubric 五维权重", weights, data.weights());
        assertNotNull("报表必须带 L3 旁证对象", data.aiUsage());
        assertFalse("必须调用注入的旁证源", queries.isEmpty());
        assertTrue("旁证查询应使用 run 的 eval_profile：" + queries, queries.get(0).startsWith("default@"));
    }

    private static Map<String, Double> customWeights() {
        Map<String, Double> weights = new LinkedHashMap<String, Double>();
        weights.put("persona", Double.valueOf(0.40D));
        weights.put("boundary", Double.valueOf(0.20D));
        weights.put("memory", Double.valueOf(0.20D));
        weights.put("knowledge", Double.valueOf(0.10D));
        weights.put("style", Double.valueOf(0.10D));
        return weights;
    }

    @Test
    public void completedRunShouldWriteReportAndReportPath() throws Exception {
        File reportsDir = new File(tempDir, "reports");
        EvalRunLoop loop = newLoop(new FakeGeneratePort(), new FakeJudgePort(), reportsDir);
        EvalRunner runner = new EvalRunner(store, EvalConfig.load(null), null, logger,
                "V0.1.0.0.0000", loop);
        // 先收口 setUp 里预置的 RUNNING 行，避免单飞检查按「遗留 RUNNING」返回 BUSY。
        assertEquals(1, store.casAborted(RUN_ID, EvalStore.nowText(), 0L, "test-cleanup"));
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_SMOKE);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());

        String runId = accepted.runId();
        JSONObject row = waitForTerminal(runId, 30000L);
        assertEquals(EvalStore.STATUS_COMPLETED, row.getString("status"));
        assertNotNull(row.getString("ended_at"));
        assertEquals("reports/" + "run-" + runId + ".md", row.getString("report_path"));
        File report = new File(reportsDir, "run-" + runId + ".md");
        assertTrue("报表文件应存在：" + report.getAbsolutePath(), report.isFile());
        assertTrue(report.length() > 0L);
        runner.shutdown();
    }


    /**
     * SUM-1 / §9.3.1 / §9.4 第 4 步 / G-11 / I-2：COMPLETED 收尾、报表落盘后，把 ≤15 行纯文本摘要
     * 发回登记的回送目标；摘要含 run_id 与报表相对路径，不带 Markdown 与 emoji。
     */
    @Test
    public void completedRunShouldSendPlainTextSummaryToSink() throws Exception {
        File reportsDir = new File(tempDir, "reports");
        EvalRunLoop loop = newLoop(new FakeGeneratePort(), new FakeJudgePort(), reportsDir);
        EvalRunner runner = new EvalRunner(store, EvalConfig.load(null), null, logger,
                "V0.1.0.0.0000", loop);
        // 先收口 setUp 里预置的 RUNNING 行，避免单飞检查按「遗留 RUNNING」返回 BUSY。
        assertEquals(1, store.casAborted(RUN_ID, EvalStore.nowText(), 0L, "test-cleanup"));
        RecordingSink sink = new RecordingSink();
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_SMOKE, sink);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
        try {
            String runId = accepted.runId();
            JSONObject row = waitForTerminal(runId, 30000L);
            assertEquals(EvalStore.STATUS_COMPLETED, row.getString("status"));

            String text = sink.await(10000L);
            assertNotNull("COMPLETED 必须发送完成摘要", text);
            assertTrue("摘要必须含 run_id：" + text, text.contains(runId));
            assertTrue("摘要必须含报表相对路径：reports/run-" + runId + ".md，实际：" + text,
                    text.contains("reports/run-" + runId + ".md"));
            assertTrue("摘要行数不得超过 15 行：" + text,
                    text.split("\n", -1).length <= EvalGroupSummaryFormatter.MAX_LINES);
            assertFalse("摘要不得含 Markdown 强调标记：" + text, text.contains("**"));
            assertFalse("摘要不得含 Markdown 标题标记：" + text, text.contains("#"));
            assertFalse("摘要不得含 emoji：" + text, containsEmoji(text));
            assertTrue("摘要应以固定回执头开始：" + text, text.startsWith("【MBB-Eval】"));
        } finally {
            runner.shutdown();
        }
    }

    /** SUM-1 / §9.3.1、§8.6：ABORTED 不发送完成摘要，只记一行原因。 */
    @Test
    public void abortedRunShouldNotSendSummary() throws Exception {
        File reportsDir = new File(tempDir, "reports");
        BlockingGeneratePort generate = new BlockingGeneratePort();
        EvalRunLoop loop = newLoop(generate, new FakeJudgePort(), reportsDir);
        EvalRunner runner = new EvalRunner(store, EvalConfig.load(null), null, logger,
                "V0.1.0.0.0000", loop);
        assertEquals(1, store.casAborted(RUN_ID, EvalStore.nowText(), 0L, "test-cleanup"));
        RecordingSink sink = new RecordingSink();
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_SMOKE, sink);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
        try {
            assertTrue("主循环应已进入执行阶段", generate.awaitEntered(10000L));
            assertEquals(EvalRunner.AbortKind.ABORTED, runner.abort().kind());
            JSONObject row = waitForTerminal(accepted.runId(), 30000L);
            assertEquals(EvalStore.STATUS_ABORTED, row.getString("status"));
            generate.release();
            assertNull("ABORTED 不得发送完成摘要", sink.await(1500L));
        } finally {
            generate.release();
            runner.shutdown();
        }
    }

    /** SUM-1 / §9.4 判定规则 7：摘要发送失败只记 WARN，不改 run 终态，也不影响报表落盘。 */
    @Test
    public void sinkFailureShouldNotChangeTerminalStateOrReport() throws Exception {
        File reportsDir = new File(tempDir, "reports");
        EvalRunLoop loop = newLoop(new FakeGeneratePort(), new FakeJudgePort(), reportsDir);
        EvalRunner runner = new EvalRunner(store, EvalConfig.load(null), null, logger,
                "V0.1.0.0.0000", loop);
        assertEquals(1, store.casAborted(RUN_ID, EvalStore.nowText(), 0L, "test-cleanup"));
        RecordingSink sink = new RecordingSink();
        sink.failure = new RuntimeException("发送通道已断开");
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_SMOKE, sink);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
        try {
            String runId = accepted.runId();
            JSONObject row = waitForTerminal(runId, 30000L);
            assertEquals(EvalStore.STATUS_COMPLETED, row.getString("status"));
            File report = new File(reportsDir, "run-" + runId + ".md");
            assertTrue("发送失败不得影响报表：" + report.getAbsolutePath(), report.isFile());
            assertTrue("发送失败应记 WARN：" + logger.warns, awaitWarn("完成摘要发送失败", 10000L));
            assertEquals("发送失败不得改写终态", EvalStore.STATUS_COMPLETED,
                    store.findRun(runId).getString("status"));
        } finally {
            runner.shutdown();
        }
    }

    /** SUM-1 兼容：旧 accept(tier) 不登记回送目标，终态与报表行为与改动前一致。 */
    @Test
    public void legacyAcceptWithoutSinkShouldKeepTerminalStateAndReport() throws Exception {
        File reportsDir = new File(tempDir, "reports");
        EvalRunLoop loop = newLoop(new FakeGeneratePort(), new FakeJudgePort(), reportsDir);
        EvalRunner runner = new EvalRunner(store, EvalConfig.load(null), null, logger,
                "V0.1.0.0.0000", loop);
        assertEquals(1, store.casAborted(RUN_ID, EvalStore.nowText(), 0L, "test-cleanup"));
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_SMOKE);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
        try {
            String runId = accepted.runId();
            JSONObject row = waitForTerminal(runId, 30000L);
            assertEquals(EvalStore.STATUS_COMPLETED, row.getString("status"));
            assertEquals("reports/run-" + runId + ".md", row.getString("report_path"));
            assertTrue(new File(reportsDir, "run-" + runId + ".md").isFile());
            assertFalse("旧入口不得发送完成摘要：" + logger.warns, hasWarn("完成摘要发送失败"));
        } finally {
            runner.shutdown();
        }
    }

    private EvalRunLoop newLoop(EvalCaseGeneratePort generate, EvalCaseJudgePort judge, File reportsDir) {
        return new EvalRunLoop(new EvalRunLoop.Deps()
                .store(store)
                .config(EvalConfig.load(null))
                .bank(bank)
                .rubric(rubric)
                .generatePort(generate)
                .judgePort(judge)
                .reportsDirectory(reportsDir)
                .logger(logger));
    }

    private EvalRunLoop.Request request(EvalRunBudget budget, EvalRunStopPoint stopPoint) {
        return EvalRunLoop.Request.of(RUN_ID, EvalStore.TIER_SMOKE, budget, stopPoint);
    }

    private EvalRunBudget futureBudget(int planned, int limit) {
        return new EvalRunBudget(planned, limit, System.currentTimeMillis() + 600000L);
    }

    private EvalRunStopPoint newStopPoint() {
        return new EvalRunStopPoint(RUN_ID);
    }

    /** 带库探针的停止点：能观察到「已被其他路径收口」的 TERMINAL_IN_STORE。 */
    private EvalRunStopPoint probedStopPoint() {
        return new EvalRunStopPoint(RUN_ID, new EvalRunStopPoint.RunStatusProbe() {
            @Override
            public boolean terminal() {
                JSONObject row = store.findRun(RUN_ID);
                return row != null && !EvalStore.STATUS_RUNNING.equals(row.getString("status"));
            }
        });
    }

    /** 等待日志里出现包含关键字的 WARN，摘要发送在终态之后异步发生，不能只看终态。 */
    private boolean awaitWarn(String keyword, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (hasWarn(keyword)) {
                return true;
            }
            Thread.sleep(50L);
        }
        return hasWarn(keyword);
    }

    private boolean hasWarn(String keyword) {
        for (String message : logger.warns) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 规格 §8.6：摘要不得含 emoji；覆盖常见表情区与变体选择符。 */
    private static boolean containsEmoji(String text) {
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (codePoint == 0xFE0F || codePoint == 0x200D
                    || (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
                    || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                    || (codePoint >= 0x2B00 && codePoint <= 0x2BFF)) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    private JSONObject waitForTerminal(String runId, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            JSONObject row = store.findRun(runId);
            if (row != null && !EvalStore.STATUS_RUNNING.equals(row.getString("status"))) {
                return row;
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("run 未在超时前收口：" + runId);
    }

    private static File createTempDir() throws IOException {
        File dir = File.createTempFile("mbb-eval-loop", ".dir");
        assertTrue(dir.delete());
        assertTrue(dir.mkdirs());
        return dir;
    }

    private static void write(File file, byte[] bytes) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    /** 记录日志的测试桩。 */
    private static final class TestLogger extends PluginLogger {
        private final List<String> warns = new ArrayList<String>();

        private TestLogger() {
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
            warns.add(message);
        }
    }

    /** 记录完成摘要的测试桩；可配置发送抛异常，模拟回送通道断开。 */
    private static final class RecordingSink implements EvalCompletionSink {
        private final CountDownLatch sent = new CountDownLatch(1);
        private final List<String> texts = new ArrayList<String>();
        private RuntimeException failure;

        @Override
        public void send(String text) {
            texts.add(text);
            if (failure != null) {
                throw failure;
            }
            sent.countDown();
        }

        /** 等到摘要发出并返回正文；超时或发送抛异常返回 null。 */
        private String await(long timeoutMillis) throws InterruptedException {
            return sent.await(timeoutMillis, TimeUnit.MILLISECONDS) ? texts.get(0) : null;
        }
    }

    /** 阻塞首题生成的假端口：让测试在收口前确定主循环已进入执行阶段。 */
    private static final class BlockingGeneratePort extends FakeGeneratePort {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public EvalGenerateResult generate(EvalGenerateRequest request) {
            entered.countDown();
            try {
                release.await(10L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.generate(request);
        }

        private boolean awaitEntered(long timeoutMillis) throws InterruptedException {
            return entered.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        private void release() {
            release.countDown();
        }
    }

    /** 假生成端口：返回一条固定成功出参，不联网。 */
    private static class FakeGeneratePort implements EvalCaseGeneratePort {
        private final List<EvalGenerateRequest> requests = new ArrayList<EvalGenerateRequest>();

        @Override
        public EvalGenerateResult generate(EvalGenerateRequest request) {
            requests.add(request);
            JSONObject usage = new JSONObject(true);
            usage.put("promptTokens", Integer.valueOf(10));
            usage.put("completionTokens", Integer.valueOf(20));
            usage.put("totalTokens", Integer.valueOf(30));
            usage.put("cachedPromptTokens", Integer.valueOf(0));
            JSONObject wire = new JSONObject(true);
            wire.put("status", Boolean.TRUE);
            wire.put("action", "eval-generate");
            wire.put("requestId", request.sessionId());
            wire.put("sessionId", request.sessionId());
            wire.put("content", "谢谢老师，我今天把作业写完了。");
            wire.put("skip", Boolean.FALSE);
            wire.put("finishReason", "stop");
            wire.put("model", "test-model");
            wire.put("profile", "eval");
            wire.put("personaId", "aris");
            wire.put("personaHash", "19e6d6be6d99");
            wire.put("elapsedMs", Long.valueOf(10L));
            wire.put("cached", Boolean.FALSE);
            wire.put("attempts", Integer.valueOf(1));
            wire.put("usage", usage);
            return EvalGenerateResult.parse(wire, request.sessionId());
        }
    }

    /** 假判分端口：默认全部通过，可模拟熔断。 */
    private static final class FakeJudgePort implements EvalCaseJudgePort {
        private final List<EvalCaseJudgeInput> inputs = new ArrayList<EvalCaseJudgeInput>();
        private boolean aborted;
        private String abortReason;

        @Override
        public EvalJudgeResult judge(EvalCaseJudgeInput input) {
            inputs.add(input);
            if (aborted) {
                return EvalJudgeResult.error(input.dimensionId(), EvalJudgeResult.KIND_TRANSPORT,
                        "ai-unavailable", false, 0, null, "判分已熔断", null, 0);
            }
            return EvalJudgeResult.ok(input.dimensionId(), 2, "默认通过", 1, null, null, 0);
        }

        @Override
        public boolean aborted() {
            return aborted;
        }

        @Override
        public String abortReason() {
            return aborted ? abortReason : null;
        }
    }
}
