package org.moboxlab.mbb.eval.baseline;

import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.baseline.EvalBaselineService.BaselineOutcome;
import org.moboxlab.mbb.eval.score.EvalDimensionWeights;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 基线服务测试（§8.1、F9、F10、F11）。
 *
 * <p>覆盖自动基线只建一次、smoke 永不成为基线、显式重设的前置校验、active 切换与
 * superseded_by 指向、历史行保留、重复设置幂等、切换失败后的收敛与可解释失败。</p>
 */
public class EvalBaselineServiceTest {

    private static final String RUN_A = "r-20261025230000-full-aaaa";
    private static final String RUN_B = "r-20261025230100-full-bbbb";
    private static final String SMOKE = "r-20261025230200-smoke-cccc";
    private static final String STARTED_AT = "2026-10-25 23:00:00";
    private static final String ENDED_AT = "2026-10-25 23:12:00";
    private static final String[] DIMENSIONS = {"persona", "boundary", "memory", "knowledge", "style"};

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private EvalStorageFake storage;
    private EvalStore store;
    private EvalBaselineService service;

    @Before
    public void setUp() throws Exception {
        storage = new EvalStorageFake(new File(folder.getRoot(), "eval-baseline.db"));
        new EvalSchemaService(storage, null).ensureSchema();
        store = new EvalStore(storage, null);
        service = new EvalBaselineService(weights());
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    public void autoBaselineShouldBeCreatedOnceForFirstCompletedFullRun() {
        createCompletedFullRun(RUN_A, "judge-1");

        BaselineOutcome first = service.autoCreateIfFirstFull(store, store.findRun(RUN_A));

        assertEquals(BaselineOutcome.State.CREATED, first.state());
        assertTrue(first.applied());
        assertEquals("b-" + RUN_A, first.baselineId());

        JSONObject active = store.findActiveBaseline();
        assertNotNull(active);
        assertEquals("b-" + RUN_A, active.getString("baseline_id"));
        assertEquals(RUN_A, active.getString("run_id"));
        assertEquals(1, active.getIntValue("active"));
        assertEquals("system", active.getString("created_by"));
        assertEquals(EvalBaselineService.AUTO_REASON, active.getString("reason"));
        assertNull(active.getString("superseded_by"));

        // 冻结成绩：五维与总分均按 0..100 落库，valid_case_count 只计有效题
        assertEquals(79.6d, active.getDoubleValue("total_score"), 1.0e-6d);
        assertEquals(5, active.getIntValue("valid_case_count"));
        JSONObject dims = JSONObject.parseObject(active.getString("dimension_scores_json"));
        assertEquals(82.5d, dims.getDoubleValue("persona"), 1.0e-6d);
        assertEquals(73.0d, dims.getDoubleValue("boundary"), 1.0e-6d);
        assertEquals(91.0d, dims.getDoubleValue("memory"), 1.0e-6d);
        assertEquals(64.0d, dims.getDoubleValue("knowledge"), 1.0e-6d);
        assertEquals(88.0d, dims.getDoubleValue("style"), 1.0e-6d);
        JSONObject perf = JSONObject.parseObject(active.getString("perf_ref_json"));
        assertNotNull(perf);
        assertTrue(perf.containsKey("calls"));
        assertTrue(perf.containsKey("validCases"));

        // 九键原值冻结
        JSONObject run = store.findRun(RUN_A);
        for (String key : EvalRunComparability.KEY_NAMES) {
            assertEquals("第 " + key + " 键必须冻结 run 快照值",
                    run.getString(key), active.getString(key));
        }

        // 第二次调用（含只带 run_id 的入参）不再建行
        assertEquals(BaselineOutcome.State.UNCHANGED,
                service.autoCreateIfFirstFull(store, store.findRun(RUN_A)).state());
        JSONObject runIdOnly = new JSONObject();
        runIdOnly.put("run_id", RUN_A);
        assertEquals(BaselineOutcome.State.UNCHANGED,
                service.autoCreateIfFirstFull(store, runIdOnly).state());
        assertEquals(1, baselineRows().size());
        assertEquals(1, activeCount());
    }

    @Test
    public void autoBaselineShouldRefuseSmokeRun() {
        insertRun(SMOKE, EvalStore.TIER_SMOKE, EvalStore.STATUS_COMPLETED, "judge-1", 82.0d);

        BaselineOutcome auto = service.autoCreateIfFirstFull(store, store.findRun(SMOKE));

        assertEquals(BaselineOutcome.State.REJECTED, auto.state());
        assertTrue(auto.note(), auto.note().contains("smoke"));

        BaselineOutcome explicit = service.explicitSet(store, SMOKE, "想拿 smoke 当基线");
        assertEquals(BaselineOutcome.State.REJECTED, explicit.state());
        assertTrue(explicit.note(), explicit.note().contains("smoke"));
        assertTrue(baselineRows().isEmpty());
    }

    @Test
    public void autoBaselineShouldRefuseCompletedRunWithoutCaseResults() {
        insertRun(RUN_A, EvalStore.TIER_FULL, EvalStore.STATUS_COMPLETED, "judge-1");

        BaselineOutcome out = service.autoCreateIfFirstFull(store, store.findRun(RUN_A));

        assertEquals(BaselineOutcome.State.REJECTED, out.state());
        assertTrue(out.note(), out.note().contains("为空"));
        assertTrue(baselineRows().isEmpty());
    }

    @Test
    public void autoBaselineShouldNotOverrideExistingActiveBaseline() {
        createCompletedFullRun(RUN_A, "judge-1");
        createCompletedFullRun(RUN_B, "judge-2");
        assertEquals(BaselineOutcome.State.CREATED,
                service.autoCreateIfFirstFull(store, store.findRun(RUN_A)).state());

        BaselineOutcome second = service.autoCreateIfFirstFull(store, store.findRun(RUN_B));

        assertEquals(BaselineOutcome.State.UNCHANGED, second.state());
        assertEquals("b-" + RUN_A, store.findActiveBaseline().getString("baseline_id"));
        assertEquals(1, baselineRows().size());
    }

    @Test
    public void explicitSetShouldRequireReason() {
        createCompletedFullRun(RUN_A, "judge-1");

        String[] reasons = {null, "", "   "};
        for (String reason : reasons) {
            BaselineOutcome out = service.explicitSet(store, RUN_A, reason);
            assertEquals("理由为空必须拒绝", BaselineOutcome.State.REJECTED, out.state());
            assertTrue(out.note(), out.note().contains("理由"));
        }
        assertTrue(baselineRows().isEmpty());
    }

    @Test
    public void explicitSetShouldRejectUnfinishedRun() {
        insertRun(RUN_A, EvalStore.TIER_FULL, EvalStore.STATUS_RUNNING, "judge-1", 82.0d);

        BaselineOutcome running = service.explicitSet(store, RUN_A, "测试未完成拒绝");
        assertEquals(BaselineOutcome.State.REJECTED, running.state());
        assertTrue(running.note(), running.note().contains("未完成"));

        insertRun(RUN_B, EvalStore.TIER_FULL, EvalStore.STATUS_RUNNING, "judge-1", 82.0d);
        assertEquals(1, store.casFailed(RUN_B, ENDED_AT, 720000L, "judge 超时"));
        BaselineOutcome failed = service.explicitSet(store, RUN_B, "失败 run 不能做基线");
        assertEquals(BaselineOutcome.State.REJECTED, failed.state());
        assertTrue(failed.note(), failed.note().contains("未完成"));

        assertTrue(baselineRows().isEmpty());
    }

    @Test
    public void explicitSetShouldRejectUnknownRun() {
        BaselineOutcome out = service.explicitSet(store, "r-20261025230900-full-zzzz", "查无此 run");

        assertEquals(BaselineOutcome.State.REJECTED, out.state());
        assertTrue(out.note(), out.note().contains("不存在"));
        assertTrue(baselineRows().isEmpty());
    }

    @Test
    public void explicitSetShouldSwitchActiveAndPointSupersededBy() {
        createCompletedFullRun(RUN_A, "judge-1");
        createCompletedFullRun(RUN_B, "judge-2");
        assertEquals(BaselineOutcome.State.CREATED,
                service.autoCreateIfFirstFull(store, store.findRun(RUN_A)).state());

        BaselineOutcome out = service.explicitSet(store, RUN_B, "换 judge 模型后重建基线");

        assertEquals(BaselineOutcome.State.CREATED, out.state());
        JSONObject active = store.findActiveBaseline();
        assertEquals("b-" + RUN_B, active.getString("baseline_id"));
        assertEquals("owner", active.getString("created_by"));
        assertEquals("换 judge 模型后重建基线", active.getString("reason"));
        assertNull(active.getString("superseded_by"));

        JSONObject oldRow = store.findBaseline("b-" + RUN_A);
        assertEquals(0, oldRow.getIntValue("active"));
        assertEquals("b-" + RUN_B, oldRow.getString("superseded_by"));
        // 历史行保留原九键与成绩，不随新基线改写
        assertEquals("judge-1", oldRow.getString("judge_model"));
        assertEquals(79.6d, oldRow.getDoubleValue("total_score"), 1.0e-6d);

        assertEquals(2, baselineRows().size());
        assertEquals(1, activeCount());
    }

    @Test
    public void explicitSetSameRunTwiceShouldBeIdempotent() {
        createCompletedFullRun(RUN_A, "judge-1");

        BaselineOutcome first = service.explicitSet(store, RUN_A, "首次显式设基线");
        BaselineOutcome second = service.explicitSet(store, RUN_A, "再次设置同一 run");

        assertEquals(BaselineOutcome.State.CREATED, first.state());
        assertEquals(BaselineOutcome.State.UNCHANGED, second.state());
        assertTrue(second.note(), second.note().contains("幂等"));
        assertEquals(1, baselineRows().size());
        assertEquals(1, activeCount());
        JSONObject row = store.findBaseline("b-" + RUN_A);
        assertEquals(1, row.getIntValue("active"));
        assertNull(row.getString("superseded_by"));
    }

    @Test
    public void reactivatingHistoricalRowShouldReuseIt() {
        createCompletedFullRun(RUN_A, "judge-1");
        createCompletedFullRun(RUN_B, "judge-2");
        service.autoCreateIfFirstFull(store, store.findRun(RUN_A));
        assertEquals(BaselineOutcome.State.CREATED,
                service.explicitSet(store, RUN_B, "B 先成为基线").state());

        BaselineOutcome back = service.explicitSet(store, RUN_A, "回退到 A 的基线");

        assertEquals(BaselineOutcome.State.REACTIVATED, back.state());
        assertEquals("b-" + RUN_A, store.findActiveBaseline().getString("baseline_id"));
        assertEquals(1, store.findBaseline("b-" + RUN_A).getIntValue("active"));
        JSONObject rowB = store.findBaseline("b-" + RUN_B);
        assertEquals(0, rowB.getIntValue("active"));
        assertEquals("b-" + RUN_A, rowB.getString("superseded_by"));
        assertEquals(2, baselineRows().size());
        assertEquals(1, activeCount());
    }

    @Test
    public void switchShouldConvergeWhenSupersedeFailsOnce() {
        createCompletedFullRun(RUN_A, "judge-1");
        createCompletedFullRun(RUN_B, "judge-2");
        service.autoCreateIfFirstFull(store, store.findRun(RUN_A));

        // 第②步失败一次：置旧 active=0 的语句未执行，重跑后必须收敛
        storage.failUpdateContaining("superseded_by", 1);

        BaselineOutcome out = service.explicitSet(store, RUN_B, "第②步失败后重跑");

        assertEquals(BaselineOutcome.State.CREATED, out.state());
        assertEquals("b-" + RUN_B, store.findActiveBaseline().getString("baseline_id"));
        assertEquals(0, store.findBaseline("b-" + RUN_A).getIntValue("active"));
        assertEquals("b-" + RUN_B, store.findBaseline("b-" + RUN_A).getString("superseded_by"));
        assertEquals(1, activeCount());
    }

    @Test
    public void persistentSwitchFailureShouldKeepOldActiveAndReportFailure() {
        createCompletedFullRun(RUN_A, "judge-1");
        createCompletedFullRun(RUN_B, "judge-2");
        service.autoCreateIfFirstFull(store, store.findRun(RUN_A));

        // 只让第②步（退役旧 active）失败，第③步因唯一索引也无法成功；重跑两次都卡在这里
        storage.failUpdateContaining("SET active = 0, superseded_by", 4);

        BaselineOutcome out = service.explicitSet(store, RUN_B, "持续失败不换基线");

        assertEquals(BaselineOutcome.State.FAILED, out.state());
        assertTrue(out.failed());
        assertNotNull(out.note());
        // 旧基线仍是唯一 active，库内不会长期无基线
        assertEquals("b-" + RUN_A, store.findActiveBaseline().getString("baseline_id"));
        assertEquals(1, activeCount());
        // 新行已插入但未激活，重跑可继续
        assertEquals(2, baselineRows().size());
        assertEquals(0, store.findBaseline("b-" + RUN_B).getIntValue("active"));
    }

    @Test
    public void insertFailureShouldBeReportedAndLeaveNoRows() {
        createCompletedFullRun(RUN_A, "judge-1");

        // insertBaseline 也走 storage.update，连续两次失败即放弃
        storage.failNextUpdate(2);

        BaselineOutcome out = service.explicitSet(store, RUN_A, "写库失败");

        assertEquals(BaselineOutcome.State.FAILED, out.state());
        assertTrue(out.note(), out.note().contains("新增基线行"));
        assertTrue(baselineRows().isEmpty());
        assertNull(store.findActiveBaseline());
    }

    // ---------------------------------------------------------------- 辅助

    private void createCompletedFullRun(String runId, String judgeModel) {
        insertRun(runId, EvalStore.TIER_FULL, EvalStore.STATUS_COMPLETED, judgeModel, 82.5d, 73.0d, 91.0d, 64.0d, 88.0d);
    }

    private void insertRun(String runId, String tier, String status, String judgeModel, double... scores) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        row.put("tier", tier);
        row.put("started_at", STARTED_AT);
        row.put("bank_hash", "bank-hash-1");
        row.put("rubric_version", "rubric-1");
        row.put("persona_hash", "persona-hash-1");
        row.put("config_fingerprint", "fingerprint-1");
        row.put("eval_profile", "eval-profile-1");
        row.put("judge_profile", "judge-profile-1");
        row.put("judge_model", judgeModel);
        row.put("generation_model", "gen-1");
        row.put("eval_api_version", "api-1");
        row.put("config_snapshot_json", "{\"maxTokens\":512}");
        assertTrue("run 行写入失败：" + runId, store.insertRunning(row));

        for (int i = 0; i < scores.length; i++) {
            JSONObject caseRow = new JSONObject();
            caseRow.put("run_id", runId);
            caseRow.put("case_id", "case-" + (i + 1));
            caseRow.put("dimension", DIMENSIONS[i % DIMENSIONS.length]);
            caseRow.put("status", "ok");
            caseRow.put("score", scores[i]);
            caseRow.put("max_score", 100);
            assertEquals(EvalStore.WriteResult.APPLIED, store.insertCaseResult(caseRow));
        }

        if (EvalStore.STATUS_COMPLETED.equals(status)) {
            assertEquals(1, store.casCompleted(runId, ENDED_AT, 720000L, null));
        } else if (EvalStore.STATUS_FAILED.equals(status)) {
            assertEquals(1, store.casFailed(runId, ENDED_AT, 720000L, "失败"));
        }
    }

    private List<JSONObject> baselineRows() {
        return store.findBaselines(Integer.MAX_VALUE);
    }

    private int activeCount() {
        int count = 0;
        for (JSONObject row : baselineRows()) {
            if (row.getIntValue("active") == 1) {
                count++;
            }
        }
        return count;
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
}
