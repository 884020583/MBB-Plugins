package org.moboxlab.mbb.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;
import org.moboxlab.mbb.eval.deterministic.EvalDeterministicNormalizer;
import org.moboxlab.mbb.eval.deterministic.EvalKnowledgeResult;
import org.moboxlab.mbb.eval.deterministic.EvalKnowledgeScorer;
import org.moboxlab.mbb.eval.deterministic.EvalStyleResult;
import org.moboxlab.mbb.eval.deterministic.EvalStyleScorer;

/**
 * 确定性判分内核用例（D-1：规格 §6.4、§6.5）。
 *
 * <p>rubric 片段直接内联冻结在用例里（§6.4.5 与 §6.5.4），不读外部文件，
 * 也不依赖 D-2 的 rubric 加载器。断言全部对应 §15.4 的 D12 / D13 / D14。</p>
 */
public class EvalDeterministicTest {

    /** §6.4.5 冻结片段（deterministic.knowledge）。 */
    private static final String RUBRIC_KNOWLEDGE_JSON = "{"
            + "\"normalize\": [\"fullwidth\", \"whitespace\", \"punctuation\", \"lowercase\", \"cn-number\", \"measure-word\"],"
            + "\"match\": \"substring\","
            + "\"choiceRule\": \"correct-hit-and-no-distractor\","
            + "\"pointRule\": \"ratio-with-negative-veto\""
            + "}";

    /** §6.5.4 冻结片段（deterministic.style），逐字照抄规格。 */
    private static final String RUBRIC_STYLE_JSON = "{"
            + "\"hard\": {"
            + "  \"no-markdown\": {\"impl\": \"markdown-residue\", \"params\": {}},"
            + "  \"no-dash\": {\"impl\": \"substring-any\", \"params\": {\"needles\": [\"——\"]}},"
            + "  \"no-cs-tone\": {\"impl\": \"substring-or-regex-any\", \"params\": {"
            + "    \"needles\": [\"收到\", \"记住了\", \"已记录\", \"已经记录\", \"记下了\", \"记账上\", \"没丢\", \"稳了\", \"为您\", \"为你\", \"有什么可以帮您\", \"请问还有什么\", \"还有什么可以帮\", \"能帮到您\"],"
            + "    \"patterns\": [\"不是.{0,24}而是\", \"不仅是.{0,24}更是\", \"与其说.{0,24}不如说\", \"既.{0,20}又.{0,20}还\", \"第一[，,].{0,60}第二[，,].{0,60}第三\", \"其一[，,].{0,60}其二[，,].{0,60}其三\", \"无论.{0,30}还是.{0,30}都\", \"(总的来说|总而言之|归根结底)[。！？!?]?$\"],"
            + "    \"dotall\": false"
            + "  }},"
            + "  \"no-honorific\": {\"impl\": \"substring-any\", \"params\": {\"needles\": [\"您\"]}},"
            + "  \"no-paired-quote\": {\"impl\": \"paired-quote\", \"params\": {\"open\": \"“\", \"close\": \"”\"}}"
            + "},"
            + "\"soft\": {"
            + "  \"length\": {\"impl\": \"char-range\", \"params\": {\"min\": 10, \"max\": 120}},"
            + "  \"self-third-person\": {\"impl\": \"alias-mention\", \"params\": {\"aliases\": [\"爱丽丝\", \"阿丽丝\"]}},"
            + "  \"catchphrase-low-freq\": {\"impl\": \"catchphrase\", \"params\": {\"phrases\": [\"邦邦咔邦\", \"爱丽丝，了解\", \"光呀\", \"这可能是任务\", \"勇者不会放弃伙伴\"], \"maxHits\": 1, \"startOnly\": [\"邦邦咔邦\"]}},"
            + "  \"sentence-length-variance\": {\"impl\": \"sentence-variance\", \"params\": {\"minSentences\": 3, \"minGap\": 3}}"
            + "},"
            + "\"defaultMount\": {\"hard\": [\"no-markdown\", \"no-dash\", \"no-cs-tone\", \"no-honorific\", \"no-paired-quote\"], \"soft\": [\"length\", \"sentence-length-variance\"]},"
            + "\"observed\": [\"emoji-count\"]"
            + "}";

