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
import org.moboxlab.mbb.eval.calib.EvalCalibrationJudgePort;
import org.moboxlab.mbb.eval.calib.EvalCalibrationSample;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.deterministic.EvalVetoPrecheck;
import org.moboxlab.mbb.eval.gateway.EvalGenerateRequest;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;
import org.moboxlab.mbb.eval.judge.EvalJudgeResult;
import org.moboxlab.mbb.eval.rubric.EvalRubric;
import org.moboxlab.mbb.eval.rubric.EvalRubricLoadResult;
import org.moboxlab.mbb.eval.rubric.EvalRubricLoader;
import org.moboxlab.mbb.eval.runner.EvalRunBudget;
import org.moboxlab.mbb.eval.runner.EvalRunner;
import org.moboxlab.mbb.eval.runner.EvalRunStopPoint;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.PluginLogger;

import com.alibaba.fastjson.JSONObject;

/**
 * CALW-1：校准自证接入 run 主流程（规格 §6.7.4、§6.8.1、§9.5、§8.6 第 8 节）。
 *
 * <p>覆盖：首次基线 full run 的 3 次 calib 调用与预算记账、非首次与 smoke 不执行校准、
 * judge 全失败不中止 run、校准样本不参与五维聚合、calibration_json 写入失败只 WARN。</p>
 */
public final class EvalCalibrationWiringTest {

    private static final String RUNNING_ID = "r-20261026220000-smoke-abcd";
    private static final String FULL_RUN_ID = "r-20261026220000-full-aaaa";
    private static final String FULL_RUN_ID_2 = "r-20261026220000-full-bbbb";

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
        assertTrue(store.insertRunning(RUNNING_ID, EvalStore.TIER_SMOKE, EvalStore.nowText(), 100,
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

    /** §6.7.4 / §6.8.1：首次基线 full run 执行 3 次 calib，落 3 行 stage=calib 并计入 calls_used。 */
    @Test
    public void firstFullRunShouldRunThreeCalibrationCallsAndPersistJson() throws Exception {
        EvalRunner runner = newRunner();
        assertEquals(1, store.casAborted(RUNNING_ID, EvalStore.nowText(), 0L, "test-cleanup"));
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_FULL);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
        try {
            String runId = accepted.runId();
            JSONObject row = waitForTerminal(runId, 30000L);
            assertEquals(EvalStore.STATUS_COMPLETED, row.getString("status"));

            assertEquals("full 60 次 + 校准 3 次", 63, row.getIntValue("calls_used"));
            assertEquals(63, store.findCalls(runId).size());
            assertEquals(3, calibrationCallCount(runId));

            String calibrationJson = row.getString("calibration_json");
            assertNotNull("首次基线 full run 必须写 calibration_json", calibrationJson);
            JSONObject json = JSONObject.parseObject(calibrationJson);
            assertTrue("校准应通过：" + calibrationJson, json.getBooleanValue("passed"));
            assertTrue(json.getBooleanValue("personaPassed"));
            assertTrue(json.getBooleanValue("memoryPassed"));
            assertTrue(json.getBooleanValue("boundaryPassed"));
            assertFalse(json.getBooleanValue("aborted"));
            assertTrue("边界硬项 veto 应命中：" + calibrationJson,
                    json.getBooleanValue("boundaryVetoed"));
            assertFalse(json.getBooleanValue("judgeJitterWarning"));
            assertEquals(1.0d, json.getDoubleValue("boundarySameRate"), 0.0001d);
            assertTrue(json.getJSONArray("flippedCases").isEmpty());
            assertEquals(3, json.getIntValue("executedSamples"));
            assertEquals(3, json.getJSONArray("samples").size());
        } finally {
            runner.shutdown();
        }
    }

    /** §6.7.4：已有 active 基线（非首次基线 run）不执行校准，calibration_json 保持 null。 */
    @Test
    public void existingBaselineShouldSkipCalibration() throws Exception {
        JSONObject baseline = new JSONObject();
        baseline.put("baseline_id", "b-20261026210000-aaaa");
        baseline.put("run_id", RUNNING_ID);
        assertEquals(EvalStore.WriteResult.APPLIED, store.insertBaseline(baseline));

        EvalRunner runner = newRunner();
        assertEquals(1, store.casAborted(RUNNING_ID, EvalStore.nowText(), 0L, "test-cleanup"));
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_FULL);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
        try {
            String runId = accepted.runId();
            JSONObject row = waitForTerminal(runId, 30000L);
            assertEquals(EvalStore.STATUS_COMPLETED, row.getString("status"));
            assertNull("非首次基线 run 不得写 calibration_json", row.getString("calibration_json"));
            assertEquals(0, calibrationCallCount(runId));
            assertEquals("没有校准时 full 仍为 60 次", 60, row.getIntValue("calls_used"));
        } finally {
            runner.shutdown();
        }
    }

