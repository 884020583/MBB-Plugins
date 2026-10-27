package org.moboxlab.mbb.eval.report;

import org.moboxlab.mbb.eval.baseline.EvalDeltaResult;
import org.moboxlab.mbb.eval.metrics.EvalMetrics;
import org.moboxlab.mbb.eval.store.EvalStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 群内纯文本摘要（F-7：规格 §8.6、§15.5 F19；I-2：§16.10、§15.9 I2）。
 *
 * <p>只读 {@link EvalReportData}，与报表同源取值，不读库、不读报表文件、不写库。
 * 输出为纯文本：不使用 Markdown 标记，不使用 emoji，行数不超过 {@link #MAX_LINES}。
 * 只有 {@code status=COMPLETED} 的 run 才生成摘要；其余状态返回中文拒绝原因，并给出
 * {@code /eval status} 指引，不输出任何分数。</p>
 *
 * <p>隐私：只出现 run 标识、分数、性能、告警与报表相对路径；不出现完整 prompt、
 * 模型回复原文、昵称、userID、persona 原文、case_id 或 evidence 摘要（§15.9 I2）。</p>
 */
public final class EvalGroupSummaryFormatter {

    /** 摘要最大行数（§8.6）。 */
    public static final int MAX_LINES = 15;

    /** 五维在摘要中的固定顺序，按 §8.6 样例（人设、记忆、边界、知识、风格）。 */
    private static final List<String> DIMENSION_ORDER = Collections.unmodifiableList(Arrays.asList(
            EvalReportData.DIM_PERSONA, EvalReportData.DIM_MEMORY, EvalReportData.DIM_BOUNDARY,
            EvalReportData.DIM_KNOWLEDGE, EvalReportData.DIM_STYLE));

    /** 方向判定容差，与报表一位小数显示精度对齐。 */
    private static final double DELTA_EPSILON = 0.05D;

    /** 告警行最大字符数，超出截断，避免单行过长。 */
    private static final int WARNING_LINE_LIMIT = 180;

    private EvalGroupSummaryFormatter() {
    }

    /**
     * 是否可生成群内摘要。
     *
     * @param data 报表装配结果；null、run 缺失或状态不是 COMPLETED 时返回 false
     * @return true 表示 {@link #toPlainText(EvalReportData)} 会输出摘要
     */
    public static boolean canSummarize(EvalReportData data) {
        return data != null
                && data.runFound()
                && EvalStore.STATUS_COMPLETED.equals(data.status());
    }

    /**
     * 生成群内纯文本摘要（§8.6）。
     *
     * <p>非 COMPLETED run 不输出摘要，返回拒绝原因与 {@code /eval status} 指引；
     * 返回文本同样不含 Markdown 与 emoji，可直接作为群消息发送。</p>
     *
     * @param data 报表装配结果；null 按数据缺失处理
     * @return 不超过 15 行的纯文本；不可生成时为拒绝原因
     */
    public static String toPlainText(EvalReportData data) {
        if (!canSummarize(data)) {
            return refusal(data);
        }
        List<String> lines = new ArrayList<String>();
        lines.add(header(data));
        lines.add("总分 " + numOrMissing(data.totalScore()));
        lines.add(baselineLine(data));
        lines.add(dimensionsLine(data));
        lines.add(levelLine(data));
        lines.add(performanceLine(data));
        lines.add(warningsLine(data));
        lines.add("报表 " + EvalReportWriter.relativeReportPath(data.runId()));
        return join(lines, "\n");
    }

    // ===== 各要素 =====

    /** 首行：run 标识、档位与开始时间（只到分钟）。 */
    private static String header(EvalReportData data) {
        return "【MBB-Eval】" + orMissing(data.runId()) + "（" + orMissing(data.tier())
                + "，" + shortTime(data.startedAt()) + "）";
    }

    /** 基线对比：基线 run_id、delta 与显著性；不可比时只写原因。 */
    private static String baselineLine(EvalReportData data) {
        EvalReportData.BaselineView baseline = data.baseline();
        if (baseline == null || !baseline.present()) {
            return "基线 无（不可比）";
        }
        if (!baseline.comparable()) {
            return "基线 " + orMissing(baseline.runId()) + "：不可比（"
                    + orMissing(baseline.reason()) + "）";
        }
        if (data.totalScore() == null || baseline.totalScore() == null) {
            return "基线 " + orMissing(baseline.runId()) + "：不可比（总分缺失）";
        }
        EvalDeltaResult paired = data.delta();
        if (paired != null && paired.available() && paired.totalDelta() != null) {
            return "基线 " + orMissing(baseline.runId()) + "："
                    + signed(paired.totalDelta().doubleValue()) + "（点值，不判显著性）";
        }
        double delta = data.totalScore().doubleValue() - baseline.totalScore().doubleValue();
        return "基线 " + orMissing(baseline.runId()) + "：" + signed(delta) + "（显著性未计算）";
    }

    /**
     * 题级对比行：有 F-6 配对 delta 时写真实计数，否则保留「未计算」占位（§8.6）。
     */
    private static String levelLine(EvalReportData data) {
        EvalDeltaResult delta = data.delta();
        if (delta == null || !delta.available()) {
            return "题级 变好/变差/持平/翻转 未计算（题级对比未接入）；ok→error 未计算";
        }
        return "题级 变好 " + delta.betterCount() + " / 变差 " + delta.worseCount()
                + " / 持平 " + delta.sameCount() + " / 翻转 "
                + delta.flippedCaseIds().size() + "/" + delta.flippedTotal()
                + "；ok→error " + delta.okToErrorCount();
    }

    /** 五维分与方向：可比时按基线维度分给出上箭头 / 下箭头 / 等号。 */
    private static String dimensionsLine(EvalReportData data) {
        StringBuilder sb = new StringBuilder(96);
        EvalReportData.BaselineView baseline = data.baseline();
        boolean comparable = baseline != null && baseline.present() && baseline.comparable();
        for (int i = 0; i < DIMENSION_ORDER.size(); i++) {
            String dimensionId = DIMENSION_ORDER.get(i);
            EvalReportData.Dimension dimension = findDimension(data, dimensionId);
            Double score = dimension == null ? null : dimension.score();
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(shortLabel(dimensionId)).append(' ').append(numOrMissing(score));
            if (comparable && score != null) {
                Double baselineScore = baseline.dimensionScores().get(dimensionId);
                if (baselineScore != null) {
                    double delta = score.doubleValue() - baselineScore.doubleValue();
                    if (delta > DELTA_EPSILON) {
                        sb.append('↑');
                    } else if (delta < -DELTA_EPSILON) {
                        sb.append('↓');
                    } else {
                        sb.append('=');
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 性能摘要：p50/p90、调用数、成功率与缓存命中数。 */
    private static String performanceLine(EvalReportData data) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("性能 ");
        EvalMetrics metrics = data.metrics();
        if (metrics == null) {
            sb.append("p50 数据缺失 / p90 数据缺失 / 调用 ").append(data.callsUsed())
                    .append('/').append(data.callsBudget())
                    .append(" / 成功率 数据缺失 / 缓存 数据缺失");
            return sb.toString();
        }
        EvalMetrics.Latency latency = metrics.latency();
        sb.append("p50 ").append(secondsOrNote(latency.p50(), latency.n()))
                .append(" / p90 ").append(secondsOrNote(latency.p90(), latency.n()));
        EvalMetrics.Calls calls = metrics.calls();
        EvalMetrics.Budget budget = metrics.budget();
        long callBudget = budget == null ? data.callsBudget() : budget.budget();
        sb.append(" / 调用 ").append(calls.total()).append('/').append(callBudget)
                .append(" / 成功率 ").append(percentOrMissing(calls.successRate()))
                .append(" / 缓存 ").append(calls.cached());
        return sb.toString();
    }

    /** 告警行：复用报表同一份 §8.5 判定，多条以分号连接并限制长度。 */
    private static String warningsLine(EvalReportData data) {
        List<String> warnings = EvalReportWriter.warningTexts(data);
        if (warnings.isEmpty()) {
            return "告警 无";
        }
        String text = "告警 " + plain(join(warnings, "；"));
        if (text.length() <= WARNING_LINE_LIMIT) {
            return text;
        }
        return text.substring(0, WARNING_LINE_LIMIT) + "…";
    }

    /** 非 COMPLETED / 数据缺失时的拒绝原因，仍为纯文本。 */
    private static String refusal(EvalReportData data) {
        if (data == null) {
            return "无法生成群内摘要：数据缺失，仅 COMPLETED 的 run 可出摘要。"
                    + "请用 /eval status 查看当前 run。";
        }
        if (!data.runFound()) {
            return "无法生成群内摘要：run " + orMissing(data.runId()) + " 不在库中，"
                    + "仅 COMPLETED 的 run 可出摘要。请用 /eval status 查看当前 run。";
        }
        return "无法生成群内摘要：run " + orMissing(data.runId()) + " 当前状态 "
                + orMissing(data.status()) + "，仅 COMPLETED 的 run 可出摘要。"
                + "请用 /eval status " + orMissing(data.runId()) + " 查看进度。";
    }

    // ===== 工具 =====

    private static EvalReportData.Dimension findDimension(EvalReportData data, String dimensionId) {
        for (EvalReportData.Dimension dimension : data.dimensions()) {
            if (dimensionId.equals(dimension.id())) {
                return dimension;
            }
        }
        return null;
    }

    /** 摘要中的维度简称（§8.6 样例逐字）。 */
    private static String shortLabel(String dimensionId) {
        if (EvalReportData.DIM_PERSONA.equals(dimensionId)) {
            return "人设";
        }
        if (EvalReportData.DIM_MEMORY.equals(dimensionId)) {
            return "记忆";
        }
        if (EvalReportData.DIM_BOUNDARY.equals(dimensionId)) {
            return "边界";
        }
        if (EvalReportData.DIM_KNOWLEDGE.equals(dimensionId)) {
            return "知识";
        }
        if (EvalReportData.DIM_STYLE.equals(dimensionId)) {
            return "风格";
        }
        return dimensionId;
    }

    /** 起始时间裁到「MM-dd HH:mm」（§8.6 样例）；格式不符时原样返回。 */
    private static String shortTime(String startedAt) {
        if (startedAt == null) {
            return EvalReportData.MISSING;
        }
        String text = startedAt.trim();
        if (text.length() >= 16 && text.charAt(4) == '-' && text.charAt(7) == '-'
                && text.charAt(10) == ' ') {
            return text.substring(5, 16);
        }
        return text;
    }

    /**
     * 告警文案转纯文本：去掉换行与 Markdown 标记字符，下划线改为连字符，
     * 避免 calls_used 这类标识被 Markdown 当作强调。
     */
    private static String plain(String value) {
        String text = value.replace('\r', ' ').replace('\n', ' ').trim();
        text = text.replace("**", "").replace("~~", "").replace("#", "").replace("|", "");
        String backtick = String.valueOf((char) 96);
        return text.replace(backtick, "").replace('_', '-');
    }

    private static String secondsOrNote(Double millis, long sampleCount) {
        if (millis != null) {
            return String.format(Locale.ROOT, "%.1fs",
                    Double.valueOf(millis.doubleValue() / 1000D));
        }
        return sampleCount <= 0L ? "无样本" : "样本不足";
    }

    private static String percentOrMissing(Double ratio) {
        return ratio == null ? EvalReportData.MISSING
                : String.format(Locale.ROOT, "%.1f%%", Double.valueOf(ratio.doubleValue() * 100D));
    }

    private static String numOrMissing(Double value) {
        return value == null ? EvalReportData.MISSING
                : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String signed(double value) {
        return String.format(Locale.ROOT, "%+.1f", Double.valueOf(value));
    }

    private static String orMissing(String value) {
        return value == null || value.isEmpty() ? EvalReportData.MISSING : value;
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
}
