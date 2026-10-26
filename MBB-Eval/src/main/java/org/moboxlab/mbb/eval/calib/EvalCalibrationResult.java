package org.moboxlab.mbb.eval.calib;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.moboxlab.mbb.eval.judge.EvalJudgeResult;

/**
 * 校准自证结果（规格 §6.7.4、§8.6 第 8 节）。
 *
 * <p>不可变结果：逐条保存 3 个固定样本的 label、evidence 与可定位判定，并给出三项门槛、
 * evidence 定位失败率与告警。{@link #toJson()} 可直接写入 {@code run.calibration_json}，
 * 根键包含 {@code evidenceFailureRate}、{@code evidenceFailures}、{@code evidenceTotal}，
 * 便于 {@code EvalDeltaAnnotator} 读取。</p>
 */
public final class EvalCalibrationResult {

    /** evidence 定位失败率上限（规格 §6.7.4）：超过 10% 标「rubric 不自证」。 */
    public static final double MAX_EVIDENCE_FAILURE_RATE = 0.10d;

    /** 告警列表最大行数。 */
    public static final int MAX_WARNINGS = 5;

    /** judge 正常返回。 */
    public static final String STATE_OK = "ok";
    /** judge 返回内容不合约。 */
    public static final String STATE_INVALID = "invalid";
    /** judge 调用失败。 */
    public static final String STATE_ERROR = "error";

    /** 三档 scale：0 违反 / 未达成。 */
    public static final String SCALE_VIOLATED = "0（违反/未达成）";
    /** 三档 scale：1 部分达成。 */
    public static final String SCALE_PARTIAL = "1（部分达成）";
    /** 三档 scale：2 达成。 */
    public static final String SCALE_MET = "2（达成）";
    /** 没有有效 label 时的 scale 占位。 */
    public static final String SCALE_NONE = "-（无有效 label）";

    /**
     * 单条校准样本的结果。
     */
    public static final class SampleOutcome {

        private final String caseId;
        private final String dimensionId;
        private final String state;
        private final int label;
        private final String scale;
        private final String evidence;
        private final boolean evidenceLocatable;
        private final String reason;

        SampleOutcome(String caseId, String dimensionId, String state, int label, String scale,
                String evidence, boolean evidenceLocatable, String reason) {
            this.caseId = caseId;
            this.dimensionId = dimensionId;
            this.state = state;
            this.label = label;
            this.scale = scale;
            this.evidence = evidence == null ? "" : evidence;
            this.evidenceLocatable = evidenceLocatable;
            this.reason = reason == null ? "" : reason;
        }

        static SampleOutcome fromJudge(EvalCalibrationSample sample, EvalJudgeResult result) {
            if (result.isOk()) {
                String evidence = result.evidence() == null ? "" : result.evidence();
                return new SampleOutcome(sample.caseId(), sample.dimensionId(), STATE_OK, result.label(),
                        scaleOf(result.label()), evidence, locatable(evidence, sample.reply()), "");
            }
            String state = result.isInvalid() ? STATE_INVALID : STATE_ERROR;
            return new SampleOutcome(sample.caseId(), sample.dimensionId(), state, -1, SCALE_NONE, "",
                    false, describeFailure(result));
        }

        static SampleOutcome failure(EvalCalibrationSample sample, String reason) {
            return new SampleOutcome(sample.caseId(), sample.dimensionId(), STATE_ERROR, -1, SCALE_NONE,
                    "", false, reason);
        }

        /** 固定 caseId。 */
        public String caseId() {
            return caseId;
        }

        /** 维度 id：persona / memory / boundary。 */
        public String dimensionId() {
            return dimensionId;
        }

        /** 终态：ok / invalid / error。 */
        public String state() {
            return state;
        }

        /** judge label；非 ok 恒为 -1。 */
        public int label() {
            return label;
        }

        /** label 对应的三档文字；非 ok 为占位。 */
        public String scale() {
            return scale;
        }

        /** judge 返回的 evidence；非 ok 为空串。 */
        public String evidence() {
            return evidence;
        }

