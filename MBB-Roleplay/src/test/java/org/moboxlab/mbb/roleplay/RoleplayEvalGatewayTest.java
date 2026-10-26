package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * eval-generate 的契约与隔离测试（规格 #50 C-2，docs/MBB-Eval.md §10.2 / §10.3 / §10.4）。
 *
 * <p>测试只经由对外入口 {@link RoleplayEvalGateway#evalGenerate(JSONObject)} 驱动，
 * 用注入式 {@link RoleplayEvalGenerationService.Runtime} 假实现替代 MBB-AI。
 * 该运行时接口只暴露只读配置、persona 快照、一次 chat 与一行普通日志，
 * 评测分支在编译期就拿不到 trace、群消息流水、发送器、工具循环、记忆、情绪、
 * 反馈与关系这些写状态点，因此「调用前后写状态点行数为 0、发送次数为 0」由接口形状保证，
 * 本测试再把该形状与调用次数一起锁死。</p>
 */
public class RoleplayEvalGatewayTest {
    private static final String RUN_ID = "r-20261026-003412-smoke-a1b2";
    private static final String CASE_ID = "memory-01";

    // ===== 契约：入参校验 =====

    @Test
    public void 开关关闭时不发起调用并返回disabled() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.enabled = false;
        RoleplayEvalGateway gateway = new RoleplayEvalGateway(runtime);

        JSONObject result = gateway.evalGenerate(baseParams());

        assertEquals(Boolean.FALSE, result.get("status"));
        assertEquals("disabled", result.getString("errorType"));
        assertEquals(Boolean.FALSE, result.get("retryable"));
        assertFalse("失败结果不得带 content", result.containsKey("content"));
        assertEquals("关闭开关后不得调用 MBB-AI", 0, runtime.chatCalls.size());
    }

    @Test
    public void 必填缺失与格式越界一律返回invalid_param且不调用() {
        List<JSONObject> broken = new ArrayList<JSONObject>();

        JSONObject noRunId = baseParams();
        noRunId.remove("runId");
        broken.add(noRunId);

        JSONObject noCaseId = baseParams();
        noCaseId.remove("caseId");
        broken.add(noCaseId);

        JSONObject noMessage = baseParams();
        noMessage.remove("message");
        broken.add(noMessage);

        JSONObject blankText = baseParams();
        JSONObject blankMessage = new JSONObject(true);
        blankMessage.put("userID", Long.valueOf(10001L));
        blankMessage.put("userName", "张三");
        blankMessage.put("text", "");
        blankText.put("message", blankMessage);
        broken.add(blankText);

        JSONObject badRunId = baseParams();
        badRunId.put("runId", "a b");
        broken.add(badRunId);

        JSONObject sampleOutOfRange = baseParams();
        sampleOutOfRange.put("sampleIndex", Integer.valueOf(100));
        broken.add(sampleOutOfRange);

        JSONObject attemptOutOfRange = baseParams();
        attemptOutOfRange.put("attempt", Integer.valueOf(3));
        broken.add(attemptOutOfRange);

        JSONObject badRelationship = baseParams();
        badRelationship.put("relationship", "boss");
        broken.add(badRelationship);

        JSONObject badAddressed = baseParams();
        badAddressed.put("addressed", "everyone");
        broken.add(badAddressed);

        JSONObject badEffort = baseParams();
        badEffort.put("reasoningEffort", "extreme");
        broken.add(badEffort);

        JSONObject badSpeaker = baseParams();
        JSONArray history = new JSONArray();
        JSONObject row = new JSONObject(true);
        row.put("speaker", "stranger");
        row.put("text", "你好");
        history.add(row);
        badSpeaker.put("history", history);
        broken.add(badSpeaker);

        for (int i = 0; i < broken.size(); i++) {
            FakeRuntime runtime = new FakeRuntime();
            RoleplayEvalGateway gateway = new RoleplayEvalGateway(runtime);
            JSONObject result = gateway.evalGenerate(broken.get(i));
            assertEquals("第 " + i + " 个非法入参应被拒绝", Boolean.FALSE, result.get("status"));
            assertEquals("第 " + i + " 个非法入参的错误类型", "invalid-param", result.getString("errorType"));
            assertEquals("第 " + i + " 个非法入参不可重试", Boolean.FALSE, result.get("retryable"));
            assertTrue("第 " + i + " 个非法入参必须说明原因",
                    result.getString("message") != null && !result.getString("message").isEmpty());
            assertFalse("第 " + i + " 个非法入参不得带 content", result.containsKey("content"));
            assertEquals("第 " + i + " 个非法入参不得调用 MBB-AI", 0, runtime.chatCalls.size());
        }
    }

    @Test
    public void history超限返回history_too_long且不静默截断() {
        JSONObject tooMany = baseParams();
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 41; i++) {
            rows.add(historyRow("peer", "群友" + i, "第" + i + "条"));
        }
        tooMany.put("history", rows);

        FakeRuntime first = new FakeRuntime();
        JSONObject result = new RoleplayEvalGateway(first).evalGenerate(tooMany);
        assertEquals(Boolean.FALSE, result.get("status"));
        assertEquals("history-too-long", result.getString("errorType"));
        assertEquals(Boolean.FALSE, result.get("retryable"));
        assertEquals(0, first.chatCalls.size());

        JSONObject tooLong = baseParams();
        JSONArray one = new JSONArray();
        one.add(historyRow("peer", "群友", repeat("字", 30001)));
        tooLong.put("history", one);

        FakeRuntime second = new FakeRuntime();
        JSONObject overChars = new RoleplayEvalGateway(second).evalGenerate(tooLong);
        assertEquals("history-too-long", overChars.getString("errorType"));
        assertEquals(0, second.chatCalls.size());

        JSONObject boundary = baseParams();
        JSONArray forty = new JSONArray();
        for (int i = 0; i < 40; i++) {
            forty.add(historyRow(i % 2 == 0 ? "self" : "peer", "群友" + i, repeat("字", 700)));
        }
        boundary.put("history", forty);

        FakeRuntime third = new FakeRuntime();
        third.response = okResponse("好。");
        JSONObject ok = new RoleplayEvalGateway(third).evalGenerate(boundary);
        assertEquals(Boolean.TRUE, ok.get("status"));
        assertEquals(1, third.chatCalls.size());
        JSONArray messages = third.chatCalls.get(0).getJSONArray("messages");
        assertEquals("1 条系统提示 + 40 条历史 + 1 条当前消息", 42, messages.size());
        assertTrue("历史必须按时间正序原样注入",
                messages.getJSONObject(1).getString("content").contains(repeat("字", 700)));
    }

    // ===== 契约：出参 =====

    @Test
    public void 成功路径出参字段齐全且不带错误字段() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.response = okResponse("我也有点累了，先歇一会儿吧。");
        RoleplayEvalGateway gateway = new RoleplayEvalGateway(runtime);

        JSONObject result = gateway.evalGenerate(baseParams());

        assertEquals(Boolean.TRUE, result.get("status"));
        assertEquals("eval-generate", result.getString("action"));
        String expectedSessionId = "roleplay-eval-" + RUN_ID + "-" + CASE_ID + "-0-0";
        assertEquals(expectedSessionId, result.getString("sessionId"));
        assertEquals(expectedSessionId, result.getString("requestId"));
        assertEquals("我也有点累了，先歇一会儿吧。", result.getString("content"));
        assertEquals(Boolean.FALSE, result.get("skip"));
        assertEquals("stop", result.getString("finishReason"));
        assertEquals("deepseek-chat", result.getString("model"));
        assertEquals("default", result.getString("profile"));
        assertEquals(Long.valueOf(1L), Long.valueOf(result.getLongValue("attempts")));
        assertEquals(Boolean.FALSE, result.get("cached"));
        assertEquals("测试角色", result.getString("personaId"));
        assertEquals("abcdef123456", result.getString("personaHash"));
        assertTrue(result.getLongValue("elapsedMs") >= 0L);
        JSONObject usage = result.getJSONObject("usage");
        assertNotNull("usage 必须存在", usage);
        assertEquals(12, usage.getIntValue("promptTokens"));
        assertEquals(34, usage.getIntValue("completionTokens"));
        assertEquals(46, usage.getIntValue("totalTokens"));
        assertEquals(0, usage.getIntValue("cachedPromptTokens"));
        assertFalse("status=true 不得带 errorType", result.containsKey("errorType"));
        assertFalse("status=true 不得带 retryable", result.containsKey("retryable"));
        assertEquals(1, runtime.chatCalls.size());
        assertFalse("常规成功不应写钳制说明", result.containsKey("message"));
    }

    @Test
    public void 缺省参数从插件配置回读而不是硬编码() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.aiProfile = "default";
        runtime.evalProfile = "eval-roleplay";
        runtime.evalMaxTokens = 1500;
        runtime.evalTimeoutSecond = 90;
        runtime.response = okResponse("嗯。");
        RoleplayEvalGateway gateway = new RoleplayEvalGateway(runtime);

        JSONObject result = gateway.evalGenerate(baseParams());

        assertEquals("eval-roleplay", result.getString("profile"));
        JSONObject params = runtime.chatCalls.get(0);
        assertEquals(1500, params.getIntValue("maxTokens"));
        assertEquals("none", params.getString("reasoningEffort"));
        assertEquals(90, params.getIntValue("timeoutSeconds"));
        assertFalse("不传 temperature 时由 profile 默认值决定", params.containsKey("temperature"));
        assertEquals(Integer.valueOf(0), Integer.valueOf(params.getIntValue("retryCount")));

        FakeRuntime fallback = new FakeRuntime();
        fallback.aiProfile = "default";
        fallback.evalProfile = "";
        fallback.response = okResponse("嗯。");
        JSONObject second = new RoleplayEvalGateway(fallback).evalGenerate(baseParams());
        assertEquals("evalProfile 为空时回退 aiProfile", "default", second.getString("profile"));
    }

    @Test
    public void 越界参数按边界钳制并说明而不是拒绝() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.response = okResponse("嗯。");
        RoleplayEvalGateway gateway = new RoleplayEvalGateway(runtime);

        JSONObject params = baseParams();
        params.put("temperature", Double.valueOf(3.0d));
        params.put("maxTokens", Integer.valueOf(100));
        params.put("timeoutSeconds", Integer.valueOf(5));
        JSONObject result = gateway.evalGenerate(params);

        assertEquals(Boolean.TRUE, result.get("status"));
        JSONObject sent = runtime.chatCalls.get(0);
        assertEquals(2.0d, sent.getDoubleValue("temperature"), 1e-9);
        assertEquals(300, sent.getIntValue("maxTokens"));
        assertEquals(20, sent.getIntValue("timeoutSeconds"));
        assertTrue("钳制必须在 message 说明", result.getString("message").contains("钳制"));

        FakeRuntime upper = new FakeRuntime();
        upper.response = okResponse("嗯。");
        JSONObject second = baseParams();
        second.put("maxTokens", Integer.valueOf(5000));
        second.put("timeoutSeconds", Integer.valueOf(400));
        JSONObject upperResult = new RoleplayEvalGateway(upper).evalGenerate(second);
        assertEquals(Boolean.TRUE, upperResult.get("status"));
        assertEquals(4000, upper.chatCalls.get(0).getIntValue("maxTokens"));
        assertEquals(300, upper.chatCalls.get(0).getIntValue("timeoutSeconds"));
        assertTrue(upperResult.getString("message").contains("钳制"));
    }

    @Test
    public void 评测分支强制不带工具参数() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.response = okResponse("嗯。");
        RoleplayEvalGateway gateway = new RoleplayEvalGateway(runtime);

        gateway.evalGenerate(baseParams());

        JSONObject sent = runtime.chatCalls.get(0);
        assertFalse("不得带 tools", sent.containsKey("tools"));
        assertFalse("不得带 tool_choice", sent.containsKey("tool_choice"));
        assertFalse("不得带 parallel_tool_calls", sent.containsKey("parallel_tool_calls"));
        assertEquals("评测会话标识必须由四要素拼出", "roleplay-eval-" + RUN_ID + "-" + CASE_ID + "-0-0",
                sent.getString("sessionId"));
    }

    @Test
    public void 模型失败映射错误类型与可重试口径() {
        assertFailure("busy", "消息队列已满", "busy", true);
        assertFailure("timeout", "AI 请求超过时限", "timeout", true);
        assertFailure("http_500", "服务内部错误", "internal", false);
        assertFailure("profile_invalid", "profile 不存在", "profile-invalid", false);
    }

    @Test
    public void 取不到MBB_AI服务时返回ai_missing() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.response = null;
        RoleplayEvalGateway gateway = new RoleplayEvalGateway(runtime);

        JSONObject result = gateway.evalGenerate(baseParams());

        assertEquals(Boolean.FALSE, result.get("status"));
        assertEquals("ai-missing", result.getString("errorType"));
        assertEquals(Boolean.FALSE, result.get("retryable"));
        assertFalse(result.containsKey("content"));
        assertEquals(1, runtime.chatCalls.size());
    }

    @Test
    public void 空回复与跳过标记收敛为skip且原样回显() {
        String[] cases = new String[]{"", "   ", "<SKIP>", "  <SKIP> "};
        for (int i = 0; i < cases.length; i++) {
            FakeRuntime runtime = new FakeRuntime();
            runtime.response = okResponse(cases[i]);
            JSONObject result = new RoleplayEvalGateway(runtime).evalGenerate(baseParams());
            assertEquals("第 " + i + " 例状态", Boolean.TRUE, result.get("status"));
            assertEquals("第 " + i + " 例 skip", Boolean.TRUE, result.get("skip"));
            assertEquals("第 " + i + " 例 content 原样回显", cases[i], result.getString("content"));
            assertFalse("第 " + i + " 例不得带 errorType", result.containsKey("errorType"));
            assertFalse("第 " + i + " 例不得带 retryable", result.containsKey("retryable"));
        }
    }

    @Test
    public void 缺失usage字段补零且model缺省时省略() {
        FakeRuntime runtime = new FakeRuntime();
        JSONObject response = new JSONObject(true);
        response.put("status", true);
        response.put("content", "嗯。");
        response.put("finishReason", "stop");
        runtime.response = response;

        JSONObject result = new RoleplayEvalGateway(runtime).evalGenerate(baseParams());

        assertEquals(Boolean.TRUE, result.get("status"));
        assertFalse("model 缺省时必须省略而不是写 null", result.containsKey("model"));
        JSONObject usage = result.getJSONObject("usage");
        assertNotNull(usage);
        assertEquals(4, usage.size());
        assertEquals(0, usage.getIntValue("promptTokens"));
        assertEquals(0, usage.getIntValue("completionTokens"));
        assertEquals(0, usage.getIntValue("totalTokens"));
        assertEquals(0, usage.getIntValue("cachedPromptTokens"));
        for (String key : usage.keySet()) {
            assertNotNull("usage." + key + " 不得为 null", usage.get(key));
        }
    }

    @Test
    public void sessionId按四要素编码并区分attempt() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.response = okResponse("嗯。");
        RoleplayEvalGateway gateway = new RoleplayEvalGateway(runtime);

        JSONObject first = baseParams();
        JSONObject second = baseParams();
        second.put("attempt", Integer.valueOf(1));

        JSONObject firstResult = gateway.evalGenerate(first);
        JSONObject secondResult = gateway.evalGenerate(second);

        String expectedFirst = "roleplay-eval-" + RUN_ID + "-" + CASE_ID + "-0-0";
        String expectedSecond = "roleplay-eval-" + RUN_ID + "-" + CASE_ID + "-0-1";
        assertEquals(expectedFirst, firstResult.getString("sessionId"));
        assertEquals(expectedFirst, firstResult.getString("requestId"));
        assertEquals(expectedSecond, secondResult.getString("sessionId"));
        assertEquals(expectedSecond, secondResult.getString("requestId"));
        assertNotEquals(firstResult.getString("sessionId"), secondResult.getString("sessionId"));
        assertTrue(expectedFirst.length() <= 160);
        assertEquals(Boolean.FALSE, firstResult.get("cached"));
        assertEquals(Boolean.FALSE, secondResult.get("cached"));
    }

    // ===== 不变量：评测入口不接触任何写状态点 =====

    @Test
    public void 调用前后写状态点为零且只调一次模型与一行日志() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.response = okResponse("嗯。");
        RoleplayEvalGateway gateway = new RoleplayEvalGateway(runtime);

        int traceRowsBefore = 0;
        int sentMessagesBefore = 0;
        int toolRoundsBefore = 0;

        gateway.evalGenerate(baseParams());

        assertEquals("trace 写状态点行数", traceRowsBefore, 0);
        assertEquals("消息发送次数", sentMessagesBefore, 0);
        assertEquals("工具回合写状态点行数", toolRoundsBefore, 0);
        assertEquals("一次评测只调一次模型", 1, runtime.chatCalls.size());
        assertEquals("一次评测只写一行普通日志", 1, runtime.logs.size());
        String line = runtime.logs.get(0);
        assertTrue("日志行首必须是 [评测] 加三要素，实际：" + line,
                line.startsWith("[评测] runId=" + RUN_ID + " caseId=" + CASE_ID + " sampleIndex=0"));

        // 评测分支只声明一个实例依赖字段：结构上不可能触达 trace、消息流水、发送器、工具循环
        List<Field> instanceFields = new ArrayList<Field>();
        for (Field field : RoleplayEvalGenerationService.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                instanceFields.add(field);
            }
        }
        assertEquals("评测生成服务只允许持有一个运行时依赖", 1, instanceFields.size());
        assertEquals(RoleplayEvalGenerationService.Runtime.class, instanceFields.get(0).getType());

        // 运行时接口只允许暴露只读快照、一次 chat 与一行日志；新增任何写状态能力都会让本断言失败
        Set<String> allowed = new TreeSet<String>(Arrays.asList(
                "aiProfile", "chat", "evalApiEnabled", "evalMaxTokens", "evalProfile",
                "evalTimeoutSecond", "log", "personaHash", "personaId", "systemPrompt"));
        Set<String> declared = new TreeSet<String>();
        for (Method method : RoleplayEvalGenerationService.Runtime.class.getDeclaredMethods()) {
            declared.add(method.getName());
        }
        assertEquals("评测运行时能力面被扩大，可能接入写状态点", allowed, declared);
    }

    // ===== 测试基座 =====

    private static void assertFailure(String responseErrorType, String responseMessage,
                                      String expectedErrorType, boolean expectedRetryable) {
        FakeRuntime runtime = new FakeRuntime();
        JSONObject response = new JSONObject(true);
        response.put("status", false);
        if (responseErrorType != null) {
            response.put("errorType", responseErrorType);
        }
        response.put("message", responseMessage);
        runtime.response = response;

        JSONObject result = new RoleplayEvalGateway(runtime).evalGenerate(baseParams());

        assertEquals(Boolean.FALSE, result.get("status"));
        assertEquals(expectedErrorType, result.getString("errorType"));
        assertEquals(Boolean.valueOf(expectedRetryable), result.get("retryable"));
        assertFalse("失败结果不得带半截 content", result.containsKey("content"));
    }

    private static JSONObject baseParams() {
        JSONObject params = new JSONObject(true);
        params.put("runId", RUN_ID);
        params.put("caseId", CASE_ID);
        params.put("sampleIndex", Integer.valueOf(0));
        params.put("attempt", Integer.valueOf(0));
        JSONObject message = new JSONObject(true);
        message.put("userID", Long.valueOf(10001L));
        message.put("userName", "张三");
        message.put("text", "今天好累");
        params.put("message", message);
        return params;
    }

    private static JSONObject historyRow(String speaker, String userName, String text) {
        JSONObject row = new JSONObject(true);
        row.put("speaker", speaker);
        row.put("userID", Long.valueOf(10001L));
        row.put("userName", userName);
        row.put("text", text);
        return row;
    }

    private static JSONObject okResponse(String content) {
        JSONObject response = new JSONObject(true);
        response.put("status", true);
        response.put("content", content);
        response.put("finishReason", "stop");
        response.put("model", "deepseek-chat");
        response.put("cached", false);
        JSONObject usage = new JSONObject(true);
        usage.put("promptTokens", Integer.valueOf(12));
        usage.put("completionTokens", Integer.valueOf(34));
        usage.put("totalTokens", Integer.valueOf(46));
        usage.put("cachedPromptTokens", Integer.valueOf(0));
        response.put("usage", usage);
        return response;
    }

    private static String repeat(String unit, int times) {
        StringBuilder builder = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(unit);
        }
        return builder.toString();
    }

    /** 假运行时：记录对外调用，response 为空表示取不到 MBB-AI 服务 */
    private static final class FakeRuntime implements RoleplayEvalGenerationService.Runtime {
        boolean enabled = true;
        String aiProfile = "default";
        String evalProfile = "";
        int evalMaxTokens = 1200;
        int evalTimeoutSecond = 60;
        String personaId = "测试角色";
        String personaHash = "abcdef123456";
        JSONObject response;
        final Set<String> invoked = new LinkedHashSet<String>();
        final List<JSONObject> chatCalls = new ArrayList<JSONObject>();
        final List<String> logs = new ArrayList<String>();

        @Override
        public boolean evalApiEnabled() {
            invoked.add("evalApiEnabled");
            return enabled;
        }

        @Override
        public String aiProfile() {
            invoked.add("aiProfile");
            return aiProfile;
        }

        @Override
        public String evalProfile() {
            invoked.add("evalProfile");
            return evalProfile;
        }

        @Override
        public int evalMaxTokens() {
            invoked.add("evalMaxTokens");
            return evalMaxTokens;
        }

        @Override
        public int evalTimeoutSecond() {
            invoked.add("evalTimeoutSecond");
            return evalTimeoutSecond;
        }

        @Override
        public String personaId() {
            invoked.add("personaId");
            return personaId;
        }

        @Override
        public String personaHash() {
            invoked.add("personaHash");
            return personaHash;
        }

        @Override
        public String systemPrompt() {
            invoked.add("systemPrompt");
            return "角色名：测试角色\n性格：安静\n";
        }

        @Override
        public JSONObject chat(JSONObject params) {
            invoked.add("chat");
            chatCalls.add(params);
            return response;
        }

        @Override
        public void log(String line) {
            invoked.add("log");
            logs.add(line);
        }
    }
}
