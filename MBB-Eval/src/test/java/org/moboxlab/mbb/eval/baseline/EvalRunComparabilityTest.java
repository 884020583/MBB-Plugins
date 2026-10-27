package org.moboxlab.mbb.eval.baseline;

import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 九键比较器测试（§8.2、F13、F14）。
 *
 * <p>要点：只有双 full + 九键全等才可比；任一键变化列出全部差异；缺失键单独标记；
 * config.yml 的键序/注释不参与比较（只比较 config_fingerprint 值）。</p>
 */
public class EvalRunComparabilityTest {

    private static final String RUN_A = "r-20261025230000-full-aaaa";
    private static final String RUN_B = "r-20261025230100-full-bbbb";
    private static final String SMOKE = "r-20261025230200-smoke-cccc";

    @Test
    public void identicalFullRunsShouldBeComparable() {
        JSONObject runA = fullRun(RUN_A);
        JSONObject runB = fullRun(RUN_B);

        EvalRunComparability.Result result = EvalRunComparability.compare(runA, runB);

        assertTrue(result.comparable());
        assertNull(result.reason());
        assertTrue(result.differingKeys().isEmpty());
        assertTrue(result.missingKeys().isEmpty());
        assertFalse(result.hasDifferingKeys());
        assertFalse(result.hasMissingKeys());
    }

    @Test
    public void keyNamesShouldMatchReportOrder() {
        assertEquals(Arrays.asList("bank_hash", "rubric_version", "persona_hash",
                "config_fingerprint", "eval_profile", "judge_profile", "judge_model",
                "generation_model", "eval_api_version"), EvalRunComparability.keyNames());
        assertEquals(org.moboxlab.mbb.eval.report.EvalReportData.nineKeyNames(),
                EvalRunComparability.keyNames());
    }

    @Test
    public void smokeRunShouldNeverBeComparable() {
        JSONObject smoke = fullRun(SMOKE);
        smoke.put("tier", "smoke");

        EvalRunComparability.Result result = EvalRunComparability.compare(fullRun(RUN_A), smoke);

        assertFalse(result.comparable());
        assertEquals(EvalRunComparability.REASON_TIER, result.reason());
        assertTrue(result.differingKeys().isEmpty());
    }

    @Test
    public void missingTierShouldBeIncomparable() {
        JSONObject noTier = fullRun(RUN_B);
        noTier.remove("tier");

        EvalRunComparability.Result result = EvalRunComparability.compare(fullRun(RUN_A), noTier);

        assertFalse(result.comparable());
        assertEquals(EvalRunComparability.REASON_TIER, result.reason());
    }

    @Test
    public void eachSingleKeyChangeShouldBeIncomparableAndListed() {
        for (String key : EvalRunComparability.KEY_NAMES) {
            JSONObject runB = fullRun(RUN_B);
            runB.put(key, "changed-" + key);

            EvalRunComparability.Result result = EvalRunComparability.compare(fullRun(RUN_A), runB);

            assertFalse("键变化必须不可比：" + key, result.comparable());
            assertEquals("键变化必须列出该键：" + key,
                    java.util.Collections.singletonList(key), result.differingKeys());
            assertTrue(result.missingKeys().isEmpty());
            assertEquals(EvalRunComparability.REASON_DIFFERENT, result.reason());
        }
    }

    @Test
    public void multipleKeyChangesShouldListAllInFixedOrder() {
        JSONObject runB = fullRun(RUN_B);
        // 故意反序修改，输出仍按 §8.2 固定顺序
        runB.put("generation_model", "gen-2");
        runB.put("judge_model", "judge-2");
        runB.put("bank_hash", "bank-2");

        EvalRunComparability.Result result = EvalRunComparability.compare(fullRun(RUN_A), runB);

        assertFalse(result.comparable());
        assertEquals(Arrays.asList("bank_hash", "judge_model", "generation_model"),
                result.differingKeys());
        assertEquals("bank_hash、judge_model、generation_model", result.differingKeysText());
    }

    @Test
    public void missingKeyShouldBeMarkedSeparatelyFromDiffering() {
        JSONObject runB = fullRun(RUN_B);
        runB.remove("judge_profile");
        runB.put("judge_model", "judge-2");

        EvalRunComparability.Result result = EvalRunComparability.compare(fullRun(RUN_A), runB);

        assertFalse(result.comparable());
        assertEquals(java.util.Collections.singletonList("judge_model"), result.differingKeys());
        assertEquals(java.util.Collections.singletonList("judge_profile"), result.missingKeys());
        assertTrue(result.hasMissingKeys());
        // 优先级：档位 > 缺失 > 不同；缺失必须能被报表识别为「快照没采到」
        assertEquals(EvalRunComparability.REASON_MISSING, result.reason());
    }

    @Test
    public void blankKeyShouldCountAsMissing() {
        JSONObject runB = fullRun(RUN_B);
        runB.put("persona_hash", "   ");

        EvalRunComparability.Result result = EvalRunComparability.compare(fullRun(RUN_A), runB);

        assertFalse(result.comparable());
        assertEquals(java.util.Collections.singletonList("persona_hash"), result.missingKeys());
        assertTrue(result.differingKeys().isEmpty());
        assertNull(result.rightValues().get("persona_hash"));
    }

    @Test
    public void snapshotKeyOrderAndCommentsShouldNotAffectFingerprintComparison() {
        JSONObject runA = fullRun(RUN_A);
        JSONObject runB = fullRun(RUN_B);
        // 同一个指纹，但快照文本的键序与注释不同（config.yml 字节变了，指纹不变）
        runA.put("config_snapshot_json", "{\"maxTokens\":512,\"temperature\":0.7}");
        runB.put("config_snapshot_json", "{\n  // 迁移后重排\n  \"temperature\": 0.7,\n  \"maxTokens\": 512\n}");

        assertTrue(EvalRunComparability.compare(runA, runB).comparable());
    }

    @Test
    public void compareToBaselineShouldOnlyCheckRunTier() {
        JSONObject run = fullRun(RUN_A);
        JSONObject baseline = new JSONObject();
        // 基线行没有 tier 列，只带九键快照
        for (String key : EvalRunComparability.KEY_NAMES) {
            baseline.put(key, run.getString(key));
        }

        assertTrue(EvalRunComparability.compareToBaseline(run, baseline).comparable());

        run.put("tier", "smoke");
        EvalRunComparability.Result smokeResult = EvalRunComparability.compareToBaseline(run, baseline);
        assertFalse(smokeResult.comparable());
        assertEquals(EvalRunComparability.REASON_TIER, smokeResult.reason());
    }

    @Test
    public void nullRunShouldBeIncomparable() {
        EvalRunComparability.Result result = EvalRunComparability.compare(null, fullRun(RUN_A));

        assertFalse(result.comparable());
        assertEquals(EvalRunComparability.REASON_RUN_MISSING, result.reason());
        assertTrue(result.differingKeys().isEmpty());
        assertTrue(result.missingKeys().isEmpty());
    }

    @Test
    public void resultListsShouldBeReadOnly() {
        EvalRunComparability.Result result = EvalRunComparability.compare(null, fullRun(RUN_A));
        try {
            result.differingKeys().add("x");
            fail("差异键列表必须只读");
        } catch (UnsupportedOperationException expected) {
            assertNotNull(expected);
        }
    }

    /** 造一条 full run 行：九键取值固定，tier=full。 */
    private static JSONObject fullRun(String runId) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("tier", "full");
        row.put("status", "COMPLETED");
        for (String key : EvalRunComparability.KEY_NAMES) {
            row.put(key, "value-" + key);
        }
        return row;
    }
}
