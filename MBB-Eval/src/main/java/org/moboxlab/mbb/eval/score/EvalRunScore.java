package org.moboxlab.mbb.eval.score;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 run 的聚合快照（D-4：规格 §6.1、§6.6）。
 *
 * <p>不可变值对象：五维分、总分、有效题数与缺维 / 失败题清单一次算清。总分只作趋势线，
 * 报表首屏必须是五维分（§6.1 判定规则 5、§8.3）。</p>
 */
public final class EvalRunScore {

    private final List<EvalDimensionScore> dimensions;
    private final Map<String, EvalDimensionScore> dimensionById;
    private final Double total;
    private final int validCaseCount;
    private final int caseCount;
    private final List<String> errorCaseIds;
    private final List<String> invalidCaseIds;
    private final List<String> missingDimensionIds;
    private final EvalCredibility credibility;

    EvalRunScore(List<EvalDimensionScore> dimensions, Double total, int validCaseCount, int caseCount,
            List<String> errorCaseIds, List<String> invalidCaseIds, List<String> missingDimensionIds,
            EvalCredibility credibility) {
        this.dimensions = Collections.unmodifiableList(new ArrayList<EvalDimensionScore>(dimensions));
        Map<String, EvalDimensionScore> byId = new LinkedHashMap<String, EvalDimensionScore>();
        for (EvalDimensionScore dimension : dimensions) {
            byId.put(dimension.dimensionId(), dimension);
        }
        this.dimensionById = Collections.unmodifiableMap(byId);
        this.total = total;
        this.validCaseCount = validCaseCount;
        this.caseCount = caseCount;
        this.errorCaseIds = Collections.unmodifiableList(new ArrayList<String>(errorCaseIds));
        this.invalidCaseIds = Collections.unmodifiableList(new ArrayList<String>(invalidCaseIds));
        this.missingDimensionIds = Collections.unmodifiableList(new ArrayList<String>(missingDimensionIds));
        this.credibility = credibility;
    }

    /** 五维聚合结果，顺序与权重表一致。 */
    public List<EvalDimensionScore> dimensions() {
        return dimensions;
    }

    /** 按维度 id 取聚合结果；不存在返回 null。 */
    public EvalDimensionScore dimension(String dimensionId) {
        return dimensionById.get(dimensionId);
    }

    /** 五维加权总分（0..100，未取整）；存在维集合为空时为 null。 */
    public Double total() {
        return total;
    }

    /** 有效题数 = status ∈ {ok, vetoed} 的题数（§7.1）。 */
    public int validCaseCount() {
        return validCaseCount;
    }

    /** 参与聚合的题数（含 error / invalid）。 */
    public int caseCount() {
        return caseCount;
    }

    /** error 题号，按输入顺序；剔除分母并单列。 */
    public List<String> errorCaseIds() {
        return errorCaseIds;
    }

    /** invalid 题号，按输入顺序；剔除分母并单列。 */
    public List<String> invalidCaseIds() {
        return invalidCaseIds;
    }

    /** 缺维 id（有效题为 0），顺序与权重表一致。 */
    public List<String> missingDimensionIds() {
        return missingDimensionIds;
    }

    /** 是否存在缺维；存在时总分已按剩余权重归一（§6.6.2）。 */
    public boolean dimensionMissing() {
        return !missingDimensionIds.isEmpty();
    }

    public EvalCredibility credibility() {
        return credibility;
    }

    /** 全部条中文标注：缺维在前，可信度门槛在后。 */
    public List<String> annotations() {
        List<String> notes = new ArrayList<String>();
        if (dimensionMissing()) {
            StringBuilder builder = new StringBuilder("dimension_missing：缺 ");
            for (int i = 0; i < missingDimensionIds.size(); i++) {
                if (i > 0) {
                    builder.append("、");
                }
                builder.append(missingDimensionIds.get(i));
            }
            notes.add(builder.toString());
        }
        notes.addAll(credibility.annotations());
        return Collections.unmodifiableList(notes);
    }
}
