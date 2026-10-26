package org.moboxlab.mbb.eval.baseline;

import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.List;

import org.moboxlab.mbb.eval.metrics.EvalMetrics;
import org.moboxlab.mbb.eval.metrics.EvalMetricsService;
import org.moboxlab.mbb.eval.score.EvalCaseScore;
import org.moboxlab.mbb.eval.score.EvalDimensionScore;
import org.moboxlab.mbb.eval.score.EvalDimensionWeights;
import org.moboxlab.mbb.eval.score.EvalRunScore;
import org.moboxlab.mbb.eval.score.EvalScoreAggregator;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.mbb.eval.store.EvalStore.RowSet;
import org.moboxlab.mbb.eval.store.EvalStore.WriteResult;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * 基线服务（规格 #50 §8.1、§8.2、§4.2.5，验收 F9 / F10 / F11 / F13）。
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li>{@link #autoCreateIfFirstFull}：库内没有 active 基线时，把第一个成功且完整的 full run
 *       冻结为基线，{@code created_by=system}，理由为「首个成功 full run 自动建立」。自动建立
 *       只发生一次；库里已有 active 基线时直接返回 UNCHANGED，绝不覆盖。</li>
 *   <li>{@link #explicitSet}：OWNER 显式重设基线（{@code /eval baseline set <runId> [理由]}），
 *       理由必填，只接受 COMPLETED 的 full run；smoke 一律拒绝（F10）。</li>
 * </ul>
 *
 * <p>active 切换按 §8.1 的三步写法，逐条单语句、**无事务**：① 新行以 active=0 插入；
 * ② {@link EvalStore#supersedeActiveBaseline} 把旧 active 置 0 并写 superseded_by=新 id；
 * ③ {@link EvalStore#activateBaseline} 把新行置 1。任一步返回 NOT_EXECUTED 时本类先回读库内
 * 真实状态，再重跑整段（最多两次）；仍不收敛则按「切换失败不得长期无基线」修复：库内没有
 * active 行时取 created_at 最新的基线行激活。整段流程可重跑且幂等——同一 run 的 baseline_id
 * 由 run_id 派生，重复 set 命中同一主键，回读判定为已生效后返回 UNCHANGED。</p>
 *
 * <p>冻结内容（§4.2.5）：五维分与总分（0..100，D-4 口径）、有效题数、九键快照、性能参照
 * （扁平 perf_ref_json）、created_at / created_by / reason。基线行永不删除，历史行保留原
 * 九键值，因此重设后新旧基线天然不可比（§8.2）。</p>
 *
 * <p>两点口径说明：</p>
 * <ul>
 *   <li>校准自证（§6.7.4）结果写入 **run 行** 的 calibration_json（G-2 负责），基线表没有该列，
 *       本片不写、也不能凭空补一个假值。</li>
 *   <li>「成功且完整」在本片按 tier=full、status=COMPLETED、case 结果行非空且行数与库内计数
 *       一致、每行 status ∈ {ok,vetoed,error,invalid} 判定；40 题硬数量由题库决定，不在服务里
 *       硬编码。</li>
 * </ul>
 */
public final class EvalBaselineService {

    /** 自动基线的 created_by。 */
    public static final String CREATED_BY_SYSTEM = "system";
    /** 显式重设的缺省 created_by（命令层已保证 OWNER 权限）。 */
    public static final String CREATED_BY_OWNER = "owner";
    /** 自动建立基线的理由（F9 要求「reason 为自动建立说明」）。 */
    public static final String AUTO_REASON = "首个成功 full run 自动建立";
    /** baseline_id 派生前缀：同一 run 只有一个基线行，重复 set 天然幂等。 */
    public static final String BASELINE_ID_PREFIX = "b-";

    /** case 结果行的封闭四态（§4.2.2）。 */
    private static final String[] CASE_STATUSES = {"ok", "vetoed", "error", "invalid"};
    /** 切换流程最多重跑两次；两次仍不收敛按失败处理并尝试修复。 */
    private static final int MAX_SWITCH_ATTEMPTS = 2;
    /**
     * 按 run_id 兜底查找基线行的扫描上限。基线行永不清理，用正整数上限近似「全量」，
     * 避免给 store 增加新方法（本片不允许改 store 包）。
     */
    private static final int SCAN_LIMIT = Integer.MAX_VALUE;

    private final EvalDimensionWeights weights;
    private final PluginLogger logger;

    /**
     * @param weights 五维权重（来自 rubric，D-4 的 EvalDimensionWeights）；基线总分必须可算，
     *                不能为空
     */
    public EvalBaselineService(EvalDimensionWeights weights) {
        this(weights, null);
    }

    public EvalBaselineService(EvalDimensionWeights weights, PluginLogger logger) {
        if (weights == null) {
            throw new IllegalArgumentException("维度权重不能为空：基线总分必须可算");
        }
        this.weights = weights;
        this.logger = logger;
    }

    /**
     * 首个成功且完整的 full run 自动成为基线（F9）。
     *
     * <p>只建一次：库内已有 active 基线时返回 UNCHANGED，不覆盖也不新增。run 行可以只带
     * run_id，其余列由本方法回查；状态、档位、case 完整性都按库内数据判定，不信任入参。</p>
     *
     * @param store  EvalStore 门面
     * @param runRow run 行（至少 run_id）或 null
     */
    public BaselineOutcome autoCreateIfFirstFull(EvalStore store, JSONObject runRow) {
        requireStore(store);
        JSONObject run = resolveRun(store, runRow);
        if (run == null) {
            return BaselineOutcome.rejected(null, "run 行缺失",
                    "自动基线需要一条可读的 run 行，无法判定是否成功且完整");
        }
        String runId = text(run, "run_id");
        String tier = text(run, "tier");
        String status = text(run, "status");
        if (!EvalStore.TIER_FULL.equals(tier)) {
            return BaselineOutcome.rejected(runId, "smoke 档不能作为基线",
                    "自动基线只接受成功且完整的 full run（实际 tier=" + tier + "）");
        }
        if (!EvalStore.STATUS_COMPLETED.equals(status)) {
            return BaselineOutcome.rejected(runId, "run 未完成",
                    "自动基线只接受 COMPLETED 的 full run（实际 status=" + status + "）");
        }
        RowSet caseRows = store.findCaseResults(runId);
        String incomplete = incompleteReason(caseRows);
        if (incomplete != null) {
            // 数据不完整属于「拒绝」，不是写库失败：一行都不写，命令层可以提示重跑该 run。
            return BaselineOutcome.rejected(runId, "run 不完整，不能作为基线", incomplete);
        }
        JSONObject active = store.findActiveBaseline();
        if (active != null) {
            return BaselineOutcome.unchanged(runId, text(active, "baseline_id"),
                    "已有 active 基线，自动基线只建一次");
        }
        JSONObject existing = findByRunId(store, runId);
        JSONObject row;
        try {
            row = existing == null ? buildRow(store, run, CREATED_BY_SYSTEM, AUTO_REASON) : existing;
        } catch (RuntimeException e) {
            return BaselineOutcome.failed(runId, "基线分值装配失败：" + e.getMessage(), null);
        }
        BaselineOutcome outcome = switchActive(store, row, BaselineOutcome.State.CREATED,
                CREATED_BY_SYSTEM, AUTO_REASON);
        if (outcome.applied()) {
            info("已自动建立基线（baseline_id=" + outcome.baselineId() + "，run_id=" + runId + "）");
        }
        return outcome;
    }

    /** 显式重设基线（F11），缺省操作者 owner。 */
    public BaselineOutcome explicitSet(EvalStore store, String runId, String reason) {
        return explicitSet(store, runId, reason, CREATED_BY_OWNER);
    }

    /**
     * 显式重设基线（F11）。
     *
     * <p>校验顺序：理由非空 → run 存在 → full 档 → COMPLETED。理由为空拒绝并提示；
     * smoke 一律拒绝（F10）；非 COMPLETED 拒绝。库里该 run 已有 active 基线时返回
     * UNCHANGED（重复 set 幂等），已有非 active 行时复用该行重新激活，不新建重复行。</p>
     *
     * @param operator 操作者，空白时按 owner 记
     */
    public BaselineOutcome explicitSet(EvalStore store, String runId, String reason, String operator) {
        requireStore(store);
        String trimmedReason = trimToNull(reason);
        if (trimmedReason == null) {
            return BaselineOutcome.rejected(runId, "重设理由不能为空",
                    "请补一句重设理由，例如「换 judge 模型后重建基线」");
        }
        String trimmedRunId = trimToNull(runId);
        if (trimmedRunId == null) {
            return BaselineOutcome.rejected(null, "run_id 不能为空", "请给出要设为基线的 run_id");
        }
        JSONObject run = store.findRun(trimmedRunId);
        if (run == null) {
            return BaselineOutcome.rejected(trimmedRunId, "run 不存在",
                    "库中没有该 run_id，不能设为基线");
        }
        String tier = text(run, "tier");
        if (!EvalStore.TIER_FULL.equals(tier)) {
            return BaselineOutcome.rejected(trimmedRunId, "smoke 档不能作为基线",
                    "显式重设只接受 full run（实际 tier=" + tier + "）");
        }
        String status = text(run, "status");
        if (!EvalStore.STATUS_COMPLETED.equals(status)) {
            return BaselineOutcome.rejected(trimmedRunId, "run 未完成",
                    "只接受 COMPLETED 的 full run（实际 status=" + status + "）");
        }
        String operatorText = trimToNull(operator) == null ? CREATED_BY_OWNER : operator.trim();
        JSONObject existing = findByRunId(store, trimmedRunId);
        if (existing != null && isActive(existing)) {
            return BaselineOutcome.unchanged(trimmedRunId, text(existing, "baseline_id"),
                    "该 run 已是当前基线，重复设置按幂等成功处理");
        }
        JSONObject row;
        try {
            row = existing != null ? existing
                    : buildRow(store, run, operatorText, trimmedReason);
        } catch (RuntimeException e) {
            return BaselineOutcome.failed(trimmedRunId, "基线分值装配失败：" + e.getMessage(), null);
        }
        BaselineOutcome.State successState = existing != null
                ? BaselineOutcome.State.REACTIVATED : BaselineOutcome.State.CREATED;
        BaselineOutcome outcome = switchActive(store, row, successState, operatorText, trimmedReason);
        if (outcome.applied()) {
            info("已重设基线（baseline_id=" + outcome.baselineId() + "，run_id=" + trimmedRunId
                    + "，operator=" + operatorText + "，理由=" + trimmedReason + "）");
        }
        return outcome;
    }

    /** baseline_id 由 run_id 派生：同一 run 重复 set 命中同一主键，天然幂等。 */
    public static String baselineIdFor(String runId) {
        if (runId == null || runId.trim().isEmpty()) {
            throw new IllegalArgumentException("run_id 不能为空");
        }
        return BASELINE_ID_PREFIX + runId.trim();
    }

    /**
     * 三步切换 active（§8.1）：插入（active=0）→ 旧 active 退役并写 superseded_by → 激活新行。
     *
     * <p>无事务，任一步失败后回读真实状态并重跑；每次重跑都从「库内是否已有该行、是否已
     * active」重新判断，因此重复调用安全。仍不收敛时按「保留最近 created_at」修复 active 行，
     * 保证不会长期无基线。</p>
     */
    private BaselineOutcome switchActive(EvalStore store, JSONObject row,
            BaselineOutcome.State successState, String operator, String reason) {
        String baselineId = text(row, "baseline_id");
        String runId = text(row, "run_id");
        String lastProblem = null;
        for (int attempt = 1; attempt <= MAX_SWITCH_ATTEMPTS; attempt++) {
            JSONObject stored = store.findBaseline(baselineId);
            if (stored == null) {
                // 第 ① 步：新行先以 active=0 插入；主键冲突（重跑）时回读视为已插入。
                WriteResult inserted = store.insertBaseline(row);
                stored = store.findBaseline(baselineId);
                if (stored == null) {
                    lastProblem = "第①步新增基线行未生效（" + inserted + "）";
                    continue;
                }
            } else if (isActive(stored)) {
                // 重跑时发现上次其实已生效：幂等返回，不再改动 superseded_by。
                return BaselineOutcome.unchanged(runId, baselineId, "基线已生效，重复设置按幂等成功处理");
            }
            // 第 ② 步：旧 active 置 0 并写 superseded_by=新 id；NO_MATCH 表示当前无 active，符合预期。
            WriteResult superseded = store.supersedeActiveBaseline(baselineId);
            // 第 ③ 步：新行置 active=1。
            WriteResult activated = store.activateBaseline(baselineId);
            JSONObject active = store.findActiveBaseline();
            if (active != null && baselineId.equals(text(active, "baseline_id"))) {
                return BaselineOutcome.success(successState, runId, baselineId,
                        "基线切换完成（" + operator + "，理由=" + reason + "）");
            }
            lastProblem = "第②③步未收敛（supersede=" + superseded + "，activate=" + activated + "）";
        }
        boolean repaired = repairActiveIfMissing(store);
        boolean hasActive = store.findActiveBaseline() != null;
        String state = repaired ? "；已按最近 created_at 修复 active 基线"
                : hasActive ? "；库内保留原 active 基线" : "；库内当前无 active 基线";
        warn("基线切换失败（run_id=" + runId + "，baseline_id=" + baselineId + "）：" + lastProblem
                + state);
        return BaselineOutcome.failed(runId, "基线切换失败：" + lastProblem + state, baselineId);
    }

    /**
     * 切换失败后的收敛修复：库内没有 active 行时，把 created_at 最近的基线行激活。
     *
     * <p>唯一索引保证不会出现多条 active（第二条写入直接失败），所以只需处理「0 条」。
     * 修复失败返回 false，由调用方降级为 FAILED 并在报表里明确报错。</p>
     */
    private boolean repairActiveIfMissing(EvalStore store) {
        if (store.findActiveBaseline() != null) {
            return false;
        }
        List<JSONObject> rows = store.findBaselines(1);
        if (rows.isEmpty()) {
            return false;
        }
        String baselineId = text(rows.get(0), "baseline_id");
        WriteResult activated = store.activateBaseline(baselineId);
        boolean repaired = store.findActiveBaseline() != null;
        if (repaired) {
            warn("已把最近的基线行修复为 active（baseline_id=" + baselineId + "，activate="
                    + activated + "）");
        }
        return repaired;
    }

    /** 装配基线行：分数与九键都从 run 行与 case 结果行冻结，绝不回查基线自己的值。 */
    private JSONObject buildRow(EvalStore store, JSONObject run, String operator, String reason) {
        String runId = text(run, "run_id");
        RowSet caseRows = store.findCaseResults(runId);
        if (caseRows.isQueryFailed()) {
            throw new IllegalStateException("读取 case 结果失败，不能冻结基线分值");
        }
        List<EvalCaseScore> cases = new ArrayList<EvalCaseScore>();
        for (JSONObject caseRow : caseRows.rows()) {
            cases.add(toCaseScore(caseRow));
        }
        EvalRunScore runScore = new EvalScoreAggregator().aggregate(cases, weights);
        JSONObject dimensionScores = new JSONObject(true);
        for (EvalDimensionScore dimension : runScore.dimensions()) {
            if (dimension.score() != null) {
                dimensionScores.put(dimension.dimensionId(), dimension.score());
            }
        }
        JSONObject baseline = new JSONObject(true);
        baseline.put("baseline_id", baselineIdFor(runId));
        baseline.put("run_id", runId);
        // 第 ① 步必须先写 active=0，active=1 由第 ③ 步单独置位。
        baseline.put("active", Integer.valueOf(0));
        baseline.put("dimension_scores_json", dimensionScores.toJSONString());
        if (runScore.total() != null) {
            baseline.put("total_score", runScore.total());
        }
        baseline.put("valid_case_count", Integer.valueOf(runScore.validCaseCount()));
        baseline.put("perf_ref_json", buildPerfRef(store, runId).toJSONString());
        for (String key : EvalRunComparability.KEY_NAMES) {
            baseline.put(key, text(run, key));
        }
        baseline.put("created_by", operator);
        baseline.put("reason", reason);
        return baseline;
    }

    /**
     * 性能参照（§4.2.5 要求扁平结构）：只写非 null 指标，缺失项不补 0（§7.5）。
     */
    private JSONObject buildPerfRef(EvalStore store, String runId) {
        RowSet calls = store.findCalls(runId);
        EvalMetrics metrics = new EvalMetricsService().compute(calls.rows(), store.findCaseResults(runId).rows());
        JSONObject perf = new JSONObject(true);
        putIfPresent(perf, "p50", metrics.latency().p50());
        putIfPresent(perf, "p90", metrics.latency().p90());
        putIfPresent(perf, "p95", metrics.latency().p95());
        putIfPresent(perf, "mean", metrics.latency().mean());
        perf.put("calls", Long.valueOf(metrics.calls().total()));
        perf.put("cached", Long.valueOf(metrics.calls().cached()));
        perf.put("retryCount", Long.valueOf(metrics.calls().retryCount()));
        perf.put("validCases", Long.valueOf(metrics.cases().valid()));
        putIfPresent(perf, "nonOkRate", metrics.cases().nonOkRate());
        return perf;
    }

    private static void putIfPresent(JSONObject target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * case 行转聚合输入：存储分是 0..100（§4.2.2 score=caseScore×100），D-4 的
     * EvalCaseScore 需要 0..1，因此按 max_score 归一。ok 的分数缺失时保留 null，
     * 由聚合器按「ok 但题分不可得」降级为 invalid，绝不折成 0 分。
     */
    private static EvalCaseScore toCaseScore(JSONObject row) {
        String caseId = text(row, "case_id");
        String dimensionId = text(row, "dimension");
        String statusText = text(row, "status");
        if (caseId == null || dimensionId == null || statusText == null) {
            throw new IllegalStateException("case 结果行缺少 case_id / dimension / status");
        }
        EvalCaseScore.Status status;
        if ("ok".equals(statusText)) {
            status = EvalCaseScore.Status.OK;
        } else if ("vetoed".equals(statusText)) {
            status = EvalCaseScore.Status.VETOED;
        } else if ("error".equals(statusText)) {
            status = EvalCaseScore.Status.ERROR;
        } else if ("invalid".equals(statusText)) {
            status = EvalCaseScore.Status.INVALID;
        } else {
            throw new IllegalStateException("未知 case 状态：" + statusText);
        }
        Double caseScore = null;
        if (status == EvalCaseScore.Status.OK) {
            Double raw = row.getDouble("score");
            if (raw != null) {
                Double max = row.getDouble("max_score");
                double scale = max == null || max.doubleValue() <= 0.0d ? 100.0d : max.doubleValue();
                caseScore = Double.valueOf(raw.doubleValue() / scale);
            }
        }
        return EvalCaseScore.of(caseId, dimensionId, status, caseScore);
    }

    /** 完整性判定：查询成功、行数非空且与库内计数一致、每行状态合法。返回 null 表示完整。 */
    private static String incompleteReason(RowSet caseRows) {
        if (caseRows.isQueryFailed()) {
            return "读取 case 结果失败，无法判定运行是否完整";
        }
        if (caseRows.size() == 0) {
            return "case 结果为空，不能作为基线";
        }
        if (!caseRows.isComplete()) {
            return "case 结果不完整（读到 " + caseRows.size() + "/" + caseRows.expectedCount()
                    + " 行），不能作为基线";
        }
        for (JSONObject row : caseRows.rows()) {
            String status = text(row, "status");
            if (!isCaseStatus(status)) {
                return "case 结果状态非法（case_id=" + text(row, "case_id") + "，status=" + status
                        + "），不能作为基线";
            }
        }
        return null;
    }

    private static boolean isCaseStatus(String status) {
        for (String candidate : CASE_STATUSES) {
            if (candidate.equals(status)) {
                return true;
            }
        }
        return false;
    }

    /** 按 run_id 找基线行：先查派生 id，再按全量列表兜底兼容历史/手工行。 */
    private static JSONObject findByRunId(EvalStore store, String runId) {
        JSONObject derived = store.findBaseline(baselineIdFor(runId));
        if (derived != null) {
            return derived;
        }
        List<JSONObject> rows = store.findBaselines(SCAN_LIMIT);
        for (JSONObject row : rows) {
            if (runId.equals(text(row, "run_id"))) {
                return row;
            }
        }
        return null;
    }

    private static boolean isActive(JSONObject baselineRow) {
        Integer active = baselineRow == null ? null : baselineRow.getInteger("active");
        return active != null && active.intValue() == 1;
    }

    /** run 行可只带 run_id：缺 tier/status 时回查库内完整行。 */
    private static JSONObject resolveRun(EvalStore store, JSONObject runRow) {
        if (runRow == null) {
            return null;
        }
        String runId = text(runRow, "run_id");
        if (runId == null) {
            return null;
        }
        if (text(runRow, "tier") != null && text(runRow, "status") != null) {
            return runRow;
        }
        return store.findRun(runId);
    }

    private static void requireStore(EvalStore store) {
        if (store == null) {
            throw new IllegalArgumentException("EvalStore 不能为空");
        }
    }

    private static String text(JSONObject row, String key) {
        if (row == null || key == null) {
            return null;
        }
        String value = row.getString(key);
        return trimToNull(value);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void info(String message) {
        if (logger != null) {
            logger.sendInfo("[MBB-Eval] " + message);
        }
    }

    private void warn(String message) {
        if (logger != null) {
            logger.sendWarn("[MBB-Eval] " + message);
        }
    }

    /** 一次基线操作的结果；拒绝/失败也返回对象，不抛异常，便于命令层直接回话。 */
    public static final class BaselineOutcome {

        /** 结果状态。 */
        public enum State {
            /** 新建并生效。 */
            CREATED,
            /** 复用已有非 active 行重新生效。 */
            REACTIVATED,
            /** 已是当前基线或自动基线只建一次，未写库。 */
            UNCHANGED,
            /** 入参不满足规则，未写库。 */
            REJECTED,
            /** 写库失败或切换未收敛。 */
            FAILED
        }

        private final State state;
        private final String runId;
        private final String baselineId;
        private final String note;

        private BaselineOutcome(State state, String runId, String baselineId, String note) {
            this.state = state;
            this.runId = runId;
            this.baselineId = baselineId;
            this.note = note;
        }

        static BaselineOutcome success(State state, String runId, String baselineId, String note) {
            return new BaselineOutcome(state, runId, baselineId, note);
        }

        static BaselineOutcome unchanged(String runId, String baselineId, String note) {
            return new BaselineOutcome(State.UNCHANGED, runId, baselineId, note);
        }

        static BaselineOutcome rejected(String runId, String note, String detail) {
            return new BaselineOutcome(State.REJECTED, runId, null,
                    detail == null ? note : note + "：" + detail);
        }

        static BaselineOutcome failed(String runId, String note, String baselineId) {
            return new BaselineOutcome(State.FAILED, runId, baselineId, note);
        }

        public State state() {
            return state;
        }

        /** 是否真的写库并生效（CREATED / REACTIVATED）。 */
        public boolean applied() {
            return state == State.CREATED || state == State.REACTIVATED;
        }

        public boolean rejected() {
            return state == State.REJECTED;
        }

        public boolean failed() {
            return state == State.FAILED;
        }

        public String runId() {
            return runId;
        }

        public String baselineId() {
            return baselineId;
        }

        /** 中文说明，命令层与日志可直接使用。 */
        public String note() {
            return note;
        }
    }
}
