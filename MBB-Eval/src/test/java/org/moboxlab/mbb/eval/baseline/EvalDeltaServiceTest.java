package org.moboxlab.mbb.eval.baseline;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.score.EvalDimensionWeights;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * delta 服务测试（§8.4、§8.5、§15.6 F16 / F17 / F18）。
 *
 * <p>覆盖：同题配对只取两侧 ok、维度 bootstrap 区间与显著四态、固定种子复现、
 * 最小步长门槛、ok→error / ok→invalid 单列不可信变更、题级计数与翻转题统计、
 * 三条硬门槛只改标注不改分、五类附加标注、基线行比较。</p>
 */
public class EvalDeltaServiceTest {

    private static final String RUN_A = "r-20261025230000-full-aaaa";
    private static final String RUN_B = "r-20261025230100-full-bbbb";
    private static final String STARTED_AT = "2026-10-25 23:00:00";
    private static final String ENDED_AT = "2026-10-25 23:12:00";
    private static final String[] DIMENSIONS = {"persona", "boundary", "memory", "knowledge", "style"};

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;
    private EvalDeltaService service;

    @Before
    public void setUp() throws Exception {
        storage = new EvalStorageFake(new File(folder.getRoot(), "eval-delta.db"));
        new EvalSchemaService(storage, null).ensureSchema();
        store = new EvalStore(storage, null);
        service = new EvalDeltaService(store, weights());
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    // ------------------------------------------------------------ F16

    @Test
    public void comparableRunPairShouldPairOkCasesAndComputeDimensionBootstrap() {
        insertRun(RUN_A, "judge-1", "gen-1", "rubric-1", flatCases(60.0d, 40));
        insertRun(RUN_B, "judge-1", "gen-1", "rubric-1", improvingCases());

        EvalDeltaResult result = service.compareRuns(RUN_A, RUN_B);

        assertTrue(result.available());
        assertTrue(result.comparable());
        assertTrue(result.credible());
        assertEquals("可信", result.credibilityLabel());
        assertEquals(40, result.pairedCount());
        assertEquals(40, result.betterCount());
        assertEquals(0, result.worseCount());
        assertEquals(0, result.sameCount());
        assertTrue(result.untrustedChanges().isEmpty());
        assertEquals(0, result.flippedTotal());

        // 总分只报点值：基线 60，当前五维均值 81.5，delta 21.5
        assertEquals(60.0d, result.baselineTotal().doubleValue(), 1.0e-9d);
        assertEquals(81.5d, result.currentTotal().doubleValue(), 1.0e-9d);
        assertEquals(21.5d, result.totalDelta().doubleValue(), 1.0e-9d);
        assertEquals(2.5d, result.totalMinimumStep(), 1.0e-12d);

        for (String dimensionId : DIMENSIONS) {
            EvalDeltaResult.DimensionDelta dimension = result.dimension(dimensionId);
            assertNotNull(dimensionId, dimension);
            assertEquals(dimensionId, 8, dimension.pairedCount());
            assertEquals(dimensionId, 21.5d, dimension.delta().doubleValue(), 1.0e-9d);
            assertNotNull(dimensionId + " 必须有 ciLow", dimension.ciLow());
            assertNotNull(dimensionId + " 必须有 ciHigh", dimension.ciHigh());
            assertTrue(dimensionId, dimension.ciLow().doubleValue() <= 21.5d);
            assertTrue(dimensionId, dimension.ciHigh().doubleValue() >= 21.5d);
            assertEquals(dimensionId, 12.5d, dimension.minimumStep(), 1.0e-12d);
            assertEquals(dimensionId, EvalDeltaResult.Significance.SIGNIFICANT_UP,
                    dimension.significance());
            assertNull(dimension.note());
        }
        assertNoAnnotation(result.annotations(), "样本不足");
    }

    @Test
    public void repeatedComparisonShouldProduceIdenticalIntervals() {
        insertRun(RUN_A, "judge-1", "gen-1", "rubric-1", flatCases(60.0d, 40));
        insertRun(RUN_B, "judge-1", "gen-1", "rubric-1", improvingCases());

        EvalDeltaResult first = service.compareRuns(RUN_A, RUN_B);
        EvalDeltaResult second = new EvalDeltaService(store, weights()).compareRuns(RUN_A, RUN_B);

        for (String dimensionId : DIMENSIONS) {
            EvalDeltaResult.DimensionDelta a = first.dimension(dimensionId);
            EvalDeltaResult.DimensionDelta b = second.dimension(dimensionId);
            assertEquals(dimensionId, a.delta().doubleValue(), b.delta().doubleValue(), 0.0d);
            assertEquals(dimensionId, a.ciLow().doubleValue(), b.ciLow().doubleValue(), 0.0d);
            assertEquals(dimensionId, a.ciHigh().doubleValue(), b.ciHigh().doubleValue(), 0.0d);
            assertEquals(dimensionId, a.significance(), b.significance());
        }
        assertEquals(first.totalDelta().doubleValue(), second.totalDelta().doubleValue(), 0.0d);
    }

    @Test
    public void smallPairSampleShouldReportPointDeltaWithoutCi() {
        insertRun(RUN_A, "judge-1", "gen-1", "rubric-1", flatCases(60.0d, 19));
        insertRun(RUN_B, "judge-1", "gen-1", "rubric-1", flatCases(70.0d, 19));

        EvalDeltaResult result = service.compareRuns(RUN_A, RUN_B);

        assertEquals(19, result.pairedCount());
        assertEquals(10.0d, result.totalDelta().doubleValue(), 1.0e-9d);
        for (String dimensionId : DIMENSIONS) {
            EvalDeltaResult.DimensionDelta dimension = result.dimension(dimensionId);
            assertEquals(dimensionId, 10.0d, dimension.delta().doubleValue(), 1.0e-9d);
            assertNull(dimensionId + " 样本不足时不出区间", dimension.ciLow());
            assertNull(dimensionId, dimension.ciHigh());
            assertEquals(dimensionId, EvalDeltaResult.Significance.NOT_COMPARABLE,
                    dimension.significance());
            assertTrue(dimensionId, dimension.note().contains("样本不足"));
        }
        assertAnnotation(result.annotations(), "样本不足：配对 n=19");
    }

    // ------------------------------------------------------------ F17

    @Test
    public void okToErrorAndOkToInvalidShouldBeListedAsUntrustedChanges() {
        insertRun(RUN_A, "judge-1", "gen-1", "rubric-1", flatCases(70.0d, 40));

        List<CaseSpec> current = new ArrayList<CaseSpec>();
        current.add(CaseSpec.status("case-1", dimensionOf(1), "error"));
        current.add(CaseSpec.status("case-2", dimensionOf(2), "invalid"));
        current.add(CaseSpec.ok("case-3", dimensionOf(3), 85.0d));
        current.add(CaseSpec.ok("case-4", dimensionOf(4), 65.0d));
        current.add(CaseSpec.status("case-5", dimensionOf(5), "vetoed"));
        for (int i = 6; i <= 40; i++) {
            current.add(CaseSpec.ok("case-" + i, dimensionOf(i), 70.0d));
        }
        insertRun(RUN_B, "judge-1", "gen-1", "rubric-1", current);

        EvalDeltaResult result = service.compareRuns(RUN_A, RUN_B);

        // 两道不可信变更不参与配对，也不并入变差；vetoed 同样不配对
        assertEquals(37, result.pairedCount());
        assertEquals(1, result.betterCount());
        assertEquals(1, result.worseCount());
        assertEquals(35, result.sameCount());
        assertEquals(2, result.untrustedChangeCount());
        assertEquals(1, result.okToErrorCount());
        assertEquals(1, result.okToInvalidCount());
        assertNotNull(result.totalDelta());

        List<EvalDeltaResult.CaseChange> changes = result.untrustedChanges();
        assertEquals("case-1", changes.get(0).caseId());
        assertEquals("persona", changes.get(0).dimensionId());
        assertEquals("ok→error", changes.get(0).label());
        assertEquals("case-2", changes.get(1).caseId());
        assertEquals("boundary", changes.get(1).dimensionId());
        assertEquals("ok→invalid", changes.get(1).label());
    }

    @Test
    public void flippedCasesShouldBeCountedFromK3Samples() {
        insertRun(RUN_A, "judge-1", "gen-1", "rubric-1", flatCases(70.0d, 20));

        List<CaseSpec> current = new ArrayList<CaseSpec>();
        for (int i = 1; i <= 20; i++) {
            if (i <= 3) {
                current.add(CaseSpec.okK3("case-" + i, dimensionOf(i), 70.0d, 3, 2, 2));
            } else {
                current.add(CaseSpec.okK3("case-" + i, dimensionOf(i), 70.0d, 3, 3, 3));
            }
        }
        insertRun(RUN_B, "judge-1", "gen-1", "rubric-1", current);

        EvalDeltaResult result = service.compareRuns(RUN_A, RUN_B);

        assertEquals(20, result.pairedCount());
        assertEquals(20, result.sameCount());
        assertEquals(20, result.flippedTotal());
        assertEquals(17, result.k3SameCount());
        assertEquals(0.85d, result.k3AllSameRate(), 1.0e-12d);
        assertEquals(Arrays.asList("case-1", "case-2", "case-3"), result.flippedCaseIds());
        assertNoAnnotation(result.annotations(), "judge 抖动大");
    }

    // ------------------------------------------------------------ F18

    @Test
    public void insufficientValidCasesShouldMarkWholeReportUntrusted() {
        insertRun(RUN_A, "judge-1", "gen-1", "rubric-1", flatCases(80.0d, 40));

        List<CaseSpec> current = new ArrayList<CaseSpec>();
        for (int i = 1; i <= 40; i++) {
            current.add(i <= 10
                    ? CaseSpec.status("case-" + i, dimensionOf(i), "error")
                    : CaseSpec.ok("case-" + i, dimensionOf(i), 80.0d));
        }
        insertRun(RUN_B, "judge-1", "gen-1", "rubric-1", current);

        EvalDeltaResult result = service.compareRuns(RUN_A, RUN_B);

        assertFalse(result.credible());
        assertEquals("不可信", result.credibilityLabel());
        assertFalse(result.trendAllowed());
        assertEquals("不可比", result.conclusion());
        assertAnnotation(result.annotations(), "不可信：有效题 30/40（低于 80%）");
        assertAnnotation(result.annotations(), "整份报告不可信，不给趋势结论");

        // 三条门槛只改标注不改分：两侧有效题都是 80 分，总分点值仍是 80
        assertEquals(80.0d, result.currentTotal().doubleValue(), 1.0e-9d);
        assertEquals(0.0d, result.totalDelta().doubleValue(), 1.0e-9d);
        assertEquals(EvalDeltaResult.Significance.NOT_COMPARABLE,
                result.dimension("persona").significance());
        assertNull(result.dimension("persona").ciLow());
    }

    @Test
    public void nonOkRateAboveTenPercentShouldMarkWholeReportUntrusted() {
        insertRun(RUN_A, "judge-1", "gen-1", "rubric-1", flatCases(80.0d, 40));

        List<CaseSpec> current = new ArrayList<CaseSpec>();
        for (int i = 1; i <= 40; i++) {
            current.add(i <= 5
                    ? CaseSpec.status("case-" + i, dimensionOf(i), "error")
                    : CaseSpec.ok("case-" + i, dimensionOf(i), 80.0d));
        }
        insertRun(RUN_B, "judge-1", "gen-1", "rubric-1", current);

        EvalDeltaResult result = service.compareRuns(RUN_A, RUN_B);

        assertFalse(result.credible());
        assertEquals("不可信", result.credibilityLabel());
        assertFalse(result.trendAllowed());
        assertAnnotation(result.annotations(), "判分不可信：非 ok 率 12.5% 超过 10%");
        assertAnnotation(result.annotations(), "整份报告不可信，不给趋势结论");
        assertNoAnnotation(result.annotations(), "有效题");
        // 有效题占比 87.5% 达标，只有非 ok 率越线；分数仍按 ok 题计算
        assertEquals(80.0d, result.currentTotal().doubleValue(), 1.0e-9d);
        assertEquals(0.0d, result.totalDelta().doubleValue(), 1.0e-9d);
    }

    @Test
    public void rubricChangeShouldSuppressDeltaAndFlagIncomparable() {
        insertRun(RUN_A, "judge-1", "gen-1", "rubric-1", flatCases(80.0d, 40));
        insertRun(RUN_B, "judge-1", "gen-1", "rubric-2", flatCases(80.0d, 40));

        EvalDeltaResult result = service.compareRuns(RUN_A, RUN_B);

        assertFalse(result.comparable());
        assertTrue(result.rubricChanged());
        assertTrue(result.differingKeys().contains("rubric_version"));
        assertTrue(result.missingKeys().isEmpty());
        assertNull("rubric 已变时不出总分 delta", result.totalDelta());
        assertEquals("不可比", result.conclusion());
        assertFalse(result.trendAllowed());
        assertAnnotation(result.annotations(), "rubric 已变，不出 delta");
        assertAnnotation(result.annotations(), "差异字段：rubric_version");

        EvalDeltaResult.DimensionDelta dimension = result.dimension("persona");
        assertNull(dimension.delta());
        assertEquals(EvalDeltaResult.Significance.NOT_COMPARABLE, dimension.significance());
        // 分数仍展示，只是不出 delta
        assertEquals(80.0d, result.baselineTotal().doubleValue(), 1.0e-9d);
        assertEquals(80.0d, result.currentTotal().doubleValue(), 1.0e-9d);
    }

    // ------------------------------------------------------------ 附加标注

    @Test
    public void sameFamilyJudgeShouldBeAnnotated() {
        insertRun(RUN_A, "qwen-max", "qwen2.5", "rubric-1", flatCases(80.0d, 40));
        insertRun(RUN_B, "qwen-max", "qwen2.5", "rubric-1", flatCases(80.0d, 40));

        EvalDeltaResult result = service.compareRuns(RUN_A, RUN_B);

        assertTrue(result.comparable());
        assertAnnotation(result.annotations(), "同家族 judge，分数仅作趋势");
    }

    @Test
    public void calibrationEvidenceFailuresShouldBeAnnotated() {
        insertRun(RUN_A, "judge-1", "gen-1", "rubric-1", flatCases(80.0d, 20));
        insertRun(RUN_B, "judge-1", "gen-1", "rubric-1", flatCases(80.0d, 20));
        setCalibration(RUN_B, "{\"evidenceFailures\":3,\"evidenceTotal\":20}");

        EvalDeltaResult result = service.compareRuns(RUN_A, RUN_B);

        assertTrue(result.credible());
        assertAnnotation(result.annotations(), "rubric 不自证");
        assertAnnotation(result.annotations(), "15.0%");
    }

    @Test
    public void judgeJitterShouldBeAnnotated() {
        insertRun(RUN_A, "judge-1", "gen-1", "rubric-1", flatCases(70.0d, 20));

        List<CaseSpec> current = new ArrayList<CaseSpec>();
        for (int i = 1; i <= 20; i++) {
            if (i <= 10) {
                current.add(CaseSpec.okK3("case-" + i, dimensionOf(i), 70.0d, 3, 3, 3));
            } else {
                current.add(CaseSpec.okK3("case-" + i, dimensionOf(i), 70.0d, 3, 2, 2));
            }
        }
        insertRun(RUN_B, "judge-1", "gen-1", "rubric-1", current);

        EvalDeltaResult result = service.compareRuns(RUN_A, RUN_B);

        assertEquals(10, result.k3SameCount());
        assertEquals(20, result.flippedTotal());
        assertAnnotation(result.annotations(), "judge 抖动大：k=3 全同率 50.0% 低于 70%");
        // 附加标注只提示，不把整份报告打成不可信
        assertTrue(result.credible());
    }

    @Test
    public void cacheAndBudgetShouldBeAnnotated() {
        insertRun(RUN_A, "judge-1", "gen-1", "rubric-1", flatCases(80.0d, 20));

        List<CaseSpec> current = new ArrayList<CaseSpec>();
        current.add(CaseSpec.cachedOk("case-1", dimensionOf(1), 80.0d, 1));
        for (int i = 2; i <= 20; i++) {
            current.add(CaseSpec.ok("case-" + i, dimensionOf(i), 80.0d));
        }
        insertRun(RUN_B, "judge-1", "gen-1", "rubric-1", current);
        setCalls(RUN_B, 100, 100);

        EvalDeltaResult result = service.compareRuns(RUN_A, RUN_B);

        assertAnnotation(result.annotations(), "缓存键疑似配置错误，性能与 token 不可用");
        assertAnnotation(result.annotations(), "预算耗尽，样本不完整");
        // 附加标注不改分也不改可信度
        assertTrue(result.credible());
        assertTrue(result.trendAllowed());
        assertEquals(0.0d, result.totalDelta().doubleValue(), 1.0e-9d);
    }

    // ------------------------------------------------------------ 基线与降级

    @Test
    public void missingBaselineShouldReturnUnavailableResult() {
        insertRun(RUN_A, "judge-1", "gen-1", "rubric-1", flatCases(80.0d, 20));

        EvalDeltaResult result = service.compareToActiveBaseline(RUN_A);

        assertFalse(result.available());
        assertFalse(result.comparable());
        assertFalse(result.trendAllowed());
        assertEquals("不可比", result.conclusion());
        assertNotNull(result.note());
        assertTrue(result.note(), result.note().contains("未设置基线"));
        assertAnnotation(result.annotations(), "未设置基线");
        assertEquals(0, result.pairedCount());
    }

    @Test
    public void compareToActiveBaselineShouldUseFrozenBaselineRow() {
        insertRun(RUN_A, "judge-1", "gen-1", "rubric-1", flatCases(60.0d, 40));
        insertRun(RUN_B, "judge-1", "gen-1", "rubric-1", improvingCases());
        EvalBaselineService baselineService = new EvalBaselineService(weights());
        assertEquals(EvalBaselineService.BaselineOutcome.State.CREATED,
                baselineService.autoCreateIfFirstFull(store, store.findRun(RUN_A)).state());

        EvalDeltaResult result = service.compareToActiveBaseline(RUN_B);

        assertTrue(result.available());
        assertTrue(result.comparable());
        assertTrue(result.credible());
        assertEquals(RUN_A, result.baselineRunId());
        assertEquals(60.0d, result.baselineTotal().doubleValue(), 1.0e-9d);
        assertEquals(81.5d, result.currentTotal().doubleValue(), 1.0e-9d);
        assertEquals(21.5d, result.totalDelta().doubleValue(), 1.0e-9d);
        assertEquals(40, result.pairedCount());
        assertNotNull(result.dimension("persona").ciLow());
    }

    // ------------------------------------------------------------ 辅助

    private void insertRun(String runId, String judgeModel, String generationModel,
                           String rubricVersion, List<CaseSpec> cases) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("tier", EvalStore.TIER_FULL);
        row.put("started_at", STARTED_AT);
        row.put("bank_hash", "bank-hash-1");
        row.put("rubric_version", rubricVersion);
        row.put("persona_hash", "persona-hash-1");
        row.put("config_fingerprint", "fingerprint-1");
        row.put("eval_profile", "eval-profile-1");
        row.put("judge_profile", "judge-profile-1");
        row.put("judge_model", judgeModel);
        row.put("generation_model", generationModel);
        row.put("eval_api_version", "api-1");
        row.put("config_snapshot_json", "{\"maxTokens\":512}");
        assertTrue("run 行写入失败：" + runId, store.insertRunning(row));

        for (CaseSpec spec : cases) {
            JSONObject caseRow = new JSONObject();
            caseRow.put("run_id", runId);
            caseRow.put("case_id", spec.caseId);
            caseRow.put("dimension", spec.dimension);
            caseRow.put("status", spec.status);
            if (spec.score != null) {
                caseRow.put("score", spec.score);
            }
            caseRow.put("max_score", Integer.valueOf(100));
            if (spec.kUsed > 0) {
                caseRow.put("k_used", Integer.valueOf(spec.kUsed));
                caseRow.put("sample_count", Integer.valueOf(spec.kUsed));
            }
            if (spec.cachedCount > 0) {
                caseRow.put("cached_count", Integer.valueOf(spec.cachedCount));
            }
            if (spec.detailJson != null) {
                caseRow.put("detail_json", spec.detailJson);
            }
            assertEquals("用例写入失败：" + spec.caseId,
                    EvalStore.WriteResult.APPLIED, store.insertCaseResult(caseRow));
        }
        assertEquals(1, store.casCompleted(runId, ENDED_AT, 720000L, null));
    }

