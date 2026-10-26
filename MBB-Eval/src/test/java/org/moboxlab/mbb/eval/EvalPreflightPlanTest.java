package org.moboxlab.mbb.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.gateway.EvalCapabilityClient;
import org.moboxlab.mbb.eval.runner.EvalPreflight;
import org.moboxlab.mbb.eval.runner.EvalPreflightPlan;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.AiStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.BankStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.CapabilitiesStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.ConfigJudgeProfileSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.DataDirStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.JudgeProfileSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.PersonaStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.RubricStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.Stage;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.StageReport;
import org.moboxlab.moboxbot.API.PluginLogger;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * 预检计划与八项预检管线测试。
 *
 * <p>覆盖规格 §9.5（预检顺序与短路）、§9.3.1（回执数字）、§13.2（预算拒绝回执与两条出路），
 * 并固定 full 79 / smoke 25 的预算构成，防止后续接线时改动数字。</p>
 */
public class EvalPreflightPlanTest {

    // ---------------------------------------------------------------- 预算计划

    @Test
    public void planShouldExposeFrozenBudgetComposition() {
        EvalPreflightPlan full = EvalPreflightPlan.of("full");
        assertEquals("full", full.tier());
        assertEquals(40, full.generationCalls());
        assertEquals(36, full.judgeCalls());
        assertEquals(3, full.calibrationCalls());
        assertEquals(79, full.planned());
        assertEquals(40, full.caseCount());
        assertEquals("生成 40 + judge 36 + 校准 3 = 79", full.composition());
        assertEquals(79, EvalPreflight.plannedCalls("full"));
        assertEquals(40, EvalPreflight.caseCount("full"));
        assertEquals(40, EvalPreflight.generationCalls("full"));
        assertEquals(36, EvalPreflight.judgeCalls("full"));

        EvalPreflightPlan smoke = EvalPreflightPlan.of("smoke");
        assertEquals("smoke", smoke.tier());
        assertEquals(12, smoke.generationCalls());
        assertEquals(10, smoke.judgeCalls());
        assertEquals(3, smoke.calibrationCalls());
        assertEquals(25, smoke.planned());
        assertEquals(12, smoke.caseCount());
        assertEquals("生成 12 + judge 10 + 校准 3 = 25", smoke.composition());
        assertEquals(25, EvalPreflight.plannedCalls("smoke"));
        assertEquals(12, EvalPreflight.caseCount("smoke"));
        assertEquals(12, EvalPreflight.generationCalls("smoke"));
        assertEquals(10, EvalPreflight.judgeCalls("smoke"));

        assertEquals(100, EvalPreflightPlan.MAX_CALL_BUDGET);
        assertTrue(full.fits(79));
        assertTrue(full.fits(100));
        assertFalse(full.fits(78));
        assertTrue(smoke.fits(25));
        assertFalse(smoke.fits(24));
    }

    @Test
    public void receiptShouldCarryRunReceiptNumbers() {
        EvalPreflightPlan.Receipt smoke = EvalPreflightPlan.of("smoke").receipt(100);
        assertEquals("smoke", smoke.tier());
        assertEquals(25, smoke.planned());
        assertEquals(100, smoke.limit());
        assertEquals(12, smoke.caseCount());
        assertEquals(2, smoke.minutesLow());
        assertEquals(4, smoke.minutesHigh());
        assertEquals("题数 12（smoke）／预计调用 25/100／预计 2-4 分钟", smoke.summary());

        EvalPreflightPlan.Receipt full = EvalPreflightPlan.of("full").receipt(100);
        assertEquals("full", full.tier());
        assertEquals(79, full.planned());
        assertEquals(100, full.limit());
        assertEquals(40, full.caseCount());
        assertEquals(6, full.minutesLow());
        assertEquals(11, full.minutesHigh());
        assertEquals("题数 40（full）／预计调用 79/100／预计 6-11 分钟", full.summary());

        // 既有静态估计方法保持原语义，不得回归。
        assertEquals(6, EvalPreflight.estimatedMinutesLow(79));
        assertEquals(11, EvalPreflight.estimatedMinutesHigh(79));
        assertEquals(2, EvalPreflight.estimatedMinutesLow(25));
        assertEquals(4, EvalPreflight.estimatedMinutesHigh(25));
    }

    // ---------------------------------------------------------------- 八项顺序

