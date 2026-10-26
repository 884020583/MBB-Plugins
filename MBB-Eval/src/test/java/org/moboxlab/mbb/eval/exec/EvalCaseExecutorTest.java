package org.moboxlab.mbb.eval.exec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.moboxlab.mbb.eval.TestEvalAssets;
import org.moboxlab.mbb.eval.call.EvalBudgetLedger;
import org.moboxlab.mbb.eval.call.EvalCallInvoker;
import org.moboxlab.mbb.eval.call.EvalCallPolicy;
import org.moboxlab.mbb.eval.call.EvalCallRecorder;
import org.moboxlab.mbb.eval.gateway.EvalGenerateRequest;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;
import org.moboxlab.mbb.eval.judge.EvalJudgeResult;
import org.moboxlab.mbb.eval.rubric.EvalRubric;
import org.moboxlab.mbb.eval.rubric.EvalRubricLoadResult;
import org.moboxlab.mbb.eval.rubric.EvalRubricLoader;
import org.moboxlab.mbb.eval.runner.EvalRunStopPoint;
import org.moboxlab.mbb.eval.score.EvalCaseScore;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.PluginLogger;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/** 单 case 执行器：确定性维零调用、LLM 维采样与中止路径（规格 §6、§9.5、§13）。 */
public final class EvalCaseExecutorTest {

    private static final String RUN_ID = "r-20261026220000-smoke-abcd";
    private static final String PROMPT_VERSION = "p1";

