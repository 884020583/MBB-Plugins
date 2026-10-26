package org.moboxlab.mbb.eval.score;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.moboxlab.mbb.eval.rubric.EvalRubric;

/**
 * 聚合用的维度权重表（D-4：规格 §6.1、§6.2.3）。
 *
 * <p>权重只来自 rubric 版本文件的 {@code dimensions[].weight}，本类不提供任何默认值，
 * 代码里不得硬编码 0.30 / 0.25 / 0.20 / 0.15 / 0.10（D1）。五维权重之和必须为 1.00；
 * 顺序按传入 Map 的迭代顺序保留，报表按该顺序展示。</p>
 */
public final class EvalDimensionWeights {

    /** 权重和的比较容差；rubric 文件用十进制小数书写，double 求和只需吸收浮点尾差。 */
    public static final double SUM_TOLERANCE = 1.0e-9d;

    private final Map<String, Double> weights;
    private final List<String> ids;
    private final double sum;

    private EvalDimensionWeights(Map<String, Double> weights) {
        this.weights = Collections.unmodifiableMap(new LinkedHashMap<String, Double>(weights));
        this.ids = Collections.unmodifiableList(new ArrayList<String>(weights.keySet()));
        double total = 0.0d;
        for (Double weight : weights.values()) {
            total += weight.doubleValue();
        }
        this.sum = total;
    }

    /**
     * 按给定权重构造；空表、非法 id、负权重或权重和不为 1.00 均拒绝。
     *
     * @param weights key 为维度 id，value 为权重；不得为 null
     */
    public static EvalDimensionWeights of(Map<String, Double> weights) {
        if (weights == null || weights.isEmpty()) {
            throw new IllegalArgumentException("维度权重不能为空");
        }
        Map<String, Double> copy = new LinkedHashMap<String, Double>();
        double sum = 0.0d;
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            String id = entry.getKey();
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("维度 id 不能为空");
            }
            Double weight = entry.getValue();
            if (weight == null) {
                throw new IllegalArgumentException("维度权重不能为空：" + id);
            }
            double value = weight.doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0d) {
                throw new IllegalArgumentException("维度权重必须是有限的非负数：" + id + "=" + value);
            }
            copy.put(id, Double.valueOf(value));
            sum += value;
        }
        if (Math.abs(sum - 1.0d) > SUM_TOLERANCE) {
            throw new IllegalArgumentException("五维权重之和必须为 1.00，实际 " + sum);
        }
        return new EvalDimensionWeights(copy);
    }

    /** 从已校验的 rubric 读取权重；维度 id 顺序与 rubric 文件一致。 */
    public static EvalDimensionWeights from(EvalRubric rubric) {
        if (rubric == null) {
            throw new IllegalArgumentException("rubric 不能为空");
        }
        Map<String, Double> map = new LinkedHashMap<String, Double>();
        for (EvalRubric.Dimension dimension : rubric.dimensions()) {
            map.put(dimension.id(), Double.valueOf(dimension.weight()));
        }
        return of(map);
    }

    /** 维度 id，按权重表顺序。 */
    public List<String> ids() {
        return ids;
    }

    public double weight(String dimensionId) {
        Double value = weights.get(dimensionId);
        if (value == null) {
            throw new IllegalArgumentException("权重表中没有维度：" + dimensionId);
        }
        return value.doubleValue();
    }

    public int size() {
        return ids.size();
    }

    public double sum() {
        return sum;
    }
}
