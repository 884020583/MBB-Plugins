package org.moboxlab.mbb.eval.score;

/**
 * 单维聚合结果（D-4：规格 §6.1、§6.6.2）。
 *
 * <p>{@code validCount} = ok 题数 + vetoed 题数；error / invalid 只计数，不进分母。
 * 该维有效题为 0 时 {@code score} 为 null（缺维），{@code effectiveWeight} 为 0，
 * 总分改由剩余权重归一（§6.6.2）。</p>
 */
public final class EvalDimensionScore {

    private final String dimensionId;
    private final double weight;
    private final double effectiveWeight;
    private final Double score;
    private final int caseCount;
    private final int okCount;
    private final int vetoedCount;
    private final int errorCount;
    private final int invalidCount;

    EvalDimensionScore(String dimensionId, double weight, double effectiveWeight, Double score,
            int caseCount, int okCount, int vetoedCount, int errorCount, int invalidCount) {
        this.dimensionId = dimensionId;
        this.weight = weight;
        this.effectiveWeight = effectiveWeight;
        this.score = score;
        this.caseCount = caseCount;
        this.okCount = okCount;
        this.vetoedCount = vetoedCount;
        this.errorCount = errorCount;
        this.invalidCount = invalidCount;
    }

    public String dimensionId() {
        return dimensionId;
    }

    /** rubric 声明的原始权重。 */
    public double weight() {
        return weight;
    }

    /** 缺维重归一后的计权；该维缺维时为 0。 */
    public double effectiveWeight() {
        return effectiveWeight;
    }

    /** 维度分（0..100，未取整）；该维有效题为 0 时为 null。 */
    public Double score() {
        return score;
    }

    /** 计入维度分母的题数（ok + vetoed）。 */
    public int validCount() {
        return okCount + vetoedCount;
    }

    public int caseCount() {
        return caseCount;
    }

    public int okCount() {
        return okCount;
    }

    public int vetoedCount() {
        return vetoedCount;
    }

    public int errorCount() {
        return errorCount;
    }

    public int invalidCount() {
        return invalidCount;
    }

    /** 该维是否缺维（有效题为 0，维分为 null）。 */
    public boolean missing() {
        return score == null;
    }

    /** 是否参与总分计权。 */
    public boolean countedInTotal() {
        return score != null;
    }
}
