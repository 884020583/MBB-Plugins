package org.moboxlab.mbb.eval.command;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.moboxlab.mbb.eval.bank.EvalBankCache;
import org.moboxlab.mbb.eval.baseline.EvalBaselineService;
import org.moboxlab.mbb.eval.baseline.EvalRunComparability;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.report.EvalGroupSummaryFormatter;
import org.moboxlab.mbb.eval.report.EvalReportData;
import org.moboxlab.mbb.eval.report.EvalReportWriter;
import org.moboxlab.mbb.eval.runner.EvalPreflightPlan;
import org.moboxlab.mbb.eval.store.EvalReadOnlyBoundary;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.mbb.eval.store.EvalStore.RowSet;

/**
 * 只读命令实现（规格 #50 §9.3、§9.6、§9.7，验收 G5 / G6 / G7 / G8）。
 *
 * <p>本类只做四件事：读库、读题库缓存、读磁盘、写报表文件。它不持有
 * {@code EvalRunService}，因此结构上不可能建 run、改 run 状态或触发 MBB-AI 调用；
 * 全部输出为不含 {@code [eval]} 前缀的纯文本行，由 {@link EvalCommand} 统一加前缀后发送。</p>
 *
 * <p>{@code status} 的「当前 run」直接读 {@code status='RUNNING'} 行：受理时 runner 已先落库再
 * 返回，内存中的单飞锁与这一行同源，所以读库即可覆盖「内存优先、DB 遗留兜底」两种来源，
 * 又不必触碰 runner。剩余墙钟按 {@code started_at + evalRunDeadlineMinutes} 计算。</p>
 *
 * <p>{@code report} 只从库重建，复用 F 区的
 * {@link EvalReportData#assemble(EvalStore, String)}、{@link EvalReportWriter#writeToFile}
 * 与 {@link EvalGroupSummaryFormatter#toPlainText}，不重复实现渲染；重复调用同名覆盖，幂等。</p>
 */
public final class EvalReadOnlyCommand {

    /** 报表摘要最多 15 行（§8.6、§9.3.1）；路径行也计入。 */
    public static final int MAX_REPORT_LINES = 15;
    /** 无参 report 扫描最近 run 的上限；只为找「最近一次 COMPLETED」。 */
    private static final int REPORT_SCAN_LIMIT = 200;
    /** baseline 历史展示上限；条数仍以实际存在的基线行为准。 */
    private static final int BASELINE_HISTORY_LIMIT = 20;
    /** 时间文本格式，与 EvalStore.nowText() 一致。 */
    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    /** 缺省墙钟上限（分钟），与 EvalConfig 默认值一致。 */
    private static final int DEFAULT_DEADLINE_MINUTES = 30;
    /** 未采到的值统一文案。 */
    private static final String MISSING = EvalReportData.MISSING;

    /** case 结果四态（§4.2.2）。 */
    private static final List<String> CASE_STATUSES =
            Collections.unmodifiableList(Arrays.asList("ok", "vetoed", "error", "invalid"));


    private final EvalStore store;
    private final EvalConfig config;
    private final EvalBankCache bankCache;
    private final File reportsDir;
    private final Clock clock;

    /** 生产构造：系统时钟。 */
    public EvalReadOnlyCommand(EvalStore store, EvalConfig config,
            EvalBankCache bankCache, File reportsDir) {
        this(store, config, bankCache, reportsDir, Clock.systemDefaultZone());
    }

