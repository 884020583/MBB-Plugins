package org.moboxlab.mbb.eval.baseline;

import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.moboxlab.mbb.eval.score.EvalCaseScore;
import org.moboxlab.mbb.eval.score.EvalDimensionScore;
import org.moboxlab.mbb.eval.score.EvalDimensionWeights;
import org.moboxlab.mbb.eval.score.EvalRunScore;
import org.moboxlab.mbb.eval.score.EvalScoreAggregator;
import org.moboxlab.mbb.eval.store.EvalStore;

/**
 * 基线与 delta 计算服务（规格 #50 §8.3、§8.4、§8.5，验收 F16 / F17 / F18）。
 *
 * <p>职责：把两次 run（或一次 run 与一条基线）的 case 结果按同题配对，产出可直接给报表
 * 渲染的 {@link EvalDeltaResult}。规则：</p>
 * <ul>
 *   <li>只对两侧 {@code status=ok} 的同 case_id 题配对，vetoed 不参与（§8.4）；</li>
 *   <li>总分只报点值差，不做区间、不标显著；</li>
 *   <li>维度 delta 用该维配对题分差的均值；整对 run 的配对题数 ≥20 时用
 *       {@link EvalDeltaCalculator} 按各维自己的配对题出区间；</li>
 *   <li>ok→error / ok→invalid 单列不可信变更，不并入变差、不参与配对（F17）；</li>
 *   <li>三条硬门槛只改标注，不改分；任一门槛触发时不给显著结论（F18）；</li>
 *   <li>九键不可比或 rubric 已变时不出维度 delta，但总分点值仍显示（F13）。</li>
 * </ul>
 *
 * <p>本类只读库，不写库、不写报表。报表接线由后续切片完成。</p>
 */
public final class EvalDeltaService {

    private final EvalStore store;
    private final EvalDimensionWeights weights;
    private final EvalDeltaCalculator calculator;
    private final EvalDeltaAnnotator annotator;
    private final EvalScoreAggregator aggregator;

    /** 生产默认：冻结的 bootstrap 参数（1000 次、种子 41）。 */
    public EvalDeltaService(EvalStore store, EvalDimensionWeights weights) {
        this(store, weights, new EvalDeltaCalculator());
    }

    /** 可注入 bootstrap 参数，供测试验证复现性与边界；生产必须使用默认构造。 */
    public EvalDeltaService(EvalStore store, EvalDimensionWeights weights,
                            EvalDeltaCalculator calculator) {
        if (store == null) {
            throw new IllegalArgumentException("EvalStore 不能为空");
        }
        if (weights == null) {
            throw new IllegalArgumentException("维度权重不能为空");
        }
        if (calculator == null) {
            throw new IllegalArgumentException("bootstrap 计算器不能为空");
        }
        this.store = store;
        this.weights = weights;
        this.calculator = calculator;
        this.annotator = new EvalDeltaAnnotator();
        this.aggregator = new EvalScoreAggregator();
    }

    /** 比较两次 run；两侧都必须是 full 档且九键全等才可比。 */
    public EvalDeltaResult compareRuns(String baselineRunId, String currentRunId) {
        JSONObject baselineRun = store.findRun(baselineRunId);
        JSONObject currentRun = store.findRun(currentRunId);
        if (baselineRun == null || currentRun == null) {
            return missingRunResult(baselineRunId, currentRunId, baselineRun, currentRun);
        }
        EvalRunComparability.Result comparability =
                EvalRunComparability.compare(baselineRun, currentRun);
        boolean rubricChanged = comparability.differingKeys().contains("rubric_version");
        Side baseline = loadRunSide(baselineRunId, baselineRun, rubricChanged);
        Side current = loadRunSide(currentRunId, currentRun, rubricChanged);
        return assemble(baseline, current, comparability, rubricChanged, null);
    }