    @Test
    public void preflightShouldRunStagesInSpecOrderAndBudgetLast() {
        List<Stage> visited = new ArrayList<Stage>();
        EvalPreflight preflight = EvalPreflight.builder()
                .config(config(1))
                .logger(new RecordingLogger())
                .capabilities(capabilitiesSource(availableSnapshot(), visited))
                .bank(new RecordingSource(Stage.BANK, StageReport.pass("题库可读"), visited))
                .rubric(new RecordingSource(Stage.RUBRIC, StageReport.pass("rubric 一致"), visited))
                .persona(new RecordingSource(Stage.PERSONA, StageReport.pass("persona 一致"), visited))
                .judgeProfile(new RecordingSource(Stage.JUDGE_PROFILE, StageReport.pass("judge 可解析"), visited))
                .ai(new RecordingSource(Stage.MBB_AI, StageReport.pass("MBB-AI 在线"), visited))
                .dataDir(new RecordingSource(Stage.DATA_DIR, StageReport.pass("数据目录可读"), visited))
                .build();

        EvalPreflight.Rejection rejection = preflight.check("full");
        assertNotNull(rejection);
        assertEquals(Stage.BUDGET, rejection.stage());
        assertEquals(EvalPreflight.Disposition.REJECT, rejection.disposition());
        assertEquals(Arrays.asList(
                Stage.CAPABILITIES,
                Stage.BANK,
                Stage.RUBRIC,
                Stage.PERSONA,
                Stage.JUDGE_PROFILE,
                Stage.MBB_AI,
                Stage.DATA_DIR), visited);
    }

    @Test
    public void preflightShouldShortCircuitAtFirstRejection() {
        List<Stage> visited = new ArrayList<Stage>();
        StageReport bankReject = StageReport.reject("E_BANK_STYLE_MOUNT",
                "题库解析失败：style-mount.json 第 3 条缺 mount 字段",
                "修正题库条目后重试 /eval run；本次未建 run、未产生任何 AI 调用");
        EvalPreflight preflight = EvalPreflight.builder()
                .config(config(100))
                .logger(new RecordingLogger())
                .capabilities(capabilitiesSource(availableSnapshot(), visited))
                .bank(new RecordingSource(Stage.BANK, bankReject, visited))
                .rubric(new RecordingSource(Stage.RUBRIC, StageReport.pass("rubric 一致"), visited))
                .persona(new RecordingSource(Stage.PERSONA, StageReport.pass("persona 一致"), visited))
                .judgeProfile(new RecordingSource(Stage.JUDGE_PROFILE, StageReport.pass("judge 可解析"), visited))
                .ai(new RecordingSource(Stage.MBB_AI, StageReport.pass("MBB-AI 在线"), visited))
                .dataDir(new RecordingSource(Stage.DATA_DIR, StageReport.pass("数据目录可读"), visited))
                .build();

        EvalPreflight.Rejection rejection = preflight.check("smoke");
        assertNotNull(rejection);
        assertEquals(Stage.BANK, rejection.stage());
        assertEquals("E_BANK_STYLE_MOUNT", rejection.code());
        assertTrue(rejection.message().contains("style-mount.json"));
        assertTrue(rejection.hint().contains("未建 run"));
        assertEquals(Arrays.asList(Stage.CAPABILITIES, Stage.BANK), visited);
    }

    // ------------------------------------------------------------ 接口能力四态

    @Test
    public void capabilitiesShouldRejectWithFourDistinctCopies() {
        EvalPreflight version = preflightForCapabilities(payloadSnapshot(versionMismatchCapabilities()));
        EvalPreflight disabled = preflightForCapabilities(payloadSnapshot(disabledCapabilities()));
        EvalPreflight missing = preflightForCapabilities(payloadSnapshot(null));
        EvalPreflight contract = preflightForCapabilities(payloadSnapshot(contractBrokenCapabilities()));

        EvalPreflight.Rejection versionRejection = version.check("smoke");
        EvalPreflight.Rejection disabledRejection = disabled.check("smoke");
        EvalPreflight.Rejection missingRejection = missing.check("smoke");
        EvalPreflight.Rejection contractRejection = contract.check("smoke");

        for (EvalPreflight.Rejection rejection : Arrays.asList(
                versionRejection, disabledRejection, missingRejection, contractRejection)) {
            assertNotNull(rejection);
            assertEquals(Stage.CAPABILITIES, rejection.stage());
            assertEquals(EvalPreflight.Disposition.REJECT, rejection.disposition());
            assertTrue(rejection.message().length() > 0);
            assertTrue(rejection.hint().length() > 0);
        }

        assertTrue(versionRejection.message().contains("版本不匹配"));
        assertEquals(EvalCapabilityClient.Status.VERSION_MISMATCH.hint(), versionRejection.hint());

        assertTrue(disabledRejection.message().contains("未启用"));
        assertEquals(EvalCapabilityClient.Status.DISABLED.hint(), disabledRejection.hint());

        assertTrue(missingRejection.message().contains("缺失"));
        assertEquals(EvalCapabilityClient.Status.PLUGIN_MISSING.hint(), missingRejection.hint());

        assertTrue(contractRejection.message().contains("契约异常"));
        assertEquals(EvalCapabilityClient.Status.CONTRACT_ERROR.hint(), contractRejection.hint());

        List<String> messages = Arrays.asList(
                versionRejection.message(), disabledRejection.message(),
                missingRejection.message(), contractRejection.message());
        List<String> hints = Arrays.asList(
                versionRejection.hint(), disabledRejection.hint(),
                missingRejection.hint(), contractRejection.hint());
        assertFalse(messages.get(0).equals(messages.get(1)));
        assertFalse(messages.get(1).equals(messages.get(2)));
        assertFalse(messages.get(2).equals(messages.get(3)));
        assertFalse(hints.get(0).equals(hints.get(1)));
        assertFalse(hints.get(1).equals(hints.get(2)));
        assertFalse(hints.get(2).equals(hints.get(3)));
    }

