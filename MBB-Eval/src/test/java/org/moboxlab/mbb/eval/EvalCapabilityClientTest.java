package org.moboxlab.mbb.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.moboxlab.mbb.eval.gateway.EvalCapabilityClient;
import org.moboxlab.mbb.eval.gateway.EvalCapabilityClient.Status;
import org.moboxlab.moboxbot.API.PluginLogger;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * 能力协商客户端用例（C-3，规格 §10.1、§13.2、§15.3 C2/C3/C4）。
 *
 * <p>只经公开缝验证：注入假的 {@link EvalCapabilityClient.CapabilitiesSource}，断言状态枚举、
 * 中文原因能否按「版本不匹配 / 未启用 / 插件缺失」区分、缓存只探测一次、异常不穿出，
 * 以及启动日志每次实例只告警一次。不触碰 MBB-Roleplay 真实服务，也不写任何数据表。</p>
 */
public class EvalCapabilityClientTest {

    @Test
    public void availableShouldExposeAllCapabilityFields() {
        JSONObject capabilities = availableCapabilities();
        CountingSource source = new CountingSource(capabilities);
        EvalCapabilityClient client = new EvalCapabilityClient(source, new RecordingLogger());

        assertEquals(Status.AVAILABLE, client.status());
        assertTrue("AVAILABLE 必须允许 run", client.isRunAllowed());
        assertNull("可用时没有禁用原因", client.disableReason());

        EvalCapabilityClient.Snapshot snapshot = client.snapshot();
        assertEquals(1, snapshot.evalApiVersion());
        assertEquals(2, snapshot.actions().size());
        assertTrue(snapshot.actions().contains("capabilities"));
        assertTrue(snapshot.actions().contains("eval-generate"));
        assertTrue(snapshot.supports("eval-generate"));
        assertTrue(snapshot.evalApiEnable());
        assertEquals("momoi", snapshot.personaId());
        assertEquals("0123456789ab", snapshot.personaHash());
        assertEquals("feedface", snapshot.configFingerprint());
        assertEquals("default", snapshot.aiProfile());
        assertEquals("eval-profile", snapshot.evalProfile());
        assertEquals(4000, snapshot.replyMaxTokens());
        assertEquals(120000, snapshot.promptTotalChars());
        assertTrue(snapshot.toolEnable());
        assertFalse(snapshot.replyReviewEnable());
        assertEquals(40, snapshot.maxHistoryRounds());
        assertEquals(30000, snapshot.maxHistoryChars());
        assertTrue("AVAILABLE 的快照仍保留原始评估接口开关", snapshot.evalApiEnable());
    }

    @Test
    public void availableShouldLogCapabilitiesResultOnce() {
        RecordingLogger logger = new RecordingLogger();
        EvalCapabilityClient client = new EvalCapabilityClient(new CountingSource(availableCapabilities()), logger);

        client.status();
        client.refresh();
        client.refresh();

        assertEquals("启动诊断只写一条 INFO", 1, logger.infos().size());
        String line = logger.infos().get(0);
        assertTrue("日志须带 evalApiVersion：" + line, line.contains("evalApiVersion=1"));
        assertTrue("日志须带 actions：" + line, line.contains("eval-generate"));
        assertTrue("日志须带 [MBB-Eval] 前缀：" + line, line.startsWith("[MBB-Eval] "));
        assertEquals(0, logger.warns().size());
        assertEquals(0, logger.errors().size());
    }

    @Test
    public void probeShouldCacheAndCallSourceOnlyOnce() {
        CountingSource source = new CountingSource(availableCapabilities());
        EvalCapabilityClient client = new EvalCapabilityClient(source, new RecordingLogger());

        for (int i = 0; i < 5; i++) {
            client.probe();
            client.snapshot();
            client.status();
            client.isRunAllowed();
            client.disableReason();
        }

        assertEquals("缓存命中的读路径不得重复探测", 1, source.calls());
    }

    @Test
    public void refreshShouldReprobeAndReplaceCachedResult() {
        CountingSource source = new CountingSource(null);
        EvalCapabilityClient client = new EvalCapabilityClient(source, new RecordingLogger());
        assertEquals(Status.PLUGIN_MISSING, client.status());

        source.payload(availableCapabilities());
        EvalCapabilityClient.Snapshot refreshed = client.refresh();

        assertEquals(Status.AVAILABLE, refreshed.status());
        assertEquals(2, source.calls());
    }

    @Test
    public void versionTwoShouldBeVersionMismatchWithoutFieldGuessing() {
        JSONObject capabilities = new JSONObject(true);
        capabilities.put("evalApiVersion", 2);
        JSONArray actions = new JSONArray();
        actions.add("capabilities");
        actions.add("eval-generate");
        capabilities.put("actions", actions);

        EvalCapabilityClient client = new EvalCapabilityClient(new CountingSource(capabilities), new RecordingLogger());

        assertThreeWay("版本不匹配", Status.VERSION_MISMATCH, client);
        assertTrue("必须回报实际版本：" + client.disableReason(), client.disableReason().contains("evalApiVersion=2"));
    }

