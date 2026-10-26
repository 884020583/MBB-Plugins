package org.moboxlab.mbb.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.BeforeClass;
import org.junit.Test;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * 题库资产自查（B-1：内置题库 bank-1.0.0）。
 *
 * <p>只从构建产物 {@code out/build/classes/bank/bank-1.0.0.json} 读取资产，不走 classpath：
 * 测试 classpath 把主程序 JAR 放在最前，主程序根目录可能存在同名资源。</p>
 *
 * <p>本测试只做资产侧结构自查；加载期的完整校验与错误模型由 B-3 的校验器负责。</p>
 */
public class EvalBankAssetTest {

    private static final String BANK_FILE_NAME = "bank-1.0.0.json";

    private static final List<String> HARD_IDS = Arrays.asList(
            "no-markdown", "no-dash", "no-cs-tone", "no-honorific", "no-paired-quote");
    private static final List<String> SOFT_IDS = Arrays.asList(
            "length", "self-third-person", "catchphrase-low-freq", "sentence-length-variance");
    private static final List<String> DIMENSIONS = Arrays.asList(
            "persona", "style", "knowledge", "boundary", "memory");
    private static final List<String> SPEAKERS = Arrays.asList("self", "peer", "otherRole");
    private static final List<String> ADDRESSED = Arrays.asList("direct", "thread", "ambient");
    private static final List<String> RELATIONSHIPS = Arrays.asList("friend", "teacher", "otherRolePeer");

    /** §5.3 公共字段与场景字段。 */
    private static final List<String> BASE_FIELDS = Arrays.asList(
            "id", "dimension", "tier", "prompt", "focus", "reference", "source", "sourceNote",
            "history", "addressed", "relationship");

    /** 运行期字段不得写进资产（§5.2.5）。 */
    private static final List<String> RUNTIME_FIELDS = Arrays.asList(
            "runId", "run_id", "score", "scores", "status", "caseResult", "result",
            "token", "tokens", "elapsedMs", "durationMs", "createdAt", "updatedAt");

    /** §5.5.2 冻结的 40 条 id，按维度顺序。 */
    private static final List<String> EXPECTED_IDS = Arrays.asList(
            "persona-01", "persona-02", "persona-03", "persona-04", "persona-05", "persona-06",
            "persona-07", "persona-08", "persona-09", "persona-10", "persona-11", "persona-12",
            "style-01", "style-02", "style-03", "style-04", "style-05", "style-06", "style-07", "style-08",
            "knowledge-01", "knowledge-02", "knowledge-03", "knowledge-04",
            "knowledge-05", "knowledge-06", "knowledge-07", "knowledge-08",
            "boundary-01", "boundary-02", "boundary-03", "boundary-04", "boundary-05", "boundary-06",
            "memory-01", "memory-02", "memory-03", "memory-04", "memory-05", "memory-06");

    /** §5.5.2 冻结的 smoke 12 条。 */
    private static final Set<String> EXPECTED_SMOKE = new LinkedHashSet<String>(Arrays.asList(
            "persona-01", "persona-02", "style-01", "style-06", "style-08",
            "knowledge-01", "knowledge-02", "knowledge-05",
            "boundary-01", "boundary-05", "memory-01", "memory-04"));

