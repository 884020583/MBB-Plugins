package org.moboxlab.mbb.eval.call;

import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.gateway.EvalGenerateRequest;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;
import org.moboxlab.mbb.eval.judge.EvalJudgeResult;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 调用器测试（规格 §4.4、§4.7、§9.5、§13.1、§13.2、§15.5 H4/H5/H6/H9）。
 *
 * <p>覆盖：先失败后成功的重试落两行、持续超时不无限重试、cached 换会话重采、
 * 预算逼近上限时停止且不超卖、不可重试错误不重试，以及 judge 入口的预算记账。</p>
 */
public class EvalCallInvokerTest {

    private static final String RUN_ID = "r-20261026120000-smoke-0005";
    private static final String CASE_ID = "case-001";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;
    private RecordingSleeper sleeper;

    @Before
    public void setUp() throws Exception {
        File dbFile = temporaryFolder.newFile("eval-invoker.db");
        storage = new EvalStorageFake(dbFile);
        assertTrue("建表失败", new EvalSchemaService(storage, null).ensureSchema());
        store = new EvalStore(storage, null);
        sleeper = new RecordingSleeper();
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    public void busyThenSuccessShouldRetryAndWriteTwoRows() {
        EvalCallInvoker invoker = invoker(10, 1, 3);
        ScriptedGenerateCall call = new ScriptedGenerateCall(
                failure("busy", true), success(false));

        EvalCallInvoker.GenerateOutcome outcome = invoker.generate(RUN_ID, CASE_ID, 0, call);

        assertEquals("重试后成功应按成功返回", EvalCallInvoker.GenerateKind.OK, outcome.kind());
        assertEquals("两次物理调用", 2, outcome.calls());
        assertEquals("一次重试", 1, outcome.retries());
        assertEquals("两次调用都要计入预算", 2, store.findRun(RUN_ID).getIntValue("calls_used"));
        assertEquals("默认退避为第一次重试的 2 秒", Arrays.asList(Integer.valueOf(2)), sleeper.sleeps);

        Map<Integer, JSONObject> rows = rowsByAttempt();
        assertEquals("每次调用一条 _call 行", 2, rows.size());
        JSONObject first = rows.get(Integer.valueOf(0));
        JSONObject second = rows.get(Integer.valueOf(1));
        assertNotNull("第一次失败的调用也要留行", first);
        assertNotNull("第二次成功的调用要留行", second);
        assertEquals("error", first.getString("status"));
        assertEquals("busy", first.getString("error_type"));
        assertEquals(1, first.getIntValue("retryable"));
        assertEquals("ok", second.getString("status"));
        assertNull(second.get("error_type"));
        assertNotEquals("两次调用 attempt 必须可区分", first.getIntValue("attempt"), second.getIntValue("attempt"));

        EvalCallInvoker.GenerateTarget firstTarget = call.targets.get(0);
        EvalCallInvoker.GenerateTarget secondTarget = call.targets.get(1);
        assertNotEquals("重试必须换 sessionId", sessionId(firstTarget), sessionId(secondTarget));
        assertEquals(0, firstTarget.attempt());
        assertEquals(1, secondTarget.attempt());
        assertEquals("逻辑采样序号不变", firstTarget.sampleIndex(), secondTarget.sampleIndex());
    }

    @Test
    public void persistentTimeoutShouldStopAfterRetryLimitAndKeepErrorRows() {
        EvalCallInvoker invoker = invoker(10, 1, 3);
        ScriptedGenerateCall call = new ScriptedGenerateCall(failure("timeout", true));

        EvalCallInvoker.GenerateOutcome outcome = invoker.generate(RUN_ID, CASE_ID, 0, call);

        assertEquals("持续超时按样本 error 处理", EvalCallInvoker.GenerateKind.SAMPLE_ERROR, outcome.kind());
        assertEquals("1 次重试即止", 2, outcome.calls());
        assertEquals(2, call.targets.size());
        assertEquals("两次都计入 calls_used", 2, store.findRun(RUN_ID).getIntValue("calls_used"));
        assertEquals("失败样本的 errorType 必须原样带出", "timeout", outcome.result().errorType());
        assertTrue("失败样本 retryable 必须可读", outcome.result().retryable());

        Map<Integer, JSONObject> rows = rowsByAttempt();
        assertEquals(2, rows.size());
        for (JSONObject row : rows.values()) {
            assertEquals("error", row.getString("status"));
            assertEquals("timeout", row.getString("error_type"));
            assertEquals(1, row.getIntValue("retryable"));
        }
    }

    @Test
    public void cachedHitShouldResampleWithNewSessionIdsThenFailWhenExhausted() {
        EvalCallInvoker invoker = invoker(10, 1, 2);
        ScriptedGenerateCall call = new ScriptedGenerateCall(success(true));

        EvalCallInvoker.GenerateOutcome outcome = invoker.generate(RUN_ID, CASE_ID, 0, call);

        assertEquals("重采用尽仍 cached 判样本 error", EvalCallInvoker.GenerateKind.SAMPLE_ERROR, outcome.kind());
        assertEquals("首次命中加两次重采共三次调用", 3, outcome.calls());
        assertEquals(3, outcome.cachedHits());
        assertEquals(3, store.findRun(RUN_ID).getIntValue("calls_used"));
        assertTrue("原因里要写明 cached 重采", outcome.message().contains("cached"));

        Map<Integer, JSONObject> rows = rowsByAttempt();
        assertEquals("三次调用三行", 3, rows.size());
        for (JSONObject row : rows.values()) {
            assertEquals("ok", row.getString("status"));
            assertEquals("cached 必须单列", 1, row.getIntValue("cached"));
        }

        List<String> sessionIds = new ArrayList<String>();
        for (EvalCallInvoker.GenerateTarget target : call.targets) {
            String sessionId = sessionId(target);
            assertFalse("每次重采都必须换 sessionId：" + sessionId, sessionIds.contains(sessionId));
            sessionIds.add(sessionId);
        }
    }

    @Test
    public void cachedThenSuccessShouldReturnOkAfterResample() {
        EvalCallInvoker invoker = invoker(10, 1, 3);
        ScriptedGenerateCall call = new ScriptedGenerateCall(success(true), success(false));

        EvalCallInvoker.GenerateOutcome outcome = invoker.generate(RUN_ID, CASE_ID, 0, call);

        assertEquals(EvalCallInvoker.GenerateKind.OK, outcome.kind());
        assertEquals(2, outcome.calls());
        assertEquals(1, outcome.cachedHits());
        assertEquals(2, store.findRun(RUN_ID).getIntValue("calls_used"));

        Map<Integer, JSONObject> rows = rowsByAttempt();
        assertEquals(1, rows.get(Integer.valueOf(0)).getIntValue("cached"));
        assertEquals(0, rows.get(Integer.valueOf(1)).getIntValue("cached"));
    }

    @Test
    public void budgetLimitShouldStopAtLimitAndNeverOversell() {
        EvalCallInvoker invoker = invoker(100, 1, 3);
        ScriptedGenerateCall call = new ScriptedGenerateCall(success(false));

        for (int sampleIndex = 0; sampleIndex < 100; sampleIndex++) {
            EvalCallInvoker.GenerateOutcome outcome = invoker.generate(RUN_ID, CASE_ID, sampleIndex, call);
            assertEquals("第 " + sampleIndex + " 个样本应成功", EvalCallInvoker.GenerateKind.OK, outcome.kind());
        }

        EvalCallInvoker.GenerateOutcome stopped = invoker.generate(RUN_ID, CASE_ID, 0, call);

        assertEquals("预算用尽必须止步", EvalCallInvoker.GenerateKind.BUDGET_STOPPED, stopped.kind());
        assertEquals("止步时不得发起调用", 0, stopped.calls());
        assertEquals("不得触发生成调用", 100, call.targets.size());
        assertEquals("不得超卖", 100, store.findRun(RUN_ID).getIntValue("calls_used"));
        assertEquals("止步后不得新增调用行", 100, store.findCalls(RUN_ID).size());
        assertEquals(EvalStore.ABORT_REASON_BUDGET, stopped.abortReason());
        assertEquals("预算已用满", 100, stopped.budgetUsed());
        assertEquals("预算上限不变", 100, stopped.budgetLimit());
    }

    @Test
    public void budgetStopBeforeRetryShouldNotRecordExtraRow() {
        EvalCallInvoker invoker = invoker(1, 1, 3);
        ScriptedGenerateCall call = new ScriptedGenerateCall(failure("busy", true), success(false));

        EvalCallInvoker.GenerateOutcome outcome = invoker.generate(RUN_ID, CASE_ID, 0, call);

        assertEquals(EvalCallInvoker.GenerateKind.BUDGET_STOPPED, outcome.kind());
        assertEquals("预算只够第一次调用", 1, outcome.calls());
        assertEquals(1, call.targets.size());
        assertEquals(1, store.findRun(RUN_ID).getIntValue("calls_used"));
        assertEquals("预算止步时不得补行", 1, store.findCalls(RUN_ID).size());
        assertEquals(EvalStore.ABORT_REASON_BUDGET, outcome.abortReason());
    }

    @Test
    public void nonRetryableErrorShouldNotRetry() {
        EvalCallInvoker invoker = invoker(10, 1, 3);
        ScriptedGenerateCall call = new ScriptedGenerateCall(failure("history-too-long", false));

        EvalCallInvoker.GenerateOutcome outcome = invoker.generate(RUN_ID, CASE_ID, 0, call);

        assertEquals(EvalCallInvoker.GenerateKind.SAMPLE_ERROR, outcome.kind());
        assertEquals("不可重试错误只调一次", 1, outcome.calls());
        assertEquals(1, call.targets.size());
        assertTrue("不得退避", sleeper.sleeps.isEmpty());
        assertEquals(1, store.findRun(RUN_ID).getIntValue("calls_used"));
        JSONObject row = rowsByAttempt().get(Integer.valueOf(0));
        assertEquals("history-too-long", row.getString("error_type"));
        assertEquals(0, row.getIntValue("retryable"));
    }

    @Test
    public void thrownExceptionShouldBecomeInternalErrorWithoutRetry() {
        EvalCallInvoker invoker = invoker(10, 1, 3);
        EvalCallInvoker.GenerateCall call = new EvalCallInvoker.GenerateCall() {
            @Override
            public EvalGenerateResult call(EvalCallInvoker.GenerateTarget target) {
                throw new IllegalStateException("模拟上游异常");
            }
        };

        EvalCallInvoker.GenerateOutcome outcome = invoker.generate(RUN_ID, CASE_ID, 0, call);

        assertEquals(EvalCallInvoker.GenerateKind.SAMPLE_ERROR, outcome.kind());
        assertEquals("异常收敛为不可重试的 internal", "internal", outcome.result().errorType());
        assertFalse(outcome.result().retryable());
        assertEquals(1, store.findRun(RUN_ID).getIntValue("calls_used"));
        assertEquals(1, store.findCalls(RUN_ID).size());
    }

    @Test
    public void judgeEntryShouldConsumeBudgetAndRecordOneRowPerCall() {
        EvalCallInvoker invoker = invoker(2, 1, 3);

        EvalCallInvoker.JudgeOutcome ok = invoker.judge(RUN_ID, CASE_ID, 0, 0,
                new FixedJudgeCall(EvalJudgeResult.ok("knowledge", 2, "证据", 1,
                        "{\"label\":2}", "judge-session-0", 0)));
        assertEquals(EvalCallInvoker.JudgeKind.OK, ok.kind());
        assertEquals(1, store.findRun(RUN_ID).getIntValue("calls_used"));
        JSONObject okRow = store.findCall(EvalStore.deriveCallId(RUN_ID, CASE_ID, "judge",
                Integer.valueOf(0), Integer.valueOf(0)));
        assertNotNull("judge 成功必须落行", okRow);
        assertEquals("ok", okRow.getString("status"));

        EvalCallInvoker.JudgeOutcome invalid = invoker.judge(RUN_ID, CASE_ID, 0, 1,
                new FixedJudgeCall(EvalJudgeResult.invalid("knowledge", EvalJudgeResult.KIND_PARSE,
                        2, "原始内容", "解析失败", "judge-session-1", 0)));
        assertEquals(EvalCallInvoker.JudgeKind.INVALID, invalid.kind());
        assertEquals(2, store.findRun(RUN_ID).getIntValue("calls_used"));
        JSONObject invalidRow = store.findCall(EvalStore.deriveCallId(RUN_ID, CASE_ID, "judge",
                Integer.valueOf(0), Integer.valueOf(1)));
        assertNotNull(invalidRow);
        assertEquals("error", invalidRow.getString("status"));
        assertEquals("parse", invalidRow.getString("error_type"));

        final boolean[] called = new boolean[] {false};
        EvalCallInvoker.JudgeOutcome stopped = invoker.judge(RUN_ID, CASE_ID, 0, 2,
                new EvalCallInvoker.JudgeCall() {
                    @Override
                    public EvalJudgeResult call() {
                        called[0] = true;
                        return EvalJudgeResult.ok("knowledge", 1, "证据", 1, "{}", "judge-session-2", 0);
                    }
                });
        assertEquals(EvalCallInvoker.JudgeKind.BUDGET_STOPPED, stopped.kind());
        assertFalse("预算止步时不得发起判分", called[0]);
        assertEquals("止步后不得新增调用行", 2, store.findCalls(RUN_ID).size());
        assertEquals(EvalStore.ABORT_REASON_BUDGET, stopped.abortReason());
    }

    private EvalCallInvoker invoker(int limit, int retries, int cachedResamples) {
        assertTrue("建 run 行失败", store.insertRunning(RUN_ID, EvalStore.TIER_SMOKE,
                "2026-10-26 12:00:00", limit, "V0.1.0.0.1830"));
        EvalBudgetLedger ledger = new EvalBudgetLedger(RUN_ID, 25, limit, Long.MAX_VALUE, store, null);
        EvalCallRecorder recorder = new EvalCallRecorder(store, null);
        EvalCallPolicy policy = new EvalCallPolicy(retries, cachedResamples, new int[] {2, 5});
        return new EvalCallInvoker(ledger, recorder, policy, sleeper);
    }

    private Map<Integer, JSONObject> rowsByAttempt() {
        EvalStore.RowSet rowSet = store.findCalls(RUN_ID);
        Map<Integer, JSONObject> rows = new HashMap<Integer, JSONObject>();
        for (JSONObject row : rowSet.rows()) {
            rows.put(Integer.valueOf(row.getIntValue("attempt")), row);
        }
        return rows;
    }

    private static String sessionId(EvalCallInvoker.GenerateTarget target) {
        return EvalGenerateRequest.encodeSessionId(target.runId(), target.caseId(),
                target.sessionSampleIndex(), target.attempt());
    }

    private static ScriptedStep success(final boolean cached) {
        return new ScriptedStep() {
            @Override
            public EvalGenerateResult build(EvalCallInvoker.GenerateTarget target) {
                String sessionId = sessionId(target);
                return EvalGenerateResult.parse(successWire(cached, sessionId), sessionId);
            }
        };
    }

    private static ScriptedStep failure(final String errorType, final boolean retryable) {
        return new ScriptedStep() {
            @Override
            public EvalGenerateResult build(EvalCallInvoker.GenerateTarget target) {
                return EvalGenerateResult.error(errorType, retryable, "模拟失败");
            }
        };
    }

    private interface ScriptedStep {
        EvalGenerateResult build(EvalCallInvoker.GenerateTarget target);
    }

    private static final class ScriptedGenerateCall implements EvalCallInvoker.GenerateCall {
        private final List<ScriptedStep> steps;
        private final List<EvalCallInvoker.GenerateTarget> targets =
                new ArrayList<EvalCallInvoker.GenerateTarget>();
        private int index;

        private ScriptedGenerateCall(ScriptedStep... steps) {
            this.steps = Arrays.asList(steps);
        }

        @Override
        public EvalGenerateResult call(EvalCallInvoker.GenerateTarget target) {
            targets.add(target);
            ScriptedStep step = steps.get(Math.min(index, steps.size() - 1));
            index++;
            return step.build(target);
        }
    }

    private static final class FixedJudgeCall implements EvalCallInvoker.JudgeCall {
        private final EvalJudgeResult result;

        private FixedJudgeCall(EvalJudgeResult result) {
            this.result = result;
        }

        @Override
        public EvalJudgeResult call() {
            return result;
        }
    }

    private static final class RecordingSleeper implements EvalCallInvoker.Sleeper {
        private final List<Integer> sleeps = new ArrayList<Integer>();

        @Override
        public void sleepSeconds(int seconds) {
            sleeps.add(Integer.valueOf(seconds));
        }
    }

    private static JSONObject successWire(boolean cached, String sessionId) {
        JSONObject usage = new JSONObject();
        usage.put("promptTokens", Integer.valueOf(11));
        usage.put("completionTokens", Integer.valueOf(22));
        usage.put("totalTokens", Integer.valueOf(33));
        usage.put("cachedPromptTokens", Integer.valueOf(3));

        JSONObject wire = new JSONObject();
        wire.put("action", "eval-generate");
        wire.put("status", Boolean.TRUE);
        wire.put("content", "你好，世界");
        wire.put("skip", Boolean.FALSE);
        wire.put("usage", usage);
        wire.put("elapsedMs", Long.valueOf(1234L));
        wire.put("cached", Boolean.valueOf(cached));
        wire.put("attempts", Integer.valueOf(1));
        wire.put("sessionId", sessionId);
        wire.put("requestId", sessionId);
        wire.put("finishReason", "stop");
        wire.put("model", "mbb-test-model");
        wire.put("profile", "eval");
        wire.put("personaId", "persona-001");
        wire.put("personaHash", "hash-001");
        return wire;
    }
}
