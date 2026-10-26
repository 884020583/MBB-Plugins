package org.moboxlab.mbb.eval.score;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.moboxlab.mbb.eval.rubric.EvalRubric;

/**
 * 维度分与总分聚合器（D-4：规格 §6.1、§6.6、§6.6.2、§6.6.3）。
 *
 * <p>唯一口径（§6.1 计算定义）：</p>
 * <pre>
 * V_d        = 维度 d 中 status ∈ {ok, vetoed} 的题
 * dimScore_d = 100 × Σ_{i∈V_d} caseScore_i / |V_d|     （|V_d|=0 → null）
 * total      = Σ_{d∈D} w_d × dimScore_d / Σ_{d∈D} w_d  （D 为存在维集合；D 空 → null）
 * </pre>
 *
 * <p>vetoed 记 0 并计入分母；error / invalid 剔除分母并单列。缺维时总分按剩余权重归一并
 * 标 {@code dimension_missing}，不做任何「把缺维当 0 分」的折算。内部计算全程不取整，
 * 展示取整交给 {@link EvalScoreFormat}（§6.1 判定规则 4）。</p>
 */
public final class EvalScoreAggregator {

    /** 聚合一次 run；不视为 rubric 变更。 */
    public EvalRunScore aggregate(List<EvalCaseScore> cases, EvalDimensionWeights weights) {
        return aggregate(cases, weights, false);
    }

    /** 聚合一次 run，并给出 rubric 是否相对基线已变。 */
    public EvalRunScore aggregate(List<EvalCaseScore> cases, EvalDimensionWeights weights,
            boolean rubricChanged) {
        if (weights == null) {
            throw new IllegalArgumentException("维度权重不能为空");
        }
        List<EvalCaseScore> rows = cases == null
                ? Collections.<EvalCaseScore>emptyList() : cases;

        Map<String, MutableDimension> byId = new LinkedHashMap<String, MutableDimension>();
        for (String dimensionId : weights.ids()) {
            byId.put(dimensionId, new MutableDimension(dimensionId, weights.weight(dimensionId)));
        }

        int validCaseCount = 0;
        int nonOkCount = 0;
        List<String> errorCaseIds = new ArrayList<String>();
        List<String> invalidCaseIds = new ArrayList<String>();

        for (EvalCaseScore row : rows) {
            if (row == null) {
                continue;
            }
            MutableDimension dimension = byId.get(row.dimensionId());
            if (dimension == null) {
                throw new IllegalArgumentException("题目维度不在 rubric 权重表内：" + row.dimensionId()
                        + "（题号 " + row.caseId() + "）");
            }
            dimension.caseCount++;
            if (row.status() == EvalCaseScore.Status.OK) {
                Double score = row.caseScore();
                if (score == null || score.doubleValue() < 0.0d || score.doubleValue() > 1.0d) {
                    // 状态 ok 但题分缺失 / 越界：按 invalid 处理，绝不折成 0 分（§6.9.3）。
                    dimension.invalidCount++;
                    invalidCaseIds.add(row.caseId());
                    nonOkCount++;
                } else {
                    dimension.okCount++;
                    dimension.scoreSum += score.doubleValue();
                    validCaseCount++;
                }
            } else if (row.status() == EvalCaseScore.Status.VETOED) {
                // vetoed 是被测失败：记 0，仍计入分母。
                dimension.vetoedCount++;
                validCaseCount++;
            } else if (row.status() == EvalCaseScore.Status.ERROR) {
                dimension.errorCount++;
                errorCaseIds.add(row.caseId());
                nonOkCount++;
            } else {
                dimension.invalidCount++;
                invalidCaseIds.add(row.caseId());
                nonOkCount++;
            }
        }

        double presentWeightSum = 0.0d;
        for (MutableDimension dimension : byId.values()) {
            if (dimension.validCount() > 0) {
                presentWeightSum += dimension.weight;
            }
        }

        List<EvalDimensionScore> dimensionScores = new ArrayList<EvalDimensionScore>();
        List<String> missingDimensionIds = new ArrayList<String>();
        Map<String, Double> scoresById = new LinkedHashMap<String, Double>();
        Map<String, Double> weightsById = new LinkedHashMap<String, Double>();
        for (MutableDimension dimension : byId.values()) {
            Double dimensionScore;
            double effectiveWeight;
            if (dimension.validCount() == 0) {
                dimensionScore = null;
                effectiveWeight = 0.0d;
                missingDimensionIds.add(dimension.dimensionId);
            } else {
                dimensionScore = Double.valueOf(100.0d * dimension.scoreSum / dimension.validCount());
                effectiveWeight = presentWeightSum > 0.0d ? dimension.weight / presentWeightSum : 0.0d;
            }
            scoresById.put(dimension.dimensionId, dimensionScore);
            weightsById.put(dimension.dimensionId, Double.valueOf(dimension.weight));
            dimensionScores.add(new EvalDimensionScore(dimension.dimensionId, dimension.weight,
                    effectiveWeight, dimensionScore, dimension.caseCount, dimension.okCount,
                    dimension.vetoedCount, dimension.errorCount, dimension.invalidCount));
        }

        // 总分与报表侧复用同一口径，避免两处各算一遍导致漂移。
        Double total = weightedTotal(scoresById, weightsById);

        int caseCount = rows.size();
        double nonOkRate = caseCount == 0 ? 0.0d : (double) nonOkCount / (double) caseCount;
        boolean validCasesSufficient = caseCount > 0 && validCaseCount * 5 >= caseCount * 4;
        boolean nonOkRateAcceptable = nonOkRate <= EvalCredibility.MAX_NON_OK_RATIO + 1.0e-9d;
        EvalCredibility credibility = new EvalCredibility(validCaseCount, caseCount, nonOkRate,
                validCasesSufficient, nonOkRateAcceptable, rubricChanged);

        return new EvalRunScore(dimensionScores, total, validCaseCount, caseCount,
                errorCaseIds, invalidCaseIds, missingDimensionIds, credibility);
    }

