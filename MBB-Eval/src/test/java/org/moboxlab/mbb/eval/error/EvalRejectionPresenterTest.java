package org.moboxlab.mbb.eval.error;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.TestEvalAssets;
import org.moboxlab.mbb.eval.bank.EvalBankCache;
import org.moboxlab.mbb.eval.bank.EvalBankLocator;
import org.moboxlab.mbb.eval.bank.EvalBankProblem;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;
import org.moboxlab.mbb.eval.judge.EvalJudgePrompt;
import org.moboxlab.mbb.eval.runner.EvalDataBootstrap;
import org.moboxlab.mbb.eval.runner.EvalPreflight;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.Stage;

import com.alibaba.fastjson.JSONObject;

/**
 * 预检拒绝回执呈现用例（规格 #50 切片 H-1；§13.2、§16.9，验收 H1/H2/H3）。
 *
 * <p>回执由固定顺序的多行组成：结论头（阶段｜错误码）、错误码中文说明、
 * 位置（绝对路径）、最多 5 条明细、总数、修复建议、只读命令可用性；
 * 同一输入两次渲染必须逐字节相同，未知错误码不得回显原码。</p>
 */
public class EvalRejectionPresenterTest {

    private static final String BANK_PATH = "C:/eval-test/evals/bank-1.0.0.json";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void bankFieldFailureShouldRenderGoldenReceipt() {
        List<String> details = Arrays.asList(
                "E_BANK_FIELD（cases[1].reference）：reference 字段不能为空",
                "E_BANK_FIELD（cases[2].history[41]）：history 最多 40 条",
                "E_BANK_FIELD（cases[3].focus）：focus 超过 200 字",
                "E_BANK_ID（cases[4].id）：id 必须匹配 <dimension>-NN",
                "E_BANK_FIELD（cases[5].style.hard）：必须恰好 8 条",
                "E_BANK_FIELD（cases[6].knowledge.expected）：expected 必须是 choices 的子集",
                "E_BANK_COVERAGE（cases）：smoke 必须 12 条");
        EvalFailure failure = EvalFailure.of(Stage.BANK, EvalBankProblem.FIELD,
                "题库校验失败：共 7 个错误（E_BANK_FIELD）",
                "请按上面列出的 JSON path 修正题库后重启插件")
                .withDetails(details, 7)
                .withAbsolutePath(BANK_PATH);

        List<String> expected = Arrays.asList(
                "[eval] 预检失败（题库｜E_BANK_FIELD）：题库校验失败：共 7 个错误（E_BANK_FIELD）",
                "[eval] 错误码说明：必填字段缺失、类型不符或场景字段越界",
                "[eval] 位置：" + BANK_PATH,
                "[eval] 1. " + details.get(0),
                "[eval] 2. " + details.get(1),
                "[eval] 3. " + details.get(2),
                "[eval] 4. " + details.get(3),
                "[eval] 5. " + details.get(4),
                "[eval] 仅回显前 5 条，共 7 个错误；完整清单见插件日志",
                "[eval] 修复建议：请按上面列出的 JSON path 修正题库后重启插件",
                "[eval] " + EvalRejectionPresenter.REPORT_HINT);

        assertEquals("回执必须逐字节稳定", expected, EvalRejectionPresenter.render(failure));
        assertEquals(String.join("\n", expected), EvalRejectionPresenter.renderText(failure));
    }

    @Test
    public void h1InvalidBankFieldShouldExposeCodeJsonPathAndFiveDetails() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        JSONObject bank = TestEvalAssets.builtinBank();
        TestEvalAssets.writeValidRubric(evals, bank);
        bank.remove("hash");
        for (int i = 0; i < 7; i++) {
            bank.getJSONArray("cases").getJSONObject(i).put("id", "bad-" + i);
        }
        TestEvalAssets.write(evals, TestEvalAssets.BANK_FILE_NAME, bank.toJSONString());

        EvalDataBootstrap bootstrap = EvalDataBootstrap.load(dataFolder,
                TestEvalAssets.BANK_FILE_NAME, EvalJudgePrompt.PROMPT_VERSION,
                null, null, false, null, null);
        EvalBankCache cache = bootstrap.bank();
        assertFalse("夹具应是校验失败的题库", cache.ok());
        EvalFailure failure = EvalFailure.bank(cache);

        assertEquals(EvalBankProblem.ID, failure.code());
        assertEquals("总数应取全量错误数", 7, failure.total());
        assertEquals("明细最多 5 条", 5, failure.details().size());

        List<String> lines = EvalRejectionPresenter.render(failure);
        String text = EvalRejectionPresenter.renderText(failure);
        assertEquals("[eval] 预检失败（题库｜E_BANK_ID）：" + cache.message(), lines.get(0));
        assertTrue("必须回显 JSON path：" + text, text.contains("cases[0].id"));
        assertTrue("必须回显 JSON path：" + text, text.contains("cases[4].id"));
        assertFalse("不得回显第 6 条错误：" + text, text.contains("cases[5].id"));
        assertTrue("必须回显总数：" + text, text.contains("仅回显前 5 条，共 7 个错误"));
        assertTrue("必须回显题库绝对路径：" + text, text.contains(cache.absolutePath()));
        assertFalse("消息已含路径时不再单列位置行：" + text, text.contains("[eval] 位置："));
        assertTrue("必须给出修复建议：" + text, text.contains("修复建议："));
        assertEquals(5, countNumberedDetailLines(lines));

