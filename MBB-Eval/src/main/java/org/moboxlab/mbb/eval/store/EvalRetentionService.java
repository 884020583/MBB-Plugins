package org.moboxlab.mbb.eval.store;

import com.alibaba.fastjson.JSONObject;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.log.EvalLog;
import org.moboxlab.moboxbot.API.PluginLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 保留清理服务（规格 §4.5、§4.7 ST7、§15.6 F6、§16.7 F-3）。
 *
 * <p>按 evalKeepRuns 保留最近 N 条终态 run，其余从旧到新逐条删除：先删 case_result 与 call 子行，
 * 再删 run 行，最后级联删报表文件。只处理终态行（COMPLETED / FAILED / ABORTED），RUNNING 不在
 * 清理范围；被任何基线行引用的 run 永不删除。无事务：任一步删除返回 0 行或 -1 即中止本轮并记 WARN，
 * 留待下一轮重试。</p>
 */
public final class EvalRetentionService {

    /** 保留条数的程序内上界（配置文件口径是 5..200，见 §12.1）。 */
    private static final int KEEP_RUNS_MAX = 200;

    private final EvalStore store;
    private final EvalConfig config;
    private final PluginLogger logger;
    /** 插件数据目录（用于把 report_path 解析成绝对路径）；未知时为 null，只删绝对路径报表。 */
    private final File dataFolder;

    public EvalRetentionService(EvalStore store, EvalConfig config, PluginLogger logger) {
        this(store, config, logger, null);
    }

    /**
     * @param dataFolder 插件数据目录（{@code getDataFolder()}）；报表固定落在 {@code reports/run-<run_id>.md}
     */
    public EvalRetentionService(EvalStore store, EvalConfig config, PluginLogger logger, File dataFolder) {
        if (store == null) {
            throw new IllegalArgumentException("存储门面不可用，无法创建保留清理服务");
        }
        if (config == null) {
            throw new IllegalArgumentException("配置不可用，无法创建保留清理服务");
        }
        this.store = store;
        this.config = config;
        this.logger = logger;
        this.dataFolder = dataFolder;
    }

    /** 按配置 evalKeepRuns 清理。 */
    public RetentionResult retain() {
        return retain(config.keepRuns());
    }

    /**
     * 保留最近 keepRuns 条终态 run，删除更旧的终态 run。
     *
     * @param keepRuns 保留条数
     */
    public RetentionResult retain(int keepRuns) {
        int effectiveKeep = clampKeepRuns(keepRuns);
        RetentionResult result = new RetentionResult(effectiveKeep);
        List<JSONObject> candidates = store.findTerminalRunsBeyond(effectiveKeep);
        // findTerminalRunsBeyond 返回最新在前；清理从最旧一条开始
        for (int i = candidates.size() - 1; i >= 0; i--) {
            JSONObject row = candidates.get(i);
            String runId = row == null ? null : row.getString("run_id");
            if (runId == null || runId.trim().isEmpty()) {
                continue;
            }
            try {
                if (!isTerminal(row.getString("status"))) {
                    // 防御：候选来自终态查询，但环境可能在查询与删除之间变化
                    result.add(runId, RetentionResult.Outcome.SKIPPED_NOT_TERMINAL, "非终态 run 不清理");
                    warn("保留清理跳过非终态运行（run_id=" + runId + "）");
                    continue;
                }
                if (store.isRunReferencedByBaseline(runId)) {
                    result.add(runId, RetentionResult.Outcome.SKIPPED_REFERENCED, "被基线引用，永不删除");
                    warn("运行被基线引用，跳过清理（run_id=" + runId + "）");
                    continue;
                }
                EvalStore.WriteResult caseResult = store.deleteCaseResults(runId);
                if (caseResult != EvalStore.WriteResult.APPLIED) {
                    result.add(runId, RetentionResult.Outcome.FAILED, "用例结果删除未生效");
                    result.abort("用例结果删除未生效（run_id=" + runId + "）");
                    warn("用例结果删除未生效，本轮保留清理中止（run_id=" + runId + "）");
                    break;
                }
                EvalStore.WriteResult callResult = store.deleteCalls(runId);
                if (callResult != EvalStore.WriteResult.APPLIED) {
                    result.add(runId, RetentionResult.Outcome.FAILED, "调用行删除未生效");
                    result.abort("调用行删除未生效（run_id=" + runId + "）");
                    warn("调用行删除未生效，本轮保留清理中止（run_id=" + runId + "）");
                    break;
                }
                EvalStore.WriteResult runResult = store.deleteRun(runId);
                if (runResult != EvalStore.WriteResult.APPLIED) {
                    result.add(runId, RetentionResult.Outcome.FAILED, "运行行删除未生效");
                    result.abort("运行行删除未生效（run_id=" + runId + "）");
                    warn("运行行删除未生效，本轮保留清理中止（run_id=" + runId + "）");
                    break;
                }
                if (store.findRun(runId) != null) {
                    // 规格要求删后复核：run 行仍在说明删除没有真正落地
                    result.add(runId, RetentionResult.Outcome.FAILED, "删除后复核 run 行仍存在");
                    result.abort("删除后复核 run 行仍存在（run_id=" + runId + "）");
                    warn("删除后复核发现 run 行仍存在，本轮保留清理中止（run_id=" + runId + "）");
                    break;
                }
                deleteReportFile(row, runId);
                result.add(runId, RetentionResult.Outcome.DELETED, "已删除");
            } catch (RuntimeException e) {
                result.add(runId, RetentionResult.Outcome.FAILED, "清理异常：" + e.getMessage());
                result.abort("清理异常（run_id=" + runId + "）");
                warn("保留清理异常，本轮中止（run_id=" + runId + "）：" + e.getMessage());
                break;
            }
        }
        info("保留清理完成：保留 " + effectiveKeep + " 条终态运行，删除 " + result.deletedCount()
                + " 条，跳过 " + result.skippedCount() + " 条，失败 " + result.failedCount()
                + " 条" + (result.isAborted() ? "，本轮已中止" : ""));
        return result;
    }