    /**
     * 按权重求总分（口径与 {@link #aggregate(List, EvalDimensionWeights)} 完全一致）。
     *
     * <p>只统计「分数存在且权重大于 0」的维度：`Σ w·score / Σ w`。分数尺度无关，维度分传
     * 0..1 或 0..100 都可；缺失维度、空权重、全零权重一律返回 {@code null}，绝不按缺维 0 分折算
     * （§6.1 判定规则）。报表侧复算总分时直接调用本方法。</p>
     */
    public static Double weightedTotal(Map<String, Double> dimensionScores,
            Map<String, Double> weights) {
        if (dimensionScores == null || dimensionScores.isEmpty() || weights == null) {
            return null;
        }
        double weightedSum = 0.0d;
        double weightSum = 0.0d;
        for (Map.Entry<String, Double> entry : dimensionScores.entrySet()) {
            if (entry == null) {
                continue;
            }
            Double score = entry.getValue();
            Double weight = weights.get(entry.getKey());
            if (score == null || weight == null || weight.doubleValue() <= 0.0d) {
                continue;
            }
            weightedSum += weight.doubleValue() * score.doubleValue();
            weightSum += weight.doubleValue();
        }
        return weightSum > 0.0d ? Double.valueOf(weightedSum / weightSum) : null;
    }

    /** 直接从 rubric 读取权重并聚合。 */
    public EvalRunScore aggregate(List<EvalCaseScore> cases, EvalRubric rubric) {
        return aggregate(cases, EvalDimensionWeights.from(rubric));
    }

    /** 聚合过程中的可变计数，只在方法内使用。 */
    private static final class MutableDimension {

        private final String dimensionId;
        private final double weight;
        private int caseCount;
        private int okCount;
        private int vetoedCount;
        private int errorCount;
        private int invalidCount;
        private double scoreSum;

        private MutableDimension(String dimensionId, double weight) {
            this.dimensionId = dimensionId;
            this.weight = weight;
        }

        private int validCount() {
            return okCount + vetoedCount;
        }
    }
}
