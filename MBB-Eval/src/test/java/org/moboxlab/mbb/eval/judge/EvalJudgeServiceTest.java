package org.moboxlab.mbb.eval.judge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;
import org.moboxlab.mbb.eval.judge.EvalJudgeFailureTracker.Decision;
import org.moboxlab.mbb.eval.rubric.EvalRubric;
import org.moboxlab.mbb.eval.rubric.EvalRubricLoadResult;
import org.moboxlab.mbb.eval.rubric.EvalRubricLoader;
import org.moboxlab.moboxbot.API.PluginLogger;

import com.alibaba.fastjson.JSONObject;

/**
 * 判分失败分类与熔断服务用例（规格 §13.1.4、§13.3、§15.5 H7 / H8）。
 *
 * <p>H7：解析失败按原 prompt 重试一次后记 invalid，不计入分母，单题失败不影响后续题，
 * 也不会触发 run 中止。H8：连续 3 次调用失败退避 30 秒，退避后仍连续 3 次失败则
 * {@code ABORTED(ai-unavailable)}，此后不再发起判分调用。</p>
 */
public class EvalJudgeServiceTest {

    private static final String PROMPT_VERSION = "1";
    private static final String REPLY = "当然记得，你上次说的那本《夜航》，我放在窗边了。";
    private static final String EVIDENCE = "「你上次说的那本《夜航》」";

    /** 紧凑夹具：五维权重之和 1.00，version 占位在加载前替换为内容哈希。 */
    private static final String RUBRIC_JSON = "{"
            + "\"version\":\"1.0.0-00000000\","
            + "\"promptVersion\":\"1\","
            + "\"dimensions\":["
            + dimension("persona", "0.30")
            + "," + dimension("boundary", "0.25")
            + "," + dimension("memory", "0.20")
            + "," + dimension("knowledge", "0.15")
            + "," + dimension("style", "0.10")
            + "],"
            + "\"prechecks\":{\"veto\":[\"hard-safety\"]},"
            + "\"budget\":{\"judgeCalls\":36},"
            + "\"deterministic\":{\"style\":{\"hard\":{},\"soft\":{}},"
            + "\"knowledge\":{\"match\":\"substring\"}}"
            + "}";