    @Test
    public void missingVersionShouldBeVersionMismatch() {
        JSONObject capabilities = new JSONObject(true);
        capabilities.put("actions", new JSONArray());

        EvalCapabilityClient client = new EvalCapabilityClient(new CountingSource(capabilities), new RecordingLogger());

        assertThreeWay("版本不匹配", Status.VERSION_MISMATCH, client);
        assertTrue(client.disableReason().contains("evalApiVersion"));
    }

    @Test
    public void nonNumericVersionShouldBeVersionMismatch() {
        JSONObject capabilities = new JSONObject(true);
        capabilities.put("evalApiVersion", "1");
        JSONArray actions = new JSONArray();
        actions.add("eval-generate");
        capabilities.put("actions", actions);

        EvalCapabilityClient client = new EvalCapabilityClient(new CountingSource(capabilities), new RecordingLogger());

        assertThreeWay("版本不匹配", Status.VERSION_MISMATCH, client);
    }

    @Test
    public void actionsWithoutGenerateShouldBeDisabled() {
        JSONObject capabilities = availableCapabilities();
        JSONArray actions = new JSONArray();
        actions.add("capabilities");
        capabilities.put("actions", actions);

        EvalCapabilityClient client = new EvalCapabilityClient(new CountingSource(capabilities), new RecordingLogger());

        assertThreeWay("未启用", Status.DISABLED, client);
        assertTrue("必须说明 actions 缺 eval-generate：" + client.disableReason(),
                client.disableReason().contains("eval-generate"));
    }

    @Test
    public void evalApiEnableFalseShouldBeDisabled() {
        JSONObject capabilities = availableCapabilities();
        capabilities.put("evalApiEnable", false);
        JSONArray actions = new JSONArray();
        actions.add("capabilities");
        capabilities.put("actions", actions);

        EvalCapabilityClient client = new EvalCapabilityClient(new CountingSource(capabilities), new RecordingLogger());

        assertThreeWay("未启用", Status.DISABLED, client);
        assertTrue("必须说明 evalApiEnable=false：" + client.disableReason(),
                client.disableReason().contains("evalApiEnable=false"));
    }

    @Test
    public void nullCapabilitiesShouldBePluginMissing() {
        EvalCapabilityClient client = new EvalCapabilityClient(new CountingSource(null), new RecordingLogger());

        assertThreeWay("插件缺失", Status.PLUGIN_MISSING, client);
        assertFalse("不得把缺失误报为未启用", client.disableReason().contains("未启用"));
    }

    @Test
    public void throwingSourceShouldBeContractErrorAndNotEscape() {
        CountingSource source = CountingSource.failing(new IllegalStateException("服务调用失败"));
        RecordingLogger logger = new RecordingLogger();
        EvalCapabilityClient client = new EvalCapabilityClient(source, logger);

        Status status = null;
        try {
            status = client.status();
        } catch (RuntimeException e) {
            throw new AssertionError("探测异常不得穿出客户端", e);
        }

        assertEquals(Status.CONTRACT_ERROR, status);
        assertFalse(client.isRunAllowed());
        assertNotNull(client.disableReason());
        assertTrue("必须保留异常原因：" + client.disableReason(), client.disableReason().contains("服务调用失败"));
        assertEquals("契约异常记 ERROR", 1, logger.errors().size());
        assertEquals(0, logger.warns().size());
    }

    @Test
    public void nonJsonPayloadShouldBeContractError() {
        EvalCapabilityClient client = new EvalCapabilityClient(new CountingSource("not-an-object"), new RecordingLogger());

        assertEquals(Status.CONTRACT_ERROR, client.status());
        assertTrue("必须说明载荷类型不符：" + client.disableReason(),
                client.disableReason().contains("JSONObject"));
    }

    @Test
    public void statusFalseShouldBeContractError() {
        JSONObject capabilities = new JSONObject(true);
        capabilities.put("status", false);
        capabilities.put("action", "capabilities");
        capabilities.put("message", "计算能力字段失败：persona 未就绪");

        EvalCapabilityClient client = new EvalCapabilityClient(new CountingSource(capabilities), new RecordingLogger());

        assertEquals(Status.CONTRACT_ERROR, client.status());
        assertTrue("必须保留角色侧失败原因：" + client.disableReason(),
                client.disableReason().contains("persona 未就绪"));
        assertTrue(client.disableReason().contains("status=false"));
    }

    @Test
    public void missingActionsFieldShouldBeContractError() {
        JSONObject capabilities = availableCapabilities();
        capabilities.remove("actions");

        EvalCapabilityClient client = new EvalCapabilityClient(new CountingSource(capabilities), new RecordingLogger());

        assertEquals(Status.CONTRACT_ERROR, client.status());
        assertTrue("必须点名缺失字段：" + client.disableReason(), client.disableReason().contains("actions"));
        assertFalse("缺字段不得降级猜测为未启用", client.disableReason().contains("未启用"));
    }