    // ---------------------------------------------------------------- 待接线

    @Test
    public void pendingStagesShouldWarnOnceAndPass() {
        RecordingLogger logger = new RecordingLogger();
        EvalPreflight preflight = EvalPreflight.builder()
                .config(config(100))
                .logger(logger)
                .capabilities(capabilitiesSource(availableSnapshot(), null))
                .build();

        assertNull(preflight.check("full"));
        assertNull(preflight.check("full"));

        assertEquals(5, logger.warns.size());
        String[] labels = {"题库", "rubric", "persona", "MBB-AI", "数据目录"};
        for (int index = 0; index < labels.length; index++) {
            String warn = logger.warns.get(index);
            assertTrue(warn, warn.contains(labels[index]));
            assertTrue(warn, warn.contains("尚未接线，跳过预检"));
        }
        for (String warn : logger.warns) {
            assertFalse(warn, warn.contains("接口能力"));
            assertFalse(warn, warn.contains("judge profile"));
            assertFalse(warn, warn.contains("预算"));
        }
        assertEquals(0, logger.errors.size());
    }

    // ---------------------------------------------------------------- 预算

    @Test
    public void budgetShouldPassAtLimitEqualsPlanned() {
        assertNull(preflightForBudget(100).check("full"));
        assertNull(preflightForBudget(79).check("full"));
        assertNull(preflightForBudget(25).check("smoke"));
    }

    @Test
    public void budgetShouldRejectWithCompositionAndTwoWaysOut() {
        EvalPreflight.Rejection rejection = preflightForBudget(78).check("full");
        assertNotNull(rejection);
        assertEquals(Stage.BUDGET, rejection.stage());
        assertEquals(EvalPreflight.Disposition.REJECT, rejection.disposition());
        assertTrue(rejection.message().contains("生成 40 + judge 36 + 校准 3 = 79"));
        assertTrue(rejection.message().contains("evalCallBudget=78"));
        assertTrue(rejection.message().contains("上限 100"));
        assertTrue(rejection.hint().contains("100"));
        assertTrue(rejection.hint().contains("smoke"));
        assertTrue(rejection.hint().contains("未建 run"));
    }

    @Test
    public void budgetShouldRejectSmokeWhenBelowTwentyFive() {
        EvalPreflight.Rejection rejection = preflightForBudget(24).check("smoke");
        assertNotNull(rejection);
        assertEquals(Stage.BUDGET, rejection.stage());
        assertTrue(rejection.message().contains("生成 12 + judge 10 + 校准 3 = 25"));
        assertTrue(rejection.message().contains("evalCallBudget=24"));
        assertTrue(rejection.hint().contains("100"));
        assertTrue(rejection.hint().contains("最低档"));
    }

    // ------------------------------------------------------------ judge profile

