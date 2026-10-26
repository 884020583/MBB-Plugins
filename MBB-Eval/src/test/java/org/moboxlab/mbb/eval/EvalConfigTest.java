package org.moboxlab.mbb.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.moboxlab.mbb.eval.config.EvalConfig;

/**
 * 配置键最小用例（A-5：配置键）。
 *
 * <p>断言 §12.1 的 14 个键与默认值逐字一致，并覆盖 §12.4 的钳制口径：
 * 缺键取默认、越界收敛并告警、非法值回退默认、未知键忽略并告警。</p>
 */
public class EvalConfigTest {

    private static final List<String> EXPECTED_KEYS = Arrays.asList(
            "evalCallBudget", "evalRunDeadlineMinutes", "evalKeepRuns", "evalDefaultTier",
            "evalTimeoutSecond", "evalJudgeTimeoutSecond", "evalGenerateRetries",
            "evalCachedResamples", "evalFailStreakAbort", "evalFailBackoffSeconds",
            "evalBankFile", "evalAllowPersonaMismatch", "evalProfile", "evalJudgeProfile");

    @Test
    public void defaultsShouldMatchSpec() {
        Map<String, String> defaults = EvalConfig.defaults();
        assertEquals("配置键数量应与 §12.1 一致", 14, defaults.size());
        assertEquals("配置键顺序应与模板一致", EXPECTED_KEYS, new ArrayList<String>(defaults.keySet()));
        assertEquals("100", defaults.get("evalCallBudget"));
        assertEquals("30", defaults.get("evalRunDeadlineMinutes"));
        assertEquals("30", defaults.get("evalKeepRuns"));
        assertEquals("smoke", defaults.get("evalDefaultTier"));
        assertEquals("60", defaults.get("evalTimeoutSecond"));
        assertEquals("60", defaults.get("evalJudgeTimeoutSecond"));
        assertEquals("1", defaults.get("evalGenerateRetries"));
        assertEquals("3", defaults.get("evalCachedResamples"));
        assertEquals("3", defaults.get("evalFailStreakAbort"));
        assertEquals("30", defaults.get("evalFailBackoffSeconds"));
        assertEquals("", defaults.get("evalBankFile"));
        assertEquals("false", defaults.get("evalAllowPersonaMismatch"));
        assertEquals("", defaults.get("evalProfile"));
        assertEquals("", defaults.get("evalJudgeProfile"));
        assertEquals(14, EvalConfig.keys().size());
    }

    @Test
    public void emptyConfigShouldUseDefaultsWithoutWarnings() {
        EvalConfig config = EvalConfig.load(null);
        assertTrue("空配置不应产生告警", config.warnings().isEmpty());
        assertEquals(100, config.callBudget());
        assertEquals(30, config.runDeadlineMinutes());
        assertEquals(30, config.keepRuns());
        assertEquals("smoke", config.defaultTier());
        assertEquals(60, config.timeoutSecond());
        assertEquals(60, config.judgeTimeoutSecond());
        assertEquals(1, config.generateRetries());
        assertEquals(3, config.cachedResamples());
        assertEquals(3, config.failStreakAbort());
        assertEquals(30, config.failBackoffSeconds());
        assertEquals("", config.bankFile());
        assertFalse(config.allowPersonaMismatch());
        assertEquals("", config.profile());
        assertEquals("", config.judgeProfile());
    }

    @Test
    public void outOfRangeIntegerShouldClampAndWarn() {
        Map<String, String> raw = new LinkedHashMap<String, String>();
        raw.put("evalCallBudget", "200");
        raw.put("evalRunDeadlineMinutes", "1");
        raw.put("evalTimeoutSecond", "1000");
        EvalConfig config = EvalConfig.fromValues(raw);
        assertEquals("evalCallBudget 上限为 100", 100, config.callBudget());
        assertEquals("evalRunDeadlineMinutes 下限为 5", 5, config.runDeadlineMinutes());
        assertEquals("evalTimeoutSecond 上限为 300", 300, config.timeoutSecond());
        assertEquals(3, config.warnings().size());
        // §12.4 只冻结钳制后的数值与「必须 WARN」，不冻结文案，这里只锁键名与结果值
        assertTrue(config.warnings().get(0).contains("evalCallBudget"));
        assertTrue(config.warnings().get(0).contains("100"));
        assertTrue(config.warnings().get(1).contains("evalRunDeadlineMinutes"));
        assertTrue(config.warnings().get(1).contains("5"));
        assertTrue(config.warnings().get(2).contains("evalTimeoutSecond"));
        assertTrue(config.warnings().get(2).contains("300"));
    }

    @Test
    public void invalidValuesShouldFallBackToDefaults() {
        Map<String, String> raw = new LinkedHashMap<String, String>();
        raw.put("evalCallBudget", "abc");
        raw.put("evalDefaultTier", "FULL");
        raw.put("evalAllowPersonaMismatch", "maybe");
        EvalConfig config = EvalConfig.fromValues(raw);
        assertEquals(100, config.callBudget());
        assertEquals("smoke", config.defaultTier());
        assertFalse(config.allowPersonaMismatch());
        assertEquals(3, config.warnings().size());
        assertTrue(config.warnings().get(0).contains("evalCallBudget"));
        assertTrue(config.warnings().get(0).contains("abc"));
        assertTrue(config.warnings().get(1).contains("evalAllowPersonaMismatch"));
        assertTrue(config.warnings().get(1).contains("maybe"));
        assertTrue(config.warnings().get(2).contains("evalDefaultTier"));
        assertTrue(config.warnings().get(2).contains("FULL"));
    }

    @Test
    public void unknownKeyShouldWarnAndBeIgnored() {
        Map<String, String> raw = new LinkedHashMap<String, String>();
        raw.put("evalUnknownKey", "1");
        EvalConfig config = EvalConfig.fromValues(raw);
        assertEquals(1, config.warnings().size());
        assertTrue(config.warnings().get(0).contains("未知配置项"));
        assertTrue(config.warnings().get(0).contains("evalUnknownKey"));
        assertEquals(100, config.callBudget());
        try {
            config.raw("evalUnknownKey");
            fail("未知键取原始值应抛异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("未知配置项"));
        }
    }

    @Test
    public void booleanShouldIgnoreCase() {
        Map<String, String> raw = new LinkedHashMap<String, String>();
        raw.put("evalAllowPersonaMismatch", "TRUE");
        EvalConfig config = EvalConfig.fromValues(raw);
        assertTrue(config.allowPersonaMismatch());
        assertTrue("大小写不敏感时不应告警", config.warnings().isEmpty());
    }
}