    /**
     * 把当前 run 与一条基线行比较。
     *
     * <p>基线行的 {@code total_score} 与 {@code dimension_scores_json} 是冻结参考；
     * case 结果仍会读出来核对基线可信度。基线 run 已被清理时只能核对到存储的汇总值，
     * 此时按「基线数据不完整」不给趋势结论。</p>
     */
    public EvalDeltaResult compareToBaseline(String currentRunId, JSONObject baselineRow) {
        JSONObject currentRun = store.findRun(currentRunId);
        if (currentRun == null) {
            return missingRunResult(null, currentRunId, null, currentRun);
        }
        if (baselineRow == null) {
            Side current = loadRunSide(currentRunId, currentRun, false);
            return assemble(nullSide(), current, null, false,
                    "未设置基线，只显示本次总分，不出 delta");
        }
        EvalRunComparability.Result comparability =
                EvalRunComparability.compareToBaseline(currentRun, baselineRow);
        boolean rubricChanged = comparability.differingKeys().contains("rubric_version");
        Side baseline = loadBaselineSide(baselineRow, rubricChanged);
        Side current = loadRunSide(currentRunId, currentRun, rubricChanged);
        return assemble(baseline, current, comparability, rubricChanged, null);
    }

    /** 读取当前激活基线并比较；没有激活基线时返回「无基线」结果。 */
    public EvalDeltaResult compareToActiveBaseline(String currentRunId) {
        return compareToBaseline(currentRunId, store.findActiveBaseline());
    }

    /** run 行缺失：不能比较，给出可渲染的说明对象。 */
    private EvalDeltaResult missingRunResult(String baselineRunId, String currentRunId,
                                             JSONObject baselineRun, JSONObject currentRun) {
        EvalDeltaResult.Builder builder = new EvalDeltaResult.Builder()
                .baselineRunId(baselineRunId)
                .currentRunId(currentRunId)
                .available(false)
                .comparable(false)
                .comparabilityReason(EvalRunComparability.REASON_RUN_MISSING)
                .baselineCredible(false)
                .currentCredible(false)
                .annotation("数据缺失：run 行不存在，无法比较");
        if (baselineRun == null) {
            builder.note("基线 run 行缺失：" + baselineRunId);
        } else {
            builder.note("当前 run 行缺失：" + currentRunId);
        }
        return builder.build();
    }

    /** 读取一次 run 的 case 结果并聚合；读表失败或部分行由 Side.complete 标记。 */
    private Side loadRunSide(String runId, JSONObject runRow, boolean rubricChanged) {
        Side side = new Side(runId, runRow, true);
        EvalStore.RowSet rowSet = store.findCaseResults(runId);
        side.rows = rowSet.rows();
        side.complete = rowSet.isComplete();
        side.expectedCount = rowSet.expectedCount();
        List<EvalCaseScore> cases = new ArrayList<EvalCaseScore>();
        for (JSONObject row : side.rows) {
            cases.add(toCaseScore(row));
        }
        side.score = aggregator.aggregate(cases, weights, rubricChanged);
        side.total = side.score.total();
        for (String dimensionId : weights.ids()) {
            EvalDimensionScore dimension = side.score.dimension(dimensionId);
            side.dimensionScores.put(dimensionId, dimension == null ? null : dimension.score());
        }
        side.credible = side.score.credibility().credible();
        side.annotations.addAll(side.score.annotations());
        side.annotations.addAll(annotator.annotate(runRow, side.rows));
        return side;
    }

    /** 从基线行装配：优先用冻结的 total_score / dimension_scores_json，再读 case 行核对可信度。 */
    private Side loadBaselineSide(JSONObject baselineRow, boolean rubricChanged) {
        String runId = baselineRow.getString("run_id");
        Side side = new Side(runId, null, true);
        side.total = baselineRow.getDouble("total_score");
        JSONObject storedDimensions = parseObject(baselineRow.getString("dimension_scores_json"));
        for (String dimensionId : weights.ids()) {
            Double stored = storedDimensions == null ? null : storedDimensions.getDouble(dimensionId);
            side.dimensionScores.put(dimensionId, stored);
        }
        if (runId == null || runId.trim().isEmpty()) {
            side.credible = false;
            side.complete = false;
            side.annotations.add("基线行没有 run_id，无法核对基线可信度");
            return side;
        }
        EvalStore.RowSet rowSet = store.findCaseResults(runId);
        side.complete = rowSet.isComplete();
        side.expectedCount = rowSet.expectedCount();
        side.rows = rowSet.rows();
        if (side.rows.isEmpty()) {
            side.credible = false;
            side.complete = false;
            side.annotations.add("基线 case 结果缺失，只保留冻结汇总值，不给趋势结论");
            return side;
        }
        List<EvalCaseScore> cases = new ArrayList<EvalCaseScore>();
        for (JSONObject row : side.rows) {
            cases.add(toCaseScore(row));
        }
        side.score = aggregator.aggregate(cases, weights, rubricChanged);
        if (side.total == null) {
            side.total = side.score.total();
        }
        for (String dimensionId : weights.ids()) {
            if (side.dimensionScores.get(dimensionId) == null) {
                EvalDimensionScore dimension = side.score.dimension(dimensionId);
                side.dimensionScores.put(dimensionId, dimension == null ? null : dimension.score());
            }
        }
        side.credible = side.score.credibility().credible();
        side.annotations.addAll(side.score.annotations());
        return side;
    }