    private void setCalibration(String runId, String calibrationJson) {
        assertEquals(1, storage.update("UPDATE plugin_mbb_eval_run SET calibration_json = ?"
                + " WHERE run_id = ?", calibrationJson, runId));
    }

    private void setCalls(String runId, int callsUsed, int callsBudget) {
        assertEquals(1, storage.update("UPDATE plugin_mbb_eval_run SET calls_used = ?, calls_budget = ?"
                + " WHERE run_id = ?", Integer.valueOf(callsUsed), Integer.valueOf(callsBudget), runId));
    }

    private static List<CaseSpec> flatCases(double score, int count) {
        List<CaseSpec> cases = new ArrayList<CaseSpec>();
        for (int i = 1; i <= count; i++) {
            cases.add(CaseSpec.ok("case-" + i, dimensionOf(i), score));
        }
        return cases;
    }

    private static List<CaseSpec> improvingCases() {
        List<CaseSpec> cases = new ArrayList<CaseSpec>();
        for (int i = 1; i <= 40; i++) {
            cases.add(CaseSpec.ok("case-" + i, dimensionOf(i), 80.0d + (i % 4)));
        }
        return cases;
    }

    private static String dimensionOf(int indexOneBased) {
        return DIMENSIONS[(indexOneBased - 1) % DIMENSIONS.length];
    }