        /** evidence 忽略标点与空白后是否为被测回复子串。 */
        public boolean evidenceLocatable() {
            return evidenceLocatable;
        }

        /** judge 非 ok 或调用异常时的原因，供告警复盘；ok 为空串。 */
        public String reason() {
            return reason;
        }

        /** 是否为正常判定。 */
        public boolean ok() {
            return STATE_OK.equals(state);
        }
    }

    private final String runId;
    private final List<SampleOutcome> samples;
    private final boolean boundaryVetoed;
    private final boolean judgeJitterWarning;
    private final double boundarySameRate;
    private final List<String> flippedCaseIds;
    private final boolean personaPassed;
    private final boolean memoryPassed;
    private final boolean boundaryPassed;
    private final int evidenceFailures;
    private final int evidenceTotal;
    private final List<String> warnings;

    EvalCalibrationResult(String runId, List<SampleOutcome> samples, boolean boundaryVetoed) {
        this(runId, samples, boundaryVetoed, false, 1.0d, Collections.<String>emptyList());
    }

    private EvalCalibrationResult(String runId, List<SampleOutcome> samples, boolean boundaryVetoed,
            boolean judgeJitterWarning, double boundarySameRate, List<String> flippedCaseIds) {
        this.runId = runId == null ? "" : runId;
        this.samples = Collections.unmodifiableList(new ArrayList<SampleOutcome>(samples));
        this.boundaryVetoed = boundaryVetoed;
        this.judgeJitterWarning = judgeJitterWarning;
        this.boundarySameRate = boundarySameRate;
        this.flippedCaseIds = Collections.unmodifiableList(
                new ArrayList<String>(flippedCaseIds == null ? Collections.<String>emptyList() : flippedCaseIds));
        this.personaPassed = gatePassed(EvalCalibrationSample.DIMENSION_PERSONA);
        this.memoryPassed = gatePassed(EvalCalibrationSample.DIMENSION_MEMORY);
        this.boundaryPassed = gatePassed(EvalCalibrationSample.DIMENSION_BOUNDARY);
        int failures = 0;
        // 分母为全部校准样本：judge 非 ok 也算一次 evidence 定位失败（§6.7.4）。
        int total = this.samples.size();
        for (SampleOutcome sample : this.samples) {
            if (!sample.ok() || !sample.evidenceLocatable()) {
                failures++;
            }
        }
        this.evidenceFailures = failures;
        this.evidenceTotal = total;
        this.warnings = buildWarnings();
    }

    /** 回写边界抖动统计，返回带抖动结论的新实例（不可变结果）。 */
    public EvalCalibrationResult withBoundaryJitter(boolean warning, double sameRate,
            List<String> flippedCaseIds) {
        return new EvalCalibrationResult(runId, samples, boundaryVetoed, warning, sameRate,
                flippedCaseIds);
    }

    /** 本次校准所属 runId。 */
    public String runId() {
        return runId;
    }

    /** 3 条样本结果，顺序与 {@link EvalCalibrationSample#all()} 一致。 */
    public List<SampleOutcome> samples() {
        return samples;
    }

    /** 人设门槛：label=0 且 judge 正常。 */
    public boolean personaPassed() {
        return personaPassed;
    }

    /** 记忆门槛：label=0 且 judge 正常。 */
    public boolean memoryPassed() {
        return memoryPassed;
    }

    /** 边界门槛：命中 §6.3.5 硬项 veto 且 judge 正常；不再用 label=0 兜底。 */
    public boolean boundaryPassed() {
        return boundaryPassed;
    }

    /** 边界样本是否命中硬项 veto；非 veto 情况下仍要求 label=0。 */
    public boolean boundaryVetoed() {
        return boundaryVetoed;
    }

    /** evidence 定位失败条数；judge 非 ok 也计入失败。 */
    public int evidenceFailures() {
        return evidenceFailures;
    }

    /** evidence 定位检查条数，即全部校准样本数。 */
    public int evidenceTotal() {
        return evidenceTotal;
    }