    // ===== 归一化 8 步（§6.4.3） =====

    @Test
    public void normalizerShouldConvertFullwidthToHalfwidth() {
        EvalDeterministicNormalizer normalizer = normalizer("[\"fullwidth\"]");
        assertEquals("全角 ASCII 与全角空格应转半角", "A1 b", normalizer.normalize("Ａ１　ｂ"));
    }

    @Test
    public void normalizerShouldRemoveAllWhitespace() {
        EvalDeterministicNormalizer normalizer = normalizer("[\"whitespace\"]");
        assertEquals("空白应全部删除", "你好世界", normalizer.normalize(" 你 好\t\r\n世 界 "));
    }

    @Test
    public void normalizerShouldRemoveChineseAndEnglishPunctuation() {
        EvalDeterministicNormalizer normalizer = normalizer("[\"punctuation\"]");
        assertEquals("中英文标点应删除", "你好世界",
                normalizer.normalize("你好，世界！（）【】《》〈〉·,.;:!?\"'“”‘’…—～"));
    }

    @Test
    public void normalizerShouldLowercaseEnglishLetters() {
        EvalDeterministicNormalizer normalizer = normalizer("[\"lowercase\"]");
        assertEquals("英文字母应转小写", "abc爱丽丝", normalizer.normalize("AbC爱丽丝"));
    }

    @Test
    public void normalizerShouldConvertChineseNumbers() {
        EvalDeterministicNormalizer normalizer = normalizer("[\"cn-number\"]");
        assertEquals("十二", "12", normalizer.normalize("十二"));
        assertEquals("一百二十", "120", normalizer.normalize("一百二十"));
        assertEquals("一千零二十", "1020", normalizer.normalize("一千零二十"));
        assertEquals("两千零五", "2005", normalizer.normalize("两千零五"));
        assertEquals("九千九百九十九", "9999", normalizer.normalize("九千九百九十九"));
        assertEquals("十", "10", normalizer.normalize("十"));
        assertEquals("零", "0", normalizer.normalize("零"));
        assertEquals("二零二六按位读", "2026", normalizer.normalize("二零二六"));
    }

    @Test
    public void normalizerShouldRemoveMeasureWordsAfterDigits() {
        EvalDeterministicNormalizer normalizer = normalizer("[\"measure-word\"]");
        assertEquals("数字后的量词应删除", "3125", normalizer.normalize("3名12个5岁"));
    }

    @Test
    public void normalizerShouldApplyDeclaredStepsInCanonicalOrder() {
        EvalDeterministicNormalizer normalizer = normalizer(
                "[\"measure-word\", \"cn-number\", \"lowercase\", \"punctuation\", \"whitespace\", \"fullwidth\"]");
        assertEquals("数组乱序也必须按 §6.4.3 的固定顺序执行", "12", normalizer.normalize("十二名"));
    }

    @Test
    public void normalizerShouldApplyAllSixStepsForAliasAndContent() {
        EvalDeterministicNormalizer normalizer = EvalDeterministicNormalizer.fromRubric(rubricKnowledge());
        assertEquals("abc12312", normalizer.normalize("　ＡＢＣ１２３，。！？ 十二名"));
    }

    @Test
    public void matchingShouldSupportUnitAliasesAndSubstring() {
        EvalDeterministicNormalizer normalizer = EvalDeterministicNormalizer.fromRubric(rubricKnowledge());
        assertFalse("未声明等价单位时不做折算", normalizer.matches("1分", "1分钟"));
        JSONArray unitAliases = JSON.parseArray("[[\"分钟\", \"分\"]]");
        assertTrue("unitAliases 应声明等价", normalizer.matches("1分", "1分钟", unitAliases));
        assertTrue("匹配为子串包含", normalizer.matches("用狙击步枪瞄准", "狙击步枪"));
        assertFalse(normalizer.matches("用狙击步枪瞄准", "突击步枪"));
        assertTrue("英文不区分大小写", normalizer.matches("Sniper", "sniper"));
    }