    /** 组装最终结果：配对、计数、维度区间、不可信变更、翻转题与标注。 */
    private EvalDeltaResult assemble(Side baseline, Side current,
                                     EvalRunComparability.Result comparability,
                                     boolean rubricChanged, String forcedNote) {
        boolean available = baseline.present && current.present;
        boolean comparable = comparability != null && comparability.comparable();
        boolean dataComplete = baseline.complete && current.complete;
        boolean credible = available && baseline.credible && current.credible && dataComplete;

        Map<String, JSONObject> baseById = indexById(baseline.rows);
        Map<String, JSONObject> curById = indexById(current.rows);
        Set<String> allCaseIds = new TreeSet<String>();
        allCaseIds.addAll(baseById.keySet());
        allCaseIds.addAll(curById.keySet());

        List<Double> totalDiffs = new ArrayList<Double>();
        Map<String, List<Double>> dimensionDiffs = new LinkedHashMap<String, List<Double>>();
        Map<String, int[]> dimensionCounts = new LinkedHashMap<String, int[]>();
        for (String dimensionId : weights.ids()) {
            dimensionDiffs.put(dimensionId, new ArrayList<Double>());
            dimensionCounts.put(dimensionId, new int[3]);
        }
        List<EvalDeltaResult.CaseChange> untrustedChanges = new ArrayList<EvalDeltaResult.CaseChange>();
        int better = 0;
        int worse = 0;
        int same = 0;

        for (String caseId : allCaseIds) {
            JSONObject baseRow = baseById.get(caseId);
            JSONObject curRow = curById.get(caseId);
            boolean baseOk = isStatus(baseRow, "ok");
            boolean curOk = isStatus(curRow, "ok");
            if (baseOk && curOk) {
                Double baseScore = normalizedScore(baseRow);
                Double curScore = normalizedScore(curRow);
                if (baseScore == null || curScore == null) {
                    continue;
                }
                double diff = (curScore.doubleValue() - baseScore.doubleValue()) * 100.0d;
                totalDiffs.add(Double.valueOf(diff));
                String dimensionId = dimensionOf(curRow, baseRow);
                List<Double> dimensionList = dimensionDiffs.get(dimensionId);
                if (dimensionList != null) {
                    dimensionList.add(Double.valueOf(diff));
                }
                int[] counts = dimensionCounts.get(dimensionId);
                if (diff > 1.0e-9d) {
                    better++;
                    if (counts != null) {
                        counts[0]++;
                    }
                } else if (diff < -1.0e-9d) {
                    worse++;
                    if (counts != null) {
                        counts[1]++;
                    }
                } else {
                    same++;
                    if (counts != null) {
                        counts[2]++;
                    }
                }
            } else if (baseOk && curRow != null
                    && ("error".equalsIgnoreCase(curRow.getString("status"))
                    || "invalid".equalsIgnoreCase(curRow.getString("status")))) {
                untrustedChanges.add(new EvalDeltaResult.CaseChange(caseId,
                        dimensionOf(curRow, baseRow), "ok",
                        curRow.getString("status").toLowerCase()));
            }
        }

        EvalDeltaAnnotator.K3Stats k3 = EvalDeltaAnnotator.k3Stats(current.rows);

        Double totalDelta = null;
        if (!rubricChanged && baseline.total != null && current.total != null) {
            totalDelta = Double.valueOf(current.total.doubleValue() - baseline.total.doubleValue());
        }

        EvalDeltaResult.Builder builder = new EvalDeltaResult.Builder()
                .baselineRunId(baseline.runId)
                .currentRunId(current.runId)
                .available(available)
                .note(forcedNote)
                .comparable(comparable)
                .comparabilityReason(comparability == null ? null : comparability.reason())
                .differingKeys(comparability == null
                        ? Collections.<String>emptyList() : comparability.differingKeys())
                .missingKeys(comparability == null
                        ? Collections.<String>emptyList() : comparability.missingKeys())
                .rubricChanged(rubricChanged)
                .baselineCredible(baseline.credible && available && dataComplete)
                .currentCredible(current.credible && available && dataComplete)
                .baselineTotal(baseline.total)
                .currentTotal(current.total)
                .totalDelta(totalDelta)
                .pairedCount(totalDiffs.size())
                .betterCount(better)
                .worseCount(worse)
                .sameCount(same)
                .flippedTotal(k3.total())
                .k3SameCount(k3.allSameCount())
                .k3Total(k3.total());

        for (String caseId : k3.flippedCaseIds()) {
            builder.flippedCaseId(caseId);
        }
        for (EvalDeltaResult.CaseChange change : untrustedChanges) {
            builder.untrustedChange(change);
        }

        for (String dimensionId : weights.ids()) {
            builder.dimension(buildDimension(baseline, current, comparability, rubricChanged,
                    credible, dimensionId, dimensionDiffs.get(dimensionId),
                    dimensionCounts.get(dimensionId), totalDiffs.size()));
        }

        LinkedHashSet<String> notes = new LinkedHashSet<String>();
        notes.addAll(current.annotations);
        for (String note : baseline.annotations) {
            notes.add("基线：" + note);
        }
        if (comparability != null && !comparability.comparable()) {
            notes.add("不可比：" + comparability.reason());
        }
        if (comparability != null && !comparability.differingKeys().isEmpty()) {
            notes.add("差异字段：" + join(comparability.differingKeys()));
        }
        if (comparability != null && !comparability.missingKeys().isEmpty()) {
            notes.add("缺失字段：" + join(comparability.missingKeys()));
        }
        if (rubricChanged) {
            notes.add("rubric 已变，不出 delta");
        }
        if (totalDiffs.size() < EvalDeltaCalculator.MIN_PAIRS) {
            notes.add("样本不足：配对 n=" + totalDiffs.size()
                    + " 少于 " + EvalDeltaCalculator.MIN_PAIRS + "，不出置信区间");
        }
        if (!credible && available) {
            notes.add("整份报告不可信，不给趋势结论");
        }
        if (!dataComplete) {
            notes.add("case 结果不完整，缺失题按数据缺失处理，不按 0 分");
        }
        if (forcedNote != null) {
            notes.add(forcedNote);
        }
        builder.annotations(new ArrayList<String>(notes));
        return builder.build();
    }