    @Test
    public void missingRequiredFieldShouldBeContractError() {
        JSONObject capabilities = availableCapabilities();
        capabilities.remove("personaHash");

        EvalCapabilityClient client = new EvalCapabilityClient(new CountingSource(capabilities), new RecordingLogger());

        assertEquals(Status.CONTRACT_ERROR, client.status());
        assertTrue("必须点名缺失字段：" + client.disableReason(), client.disableReason().contains("personaHash"));
    }

    @Test
    public void wrongFieldTypeShouldBeContractError() {
        JSONObject capabilities = availableCapabilities();
        capabilities.put("replyMaxTokens", "4000");

        EvalCapabilityClient client = new EvalCapabilityClient(new CountingSource(capabilities), new RecordingLogger());

        assertEquals(Status.CONTRACT_ERROR, client.status());
        assertTrue("必须点名类型不符字段：" + client.disableReason(), client.disableReason().contains("replyMaxTokens"));
    }

    @Test
    public void failureShouldWarnOnlyOnceEvenWhenReprobed() {
        RecordingLogger logger = new RecordingLogger();
        EvalCapabilityClient client = new EvalCapabilityClient(new CountingSource(null), logger);

        client.status();
        client.refresh();
        client.refresh();

        assertEquals("预检类失败只 WARN 一次", 1, logger.warns().size());
        assertEquals(0, logger.errors().size());
        String line = logger.warns().get(0);
        assertTrue("日志须带 [MBB-Eval] 前缀：" + line, line.startsWith("[MBB-Eval] "));
        assertTrue("日志须说明插件缺失：" + line, line.contains("插件缺失"));
    }

    /** 三选一互斥：状态正确、run 被禁、原因只命中本类关键词。 */
    private static void assertThreeWay(String keyword, Status expected, EvalCapabilityClient client) {
        assertEquals(expected, client.status());
        assertFalse("非 AVAILABLE 必须禁用 run", client.isRunAllowed());
        String reason = client.disableReason();
        assertNotNull(reason);
        assertTrue("原因必须包含「" + keyword + "」：" + reason, reason.contains(keyword));
        String[] otherKeywords = { "版本不匹配", "未启用", "插件缺失" };
        for (String other : otherKeywords) {
            if (!other.equals(keyword)) {
                assertFalse("三选一必须互斥，不应出现「" + other + "」：" + reason, reason.contains(other));
            }
        }
    }

    /** 与 MBB-Roleplay 侧 RoleplayEvalGateway.buildCapabilities 出参同形状的 14 字段样本。 */
    private static JSONObject availableCapabilities() {
        JSONObject capabilities = new JSONObject(true);
        capabilities.put("status", true);
        capabilities.put("action", "capabilities");
        capabilities.put("evalApiVersion", 1);
        JSONArray actions = new JSONArray();
        actions.add("capabilities");
        actions.add("eval-generate");
        capabilities.put("actions", actions);
        capabilities.put("personaId", "momoi");
        capabilities.put("personaHash", "0123456789ab");
        capabilities.put("configFingerprint", "feedface");
        capabilities.put("aiProfile", "default");
        capabilities.put("evalProfile", "eval-profile");
        capabilities.put("replyMaxTokens", 4000);
        capabilities.put("promptTotalChars", 120000);
        capabilities.put("toolEnable", true);
        capabilities.put("replyReviewEnable", false);
        capabilities.put("maxHistoryRounds", 40);
        capabilities.put("maxHistoryChars", 30000);
        capabilities.put("evalApiEnable", true);
        return capabilities;
    }

    /** 计数器假 source：同时模拟正常返回、可替换载荷与抛异常三种路径。 */
    private static final class CountingSource implements EvalCapabilityClient.CapabilitiesSource {
        private Object payload;
        private RuntimeException failure;
        private int calls;

        private CountingSource(Object payload) {
            this.payload = payload;
        }

        private static CountingSource failing(RuntimeException failure) {
            CountingSource source = new CountingSource(null);
            source.failure = failure;
            return source;
        }

        @Override
        public Object fetch() {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return payload;
        }

        private void payload(Object payload) {
            this.payload = payload;
        }

        private int calls() {
            return calls;
        }
    }

    /** 只收集日志的假件：不走主程序日志，避免测试写出日志文件。 */
    private static final class RecordingLogger extends PluginLogger {
        private final List<String> infos = new ArrayList<String>();
        private final List<String> warns = new ArrayList<String>();
        private final List<String> errors = new ArrayList<String>();

        private RecordingLogger() {
            super("test", null);
        }

        @Override
        public void sendInfo(String message) {
            infos.add(message);
        }

        @Override
        public void sendWarn(String message) {
            warns.add(message);
        }

        @Override
        public void sendError(String message) {
            errors.add(message);
        }

        private List<String> infos() {
            return Collections.unmodifiableList(infos);
        }

        private List<String> warns() {
            return Collections.unmodifiableList(warns);
        }

        private List<String> errors() {
            return Collections.unmodifiableList(errors);
        }
    }
}