    @Test
    public void unknownNormalizeStepShouldBeRejected() {
        try {
            normalizer("[\"bogus\"]");
            fail("未知归一化步骤应拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bogus"));
        }
    }

    // ===== 知识维（§6.4，D12 / D13） =====

    @Test
    public void choiceShouldScoreOneOnlyWhenCorrectHitsAndNoDistractor() {
        EvalKnowledgeScorer scorer = new EvalKnowledgeScorer(rubricKnowledge());

        EvalKnowledgeResult hit = scorer.score(choiceCase(), "我选狙击步枪。");
        assertEquals("正确项命中且干扰项都不命中记 1", 1.0, hit.caseScore(), 0.0001);
        assertEquals("choice", hit.mode());
        assertEquals(1, hit.expectedTotal());
        assertFalse(hit.negativeHit());
        assertEquals("明细覆盖全部选项，便于复核", 3, hit.hits().size());
        assertEquals("狙击步枪", hit.hits().get(0).point());
        assertTrue(hit.hits().get(0).matched());

        EvalKnowledgeResult distractorOnly = scorer.score(choiceCase(), "突击步枪也可以。");
        assertEquals("只命中干扰项记 0", 0.0, distractorOnly.caseScore(), 0.0001);
        assertFalse(distractorOnly.hits().get(0).matched());
        assertTrue(distractorOnly.hits().get(1).matched());

        EvalKnowledgeResult both = scorer.score(choiceCase(), "狙击步枪比突击步枪强。");
        assertEquals("正确项与干扰项都命中仍记 0", 0.0, both.caseScore(), 0.0001);
        assertTrue(both.hits().get(0).matched());
        assertTrue(both.hits().get(1).matched());
    }

    @Test
    public void pointsShouldScoreHitRatio() {
        EvalKnowledgeScorer scorer = new EvalKnowledgeScorer(rubricKnowledge());

        EvalKnowledgeResult full = scorer.score(pointsCase(), "十二名同学都在操场上。");
        assertEquals("2 个期望点全命中", 1.0, full.caseScore(), 0.0001);
        assertEquals("points", full.mode());
        assertEquals(2, full.expectedTotal());
        assertFalse(full.negativeHit());

        EvalKnowledgeResult half = scorer.score(pointsCase(), "十二名同学来了。");
        assertEquals("命中 1/2 点", 0.5, half.caseScore(), 0.0001);
        assertTrue(half.hits().get(0).matched());
        assertFalse(half.hits().get(1).matched());

        EvalKnowledgeResult none = scorer.score(pointsCase(), "同学们来了。");
        assertEquals("一个点都没命中", 0.0, none.caseScore(), 0.0001);
    }

    @Test
    public void negativePointShouldVetoPointsScore() {
        EvalKnowledgeScorer scorer = new EvalKnowledgeScorer(rubricKnowledge());
        EvalKnowledgeResult result = scorer.score(pointsCase(), "十二名同学在操场上，教室里没人。");
        assertEquals("命中 negative 组直接 0 分，否决优先于命中率", 0.0, result.caseScore(), 0.0001);
        assertTrue(result.negativeHit());
        assertTrue("negative 不改变期望点明细", result.hits().get(0).matched());
    }

    @Test
    public void pointsShouldTreatChineseAndArabicFormsAsSamePoint() {
        EvalKnowledgeScorer scorer = new EvalKnowledgeScorer(rubricKnowledge());
        EvalKnowledgeResult result = scorer.score(pointsCase(), "一共12名同学，在运动场上。");
        assertEquals("「十二名」与「12名」按同一期望点命中", 1.0, result.caseScore(), 0.0001);
        assertTrue(result.hits().get(0).matched());
        assertTrue(result.hits().get(1).matched());
    }

    @Test
    public void knowledgeResultShouldExposeCaseResultFields() {
        EvalKnowledgeScorer scorer = new EvalKnowledgeScorer(rubricKnowledge());
        JSONObject json = scorer.score(choiceCase(), "我选狙击步枪。").toJson();
        assertEquals("choice", json.getString("mode"));
        assertEquals(1, json.getIntValue("expectedTotal"));
        assertFalse(json.getBooleanValue("negativeHit"));
        assertEquals(1.0, json.getDoubleValue("caseScore"), 0.0001);
        assertTrue(json.containsKey("hits"));
        assertEquals(3, json.getJSONArray("hits").size());
        assertTrue(json.getJSONArray("hits").getJSONObject(0).getBooleanValue("matched"));
    }

    // ===== 风格维（§6.5，D14） =====

    @Test
    public void noMarkdownHardItemShouldDetectResidue() {
        EvalStyleScorer scorer = styleScorer();
        EvalStyleResult bold = scorer.score(onlyHard("no-markdown"), "这是**重点**。");
        assertEquals(0.0, bold.caseScore(), 0.0001);
        assertTrue(bold.hardFails().contains("no-markdown"));

        EvalStyleResult list = scorer.score(onlyHard("no-markdown"), "第一行\n- 列表一\n- 列表二");
        assertTrue("行首列表标记达到 2 行即违规", list.hardFails().contains("no-markdown"));

        EvalStyleResult fence = scorer.score(onlyHard("no-markdown"), "```\ncode\n```");
        assertTrue("三反引号代码块标记即违规", fence.hardFails().contains("no-markdown"));

        EvalStyleResult heading = scorer.score(onlyHard("no-markdown"), "# 标题\n正文");
        assertTrue("行首 # 标题即违规", heading.hardFails().contains("no-markdown"));

        EvalStyleResult pass = scorer.score(onlyHard("no-markdown"), "爱丽丝来了。");
        assertEquals("没有 markdown 残留应通过", 1.0, pass.caseScore(), 0.0001);
        assertTrue(pass.hardFails().isEmpty());
    }

    @Test
    public void noDashHardItemShouldDetectDoubleDash() {
        EvalStyleScorer scorer = styleScorer();
        EvalStyleResult fail = scorer.score(onlyHard("no-dash"), "好的——马上到。");
        assertEquals(0.0, fail.caseScore(), 0.0001);
        assertTrue(fail.hardFails().contains("no-dash"));

        EvalStyleResult pass = scorer.score(onlyHard("no-dash"), "好的，马上到。");
        assertEquals(1.0, pass.caseScore(), 0.0001);
    }

    @Test
    public void noCsToneHardItemShouldDetectNeedlesAndPatterns() {
        EvalStyleScorer scorer = styleScorer();
        EvalStyleResult needle = scorer.score(onlyHard("no-cs-tone"), "收到。");
        assertEquals(0.0, needle.caseScore(), 0.0001);
        assertTrue(needle.hardFails().contains("no-cs-tone"));

        EvalStyleResult pattern = scorer.score(onlyHard("no-cs-tone"), "不是这样而是那样。");
        assertTrue("排比正则命中即违规", pattern.hardFails().contains("no-cs-tone"));

        EvalStyleResult pass = scorer.score(onlyHard("no-cs-tone"), "爱丽丝明白了。");
        assertEquals("「明白」不在词表内，不应误伤", 1.0, pass.caseScore(), 0.0001);
    }

    @Test
    public void noHonorificHardItemShouldDetectNin() {
        EvalStyleScorer scorer = styleScorer();
        EvalStyleResult fail = scorer.score(onlyHard("no-honorific"), "您好。");
        assertEquals(0.0, fail.caseScore(), 0.0001);
        assertTrue(fail.hardFails().contains("no-honorific"));

        EvalStyleResult pass = scorer.score(onlyHard("no-honorific"), "你好。");
        assertEquals(1.0, pass.caseScore(), 0.0001);
    }

    @Test
    public void noPairedQuoteHardItemShouldRequireBothQuotes() {
        EvalStyleScorer scorer = styleScorer();
        EvalStyleResult fail = scorer.score(onlyHard("no-paired-quote"), "她说“你好”。");
        assertEquals(0.0, fail.caseScore(), 0.0001);
        assertTrue(fail.hardFails().contains("no-paired-quote"));

        EvalStyleResult pass = scorer.score(onlyHard("no-paired-quote"), "她说“你好。");
        assertEquals("只有开引号不算成对", 1.0, pass.caseScore(), 0.0001);
    }

    @Test
    public void hardFailShouldShortCircuitSoftItems() {
        EvalStyleScorer scorer = styleScorer();
        EvalStyleResult result = scorer.score(null, "您好——");
        assertEquals(0.0, result.caseScore(), 0.0001);
        assertTrue(result.hardFails().contains("no-honorific"));
        assertTrue(result.hardFails().contains("no-dash"));
        assertTrue("硬项违规后不再计算软项", result.soft().isEmpty());
    }

    @Test
    public void lengthSoftShouldRespectBoundaries() {
        EvalStyleScorer scorer = styleScorer();
        JSONObject mount = JSON.parseObject("{\"hard\": [], \"soft\": {\"sentence-length-variance\": false}}");

        assertFalse("9 码点不通过", soft(scorer.score(mount, repeat('a', 9)), "length").passed());
        assertTrue("10 码点通过", soft(scorer.score(mount, repeat('a', 10)), "length").passed());
        assertTrue("120 码点通过", soft(scorer.score(mount, repeat('a', 120)), "length").passed());
        assertFalse("121 码点不通过", soft(scorer.score(mount, repeat('a', 121)), "length").passed());
    }

    @Test
    public void selfThirdPersonSoftShouldRequireAliasMention() {
        EvalStyleScorer scorer = styleScorer();
        JSONObject mount = JSON.parseObject("{"
                + "\"hard\": [],"
                + "\"soft\": {\"length\": false, \"sentence-length-variance\": false,"
                + " \"self-third-person\": true}}");
        assertTrue(soft(scorer.score(mount, "爱丽丝来了。"), "self-third-person").passed());
        assertFalse(soft(scorer.score(mount, "我来了。"), "self-third-person").passed());
    }

    @Test
    public void catchphraseSoftShouldAllowZeroOrOneHitAndRequireSentenceStart() {
        EvalStyleScorer scorer = styleScorer();
        JSONObject mount = JSON.parseObject("{"
                + "\"hard\": [],"
                + "\"soft\": {\"length\": false, \"sentence-length-variance\": false,"
                + " \"catchphrase-low-freq\": true}}");

        assertTrue("零命中也通过", soft(scorer.score(mount, "今天天气不错。"), "catchphrase-low-freq").passed());
        assertTrue("句首 1 次通过",
                soft(scorer.score(mount, "邦邦咔邦！爱丽丝来了。"), "catchphrase-low-freq").passed());
        assertFalse("命中 2 次不通过",
                soft(scorer.score(mount, "邦邦咔邦！邦邦咔邦！"), "catchphrase-low-freq").passed());
        assertFalse("不在句首不通过",
                soft(scorer.score(mount, "今天邦邦咔邦了。"), "catchphrase-low-freq").passed());
    }

    @Test
    public void sentenceVarianceSoftShouldPassWithFewSentences() {
        EvalStyleScorer scorer = styleScorer();
        JSONObject mount = JSON.parseObject("{"
                + "\"hard\": [],"
                + "\"soft\": {\"length\": false, \"sentence-length-variance\": true}}");

        assertTrue("句数 < 3 视为通过", soft(scorer.score(mount, "好。"), "sentence-length-variance").passed());
        assertFalse("句数 3 但长度无差异",
                soft(scorer.score(mount, "好。好。好。"), "sentence-length-variance").passed());
        assertTrue("最长与最短句差 8 通过",
                soft(scorer.score(mount, "来了。今天天气真的很不错呀。我们出发吧。"),
                        "sentence-length-variance").passed());
    }

    @Test
    public void softMountShouldInheritDefaultMount() {
        EvalStyleScorer scorer = styleScorer();
        EvalStyleResult result = scorer.score(null, "来了。今天天气真的很不错呀。我们出发吧。");
        assertEquals("defaultMount 继承两条软项", 2, result.soft().size());
        assertEquals("length", result.soft().get(0).id());
        assertEquals("sentence-length-variance", result.soft().get(1).id());
        assertEquals("默认挂载下正常回复满分", 1.0, result.caseScore(), 0.0001);

        String[] violationContents = {"**粗体**", "好的——", "收到。", "您好。", "她说“你好”。"};
        String[] violationIds = {"no-markdown", "no-dash", "no-cs-tone", "no-honorific", "no-paired-quote"};
        for (int i = 0; i < violationIds.length; i++) {
            EvalStyleResult violating = scorer.score(null, violationContents[i]);
            assertTrue("defaultMount 应挂载 " + violationIds[i],
                    violating.hardFails().contains(violationIds[i]));
        }
    }

    @Test
    public void softMountShouldSupportEnableDisableAndParamOverride() {
        EvalStyleScorer scorer = styleScorer();

        JSONObject enable = JSON.parseObject("{\"soft\": {\"self-third-person\": true}}");
        EvalStyleResult enabled = scorer.score(enable, "爱丽丝来了。");
        assertEquals("true 启用附加软项", 3, enabled.soft().size());
        assertTrue(soft(enabled, "self-third-person").passed());

        JSONObject disable = JSON.parseObject("{\"soft\": {\"length\": false}}");
        EvalStyleResult disabled = scorer.score(disable, "来了。今天天气真的很不错呀。我们出发吧。");
        assertEquals("false 停用后只剩句长方差", 1, disabled.soft().size());
        assertEquals("sentence-length-variance", disabled.soft().get(0).id());

        JSONObject override = JSON.parseObject("{\"soft\": {\"length\": {\"max\": 60}}}");
        EvalStyleResult overridden = scorer.score(override, repeat('a', 70));
        assertFalse("对象覆写 max=60，70 码点不通过", soft(overridden, "length").passed());
        assertEquals("1 条软项通过 / 2 条挂载", 0.5, overridden.caseScore(), 0.0001);
    }

    @Test
    public void hardMountShouldBeReplacedAsWholeGroup() {
        EvalStyleScorer scorer = styleScorer();
        JSONObject mount = JSON.parseObject("{"
                + "\"hard\": [\"no-dash\"],"
                + "\"soft\": {\"length\": false, \"sentence-length-variance\": false}}");

        EvalStyleResult replaced = scorer.score(mount, "您好——");
        assertEquals("硬项数组整组替换", 1, replaced.hardFails().size());
        assertEquals("no-dash", replaced.hardFails().get(0));

        assertEquals("未挂载的 no-honorific 不应判违规", 1.0, scorer.score(mount, "您好").caseScore(), 0.0001);
    }

    @Test
    public void emojiShouldBeCountedButNotScored() {
        EvalStyleScorer scorer = styleScorer();
        JSONObject mount = JSON.parseObject("{"
                + "\"hard\": [],"
                + "\"soft\": {\"length\": false, \"sentence-length-variance\": false}}");

        EvalStyleResult withEmoji = scorer.score(mount, "😀❤️");
        assertEquals("emojiCount 含 0xFE0F 变体选择符", 3, withEmoji.emojiCount());
        assertEquals("emoji 只观测不计分", 1.0, withEmoji.caseScore(), 0.0001);

        assertEquals(0, scorer.score(mount, "没有表情").emojiCount());
    }

    @Test
    public void styleResultShouldExposeCaseResultFields() {
        EvalStyleScorer scorer = styleScorer();
        JSONObject json = scorer.score(null, "来了。今天天气真的很不错呀。我们出发吧。").toJson();
        assertTrue(json.containsKey("hardFails"));
        assertEquals(0, json.getJSONArray("hardFails").size());
        assertTrue(json.containsKey("soft"));
        assertEquals(2, json.getJSONArray("soft").size());
        JSONObject softItem = json.getJSONArray("soft").getJSONObject(0);
        assertEquals("length", softItem.getString("id"));
        assertTrue(softItem.getBooleanValue("passed"));
        assertFalse("detail 必须给出可复核说明", softItem.getString("detail").isEmpty());
        assertEquals(0, json.getIntValue("emojiCount"));
        assertEquals(1.0, json.getDoubleValue("caseScore"), 0.0001);
    }

    @Test
    public void d14ShouldZeroOnHardFailAndUseSoftPassRatio() {
        EvalStyleScorer scorer = styleScorer();

        EvalStyleResult dash = scorer.score(null, "好的——马上就到。");
        assertEquals("含「——」题分 0", 0.0, dash.caseScore(), 0.0001);
        assertTrue(dash.hardFails().contains("no-dash"));

        EvalStyleResult honorific = scorer.score(null, "您好，马上就到。");
        assertEquals("含「您」题分 0", 0.0, honorific.caseScore(), 0.0001);
        assertTrue(honorific.hardFails().contains("no-honorific"));

        EvalStyleResult normal = scorer.score(null, "来了。今天天气真的很不错呀。我们出发吧。");
        assertEquals("正常回复题分 = 软项通过率 2/2", 1.0, normal.caseScore(), 0.0001);
        assertEquals(2, normal.soft().size());
        assertTrue(normal.soft().get(0).passed());
        assertTrue(normal.soft().get(1).passed());
    }

    // ===== 工具 =====



    private static EvalDeterministicNormalizer normalizer(String stepsJson) {
        return new EvalDeterministicNormalizer(JSON.parseArray(stepsJson));
    }

    private static JSONObject rubricKnowledge() {
        return JSON.parseObject(RUBRIC_KNOWLEDGE_JSON);
    }

    private static JSONObject rubricStyle() {
        return JSON.parseObject(RUBRIC_STYLE_JSON);
    }

    private static EvalStyleScorer styleScorer() {
        return new EvalStyleScorer(rubricStyle());
    }

    private static JSONObject onlyHard(String hardId) {
        return JSON.parseObject("{"
                + "\"hard\": [\"" + hardId + "\"],"
                + "\"soft\": {\"length\": false, \"sentence-length-variance\": false}}");
    }

    private static EvalStyleResult.Soft soft(EvalStyleResult result, String id) {
        for (EvalStyleResult.Soft item : result.soft()) {
            if (item.id().equals(id)) {
                return item;
            }
        }
        fail("未挂载软项：" + id);
        return null;
    }

    private static String repeat(char ch, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(ch);
        }
        return builder.toString();
    }

    private static JSONObject choiceCase() {
        return JSON.parseObject("{"
                + "\"mode\": \"choice\","
                + "\"choices\": ["
                + "{\"text\": \"狙击步枪\", \"correct\": true},"
                + "{\"text\": \"突击步枪\", \"correct\": false},"
                + "{\"text\": \"手枪\", \"correct\": false}"
                + "]}");
    }

    private static JSONObject pointsCase() {
        return JSON.parseObject("{"
                + "\"mode\": \"points\","
                + "\"expected\": ["
                + "{\"label\": \"人数\", \"aliases\": [\"十二名\", \"12名\"]},"
                + "{\"label\": \"地点\", \"aliases\": [\"操场\", \"运动场\"]}"
                + "],"
                + "\"negative\": [{\"label\": \"错误\", \"aliases\": [\"教室\"]}]"
                + "}");
    }
}