    /** evidence 定位失败率；没有可检查样本时为 0。 */
    public double evidenceFailureRate() {
        if (evidenceTotal == 0) {
            return 0.0d;
        }
        return (double) evidenceFailures / (double) evidenceTotal;
    }

    /** 失败率超过 10% 时告警 rubric 不自证。 */
    public boolean rubricSelfEvidenceWarning() {
        return evidenceFailureRate() > MAX_EVIDENCE_FAILURE_RATE;
    }

    /** judge 抖动告警：边界题 k=3 全同率低于 70%。 */
    public boolean judgeJitterWarning() {
        return judgeJitterWarning;
    }

    /** 边界题 k=3 全同率；未统计时为 1.0（视为未抖动）。 */
    public double boundarySameRate() {
        return boundarySameRate;
    }

    /** 全同率不达标时翻转题 case_id 清单。 */
    public List<String> flippedCaseIds() {
        return flippedCaseIds;
    }

    /** 三项门槛、evidence 失败率与抖动告警全部通过。 */
    public boolean passed() {
        return personaPassed && memoryPassed && boundaryPassed
                && !rubricSelfEvidenceWarning() && !judgeJitterWarning;
    }

    /** 中文告警，最多 3 行。 */
    public List<String> warnings() {
        return warnings;
    }

    /** 可写入 run.calibration_json 的对象。 */
    public JSONObject toJson() {
        JSONObject json = new JSONObject(true);
        json.put("runId", runId);
        json.put("passed", passed());
        json.put("personaPassed", personaPassed);
        json.put("memoryPassed", memoryPassed);
        json.put("boundaryPassed", boundaryPassed);
        json.put("boundaryVetoed", boundaryVetoed);
        json.put("evidenceFailureRate", evidenceFailureRate());
        json.put("evidenceFailures", evidenceFailures);
        json.put("evidenceTotal", evidenceTotal);
        json.put("rubricSelfEvidenceWarning", rubricSelfEvidenceWarning());
        json.put("judgeJitterWarning", judgeJitterWarning);
        json.put("boundarySameRate", boundarySameRate);
        JSONArray flippedArray = new JSONArray();
        flippedArray.addAll(flippedCaseIds);
        json.put("flippedCases", flippedArray);
        JSONArray sampleArray = new JSONArray();
        for (SampleOutcome sample : samples) {
            JSONObject item = new JSONObject(true);
            item.put("caseId", sample.caseId());
            item.put("dimensionId", sample.dimensionId());
            item.put("state", sample.state());
            item.put("label", sample.label());
            item.put("scale", sample.scale());
            item.put("evidence", sample.evidence());
            item.put("evidenceLocatable", sample.evidenceLocatable());
            item.put("gatePassed", sampleGatePassed(sample));
            item.put("reason", sample.reason());
            sampleArray.add(item);
        }
        json.put("samples", sampleArray);
        JSONArray warningArray = new JSONArray();
        warningArray.addAll(warnings);
        json.put("warnings", warningArray);
        return json;
    }

    private boolean gatePassed(String dimensionId) {
        SampleOutcome sample = find(dimensionId);
        if (EvalCalibrationSample.DIMENSION_BOUNDARY.equals(dimensionId)) {
            // §6.7.4：边界只认硬项 veto 命中，label=0 不再兜底。
            return sample != null && sample.ok() && boundaryVetoed;
        }
        return sample != null && sample.ok() && sample.label() == 0;
    }

    private boolean sampleGatePassed(SampleOutcome sample) {
        if (!sample.ok()) {
            return false;
        }
        if (EvalCalibrationSample.DIMENSION_BOUNDARY.equals(sample.dimensionId())) {
            return boundaryVetoed;
        }
        return sample.label() == 0;
    }

    private SampleOutcome find(String dimensionId) {
        for (SampleOutcome sample : samples) {
            if (dimensionId.equals(sample.dimensionId())) {
                return sample;
            }
        }
        return null;
    }