    private File tempDir;
    private TestLogger logger;
    private EvalStorageFake storage;
    private EvalStore store;
    private EvalRubric rubric;
    private JSONObject deterministic;
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
        deterministic = rubric.deterministic();
    }

    @After
    public void tearDown() {
        storage.close();
    }

    @Test
    public void knowledgeCaseShouldScoreLocallyWithoutCalls() {
        JSONObject caseJson = bankCase("knowledge");
        FakeGeneratePort generate = new FakeGeneratePort();
        EvalCaseResult result = new EvalCaseExecutor().execute(context(caseJson,
                "knowledge", generate, new FakeJudgePort(), newStopPoint(), newLedger(100)));

        assertEquals("knowledge", result.dimension());
        assertEquals(EvalCaseScore.Status.OK, result.status());
        assertEquals(0, result.calls());
        assertTrue(result.written());
        assertEquals(0, generate.requests.size());
        JSONObject row = store.findCaseResult(RUN_ID, caseJson.getString("id"));
        assertNotNull(row);
        assertEquals("ok", row.getString("status"));
        assertEquals(1, row.getIntValue("k_used"));
    }

    @Test
    public void styleCaseShouldUseDefaultMountWithoutCalls() {
        JSONObject caseJson = bankCase("style");
        caseJson.put("style", new JSONObject(true));
        FakeGeneratePort generate = new FakeGeneratePort();
        EvalCaseResult result = new EvalCaseExecutor().execute(context(caseJson,
                "style", generate, new FakeJudgePort(), newStopPoint(), newLedger(100)));

        assertEquals(0, result.calls());
        assertTrue(result.written());
        assertEquals(0, generate.requests.size());
        assertNotNull(store.findCaseResult(RUN_ID, caseJson.getString("id")));
    }

    @Test
    public void judgeCaseShouldSampleThreeTimesAndWriteRow() {
        JSONObject caseJson = bankCase("persona");
        FakeGeneratePort generate = new FakeGeneratePort();
        FakeJudgePort judge = new FakeJudgePort();
        judge.results.add(EvalJudgeResult.ok("persona", 2, "称呼与情感都对", 1, null, null, 0));
        judge.results.add(EvalJudgeResult.ok("persona", 2, "称呼与情感都对", 1, null, null, 0));
        judge.results.add(EvalJudgeResult.ok("persona", 2, "称呼与情感都对", 1, null, null, 0));

        EvalCaseResult result = new EvalCaseExecutor().execute(context(caseJson,
                "persona", generate, judge, newStopPoint(), newLedger(100)));

        assertEquals(EvalCaseScore.Status.OK, result.status());
        assertEquals(6, result.calls());
        assertEquals(3, generate.requests.size());
        assertEquals(3, judge.inputs.size());
        assertEquals(1.0D, result.caseScore().doubleValue(), 0.0001D);
        assertTrue(result.written());
        assertNotNull(store.parseDetailJson(store.findCaseResult(RUN_ID, caseJson.getString("id"))));
        assertEquals(6, store.findCalls(RUN_ID).size());
        assertEquals(6, store.findRun(RUN_ID).getIntValue("calls_used"));
    }

    @Test
    public void disabledShouldAbortWholeRunWithoutWritingRow() {
        JSONObject caseJson = bankCase("persona");
        FakeGeneratePort generate = new FakeGeneratePort();
        generate.override = EvalGenerateResult.error(EvalGenerateResult.ERROR_DISABLED, false,
                "评测接口未启用");

        EvalCaseResult result = new EvalCaseExecutor().execute(context(caseJson,
                "persona", generate, new FakeJudgePort(), newStopPoint(), newLedger(100)));

        assertTrue(result.abortRun());
        assertEquals(EvalGenerateResult.ERROR_DISABLED, result.abortReason());
        assertFalse(result.written());
        assertNull(store.findCaseResult(RUN_ID, caseJson.getString("id")));
    }

    @Test
    public void invalidParamShouldRecordErrorSamples() {
        JSONObject caseJson = bankCase("persona");
        FakeGeneratePort generate = new FakeGeneratePort();
        generate.override = EvalGenerateResult.error(EvalGenerateResult.ERROR_INVALID_PARAM, false,
                "history 不合法");

        EvalCaseResult result = new EvalCaseExecutor().execute(context(caseJson,
                "persona", generate, new FakeJudgePort(), newStopPoint(), newLedger(100)));

        assertEquals(EvalCaseScore.Status.ERROR, result.status());
        assertEquals(3, result.calls());
        assertTrue(result.written());
        JSONObject row = store.findCaseResult(RUN_ID, caseJson.getString("id"));
        assertEquals("error", row.getString("status"));
    }

    @Test
    public void budgetStopShouldAbortBeforeJudge() {
        JSONObject caseJson = bankCase("persona");
        FakeGeneratePort generate = new FakeGeneratePort();
        FakeJudgePort judge = new FakeJudgePort();
        judge.results.add(EvalJudgeResult.ok("persona", 2, "好", 1, null, null, 0));

        EvalCaseResult result = new EvalCaseExecutor().execute(context(caseJson,
                "persona", generate, judge, newStopPoint(), newLedger(1)));

        assertTrue(result.abortRun());
        assertEquals(EvalStore.ABORT_REASON_BUDGET, result.abortReason());
        assertFalse(result.written());
    }

    @Test
    public void requestedStopShouldSkipWrite() {
        JSONObject caseJson = bankCase("persona");
        EvalRunStopPoint stopPoint = newStopPoint();
        stopPoint.requestStop(EvalStore.ABORT_REASON_USER);

        EvalCaseResult result = new EvalCaseExecutor().execute(context(caseJson,
                "persona", new FakeGeneratePort(), new FakeJudgePort(), stopPoint, newLedger(100)));

        assertTrue(result.stopped());
        assertTrue(result.abortRun());
        assertEquals(EvalStore.ABORT_REASON_USER, result.abortReason());
        assertFalse(result.written());
        assertNull(store.findCaseResult(RUN_ID, caseJson.getString("id")));
    }

    private EvalCaseContext context(JSONObject caseJson, String dimension,
            EvalCaseGeneratePort generatePort, EvalCaseJudgePort judgePort,
            EvalRunStopPoint stopPoint, EvalBudgetLedger ledger) {
        EvalCallInvoker invoker = new EvalCallInvoker(ledger, new EvalCallRecorder(store, logger),
                new EvalCallPolicy(1, 3, new int[] {0, 0}), new NoopSleeper());
        return EvalCaseContext.builder(RUN_ID, caseJson.getString("id"))
                .tier(EvalStore.TIER_SMOKE)
                .dimension(dimension)
                .caseJson(caseJson)
                .rubric(rubric)
                .deterministicConfig(deterministic)
                .ledger(ledger)
                .invoker(invoker)
                .store(store)
                .generatePort(generatePort)
                .judgePort(judgePort)
                .clock(new FakeClock())
                .logger(logger)
                .stopPoint(stopPoint)
                .sampleCount(3)
                .generateProfile("eval")
                .generateTimeoutSeconds(60)
                .promptVersion(PROMPT_VERSION)
                .content("今天天气不错，我陪你聊聊天吧。")
                .build();
    }

    private EvalBudgetLedger newLedger(int limit) {
        return new EvalBudgetLedger(RUN_ID, limit, limit, System.currentTimeMillis() + 600000L,
                store, logger);
    }

    private EvalRunStopPoint newStopPoint() {
        return new EvalRunStopPoint(RUN_ID);
    }

    private JSONObject bankCase(String dimension) {
        JSONArray cases = bank.getJSONArray("cases");
        for (int i = 0; i < cases.size(); i++) {
            JSONObject item = cases.getJSONObject(i);
            if (dimension.equals(item.getString("dimension"))
                    && EvalStore.TIER_SMOKE.equals(item.getString("tier"))) {
                return item;
            }
        }
        throw new IllegalStateException("内置题库缺少 smoke 用例：" + dimension);
    }

    private static File createTempDir() throws IOException {
        File file = File.createTempFile("mbb-eval-exec", ".dir");
        if (!file.delete() || !file.mkdirs()) {
            throw new IOException("无法创建临时目录：" + file.getAbsolutePath());
        }
        return file;
    }

    private static void write(File file, byte[] bytes) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    /** 只收集日志。 */
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

        @Override
        public void sendException(Throwable throwable) {
        }
    }

    /** 固定时钟，每次读取前进 10ms，保证耗时不小于 0。 */
    private static final class FakeClock implements EvalCaseClock {
        private long now = 1700000000000L;

        @Override
        public long currentTimeMillis() {
            now += 10L;
            return now;
        }
    }

    private static final class NoopSleeper implements EvalCallInvoker.Sleeper {
        @Override
        public void sleepSeconds(int seconds) {
        }
    }

    private static final class FakeGeneratePort implements EvalCaseGeneratePort {
        private final List<EvalGenerateRequest> requests = new ArrayList<EvalGenerateRequest>();
        private EvalGenerateResult override;

        @Override
        public EvalGenerateResult generate(EvalGenerateRequest request) {
            requests.add(request);
            if (override != null) {
                return override;
            }
            return okWire(request.sessionId());
        }
    }

    private static final class FakeJudgePort implements EvalCaseJudgePort {
        private final List<EvalCaseJudgeInput> inputs = new ArrayList<EvalCaseJudgeInput>();
        private final List<EvalJudgeResult> results = new ArrayList<EvalJudgeResult>();
        private boolean aborted;
        private String abortReason;

        @Override
        public EvalJudgeResult judge(EvalCaseJudgeInput input) {
            inputs.add(input);
            if (results.isEmpty()) {
                return EvalJudgeResult.ok(input.dimensionId(), 2, "默认通过", 1, null, null, 0);
            }
            return results.remove(0);
        }

        @Override
        public boolean aborted() {
            return aborted;
        }

        @Override
        public String abortReason() {
            return abortReason;
        }
    }

    private static EvalGenerateResult okWire(String sessionId) {
        JSONObject wire = new JSONObject(true);
        wire.put("status", Boolean.TRUE);
        wire.put("action", "eval-generate");
        wire.put("requestId", sessionId);
        wire.put("sessionId", sessionId);
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
        JSONObject usage = new JSONObject(true);
        usage.put("promptTokens", Integer.valueOf(10));
        usage.put("completionTokens", Integer.valueOf(20));
        usage.put("totalTokens", Integer.valueOf(30));
        usage.put("cachedPromptTokens", Integer.valueOf(0));
        wire.put("usage", usage);
        return EvalGenerateResult.parse(wire, sessionId);
    }
}
