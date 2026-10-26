package org.moboxlab.mbb.eval.command;

import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.moboxlab.mbb.eval.baseline.EvalBaselineService;
import org.moboxlab.mbb.eval.baseline.EvalBaselineService.BaselineOutcome;
import org.moboxlab.mbb.eval.report.EvalReportData;
import org.moboxlab.mbb.eval.runner.EvalRunService;
import org.moboxlab.mbb.eval.runner.EvalRunner;
import org.moboxlab.mbb.eval.store.EvalStore;

/**
 * 写侧命令实现（规格 #50 §9.3、§9.6、§9.7，验收 G9 / G10 / G15）。
 *
 * <p>本类只做两个写侧处理器：{@code baseline set} 与 {@code abort}。两者都返回不含
 * {@code [eval]} 前缀的纯文本行，由 {@link EvalCommand} 统一加前缀后发送。</p>
 *
 * <p>{@code baseline set} 严格按 §9.3 校验（runId 格式、理由必填），再委派
 * {@link EvalBaselineService#explicitSet(EvalStore, String, String)}：服务内部只读 run 行、
 * 只写基线表，全程不改 run 状态；切换 active 采用 §8.1 的三步单语句写法，不做事务。
 * 拒绝与失败都如实回执原因与下一步动作（§9.3.1）。</p>
 *
 * <p>{@code abort} 只用 {@link EvalRunService#abort()} 的结果对象说话，不自己改库：
 * ABORTED 回「已中止 runId」；没有进行中的 run 回「当前没有进行中的评测」（重复 abort 幂等）；
 * CAS 未执行时按 §13.4 口径说明以数据库现行状态为准、不重试，重启后由启动期 init 回收为
 * ABORTED(orphan)。</p>
 */
public final class EvalWriteCommand {

    /** 五维顺序与中文名（§6.1），只用于回执展示，不参与计分。 */
    private static final Map<String, String> DIMENSION_LABELS;

    /** 未采到的值统一文案，与只读命令保持一致。 */
    private static final String MISSING = EvalReportData.MISSING;

    /** baseline set 的用法（缺参、格式错、理由为空时复用）。 */
    private static final String USAGE_SET =
            "用法 /eval baseline set <runId> <理由>；理由必填，只接受 COMPLETED 的 full run";

    /** abort 不接受参数。 */
    private static final String USAGE_ABORT = "用法 /eval abort；不接受参数";

    static {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put(EvalReportData.DIM_PERSONA, "人设一致性");
        labels.put(EvalReportData.DIM_BOUNDARY, "边界安全");
        labels.put(EvalReportData.DIM_MEMORY, "记忆召回");
        labels.put(EvalReportData.DIM_KNOWLEDGE, "知识正确性");
        labels.put(EvalReportData.DIM_STYLE, "风格匹配");
        DIMENSION_LABELS = Collections.unmodifiableMap(labels);
    }

    /** 基线写入用的库门面；null 表示未接线，baseline set 只回库不可用。 */
    private final EvalStore store;
    /** 基线服务；null 表示缺少 rubric 权重，baseline set 只回服务未接线。 */
    private final EvalBaselineService baselineService;
    /** 中止面只经该接口；null 表示执行器未接线。 */
    private final EvalRunService runner;

    public EvalWriteCommand(EvalStore store, EvalBaselineService baselineService, EvalRunService runner) {
        this.store = store;
        this.baselineService = baselineService;
        this.runner = runner;
    }

    // ------------------------------------------------------------ baseline set

    /**
     * {@code /eval baseline set <runId> <理由…>}：显式重设基线（§9.3、§9.6、§8.1）。
     *
     * @param params 子命令之后的参数：第 1 个是 runId，其余整体作为理由（中文与空格原样保留）
     */
    public List<String> baselineSet(String[] params) {
        try {
            return doBaselineSet(params == null ? new String[0] : params);
        } catch (RuntimeException e) {
            return single("重设基线失败：" + describe(e) + "；请查看插件日志后重试 /eval baseline set");
        }
    }

