package org.moboxlab.mbb.eval.metrics;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.moboxlab.moboxbot.API.PluginLogger;

import org.moboxlab.mbb.eval.log.EvalLog;

/**
 * L3 只读旁证服务（E-4：规格 §7.5、§16.6；验收 §15.5 E11）。
 *
 * <p>职责只有三件：按 profile×日期向 {@link EvalAiUsageSource} 取数、在本地再过滤并
 * 按日期合并、输出带固定标注的旁证文本。它不写任何表、不产生 {@link EvalMetrics}、
 * 不参与总分或性能指标，也不把线上值换算成逐调用口径。</p>
 *
 * <p>数据源由构造注入，测试用内存假源即可覆盖 E11 全部分支；读取异常按「不可得」兜底，
 * 不让旁证失败影响任何评测流程。</p>
 */
public final class EvalAiUsageEvidenceService {

    /** 日期参数形状：yyyy-MM-dd。 */
    private static final Pattern DATE_SHAPE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    /** 无数据时的不可得原因。 */
    private static final String REASON_NO_ROWS = "统计表无该 profile×日期的记录";
    /** 读取失败时的不可得原因。 */
    private static final String REASON_READ_FAILED = "统计表读取失败";

    private final EvalAiUsageSource source;
    private final PluginLogger logger;

    public EvalAiUsageEvidenceService(EvalAiUsageSource source) {
        this(source, null);
    }

    /**
     * @param source 只读数据源；不得为 null
     * @param logger 可选日志；读取失败时记一条中文 WARN，可为 null
     */
    public EvalAiUsageEvidenceService(EvalAiUsageSource source, PluginLogger logger) {
        if (source == null) {
            throw new IllegalArgumentException("L3 旁证数据源不能为空");
        }
        this.source = source;
        this.logger = logger;
    }

    /** 查询单日：profile × 日期。 */
    public EvalAiUsageEvidence collect(String profile, String date) {
        return collect(profile, date, date);
    }

    /**
     * 查询日期区间内的 profile 用量，并合并同一天的多个 model/action 行。
     *
     * @param profile 统计表 profile 名；空白抛 IllegalArgumentException
     * @param startDate 起始日期 yyyy-MM-dd（含）
     * @param endDate 结束日期 yyyy-MM-dd（含），不得早于起始日期
     * @return 只读旁证；无数据或读取失败时 {@link EvalAiUsageEvidence#available()} 为 false
     */
    public EvalAiUsageEvidence collect(String profile, String startDate, String endDate) {
        String wantedProfile = requireText(profile, "profile");
        String start = requireDate(startDate, "起始日期");
        String end = requireDate(endDate, "结束日期");
        if (start.compareTo(end) > 0) {
            throw new IllegalArgumentException("起始日期不得晚于结束日期：" + start + " > " + end);
        }

        List<EvalAiUsageDay> raw = null;
        String missingReason = null;
        try {
            raw = source.queryDailyUsage(wantedProfile, start, end);
        } catch (RuntimeException e) {
            EvalLog.warn(logger, "L3 旁证读取失败，按不可得处理：" + e.getClass().getSimpleName());
            missingReason = REASON_READ_FAILED;
        }

        Map<String, long[]> merged = new TreeMap<String, long[]>();
        if (raw != null) {
            for (EvalAiUsageDay row : raw) {
                if (row == null || row.statDate() == null || row.profile() == null) {
                    continue;
                }
                if (!wantedProfile.equals(row.profile())) {
                    continue;
                }
                String date = row.statDate();
                if (date.compareTo(start) < 0 || date.compareTo(end) > 0) {
                    continue;
                }
                long[] values = merged.get(date);
                if (values == null) {
                    values = new long[8];
                    merged.put(date, values);
                }
                values[0] += row.requests();
                values[1] += row.successes();
                values[2] += row.failures();
                values[3] += row.promptTokens();
                values[4] += row.completionTokens();
                values[5] += row.totalTokens();
                values[6] += row.totalLatencyMs();
                values[7] += row.cachedHits();
            }
        }

        List<EvalAiUsageDay> rows = new ArrayList<EvalAiUsageDay>(merged.size());
        for (Map.Entry<String, long[]> entry : merged.entrySet()) {
            long[] values = entry.getValue();
            rows.add(new EvalAiUsageDay(entry.getKey(), wantedProfile,
                    values[0], values[1], values[2], values[3],
                    values[4], values[5], values[6], values[7]));
        }
        if (missingReason == null && rows.isEmpty()) {
            missingReason = REASON_NO_ROWS;
        }
        return new EvalAiUsageEvidence(wantedProfile, start, end, rows, missingReason);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.trim();
    }

    private static String requireDate(String value, String label) {
        String text = requireText(value, label);
        if (!DATE_SHAPE.matcher(text).matches()) {
            throw new IllegalArgumentException(label + "必须是 yyyy-MM-dd：" + text);
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(text, position);
        if (parsed == null || position.getIndex() != text.length()) {
            throw new IllegalArgumentException(label + "不是合法日期：" + text);
        }
        return text;
    }
}
