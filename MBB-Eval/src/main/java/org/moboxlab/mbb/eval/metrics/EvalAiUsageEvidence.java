package org.moboxlab.mbb.eval.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * L3 旁证结果（E-4：规格 §7.5、§15.5 E11）。
 *
 * <p>不可变值对象：只暴露 usage 行与固定说明文本，不暴露任何分数或指标快照；
 * {@link #text()} 无论是否有数据都固定带 {@link #CALIBER_NOTE}（「不同口径，不可换算」），
 * 数据缺失时明确写「不可得」。</p>
 *
 * <p>文本只比数量级：逐日列出 requests / successes / failures / token 与线上平均耗时，
 * 不做分位、不做成功率换算、不写回任何表。</p>
 */
public final class EvalAiUsageEvidence {

    /** 跨口径固定标注：线上统计与评测逐调用口径不同，禁止换算（§7.5）。 */
    public static final String CALIBER_NOTE = "不同口径，不可换算";
    /** 数据缺失时的固定词（E11）。 */
    public static final String MISSING_TEXT = "不可得";

    private final String profile;
    private final String startDate;
    private final String endDate;
    private final List<EvalAiUsageDay> rows;
    private final String missingReason;

    EvalAiUsageEvidence(String profile, String startDate, String endDate,
            List<EvalAiUsageDay> rows, String missingReason) {
        this.profile = profile;
        this.startDate = startDate;
        this.endDate = endDate;
        List<EvalAiUsageDay> copy = rows == null
                ? Collections.<EvalAiUsageDay>emptyList() : new ArrayList<EvalAiUsageDay>(rows);
        this.rows = Collections.unmodifiableList(copy);
        this.missingReason = this.rows.isEmpty() ? missingReason : null;
    }

    public String profile() {
        return profile;
    }

    public String startDate() {
        return startDate;
    }

    public String endDate() {
        return endDate;
    }

    /** 逐日聚合行，按日期升序；无数据时为空列表。 */
    public List<EvalAiUsageDay> rows() {
        return rows;
    }

    /** 是否取到了数据；false 表示 E11 的「不可得」。 */
    public boolean available() {
        return !rows.isEmpty();
    }

    /** 不可得原因；有数据时为 null。 */
    public String missingReason() {
        return missingReason;
    }

    /**
     * 渲染固定的只读旁证文本。
     *
     * <p>格式固定，便于报表挂接方原样拼进性能节：首行标明 L3 与表名，第二行固定写
     * 口径与 {@link #CALIBER_NOTE}；缺失时写「不可得」并带原因；有数据时逐日列计数。</p>
     */
    public String text() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("L3 旁证（plugin_mbb_ai_usage，只读）").append('\n');
        sb.append("- 口径：MBB-AI 线上聚合统计；仅比数量级，不参与评测指标；")
                .append(CALIBER_NOTE).append('\n');
        sb.append("- profile：").append(profile).append("；日期：").append(startDate)
                .append(" 至 ").append(endDate).append('\n');
        if (!available()) {
            sb.append("- 数据：").append(MISSING_TEXT)
                    .append("（").append(missingReason == null ? "未取到记录" : missingReason)
                    .append("）").append('\n');
            return sb.toString();
        }
        for (EvalAiUsageDay row : rows) {
            sb.append("- ").append(row.statDate())
                    .append("：requests ").append(row.requests())
                    .append("；successes ").append(row.successes())
                    .append("；failures ").append(row.failures())
                    .append("；token prompt ").append(row.promptTokens())
                    .append(" / completion ").append(row.completionTokens())
                    .append(" / total ").append(row.totalTokens())
                    .append("；平均耗时 ").append(averageLatency(row.averageLatencyMs()))
                    .append('\n');
        }
        sb.append("- 仅比数量级：不写回评测表，不换算为逐调用口径").append('\n');
        return sb.toString();
    }

    private static String averageLatency(Double latencyMs) {
        if (latencyMs == null) {
            return MISSING_TEXT;
        }
        return String.format(Locale.ROOT, "%.1f 毫秒", latencyMs);
    }
}
