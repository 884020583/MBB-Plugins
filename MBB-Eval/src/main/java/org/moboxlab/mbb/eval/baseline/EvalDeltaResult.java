package org.moboxlab.mbb.eval.baseline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次基线比较的完整 delta 结果（规格 #50 §8.3、§8.4），可直接交给报表渲染。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>总分只报点值 {@link #totalDelta()}，没有置信区间与显著性；</li>
 *   <li>维度各自带 {@code ciLow/ciHigh} 与四态显著性；</li>
 *   <li>ok→error、ok→invalid 单列 {@link #untrustedChanges()}，不并入变差、不参与配对；</li>
 *   <li>三条硬门槛只改标注（{@link #credible()}、{@link #annotations()}），不改分；</li>
 *   <li>不可比时维度 delta 为 null、显著性为「不可比」，但总分点值仍给出。</li>
 * </ul>
 */
public final class EvalDeltaResult {

    /** 维度显著性四态（§8.4）。 */
    public enum Significance {
        /** 显著变好。 */
        SIGNIFICANT_UP("显著↑"),
        /** 显著变差。 */
        SIGNIFICANT_DOWN("显著↓"),
        /** 持平（不显著）。 */
        FLAT("持平（不显著）"),
        /** 不可比（九键不同、样本不足或 rubric 已变）。 */
        NOT_COMPARABLE("不可比");

        private final String label;

        Significance(String label) {
            this.label = label;
        }

        /** 中文展示名。 */
        public String label() {
            return label;
        }
    }

    private final String baselineRunId;
    private final String currentRunId;
    private final boolean available;
    private final String note;
    private final boolean comparable;
    private final String comparabilityReason;
    private final List<String> differingKeys;
    private final List<String> missingKeys;
    private final boolean rubricChanged;
    private final boolean baselineCredible;
    private final boolean currentCredible;
    private final List<String> annotations;
    private final Double baselineTotal;
    private final Double currentTotal;
    private final Double totalDelta;
    private final int pairedCount;
    private final Map<String, DimensionDelta> dimensions;
    private final List<CaseChange> untrustedChanges;
    private final List<String> flippedCaseIds;
    private final int betterCount;
    private final int worseCount;
    private final int sameCount;
    private final int flippedTotal;
    private final int k3SameCount;
    private final int k3Total;

    private EvalDeltaResult(Builder builder) {
        this.baselineRunId = builder.baselineRunId;
        this.currentRunId = builder.currentRunId;
        this.available = builder.available;
        this.note = builder.note;
        this.comparable = builder.comparable;
        this.comparabilityReason = builder.comparabilityReason;
        this.differingKeys = Collections.unmodifiableList(new ArrayList<String>(builder.differingKeys));
        this.missingKeys = Collections.unmodifiableList(new ArrayList<String>(builder.missingKeys));
        this.rubricChanged = builder.rubricChanged;
        this.baselineCredible = builder.baselineCredible;
        this.currentCredible = builder.currentCredible;
        this.annotations = Collections.unmodifiableList(new ArrayList<String>(builder.annotations));
        this.baselineTotal = builder.baselineTotal;
        this.currentTotal = builder.currentTotal;
        this.totalDelta = builder.totalDelta;
        this.pairedCount = builder.pairedCount;
        this.dimensions = Collections.unmodifiableMap(new LinkedHashMap<String, DimensionDelta>(builder.dimensions));
        this.untrustedChanges = Collections.unmodifiableList(new ArrayList<CaseChange>(builder.untrustedChanges));
        this.flippedCaseIds = Collections.unmodifiableList(new ArrayList<String>(builder.flippedCaseIds));
        this.betterCount = builder.betterCount;
        this.worseCount = builder.worseCount;
        this.sameCount = builder.sameCount;
        this.flippedTotal = builder.flippedTotal;
        this.k3SameCount = builder.k3SameCount;
        this.k3Total = builder.k3Total;
    }

    public String baselineRunId() {
        return baselineRunId;
    }

    public String currentRunId() {
        return currentRunId;
    }

    /** 两侧 run 与 case 结果是否都取到了；false 时其余字段只有说明。 */
    public boolean available() {
        return available;
    }

    /** 取数失败或缺少基线时的人类可读说明，正常情况下为 null。 */
    public String note() {
        return note;
    }

    /** 九键可比（§8.4）。 */
    public boolean comparable() {
        return comparable;
    }

    /** 不可比原因；可比时为 null。 */
    public String comparabilityReason() {
        return comparabilityReason;
    }

    /** 九键中取值不同的键名；顺序同 {@link EvalRunComparability#KEY_NAMES}。 */
    public List<String> differingKeys() {
        return differingKeys;
    }

    /** 九键中缺失的键名。 */
    public List<String> missingKeys() {
        return missingKeys;
    }

    /** rubric_version 与基线不同：不出 delta、不标显著（§8.5 第三条硬门槛）。 */
    public boolean rubricChanged() {
        return rubricChanged;
    }

    /** 基线一侧是否可信（三条硬门槛 + 附加标注）。 */
    public boolean baselineCredible() {
        return baselineCredible;
    }

    /** 当前一侧是否可信。 */
    public boolean currentCredible() {
        return currentCredible;
    }

    /** 任一侧不可信即整份比较不可信：delta 只报点值与标注，不给显著结论（§8.5）。 */
    public boolean credible() {
        return baselineCredible && currentCredible;
    }

    /** 首屏可信标记：「可信」/「不可信」。 */
    public String credibilityLabel() {
        return credible() ? "可信" : "不可信";
    }

    public List<String> annotations() {
        return annotations;
    }

    /** 基线总分点值；取不到时为 null。 */
    public Double baselineTotal() {
        return baselineTotal;
    }

    /** 当前总分点值；取不到时为 null。 */
    public Double currentTotal() {
        return currentTotal;
    }

    /** 总分点值差：current − baseline，单位分；没有基线或 rubric 已变时为 null。 */
    public Double totalDelta() {
        return totalDelta;
    }

    /** 参与配对的有效题数（两侧 status=ok）。 */
    public int pairedCount() {
        return pairedCount;
    }

    /** 总分最小步长 100/n；n 为 0 时返回 NaN。 */
    public double totalMinimumStep() {
        return pairedCount > 0 ? EvalDeltaCalculator.minimumStep(pairedCount) : Double.NaN;
    }

    /** 按维度 id 顺序（权重表顺序）返回维度 delta。 */
    public Map<String, DimensionDelta> dimensions() {
        return dimensions;
    }

    public DimensionDelta dimension(String dimensionId) {
        return dimensions.get(dimensionId);
    }

    /** ok→error / ok→invalid 的题，单列「不可信变更」。 */
    public List<CaseChange> untrustedChanges() {
        return untrustedChanges;
    }

    /** 翻转题 case_id：k=3 采样 label 不全同（§6.7.2）。 */
    public List<String> flippedCaseIds() {
        return flippedCaseIds;
    }

    public int betterCount() {
        return betterCount;
    }

    public int worseCount() {
        return worseCount;
    }

    public int sameCount() {
        return sameCount;
    }

    /** 该 run 中 k=3 且样本可判的题数，即翻转率分母。 */
    public int flippedTotal() {
        return flippedTotal;
    }

    public int k3SameCount() {
        return k3SameCount;
    }

    public int k3Total() {
        return k3Total;
    }

    /** k=3 全同率；没有可判样本时为 NaN。 */
    public double k3AllSameRate() {
        return k3Total > 0 ? (double) k3SameCount / (double) k3Total : Double.NaN;
    }

    public int untrustedChangeCount() {
        return untrustedChanges.size();
    }

    public int okToErrorCount() {
        int count = 0;
        for (CaseChange change : untrustedChanges) {
            if ("error".equals(change.toStatus())) {
                count++;
            }
        }
        return count;
    }

    public int okToInvalidCount() {
        int count = 0;
        for (CaseChange change : untrustedChanges) {
            if ("invalid".equals(change.toStatus())) {
                count++;
            }
        }
        return count;
    }

    /** 是否允许出趋势结论：可比、可信、rubric 未变、且总分点值存在。 */
    public boolean trendAllowed() {
        return comparable && credible() && !rubricChanged && totalDelta != null;
    }

    /**
     * 一句话结论：变好 / 变差 / 持平 / 不可比（§8.3 §1）。
     *
     * <p>不可比优先；其次不可信；再次 rubric 已变；再看点值是否达到最小步长。</p>
     */
    public String conclusion() {
        if (!available) {
            return "不可比";
        }
        if (!comparable || totalDelta == null) {
            return "不可比";
        }
        if (!credible() || rubricChanged) {
            return "不可比";
        }
        if (pairedCount <= 0) {
            return "不可比";
        }
        if (Math.abs(totalDelta) < totalMinimumStep()) {
            return "持平";
        }
        return totalDelta > 0 ? "变好" : "变差";
    }

    /** 配对题级变更：单题从一个状态变为另一个状态。 */
    public static final class CaseChange {

        private final String caseId;
        private final String dimensionId;
        private final String fromStatus;
        private final String toStatus;

        CaseChange(String caseId, String dimensionId, String fromStatus, String toStatus) {
            this.caseId = caseId;
            this.dimensionId = dimensionId;
            this.fromStatus = fromStatus;
            this.toStatus = toStatus;
        }

        public String caseId() {
            return caseId;
        }

        public String dimensionId() {
            return dimensionId;
        }

        public String fromStatus() {
            return fromStatus;
        }

        public String toStatus() {
            return toStatus;
        }

        /** 例如 {@code ok→error}。 */
        public String label() {
            return fromStatus + "→" + toStatus;
        }

        @Override
        public String toString() {
            return caseId + "(" + dimensionId + ") " + label();
        }
    }

    /** 单个维度的 delta 与显著性四态。 */
    public static final class DimensionDelta {

        private final String dimensionId;
        private final Double baselineScore;
        private final Double currentScore;
        private final Double delta;
        private final Double ciLow;
        private final Double ciHigh;
        private final int pairedCount;
        private final double minimumStep;
        private final Significance significance;
        private final String note;
        private final int betterCount;
        private final int worseCount;
        private final int sameCount;

        DimensionDelta(String dimensionId, Double baselineScore, Double currentScore,
                       Double delta, Double ciLow, Double ciHigh, int pairedCount,
                       double minimumStep, Significance significance, String note,
                       int betterCount, int worseCount, int sameCount) {
            this.dimensionId = dimensionId;
            this.baselineScore = baselineScore;
            this.currentScore = currentScore;
            this.delta = delta;
            this.ciLow = ciLow;
            this.ciHigh = ciHigh;
            this.pairedCount = pairedCount;
            this.minimumStep = minimumStep;
            this.significance = significance;
            this.note = note;
            this.betterCount = betterCount;
            this.worseCount = worseCount;
            this.sameCount = sameCount;
        }

        public String dimensionId() {
            return dimensionId;
        }

        public Double baselineScore() {
            return baselineScore;
        }

        public Double currentScore() {
            return currentScore;
        }

        /** 维度点值差，单位分；不可比或 rubric 已变时为 null。 */
        public Double delta() {
            return delta;
        }

        public Double ciLow() {
            return ciLow;
        }

        public Double ciHigh() {
            return ciHigh;
        }

        public int pairedCount() {
            return pairedCount;
        }

        public double minimumStep() {
            return minimumStep;
        }

        public Significance significance() {
            return significance;
        }

        /** 附加说明，例如「样本不足：配对 n 少于 20」；没有则为 null。 */
        public String note() {
            return note;
        }

        public boolean significant() {
            return significance == Significance.SIGNIFICANT_UP
                    || significance == Significance.SIGNIFICANT_DOWN;
        }

        public int betterCount() {
            return betterCount;
        }

        public int worseCount() {
            return worseCount;
        }

        public int sameCount() {
            return sameCount;
        }
    }

    /** 构造器；只由 {@link EvalDeltaService} 使用。 */
    static final class Builder {

        private String baselineRunId;
        private String currentRunId;
        private boolean available;
        private String note;
        private boolean comparable;
        private String comparabilityReason;
        private final List<String> differingKeys = new ArrayList<String>();
        private final List<String> missingKeys = new ArrayList<String>();
        private boolean rubricChanged;
        private boolean baselineCredible = true;
        private boolean currentCredible = true;
        private final List<String> annotations = new ArrayList<String>();
        private Double baselineTotal;
        private Double currentTotal;
        private Double totalDelta;
        private int pairedCount;
        private final Map<String, DimensionDelta> dimensions = new LinkedHashMap<String, DimensionDelta>();
        private final List<CaseChange> untrustedChanges = new ArrayList<CaseChange>();
        private final List<String> flippedCaseIds = new ArrayList<String>();
        private int betterCount;
        private int worseCount;
        private int sameCount;
        private int flippedTotal;
        private int k3SameCount;
        private int k3Total;

        Builder baselineRunId(String value) {
            this.baselineRunId = value;
            return this;
        }

        Builder currentRunId(String value) {
            this.currentRunId = value;
            return this;
        }

        Builder available(boolean value) {
            this.available = value;
            return this;
        }

        Builder note(String value) {
            this.note = value;
            return this;
        }

        Builder comparable(boolean value) {
            this.comparable = value;
            return this;
        }

        Builder comparabilityReason(String value) {
            this.comparabilityReason = value;
            return this;
        }

        Builder differingKeys(List<String> values) {
            this.differingKeys.clear();
            if (values != null) {
                this.differingKeys.addAll(values);
            }
            return this;
        }

        Builder missingKeys(List<String> values) {
            this.missingKeys.clear();
            if (values != null) {
                this.missingKeys.addAll(values);
            }
            return this;
        }

        Builder rubricChanged(boolean value) {
            this.rubricChanged = value;
            return this;
        }

        Builder baselineCredible(boolean value) {
            this.baselineCredible = value;
            return this;
        }

        Builder currentCredible(boolean value) {
            this.currentCredible = value;
            return this;
        }

        Builder annotation(String value) {
            if (value != null && !value.isEmpty()) {
                this.annotations.add(value);
            }
            return this;
        }

        Builder annotations(List<String> values) {
            if (values != null) {
                for (String value : values) {
                    annotation(value);
                }
            }
            return this;
        }

        Builder baselineTotal(Double value) {
            this.baselineTotal = value;
            return this;
        }

        Builder currentTotal(Double value) {
            this.currentTotal = value;
            return this;
        }

        Builder totalDelta(Double value) {
            this.totalDelta = value;
            return this;
        }

        Builder pairedCount(int value) {
            this.pairedCount = value;
            return this;
        }

        Builder dimension(DimensionDelta value) {
            if (value != null) {
                this.dimensions.put(value.dimensionId(), value);
            }
            return this;
        }

        Builder untrustedChange(CaseChange value) {
            if (value != null) {
                this.untrustedChanges.add(value);
            }
            return this;
        }

        Builder flippedCaseId(String caseId) {
            if (caseId != null) {
                this.flippedCaseIds.add(caseId);
            }
            return this;
        }

        Builder betterCount(int value) {
            this.betterCount = value;
            return this;
        }

        Builder worseCount(int value) {
            this.worseCount = value;
            return this;
        }

        Builder sameCount(int value) {
            this.sameCount = value;
            return this;
        }

        Builder flippedTotal(int value) {
            this.flippedTotal = value;
            return this;
        }

        Builder k3SameCount(int value) {
            this.k3SameCount = value;
            return this;
        }

        Builder k3Total(int value) {
            this.k3Total = value;
            return this;
        }

        EvalDeltaResult build() {
            return new EvalDeltaResult(this);
        }
    }
}