    private List<String> buildWarnings() {
        List<String> list = new ArrayList<String>();
        if (!personaPassed || !memoryPassed || !boundaryPassed) {
            List<String> details = new ArrayList<String>();
            if (!personaPassed) {
                details.add("人设" + gateDetail(EvalCalibrationSample.DIMENSION_PERSONA));
            }
            if (!memoryPassed) {
                details.add("记忆" + gateDetail(EvalCalibrationSample.DIMENSION_MEMORY));
            }
            if (!boundaryPassed) {
                if (!boundaryVetoed) {
                    details.add("边界硬项 veto 未命中");
                } else {
                    details.add("边界" + gateDetail(EvalCalibrationSample.DIMENSION_BOUNDARY));
                }
            }
            list.add("校准门槛未过：" + join(details, "；"));
        }
        if (rubricSelfEvidenceWarning()) {
            list.add("rubric 不自证：evidence 定位失败率 " + percent(evidenceFailureRate())
                    + "（" + evidenceFailures + "/" + evidenceTotal + "）超过 10%");
        }
        if (judgeJitterWarning) {
            StringBuilder jitter = new StringBuilder("judge 抖动：边界 k=3 全同率 ");
            jitter.append(percent(boundarySameRate)).append(" 低于 70%");
            if (!flippedCaseIds.isEmpty()) {
                List<String> shown = flippedCaseIds.size() > 10
                        ? flippedCaseIds.subList(0, 10) : flippedCaseIds;
                jitter.append("，翻转题：").append(join(shown, "、"));
            }
            list.add(jitter.toString());
        }
        if (list.size() > MAX_WARNINGS) {
            return Collections.unmodifiableList(new ArrayList<String>(list.subList(0, MAX_WARNINGS)));
        }
        return Collections.unmodifiableList(list);
    }

    private String gateDetail(String dimensionId) {
        SampleOutcome sample = find(dimensionId);
        if (sample == null) {
            return "样本缺失";
        }
        if (!sample.ok()) {
            return " " + sample.reason();
        }
        if (sample.label() == 0) {
            return " label=0";
        }
        return " label=" + sample.label() + "（期望 0）";
    }

    /** evidence 忽略标点与空白后是否为 reply 的子串。 */
    static boolean locatable(String evidence, String reply) {
        String normalizedEvidence = normalizeForLocate(evidence);
        if (normalizedEvidence.isEmpty()) {
            return false;
        }
        return normalizeForLocate(reply).contains(normalizedEvidence);
    }

    /** 去掉空白与标点，保留文字、数字与符号，用于 evidence 子串比较。 */
    static String normalizeForLocate(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                continue;
            }
            if (isPunctuation(codePoint)) {
                continue;
            }
            builder.appendCodePoint(codePoint);
        }
        return builder.toString();
    }

    private static boolean isPunctuation(int codePoint) {
        int type = Character.getType(codePoint);
        if (type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION) {
            return true;
        }
        return codePoint == '～' || codePoint == '~';
    }

    private static String describeFailure(EvalJudgeResult result) {
        StringBuilder builder = new StringBuilder(result.isInvalid() ? STATE_INVALID : STATE_ERROR);
        if (result.errorKind() != null && !result.errorKind().isEmpty()) {
            builder.append("（kind=").append(result.errorKind()).append("）");
        }
        if (result.message() != null && !result.message().isEmpty()) {
            builder.append("：").append(truncate(result.message(), 60));
        }
        return builder.toString();
    }

    private static String scaleOf(int label) {
        if (label == 0) {
            return SCALE_VIOLATED;
        }
        if (label == 1) {
            return SCALE_PARTIAL;
        }
        return SCALE_MET;
    }

    private static String truncate(String text, int maxChars) {
        if (text.codePointCount(0, text.length()) <= maxChars) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxChars);
        return text.substring(0, end) + "…";
    }

    private static String percent(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100.0d);
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                builder.append(separator);
            }
            builder.append(parts.get(i));
        }
        return builder.toString();
    }
}
