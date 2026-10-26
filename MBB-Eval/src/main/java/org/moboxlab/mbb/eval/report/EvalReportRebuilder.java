package org.moboxlab.mbb.eval.report;

import com.alibaba.fastjson.JSONObject;
import org.moboxlab.mbb.eval.baseline.EvalDeltaResult;
import org.moboxlab.mbb.eval.baseline.EvalDeltaService;
import org.moboxlab.mbb.eval.log.EvalLog;
import org.moboxlab.mbb.eval.score.EvalDimensionWeights;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.PluginLogger;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 报表重建（H-5：规格 §8.7、§13.4、H17）。
 *
 * <p>数据库是唯一事实源；本类按 run_id 从四表重新装配并渲染整份报表，覆盖磁盘上的旧文件
 * （包括被手工改过的内容），随后只把 {@code report_path} 回写到 run 行。终态、结束时间、
 * 调用数与分数一律不改。</p>
 *
 * <p>只有 {@code COMPLETED} 可以重建：{@code RUNNING}、{@code ABORTED}、{@code FAILED}
 * 一律拒绝并给出原因（§13.4）。重建失败只记 WARN，{@link EvalReportWriter} 的临时文件
 * 加原子改名保证既有报表不被半截内容破坏。</p>
 */
public final class EvalReportRebuilder {

    /** 报表目录名（§8.7 固定路径 {@code getDataFolder()/reports}）。 */
    public static final String REPORTS_DIR_NAME = "reports";

    private final EvalStore store;
    private final EvalDimensionWeights weights;
    private final File reportsDir;
    private final PluginLogger logger;

    /**
     * @param store      评测库门面，不能为空
     * @param weights    维度权重；与 delta 计算共用，保证报表第 3/4/5 节口径一致
     * @param reportsDir 报表目录，不能为空
     * @param logger     插件日志；可为 null（测试与只读诊断）
     */
    public EvalReportRebuilder(EvalStore store, EvalDimensionWeights weights, File reportsDir,
                               PluginLogger logger) {
        if (store == null) {
            throw new IllegalArgumentException("EvalStore 不能为空");
        }
        if (weights == null) {
            throw new IllegalArgumentException("维度权重不能为空");
        }
        if (reportsDir == null) {
            throw new IllegalArgumentException("报表目录不能为空");
        }
        this.store = store;
        this.weights = weights;
        this.reportsDir = reportsDir;
        this.logger = logger;
    }

    /**
     * 从库重建报表并回写 report_path。
     *
     * <p>幂等：同一 run 重复重建得到同一内容与同一路径；写入采用临时文件加原子改名，
     * 失败时旧文件保持原样。</p>
     *
     * @param runId run 标识
     * @return 重建结果；拒绝或失败时 {@link RebuildResult#rebuilt()} 为 false
     */
    public RebuildResult rebuild(String runId) {
        String safeRunId = runId == null ? "" : runId.trim();
        if (!EvalStore.isValidRunId(safeRunId)) {
            EvalLog.warn(logger, "拒绝重建报表：run_id 不合法 runId=" + safeRunId);
            return RebuildResult.rejected(safeRunId, null, "run_id 不合法，无法重建报表");
        }
        JSONObject run = store.findRun(safeRunId);
        if (run == null) {
            EvalLog.warn(logger, "拒绝重建报表：库中无该 run runId=" + safeRunId);
            return RebuildResult.rejected(safeRunId, null, "库中无该 run 的行，无法重建报表");
        }
        String status = run.getString("status");
        if (!EvalStore.STATUS_COMPLETED.equals(status)) {
            String reason = "该 run 状态为 " + (status == null ? "未知" : status)
                    + "，只有 COMPLETED 可重建报表；请发送 /eval status " + safeRunId + " 查看原因";
            EvalLog.warn(logger, "拒绝重建报表 runId=" + safeRunId + " status="
                    + (status == null ? "未知" : status));
            return RebuildResult.rejected(safeRunId, status, reason);
        }

        EvalReportData data = EvalReportData.assemble(store, safeRunId, weightMap(), null, logger);
        if (!data.runFound()) {
            EvalLog.warn(logger, "重建报表失败：装配结果标数据缺失 runId=" + safeRunId
                    + " 原因=" + data.note());
            return RebuildResult.failed(safeRunId, status, null, null,
                    "报表装配失败：" + data.note());
        }

        EvalDeltaResult delta = computeDelta(safeRunId);
        File target;
        try {
            target = EvalReportWriter.writeToFile(data, reportsDir, delta, logger);
        } catch (IOException e) {
            EvalLog.warn(logger, "重建报表写盘失败，保留旧文件 runId=" + safeRunId
                    + " 原因=" + e.getMessage());
            return RebuildResult.failed(safeRunId, status, existingFile(safeRunId), null,
                    "报表写盘失败：" + e.getMessage());
        } catch (RuntimeException e) {
            EvalLog.warn(logger, "重建报表失败，保留旧文件 runId=" + safeRunId
                    + " 原因=" + e.getClass().getSimpleName());
            return RebuildResult.failed(safeRunId, status, existingFile(safeRunId), null,
                    "报表重建失败：" + e.getClass().getSimpleName());
        }

        String relativePath = EvalReportWriter.relativeReportPath(safeRunId);
        boolean pathUpdated = false;
        int updated = store.updateReportPath(safeRunId, relativePath);
        if (updated > 0) {
            pathUpdated = true;
        } else if (updated == 0) {
            EvalLog.warn(logger, "报表已生成但 report_path 未命中 run 行 runId=" + safeRunId);
        } else {
            EvalLog.warn(logger, "报表已生成但 report_path 回写未执行 runId=" + safeRunId);
        }
        EvalLog.info(logger, "报表重建完成 runId=" + safeRunId + " path=" + relativePath);
        return new RebuildResult(true, safeRunId, status, target, relativePath, null,
                pathUpdated, delta != null);
    }

