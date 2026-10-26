package org.moboxlab.mbb.eval.runner;

import java.util.List;

import com.alibaba.fastjson.JSONObject;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * 启动期孤儿 RUNNING 行回收（规格 §4.5、§13.5、ST10）。
 *
 * <p>插件重启后，上次进程遗留的 RUNNING 行永远等不到收尾。判定依据不是「跑了多久」，而是
 * {@code started_at} 早于本次进程启动时刻：只有启动之前写入的 RUNNING 行才可能是孤儿，
 * 本次启动后新写入的行绝不能被回收。</p>
 *
 * <p>回收只把行 CAS 成 ABORTED(orphan) 并写 {@link #ORPHAN_SUMMARY}，不删 run 行、不删
 * case/call 行、不删报表；重启不续跑。CAS 返回 -1 时只记 WARN、不重试（§13.4），行保持
 * RUNNING 留待下次启动再回收；重复启动是幂等的（已回收的行不再被选中）。</p>
 */
public final class EvalRunRecovery {

    /** §13.1.3 ABORTED 原因码：进程中断。 */
    public static final String ABORT_REASON_ORPHAN = "orphan";

    /** 写入 error_summary 的摘要：原因码 + §4.5 要求的「进程中断回收」说明。 */
    public static final String ORPHAN_SUMMARY = ABORT_REASON_ORPHAN + "（进程中断回收）";

    private final EvalStore store;
    private final PluginLogger logger;
    private final long processStartedAtMillis;

    /**
     * @param store                 已就绪的存储门面；为 null 时构造失败，调用方不得回收
     * @param logger                插件日志，可为 null
     * @param processStartedAtMillis 本次进程启动时刻（毫秒），只回收严格早于它的 RUNNING 行
     */
    public EvalRunRecovery(EvalStore store, PluginLogger logger, long processStartedAtMillis) {
        if (store == null) {
            throw new IllegalArgumentException("存储未就绪，无法回收孤儿 RUNNING 行");
        }
        this.store = store;
        this.logger = logger;
        this.processStartedAtMillis = processStartedAtMillis;
    }

    /**
     * 回收早于本次进程启动时刻的 RUNNING 行。
     *
     * <p>查询与写入都不抛异常：查询失败由 {@link EvalStore} 记 WARN 并返回空/部分行，
     * 单行 CAS 失败只累加 failed、继续处理其余行。</p>
     */
    public RecoveryResult recoverOrphans() {
        String threshold = EvalStore.formatTime(processStartedAtMillis);
        List<JSONObject> staleRows = store.findRunningRunsStartedBefore(threshold);
        int recovered = 0;
        int failed = 0;
        for (JSONObject row : staleRows) {
            String runId = row == null ? null : row.getString("run_id");
            if (runId == null || runId.trim().isEmpty()) {
                failed++;
                warn("孤儿回收跳过缺少 run_id 的 RUNNING 行");
                continue;
            }
            EvalRunner.FinalizeResult result = EvalRunner.casTerminal(store, logger, runId,
                    EvalStore.STATUS_ABORTED, 0L, null, ORPHAN_SUMMARY);
            if (result.notExecuted()) {
                failed++;
            } else {
                recovered++;
            }
        }
        if (failed > 0) {
            warn("孤儿回收未全部完成（启动时刻 " + threshold + "，失败 " + failed + " 条），"
                    + "按 §13.4 记 WARN、不重试，留待下次启动再回收");
        }
        if (recovered > 0) {
            warn("init 孤儿回收：已把 " + recovered + " 条早于启动时刻 " + threshold
                    + " 的 RUNNING 行置为 ABORTED(" + ABORT_REASON_ORPHAN + ")");
        }
        return new RecoveryResult(staleRows.size(), recovered, failed);
    }

    private void warn(String message) {
        if (logger != null) {
            logger.sendWarn("[MBB-Eval] " + message);
        }
    }

    /** 单次回收的统计；scanned = recovered + failed（查询失败时可能为 0）。 */
    public static final class RecoveryResult {
        private final int scanned;
        private final int recovered;
        private final int failed;

        private RecoveryResult(int scanned, int recovered, int failed) {
            this.scanned = scanned;
            this.recovered = recovered;
            this.failed = failed;
        }

        /** 被选中的早期 RUNNING 行数。 */
        public int scanned() {
            return scanned;
        }

        /** 已成功置为 ABORTED(orphan) 的行数。 */
        public int recovered() {
            return recovered;
        }

        /** CAS 未执行、仍留在 RUNNING 的行数。 */
        public int failed() {
            return failed;
        }
    }
}
