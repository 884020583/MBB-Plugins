package org.moboxlab.mbb.eval.report;

import org.moboxlab.mbb.eval.baseline.EvalDeltaResult;
import org.moboxlab.mbb.eval.metrics.EvalMetrics;
import org.moboxlab.mbb.eval.store.EvalStore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 报表渲染（F-4：规格 §8.3 九节 Markdown、§8.4 对比口径、§8.5 可信度、§13.4 落盘）。
 *
 * <p>纯渲染：只读 {@link EvalReportData}，不读库、不记日志，同一输入重复渲染得到同一文本。
 * 九节标题与顺序逐字按 §8.3，任何不可得值都写固定文案（{@link EvalReportData#MISSING}、
 * 「样本不足（n=…，需 ≥…）」），不补 0、不省略。</p>
 *
 * <p>隐私：只写 run_id、分数、性能、告警与路径；不写完整 prompt、模型回复原文、昵称或
 * userID（§15.9 I2）。evidence 只以摘要形式出现，长度受限。</p>
 */
public final class EvalReportWriter {

    private static final String TITLE = "# MBB-Eval 评测报表";
    private static final String HEADING_1 = "## 1. 头部与结论";
    private static final String HEADING_2 = "## 2. 快照";
    private static final String HEADING_3 = "## 3. 五维";
    private static final String HEADING_4 = "## 4. 总分";
    private static final String HEADING_5 = "## 5. 与基线对比";
    private static final String HEADING_6 = "## 6. 题级明细";
    private static final String HEADING_7 = "## 7. 性能";
    private static final String HEADING_8 = "## 8. 校准与告警";
    private static final String HEADING_9 = "## 9. 附录";
    private static final String TREND_HEADING = "### 最近 10 次 run 总分趋势表";

    /**
     * 隐私口径允许写出的字段清单（I-2：§16.10、§15.9 I2）。
     *
     * <p>只含 run 标识、分数、性能、告警与报表路径；顺序固定，供测试与自查引用。</p>
     */
    public static final List<String> PRIVACY_FIELDS = Collections.unmodifiableList(Arrays.asList(
            "run_id", "tier", "status", "started_at", "ended_at", "duration_ms",
            "total_score", "baseline", "dimension_scores", "performance", "warnings",
            "report_path"));

    /**
     * 隐私口径明确排除的字段与内容（I-2：§16.10、§15.9 I2）。
     *
     * <p>history / trend 指历史 run 与趋势表；prompt / reply / message / content 指完整
     * 提示词与模型回复；persona 指 persona 原文与标识；昵称、userID 等个人信息一律不写。</p>
     */
    public static final List<String> PRIVACY_EXCLUDED_FIELDS = Collections.unmodifiableList(
            Arrays.asList("history", "trend", "case_detail", "evidence", "detail_json",
                    "config_snapshot_json", "calibration_json", "error_summary",
                    "prompt", "reply", "message", "content", "nickname", "persona", "user_id"));

    /** §8.3 第 2 节固定标注：git commit 不在 v1 采集范围。 */
    private static final String GIT_COMMIT_NOTE = "未采集";

    /** 有效题占比门槛（§8.5）：低于 80% 判不可信。 */
    private static final double VALID_CASE_RATIO_FLOOR = 0.8D;
    /** 非 ok 题占比门槛（§8.5）：高于 10% 判不可信。 */
    private static final double NON_OK_RATIO_CEILING = 0.1D;
    /** 总分 delta 判「变好 / 变差」时的 0.05 容差，与一位小数的显示精度对齐。 */
    private static final double DELTA_EPSILON = 0.05D;

    private static final String TABLE_SEPARATOR_7 = "| --- | --- | --- | --- | --- | --- | --- |";
    private static final String TABLE_SEPARATOR_6 = "| --- | --- | --- | --- | --- | --- |";
    private static final String TABLE_SEPARATOR_4 = "| --- | --- | --- | --- |";

    private EvalReportWriter() {
    }

    /**
     * 渲染九节 Markdown（§8.3）。
     *
     * @param data 装配结果；null 时按「数据缺失」渲染，九节结构仍然完整
     * @return 以单个换行结尾的 Markdown 文本，UTF-8 无 BOM
     */
    public static String render(EvalReportData data) {
        return render(data, null);
    }

    /**
     * 渲染九节 Markdown，并在第 5 节消费 F-6 的 {@link EvalDeltaResult}（§8.4）。
     *
     * <p>传入 delta 时，第 5 节使用配对 delta、维度置信区间、显著性四态、题级翻转、
     * 不可信变更与标注；delta 为 null 时回退装配结果自带的 {@link EvalReportData#delta()}，
     * 两者都没有才保留「未计算」占位。第 7 节按 {@link EvalReportData#aiUsage()} 挂 L3 旁证。</p>
     *
     * @param data  装配结果；null 时按「数据缺失」渲染
     * @param delta 基线比较结果；null 时使用装配结果自带的 delta
     * @return 以单个换行结尾的 Markdown 文本
     */
    public static String render(EvalReportData data, EvalDeltaResult delta) {
        EvalReportData report = data == null
                ? EvalReportData.missing("", "未装配报表数据") : data;
        EvalDeltaResult effective = delta != null ? delta : report.delta();
        StringBuilder sb = new StringBuilder(4096);
        sb.append(TITLE).append("\n\n");
        appendHeader(sb, report);
        appendSnapshot(sb, report);
        appendDimensions(sb, report);
        appendTotal(sb, report);
        appendBaseline(sb, report, effective);
        appendCases(sb, report);
        appendPerformance(sb, report);
        appendCalibration(sb, report);
        appendAppendix(sb, report);
        return sb.toString();
    }

    /**
     * 渲染隐私口径摘要（I-2：§16.10、§15.9 I2）。
     *
     * <p>只写 run 标识、分数、性能、告警与报表相对路径；不写快照、persona、题级明细、
     * evidence、趋势历史、校准 JSON 与错误摘要。既有 {@link #render(EvalReportData)}
     * 的九节输出保持不变。</p>
     *
     * @param data 装配结果；null 时按数据缺失渲染
     * @return 以换行结尾的 Markdown 文本
     */
    public static String renderPrivacy(EvalReportData data) {
        EvalReportData report = data == null
                ? EvalReportData.missing("", "未装配报表数据") : data;
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# MBB-Eval 评测摘要（隐私口径）").append("\n\n");
        appendPrivacyHeader(sb, report);
        appendPrivacyDimensions(sb, report);
        appendPrivacyPerformance(sb, report);
        appendPrivacyWarnings(sb, report);
        appendPrivacyPath(sb, report);
        return sb.toString();
    }

    /** 隐私口径第 1 节：run 标识、时间、总分与基线点值。 */
    private static void appendPrivacyHeader(StringBuilder sb, EvalReportData data) {
        sb.append("## 1. 结果").append('\n');
        appendBullet(sb, "run_id", orMissing(data.runId()));
        sb.append("- 起止时间：").append(orMissing(data.startedAt())).append(" → ")
                .append(orMissing(data.endedAt())).append('\n');
        sb.append("- 总时长：").append(secondsOrMissing(data.durationMs())).append('\n');
        sb.append("- tier：").append(orMissing(data.tier())).append('\n');
        sb.append("- status：").append(orMissing(data.status())).append('\n');
        sb.append("- 总分：").append(numOrMissing(data.totalScore())).append('\n');
        sb.append("- 基线：").append(privacyBaselineText(data)).append('\n');
        sb.append("- 说明：本口径只写分数、性能、告警与报表路径，不含 prompt、回复、昵称与 persona 原文")
                .append('\n');
        sb.append('\n');
    }

    private static String privacyBaselineText(EvalReportData data) {
        EvalReportData.BaselineView baseline = data.baseline();
        if (baseline == null || !baseline.present()) {
            return "无（不可比）";
        }
        if (!baseline.comparable()) {
            return orMissing(baseline.runId()) + "：不可比（" + orMissing(baseline.reason()) + "）";
        }
        if (data.totalScore() == null || baseline.totalScore() == null) {
            return orMissing(baseline.runId()) + "：不可比（总分缺失）";
        }
        return orMissing(baseline.runId()) + "：" + signed(data.totalScore().doubleValue()
                - baseline.totalScore().doubleValue()) + "（点值，不判显著性）";
    }

    /** 隐私口径第 2 节：五维得分，不写样本、case 与 evidence。 */
    private static void appendPrivacyDimensions(StringBuilder sb, EvalReportData data) {
        sb.append("## 2. 五维").append('\n');
        sb.append("| 维度 | 得分 |\n");
        sb.append("| --- | --- |\n");
        for (EvalReportData.Dimension dimension : data.dimensions()) {
            sb.append("| ").append(dimension.id()).append(' ').append(dimension.label())
                    .append(" | ").append(numOrMissing(dimension.score())).append(" |\n");
        }
        sb.append('\n');
    }

    /** 隐私口径第 3 节：复用第 7 节性能渲染，只把标题编号改为 3。 */
    private static void appendPrivacyPerformance(StringBuilder sb, EvalReportData data) {
        String performance = EvalReportPerformanceRenderer.render(data);
        if (performance.startsWith(EvalReportPerformanceRenderer.HEADING)) {
            sb.append("## 3. 性能")
                    .append(performance.substring(EvalReportPerformanceRenderer.HEADING.length()));
        } else {
            sb.append(performance);
        }
    }

    /** 隐私口径第 4 节：与九节报表同一份 §8.5 告警。 */
    private static void appendPrivacyWarnings(StringBuilder sb, EvalReportData data) {
        sb.append("## 4. 告警").append('\n');
        List<String> warnings = warningTexts(data);
        if (warnings.isEmpty()) {
            sb.append("- 告警：无").append('\n');
        } else {
            sb.append("- 告警：").append('\n');
            for (String warning : warnings) {
                sb.append("  - ").append(warning).append('\n');
            }
        }
        sb.append('\n');
    }

    /** 隐私口径第 5 节：只写报表相对路径。 */
    private static void appendPrivacyPath(StringBuilder sb, EvalReportData data) {
        sb.append("## 5. 报表路径").append('\n');
        sb.append("- 报表路径：")
                .append(data.runFound() ? relativeReportPath(data.runId()) : "reports/数据缺失")
                .append('\n');
        sb.append('\n');
    }

    /** §8.3 第 1 节：run_id、起止时间、tier、status、一句话结论。 */
    private static void appendHeader(StringBuilder sb, EvalReportData data) {
        sb.append(HEADING_1).append('\n');
        appendBullet(sb, "run_id", data.runFound() ? orMissing(data.runId()) : orMissing(data.runId()));
        sb.append("- 起止时间：").append(orMissing(data.startedAt())).append(" → ")
                .append(orMissing(data.endedAt())).append('\n');
        sb.append("- 总时长：").append(secondsOrMissing(data.durationMs())).append('\n');
        sb.append("- tier：").append(orMissing(data.tier())).append('\n');
        sb.append("- status：").append(orMissing(data.status())).append('\n');
        sb.append("- 结论：").append(verdict(data)).append('\n');
        sb.append("- 可信度：").append(reliabilityLabel(data)).append('\n');
        if (!data.runFound()) {
            sb.append("- 数据缺失：").append(orMissing(data.note())).append('\n');
        }
        if (data.status() != null && !EvalStore.STATUS_COMPLETED.equals(data.status())) {
            sb.append("- 注意：status 不是 COMPLETED，报表仅供排查（§13.4）").append('\n');
        }
        if (data.errorSummary() != null) {
            sb.append("- 错误摘要：").append(clip(data.errorSummary(), 200)).append('\n');
        }
        sb.append('\n');
    }

    /** §8.3 第 2 节：九键、题库 / persona / rubric / API 版本、配置指纹与 git commit。 */
    private static void appendSnapshot(StringBuilder sb, EvalReportData data) {
        sb.append(HEADING_2).append('\n');
        sb.append("| # | 键 | 值 |\n");
        sb.append("| --- | --- | --- |\n");
        List<String> keys = EvalReportData.nineKeyNames();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            sb.append("| ").append(i + 1).append(" | ").append(key).append(" | ")
                    .append(orMissing(data.nineKey(key))).append(" |\n");
        }
        appendBullet(sb, "bank_file", data.bankFile());
        appendBullet(sb, "bank_version", data.bankVersion());
        appendBullet(sb, "persona", joinOrMissing(data.personaId(), data.personaVersion(),
                data.personaHash()));
        appendBullet(sb, "rubric_version", data.rubricVersion());
        appendBullet(sb, "eval_api_version", data.evalApiVersion());
        sb.append("- config_snapshot：仅显示 config_fingerprint 前 8 位 ")
                .append(fingerprintPrefix(data.configFingerprint())).append('\n');
        sb.append("- git commit：").append(GIT_COMMIT_NOTE).append('\n');
        sb.append("- 数据完整性：case_result ").append(rowsRead(data.casesComplete(),
                data.cases().size(), data.casesExpected()))
                .append("；call ").append(rowsRead(data.callsComplete(), data.callsRead(), data.callsExpected()))
                .append('\n');
        sb.append('\n');
    }

    /** §8.3 第 3 节：五维得分、计权、样本与 cached 数。 */
    private static void appendDimensions(StringBuilder sb, EvalReportData data) {
        sb.append(HEADING_3).append('\n');
        sb.append("| 维度 | status | 得分 | 计权 | n | 样本（cached） | 备注 |\n");
        sb.append(TABLE_SEPARATOR_7).append('\n');
        for (EvalReportData.Dimension dimension : data.dimensions()) {
            sb.append("| ").append(dimension.id()).append(' ').append(dimension.label())
                    .append(" | ").append(dimension.status())
                    .append(" | ").append(numOrMissing(dimension.score()))
                    .append(" | ").append(dimension.score() == null ? "否" : "是")
                    .append(" | ").append(dimension.caseCount())
                    .append(" | ").append(dimension.sampleCount()).append("（")
                    .append(dimension.cachedCount()).append("）")
                    .append(" | ").append(dimensionNote(dimension)).append(" |\n");
        }
        sb.append("- 说明：得分 = 100 × mean(caseScore)，分母只含 ok 与 vetoed；error / invalid 不计入（§6.1）")
                .append('\n');
        sb.append('\n');
    }

    /** §8.3 第 4 节：总分点值、有效题、缺维、权重表与是否可信。 */
    private static void appendTotal(StringBuilder sb, EvalReportData data) {
        sb.append(HEADING_4).append('\n');
        sb.append("- 总分：").append(numOrMissing(data.totalScore())).append('\n');
        sb.append("- 有效题：").append(validCaseText(data)).append('\n');
        sb.append("- 缺维：").append(missingDimensions(data)).append('\n');
        sb.append("- 可信度：").append(reliabilityLabel(data)).append('\n');
        sb.append("- 口径：总分只报点值，不做置信区间与显著性判定（§8.4）").append('\n');
        sb.append("| 维度 | 权重 | 归一后权重 | 得分 |\n");
        sb.append(TABLE_SEPARATOR_4).append('\n');
        for (EvalReportData.Dimension dimension : data.dimensions()) {
            sb.append("| ").append(dimension.id()).append(' ').append(dimension.label())
                    .append(" | ").append(weight(dimension.weight()))
                    .append(" | ").append(weight(dimension.normalizedWeight()))
                    .append(" | ").append(numOrMissing(dimension.score()))
                    .append(" |\n");
        }
        sb.append('\n');
    }

    /**
     * §8.3 第 5 节：有 F-6 delta 时用 delta 渲染，否则退回「未计算」占位。
     *
     * <p>报表只读装配结果与 delta，不自行判定显著性；有 delta 时所有维度区间、结论、
     * 题级翻转与不可信变更都直接取自 {@link EvalDeltaResult}，避免报表另算一套口径。</p>
     */
    private static void appendBaseline(StringBuilder sb, EvalReportData data, EvalDeltaResult delta) {
        boolean hasBaseline = data.baseline() != null && data.baseline().present();
        if (delta == null || (!delta.available() && !hasBaseline)) {
            appendBaselineLegacy(sb, data);
            return;
        }
        sb.append(HEADING_5).append('\n');
        EvalReportData.BaselineView baseline = data.baseline();
        String baselineRunId = baseline != null && baseline.present()
                ? baseline.runId() : delta.baselineRunId();
        sb.append("- 基线：").append(orMissing(baselineRunId));
        if (baseline != null && baseline.present()) {
            sb.append("（").append(orMissing(baseline.createdAt())).append("）");
        }
        sb.append('\n');
        if (!delta.available()) {
            sb.append("- 判定：不可比（").append(orMissing(delta.note())).append("）").append('\n');
            sb.append("- 差异字段：").append(listOrNone(delta.differingKeys())).append('\n');
            sb.append("- delta：不可比（不出 delta 与显著性）").append('\n');
            appendDeltaAnnotations(sb, delta);
            sb.append('\n');
            return;
        }
        sb.append("- 判定：").append(delta.comparable() ? "可比（九键一致）"
                : "不可比（" + orMissing(delta.comparabilityReason()) + "）").append('\n');
        sb.append("- 差异字段：").append(listOrNone(delta.differingKeys())).append('\n');
        if (delta.totalDelta() == null) {
            sb.append("- 总分 delta：不可比（").append(delta.rubricChanged()
                    ? "rubric 已变" : "基线或本次总分缺失").append("）").append('\n');
        } else {
            sb.append("- 总分 delta：").append(signed(delta.totalDelta().doubleValue()))
                    .append("（点值，不判显著性）").append('\n');
        }
        sb.append("- 两侧可信度：基线 ").append(delta.baselineCredible() ? "可信" : "不可信")
                .append(" / 本次 ").append(delta.currentCredible() ? "可信" : "不可信")
                .append("；结论 ").append(delta.conclusion()).append('\n');
        sb.append("| 维度 | 基线分 | 本次分 | delta | ci_low | ci_high | 结论 |\n");
        sb.append(TABLE_SEPARATOR_7).append('\n');
        for (EvalReportData.Dimension dimension : data.dimensions()) {
            EvalDeltaResult.DimensionDelta dimensionDelta = delta.dimension(dimension.id());
            boolean unavailable = dimensionDelta == null;
            sb.append("| ").append(dimension.id()).append(' ').append(dimension.label())
                    .append(" | ").append(unavailable ? EvalReportData.MISSING
                            : numOrMissing(dimensionDelta.baselineScore()))
                    .append(" | ").append(unavailable ? EvalReportData.MISSING
                            : numOrMissing(dimensionDelta.currentScore()))
                    .append(" | ").append(unavailable ? EvalReportData.MISSING
                            : signedOrMissing(dimensionDelta.delta()))
                    .append(" | ").append(unavailable ? EvalReportData.MISSING
                            : numOrMissing(dimensionDelta.ciLow()))
                    .append(" | ").append(unavailable ? EvalReportData.MISSING
                            : numOrMissing(dimensionDelta.ciHigh()))
                    .append(" | ").append(unavailable ? "不可比"
                            : dimensionDelta.significance().label())
                    .append(" |\n");
        }
        sb.append("- 注：维度区间与显著性由 bootstrap 计算（1000 次、种子 41；§8.4）").append('\n');
        sb.append("- 题级：变好 ").append(delta.betterCount())
                .append(" / 变差 ").append(delta.worseCount())
                .append(" / 持平 ").append(delta.sameCount())
                .append(" / 翻转 ").append(delta.flippedCaseIds().size())
                .append("/").append(delta.flippedTotal()).append('\n');
        sb.append("- 不可信变更：ok→error ").append(delta.okToErrorCount())
                .append("；ok→invalid ").append(delta.okToInvalidCount())
                .append("（§8.4）").append('\n');
        for (EvalDeltaResult.CaseChange change : delta.untrustedChanges()) {
            sb.append("  - ").append(orMissing(change.caseId()))
                    .append("（").append(orMissing(change.dimensionId())).append("） ")
                    .append(change.label()).append('\n');
        }
        appendDeltaAnnotations(sb, delta);
        sb.append('\n');
    }

    /** F-6 标注逐条落到第 5 节，保证「不可信」不只出现在摘要里（§8.5、H11）。 */
    private static void appendDeltaAnnotations(StringBuilder sb, EvalDeltaResult delta) {
        List<String> annotations = delta.annotations();
        if (annotations.isEmpty()) {
            sb.append("- 标注：无").append('\n');
            return;
        }
        sb.append("- 标注：").append('\n');
        for (String annotation : annotations) {
            sb.append("  - ").append(orMissing(annotation)).append('\n');
        }
    }

    private static String signedOrMissing(Double value) {
        return value == null ? EvalReportData.MISSING : signed(value.doubleValue());
    }

    private static String listOrNone(List<String> values) {
        return values == null || values.isEmpty() ? "无" : join(values, "、");
    }

    /** 旧口径：没有 F-6 delta 时保留「未计算」占位，避免其它调用方拿到半份对比。 */
    private static void appendBaselineLegacy(StringBuilder sb, EvalReportData data) {
        sb.append(HEADING_5).append('\n');
        EvalReportData.BaselineView baseline = data.baseline();
        if (baseline == null || !baseline.present()) {
            sb.append("- 基线：无（不可比）").append('\n');
            sb.append("- 判定：不可比（无基线）").append('\n');
            sb.append("- 差异字段：无（无基线）").append('\n');
            sb.append("- delta：不可比（无基线，不出 delta 与显著性）").append('\n');
            sb.append('\n');
            return;
        }
        sb.append("- 基线：").append(orMissing(baseline.runId())).append("（")
                .append(orMissing(baseline.createdAt())).append("）").append('\n');
        List<String> differing = baseline.differingKeys();
        if (!baseline.comparable()) {
            sb.append("- 判定：不可比（").append(orMissing(baseline.reason())).append("）")
                    .append('\n');
            sb.append("- 差异字段：").append(differing.isEmpty()
                    ? "无" : join(differing, "、")).append('\n');
            sb.append("- delta：不可比（口径不同，不出 delta 与显著性）").append('\n');
            sb.append('\n');
            return;
        }
        double delta = data.totalScore().doubleValue() - baseline.totalScore().doubleValue();
        sb.append("- 判定：可比（九键一致）").append('\n');
        sb.append("- 差异字段：无").append('\n');
        sb.append("- 总分 delta：").append(signed(delta)).append("（点值，不判显著性）")
                .append('\n');
        sb.append("| 维度 | 基线分 | 本次分 | delta | ci_low | ci_high | 结论 |\n");
        sb.append(TABLE_SEPARATOR_7).append('\n');
        for (EvalReportData.Dimension dimension : data.dimensions()) {
            Double baseScore = baseline.dimensionScores().get(dimension.id());
            Double currentScore = dimension.score();
            Double dimensionDelta = baseScore == null || currentScore == null
                    ? null : Double.valueOf(currentScore.doubleValue() - baseScore.doubleValue());
            sb.append("| ").append(dimension.id()).append(' ').append(dimension.label())
                    .append(" | ").append(numOrMissing(baseScore))
                    .append(" | ").append(numOrMissing(currentScore))
                    .append(" | ").append(dimensionDelta == null ? EvalReportData.MISSING
                            : signed(dimensionDelta.doubleValue()))
                    .append(" | 未计算 | 未计算 | ")
                    .append(dimensionDelta == null ? "不可比" : "未计算")
                    .append(" |\n");
        }
        sb.append("- 注：维度区间与显著性由 bootstrap 计算，未接入时写「未计算」（§8.4）").append('\n');
        sb.append("- 题级：未计算（题级翻转对比未接入）").append('\n');
        sb.append("- 不可信变更：ok→error 未计算；ok→invalid 未计算（§8.4）").append('\n');
        sb.append('\n');
    }

    /** §8.3 第 6 节：题级明细，evidence 只给摘要。 */
    private static void appendCases(StringBuilder sb, EvalReportData data) {
        sb.append(HEADING_6).append('\n');
        if (data.cases().isEmpty()) {
            sb.append("- 数据缺失：库中无该 run 的 case 结果行").append('\n');
            sb.append('\n');
            return;
        }
        if (!data.casesComplete()) {
            sb.append("- 数据缺失：case 结果行读到 ").append(data.cases().size()).append("/")
                    .append(rowsExpected(data.casesExpected())).append("，明细不完整")
                    .append('\n');
        }
        sb.append("| case_result_id | case_id | dimension | status | score | label | evidence 摘要 |\n");
        sb.append(TABLE_SEPARATOR_7).append('\n');
        for (EvalReportData.CaseRecord record : data.cases()) {
            sb.append("| ").append(orMissing(record.caseResultId()))
                    .append(" | ").append(orMissing(record.caseId()))
                    .append(" | ").append(orMissing(record.dimension()))
                    .append(" | ").append(orMissing(record.status()))
                    .append(" | ").append(numOrMissing(record.score()))
                    .append(" | ").append(orMissing(record.label()))
                    .append(" | ").append(orMissing(record.evidenceSummary()))
                    .append(" |\n");
        }
        sb.append('\n');
    }

    /** §8.3 第 7 节：委托性能渲染器，保持九节顺序与固定不可得标注。 */
    private static void appendPerformance(StringBuilder sb, EvalReportData data) {
        sb.append(EvalReportPerformanceRenderer.render(data));
    }

    /** §8.3 第 8 节：校准结果、预算消耗与 §8.5 全部告警。 */
    private static void appendCalibration(StringBuilder sb, EvalReportData data) {
        sb.append(HEADING_8).append('\n');
        sb.append("- 校准结果：").append(calibrationText(data)).append('\n');
        if (data.metrics() == null) {
            sb.append("- 预算消耗：数据缺失").append('\n');
        } else {
            EvalMetrics.Budget budget = data.metrics().budget();
            sb.append("- 预算消耗：").append(budget.used()).append("/").append(budget.budget())
                    .append("（").append(percent(budget.ratio())).append("）").append('\n');
        }
        List<String> warnings = warnings(data);
        if (warnings.isEmpty()) {
            sb.append("- 告警：无").append('\n');
        } else {
            sb.append("- 告警：").append('\n');
            for (String warning : warnings) {
                sb.append("  - ").append(warning).append('\n');
            }
        }
        sb.append('\n');
    }

    /** §8.3 第 9 节：附录与最近 10 次 run 总分趋势表。 */
    private static void appendAppendix(StringBuilder sb, EvalReportData data) {
        sb.append(HEADING_9).append('\n');
        appendBullet(sb, "bank_file", data.bankFile());
        appendBullet(sb, "bank_version", data.bankVersion());
        appendBullet(sb, "bank_hash", data.bankHash());
        appendBullet(sb, "persona", joinOrMissing(data.personaId(), data.personaVersion(),
                data.personaHash()));
        appendBullet(sb, "judge_profile", data.judgeProfile());
        appendBullet(sb, "judge_model", data.judgeModel());
        appendBullet(sb, "generation_model", data.generationModel());
        appendBullet(sb, "rubric_version", data.rubricVersion());
        appendBullet(sb, "eval_api_version", data.evalApiVersion());
        appendBullet(sb, "生成命令", "/eval report " + orMissing(data.runId()));
        sb.append('\n');
        sb.append(TREND_HEADING).append('\n');
        sb.append("| run_id | 日期 | tier | 总分 | delta | 显著 |\n");
        sb.append(TABLE_SEPARATOR_6).append('\n');
        if (data.trend().isEmpty()) {
            sb.append("| 数据缺失 | 数据缺失 | 数据缺失 | 数据缺失 | - | 不可比 |\n");
        } else {
            for (EvalReportData.TrendEntry entry : data.trend()) {
                sb.append("| ").append(orMissing(entry.runId()))
                        .append(" | ").append(orMissing(entry.startedAt()))
                        .append(" | ").append(orMissing(entry.tier()))
                        .append(" | ").append(numOrMissing(entry.totalScore()))
                        .append(" | ").append(entry.delta() == null ? "-"
                                : signed(entry.delta().doubleValue()))
                        .append(" | ").append(orMissing(entry.significance()))
                        .append(" |\n");
            }
        }
        sb.append("- 注：delta 为与上一次 run 的差值；总分只报点值，不判显著性（§8.4）").append('\n');
        sb.append('\n');
    }

    /**
     * 写报表文件（§8.3 路径、§13.4 落盘）。
     *
     * <p>先写同目录临时文件再原子改名，避免半截报表；同名文件按 F20 覆盖重建。</p>
     *
     * <p>{@code COMPLETED} 的 run 还会在同目录写群内摘要纯文本
     * {@code run-<run_id>.summary.txt}；返回值仍是完整报表文件，{@code report_path} 语义不变。</p>
     *
     * @param data       装配结果
     * @param reportsDir 报表目录（{@code getDataFolder()/reports}）
     * @return 写入的完整报表文件
     * @throws IOException 目录创建或写入失败，交由命令层记录与重试
     */
    public static File writeToFile(EvalReportData data, File reportsDir) throws IOException {
        return writeToFile(data, reportsDir, null);
    }

    /**
     * 写报表文件，并让第 5 节使用给定的 F-6 delta（§8.3、§8.4）。
     *
     * @param data       装配结果
     * @param reportsDir 报表目录（{@code getDataFolder()/reports}）
     * @param delta      基线比较结果；null 时使用 {@link EvalReportData#delta()}
     * @return 写入的完整报表文件（群摘要是同目录附属文件，见 {@link #summaryFileName(String)}）
     * @throws IOException 目录创建或写入失败；旧文件不被破坏
     */
    public static File writeToFile(EvalReportData data, File reportsDir, EvalDeltaResult delta)
            throws IOException {
        if (data == null) {
            throw new IllegalArgumentException("报表数据不能为空");
        }
        if (reportsDir == null) {
            throw new IllegalArgumentException("报表目录不能为空");
        }
        String runId = data.runId();
        if (runId == null || !EvalStore.isValidRunId(runId)) {
            throw new IllegalArgumentException("run_id 不合法，拒绝写报表文件：" + runId);
        }
        if (!reportsDir.isDirectory() && !reportsDir.mkdirs() && !reportsDir.isDirectory()) {
            throw new IOException("创建报表目录失败：" + reportsDir.getAbsolutePath());
        }
        File target = new File(reportsDir, fileName(runId));
        writeAtomically(target, render(data, delta));
        writeGroupSummary(data, reportsDir, runId);
        return target;
    }

    /**
     * 同目录写群内摘要纯文本（F-7：§8.6）：{@code run-<run_id>.summary.txt}。
     *
     * <p>仅 {@code COMPLETED} 的 run 生成摘要（I-2 口径，≤15 行）；摘要为附属产物，
     * 写失败只跳过摘要，不破坏已落盘的完整报表，也不向命令层抛错（§13.4）。</p>
     */
    private static void writeGroupSummary(EvalReportData data, File reportsDir, String runId) {
        if (!EvalGroupSummaryFormatter.canSummarize(data)) {
            return;
        }
        String text = EvalGroupSummaryFormatter.toPlainText(data);
        File target = new File(reportsDir, summaryFileName(runId));
        try {
            writeAtomically(target, text);
        } catch (IOException e) {
            return;
        }
    }

    /** 先写同目录临时文件再原子改名；失败时保留旧文件。 */
    private static void writeAtomically(File target, String text) throws IOException {
        File temp = File.createTempFile(target.getName() + ".", ".tmp", target.getParentFile());
        boolean moved = false;
        try {
            Files.write(temp.toPath(), text.getBytes(StandardCharsets.UTF_8));
            moveAtomically(temp, target);
            moved = true;
        } finally {
            if (!moved) {
                temp.delete();
            }
        }
    }

    /** 群内摘要文件名：run-<run_id>.summary.txt（纯文本，不是 Markdown）。 */
    public static String summaryFileName(String runId) {
        return "run-" + runId + ".summary.txt";
    }

    /** 报表文件名：run-<run_id>.md（§8.3 逐字）。 */
    public static String fileName(String runId) {
        return "run-" + runId + ".md";
    }

    /**
     * 群内摘要与隐私口径使用的报表相对路径（§8.6 固定文案）。
     *
     * @param runId run 标识；缺失时返回 {@code reports/数据缺失}
     * @return 形如 {@code reports/run-<run_id>.md} 的相对路径
     */
    public static String relativeReportPath(String runId) {
        if (runId == null || runId.isEmpty()) {
            return "reports/" + EvalReportData.MISSING;
        }
        return "reports/" + fileName(runId);
    }

    private static void moveAtomically(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 一句话结论（§8.3 第 1 节）：只按点值判变好 / 变差 / 持平，不可比给出原因。 */
    private static String verdict(EvalReportData data) {
        if (!data.runFound()) {
            return "不可比（数据缺失）";
        }
        EvalReportData.BaselineView baseline = data.baseline();
        if (baseline == null || !baseline.present()) {
            return "不可比（无基线）";
        }
        if (!baseline.comparable()) {
            return "不可比（" + orMissing(baseline.reason()) + "）";
        }
        if (data.totalScore() == null || baseline.totalScore() == null) {
            return "不可比（总分缺失）";
        }
        double delta = data.totalScore().doubleValue() - baseline.totalScore().doubleValue();
        String word;
        if (delta > DELTA_EPSILON) {
            word = "变好";
        } else if (delta < -DELTA_EPSILON) {
            word = "变差";
        } else {
            word = "持平";
        }
        return word + "（" + signed(delta) + "）";
    }

    /** 可信度与 §8 告警共用同一份判定，避免两处口径漂移。 */
    private static String reliabilityLabel(EvalReportData data) {
        return warnings(data).isEmpty() ? "可信" : "不可信";
    }

    /** §8.5 告警：只用库中已采集的数据判定，缺数据的门槛写明不可得。 */
    private static List<String> warnings(EvalReportData data) {
        List<String> warnings = new ArrayList<String>();
        int totalCases = 0;
        int nonOkCases = 0;
        for (EvalReportData.Dimension dimension : data.dimensions()) {
            totalCases += dimension.caseCount();
            nonOkCases += dimension.errorCount() + dimension.invalidCount();
        }
        if (totalCases > 0) {
            double validRatio = (double) data.validCaseCount() / (double) totalCases;
            if (validRatio < VALID_CASE_RATIO_FLOOR - 1.0E-9D) {
                warnings.add(String.format(Locale.ROOT,
                        "不可信：有效题 %d/%d（%s）低于 80%%（§8.5）",
                        Integer.valueOf(data.validCaseCount()), Integer.valueOf(totalCases),
                        percent(Double.valueOf(validRatio))));
            }
            double nonOkRatio = (double) nonOkCases / (double) totalCases;
            if (nonOkRatio > NON_OK_RATIO_CEILING + 1.0E-9D) {
                warnings.add(String.format(Locale.ROOT,
                        "不可信：非 ok 题占比 %s 超过 10%%（§8.5）", percent(Double.valueOf(nonOkRatio))));
            }
        }
        EvalReportData.BaselineView baseline = data.baseline();
        if (baseline != null && baseline.present() && !baseline.comparable()
                && baseline.differingKeys().contains("rubric_version")) {
            warnings.add("rubric 已变：基线 rubric_version 与本次不同，不与历史直接比（§8.5）");
        }
        warnings.addAll(EvalReportPerformanceRenderer.performanceWarnings(data));
        List<String> partial = new ArrayList<String>();
        List<String> missing = new ArrayList<String>();
        for (EvalReportData.Dimension dimension : data.dimensions()) {
            if (EvalReportData.STATUS_PARTIAL.equals(dimension.status())) {
                partial.add(dimension.id() + "（" + dimension.sampleCount() + " 样本）");
            }
            if (EvalReportData.STATUS_DIMENSION_MISSING.equals(dimension.status())) {
                missing.add(dimension.id());
            } else if (EvalReportData.MISSING.equals(dimension.status())) {
                missing.add(dimension.id() + "（数据缺失）");
            }
        }
        if (!missing.isEmpty()) {
            warnings.add("维度缺失（dimension_missing）：" + join(missing, "、") + "（§8.5）");
        }
        if (!partial.isEmpty()) {
            warnings.add("样本不足维度：" + join(partial, "、") + "（§8.5）");
        }
        if (data.judgeModel() != null && data.judgeModel().equals(data.generationModel())) {
            warnings.add("同家族 judge：judge_model 与 generation_model 相同（"
                    + data.judgeModel() + "），分数仅作趋势（§6.3.4）");
        }
        if (data.metrics() == null) {
            warnings.add("指标未装配，性能与预算不可判定（§8.3 第 7 节）");
        }
        return warnings;
    }

    /**
     * 供群内摘要等复用 §8.5 告警判定，避免摘要与报表两处口径漂移。
     *
     * @param data 报表数据；null 按数据缺失处理
     * @return 可直接展示的中文告警文案；顺序固定
     */
    static List<String> warningTexts(EvalReportData data) {
        EvalReportData report = data == null
                ? EvalReportData.missing("", "未装配报表数据") : data;
        return warnings(report);
    }

    private static String validCaseText(EvalReportData data) {
        int total = 0;
        for (EvalReportData.Dimension dimension : data.dimensions()) {
            total += dimension.caseCount();
        }
        if (total <= 0) {
            return data.validCaseCount() + "/0（" + EvalReportData.MISSING + "）";
        }
        double ratio = (double) data.validCaseCount() / (double) total;
        return data.validCaseCount() + "/" + total + "（" + percent(Double.valueOf(ratio)) + "）";
    }

    private static String missingDimensions(EvalReportData data) {
        List<String> parts = new ArrayList<String>();
        for (EvalReportData.Dimension dimension : data.dimensions()) {
            if (dimension.score() == null) {
                parts.add(dimension.id() + "（" + dimension.status() + "）");
            }
        }
        return parts.isEmpty() ? "无" : join(parts, "、");
    }

    private static String dimensionNote(EvalReportData.Dimension dimension) {
        StringBuilder note = new StringBuilder();
        note.append(dimension.judge() ? "k=" + dimension.kUsed() : "确定性");
        if (dimension.vetoedCount() > 0) {
            note.append("；vetoed ").append(dimension.vetoedCount());
        }
        if (dimension.errorCount() > 0) {
            note.append("；error ").append(dimension.errorCount());
        }
        if (dimension.invalidCount() > 0) {
            note.append("；invalid ").append(dimension.invalidCount());
        }
        if (dimension.missingScoreCount() > 0) {
            note.append("；score 缺失 ").append(dimension.missingScoreCount());
        }
        if (EvalReportData.STATUS_DIMENSION_MISSING.equals(dimension.status())) {
            note.append("；全部 error/invalid");
        }
        return note.toString();
    }

    private static String calibrationText(EvalReportData data) {
        String json = data.calibrationJson();
        if (json == null) {
            return EvalReportData.MISSING;
        }
        String flat = clip(json, 160);
        try {
            com.alibaba.fastjson.JSONObject object =
                    com.alibaba.fastjson.JSONObject.parseObject(json);
            for (String key : new String[] {"result", "status", "passed", "conclusion"}) {
                Object value = object.get(key);
                if (value != null) {
                    return String.valueOf(value);
                }
            }
        } catch (RuntimeException e) {
            return flat;
        }
        return flat;
    }

    private static String rowsRead(boolean complete, int read, int expected) {
        if (expected < 0) {
            return read < 0 ? EvalReportData.MISSING
                    : read + "/?（计数失败）";
        }
        int actual = read;
        if (actual < 0) {
            return "?/" + expected;
        }
        String prefix = complete ? "" : EvalReportData.MISSING;
        return prefix.isEmpty() ? actual + "/" + expected
                : actual + "/" + expected + "（" + EvalReportData.MISSING + "）";
    }

    private static String rowsExpected(int expected) {
        return expected < 0 ? "?" : String.valueOf(expected);
    }

    private static String fingerprintPrefix(String fingerprint) {
        if (fingerprint == null) {
            return EvalReportData.MISSING;
        }
        return fingerprint.length() <= 8 ? fingerprint : fingerprint.substring(0, 8);
    }

    private static String joinOrMissing(String first, String second, String third) {
        if (first == null && second == null && third == null) {
            return EvalReportData.MISSING;
        }
        return orMissing(first) + " / " + orMissing(second) + " / " + orMissing(third);
    }

    private static String secondsOrMissing(Long millis) {
        if (millis == null) {
            return EvalReportData.MISSING;
        }
        return String.format(Locale.ROOT, "%.1f 秒", Double.valueOf(millis.doubleValue() / 1000D));
    }

    private static String sampleShort(long n, int need) {
        return "样本不足（n=" + n + "，需 ≥" + need + "）";
    }

    private static void appendBullet(StringBuilder sb, String label, String value) {
        sb.append("- ").append(label).append('：').append(orMissing(value)).append('\n');
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String orMissing(String value) {
        return value == null || value.isEmpty() ? EvalReportData.MISSING : value;
    }

    private static String numOrMissing(Double value) {
        return value == null ? EvalReportData.MISSING
                : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String seconds(Double value) {
        return value == null ? EvalReportData.MISSING
                : String.format(Locale.ROOT, "%.1f 秒", Double.valueOf(value.doubleValue() / 1000D));
    }

    private static String percent(Double ratio) {
        return ratio == null ? EvalReportData.MISSING
                : String.format(Locale.ROOT, "%.1f%%", Double.valueOf(ratio.doubleValue() * 100D));
    }

    private static String signed(double value) {
        return String.format(Locale.ROOT, "%+.1f", Double.valueOf(value));
    }

    private static String weight(double value) {
        return String.format(Locale.ROOT, "%.2f", Double.valueOf(value));
    }

    private static String clip(String value, int limit) {
        if (value == null) {
            return null;
        }
        String flat = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (flat.length() <= limit) {
            return flat;
        }
        return flat.substring(0, limit) + "…";
    }
}