    /**
     * 构造单维 delta：点值取该维配对题分差均值，区间只在线索齐全时计算。
     *
     * <p>样本量门槛按整对 run 的配对题数判定（§15.6 F16 以「可比 run 对、n≥20」为单位），
     * 通过后每个维度仍按自己的配对题独立重采样，最小步长也按该维的 n 计算（§8.4）。</p>
     */
    private EvalDeltaResult.DimensionDelta buildDimension(Side baseline, Side current,
                                                          EvalRunComparability.Result comparability,
                                                          boolean rubricChanged, boolean credible,
                                                          String dimensionId, List<Double> diffs,
                                                          int[] counts, int totalPairs) {
        boolean comparable = comparability != null && comparability.comparable();
        Double baselineScore = baseline.dimensionScores.get(dimensionId);
        Double currentScore = current.dimensionScores.get(dimensionId);
        Double delta = null;
        Double ciLow = null;
        Double ciHigh = null;
        double minimumStep = Double.NaN;
        EvalDeltaResult.Significance significance = EvalDeltaResult.Significance.NOT_COMPARABLE;
        String note = null;

        if (!comparable) {
            note = comparability == null ? "没有基线" : comparability.reason();
        } else if (rubricChanged) {
            note = "rubric 已变，不出 delta";
        } else if (diffs.isEmpty()) {
            note = "无有效配对题";
        } else {
            delta = Double.valueOf(mean(diffs));
            minimumStep = EvalDeltaCalculator.minimumStep(diffs.size());
            if (totalPairs < EvalDeltaCalculator.MIN_PAIRS) {
                note = "样本不足：配对 n=" + totalPairs + " 少于 "
                        + EvalDeltaCalculator.MIN_PAIRS + "，不出置信区间";
            } else if (!credible) {
                note = "任一侧不可信，只报点值，不给显著结论";
            } else {
                EvalDeltaCalculator.Estimate estimate = calculator.estimate(toArray(diffs));
                ciLow = Double.valueOf(estimate.ciLow());
                ciHigh = Double.valueOf(estimate.ciHigh());
                if (estimate.significant()) {
                    significance = estimate.delta() >= 0.0d
                            ? EvalDeltaResult.Significance.SIGNIFICANT_UP
                            : EvalDeltaResult.Significance.SIGNIFICANT_DOWN;
                } else {
                    significance = EvalDeltaResult.Significance.FLAT;
                }
            }
        }

        int[] safeCounts = counts == null ? new int[3] : counts;
        return new EvalDeltaResult.DimensionDelta(dimensionId, baselineScore, currentScore,
                delta, ciLow, ciHigh, diffs.size(), minimumStep, significance, note,
                safeCounts[0], safeCounts[1], safeCounts[2]);
    }