    private static String samples(int... labels) {
        JSONArray samples = new JSONArray();
        for (int label : labels) {
            JSONObject sample = new JSONObject();
            sample.put("label", Integer.valueOf(label));
            samples.add(sample);
        }
        JSONObject detail = new JSONObject();
        detail.put("samples", samples);
        return detail.toJSONString();
    }

    private static void assertAnnotation(List<String> annotations, String fragment) {
        for (String note : annotations) {
            if (note != null && note.contains(fragment)) {
                return;
            }
        }
        fail("缺少标注：" + fragment + "，实际：" + annotations);
    }

    private static void assertNoAnnotation(List<String> annotations, String fragment) {
        for (String note : annotations) {
            if (note != null && note.contains(fragment)) {
                fail("不应出现标注：" + fragment + "，实际：" + annotations);
            }
        }
    }

    private static EvalDimensionWeights weights() {
        Map<String, Double> map = new LinkedHashMap<String, Double>();
        map.put("persona", 0.30d);
        map.put("boundary", 0.25d);
        map.put("memory", 0.20d);
        map.put("knowledge", 0.15d);
        map.put("style", 0.10d);
        return EvalDimensionWeights.of(map);
    }

    /** 一条用例结果的构造参数。 */
    private static final class CaseSpec {

        private final String caseId;
        private final String dimension;
        private final String status;
        private final Double score;
        private final int kUsed;
        private final String detailJson;
        private final int cachedCount;

        private CaseSpec(String caseId, String dimension, String status, Double score,
                         int kUsed, String detailJson, int cachedCount) {
            this.caseId = caseId;
            this.dimension = dimension;
            this.status = status;
            this.score = score;
            this.kUsed = kUsed;
            this.detailJson = detailJson;
            this.cachedCount = cachedCount;
        }

        private static CaseSpec ok(String caseId, String dimension, double score) {
            return new CaseSpec(caseId, dimension, "ok", Double.valueOf(score), 0, null, 0);
        }

        private static CaseSpec okK3(String caseId, String dimension, double score, int... labels) {
            return new CaseSpec(caseId, dimension, "ok", Double.valueOf(score), 3,
                    samples(labels), 0);
        }

        private static CaseSpec cachedOk(String caseId, String dimension, double score,
                                         int cachedCount) {
            return new CaseSpec(caseId, dimension, "ok", Double.valueOf(score), 0, null,
                    cachedCount);
        }

        private static CaseSpec status(String caseId, String dimension, String status) {
            return new CaseSpec(caseId, dimension, status, null, 0, null, 0);
        }
    }
}