    /** 测试构造：注入固定时钟，剩余墙钟可断言。 */
    public EvalReadOnlyCommand(EvalStore store, EvalConfig config,
            EvalBankCache bankCache, File reportsDir, Clock clock) {
        this.store = store;
        this.config = config;
        this.bankCache = bankCache;
        this.reportsDir = reportsDir;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    // ------------------------------------------------------------ status

    /**
     * {@code /eval status [runId]}：只读展示当前/指定 run。
     *
     * @param params 子命令之后的参数（0 或 1 个）
     */
    public List<String> status(String[] params) {
        try {
            return doStatus(params == null ? new String[0] : params);
        } catch (RuntimeException e) {
            return single("状态查询失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage())
                    + "；请查看插件日志后重试 /eval status");
        }
    }

    private List<String> doStatus(String[] params) {
        if (params.length > 1) {
            return single("参数过多：用法 /eval status [runId]");
        }
        if (store == null) {
            return single("库不可用：EvalStore 未初始化（数据表未就绪），无法查询 run；详见插件启动日志");
        }
        JSONObject row;
        if (params.length == 1) {
            String runId = params[0];
            if (!EvalStore.isValidRunId(runId)) {
                return single("runId 格式不正确：" + runId
                        + "；应形如 r-<yyyyMMddHHmmss>-<smoke|full>-<4 位随机>");
            }
            row = store.findRun(runId);
            if (row == null || row.isEmpty()) {
                return single("无此 run：" + runId + "；发送 /eval status（无参）查看当前或最近一次 run");
            }
        } else {
            row = store.findRunningRun();
            if (row == null || row.isEmpty()) {
                row = store.findLatestRun();
            }
            if (row == null || row.isEmpty()) {
                return single("还没有任何评测记录，或数据库不可用（详见插件日志）；可先发送 /eval run smoke");
            }
        }
        return renderStatus(row);
    }

    private List<String> renderStatus(JSONObject row) {
        List<String> lines = new ArrayList<String>();
        String runId = text(row, "run_id");
        String tier = text(row, "tier");
        String status = text(row, "status");
        String startedAt = text(row, "started_at");
        int callsUsed = intValue(row.get("calls_used"));
        int callsBudget = intValue(row.get("calls_budget"));
        lines.add("runId=" + value(runId) + " tier=" + value(tier) + " status=" + value(status));

        int total = expectedCaseCount(tier);
        RowSet caseRows = store.findCaseResults(runId);
        int completed = 0;
        int error = 0;
        int invalid = 0;
        for (JSONObject caseRow : caseRows.rows()) {
            String caseStatus = text(caseRow, "status");
            if (CASE_STATUSES.contains(caseStatus)) {
                completed++;
            }
            if ("error".equals(caseStatus)) {
                error++;
            } else if ("invalid".equals(caseStatus)) {
                invalid++;
            }
        }
        if (total < completed) {
            total = completed;
        }
        lines.add("进度：" + completed + "/" + total + " 已完成 case");
        lines.add("调用：calls_used=" + callsUsed + "/calls_budget=" + callsBudget);
        lines.add("失败分布：error=" + error + "，invalid=" + invalid + "（合计 " + (error + invalid) + "）");
        lines.add("开始于：" + value(startedAt));
        lines.add(remainingLine(status, startedAt));
        if (caseRows.isQueryFailed()) {
            lines.add("case 进度查询失败，以上数字可能不完整；详见插件日志");
        } else if (!caseRows.isComplete() && caseRows.size() != caseRows.expectedCount()) {
            lines.add("case 行数与库内计数不一致（" + caseRows.size() + "/" + caseRows.expectedCount()
                    + "），进度按已读到的行统计");
        }
        return lines;
    }

    private String remainingLine(String status, String startedAt) {
        if (!EvalStore.STATUS_RUNNING.equals(status)) {
            return "剩余墙钟：不适用（状态 " + value(status) + "）";
        }
        Long startMillis = parseTime(startedAt);
        int minutes = deadlineMinutes();
        if (startMillis == null) {
            return "剩余墙钟：无法计算（started_at 缺失或格式异常；上限 " + minutes + " 分钟）";
        }
        long remaining = startMillis.longValue() + (long) minutes * 60000L - clock.millis();
        if (remaining >= 0L) {
            return "剩余墙钟约 " + (remaining / 1000L) + " 秒（上限 " + minutes + " 分钟）";
        }
        return "剩余墙钟 0 秒（已超时约 " + ((-remaining) / 1000L)
                + " 秒；该 RUNNING 行可能是上次进程遗留，可发 /eval abort 中止后重试）";
    }

    // ------------------------------------------------------------ report

    /**
     * {@code /eval report [runId]}：只接受 COMPLETED，从库重建报表并写
     * {@code reports/run-<runId>.md}，随后回路径与 ≤15 行摘要。
     */
    public List<String> report(String[] params) {
        try {
            return doReport(params == null ? new String[0] : params);
        } catch (RuntimeException e) {
            return single("报表重建失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage())
                    + "；库仍在，可稍后重试 /eval report");
        }
    }

    private List<String> doReport(String[] params) {
        if (params.length > 1) {
            return single("参数过多：用法 /eval report [runId]");
        }
        if (store == null) {
            return single("库不可用：EvalStore 未初始化（数据表未就绪），无法重建报表；详见插件启动日志");
        }
        JSONObject row;
        if (params.length == 1) {
            String runId = params[0];
            if (!EvalStore.isValidRunId(runId)) {
                return single("runId 格式不正确：" + runId
                        + "；应形如 r-<yyyyMMddHHmmss>-<smoke|full>-<4 位随机>");
            }
            row = store.findRun(runId);
            if (row == null || row.isEmpty()) {
                return single("无此 run：" + runId + "；发送 /eval status（无参）查看已有 run");
            }
        } else {
            row = latestCompletedRun();
            if (row == null) {
                JSONObject latest = store.findLatestRun();
                if (latest == null || latest.isEmpty()) {
                    return single("报表重建失败：库不可用或还没有任何 run 记录；请检查数据库与插件日志");
                }
                String latestId = text(latest, "run_id");
                return single("最近没有 COMPLETED 的 run：最近一次为 " + value(latestId)
                        + "（状态 " + value(text(latest, "status")) + "）；发送 /eval status "
                        + value(latestId) + " 查看原因");
            }
        }
        String runId = text(row, "run_id");
        String status = text(row, "status");
        if (!EvalStore.STATUS_COMPLETED.equals(status)) {
            return single("该 run 状态为 " + value(status) + "，只有 COMPLETED 可生成报表；发送 /eval status "
                    + value(runId) + " 查看原因");
        }
        if (reportsDir == null) {
            return single("报表目录未配置（插件数据目录不可用），无法写入 reports/run-" + value(runId)
                    + ".md；详见插件启动日志");
        }
        if (!reportsDir.isDirectory() && !reportsDir.mkdirs() && !reportsDir.isDirectory()) {
            return single("报表目录不可用：" + reportsDir.getAbsolutePath()
                    + "；请检查插件数据目录权限后重试 /eval report");
        }
        EvalReportData data = EvalReportData.assemble(store, runId);
        if (data == null || !data.runFound()) {
            return single("报表重建失败：库内缺少 run 行或查询失败"
                    + (data == null || data.note() == null ? "" : "（" + data.note() + "）")
                    + "；发送 /eval status " + value(runId) + " 查看状态");
        }
        File target;
        try {
            target = EvalReportWriter.writeToFile(data, reportsDir);
        } catch (IOException e) {
            return single("报表写入失败：" + e.getMessage() + "；请检查 " + reportsDir.getAbsolutePath()
                    + " 的写权限后重试 /eval report");
        } catch (RuntimeException e) {
            return single("报表写入失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage())
                    + "；请检查 reports 目录后重试 /eval report");
        }
        List<String> lines = new ArrayList<String>();
        lines.add("report_path=" + reportDisplayPath(runId));
        // 摘要是多行纯文本；逐行加 [eval] 前缀，总行数封顶 15（含路径行）。
        for (String line : EvalGroupSummaryFormatter.toPlainText(data).split("\\r?\\n")) {
            if (lines.size() >= MAX_REPORT_LINES) {
                break;
            }
            if (line != null && !line.trim().isEmpty()) {
                lines.add(line);
            }
        }
        // 目标文件已由 writer 原子写入；这里仅防御 writer 未来改名导致路径与文件不一致。
        if (target == null || !target.isFile()) {
            lines.add("提示：报表文件未在预期目录找到，请检查插件日志");
        }
        return lines;
    }

    private JSONObject latestCompletedRun() {
        for (JSONObject row : store.findRecentRuns(REPORT_SCAN_LIMIT)) {
            if (EvalStore.STATUS_COMPLETED.equals(text(row, "status"))) {
                return row;
            }
        }
        return null;
    }

    /** 对外路径固定为 §5.1 的 {@code ./MoBoxBot/plugins/MBB-Eval/reports/run-<run_id>.md}。 */
    private static String reportDisplayPath(String runId) {
        return EvalReadOnlyBoundary.REPORT_BASE_PATH + EvalReportWriter.fileName(runId);
    }

    // ------------------------------------------------------------ list

    /**
     * {@code /eval list [full|smoke]}：只读题库缓存，0 调用、不建 run。
     */
    public List<String> list(String[] params) {
        try {
            return doList(params == null ? new String[0] : params);
        } catch (RuntimeException e) {
            return single("题库列表读取失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()) + "；请查看插件日志");
        }
    }

    private List<String> doList(String[] params) {
        if (params.length > 1) {
            return single("参数过多：用法 /eval list [full|smoke]");
        }
        String tier = params.length == 1 ? params[0] : null;
        if (tier != null && !EvalStore.TIER_SMOKE.equals(tier) && !EvalStore.TIER_FULL.equals(tier)) {
            return single("参数错误：档位只能是 smoke 或 full（大小写敏感）；用法 /eval list [full|smoke]");
        }
        if (bankCache == null) {
            return single("题库未就绪：题库缓存未接线（缺少 EvalBankCache）；无法执行 /eval list，"
                    + "不建 run、不调 AI；请检查插件启动日志");
        }
        if (!bankCache.ok()) {
            List<String> lines = new ArrayList<String>();
            lines.add("题库未就绪：" + value(bankCache.code()) + " " + value(bankCache.message()));
            if (bankCache.hint() != null && !bankCache.hint().trim().isEmpty()) {
                lines.add("修复提示：" + bankCache.hint());
            }
            lines.add("无法执行 /eval list；本次 0 调用、不建 run，/eval report 仍可用");
            return lines;
        }
        JSONObject bank = bankCache.json();
        JSONArray cases = bank == null ? null : bank.getJSONArray("cases");
        if (cases == null || cases.isEmpty()) {
            return single("题库未就绪：题库内容为空（0 条 case）；无法执行 /eval list，不建 run、不调 AI");
        }
        List<String> lines = new ArrayList<String>();
        List<JSONObject> selected = new ArrayList<JSONObject>();
        for (int i = 0; i < cases.size(); i++) {
            JSONObject item = cases.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String itemTier = text(item, "tier");
            // full 档包含全部 40 条（smoke 12 条是其中子集）；smoke 只列子集；无参列全部。
            if (tier == null || EvalStore.TIER_FULL.equals(tier) || tier.equals(itemTier)) {
                selected.add(item);
            }
        }
        lines.add("题库 " + value(bankCache.version()) + "：本次列出 " + selected.size()
                + " 条（档位 " + (tier == null ? "all" : tier) + "）；0 调用、不建 run");
        for (JSONObject item : selected) {
            lines.add("case " + value(text(item, "id"))
                    + " 维度=" + value(text(item, "dimension"))
                    + " tier=" + value(text(item, "tier"))
                    + " focus=" + value(text(item, "focus")));
        }
        if (selected.isEmpty()) {
            lines.add("档位 " + value(tier) + " 没有 case；可用 /eval list 查看全部");
        }
        return lines;
    }

    // ------------------------------------------------------------ baseline

    /**
     * {@code /eval baseline}：列当前 active 基线与最近历史；只读。
     */
    public List<String> baseline(String[] params) {
        try {
            return doBaseline(params == null ? new String[0] : params);
        } catch (RuntimeException e) {
            return single("基线查询失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()) + "；请查看插件日志");
        }
    }

    private List<String> doBaseline(String[] params) {
        if (params.length > 0) {
            return single("参数过多：/eval baseline 不接受参数；查看单条基线用 /eval baseline show [runId]");
        }
        if (store == null) {
            return single("库不可用：EvalStore 未初始化（数据表未就绪），无法查询基线；详见插件启动日志");
        }
        List<String> lines = new ArrayList<String>();
        JSONObject active = store.findActiveBaseline();
        if (active == null || active.isEmpty()) {
            lines.add("active 基线：无；首个成功且完整的 full run 会自动建立");
        } else {
            lines.add("active 基线：" + value(text(active, "baseline_id"))
                    + "（run " + value(text(active, "run_id"))
                    + "，总分 " + score(active.get("total_score"))
                    + "，建立于 " + value(text(active, "created_at"))
                    + "，理由 " + value(text(active, "reason")) + "）");
        }
        List<JSONObject> history = store.findBaselines(BASELINE_HISTORY_LIMIT);
        String activeId = active == null ? null : text(active, "baseline_id");
        List<JSONObject> others = new ArrayList<JSONObject>();
        for (JSONObject row : history) {
            if (activeId == null || !activeId.equals(text(row, "baseline_id"))) {
                others.add(row);
            }
        }
        lines.add("最近历史：" + (others.isEmpty() ? "无" : others.size() + " 条"));
        for (JSONObject row : others) {
            lines.add("历史 " + value(text(row, "baseline_id"))
                    + " run=" + value(text(row, "run_id"))
                    + " 总分=" + score(row.get("total_score"))
                    + " 建立于=" + value(text(row, "created_at"))
                    + " 理由=" + value(text(row, "reason")));
        }
        return lines;
    }

    /**
     * {@code /eval baseline show [runId]}：无参显示 active，有参显示该 run 锚定的基线；
     * 展示冻结的五维分、总分与九键快照。
     */
    public List<String> baselineShow(String[] params) {
        try {
            return doBaselineShow(params == null ? new String[0] : params);
        } catch (RuntimeException e) {
            return single("基线详情查询失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()) + "；请查看插件日志");
        }
    }

    private List<String> doBaselineShow(String[] params) {
        if (params.length > 1) {
            return single("参数过多：用法 /eval baseline show [runId]");
        }
        if (store == null) {
            return single("库不可用：EvalStore 未初始化（数据表未就绪），无法查询基线；详见插件启动日志");
        }
        JSONObject row;
        if (params.length == 0) {
            row = store.findActiveBaseline();
            if (row == null || row.isEmpty()) {
                return single("当前没有 active 基线；发送 /eval baseline 查看历史，"
                        + "或等首个成功且完整的 full run 自动建立");
            }
        } else {
            String runId = params[0];
            if (!EvalStore.isValidRunId(runId)) {
                return single("runId 格式不正确：" + runId
                        + "；应形如 r-<yyyyMMddHHmmss>-<smoke|full>-<4 位随机>");
            }
            row = findByRunId(runId);
            if (row == null) {
                return single("该 runId 未被任何 baseline 引用：" + runId
                        + "；发送 /eval baseline 查看已有基线");
            }
        }
        return renderBaseline(row);
    }

    /** 先按派生 id 直查，再兜底扫描历史行（旧数据可能不是 b-<runId> 形状）。 */
    private JSONObject findByRunId(String runId) {
        JSONObject row = store.findBaseline(EvalBaselineService.BASELINE_ID_PREFIX + runId);
        if (row != null && !row.isEmpty()) {
            return row;
        }
        for (JSONObject candidate : store.findBaselines(Integer.MAX_VALUE)) {
            if (runId.equals(text(candidate, "run_id"))) {
                return candidate;
            }
        }
        return null;
    }

    private List<String> renderBaseline(JSONObject row) {
        List<String> lines = new ArrayList<String>();
        String baselineId = text(row, "baseline_id");
        String runId = text(row, "run_id");
        lines.add("基线：baseline_id=" + value(baselineId) + " run_id=" + value(runId)
                + " active=" + ("1".equals(String.valueOf(row.get("active"))) ? "是" : "否"));
        lines.add("总分=" + score(row.get("total_score"))
                + " 有效题数=" + value(text(row, "valid_case_count"))
                + " 建立于 " + value(text(row, "created_at"))
                + " 建立者 " + value(text(row, "created_by"))
                + " 理由 " + value(text(row, "reason")));

        JSONObject dimensions = parseObject(text(row, "dimension_scores_json"));
        StringBuilder dimLine = new StringBuilder("五维分：");
        boolean first = true;
        for (Map.Entry<String, String> entry : EvalReportData.dimensionLabels().entrySet()) {
            if (!first) {
                dimLine.append("、");
            }
            first = false;
            dimLine.append(entry.getKey()).append("（").append(entry.getValue()).append("）=")
                    .append(score(dimensions == null ? null : dimensions.get(entry.getKey())));
        }
        lines.add(dimLine.toString());

        lines.add("九键快照：");
        Map<String, String> keys = EvalRunComparability.nineKeyValues(row);
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            lines.add(entry.getKey() + "=" + value(entry.getValue()));
        }
        return lines;
    }

    // ------------------------------------------------------------ 工具

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

    private int expectedCaseCount(String tier) {
        if (bankCache != null && bankCache.ok()) {
            if (EvalStore.TIER_FULL.equals(tier)) {
                return bankCache.caseCount();
            }
            if (EvalStore.TIER_SMOKE.equals(tier)) {
                return bankCache.smokeCount();
            }
        }
        try {
            return EvalPreflightPlan.of(tier).caseCount();
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private int deadlineMinutes() {
        if (config == null) {
            return DEFAULT_DEADLINE_MINUTES;
        }
        try {
            return config.runDeadlineMinutes();
        } catch (RuntimeException e) {
            return DEFAULT_DEADLINE_MINUTES;
        }
    }

    private static Long parseTime(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        SimpleDateFormat format = new SimpleDateFormat(TIME_PATTERN, Locale.CHINA);
        format.setLenient(false);
        try {
            Date date = format.parse(text.trim());
            return Long.valueOf(date.getTime());
        } catch (ParseException e) {
            return null;
        }
    }

    private static int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
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

    private static List<String> single(String line) {
        return new ArrayList<String>(Collections.singletonList(line));
    }
}
