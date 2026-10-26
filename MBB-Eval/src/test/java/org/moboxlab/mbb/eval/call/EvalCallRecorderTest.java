package org.moboxlab.mbb.eval.call;

import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;
import org.moboxlab.mbb.eval.judge.EvalJudgeResult;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 调用行采集测试（规格 §4.2、§4.4、§7.5、§13.3）。
 *
 * <p>覆盖三类 stage 的映射、失败调用仍落行、cached 单列、usage 四字段，
 * 以及写库失败只降级不中断。</p>
 */
public class EvalCallRecorderTest {

    private static final String RUN_ID = "r-20261026120000-smoke-0001";
    private static final String CASE_ID = "case-001";
    private static final String SESSION_ID = "roleplay-eval-" + RUN_ID + "-" + CASE_ID + "-2-1";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;
    private EvalCallRecorder recorder;

    @Before
    public void setUp() throws Exception {
        File dbFile = temporaryFolder.newFile("eval-call.db");
        storage = new EvalStorageFake(dbFile);
        assertTrue("建表失败", new EvalSchemaService(storage, null).ensureSchema());
        store = new EvalStore(storage, null);
        assertTrue("建 run 行失败", store.insertRunning(RUN_ID, EvalStore.TIER_SMOKE,
                "2026-10-26 12:00:00", 100, "V0.1.0.0.1830"));
        recorder = new EvalCallRecorder(store, null);
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    public void generateSuccessShouldMapAllL1Columns() {
        EvalGenerateResult result = EvalGenerateResult.parse(generateWire(), SESSION_ID);

        recorder.recordGenerate(RUN_ID, CASE_ID, 2, 1, result);

        String callId = EvalStore.deriveCallId(RUN_ID, CASE_ID, "gen", Integer.valueOf(2), Integer.valueOf(1));
        JSONObject row = store.findCall(callId);
        assertNotNull("成功后必须落行", row);
        assertEquals("gen", row.getString("stage"));
        assertEquals("ok", row.getString("status"));
        assertNull("成功行不得有 error_type", row.get("error_type"));
        assertEquals(0, row.getIntValue("retryable"));
        assertEquals("cached 必须单列", 1, row.getIntValue("cached"));
        assertEquals("eval", row.getString("profile"));
        assertEquals("mbb-test-model", row.getString("model"));
        assertEquals(11L, row.getLongValue("usage_prompt"));
        assertEquals(22L, row.getLongValue("usage_completion"));
        assertEquals(33L, row.getLongValue("usage_total"));
        assertEquals(3L, row.getLongValue("usage_cached"));
        assertEquals(1234L, row.getLongValue("elapsed_ms"));
        assertEquals("stop", row.getString("finish_reason"));
        assertEquals("你好，世界".length(), row.getIntValue("response_chars"));
        assertEquals(2, row.getIntValue("sample_index"));
        assertEquals(1, row.getIntValue("attempt"));
        assertNotNull("created_at 必须有值", row.getString("created_at"));
        assertEquals(20, row.size());
        assertFalse("sessionId 不入表", row.containsKey("sessionId"));
        assertFalse("requestId 不入表", row.containsKey("requestId"));
        assertFalse("sessionId 不入表", row.containsKey("session_id"));
        assertFalse("requestId 不入表", row.containsKey("request_id"));
    }

    @Test
    public void generateFailureShouldStillWriteRowWithoutUsage() {
        EvalGenerateResult result = EvalGenerateResult.error("busy", true, "上游忙");

        recorder.recordGenerate(RUN_ID, CASE_ID, 1, 2, result);

        String callId = EvalStore.deriveCallId(RUN_ID, CASE_ID, "gen", Integer.valueOf(1), Integer.valueOf(2));
        JSONObject row = store.findCall(callId);
        assertNotNull("失败调用也必须留行", row);
        assertEquals("error", row.getString("status"));
        assertEquals("busy", row.getString("error_type"));
        assertEquals(1, row.getIntValue("retryable"));
        assertEquals(0, row.getIntValue("cached"));
        assertEquals(2, row.getIntValue("attempt"));
        assertNull("失败无 usage", row.get("usage_prompt"));
        assertNull("失败无 usage", row.get("usage_total"));
        assertNull("失败无耗时", row.get("elapsed_ms"));
        assertNull("失败无 finish_reason", row.get("finish_reason"));
        assertNull("失败无响应体量", row.get("response_chars"));
    }

    @Test
    public void judgeSuccessShouldMapL2Columns() {
        EvalJudgeResult result = EvalJudgeResult.ok("persona", 2, "证据", 1,
                "{\"label\":2}", "judge-session", 0);

        recorder.recordJudge(RUN_ID, CASE_ID, 0, 1, 850L, result);

        String callId = EvalStore.deriveCallId(RUN_ID, CASE_ID, "judge", Integer.valueOf(0), Integer.valueOf(1));
        JSONObject row = store.findCall(callId);
        assertNotNull(row);
        assertEquals("judge", row.getString("stage"));
        assertEquals("ok", row.getString("status"));
        assertNull(row.get("error_type"));
        assertEquals(0, row.getIntValue("retryable"));
        assertEquals(0, row.getIntValue("cached"));
        assertEquals("墙钟耗时来自 runner", 850L, row.getLongValue("elapsed_ms"));
        assertNull("judge 无 usage", row.get("usage_prompt"));
        assertNull("judge 无 model", row.get("model"));
        assertNull("judge 无 finish_reason", row.get("finish_reason"));
        assertNull("judge 无 response_chars", row.get("response_chars"));
    }

    @Test
    public void judgeInvalidShouldMapErrorKindAsErrorType() {
        EvalJudgeResult result = EvalJudgeResult.invalid("persona", EvalJudgeResult.KIND_TRUNCATED,
                1, "原始内容", "输出被截断", "judge-session", 0);

        recorder.recordJudge(RUN_ID, CASE_ID, 0, 3, 640L, result);

        String callId = EvalStore.deriveCallId(RUN_ID, CASE_ID, "judge", Integer.valueOf(0), Integer.valueOf(3));
        JSONObject row = store.findCall(callId);
        assertNotNull(row);
        assertEquals("error", row.getString("status"));
        assertEquals("truncated", row.getString("error_type"));
        assertEquals(0, row.getIntValue("retryable"));
        assertEquals(3, row.getIntValue("attempt"));
    }

    @Test
    public void judgeCacheHitShouldBeFlaggedAsCached() {
        EvalJudgeResult result = EvalJudgeResult.error("persona", EvalJudgeResult.KIND_CACHE,
                null, false, 1, "原始内容", "缓存反复命中", "judge-session", 3);

        recorder.recordJudge(RUN_ID, CASE_ID, 0, 0, 120L, result);

        String callId = EvalStore.deriveCallId(RUN_ID, CASE_ID, "judge", Integer.valueOf(0), Integer.valueOf(0));
        JSONObject row = store.findCall(callId);
        assertNotNull(row);
        assertEquals("error", row.getString("status"));
        assertEquals("cache", row.getString("error_type"));
        assertEquals("cache 命中必须单列", 1, row.getIntValue("cached"));
    }

    @Test
    public void calibrationShouldUseCalibStageAndAllowNullCaseId() {
        EvalJudgeResult result = EvalJudgeResult.ok("persona", 0, "违例", 1,
                "{}", "calib-session", 0);

        recorder.recordCalibration(RUN_ID, null, 0, 0, 321L, result);

        String callId = EvalStore.deriveCallId(RUN_ID, null, "calib", Integer.valueOf(0), Integer.valueOf(0));
        JSONObject row = store.findCall(callId);
        assertNotNull(row);
        assertEquals("calib", row.getString("stage"));
        assertEquals("ok", row.getString("status"));
        assertNull("校准行可以没有 case_id", row.get("case_id"));
        assertEquals(321L, row.getLongValue("elapsed_ms"));
    }

    @Test
    public void writeFailureShouldWarnButNotThrow() {
        storage.failNextUpdate(1);
        EvalGenerateResult result = EvalGenerateResult.parse(generateWire(), SESSION_ID);

        JSONObject row = recorder.recordGenerate(RUN_ID, CASE_ID, 0, 0, result);

        assertNotNull("采集方仍应拿到已映射的行", row);
        String callId = EvalStore.deriveCallId(RUN_ID, CASE_ID, "gen", Integer.valueOf(0), Integer.valueOf(0));
        assertNull("写失败时库里不应有行", store.findCall(callId));
        assertFalse("写失败必须留下 WARN", store.warnings().isEmpty());
    }

    private static JSONObject generateWire() {
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
        wire.put("cached", Boolean.TRUE);
        wire.put("attempts", Integer.valueOf(1));
        wire.put("sessionId", SESSION_ID);
        wire.put("requestId", SESSION_ID);
        wire.put("finishReason", "stop");
        wire.put("model", "mbb-test-model");
        wire.put("profile", "eval");
        wire.put("personaId", "persona-001");
        wire.put("personaHash", "hash-001");
        return wire;
    }
}
