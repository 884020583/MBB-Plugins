package org.moboxlab.mbb.eval.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * config_snapshot_json 脱敏用例（I-3：规格 §4.2、§12.4、§15.9 I3/I4）。
 *
 * <p>断言只从 redact(JSONObject) 与 toJson() 观察：白名单保留、敏感键剔除并 WARN 一次、
 * 大小写与嵌套、脱敏结果不含敏感原值。</p>
 */
public class EvalSnapshotRedactorTest {

    private final RecordingLogger logger = new RecordingLogger();
    private final EvalSnapshotRedactor redactor = new EvalSnapshotRedactor(logger);

    @Test
    public void shouldKeepWhitelistedConfigAndDropUnknownScalars() {
        JSONObject config = source(
                "aiProfile", "main",
                "maxTokens", 2048,
                "temperature", 0.7,
                "sample_k", 3,
                "evalApiEnable", true,
                "configFingerprint", "abc123",
                "chatHistory", "完整对话原文",
                "unknownFlag", "x");

        JSONObject out = redactor.redact(config);

        assertEquals("main", out.getString("aiProfile"));
        assertEquals(Integer.valueOf(2048), out.getInteger("maxTokens"));
        assertEquals(0.7, out.getDoubleValue("temperature"), 0.0001);
        assertEquals(3, out.getIntValue("sample_k"));
        assertTrue(out.getBooleanValue("evalApiEnable"));
        assertEquals("abc123", out.getString("configFingerprint"));
        assertFalse(out.containsKey("chatHistory"));
        assertFalse(out.containsKey("unknownFlag"));
        assertTrue(redactor.toJson().contains("aiProfile"));
        assertFalse(redactor.toJson().contains("完整对话原文"));
        assertTrue(logger.warns().isEmpty());
    }

    @Test
    public void shouldDropSensitiveKeysAndWarnOncePerKey() {
        JSONObject nested = source("API_KEY", "nested-secret", "aiProfile", "nested-profile");
        JSONObject config = source(
                "aiProfile", "main",
                "apiKey", "top-secret",
                "nested", nested,
                "modelServiceToken", "sk-live-999",
                "accessTokens", "oauth-1");

        JSONObject out = redactor.redact(config);

        assertEquals("main", out.getString("aiProfile"));
        assertFalse(out.containsKey("apiKey"));
        assertFalse(out.containsKey("modelServiceToken"));
        assertFalse(out.containsKey("accessTokens"));
        JSONObject outNested = out.getJSONObject("nested");
        assertNotNull(outNested);
        assertEquals("nested-profile", outNested.getString("aiProfile"));
        assertFalse(outNested.containsKey("API_KEY"));

        String json = redactor.toJson();
        assertFalse(json.contains("top-secret"));
        assertFalse(json.contains("nested-secret"));
        assertFalse(json.contains("sk-live-999"));
        assertFalse(json.contains("oauth-1"));

        assertEquals(3, logger.warns().size());
        String warnings = logger.warns().toString();
        assertTrue(warnings.contains("apiKey"));
        assertTrue(warnings.contains("modelServiceToken"));
        assertTrue(warnings.contains("accessTokens"));
        assertFalse(warnings.contains("top-secret"));
        assertTrue(logger.warns().get(0).startsWith("[eval] "));
    }

    @Test
    public void shouldMatchWhitelistCaseInsensitivelyAndRedactNestedObjects() {
        JSONObject nested = source("Temperature", 0.2, "unknownThing", "y", "API_KEY", "z");
        JSONObject config = source("AIProfile", "x", "MAXTOKENS", 100, "Nested", nested);

        JSONObject out = redactor.redact(config);

        assertEquals("x", out.getString("AIProfile"));
        assertEquals(Integer.valueOf(100), out.getInteger("MAXTOKENS"));
        JSONObject outNested = out.getJSONObject("Nested");
        assertNotNull(outNested);
        assertEquals(0.2, outNested.getDoubleValue("Temperature"), 0.0001);
        assertFalse(outNested.containsKey("unknownThing"));
        assertFalse(outNested.containsKey("API_KEY"));
        assertEquals(1, logger.warns().size());
        assertFalse(redactor.toJson().contains("\"z\""));
    }

    @Test
    public void shouldRedactArrayElementsAndDropUnknownScalarArrays() {
        JSONArray history = new JSONArray();
        history.add(source("aiProfile", "a", "apiKey", "array-secret"));
        history.add(source("temperature", 0.5, "reply", "array-reply"));
        JSONObject config = source(
                "history", history,
                "tags", new JSONArray(Arrays.asList("x", "y")));

        JSONObject out = redactor.redact(config);

        JSONArray outHistory = out.getJSONArray("history");
        assertNotNull(outHistory);
        assertEquals(2, outHistory.size());
        assertEquals("a", outHistory.getJSONObject(0).getString("aiProfile"));
        assertFalse(outHistory.getJSONObject(0).containsKey("apiKey"));
        assertEquals(0.5, outHistory.getJSONObject(1).getDoubleValue("temperature"), 0.0001);
        assertFalse(outHistory.getJSONObject(1).containsKey("reply"));
        assertFalse(out.containsKey("tags"));
        assertFalse(redactor.toJson().contains("array-secret"));
        assertFalse(redactor.toJson().contains("array-reply"));
        assertEquals(1, logger.warns().size());
    }

    @Test
    public void shouldExposeLastRedactionAsCompactJson() {
        EvalSnapshotRedactor fresh = new EvalSnapshotRedactor(logger);
        assertEquals("{}", fresh.toJson());
        assertTrue(fresh.redact(null).isEmpty());
        assertEquals("{}", fresh.toJson());

        JSONObject out = fresh.redact(source("aiProfile", "a", "modelServiceToken", "sk-1"));

        assertEquals(out.toJSONString(), fresh.toJson());
        assertFalse(fresh.toJson().contains("sk-1"));
        assertEquals(1, logger.warns().size());
    }

    @Test
    public void shouldKeepAllNineSnapshotKeys() {
        String[] nineKeys = {
                "bank_hash", "rubric_version", "persona_hash", "config_fingerprint",
                "eval_profile", "judge_profile", "judge_model", "generation_model",
                "eval_api_version"
        };
        JSONObject config = new JSONObject(true);
        for (String key : nineKeys) {
            config.put(key, "v-" + key);
        }

        JSONObject out = redactor.redact(config);

        assertEquals(nineKeys.length, out.size());
        for (String key : nineKeys) {
            assertEquals("v-" + key, out.getString(key));
        }
    }

    private static JSONObject source(Object... pairs) {
        JSONObject object = new JSONObject(true);
        for (int i = 0; i < pairs.length; i += 2) {
            object.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return object;
    }
}