    private List<String> doBaselineSet(String[] params) {
        if (params.length == 0 || params[0] == null || params[0].trim().isEmpty()) {
            return single("缺少 runId：" + USAGE_SET);
        }
        String runId = params[0].trim();
        if (!EvalStore.isValidRunId(runId)) {
            return single("runId 格式不正确：" + runId
                    + "；应形如 r-<yyyyMMddHHmmss>-<smoke|full>-<4 位随机>；" + USAGE_SET);
        }
        String reason = join(params, 1);
        if (reason == null) {
            // 先回理由，避免把「忘写理由」误报成 run 不存在（§9.3 校验顺序）。
            return lines("拒绝重设基线：理由不能为空（只有空白不算理由）",
                    "下一步：补一句理由后重发，例如 /eval baseline set " + runId
                            + " 换 judge 模型后重建基线；" + USAGE_SET);
        }
        if (store == null) {
            return single("库不可用：EvalStore 未初始化（数据表未就绪），无法重设基线；详见插件启动日志");
        }
        if (baselineService == null) {
            return single("写侧基线服务未接线：缺少 rubric 维度权重，无法计算基线总分；请查看插件启动日志");
        }
        // 先读旧 active 只用于回执对比；不改 run 行，也不在这里写基线。
        JSONObject previous = store.findActiveBaseline();
        BaselineOutcome outcome = baselineService.explicitSet(store, runId, reason);
        switch (outcome.state()) {
            case CREATED:
            case REACTIVATED:
                return renderApplied(outcome, previous);
            case UNCHANGED:
                return renderUnchanged(outcome);
            case REJECTED:
                return renderRejected(outcome, runId);
            case FAILED:
            default:
                return lines("重设未生效：" + note(outcome),
                        "下一步：按 §13.4 以数据库现行状态为准（本次不重试）；发送 /eval baseline 查看当前 active，"
                                + "必要时重发 /eval baseline set " + runId + " <理由>");
        }
    }

    /** 成功回执：新基线 id、五维分与总分、旧 active 退役情况。 */
    private List<String> renderApplied(BaselineOutcome outcome, JSONObject previous) {
        String baselineId = outcome.baselineId();
        JSONObject row = baselineId == null ? null : store.findBaseline(baselineId);
        List<String> lines = new ArrayList<String>();
        lines.add("已重设基线：baseline_id=" + value(baselineId) + " run_id=" + value(outcome.runId()));
        if (row != null && !row.isEmpty()) {
            lines.add("总分=" + score(row.get("total_score"))
                    + " 有效题数=" + value(text(row, "valid_case_count"))
                    + " 建立于 " + value(text(row, "created_at"))
                    + " 建立者 " + value(text(row, "created_by"))
                    + " 理由 " + value(text(row, "reason")));
            lines.add(dimensionLine(text(row, "dimension_scores_json")));
        }
        lines.add(retiredLine(previous, baselineId));
        lines.add("run 状态未改动；查看详情 /eval baseline show " + value(outcome.runId()));
        return lines;
    }

    /** 旧 active 退役回执：回读旧行确认 active=0 且 superseded_by 指向新行。 */
    private String retiredLine(JSONObject previous, String newBaselineId) {
        if (previous == null || previous.isEmpty()) {
            return "旧 active：此前没有 active 基线，本次为第一条";
        }
        String oldId = text(previous, "baseline_id");
        String oldRunId = text(previous, "run_id");
        if (newBaselineId != null && newBaselineId.equals(oldId)) {
            return "旧 active：本次沿用该行，没有其他基线被退役";
        }
        JSONObject oldRow = oldId == null ? null : store.findBaseline(oldId);
        String active = oldRow == null ? null : String.valueOf(oldRow.get("active"));
        String supersededBy = oldRow == null ? null : text(oldRow, "superseded_by");
        boolean retired = oldRow != null && !"1".equals(active)
                && newBaselineId != null && newBaselineId.equals(supersededBy);
        String base = "旧 active 已退役：baseline_id=" + value(oldId) + " run_id=" + value(oldRunId)
                + "（active=0，superseded_by=" + value(newBaselineId) + "）";
        return retired ? base
                : "旧 active 退役未确认：baseline_id=" + value(oldId) + " run_id=" + value(oldRunId)
                        + "（回读 active=" + value(active) + "，superseded_by=" + value(supersededBy)
                        + "）；请用 /eval baseline 核对当前 active";
    }

    /** 幂等回执：同一 run 重复 set 不新增行、不再切换 active（§9.7、G15）。 */
    private List<String> renderUnchanged(BaselineOutcome outcome) {
        List<String> lines = new ArrayList<String>();
        lines.add("重复设置按幂等处理：" + value(outcome.runId()) + " 已是当前基线"
                + (outcome.baselineId() == null ? "" : "（baseline_id=" + outcome.baselineId() + "）"));
        JSONObject row = outcome.baselineId() == null ? null : store.findBaseline(outcome.baselineId());
        if (row != null && !row.isEmpty()) {
            lines.add("总分=" + score(row.get("total_score"))
                    + " 有效题数=" + value(text(row, "valid_case_count"))
                    + " 建立于 " + value(text(row, "created_at"))
                    + " 理由 " + value(text(row, "reason")));
            lines.add(dimensionLine(text(row, "dimension_scores_json")));
        }
        lines.add("本次未写库、未切换 active；查看详情 /eval baseline show " + value(outcome.runId()));
        return lines;
    }

    /** 拒绝回执：原因 + 下一步动作（§9.3.1 不允许只回「失败」）。 */
    private List<String> renderRejected(BaselineOutcome outcome, String runId) {
        List<String> lines = new ArrayList<String>();
        lines.add("拒绝重设基线：" + note(outcome));
        lines.add("下一步：" + nextAction(outcome, runId));
        return lines;
    }