    @Test
    public void judgeProfileShouldFallBackAndRejectWhenAllEmpty() {
        EvalCapabilityClient.Snapshot snapshot = availableSnapshot();
        CapabilitiesStatusSource capabilities = capabilitiesSource(snapshot, null);

        StageReport fromJudge = new ConfigJudgeProfileSource(configWith("evalJudgeProfile", "judge-x"), capabilities)
                .report("smoke");
        assertEquals(StageReport.Outcome.PASS, fromJudge.outcome());
        assertTrue(fromJudge.message().contains("judge-x"));

        StageReport fromEval = new ConfigJudgeProfileSource(
                configWith("evalProfile", "eval-y"), capabilities).report("smoke");
        assertEquals(StageReport.Outcome.PASS, fromEval.outcome());
        assertTrue(fromEval.message().contains("eval-y"));

        StageReport fromAi = new ConfigJudgeProfileSource(
                EvalConfig.fromValues(new LinkedHashMap<String, String>()), capabilities).report("smoke");
        assertEquals(StageReport.Outcome.PASS, fromAi.outcome());
        assertTrue(fromAi.message().contains("default"));

        EvalCapabilityClient.Snapshot blankAi = payloadSnapshot(blankAiCapabilities());
        StageReport missing = new ConfigJudgeProfileSource(
                EvalConfig.fromValues(new LinkedHashMap<String, String>()),
                capabilitiesSource(blankAi, null)).report("smoke");
        assertEquals(StageReport.Outcome.REJECT, missing.outcome());
        assertTrue(missing.message().contains("judge profile 缺失"));
        assertTrue(missing.hint().contains("evalJudgeProfile"));
    }

    // ---------------------------------------------------------------- 辅助

    private static EvalPreflight preflightForBudget(int callBudget) {
        return EvalPreflight.builder()
                .config(config(callBudget))
                .logger(new RecordingLogger())
                .capabilities(capabilitiesSource(availableSnapshot(), null))
                .build();
    }

    private static EvalPreflight preflightForCapabilities(EvalCapabilityClient.Snapshot snapshot) {
        final EvalCapabilityClient.Snapshot fixed = snapshot;
        return EvalPreflight.builder()
                .config(config(100))
                .logger(new RecordingLogger())
                .capabilities(new CapabilitiesStatusSource() {
                    @Override
                    public EvalCapabilityClient.Snapshot snapshot() {
                        return fixed;
                    }
                })
                .build();
    }

    private static EvalConfig config(int callBudget) {
        Map<String, String> raw = new LinkedHashMap<String, String>();
        raw.put("evalCallBudget", String.valueOf(callBudget));
        return EvalConfig.fromValues(raw);
    }

    private static EvalConfig configWith(String key, String value) {
        Map<String, String> raw = new LinkedHashMap<String, String>();
        raw.put(key, value);
        raw.put("evalCallBudget", "100");
        return EvalConfig.fromValues(raw);
    }

    private static CapabilitiesStatusSource capabilitiesSource(
            final EvalCapabilityClient.Snapshot snapshot, final List<Stage> visited) {
        return new CapabilitiesStatusSource() {
            @Override
            public EvalCapabilityClient.Snapshot snapshot() {
                if (visited != null) {
                    visited.add(Stage.CAPABILITIES);
                }
                return snapshot;
            }
        };
    }

    private static EvalCapabilityClient.Snapshot availableSnapshot() {
        return payloadSnapshot(availableCapabilities());
    }

    private static EvalCapabilityClient.Snapshot payloadSnapshot(final JSONObject payload) {
        return new EvalCapabilityClient(new EvalCapabilityClient.CapabilitiesSource() {
            @Override
            public Object fetch() {
                return payload;
            }
        }, null).snapshot();
    }

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

    private static JSONObject versionMismatchCapabilities() {
        JSONObject capabilities = availableCapabilities();
        capabilities.put("evalApiVersion", 2);
        return capabilities;
    }

    private static JSONObject disabledCapabilities() {
        JSONObject capabilities = availableCapabilities();
        capabilities.put("evalApiEnable", false);
        return capabilities;
    }

    private static JSONObject contractBrokenCapabilities() {
        JSONObject capabilities = availableCapabilities();
        capabilities.put("status", false);
        return capabilities;
    }

    private static JSONObject blankAiCapabilities() {
        JSONObject capabilities = availableCapabilities();
        capabilities.put("aiProfile", "");
        return capabilities;
    }

    /** 同时实现六个可注入来源，记录调用顺序并返回固定结论。 */
    private static final class RecordingSource implements BankStatusSource, RubricStatusSource,
            PersonaStatusSource, JudgeProfileSource, AiStatusSource, DataDirStatusSource {

        private final Stage stage;
        private final StageReport report;
        private final List<Stage> visited;

        RecordingSource(Stage stage, StageReport report, List<Stage> visited) {
            this.stage = stage;
            this.report = report;
            this.visited = visited;
        }

        @Override
        public StageReport report(String tier) {
            visited.add(stage);
            return report;
        }
    }

    /** 记录 info/warn/error 的假日志，用于断言告警次数与文案。 */
    private static final class RecordingLogger extends PluginLogger {

        private final List<String> infos = new ArrayList<String>();
        private final List<String> warns = new ArrayList<String>();
        private final List<String> errors = new ArrayList<String>();

        RecordingLogger() {
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
    }
}