    /** delta 计算失败不能阻断报表重建：第 5 节退回未计算占位并记 WARN。 */
    private EvalDeltaResult computeDelta(String runId) {
        try {
            return new EvalDeltaService(store, weights).compareToActiveBaseline(runId);
        } catch (RuntimeException e) {
            EvalLog.warn(logger, "基线 delta 计算失败，第 5 节按未计算渲染 runId=" + runId
                    + " 原因=" + e.getClass().getSimpleName());
            return null;
        }
    }

    private Map<String, Double> weightMap() {
        Map<String, Double> map = new LinkedHashMap<String, Double>();
        for (String id : weights.ids()) {
            map.put(id, Double.valueOf(weights.weight(id)));
        }
        return map;
    }

    private File existingFile(String runId) {
        File file = new File(reportsDir, EvalReportWriter.fileName(runId));
        return file.isFile() ? file : null;
    }

    /** 重建结果；拒绝与失败都携带可展示原因（中文、无 emoji）。 */
    public static final class RebuildResult {

        private final boolean rebuilt;
        private final String runId;
        private final String status;
        private final File file;
        private final String relativePath;
        private final String reason;
        private final boolean reportPathUpdated;
        private final boolean deltaApplied;

        private RebuildResult(boolean rebuilt, String runId, String status, File file,
                              String relativePath, String reason, boolean reportPathUpdated,
                              boolean deltaApplied) {
            this.rebuilt = rebuilt;
            this.runId = runId;
            this.status = status;
            this.file = file;
            this.relativePath = relativePath;
            this.reason = reason;
            this.reportPathUpdated = reportPathUpdated;
            this.deltaApplied = deltaApplied;
        }

        static RebuildResult rejected(String runId, String status, String reason) {
            return new RebuildResult(false, runId, status, null, null, reason, false, false);
        }

        static RebuildResult failed(String runId, String status, File file, String relativePath,
                                    String reason) {
            return new RebuildResult(false, runId, status, file, relativePath, reason, false, false);
        }

        /** 是否成功写出报表并完成 report_path 回写尝试。 */
        public boolean rebuilt() {
            return rebuilt;
        }

        public String runId() {
            return runId;
        }

        /** 重建时的 run 状态；未知时为 null。 */
        public String status() {
            return status;
        }

        /** 实际写出的报表文件；拒绝时为 null，写盘失败时为既有旧文件（若有）。 */
        public File file() {
            return file;
        }

        /** 库中记录的相对路径，形如 {@code reports/run-<run_id>.md}。 */
        public String relativePath() {
            return relativePath;
        }

        /** 拒绝或失败原因；成功时为 null。 */
        public String reason() {
            return reason;
        }

        /** report_path 是否实际更新成功。 */
        public boolean reportPathUpdated() {
            return reportPathUpdated;
        }

        /** 第 5 节是否用上了 F-6 delta。 */
        public boolean deltaApplied() {
            return deltaApplied;
        }

        @Override
        public String toString() {
            return "RebuildResult{runId=" + runId + ", status=" + status + ", rebuilt=" + rebuilt
                    + ", file=" + (file == null ? "-" : file.getAbsolutePath())
                    + ", reason=" + reason + "}";
        }
    }
}