    private static String nextAction(BaselineOutcome outcome, String runId) {
        String message = outcome.note() == null ? "" : outcome.note();
        if (message.startsWith("重设理由不能为空")) {
            return "补一句重设理由后重发，例如 /eval baseline set " + runId + " 换 judge 模型后重建基线";
        }
        if (message.startsWith("run 不存在")) {
            return "发送 /eval status 查看已有 run；runId 需形如 r-<yyyyMMddHHmmss>-<smoke|full>-<4 位随机>";
        }
        if (message.startsWith("smoke 档不能作为基线")) {
            return "smoke 永不成为基线（§8.1）；请改用 COMPLETED 的 full run";
        }
        if (message.startsWith("run 未完成")) {
            return "发送 /eval status " + runId + " 查看进度；待该 run 变为 COMPLETED 后再重设";
        }
        return "核对 runId、档位与状态后重试；详见 /eval status 与 /eval baseline";
    }

    // ------------------------------------------------------------ abort

    /**
     * {@code /eval abort}：中止当前 run（§9.6）。
     *
     * <p>只转发 {@link EvalRunService#abort()} 的结果，不在命令层改库；重复调用第二次由服务
     * 返回 NONE，命令层照原样回「当前没有进行中的评测」。</p>
     */
    public List<String> abort(String[] params) {
        try {
            return doAbort(params == null ? new String[0] : params);
        } catch (RuntimeException e) {
            return single("中止失败：" + describe(e) + "；请查看插件日志后重试 /eval abort");
        }
    }

    private List<String> doAbort(String[] params) {
        if (params.length > 0) {
            return single("参数过多：/eval abort 不接受参数；" + USAGE_ABORT);
        }
        if (runner == null) {
            return single("中止不可用：评测执行器未接线；请查看插件启动日志");
        }
        EvalRunner.AbortResult result = runner.abort();
        if (result == null) {
            return single("中止未执行：执行器没有返回结果；发送 /eval status 查看数据库现行状态");
        }
        switch (result.kind()) {
            case ABORTED:
                return single("已中止：" + result.runId() + "（状态见 /eval status）");
            case NONE:
                return single("当前没有进行中的评测");
            case FAILED:
            default:
                // §13.4：CAS 未执行或前置不匹配都不重试，以数据库现行状态为准；重启由 init 回收。
                return lines("中止未执行：" + (result.message() == null ? "未给出原因" : result.message()),
                        "下一步：发送 /eval status 查看数据库现行状态（§13.4 不重试）；"
                                + "若该 run 仍为 RUNNING，重启插件后由启动期 init 回收为 ABORTED(orphan)");
        }
    }

    // ------------------------------------------------------------ 工具

    /** 理由取 runId 之后的全部参数：中文原样保留，词间以单个半角空格连接。 */
    private static String join(String[] params, int from) {
        StringBuilder text = new StringBuilder();
        for (int i = from; i < params.length; i++) {
            String part = params[i] == null ? "" : params[i].trim();
            if (part.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(part);
        }
        return text.length() == 0 ? null : text.toString();
    }

    /** 五维分展示行；缺维度按「数据缺失」显示，不补 0（§7.5）。 */
    private static String dimensionLine(String json) {
        JSONObject dimensions = parseObject(json);
        StringBuilder line = new StringBuilder("五维分：");
        boolean first = true;
        for (Map.Entry<String, String> entry : DIMENSION_LABELS.entrySet()) {
            if (!first) {
                line.append('、');
            }
            first = false;
            line.append(entry.getKey()).append('（').append(entry.getValue()).append("）=")
                    .append(score(dimensions == null ? null : dimensions.get(entry.getKey())));
        }
        return line.toString();
    }

    private static JSONObject parseObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return JSONObject.parseObject(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 文本列：null 或空白统一为 null。 */
    private static String text(JSONObject row, String key) {
        if (row == null) {
            return null;
        }
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.trim().isEmpty() ? null : text;
    }

    private static String value(String text) {
        return text == null || text.trim().isEmpty() ? MISSING : text;
    }

    /** 分数展示：整数不带小数点，其余保留原始值。 */
    private static String score(Object value) {
        if (value == null) {
            return MISSING;
        }
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (!Double.isNaN(number) && !Double.isInfinite(number) && number == Math.rint(number)) {
                return String.valueOf((long) number);
            }
            return String.valueOf(number);
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? MISSING : text;
    }

    private static String note(BaselineOutcome outcome) {
        return outcome.note() == null || outcome.note().trim().isEmpty() ? "未给出原因" : outcome.note();
    }

    private static String describe(RuntimeException e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : "：" + e.getMessage());
    }

    private static List<String> single(String line) {
        return new ArrayList<String>(Collections.singletonList(line));
    }

    private static List<String> lines(String... items) {
        List<String> lines = new ArrayList<String>();
        Collections.addAll(lines, items);
        return lines;
    }
}