        for (String line : lines) {
            assertTrue("每行都必须带 [eval] 前缀：" + line, line.startsWith("[eval] "));
            assertFalse("回执行内不得含换行：" + line, line.contains("\n"));
        }
    }

    @Test
    public void h2UnreadableEvalsShouldRenderDataNotFoundWithActualPath() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.evalsDirectory(dataFolder);
        assertFalse("夹具不应预先创建 evals 目录", evals.exists());

        EvalDataBootstrap bootstrap = EvalDataBootstrap.load(dataFolder,
                TestEvalAssets.BANK_FILE_NAME, EvalJudgePrompt.PROMPT_VERSION,
                null, null, false, null, null);
        EvalBankCache cache = bootstrap.bank();
        assertFalse("目录不可读时题库必须加载失败", cache.ok());
        assertEquals(EvalBankLocator.CODE_DATA_NOT_FOUND, cache.code());

        EvalFailure failure = EvalFailure.bank(cache);
        List<String> lines = EvalRejectionPresenter.render(failure);
        String text = EvalRejectionPresenter.renderText(failure);

        assertEquals("[eval] 预检失败（题库｜E_ROLEPLAY_DATA_NOT_FOUND）：" + cache.message(),
                lines.get(0));
        assertTrue("必须回显实际查找路径：" + text, text.contains(evals.getAbsolutePath()));
        assertTrue("必须给出实际项目录：" + text, text.contains("plugins/MBB-Roleplay/evals"));
        assertTrue("说明中必须点明只读 report 仍可用：" + text, text.contains("/eval report"));
        assertEquals("最后一行必须是只读命令可用性说明",
                "[eval] " + EvalRejectionPresenter.REPORT_HINT, lines.get(lines.size() - 1));
    }

    @Test
    public void h2DataDirectoryFactoryShouldGiveAbsolutePathAndNoRunSideEffects() {
        File evals = new File("MBB-Eval/out/h2-missing-evals");
        EvalFailure failure = EvalFailure.dataDirectory(evals);
        assertEquals(EvalBankLocator.CODE_DATA_NOT_FOUND, failure.code());
        assertTrue("原因应含实际路径：" + failure.message(),
                failure.message().contains(evals.getAbsolutePath()));
        String text = EvalRejectionPresenter.renderText(failure);
        assertTrue(text, text.contains(evals.getAbsolutePath()));
        assertTrue("修复建议应给实际目录：" + text,
                text.contains("plugins/MBB-Roleplay/evals"));
        assertTrue("修复建议应给只读出口：" + text, text.contains("/eval report"));
    }

    @Test
    public void h3DisabledAiShouldRejectWithAiHostHint() {
        EvalFailure failure = EvalFailure.ofStage(Stage.MBB_AI, EvalGenerateResult.ERROR_AI_MISSING,
                "MBB-AI 服务不可用：连接被拒绝", "");
        String text = EvalRejectionPresenter.renderText(failure);

        assertTrue("H3：必须表现为预检拒绝：" + text, text.contains("预检失败"));
        assertTrue("H3：必须提示检查 aiHost：" + text, text.contains("aiHost"));
        assertTrue("H3：必须回显错误码：" + text, text.contains("ai-missing"));
        assertTrue("H3：说明应指出来源：" + text, text.contains("MBB-AI"));
    }

    @Test
    public void h3EmptyCodeShouldStillFallBackToStageHint() {
        EvalFailure failure = EvalFailure.ofStage(Stage.MBB_AI, "",
                "MBB-AI 服务不可用：连接被拒绝", "");
        List<String> lines = EvalRejectionPresenter.render(failure);
        String text = String.join("\n", lines);

        assertEquals("[eval] 预检失败（MBB-AI）：MBB-AI 服务不可用：连接被拒绝", lines.get(0));
        assertFalse("空码不应有错误码说明行：" + text, text.contains("错误码说明："));
        assertTrue("H3：阶段兜底建议仍须提示 aiHost：" + text, text.contains("aiHost"));
    }

    @Test
    public void unknownCodeShouldNeverBeEchoed() {
        EvalFailure failure = EvalFailure.ofStage(Stage.BANK, "E_BANK_INVENTED",
                "题库校验失败：无法识别的错误", "");
        String text = EvalRejectionPresenter.renderText(failure);

        assertFalse("未知码不得被回显：" + text, text.contains("E_BANK_INVENTED"));
        assertTrue("必须标记为未登记错误码：" + text, text.contains("未登记错误码"));
        assertTrue(text, text.contains("封闭错误码集合"));
    }

    @Test
    public void everyKnownCodeShouldProvideCatalogNextActionWhenHintMissing() {
        for (String code : EvalErrorCatalog.codes()) {
            EvalFailure failure = EvalFailure.ofStage(Stage.BANK, code, "测试原因", "");
            String text = EvalRejectionPresenter.renderText(failure);
            assertTrue("缺少目录修复建议：" + code,
                    text.contains(EvalErrorCatalog.nextAction(code)));
            assertTrue("缺少目录原因：" + code, text.contains(EvalErrorCatalog.reason(code)));
        }
    }

    @Test
    public void renderingShouldBeByteStableAcrossInstances() {
        String[] details = { "E_BANK_ID（cases[0].id）：id 不合法", "E_BANK_ID（cases[1].id）：id 重复" };
        EvalFailure first = EvalFailure.of(Stage.BANK, EvalBankProblem.ID, "题库校验失败",
                "请修正 id 后重启插件").withDetails(Arrays.asList(details), 2)
                .withAbsolutePath(BANK_PATH);
        EvalFailure second = EvalFailure.of(Stage.BANK, EvalBankProblem.ID, "题库校验失败",
                "请修正 id 后重启插件").withDetails(Arrays.asList(details), 2)
                .withAbsolutePath(BANK_PATH);

        assertEquals(EvalRejectionPresenter.renderText(first), EvalRejectionPresenter.renderText(second));
        assertEquals(EvalRejectionPresenter.render(first), EvalRejectionPresenter.render(second));
        assertEquals(EvalRejectionPresenter.render(first), EvalRejectionPresenter.render(first));
        assertTrue("全部错误时给全部结论：" + EvalRejectionPresenter.renderText(first),
                EvalRejectionPresenter.renderText(first).contains("以上 2 条为全部错误"));
    }

    @Test
    public void startupSummaryShouldBeSingleLineWarnWithCacheWording() throws Exception {
        File dataFolder = TestEvalAssets.dataFolder(temporaryFolder.newFolder());
        File evals = TestEvalAssets.ensureEvalsDirectory(dataFolder);
        JSONObject bank = TestEvalAssets.builtinBank();
        TestEvalAssets.writeValidRubric(evals, bank);
        bank.remove("hash");
        for (int i = 0; i < 7; i++) {
            bank.getJSONArray("cases").getJSONObject(i).put("id", "bad-" + i);
        }
        TestEvalAssets.write(evals, TestEvalAssets.BANK_FILE_NAME, bank.toJSONString());
        EvalDataBootstrap bootstrap = EvalDataBootstrap.load(dataFolder,
                TestEvalAssets.BANK_FILE_NAME, EvalJudgePrompt.PROMPT_VERSION,
                null, null, false, null, null);
        EvalBankCache cache = bootstrap.bank();

        String summary = EvalRejectionPresenter.startupSummary(cache);
        assertFalse("onEnable 摘要必须是单行日志：" + summary, summary.contains("\n"));
        assertTrue("日志统一 [评测] 前缀：" + summary, summary.startsWith("[评测] "));
        assertTrue("必须复用加载消息：" + summary, summary.contains(cache.message()));
        assertTrue("必须复用加载修复建议：" + summary, summary.contains(cache.hint()));
        assertTrue("必须给错误总数：" + summary, summary.contains("共 7 个错误"));
        assertTrue("必须给首个问题：" + summary,
                summary.contains(cache.errors().get(0)));
        assertNotNull(EvalRejectionPresenter.startupSummary(EvalFailure.bank(cache)));
    }

    @Test
    public void trialDispositionShouldRenderWarningWithoutReadonlyTail() {
        EvalPreflight.Rejection rejection = new EvalPreflight.Rejection(Stage.PERSONA,
                EvalBankProblem.PERSONA, "题库 persona 与当前 persona 不一致",
                "已按 evalAllowPersonaMismatch=true 继续", EvalPreflight.Disposition.TRIAL);
        String text = EvalRejectionPresenter.renderText(EvalFailure.of(rejection));

        assertTrue("降级试跑应为告警：" + text, text.contains("预检告警"));
        assertFalse("告警不是拒绝，不附拒绝尾行：" + text,
                text.contains(EvalRejectionPresenter.REPORT_HINT));
        assertTrue(text, text.contains("已按 evalAllowPersonaMismatch=true 继续"));
    }

    @Test
    public void missingBankCacheShouldFallBackToReadCode() {
        EvalFailure failure = EvalFailure.bank(null);
        assertEquals(Stage.BANK, failure.stage());
        assertEquals(EvalBankProblem.READ, failure.code());
        String text = EvalRejectionPresenter.renderText(failure);
        assertTrue(text, text.contains("题库尚未加载"));
        assertTrue(text, text.contains("/eval report"));
    }

    @Test
    public void nullInputShouldBeRejected() {
        try {
            EvalFailure.of((EvalPreflight.Rejection) null);
            fail("空拒绝结果必须抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            EvalRejectionPresenter.render(null);
            fail("空失败对象必须抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    private static int countNumberedDetailLines(List<String> lines) {
        int count = 0;
        for (String line : lines) {
            for (int i = 1; i <= EvalRejectionPresenter.MAX_DETAILS; i++) {
                if (line.startsWith("[eval] " + i + ". ")) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }
}