    /** 程序内保留条数：非正数按 1、超过 200 按 200 钳制并记 WARN。 */
    private int clampKeepRuns(int keepRuns) {
        if (keepRuns <= 0) {
            warn("保留条数 " + keepRuns + " 非法，按 1 处理");
            return 1;
        }
        if (keepRuns > KEEP_RUNS_MAX) {
            warn("保留条数 " + keepRuns + " 超过上限，按 " + KEEP_RUNS_MAX + " 处理");
            return KEEP_RUNS_MAX;
        }
        return keepRuns;
    }

    /**
     * 级联删除报表文件（§4.5、§8.7）：删除失败只记 WARN，不回滚库内删除，也不中止本轮。
     */
    private void deleteReportFile(JSONObject row, String runId) {
        File file = resolveReportFile(row, runId);
        if (file == null || !file.exists()) {
            return;
        }
        if (!file.delete()) {
            warn("报表文件删除失败（run_id=" + runId + "，path=" + file.getPath() + "）");
        }
    }

    /**
     * 报表文件位置：优先 run 行里的 report_path（绝对路径直接用，相对路径按数据目录解析），
     * 缺失或不可解析时回退到固定的 {@code reports/run-<run_id>.md}。数据目录未知时返回 null。
     */
    private File resolveReportFile(JSONObject row, String runId) {
        String reportPath = row == null ? null : row.getString("report_path");
        if (reportPath != null && !reportPath.trim().isEmpty()) {
            File file = new File(reportPath.trim());
            if (file.isAbsolute()) {
                return file;
            }
            if (dataFolder != null) {
                return new File(dataFolder, reportPath.trim());
            }
        }
        if (dataFolder != null) {
            return new File(new File(dataFolder, "reports"), "run-" + runId + ".md");
        }
        return null;
    }

    /** 是否属于允许清理的终态。 */
    private static boolean isTerminal(String status) {
        return EvalStore.STATUS_COMPLETED.equals(status)
                || EvalStore.STATUS_FAILED.equals(status)
                || EvalStore.STATUS_ABORTED.equals(status);
    }

    private void info(String message) {
        EvalLog.info(logger, message);
    }

    private void warn(String message) {
        EvalLog.warn(logger, message);
    }

    /** 一轮清理的结果：删除 / 跳过 / 失败计数与逐条明细。 */
    public static final class RetentionResult {

        /** 单条 run 的清理结论。 */
        public enum Outcome {
            /** 子行、run 行与报表文件都已清理（报表文件缺失不算失败）。 */
            DELETED,
            /** 被基线引用，按规格豁免。 */
            SKIPPED_REFERENCED,
            /** 非终态（例如已重新变为 RUNNING），不在删除范围。 */
            SKIPPED_NOT_TERMINAL,
            /** 删除未生效，本轮中止。 */
            FAILED
        }

        /** 单条明细。 */
        public static final class Entry {
            private final String runId;
            private final Outcome outcome;
            private final String detail;

            private Entry(String runId, Outcome outcome, String detail) {
                this.runId = runId;
                this.outcome = outcome;
                this.detail = detail;
            }

            public String runId() {
                return runId;
            }

            public Outcome outcome() {
                return outcome;
            }

            public String detail() {
                return detail;
            }
        }

        private final int keepRuns;
        private final List<Entry> entries = new ArrayList<Entry>();
        private boolean aborted;
        private String abortReason;

        private RetentionResult(int keepRuns) {
            this.keepRuns = keepRuns;
        }

        private void add(String runId, Outcome outcome, String detail) {
            entries.add(new Entry(runId, outcome, detail));
        }

        private void abort(String reason) {
            this.aborted = true;
            this.abortReason = reason;
        }

        /** 本轮实际保留的最近终态 run 数。 */
        public int keepRuns() {
            return keepRuns;
        }

        public int deletedCount() {
            return count(Outcome.DELETED);
        }

        public int skippedCount() {
            return count(Outcome.SKIPPED_REFERENCED) + count(Outcome.SKIPPED_NOT_TERMINAL);
        }

        public int failedCount() {
            return count(Outcome.FAILED);
        }

        private int count(Outcome outcome) {
            int count = 0;
            for (Entry entry : entries) {
                if (entry.outcome == outcome) {
                    count++;
                }
            }
            return count;
        }

        /** 逐条明细（只读）。 */
        public List<Entry> entries() {
            return Collections.unmodifiableList(entries);
        }

        /** 本轮是否因单条删除未生效而提前中止。 */
        public boolean isAborted() {
            return aborted;
        }

        /** 中止原因；未中止时为 null。 */
        public String abortReason() {
            return abortReason;
        }
    }
}