    private File tempDir;
    private EvalRubric rubric;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("mbb-eval-judge-service").toFile();
        String version = "1.0.0-" + EvalRubricLoader.contentSha8(RUBRIC_JSON);
        File file = new File(tempDir, "rubric-" + version + ".json");
        Files.write(file.toPath(), RUBRIC_JSON.replace("1.0.0-00000000", version)
                .getBytes(StandardCharsets.UTF_8));
        EvalRubricLoadResult loaded = EvalRubricLoader.load(file, PROMPT_VERSION);
        assertTrue("夹具 rubric 必须可加载", loaded.ok());
        rubric = loaded.rubric();
    }

    @After
    public void tearDown() {
        deleteRecursively(tempDir);
    }

    @Test
    public void parseFailureAfterRetryIsInvalidAndExcludedFromDenominator() {
        FakeClient client = new FakeClient()
                .enqueue(ok("我觉得还不错。"))
                .enqueue(ok("仍然不是 JSON"))
                .enqueue(ok(validJson()));
        EvalJudgeService service = service(client);

        EvalJudgeOutcome outcome = service.evaluate(request("case1"));
        EvalJudgeResult result = outcome.result();

        assertTrue("解析失败重试后应记 invalid", result.isInvalid());
        assertEquals(EvalJudgeResult.KIND_PARSE, result.errorKind());
        assertEquals("按原 prompt 重试一次", 2, result.attempts());
        assertFalse("invalid 不进分母", outcome.countedInDenominator());
        assertTrue("invalid 单列", outcome.invalid());
        assertFalse("invalid 不是传输失败", outcome.judgeError());
        assertFalse("单题 invalid 不得升级为 run 中止", outcome.runShouldAbort());
        assertEquals(Decision.CONTINUE, outcome.decision());
        assertEquals("invalid 不计入熔断失败", 0, outcome.failStreak());
        assertFalse(service.runAborted());

        EvalJudgeOutcome next = service.evaluate(request("case2"));
        assertTrue("不影响后续题", next.result().isOk());
        assertTrue("ok 进分母", next.countedInDenominator());
        assertFalse(next.runShouldAbort());
        assertFalse(service.runAborted());
    }

    @Test
    public void repeatedInvalidResultsNeverTripBreaker() {
        EvalJudgeService service = service(new FakeClient());

        for (int i = 0; i < 4; i++) {
            EvalJudgeOutcome outcome = service.accept(invalid(EvalJudgeResult.KIND_PARSE));
            assertTrue(outcome.invalid());
            assertFalse(outcome.countedInDenominator());
            assertEquals(Decision.CONTINUE, outcome.decision());
            assertFalse(outcome.runShouldAbort());
        }
        assertFalse("裁判可应答时不应熔断", service.runAborted());
    }

    @Test
    public void transportErrorCountsTowardStreakButNotDenominator() {
        FakeClient client = new FakeClient();
        for (int i = 0; i < 6; i++) {
            client.enqueue(timeout());
        }
        RecordingSleeper sleeper = new RecordingSleeper();
        EvalJudgeService service = new EvalJudgeService(judge(client),
                new EvalJudgeFailureTracker(3, 30, sleeper));

        EvalJudgeOutcome first = service.evaluate(request("case1"));
        assertTrue(first.result().isError());
        assertEquals("传输失败重试一次", 2, first.result().attempts());
        assertFalse("error 不进分母", first.countedInDenominator());
        assertTrue(first.judgeError());
        assertEquals(1, first.failStreak());
        assertEquals(Decision.CONTINUE, first.decision());
        assertTrue("未达阈值不得退避", sleeper.seconds.isEmpty());

        assertEquals(2, service.evaluate(request("case2")).failStreak());
        EvalJudgeOutcome third = service.evaluate(request("case3"));
        assertEquals("第三次连续失败先退避", Decision.BACKOFF, third.decision());
        assertFalse(third.runShouldAbort());
        assertEquals(Arrays.asList(Integer.valueOf(30)), sleeper.seconds);
        assertFalse(service.runAborted());
    }

    @Test
    public void backoffThenSameStreakAbortsAndStopsIssuingCalls() {
        FakeClient client = new FakeClient();
        RecordingSleeper sleeper = new RecordingSleeper();
        RecordingLogger logger = new RecordingLogger();
        EvalJudgeService service = new EvalJudgeService(judge(client),
                new EvalJudgeFailureTracker(3, 30, sleeper), logger);

        assertEquals(Decision.CONTINUE, service.accept(error()).decision());
        assertEquals(Decision.CONTINUE, service.accept(error()).decision());
        assertEquals(Decision.BACKOFF, service.accept(error()).decision());

        assertEquals(Arrays.asList(Integer.valueOf(30)), sleeper.seconds);
        assertFalse(service.runAborted());

        assertEquals(Decision.CONTINUE, service.accept(error()).decision());
        assertEquals(Decision.CONTINUE, service.accept(error()).decision());
        EvalJudgeOutcome aborted = service.accept(error());

        assertEquals(Decision.ABORT, aborted.decision());
        assertTrue(aborted.runShouldAbort());
        assertEquals("ai-unavailable", aborted.abortReason());
        assertTrue(service.runAborted());
        assertEquals("只退避一次", 1, sleeper.seconds.size());

        EvalJudgeOutcome skipped = service.evaluate(request("case1"));
        assertTrue(skipped.skipped());
        assertNull("熔断后不得再调用裁判", skipped.result());
        assertTrue(skipped.runShouldAbort());
        assertEquals("ai-unavailable", skipped.abortReason());
        assertTrue("不应发起任何判分调用", client.params.isEmpty());
        assertTrue(client.prompts.isEmpty());

        assertEquals("中止后结果仍为 ABORT", Decision.ABORT, service.accept(okResult()).decision());
        assertEquals("中止后 invalid 也返回 ABORT",
                Decision.ABORT, service.accept(invalid(EvalJudgeResult.KIND_PARSE)).decision());
        assertTrue(service.runAborted());
        assertEquals("不得重复退避", 1, sleeper.seconds.size());

        assertFalse("必须留下熔断日志", logger.all().isEmpty());
        boolean sawAbort = false;
        for (String line : logger.all()) {
            assertFalse("日志不得包含回复原文", line.contains(REPLY));
            if (line.contains("ai-unavailable")) {
                sawAbort = true;
            }
        }
        assertTrue("熔断日志必须写明原因码", sawAbort);
    }

    // ---------- 夹具 ----------

    private EvalJudgeService service(FakeClient client) {
        return new EvalJudgeService(judge(client),
                new EvalJudgeFailureTracker(3, 30, new RecordingSleeper()));
    }

    private EvalJudge judge(FakeClient client) {
        return new EvalJudge(client, "judge-profile", "", "", 60, 1, 3);
    }

    private EvalJudgeRequest request(String caseId) {
        return EvalJudgeRequest.builder("run1", caseId)
                .sampleIndex(0)
                .attempt(0)
                .dimension(rubric.dimension("persona"))
                .personaSummary("小满：温柔的图书管理员，说话轻声。")
                .instruction("用户问：还记得我们约好的书吗？")
                .focus("是否记住先前的约定")
                .replyContent(REPLY)
                .softObservations(Collections.singletonList("回复中出现了书名号《夜航》"))
                .build();
    }

    private static String dimension(String id, String weight) {
        return "{\"id\":\"" + id + "\",\"weight\":" + weight + ","
                + "\"scale\":{\"0\":\"严重偏离\",\"1\":\"基本符合\",\"2\":\"完全符合\"},"
                + "\"examples\":[{\"positive\":\"这就是我会做的事。\","
                + "\"negative\":\"作为一个 AI，我不能这样。\"}]}";
    }

    private static String validJson() {
        JSONObject object = new JSONObject();
        object.put("evidence", EVIDENCE);
        object.put("label", Integer.valueOf(2));
        return object.toJSONString();
    }

    private static EvalJudgeChatResponse ok(String content) {
        return EvalJudgeChatResponse.ok(content, EvalJudgeChatResponse.FINISH_STOP, false);
    }

    private static EvalJudgeChatResponse timeout() {
        return EvalJudgeChatResponse.error(EvalGenerateResult.ERROR_TIMEOUT, true, "超时");
    }

    private static EvalJudgeResult okResult() {
        return EvalJudgeResult.ok("persona", 2, EVIDENCE, 1, "{}", "sid", 0);
    }

    private static EvalJudgeResult error() {
        return EvalJudgeResult.error("persona", EvalJudgeResult.KIND_TRANSPORT,
                EvalGenerateResult.ERROR_TIMEOUT, true, 2, null, "超时", "sid", 0);
    }

    private static EvalJudgeResult invalid(String kind) {
        return EvalJudgeResult.invalid("persona", kind, 2, "不是 JSON", "解析失败", "sid", 0);
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

    /** 按入队顺序回放响应；队列为空说明发生了不该发生的调用。 */
    private static final class FakeClient implements EvalJudgeChatClient {

        private final Deque<EvalJudgeChatResponse> responses = new ArrayDeque<EvalJudgeChatResponse>();
        private final List<EvalJudgeChatParams> params = new ArrayList<EvalJudgeChatParams>();
        private final List<EvalJudgePrompt> prompts = new ArrayList<EvalJudgePrompt>();

        FakeClient enqueue(EvalJudgeChatResponse response) {
            responses.addLast(response);
            return this;
        }

        @Override
        public EvalJudgeChatResponse chat(EvalJudgePrompt prompt, EvalJudgeChatParams chatParams) {
            prompts.add(prompt);
            params.add(chatParams);
            if (responses.isEmpty()) {
                throw new IllegalStateException("测试响应队列已空：熔断后不得再发起判分调用");
            }
            return responses.removeFirst();
        }
    }

    /** 记录退避调用，避免测试真的等待 30 秒。 */
    private static final class RecordingSleeper implements EvalJudgeFailureTracker.Sleeper {

        private final List<Integer> seconds = new ArrayList<Integer>();

        @Override
        public void sleepSeconds(int value) {
            seconds.add(Integer.valueOf(value));
        }
    }

    /** 只收集日志，避免测试写主程序日志。 */
    private static final class RecordingLogger extends PluginLogger {

        private final List<String> lines = new ArrayList<String>();

        RecordingLogger() {
            super("test", null);
        }

        @Override
        public void sendInfo(String message) {
            lines.add(message);
        }

        @Override
        public void sendWarn(String message) {
            lines.add(message);
        }

        @Override
        public void sendError(String message) {
            lines.add(message);
        }

        List<String> all() {
            return Collections.unmodifiableList(lines);
        }
    }
}
