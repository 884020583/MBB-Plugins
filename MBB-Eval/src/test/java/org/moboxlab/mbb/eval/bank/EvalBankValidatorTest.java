package org.moboxlab.mbb.eval.bank;

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
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;

/**
 * 题库校验器测试（B-3）。
 *
 * <p>合法基线取构建产物 {@code out/build/classes/bank/bank-1.0.0.json}；
 * 每个反例都在深拷贝上改一个字段，断言 {@code code} 与 {@code path}。</p>
 */
public class EvalBankValidatorTest {

    private static final String BANK_FILE_NAME = "bank-1.0.0.json";

    private static final List<String> DIMENSIONS = Arrays.asList(
            "persona", "style", "knowledge", "boundary", "memory");
    private static final List<String> HARD_IDS = Arrays.asList(
            "no-markdown", "no-dash", "no-cs-tone", "no-honorific", "no-paired-quote");
    private static final List<String> SOFT_IDS = Arrays.asList(
            "length", "self-third-person", "catchphrase-low-freq", "sentence-length-variance");

    private static byte[] assetBytes;
    private static JSONObject asset;

    @BeforeClass
    public static void loadBank() throws Exception {
        File file = new File(locateClassesDir(), "bank/" + BANK_FILE_NAME);
        assertTrue("题库资产不存在，请先执行 build.ps1：" + file.getAbsolutePath(), file.isFile());
        assetBytes = Files.readAllBytes(file.toPath());
        asset = parse(new String(assetBytes, StandardCharsets.UTF_8));
        assertNotNull("题库资产解析结果为空", asset);
    }

    @Test
    public void validAssetPassesWithBytesStringAndObject() {
        EvalBankCheckResult bytesResult = EvalBankValidator.validate(assetBytes, catalog());
        assertTrue(displays(bytesResult), bytesResult.ok());
        assertTrue("合法资产不应有告警：" + bytesResult.warnings(), bytesResult.warnings().isEmpty());

        EvalBankCheckResult stringResult = EvalBankValidator.validate(
                new String(assetBytes, StandardCharsets.UTF_8), catalog());
        assertTrue(displays(stringResult), stringResult.ok());

        EvalBankCheckResult objectResult = EvalBankValidator.validate(copyAsset(), catalog());
        assertTrue(displays(objectResult), objectResult.ok());
    }