    private EvalCaseScore toCaseScore(JSONObject row) {
        String statusText = row.getString("status");
        EvalCaseScore.Status status = parseStatus(statusText);
        Double score = status == EvalCaseScore.Status.OK ? normalizedScore(row) : null;
        return EvalCaseScore.of(row.getString("case_id"), row.getString("dimension"), status, score);
    }

    private static EvalCaseScore.Status parseStatus(String value) {
        if (value == null) {
            return EvalCaseScore.Status.INVALID;
        }
        String normalized = value.trim().toLowerCase();
        if ("ok".equals(normalized)) {
            return EvalCaseScore.Status.OK;
        }
        if ("vetoed".equals(normalized)) {
            return EvalCaseScore.Status.VETOED;
        }
        if ("error".equals(normalized)) {
            return EvalCaseScore.Status.ERROR;
        }
        return EvalCaseScore.Status.INVALID;
    }

    /** 把 case_result.score / max_score 归一为 0..1；缺失或非法返回 null，绝不折成 0。 */
    private static Double normalizedScore(JSONObject row) {
        if (row == null) {
            return null;
        }
        Double score = row.getDouble("score");
        if (score == null || score.isNaN() || score.isInfinite()) {
            return null;
        }
        Double maxScore = row.getDouble("max_score");
        double max = maxScore == null || maxScore.doubleValue() <= 0.0d
                ? 100.0d : maxScore.doubleValue();
        return Double.valueOf(score.doubleValue() / max);
    }

    private static boolean isStatus(JSONObject row, String status) {
        return row != null && status.equalsIgnoreCase(row.getString("status"));
    }

    private static String dimensionOf(JSONObject preferred, JSONObject fallback) {
        String dimensionId = preferred == null ? null : preferred.getString("dimension");
        if (dimensionId == null || dimensionId.trim().isEmpty()) {
            dimensionId = fallback == null ? "" : fallback.getString("dimension");
        }
        return dimensionId;
    }

    private static Map<String, JSONObject> indexById(List<JSONObject> rows) {
        Map<String, JSONObject> indexed = new TreeMap<String, JSONObject>();
        if (rows == null) {
            return indexed;
        }
        for (JSONObject row : rows) {
            String caseId = row.getString("case_id");
            if (caseId != null && !caseId.trim().isEmpty()) {
                indexed.put(caseId, row);
            }
        }
        return indexed;
    }

    private static double mean(List<Double> values) {
        double sum = 0.0d;
        for (Double value : values) {
            sum += value.doubleValue();
        }
        return sum / values.size();
    }

    private static double[] toArray(List<Double> values) {
        double[] array = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            array[i] = values.get(i).doubleValue();
        }
        return array;
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append('\u3001');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static JSONObject parseObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            Object parsed = JSONObject.parse(json.trim());
            return parsed instanceof JSONObject ? (JSONObject) parsed : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Side nullSide() {
        return new Side(null, null, false);
    }

    /** 一次 run 或一条基线的读取结果，只在服务内部使用。 */
    private static final class Side {

        private final String runId;
        private final JSONObject runRow;
        private final boolean present;
        private List<JSONObject> rows = Collections.emptyList();
        private EvalRunScore score;
        private Double total;
        private final Map<String, Double> dimensionScores = new LinkedHashMap<String, Double>();
        private boolean credible;
        private boolean complete = true;
        private int expectedCount;
        private final List<String> annotations = new ArrayList<String>();

        private Side(String runId, JSONObject runRow, boolean present) {
            this.runId = runId;
            this.runRow = runRow;
            this.present = present;
        }
    }
}
