package org.moboxlab.mbb.eval.error;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;
import org.moboxlab.mbb.eval.bank.EvalBankLocator;
import org.moboxlab.mbb.eval.bank.EvalBankProblem;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;
import org.moboxlab.mbb.eval.rubric.EvalRubricProblem;

/**
 * 错误码目录用例（规格 #50 切片 H-1；§13.1.1、§13.1.2、§16.9）。
 *
 * <p>错误码集合是封闭的：15 个 E_BANK_*、E_ROLEPLAY_DATA_NOT_FOUND、
 * 8 个 E_RUBRIC_* 与 8 个 eval-generate errorType，共 32 个；
 * 任何未知码都不得被目录接受，也不得被补出原因或下一步动作。</p>
 */
public class EvalErrorCatalogTest {

    private static final List<String> RUBRIC_CODES = Arrays.asList(
            EvalRubricProblem.CODE_READ,
            EvalRubricProblem.CODE_JSON,
            EvalRubricProblem.CODE_VERSION,
            EvalRubricProblem.CODE_HASH,
            EvalRubricProblem.CODE_PROMPT_VERSION,
            EvalRubricProblem.CODE_FIELD,
            EvalRubricProblem.CODE_DIMENSION,
            EvalRubricProblem.CODE_WEIGHT);

    private static final List<String> GENERATE_ERROR_TYPES = Arrays.asList(
            EvalGenerateResult.ERROR_DISABLED,
            EvalGenerateResult.ERROR_INVALID_PARAM,
            EvalGenerateResult.ERROR_HISTORY_TOO_LONG,
            EvalGenerateResult.ERROR_PROFILE_INVALID,
            EvalGenerateResult.ERROR_AI_MISSING,
            EvalGenerateResult.ERROR_BUSY,
            EvalGenerateResult.ERROR_TIMEOUT,
            EvalGenerateResult.ERROR_INTERNAL);

    @Test
    public void codeSetShouldBeClosedAndMatchSpecSources() {
        Set<String> expected = new HashSet<String>();
        expected.addAll(EvalBankProblem.CODES);
        expected.add(EvalBankLocator.CODE_DATA_NOT_FOUND);
        expected.addAll(RUBRIC_CODES);
        expected.addAll(GENERATE_ERROR_TYPES);

        assertEquals("规格定义的错误码应为 32 个：15 + 1 + 8 + 8", 32, expected.size());
        assertEquals("目录不得多登记或少登记错误码", expected,
                new HashSet<String>(EvalErrorCatalog.codes()));
        assertEquals("目录大小应与封闭集合一致", 32, EvalErrorCatalog.codes().size());
    }

    @Test
    public void codeOrderShouldBeStableAcrossCalls() {
        List<String> codes = EvalErrorCatalog.codes();
        assertEquals("E_BANK_* 必须按题库校验顺序排列", EvalBankProblem.CODES,
                codes.subList(0, 15));
        assertEquals(EvalBankLocator.CODE_DATA_NOT_FOUND, codes.get(15));
        assertEquals(RUBRIC_CODES, codes.subList(16, 24));
        assertEquals(GENERATE_ERROR_TYPES, codes.subList(24, 32));
        assertEquals("同一输入顺序必须逐项稳定", codes, EvalErrorCatalog.codes());
    }

    @Test
    public void everyCodeShouldCarryChineseReasonAndNextAction() {
        for (String code : EvalErrorCatalog.codes()) {
            EvalErrorCatalog.Entry entry = EvalErrorCatalog.entry(code);
            assertNotNull("缺少登记：" + code, entry);
            assertEquals(code, entry.code());
            assertFalse("缺少中文原因：" + code, entry.reason().isEmpty());
            assertFalse("缺少下一步动作：" + code, entry.nextAction().isEmpty());
            assertFalse("原因应为单行：" + code, entry.reason().indexOf('\n') >= 0);
            assertFalse("动作应为单行：" + code, entry.nextAction().indexOf('\n') >= 0);
            assertTrue("原因应含中文：" + code, containsChinese(entry.reason()));
            assertTrue("动作应含中文：" + code, containsChinese(entry.nextAction()));
            assertEquals(entry.reason(), EvalErrorCatalog.reason(code));
            assertEquals(entry.nextAction(), EvalErrorCatalog.nextAction(code));
        }
    }

    @Test
    public void groupsShouldMatchSpecSections() {
        for (String code : EvalBankProblem.CODES) {
            assertEquals(code, EvalErrorCatalog.GROUP_BANK, EvalErrorCatalog.group(code));
        }
        assertEquals(EvalErrorCatalog.GROUP_DATA,
                EvalErrorCatalog.group(EvalBankLocator.CODE_DATA_NOT_FOUND));
        for (String code : RUBRIC_CODES) {
            assertEquals(code, EvalErrorCatalog.GROUP_RUBRIC, EvalErrorCatalog.group(code));
        }
        for (String code : GENERATE_ERROR_TYPES) {
            assertEquals(code, EvalErrorCatalog.GROUP_GENERATE, EvalErrorCatalog.group(code));
        }
    }

    @Test
    public void unknownCodeShouldNeverBeAcceptedOrInvented() {
        String[] unknown = { "E_BANK_NOT_A_REAL_CODE", "E_EVAL_RUN_FAILED", "eval-unknown",
                "", null };
        for (String code : unknown) {
            assertFalse("未知码不得被接受：" + code, EvalErrorCatalog.isKnown(code));
            assertNull("未知码不得有登记项：" + code, EvalErrorCatalog.entry(code));
            assertEquals("未知码不得补出原因：" + code, "", EvalErrorCatalog.reason(code));
            assertEquals("未知码不得补出动作：" + code, "", EvalErrorCatalog.nextAction(code));
            assertEquals("未知码不得补出分组：" + code, "", EvalErrorCatalog.group(code));
        }
    }

    @Test
    public void codesShouldBeUnmodifiable() {
        try {
            EvalErrorCatalog.codes().add("E_BANK_INVENTED");
            fail("错误码集合必须不可变，禁止运行期新增错误码");
        } catch (UnsupportedOperationException expected) {
            // 预期：封闭集合，运行期不可扩展
        }
    }

    @Test
    public void dataNotFoundShouldPointToEvalsDirectoryAndReadonlyReport() {
        String action = EvalErrorCatalog.nextAction(EvalBankLocator.CODE_DATA_NOT_FOUND);
        assertTrue("必须给出实际寻找目录：" + action,
                action.contains("plugins/MBB-Roleplay/evals"));
        assertTrue("必须说明只读 report 仍可用：" + action, action.contains("/eval report"));
        assertTrue("原因应点明目录不可读：" + EvalErrorCatalog.reason(
                EvalBankLocator.CODE_DATA_NOT_FOUND), containsChinese(
                EvalErrorCatalog.reason(EvalBankLocator.CODE_DATA_NOT_FOUND)));
    }

    @Test
    public void aiMissingShouldPointToAiHost() {
        String action = EvalErrorCatalog.nextAction(EvalGenerateResult.ERROR_AI_MISSING);
        assertTrue("H3：必须提示检查 aiHost 配置：" + action, action.contains("aiHost"));
        assertTrue("H3：必须说明启用状态：" + action, action.contains("MBB-AI"));
    }

    @Test
    public void disabledShouldPointToEvalApiSwitch() {
        String action = EvalErrorCatalog.nextAction(EvalGenerateResult.ERROR_DISABLED);
        assertTrue("必须给出启用开关：" + action, action.contains("evalApiVersion"));
    }

    private static boolean containsChinese(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x4E00 && text.charAt(i) <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }
}