    @Test
    public void bomAndNoBomProduceSameResult() {
        byte[] withBom = new byte[assetBytes.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(assetBytes, 0, withBom, 3, assetBytes.length);

        EvalBankCheckResult result = EvalBankValidator.validate(withBom, catalog());
        assertTrue(displays(result), result.ok());
        assertEquals("BOM 与无 BOM 应得到相同问题数", 0, result.problems().size());
        assertEquals("BOM 与无 BOM 应得到相同告警", 0, result.warnings().size());
        assertEquals("BOM 与无 BOM 的 bank_hash 应一致",
                EvalBankValidator.bankHash(assetBytes), EvalBankValidator.bankHash(withBom));
    }

    @Test
    public void schemaVersionMustBeIntegerOne() {
        JSONObject bank = copyAsset();
        bank.put("schemaVersion", 2);
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.SCHEMA_VERSION, "schemaVersion");

        bank = copyAsset();
        bank.put("schemaVersion", "1");
        EvalBankCheckResult stringVersion = EvalBankValidator.validate(bank, catalog());
        assertProblem(stringVersion, EvalBankProblem.SCHEMA_VERSION, "schemaVersion");
        for (EvalBankProblem problem : stringVersion.problems()) {
            assertFalse("schemaVersion 不合法时不得进入用例级校验：" + problem.display(),
                    problem.path().startsWith("cases["));
        }

        bank = copyAsset();
        bank.remove("schemaVersion");
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.SCHEMA_VERSION, "schemaVersion");
    }

    @Test
    public void topLevelFieldsReportFieldErrorsAndStopBeforeCases() {
        JSONObject bank = copyAsset();
        bank.remove("cases");
        EvalBankCheckResult missingCases = EvalBankValidator.validate(bank, catalog());
        assertProblem(missingCases, EvalBankProblem.FIELD, "cases");
        assertEquals("删 cases 只应报一个顶层问题", 1, missingCases.problems().size());

        bank = copyAsset();
        bank.remove("persona");
        EvalBankCheckResult missingPersona = EvalBankValidator.validate(bank, catalog());
        assertProblem(missingPersona, EvalBankProblem.FIELD, "persona");
        assertEquals("删 persona 只应报一个顶层问题", 1, missingPersona.problems().size());

        bank = copyAsset();
        bank.put("version", Integer.valueOf(1));
        EvalBankCheckResult badVersion = EvalBankValidator.validate(bank, catalog());
        assertProblem(badVersion, EvalBankProblem.FIELD, "version");
        for (EvalBankProblem problem : badVersion.problems()) {
            assertFalse("顶层类型错误时不得进入用例级校验：" + problem.display(),
                    problem.path().startsWith("cases["));
        }

        bank = copyAsset();
        bank.put("cases", new JSONObject());
        EvalBankCheckResult objectCases = EvalBankValidator.validate(bank, catalog());
        assertProblem(objectCases, EvalBankProblem.FIELD, "cases");
        assertEquals("cases 为对象只应报一个顶层问题", 1, objectCases.problems().size());
    }

    @Test
    public void versionMustBeSemverAndMatchFileName() {
        JSONObject bank = copyAsset();
        bank.put("version", "1.0");
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.VERSION, "version");

        bank = copyAsset();
        bank.put("version", "1.0.1");
        EvalBankCheckResult fileMismatch = EvalBankValidator.validate(bank, catalog());
        EvalBankProblem problem = assertProblem(fileMismatch, EvalBankProblem.VERSION, "version");
        assertTrue("消息应指出文件名差异：" + problem.message(),
                problem.message().contains(BANK_FILE_NAME));

        bank = copyAsset();
        EvalBankCheckResult wrongFileName = EvalBankValidator.validate(bank,
                EvalBankValidator.Options.defaults()
                        .expectedFileName("bank-1.0.1.json")
                        .dimensionIds(DIMENSIONS)
                        .styleCatalog(HARD_IDS, SOFT_IDS));
        assertProblem(wrongFileName, EvalBankProblem.VERSION, "version");

        assertTrue("不传文件名时跳过文件名一致性校验",
                EvalBankValidator.validate(copyAsset()).ok());
    }

    @Test
    public void metaOnlyAllowsTitleAndDescription() {
        JSONObject bank = copyAsset();
        bank.getJSONObject("meta").put("notes", "多余字段");
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.FIELD, "meta.notes");

        bank = copyAsset();
        bank.put("meta", "文本");
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.FIELD, "meta");

        bank = copyAsset();
        bank.getJSONObject("meta").put("title", Integer.valueOf(1));
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.FIELD, "meta.title");
    }

    @Test
    public void caseIdFormatPrefixAndUniqueness() {
        JSONObject bank = copyAsset();
        findCase(bank, "persona-01").put("id", "persona-1");
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.ID, "cases[0].id");

        bank = copyAsset();
        int personaIndex = indexOfCase(bank, "persona-01");
        JSONObject boundaryCase = findCase(bank, "persona-01");
        boundaryCase.put("dimension", "boundary");
        EvalBankProblem prefixProblem = assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.ID, "cases[" + personaIndex + "].id");
        assertTrue("消息应指出前缀与 dimension 不一致：" + prefixProblem.message(),
                prefixProblem.message().contains("dimension"));

        bank = copyAsset();
        int secondIndex = indexOfCase(bank, "persona-02");
        findCase(bank, "persona-02").put("id", "persona-01");
        EvalBankProblem duplicate = assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.ID, "cases[" + secondIndex + "].id");
        assertTrue("重复 id 消息应给出首次出现的下标：" + duplicate.message(),
                duplicate.message().contains("cases[0]"));
    }

    @Test
    public void dimensionMustBeInFiveAndRubricTable() {
        JSONObject bank = copyAsset();
        int index = indexOfCase(bank, "persona-01");
        findCase(bank, "persona-01").put("dimension", "tone");
        EvalBankCheckResult unknown = EvalBankValidator.validate(bank, catalog());
        assertProblem(unknown, EvalBankProblem.DIMENSION, "cases[" + index + "].dimension");
        assertEquals("未知 dimension 只应报一个维度问题", 1, unknown.problems().size());

        bank = copyAsset();
        EvalBankValidator.Options withoutPersona = EvalBankValidator.Options.defaults()
                .expectedFileName(BANK_FILE_NAME)
                .dimensionIds(Arrays.asList("style", "knowledge", "boundary", "memory"))
                .styleCatalog(HARD_IDS, SOFT_IDS);
        EvalBankCheckResult missingInRubric = EvalBankValidator.validate(bank, withoutPersona);
        assertProblem(missingInRubric, EvalBankProblem.DIMENSION, "cases[0].dimension");
    }

    @Test
    public void tierMustBeSmokeOrFull() {
        JSONObject bank = copyAsset();
        int index = indexOfCase(bank, "persona-01");
        findCase(bank, "persona-01").put("tier", "quick");
        EvalBankCheckResult result = EvalBankValidator.validate(bank, catalog());
        assertProblem(result, EvalBankProblem.TIER, "cases[" + index + "].tier");
        assertEquals("非法 tier 只应报一个用例问题", 1, result.problems().size());
    }

    @Test
    public void focusAndReferenceAreRequiredByDimension() {
        JSONObject bank = copyAsset();
        int personaIndex = indexOfCase(bank, "persona-01");
        findCase(bank, "persona-01").remove("focus");
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.FIELD, "cases[" + personaIndex + "].focus");

        bank = copyAsset();
        findCase(bank, "persona-01").put("focus", "  ");
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.FIELD, "cases[" + personaIndex + "].focus");

        bank = copyAsset();
        findCase(bank, "style-01").put("focus", "");
        EvalBankCheckResult blankStyleFocus = EvalBankValidator.validate(bank, catalog());
        assertTrue("style 维度的 focus 空串按缺失处理，应通过：" + displays(blankStyleFocus),
                blankStyleFocus.ok());

        bank = copyAsset();
        findCase(bank, "style-01").put("reference", "");
        EvalBankCheckResult blankStyleReference = EvalBankValidator.validate(bank, catalog());
        assertTrue("非知识题的 reference 空串按缺失处理，应通过：" + displays(blankStyleReference),
                blankStyleReference.ok());

        bank = copyAsset();
        int knowledgeIndex = indexOfCase(bank, "knowledge-01");
        findCase(bank, "knowledge-01").remove("reference");
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.FIELD, "cases[" + knowledgeIndex + "].reference");

        bank = copyAsset();
        findCase(bank, "knowledge-01").remove("knowledge");
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.FIELD, "cases[" + knowledgeIndex + "].knowledge");
    }

    @Test
    public void casesCountLimits() {
        JSONObject bank = copyAsset();
        JSONArray cases = bank.getJSONArray("cases");
        JSONObject extra = JSON.parseObject(JSON.toJSONString(findCase(bank, "persona-01")),
                Feature.OrderedField);
        extra.put("id", "persona-13");
        cases.add(extra);
        EvalBankCheckResult tooMany = EvalBankValidator.validate(bank, catalog());
        EvalBankProblem problem = assertProblem(tooMany, EvalBankProblem.COVERAGE, "cases");
        assertTrue("消息应指出 40 条上限：" + problem.message(), problem.message().contains("40"));

        bank = copyAsset();
        bank.put("cases", new JSONArray());
        EvalBankCheckResult empty = EvalBankValidator.validate(bank, catalog());
        assertProblem(empty, EvalBankProblem.COVERAGE, "cases");
    }

    @Test
    public void dimensionCountsAndRatios() {
        JSONObject bank = copyAsset();
        removeCase(bank, "style-04");
        EvalBankCheckResult styleShort = EvalBankValidator.validate(bank, catalog());
        EvalBankProblem styleProblem = assertProblem(styleShort, EvalBankProblem.COVERAGE, "cases");
        assertTrue("消息应指出 style 不足 8 条：" + styleProblem.message(),
                styleProblem.message().contains("style"));
        assertEquals("删一条非 smoke 的 style 只应报一个配比问题", 1, styleShort.problems().size());

        bank = copyAsset();
        removeCase(bank, "knowledge-03");
        EvalBankCheckResult knowledgeShort = EvalBankValidator.validate(bank, catalog());
        EvalBankProblem knowledgeProblem = assertProblem(knowledgeShort,
                EvalBankProblem.COVERAGE, "cases");
        assertTrue("消息应指出 knowledge 不足 8 条：" + knowledgeProblem.message(),
                knowledgeProblem.message().contains("knowledge"));

        bank = copyAsset();
        removeCase(bank, "boundary-01");
        removeCase(bank, "boundary-02");
        removeCase(bank, "boundary-03");
        removeCase(bank, "boundary-04");
        removeCase(bank, "boundary-05");
        removeCase(bank, "boundary-06");
        EvalBankCheckResult noBoundary = EvalBankValidator.validate(bank, catalog());
        assertTrue("缺少 boundary 维度应报 COVERAGE：" + displays(noBoundary),
                hasMessage(noBoundary, "缺少维度 boundary"));
    }

    @Test
    public void smokeSubsetMustCoverFiveDimensionsAndTwoKnowledgeModes() {
        JSONObject bank = copyAsset();
        findCase(bank, "boundary-01").put("tier", "full");
        findCase(bank, "boundary-05").put("tier", "full");
        EvalBankCheckResult tenSmoke = EvalBankValidator.validate(bank, catalog());
        EvalBankProblem countProblem = assertProblem(tenSmoke, EvalBankProblem.COVERAGE, "cases");
        assertTrue("消息应含 smoke 与 12 条：" + countProblem.message(),
                countProblem.message().contains("smoke") && countProblem.message().contains("12"));

        bank = copyAsset();
        for (Object item : bank.getJSONArray("cases")) {
            ((JSONObject) item).put("tier", "full");
        }
        EvalBankCheckResult noSmoke = EvalBankValidator.validate(bank, catalog());
        assertProblem(noSmoke, EvalBankProblem.COVERAGE, "cases");

        bank = copyAsset();
        findCase(bank, "boundary-01").put("tier", "full");
        findCase(bank, "boundary-05").put("tier", "full");
        findCase(bank, "style-02").put("tier", "smoke");
        findCase(bank, "style-03").put("tier", "smoke");
        EvalBankCheckResult missingSmokeDimension = EvalBankValidator.validate(bank, catalog());
        assertTrue("smoke 未覆盖 boundary 应报 COVERAGE：" + displays(missingSmokeDimension),
                hasMessage(missingSmokeDimension, "缺少维度 boundary"));
    }

    @Test
    public void styleMountsAreCheckedAgainstRubricCatalog() {
        JSONObject bank = copyAsset();
        int styleIndex = indexOfCase(bank, "style-04");
        JSONObject styleCase = findCase(bank, "style-04");
        JSONArray hard = new JSONArray();
        hard.add("no-emoji");
        styleCase.getJSONObject("style").put("hard", hard);
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.STYLE_MOUNT, "cases[" + styleIndex + "].style.hard[0]");

        bank = copyAsset();
        findCase(bank, "style-04").getJSONObject("style").getJSONObject("soft")
                .put("emoji-budget", Boolean.TRUE);
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.STYLE_MOUNT,
                "cases[" + styleIndex + "].style.soft.emoji-budget");

        bank = copyAsset();
        int lengthIndex = indexOfCase(bank, "style-08");
        findCase(bank, "style-08").getJSONObject("style").getJSONObject("soft")
                .put("length", "false");
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.STYLE_MOUNT, "cases[" + lengthIndex + "].style.soft.length");

        bank = copyAsset();
        findCase(bank, "style-08").getJSONObject("style").getJSONObject("soft")
                .getJSONObject("length").put("min", Integer.valueOf(200));
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.STYLE_MOUNT, "cases[" + lengthIndex + "].style.soft.length");

        bank = copyAsset();
        styleCase = findCase(bank, "style-04");
        hard = new JSONArray();
        hard.add("no-emoji");
        styleCase.getJSONObject("style").put("hard", hard);
        assertTrue("未提供 rubric 风格目录时跳过成员校验",
                EvalBankValidator.validate(bank).ok());
    }

    @Test
    public void knowledgeShapeAndMutualExclusion() {
        JSONObject bank = copyAsset();
        int pointsIndex = indexOfCase(bank, "knowledge-05");
        findCase(bank, "knowledge-05").getJSONObject("knowledge")
                .put("choices", new JSONArray());
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.KNOWLEDGE, "cases[" + pointsIndex + "].knowledge.choices");

        bank = copyAsset();
        int choiceIndex = indexOfCase(bank, "knowledge-01");
        JSONObject choiceCase = findCase(bank, "knowledge-01");
        JSONArray choices = choiceCase.getJSONObject("knowledge").getJSONArray("choices");
        int correctIndex = -1;
        for (int i = 0; i < choices.size(); i++) {
            if (Boolean.TRUE.equals(choices.getJSONObject(i).getBoolean("correct"))) {
                correctIndex = i;
            }
        }
        assertTrue("资产 choice 题应有正确项", correctIndex >= 0);
        int otherIndex = correctIndex == 0 ? 1 : 0;
        choices.getJSONObject(otherIndex).put("correct", Boolean.TRUE);
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.KNOWLEDGE, "cases[" + choiceIndex + "].knowledge.choices");

        bank = copyAsset();
        choiceCase = findCase(bank, "knowledge-01");
        choices = choiceCase.getJSONObject("knowledge").getJSONArray("choices");
        int distractorIndex = -1;
        correctIndex = -1;
        for (int i = 0; i < choices.size(); i++) {
            if (Boolean.TRUE.equals(choices.getJSONObject(i).getBoolean("correct"))) {
                correctIndex = i;
            } else if (distractorIndex < 0) {
                distractorIndex = i;
            }
        }
        JSONArray oneDistractor = new JSONArray();
        oneDistractor.add(choices.getJSONObject(correctIndex));
        oneDistractor.add(choices.getJSONObject(distractorIndex));
        choiceCase.getJSONObject("knowledge").put("choices", oneDistractor);
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.KNOWLEDGE, "cases[" + choiceIndex + "].knowledge.choices");

        bank = copyAsset();
        choiceCase = findCase(bank, "knowledge-01");
        choices = choiceCase.getJSONObject("knowledge").getJSONArray("choices");
        String correctText = null;
        for (int i = 0; i < choices.size(); i++) {
            if (Boolean.TRUE.equals(choices.getJSONObject(i).getBoolean("correct"))) {
                correctText = choices.getJSONObject(i).getString("text");
            }
        }
        for (int i = 0; i < choices.size(); i++) {
            if (!Boolean.TRUE.equals(choices.getJSONObject(i).getBoolean("correct"))) {
                choices.getJSONObject(i).put("text", correctText + "并且");
                break;
            }
        }
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.KNOWLEDGE, "cases[" + choiceIndex + "].knowledge.choices");

        bank = copyAsset();
        findCase(bank, "knowledge-05").getJSONObject("knowledge")
                .getJSONArray("expected").getJSONObject(0).put("aliases", new JSONArray());
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.KNOWLEDGE,
                "cases[" + pointsIndex + "].knowledge.expected[0].aliases");

        bank = copyAsset();
        findCase(bank, "knowledge-01").getJSONObject("knowledge").put("mode", "other");
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.KNOWLEDGE, "cases[" + choiceIndex + "].knowledge.mode");
    }

    @Test
    public void memoryEvidenceTurnsStructure() {
        JSONObject bank = copyAsset();
        int memoryIndex = indexOfCase(bank, "memory-01");
        JSONObject knowledgeCase = findCase(bank, "memory-01");
        knowledgeCase.getJSONObject("memory").put("evidenceTurns", stringArray("0"));
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.MEMORY, "cases[" + memoryIndex + "].memory.evidenceTurns[0]");

        bank = copyAsset();
        JSONArray fourTurns = new JSONArray();
        fourTurns.add(Integer.valueOf(0));
        fourTurns.add(Integer.valueOf(1));
        fourTurns.add(Integer.valueOf(2));
        fourTurns.add(Integer.valueOf(3));
        findCase(bank, "memory-01").getJSONObject("memory").put("evidenceTurns", fourTurns);
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.MEMORY, "cases[" + memoryIndex + "].memory.evidenceTurns");

        bank = copyAsset();
        JSONArray negative = new JSONArray();
        negative.add(Integer.valueOf(-1));
        findCase(bank, "memory-01").getJSONObject("memory").put("evidenceTurns", negative);
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.MEMORY, "cases[" + memoryIndex + "].memory.evidenceTurns[0]");

        bank = copyAsset();
        findCase(bank, "memory-01").remove("history");
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.FIELD, "cases[" + memoryIndex + "].history");
    }

    @Test
    public void evidenceTurnsOutOfRangeOnlyWarns() {
        JSONObject bank = copyAsset();
        JSONArray turns = new JSONArray();
        turns.add(Integer.valueOf(99));
        findCase(bank, "memory-01").getJSONObject("memory").put("evidenceTurns", turns);
        EvalBankCheckResult result = EvalBankValidator.validate(bank, catalog());
        assertTrue("evidenceTurns 越界不应拒绝加载：" + displays(result), result.ok());
        assertFalse("应给出运行期 invalid 告警", result.warnings().isEmpty());
        boolean hasWarning = false;
        for (String warning : result.warnings()) {
            if (warning.contains("evidenceTurns") && warning.contains("invalid")) {
                hasWarning = true;
            }
        }
        assertTrue("告警应提到 evidenceTurns 与 invalid：" + result.warnings(), hasWarning);
    }

    @Test
    public void historyCountAndCharacterLimits() {
        JSONObject bank = copyAsset();
        int memoryIndex = indexOfCase(bank, "memory-01");
        JSONObject memoryCase = findCase(bank, "memory-01");
        JSONArray history = memoryCase.getJSONArray("history");
        JSONObject turn = JSON.parseObject(JSON.toJSONString(history.getJSONObject(0)),
                Feature.OrderedField);
        while (history.size() < 41) {
            history.add(JSON.parseObject(JSON.toJSONString(turn), Feature.OrderedField));
        }
        EvalBankCheckResult tooManyTurns = EvalBankValidator.validate(bank, catalog());
        EvalBankProblem problem = assertProblem(tooManyTurns,
                EvalBankProblem.FIELD, "cases[" + memoryIndex + "].history");
        assertTrue("消息应指出 40 条上限：" + problem.message(), problem.message().contains("40"));

        bank = copyAsset();
        memoryCase = findCase(bank, "memory-01");
        JSONArray longHistory = new JSONArray();
        longHistory.add(turnWithText(repeat('a', 16001)));
        longHistory.add(turnWithText(repeat('b', 16001)));
        memoryCase.put("history", longHistory);
        EvalBankCheckResult tooManyChars = EvalBankValidator.validate(bank, catalog());
        problem = assertProblem(tooManyChars, EvalBankProblem.FIELD,
                "cases[" + memoryIndex + "].history");
        assertTrue("消息应指出 30000 字符上限：" + problem.message(),
                problem.message().contains("30000"));
    }

    @Test
    public void sceneEnumsAreValidated() {
        JSONObject bank = copyAsset();
        int memoryIndex = indexOfCase(bank, "memory-01");
        findCase(bank, "memory-01").getJSONArray("history").getJSONObject(0)
                .put("speaker", "narrator");
        assertProblem(EvalBankValidator.validate(bank, catalog()), EvalBankProblem.FIELD,
                "cases[" + memoryIndex + "].history[0].speaker");

        bank = copyAsset();
        findCase(bank, "memory-01").put("addressed", "broadcast");
        assertProblem(EvalBankValidator.validate(bank, catalog()), EvalBankProblem.FIELD,
                "cases[" + memoryIndex + "].addressed");

        bank = copyAsset();
        findCase(bank, "memory-01").put("relationship", "stranger");
        assertProblem(EvalBankValidator.validate(bank, catalog()), EvalBankProblem.FIELD,
                "cases[" + memoryIndex + "].relationship");
    }

    @Test
    public void historyUserIDOnlyWarns() {
        JSONObject bank = copyAsset();
        findCase(bank, "memory-01").getJSONArray("history").getJSONObject(0)
                .put("userID", "10001");
        EvalBankCheckResult result = EvalBankValidator.validate(bank, catalog());
        assertTrue("history 写 userID 只告警：" + displays(result), result.ok());
        boolean warned = false;
        for (String warning : result.warnings()) {
            if (warning.contains("userID")) {
                warned = true;
            }
        }
        assertTrue("告警应提到 userID：" + result.warnings(), warned);
    }

    @Test
    public void personaDeclarationsAreValidated() {
        JSONObject bank = copyAsset();
        bank.getJSONObject("persona").put("summary", repeat('人', 401));
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.PERSONA, "persona.summary");

        bank = copyAsset();
        bank.getJSONObject("persona").remove("file");
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.PERSONA, "persona.file");

        bank = copyAsset();
        bank.getJSONObject("persona").put("personaHash", "1234567890");
        assertProblem(EvalBankValidator.validate(bank, catalog()),
                EvalBankProblem.PERSONA, "persona.personaHash");

        bank = copyAsset();
        bank.getJSONObject("persona").put("personaHash", "abcdef123456");
        assertTrue("合法 12 位小写十六进制 personaHash 应通过",
                EvalBankValidator.validate(bank, catalog()).ok());
    }

    @Test
    public void declaredHashMismatchReportsBothValues() {
        String text = new String(assetBytes, StandardCharsets.UTF_8);
        String withHash = text.replaceFirst("\\{", "{\"hash\":\"000000000000\",");
        byte[] withHashBytes = withHash.getBytes(StandardCharsets.UTF_8);
        EvalBankCheckResult result = EvalBankValidator.validate(withHash, catalog());
        EvalBankProblem problem = assertProblem(result, EvalBankProblem.HASH, "hash");
        String actual = EvalBankValidator.bankHash(withHashBytes);
        assertTrue("消息应给出声明值：" + problem.message(),
                problem.message().contains("000000000000"));
        assertTrue("消息应给出实际值：" + problem.message(), problem.message().contains(actual));

        EvalBankCheckResult objectResult = EvalBankValidator.validate(parse(withHash), catalog());
        assertTrue("JSONObject 入参无原始字节，应跳过声明 hash 校验：" + displays(objectResult),
                objectResult.ok());
        assertFalse("应给出跳过声明 hash 的告警", objectResult.warnings().isEmpty());
    }

    @Test
    public void unknownFieldsOnlyWarn() {
        JSONObject bank = copyAsset();
        bank.put("notes", "顶层备注");
        JSONObject personaCase = findCase(bank, "persona-01");
        personaCase.put("weight", Integer.valueOf(1));
        personaCase.put("runId", "r-1");
        EvalBankCheckResult result = EvalBankValidator.validate(bank, catalog());
        assertTrue("未知字段不应导致失败：" + displays(result), result.ok());
        assertTrue("告警应提到 notes", hasWarning(result, "notes"));
        assertTrue("告警应提到 weight", hasWarning(result, "weight"));
        assertTrue("告警应提到 runId", hasWarning(result, "runId"));
    }

    @Test
    public void errorOrderIsStableAndCappedAtFive() {
        JSONObject bank = copyAsset();
        findCase(bank, "persona-01").put("id", "persona-1");
        findCase(bank, "persona-02").put("id", "persona-2");
        findCase(bank, "persona-03").put("id", "persona-3");
        findCase(bank, "persona-04").put("id", "persona-4");
        findCase(bank, "persona-05").put("tier", "quick");
        findCase(bank, "style-01").put("tier", "quick");
        findCase(bank, "knowledge-05").getJSONObject("knowledge")
                .put("choices", new JSONArray());
        EvalBankCheckResult first = EvalBankValidator.validate(bank, catalog());
        assertEquals("七个错误应全部收集", 7, first.problems().size());
        assertEquals("只回显前 5 条", 5, first.visibleProblems().size());
        assertEquals("其余 2 条只计数", 2, first.suppressedCount());

        int previous = -1;
        for (EvalBankProblem problem : first.visibleProblems()) {
            int index = caseIndexOf(problem.path());
            assertTrue("应按下标升序：" + problem.path(), index >= previous);
            previous = index;
        }
        List<String> firstDisplays = displaysOf(first);

        EvalBankCheckResult second = EvalBankValidator.validate(bank, catalog());
        assertEquals("两次校验的排序必须一致", firstDisplays, displaysOf(second));
    }

    @Test
    public void sameCaseFieldsFollowSchemaOrder() {
        JSONObject bank = copyAsset();
        findCase(bank, "persona-01").put("tier", "quick");
        findCase(bank, "persona-01").put("id", "persona-1");
        EvalBankCheckResult result = EvalBankValidator.validate(bank, catalog());
        assertEquals(2, result.problems().size());
        assertEquals(EvalBankProblem.ID, result.problems().get(0).code());
        assertEquals("cases[0].id", result.problems().get(0).path());
        assertEquals(EvalBankProblem.TIER, result.problems().get(1).code());
        assertEquals("cases[0].tier", result.problems().get(1).path());
    }

    @Test
    public void sizeLimitAndExactLimit() {
        byte[] huge = new byte[EvalBankValidator.MAX_BYTES + 1];
        EvalBankCheckResult oversized = EvalBankValidator.validate(huge);
        assertProblem(oversized, EvalBankProblem.SIZE, "");
        assertEquals("超限时只报 SIZE，不解析内容", 1, oversized.problems().size());

        byte[] exact = new byte[EvalBankValidator.MAX_BYTES];
        System.arraycopy(assetBytes, 0, exact, 0, assetBytes.length);
        for (int i = assetBytes.length; i < exact.length; i++) {
            exact[i] = ' ';
        }
        assertTrue("恰好 512 KB 应通过大小校验", EvalBankValidator.validate(exact, catalog()).ok());
    }

    @Test
    public void jsonAndEncodingErrors() {
        assertProblem(EvalBankValidator.validate("{"), EvalBankProblem.JSON, "");
        assertProblem(EvalBankValidator.validate("[]"), EvalBankProblem.JSON, "");
        assertProblem(EvalBankValidator.validate("null"), EvalBankProblem.JSON, "");
        byte[] invalidUtf8 = new byte[] {(byte) 0xFF, (byte) 0xFE, (byte) 0x00};
        assertProblem(EvalBankValidator.validate(invalidUtf8), EvalBankProblem.JSON, "");
        assertProblem(EvalBankValidator.validate((byte[]) null), EvalBankProblem.READ, "");
    }

    @Test
    public void codesAreClosedSet() {
        assertEquals("§5.7.3 冻结 15 个错误码", 15, EvalBankProblem.CODES.size());
        assertTrue(EvalBankProblem.CODES.contains(EvalBankProblem.READ));
        assertTrue(EvalBankProblem.CODES.contains(EvalBankProblem.JSON));
        assertTrue(EvalBankProblem.CODES.contains(EvalBankProblem.SIZE));
        assertTrue(EvalBankProblem.CODES.contains(EvalBankProblem.SCHEMA_VERSION));
        assertTrue(EvalBankProblem.CODES.contains(EvalBankProblem.VERSION));
        assertTrue(EvalBankProblem.CODES.contains(EvalBankProblem.FIELD));
        assertTrue(EvalBankProblem.CODES.contains(EvalBankProblem.ID));
        assertTrue(EvalBankProblem.CODES.contains(EvalBankProblem.DIMENSION));
        assertTrue(EvalBankProblem.CODES.contains(EvalBankProblem.COVERAGE));
        assertTrue(EvalBankProblem.CODES.contains(EvalBankProblem.STYLE_MOUNT));
        assertTrue(EvalBankProblem.CODES.contains(EvalBankProblem.KNOWLEDGE));
        assertTrue(EvalBankProblem.CODES.contains(EvalBankProblem.MEMORY));
        assertTrue(EvalBankProblem.CODES.contains(EvalBankProblem.TIER));
        assertTrue(EvalBankProblem.CODES.contains(EvalBankProblem.PERSONA));
        assertTrue(EvalBankProblem.CODES.contains(EvalBankProblem.HASH));
        try {
            new EvalBankProblem("E_BANK_UNKNOWN", "", "未知码");
            fail("未知错误码应被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("E_BANK_UNKNOWN"));
        }
    }

    @Test
    public void bankHashMatchesIndependentSha256() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(assetBytes);
        StringBuilder hex = new StringBuilder();
        for (byte value : hash) {
            hex.append(String.format("%02x", value));
        }
        assertEquals(hex.substring(0, 12), EvalBankValidator.bankHash(assetBytes));
    }

    private static EvalBankValidator.Options catalog() {
        return EvalBankValidator.Options.defaults()
                .expectedFileName(BANK_FILE_NAME)
                .dimensionIds(DIMENSIONS)
                .styleCatalog(HARD_IDS, SOFT_IDS);
    }

    private static JSONObject copyAsset() {
        return JSON.parseObject(JSON.toJSONString(asset), Feature.OrderedField);
    }

    private static JSONObject parse(String text) {
        return JSON.parseObject(text, Feature.OrderedField);
    }

    private static JSONObject findCase(JSONObject bank, String id) {
        JSONArray cases = bank.getJSONArray("cases");
        for (int i = 0; i < cases.size(); i++) {
            JSONObject item = cases.getJSONObject(i);
            if (id.equals(item.getString("id"))) {
                return item;
            }
        }
        throw new AssertionError("题库资产中找不到用例：" + id);
    }

    private static int indexOfCase(JSONObject bank, String id) {
        JSONArray cases = bank.getJSONArray("cases");
        for (int i = 0; i < cases.size(); i++) {
            if (id.equals(cases.getJSONObject(i).getString("id"))) {
                return i;
            }
        }
        throw new AssertionError("题库资产中找不到用例：" + id);
    }

    private static void removeCase(JSONObject bank, String id) {
        int index = indexOfCase(bank, id);
        bank.getJSONArray("cases").remove(index);
    }

    private static JSONArray stringArray(String value) {
        JSONArray array = new JSONArray();
        array.add(value);
        return array;
    }

    private static JSONObject turnWithText(String text) {
        JSONObject turn = new JSONObject(true);
        turn.put("speaker", "self");
        turn.put("userName", "老师");
        turn.put("text", text);
        return turn;
    }

    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            text.append(value);
        }
        return text.toString();
    }

    private static EvalBankProblem assertProblem(EvalBankCheckResult result, String code,
            String path) {
        assertFalse("校验应失败（期望 " + code + " " + path + "）：" + displays(result),
                result.ok());
        for (EvalBankProblem problem : result.problems()) {
            if (problem.code().equals(code) && problem.path().equals(path)) {
                return problem;
            }
        }
        fail("未找到 " + code + " " + path + "，实际问题：\n" + displays(result));
        return null;
    }

    private static boolean hasMessage(EvalBankCheckResult result, String fragment) {
        for (EvalBankProblem problem : result.problems()) {
            if (problem.message().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWarning(EvalBankCheckResult result, String fragment) {
        for (String warning : result.warnings()) {
            if (warning.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String displays(EvalBankCheckResult result) {
        StringBuilder text = new StringBuilder();
        for (EvalBankProblem problem : result.problems()) {
            text.append("\n  ").append(problem.display());
        }
        for (String warning : result.warnings()) {
            text.append("\n  [告警] ").append(warning);
        }
        return text.toString();
    }

    private static List<String> displaysOf(EvalBankCheckResult result) {
        List<String> displays = new ArrayList<String>();
        for (EvalBankProblem problem : result.problems()) {
            displays.add(problem.display());
        }
        return displays;
    }

    private static int caseIndexOf(String path) {
        int open = path.indexOf("cases[");
        if (open < 0) {
            return -1;
        }
        int close = path.indexOf(']', open);
        if (close < 0) {
            return -1;
        }
        return Integer.parseInt(path.substring(open + 6, close));
    }

    private static File locateClassesDir() throws Exception {
        File testClasses = new File(EvalBankValidatorTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        return new File(testClasses.getParentFile(), "classes");
    }
}