    /** smoke 档即使受理时没有 baseline 也不执行校准（本片口径收紧 §9.5 的 smoke 含 3 次）。 */
    @Test
    public void smokeTierShouldSkipCalibration() throws Exception {
        EvalRunner runner = newRunner();
        assertEquals(1, store.casAborted(RUNNING_ID, EvalStore.nowText(), 0L, "test-cleanup"));
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_SMOKE);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
        try {
            String runId = accepted.runId();
            JSONObject row = waitForTerminal(runId, 30000L);
            assertEquals(EvalStore.STATUS_COMPLETED, row.getString("status"));
            assertNull(row.getString("calibration_json"));
            assertEquals(0, calibrationCallCount(runId));
            assertEquals(16, row.getIntValue("calls_used"));
        } finally {
            runner.shutdown();
        }
    }

    /** 主循环层面的兜底：即使调用方误把 smoke 请求标成首次基线，也不发起校准。 */
    @Test
    public void smokeRequestWithFirstBaselineFlagShouldStillSkipCalibration() {
        EvalRunLoop.Result result = newLoop(new FakeGeneratePort(), new FakeJudgePort(),
                new FakeCalibrationPort())
                .run(EvalRunLoop.Request.of(RUNNING_ID, EvalStore.TIER_SMOKE,
                        futureBudget(25, 100), new EvalRunStopPoint(RUNNING_ID), true));

        assertEquals(EvalRunLoop.Status.COMPLETED, result.status());
        assertNull(result.calibrationJson());
        assertEquals(16, result.callsUsed());
        assertEquals(0, calibrationCallCount(RUNNING_ID));
    }

    /** §6.7.4：校准 judge 全失败时只中止校准步骤，run 仍按正式题集完成并写失败原因。 */
    @Test
    public void calibrationJudgeFailureShouldNotAbortRun() throws Exception {
        FakeCalibrationPort calibration = new FakeCalibrationPort();
        calibration.failAll = true;
        EvalRunner runner = newRunner(calibration);
        assertEquals(1, store.casAborted(RUNNING_ID, EvalStore.nowText(), 0L, "test-cleanup"));
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_FULL);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
        try {
            String runId = accepted.runId();
            JSONObject row = waitForTerminal(runId, 30000L);
            assertEquals("校准失败不得改变 run 终态", EvalStore.STATUS_COMPLETED, row.getString("status"));
            assertEquals(61, row.getIntValue("calls_used"));
            assertEquals(1, calibrationCallCount(runId));

            String calibrationJson = row.getString("calibration_json");
            assertNotNull(calibrationJson);
            JSONObject json = JSONObject.parseObject(calibrationJson);
            assertFalse(json.getBooleanValue("passed"));
            assertTrue(json.getBooleanValue("aborted"));
            assertEquals(1, json.getIntValue("executedSamples"));
            assertTrue("失败原因应落库：" + calibrationJson,
                    json.getString("abortReason").contains("判分失败"));
        } finally {
            runner.shutdown();
        }
    }

    /** §6.7.4 / §6.7.2：边界题 k=3 标签不全同时写抖动告警与翻转题清单。 */
    @Test
    public void boundaryJitterShouldWarnWithFlippedCasesInCalibrationJson() throws Exception {
        EvalRunner runner = newRunner(new FakeCalibrationPort(), new FlippingBoundaryJudgePort());
        assertEquals(1, store.casAborted(RUNNING_ID, EvalStore.nowText(), 0L, "test-cleanup"));
        EvalRunner.AcceptResult accepted = runner.accept(EvalStore.TIER_FULL);
        assertEquals(EvalRunner.AcceptKind.ACCEPTED, accepted.kind());
        try {
            String runId = accepted.runId();
            JSONObject row = waitForTerminal(runId, 30000L);
            assertEquals(EvalStore.STATUS_COMPLETED, row.getString("status"));
            String calibrationJson = row.getString("calibration_json");
            assertNotNull(calibrationJson);
            JSONObject json = JSONObject.parseObject(calibrationJson);
            assertFalse("抖动告警应使校准不通过：" + calibrationJson, json.getBooleanValue("passed"));
            assertTrue("边界标签翻转应触发抖动告警：" + calibrationJson,
                    json.getBooleanValue("judgeJitterWarning"));
            assertEquals(0.0d, json.getDoubleValue("boundarySameRate"), 0.0001d);
            assertEquals(6, json.getJSONArray("flippedCases").size());
        } finally {
            runner.shutdown();
        }
    }

    /** §6.7.4：校准样本只做自证，不进入五维聚合；同一题集的总分与不校准时一致。 */
    @Test
    public void calibrationSamplesShouldNotAffectFiveDimensionScore() {
        assertTrue(store.insertRunning(FULL_RUN_ID, EvalStore.TIER_FULL, EvalStore.nowText(), 100,
                "V0.1.0.0.0000"));
        assertTrue(store.insertRunning(FULL_RUN_ID_2, EvalStore.TIER_FULL, EvalStore.nowText(), 100,
                "V0.1.0.0.0000"));

        EvalRunLoop withCalibration = newLoop(new FakeGeneratePort(), new FakeJudgePort(),
                new FakeCalibrationPort());
        EvalRunLoop.Result calibrated = withCalibration.run(EvalRunLoop.Request.of(FULL_RUN_ID,
                EvalStore.TIER_FULL, futureBudget(79, 200), new EvalRunStopPoint(FULL_RUN_ID), true));
        EvalRunLoop.Result plain = withCalibration.run(EvalRunLoop.Request.of(FULL_RUN_ID_2,
                EvalStore.TIER_FULL, futureBudget(79, 200), new EvalRunStopPoint(FULL_RUN_ID_2), false));

        assertEquals(EvalRunLoop.Status.COMPLETED, calibrated.status());
        assertEquals(EvalRunLoop.Status.COMPLETED, plain.status());
        assertNotNull(calibrated.score());
        assertNotNull(plain.score());
        assertEquals(plain.score().total(), calibrated.score().total());
        assertEquals(63, calibrated.callsUsed());
        assertEquals(60, plain.callsUsed());
        assertNotNull(calibrated.calibrationJson());
        assertNull(plain.calibrationJson());
    }

    /** §13.4：calibration_json 写入 update=0/-1 时只 WARN 返回 false，run 终态不受影响。 */
    @Test
    public void writeCalibrationJsonShouldFailSoftlyOnNoopOrError() {
        storage.failNextUpdate(1);
        assertFalse(store.writeCalibrationJson(RUNNING_ID, "{\"passed\":false}"));
        assertTrue(hasWarn("calibration_json"));

        assertEquals(1, store.casAborted(RUNNING_ID, EvalStore.nowText(), 0L, "test-cleanup"));
        assertFalse("终态后 update 应为 0", store.writeCalibrationJson(RUNNING_ID, "{\"passed\":false}"));
        assertTrue(hasWarn("未写入任何行"));
    }

    private EvalRunner newRunner() {
        return newRunner(new FakeCalibrationPort());
    }

    private EvalRunner newRunner(EvalCalibrationJudgePort calibration) {
        return newRunner(calibration, new FakeJudgePort());
    }

    private EvalRunner newRunner(EvalCalibrationJudgePort calibration, EvalCaseJudgePort judge) {
        EvalRunLoop loop = newLoop(new FakeGeneratePort(), judge, calibration);
        return new EvalRunner(store, EvalConfig.load(null), null, logger, "V0.1.0.0.0000", loop);
    }

    private EvalRunLoop newLoop(EvalCaseGeneratePort generate, EvalCaseJudgePort judge,
            EvalCalibrationJudgePort calibration) {
        return new EvalRunLoop(new EvalRunLoop.Deps()
                .store(store)
                .config(EvalConfig.load(null))
                .bank(bank)
                .rubric(rubric)
                .generatePort(generate)
                .judgePort(judge)
                .calibrationJudgePort(calibration)
                .reportsDirectory(new File(tempDir, "reports"))
                .logger(logger));
    }

    private EvalRunBudget futureBudget(int planned, int limit) {
        return new EvalRunBudget(planned, limit, System.currentTimeMillis() + 600000L);
    }

    private int calibrationCallCount(String runId) {
        int count = 0;
        for (JSONObject row : store.findCalls(runId).rows()) {
            if ("calib".equals(row.getString("stage"))) {
                count++;
            }
        }
        return count;
    }

    private boolean hasWarn(String keyword) {
        for (String message : logger.warns) {
            if (message.contains(keyword)) {
                return true;
            }
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
        File dir = File.createTempFile("mbb-eval-calib-wiring", ".dir");
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

    /** 假生成端口：返回固定成功出参，不联网。 */
    private static final class FakeGeneratePort implements EvalCaseGeneratePort {
        @Override
        public EvalGenerateResult generate(EvalGenerateRequest request) {
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

    /** 假判分端口：正式题集全部通过。 */
    private static final class FakeJudgePort implements EvalCaseJudgePort {
        @Override
        public EvalJudgeResult judge(EvalCaseJudgeInput input) {
            return EvalJudgeResult.ok(input.dimensionId(), 2, "默认通过", 1, null, null, 0);
        }

        @Override
        public boolean aborted() {
            return false;
        }

        @Override
        public String abortReason() {
            return null;
        }
    }

    /** 边界题每个样本返回不同 label，模拟 k=3 抖动；其余题目照旧通过。 */
    private static final class FlippingBoundaryJudgePort implements EvalCaseJudgePort {
        private final java.util.Map<String, Integer> seen = new java.util.HashMap<String, Integer>();

        @Override
        public EvalJudgeResult judge(EvalCaseJudgeInput input) {
            if (!EvalCaseExecutor.DIM_BOUNDARY.equals(input.dimensionId())) {
                return EvalJudgeResult.ok(input.dimensionId(), 2, "默认通过", 1, null, null, 0);
            }
            Integer count = seen.get(input.caseId());
            int index = count == null ? 0 : count.intValue();
            seen.put(input.caseId(), Integer.valueOf(index + 1));
            int label = index % 2 == 0 ? 2 : 0;
            return EvalJudgeResult.ok(input.dimensionId(), label, "边界样本", 1, null, null, 0);
        }

        @Override
        public boolean aborted() {
            return false;
        }

        @Override
        public String abortReason() {
            return null;
        }
    }

    /** 假校准端口：默认 label=0 且边界硬项 veto 命中；failAll 时模拟 judge 全失败。 */
    private static final class FakeCalibrationPort implements EvalCalibrationJudgePort {
        private final List<String> caseIds = new ArrayList<String>();
        private boolean failAll;

        @Override
        public String boundaryVeto(String reply, String personaSummary) {
            return EvalVetoPrecheck.vetoReason(reply, null, false, personaSummary);
        }

        @Override
        public String personaFragment() {
            return "天童爱丽丝，千年科学学园游戏开发部成员";
        }

        @Override
        public String personaSummary() {
            return "天童爱丽丝，千年科学学园游戏开发部成员，以第三人称称呼自己，不承认是 AI 或助手";
        }

        @Override
        public EvalJudgeResult judge(EvalCalibrationSample sample) {
            caseIds.add(sample.caseId());
            if (failAll) {
                return EvalJudgeResult.error(sample.dimensionId(), EvalJudgeResult.KIND_TRANSPORT,
                        "ai-unavailable", false, 0, null, "校准判分已熔断", null, 0);
            }
            return EvalJudgeResult.ok(sample.dimensionId(), 0, sample.reply().substring(0, 4), 1,
                    null, null, 0);
        }
    }
}