    private static final Pattern ID_PATTERN = Pattern.compile("^(persona|style|knowledge|boundary|memory)-\\d{2}$");
    private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-f]{12}$");
    private static final Pattern OPTION_LINE_PATTERN = Pattern.compile("(?m)^\\s*[A-DＡ-Ｄ][.、:：]");

    private static File bankFile;
    private static JSONObject bank;

    @BeforeClass
    public static void loadBank() throws Exception {
        bankFile = new File(locateClassesDir(), "bank/" + BANK_FILE_NAME);
        assertTrue("题库资产不存在，请先执行 build.ps1：" + bankFile.getAbsolutePath(), bankFile.isFile());
        bank = JSON.parseObject(decodeUtf8(Files.readAllBytes(bankFile.toPath())));
        assertNotNull("题库资产解析结果为空", bank);
    }

    @Test
    public void bankFileShouldBeUtf8WithoutBomAndUnderLimit() throws Exception {
        byte[] bytes = Files.readAllBytes(bankFile.toPath());
        assertTrue("题库资产应小于 512KB，实际 " + bytes.length + " 字节", bytes.length < 512 * 1024);
        assertFalse("题库资产应为 UTF-8 无 BOM",
                bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF);
        // decodeUtf8 用 REPORT 策略，非法字节会直接抛异常
        assertNotNull(decodeUtf8(bytes));
    }

    @Test
    public void topLevelStructureShouldMatchSchemaOne() {
        assertEquals("schemaVersion 必须恒为 1", 1, bank.getIntValue("schemaVersion"));
        assertEquals("version 应为 1.0.0", "1.0.0", bank.getString("version"));
        assertEquals("版本号必须与文件名字版本一致", "bank-" + bank.getString("version") + ".json", BANK_FILE_NAME);

        JSONObject persona = bank.getJSONObject("persona");
        assertNotNull("persona 必填", persona);
        assertEquals("persona.file 应为角色事实文件", "persona-aris.json", persona.getString("file"));

        JSONObject meta = bank.getJSONObject("meta");
        assertNotNull("meta 可选，但本资产提供", meta);
        assertFalse("meta.title 非空", isBlank(meta.getString("title")));
        assertFalse("meta.description 非空", isBlank(meta.getString("description")));
        assertEquals("meta 只允许 title 与 description", new HashSet<String>(Arrays.asList("title", "description")),
                new HashSet<String>(meta.keySet()));

        JSONArray cases = bank.getJSONArray("cases");
        assertNotNull("cases 必填", cases);
        assertEquals("v1 题库固定 40 条", 40, cases.size());
    }

    @Test
    public void personaSummaryAndHashShouldBindPersonaFile() throws Exception {
        JSONObject persona = bank.getJSONObject("persona");
        Object summaryValue = persona.get("summary");
        assertTrue("persona.summary 必须是字符串", summaryValue instanceof String);
        String summary = (String) summaryValue;
        int codePoints = summary.codePointCount(0, summary.length());
        assertTrue("persona.summary 长度应为 1–400 码点，实际 " + codePoints,
                codePoints >= 1 && codePoints <= 400);

        String hash = persona.getString("personaHash");
        assertNotNull("personaHash 可选，但本资产提供用于绑定角色事实", hash);
        assertTrue("personaHash 应为 12 位小写十六进制：" + hash, HASH_PATTERN.matcher(hash).matches());

        File personaFile = locatePersonaFile();
        assertTrue("找不到角色事实文件：" + personaFile.getAbsolutePath(), personaFile.isFile());
        String actual = sha256Hex(personaFile).substring(0, 12);
        assertEquals("personaHash 必须等于角色事实文件 sha256 前 12 位", actual, hash);
    }

    @Test
    public void casesShouldHaveFrozenIdsAndDimensionMix() {
        JSONArray cases = bank.getJSONArray("cases");
        List<String> ids = new ArrayList<String>();
        Map<String, Integer> byDimension = new HashMap<String, Integer>();
        for (int i = 0; i < cases.size(); i++) {
            JSONObject item = cases.getJSONObject(i);
            String id = item.getString("id");
            ids.add(id);
            String dimension = item.getString("dimension");
            assertTrue("维度必须是五维之一：" + dimension, DIMENSIONS.contains(dimension));
            assertTrue("id 必须匹配 §5.3.1 格式：" + id, id != null && ID_PATTERN.matcher(id).matches());
            assertTrue("id 前缀必须等于 dimension：" + id, id.startsWith(dimension + "-"));
            Integer count = byDimension.get(dimension);
            byDimension.put(dimension, count == null ? 1 : count + 1);
        }

        assertEquals("应与 §5.5.2 冻结的 40 条 id 完全一致", EXPECTED_IDS, ids);
        assertEquals("id 不得重复", 40, new HashSet<String>(ids).size());

        Map<String, Integer> expectedMix = new LinkedHashMap<String, Integer>();
        expectedMix.put("persona", 12);
        expectedMix.put("style", 8);
        expectedMix.put("knowledge", 8);
        expectedMix.put("boundary", 6);
        expectedMix.put("memory", 6);
        assertEquals("维度配比应与 §5.5.1 一致", expectedMix, byDimension);
    }

    @Test
    public void smokeSubsetShouldBeFrozenTwelveCoveringAllDimensionsAndModes() {
        JSONArray cases = bank.getJSONArray("cases");
        Set<String> smokeIds = new LinkedHashSet<String>();
        Map<String, Integer> smokeByDimension = new HashMap<String, Integer>();
        Set<String> knowledgeModes = new HashSet<String>();
        for (int i = 0; i < cases.size(); i++) {
            JSONObject item = cases.getJSONObject(i);
            String tier = item.getString("tier");
            assertTrue("tier 只能是 smoke 或 full：" + tier, "smoke".equals(tier) || "full".equals(tier));
            if (!"smoke".equals(tier)) {
                continue;
            }
            smokeIds.add(item.getString("id"));
            String dimension = item.getString("dimension");
            Integer count = smokeByDimension.get(dimension);
            smokeByDimension.put(dimension, count == null ? 1 : count + 1);
            if ("knowledge".equals(dimension)) {
                knowledgeModes.add(item.getJSONObject("knowledge").getString("mode"));
            }
        }

        assertEquals("smoke 应为冻结的 12 条", EXPECTED_SMOKE, smokeIds);
        assertEquals("smoke 应覆盖全部五个维度", 5, smokeByDimension.size());
        assertEquals("smoke persona 2 条", Integer.valueOf(2), smokeByDimension.get("persona"));
        assertEquals("smoke style 3 条", Integer.valueOf(3), smokeByDimension.get("style"));
        assertEquals("smoke knowledge 3 条", Integer.valueOf(3), smokeByDimension.get("knowledge"));
        assertEquals("smoke boundary 2 条", Integer.valueOf(2), smokeByDimension.get("boundary"));
        assertEquals("smoke memory 2 条", Integer.valueOf(2), smokeByDimension.get("memory"));
        assertEquals("smoke 的 knowledge 必须同时覆盖 choice 与 points",
                new HashSet<String>(Arrays.asList("choice", "points")), knowledgeModes);
    }

    @Test
    public void caseFieldsShouldBeCompleteAndTyped() {
        List<String> base = new ArrayList<String>(BASE_FIELDS);
        for (JSONObject item : eachCase()) {
            String id = item.getString("id");
            String dimension = item.getString("dimension");

            List<String> allowed = new ArrayList<String>(base);
            if ("style".equals(dimension)) {
                allowed.add("style");
            } else if ("knowledge".equals(dimension)) {
                allowed.add("knowledge");
            } else if ("memory".equals(dimension)) {
                allowed.add("memory");
            }
            for (String key : item.keySet()) {
                assertTrue(id + " 含不允许的字段：" + key, allowed.contains(key));
                assertFalse(id + " 含运行期字段：" + key, RUNTIME_FIELDS.contains(key));
            }
            for (String key : bank.keySet()) {
                assertFalse("顶层含运行期字段：" + key, RUNTIME_FIELDS.contains(key));
            }

            assertFalse(id + " 的 prompt 不得为空", isBlank(item.getString("prompt")));
            assertEquals(id + " 的 source 必须为 handcrafted", "handcrafted", item.getString("source"));
            assertFalse(id + " 不得带 sourceNote（仅 chat 来源使用）", item.containsKey("sourceNote"));

            boolean focusRequired = "persona".equals(dimension) || "boundary".equals(dimension)
                    || "memory".equals(dimension);
            if (focusRequired) {
                assertFalse(id + " 的 focus 必填", isBlank(item.getString("focus")));
            } else if (item.containsKey("focus")) {
                assertFalse(id + " 的 focus 若存在则不得为空", isBlank(item.getString("focus")));
            }

            if ("knowledge".equals(dimension)) {
                assertFalse(id + " 的 reference 必填", isBlank(item.getString("reference")));
                JSONObject knowledge = item.getJSONObject("knowledge");
                assertNotNull(id + " 必须有 knowledge 对象", knowledge);
                String mode = knowledge.getString("mode");
                assertTrue(id + " 的 mode 只能是 choice 或 points：" + mode,
                        "choice".equals(mode) || "points".equals(mode));
            } else if (item.containsKey("reference")) {
                assertFalse(id + " 的 reference 若存在则不得为空", isBlank(item.getString("reference")));
            }
        }
    }

    @Test
    public void scenarioFieldsShouldRespectHistoryLimitsAndEnums() {
        boolean hasHistory = false;
        boolean hasThread = false;
        boolean hasTeacher = false;
        boolean hasOtherRole = false;
        for (JSONObject item : eachCase()) {
            String id = item.getString("id");

            String addressed = item.getString("addressed");
            if (item.containsKey("addressed")) {
                assertTrue(id + " 的 addressed 非法：" + addressed, ADDRESSED.contains(addressed));
                hasThread = hasThread || "thread".equals(addressed);
            }
            String relationship = item.getString("relationship");
            if (item.containsKey("relationship")) {
                assertTrue(id + " 的 relationship 非法：" + relationship, RELATIONSHIPS.contains(relationship));
                hasTeacher = hasTeacher || "teacher".equals(relationship);
            }

            if (!item.containsKey("history")) {
                continue;
            }
            hasHistory = true;
            Object historyValue = item.get("history");
            assertTrue(id + " 的 history 必须是数组", historyValue instanceof JSONArray);
            JSONArray history = (JSONArray) historyValue;
            assertTrue(id + " 的 history 不得为空", history.size() >= 1);
            assertTrue(id + " 的 history 不得超过 40 条，实际 " + history.size(), history.size() <= 40);
            long totalChars = 0;
            for (int i = 0; i < history.size(); i++) {
                Object turnValue = history.get(i);
                assertTrue(id + " 的 history[" + i + "] 必须是对象", turnValue instanceof JSONObject);
                JSONObject turn = (JSONObject) turnValue;
                String speaker = turn.getString("speaker");
                assertTrue(id + " 的 history[" + i + "].speaker 非法：" + speaker, SPEAKERS.contains(speaker));
                hasOtherRole = hasOtherRole || "otherRole".equals(speaker);
                assertFalse(id + " 的 history[" + i + "].userName 不得为空", isBlank(turn.getString("userName")));
                String text = turn.getString("text");
                assertFalse(id + " 的 history[" + i + "].text 不得为空", isBlank(text));
                assertFalse(id + " 的 history[" + i + "] 不得写 userID", turn.containsKey("userID"));
                assertFalse(id + " 的 history[" + i + "] 不得写 userId", turn.containsKey("userId"));
                assertFalse(id + " 的 history[" + i + "] 不得写 user_id", turn.containsKey("user_id"));
                totalChars += text.length();
            }
            assertTrue(id + " 的 history 合计不得超过 30000 字符，实际 " + totalChars, totalChars <= 30000);
        }

        assertTrue("资产应实际使用 history 场景字段", hasHistory);
        assertTrue("资产应实际覆盖 addressed=thread", hasThread);
        assertTrue("资产应实际覆盖 relationship=teacher", hasTeacher);
        assertTrue("资产应实际包含 otherRole 发言", hasOtherRole);
    }

    @Test
    public void styleMountsShouldFollowFrozenRubricIds() {
        Map<String, JSONObject> styleCases = new LinkedHashMap<String, JSONObject>();
        for (JSONObject item : eachCase()) {
            if ("style".equals(item.getString("dimension"))) {
                styleCases.put(item.getString("id"), item);
            }
        }
        assertEquals("style 维度应有 8 条", 8, styleCases.size());

        for (Map.Entry<String, JSONObject> entry : styleCases.entrySet()) {
            String id = entry.getKey();
            JSONObject item = entry.getValue();
            Object styleValue = item.get("style");
            if (!item.containsKey("style")) {
                continue;
            }
            assertTrue(id + " 的 style 必须是对象", styleValue instanceof JSONObject);
            JSONObject style = (JSONObject) styleValue;

            Object hardValue = style.get("hard");
            if (hardValue != null) {
                assertTrue(id + " 的 style.hard 必须是数组", hardValue instanceof JSONArray);
                JSONArray hard = (JSONArray) hardValue;
                assertTrue(id + " 的 style.hard 不得为空", hard.size() >= 1);
                Set<String> seen = new HashSet<String>();
                for (int i = 0; i < hard.size(); i++) {
                    String hardId = hard.getString(i);
                    assertTrue(id + " 的硬项 id 不在 §6.5.1 目录：" + hardId, HARD_IDS.contains(hardId));
                    assertTrue(id + " 的硬项 id 重复：" + hardId, seen.add(hardId));
                }
            }

            Object softValue = style.get("soft");
            if (softValue != null) {
                assertTrue(id + " 的 style.soft 必须是对象", softValue instanceof JSONObject);
                JSONObject soft = (JSONObject) softValue;
                assertFalse(id + " 的 style.soft 不得为空", soft.isEmpty());
                for (String softId : soft.keySet()) {
                    assertTrue(id + " 的软项 id 不在 §6.5.2 目录：" + softId, SOFT_IDS.contains(softId));
                    Object value = soft.get(softId);
                    if (value instanceof Boolean) {
                        continue;
                    }
                    assertTrue(id + " 的软项参数必须是布尔或对象：" + softId, value instanceof JSONObject);
                    assertTrue(id + " 只有 length 可以带参数对象", "length".equals(softId));
                    JSONObject params = (JSONObject) value;
                    for (String key : params.keySet()) {
                        assertTrue(id + " 的 length 参数只允许 min 与 max：" + key,
                                "min".equals(key) || "max".equals(key));
                    }
                    int min = params.containsKey("min") ? params.getIntValue("min") : 10;
                    int max = params.containsKey("max") ? params.getIntValue("max") : 120;
                    assertTrue(id + " 的 length 参数应为正整数", min > 0 && max > 0);
                    assertTrue(id + " 的 length 参数应满足 min < max", min < max);
                    assertTrue(id + " 的 length 上限不应超过 T4 的 180", max <= 180);
                }
            }
        }

        assertFalse("style-01 应完全继承 defaultMount，不写 style", styleCases.get("style-01").containsKey("style"));
        assertFalse("style-02 应完全继承 defaultMount，不写 style", styleCases.get("style-02").containsKey("style"));
        assertFalse("style-03 应完全继承 defaultMount，不写 style", styleCases.get("style-03").containsKey("style"));
        assertSoftBoolean(styleCases.get("style-04"), "self-third-person");
        assertSoftBoolean(styleCases.get("style-05"), "self-third-person");
        assertSoftBoolean(styleCases.get("style-06"), "catchphrase-low-freq");
        assertSoftBoolean(styleCases.get("style-07"), "catchphrase-low-freq");

        JSONObject t4Soft = styleCases.get("style-08").getJSONObject("style").getJSONObject("soft");
        assertNotNull("style-08 应挂 length 覆写", t4Soft);
        JSONObject length = t4Soft.getJSONObject("length");
        assertNotNull("style-08 的 length 应是参数对象", length);
        assertEquals("T4 句长上限应为 180", 180, length.getIntValue("max"));
        assertFalse("style-08 的 length 不应带 min 覆写", length.containsKey("min"));
    }

    @Test
    public void knowledgeCasesShouldSplitFourChoiceAndFourPoints() {
        Map<String, JSONObject> knowledgeCases = new LinkedHashMap<String, JSONObject>();
        for (JSONObject item : eachCase()) {
            if ("knowledge".equals(item.getString("dimension"))) {
                knowledgeCases.put(item.getString("id"), item);
            }
        }
        assertEquals("knowledge 维度应有 8 条", 8, knowledgeCases.size());
        assertEquals("choice 应 4 条", 4, countMode(knowledgeCases, "choice"));
        assertEquals("points 应 4 条", 4, countMode(knowledgeCases, "points"));

        for (String id : Arrays.asList("knowledge-01", "knowledge-02", "knowledge-03", "knowledge-04")) {
            assertEquals(id + " 应为 choice", "choice", knowledgeCases.get(id).getJSONObject("knowledge").getString("mode"));
        }
        for (String id : Arrays.asList("knowledge-05", "knowledge-06", "knowledge-07", "knowledge-08")) {
            assertEquals(id + " 应为 points", "points", knowledgeCases.get(id).getJSONObject("knowledge").getString("mode"));
        }
    }

    @Test
    public void choiceOptionsShouldHaveSingleCorrectItemWithoutSubstrings() {
        for (JSONObject item : eachCase()) {
            if (!"knowledge".equals(item.getString("dimension"))) {
                continue;
            }
            JSONObject knowledge = item.getJSONObject("knowledge");
            if (!"choice".equals(knowledge.getString("mode"))) {
                continue;
            }
            String id = item.getString("id");
            assertFalse(id + " 的 choice 不应同时带 expected", knowledge.containsKey("expected"));
            Object choicesValue = knowledge.get("choices");
            assertTrue(id + " 的 choice 必须有 choices 数组", choicesValue instanceof JSONArray);
            JSONArray choices = (JSONArray) choicesValue;
            assertTrue(id + " 的选项数应为 3–5（1 正确 + 2–4 干扰）", choices.size() >= 3 && choices.size() <= 5);

            String prompt = item.getString("prompt");
            List<String> texts = new ArrayList<String>();
            int correctCount = 0;
            for (int i = 0; i < choices.size(); i++) {
                JSONObject choice = choices.getJSONObject(i);
                String text = choice.getString("text");
                assertFalse(id + " 的选项文本不得为空", isBlank(text));
                texts.add(text);
                assertFalse(id + " 的题面不得直接列出选项：" + text, prompt.contains(text));
                Object correct = choice.get("correct");
                if (correct != null) {
                    assertTrue(id + " 的 correct 必须是布尔", correct instanceof Boolean);
                    if (Boolean.TRUE.equals(correct)) {
                        correctCount++;
                    }
                }
            }
            assertEquals(id + " 必须恰有一个 correct:true", 1, correctCount);
            assertFalse(id + " 的题面不得使用 A/B/C/D 选项行", OPTION_LINE_PATTERN.matcher(prompt).find());

            for (int i = 0; i < texts.size(); i++) {
                for (int j = 0; j < texts.size(); j++) {
                    if (i == j) {
                        continue;
                    }
                    assertFalse(id + " 的选项互为子串：" + texts.get(i) + " / " + texts.get(j),
                            texts.get(i).contains(texts.get(j)));
                }
            }
            assertAbsentOrEmpty(knowledge, "negative", id);
            assertAbsentOrEmpty(knowledge, "unitAliases", id);
        }
    }

    @Test
    public void pointExpectationsShouldBeWellFormed() {
        for (JSONObject item : eachCase()) {
            if (!"knowledge".equals(item.getString("dimension"))) {
                continue;
            }
            JSONObject knowledge = item.getJSONObject("knowledge");
            if (!"points".equals(knowledge.getString("mode"))) {
                continue;
            }
            String id = item.getString("id");
            assertFalse(id + " 的 points 不应同时带 choices", knowledge.containsKey("choices"));
            Object expectedValue = knowledge.get("expected");
            assertTrue(id + " 的 points 必须有 expected 数组", expectedValue instanceof JSONArray);
            JSONArray expected = (JSONArray) expectedValue;
            assertTrue(id + " 的期望点应为 1–3 条", expected.size() >= 1 && expected.size() <= 3);
            for (int i = 0; i < expected.size(); i++) {
                JSONObject point = expected.getJSONObject(i);
                assertFalse(id + " 的期望点 label 不得为空", isBlank(point.getString("label")));
                Object aliasesValue = point.get("aliases");
                assertTrue(id + " 的期望点 aliases 必须是数组", aliasesValue instanceof JSONArray);
                JSONArray aliases = (JSONArray) aliasesValue;
                assertTrue(id + " 的期望点 aliases 不得为空", aliases.size() >= 1);
                for (int j = 0; j < aliases.size(); j++) {
                    String alias = aliases.getString(j);
                    assertFalse(id + " 的 alias 不得为空", isBlank(alias));
                }
            }
            assertAbsentOrEmpty(knowledge, "negative", id);
            assertAbsentOrEmpty(knowledge, "unitAliases", id);
        }
    }

    @Test
    public void memoryCasesShouldCarryValidEvidenceTurns() {
        Map<String, JSONObject> memoryCases = new LinkedHashMap<String, JSONObject>();
        for (JSONObject item : eachCase()) {
            String dimension = item.getString("dimension");
            if ("memory".equals(dimension)) {
                memoryCases.put(item.getString("id"), item);
            } else {
                assertFalse(item.getString("id") + " 只有 memory 维度可写 evidenceTurns",
                        item.containsKey("memory"));
            }
        }
        assertEquals("memory 维度应有 6 条", 6, memoryCases.size());

        for (Map.Entry<String, JSONObject> entry : memoryCases.entrySet()) {
            String id = entry.getKey();
            JSONObject item = entry.getValue();
            JSONObject memory = item.getJSONObject("memory");
            assertNotNull(id + " 必须有 memory 对象", memory);
            JSONArray history = item.getJSONArray("history");
            assertNotNull(id + " 必须带 history", history);
            assertFalse(id + " 的 history 不得为空", history.isEmpty());

            Object evidenceValue = memory.get("evidenceTurns");
            assertTrue(id + " 必须有 evidenceTurns 数组", evidenceValue instanceof JSONArray);
            JSONArray evidence = (JSONArray) evidenceValue;
            assertTrue(id + " 的 evidenceTurns 应为 1–3 个", evidence.size() >= 1 && evidence.size() <= 3);
            Set<Integer> unique = new HashSet<Integer>();
            for (int i = 0; i < evidence.size(); i++) {
                Integer turn = evidence.getInteger(i);
                assertNotNull(id + " 的 evidenceTurns 必须是整数", turn);
                assertTrue(id + " 的 evidenceTurns 必须 0 基且非负：" + turn, turn >= 0);
                assertTrue(id + " 的 evidenceTurns 越界：" + turn + " >= " + history.size(), turn < history.size());
                assertTrue(id + " 的 evidenceTurns 不得重复：" + turn, unique.add(turn));
            }
        }

        JSONObject first = memoryCases.get("memory-01");
        assertEquals("memory-01 的 history 应为 3 条", 3, first.getJSONArray("history").size());
        assertEquals("memory-01 的证据轮应为第 0 与第 2 条", Arrays.asList(0, 2),
                toIntegerList(first.getJSONObject("memory").getJSONArray("evidenceTurns")));

        assertHistorySizeBetween(memoryCases, "memory-02", 8, 12);
        assertHistorySizeBetween(memoryCases, "memory-03", 10, 14);
        assertHistorySizeBetween(memoryCases, "memory-04", 36, 40);
        assertHistorySizeBetween(memoryCases, "memory-05", 34, 38);
        assertHistorySizeBetween(memoryCases, "memory-06", 40, 40);
        JSONObject fourth = memoryCases.get("memory-04");
        for (Integer turn : toIntegerList(fourth.getJSONObject("memory").getJSONArray("evidenceTurns"))) {
            assertTrue("memory-04 的证据应在前段：第 " + turn + " 条",
                    turn < fourth.getJSONArray("history").size() / 2);
        }
    }

    @Test
    public void bankShouldContainNoEmoji() {
        List<String> hits = new ArrayList<String>();
        scanStrings(bank, "bank", hits);
        assertTrue("资产不得含 emoji，命中：" + hits, hits.isEmpty());
    }

    private static void assertSoftBoolean(JSONObject item, String softId) {
        String id = item.getString("id");
        Object styleValue = item.get("style");
        assertTrue(id + " 应写 style 覆写", styleValue instanceof JSONObject);
        Object softValue = ((JSONObject) styleValue).get("soft");
        assertTrue(id + " 应写 soft 覆写", softValue instanceof JSONObject);
        JSONObject soft = (JSONObject) softValue;
        assertEquals(id + " 的软项 " + softId + " 应显式为 true", Boolean.TRUE, soft.get(softId));
    }

    private static void assertHistorySizeBetween(Map<String, JSONObject> cases, String id, int min, int max) {
        JSONArray history = cases.get(id).getJSONArray("history");
        assertNotNull(id + " 必须带 history", history);
        int size = history.size();
        assertTrue(id + " 的 history 条数应在 " + min + "–" + max + "，实际 " + size, size >= min && size <= max);
    }

    private static List<Integer> toIntegerList(JSONArray array) {
        List<Integer> values = new ArrayList<Integer>();
        for (int i = 0; i < array.size(); i++) {
            values.add(array.getInteger(i));
        }
        return values;
    }

    private static void assertAbsentOrEmpty(JSONObject object, String key, String id) {
        if (!object.containsKey(key)) {
            return;
        }
        Object value = object.get(key);
        assertTrue(id + " 的 " + key + " 若存在必须是数组", value instanceof JSONArray);
        assertTrue(id + " 的 " + key + " 在 v1 应留空", ((JSONArray) value).isEmpty());
    }

    private static void scanStrings(Object node, String path, List<String> hits) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            for (String key : object.keySet()) {
                scanStrings(object.get(key), path + "." + key, hits);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.size(); i++) {
                scanStrings(array.get(i), path + "[" + i + "]", hits);
            }
        } else if (node instanceof String) {
            String text = (String) node;
            for (int i = 0; i < text.length(); ) {
                int codePoint = text.codePointAt(i);
                if (isEmojiCodePoint(codePoint)) {
                    hits.add(path + " 含 U+" + Integer.toHexString(codePoint).toUpperCase());
                }
                i += Character.charCount(codePoint);
            }
        }
    }

    private static boolean isEmojiCodePoint(int codePoint) {
        return (codePoint >= 0x1F300 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                || codePoint == 0xFE0F;
    }

    private static int countMode(Map<String, JSONObject> cases, String mode) {
        int count = 0;
        for (JSONObject item : cases.values()) {
            if (mode.equals(item.getJSONObject("knowledge").getString("mode"))) {
                count++;
            }
        }
        return count;
    }

    private static List<JSONObject> eachCase() {
        JSONArray cases = bank.getJSONArray("cases");
        List<JSONObject> result = new ArrayList<JSONObject>();
        for (int i = 0; i < cases.size(); i++) {
            result.add(cases.getJSONObject(i));
        }
        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 构建产物 classes 目录：test-classes 的兄弟目录 classes（out/build/classes）。 */
    private static File locateClassesDir() throws Exception {
        File testClasses = new File(EvalBankAssetTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        return new File(testClasses.getParentFile(), "classes");
    }

    /** 仓库根下的角色事实文件；classes 目录向上四级为仓库根。 */
    private static File locatePersonaFile() throws Exception {
        File repoRoot = locateClassesDir().getParentFile().getParentFile().getParentFile().getParentFile();
        return new File(repoRoot, "MBB-Roleplay/src/main/resources/persona-aris.json").getCanonicalFile();
    }

    private static String sha256Hex(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(file.toPath()));
        StringBuilder hex = new StringBuilder();
        for (byte value : hash) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }
}
