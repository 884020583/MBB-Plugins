package org.moboxlab.mbb.eval.runner;

import com.alibaba.fastjson.JSONObject;
import org.moboxlab.mbb.eval.baseline.EvalBaselineService;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.exec.EvalRunLoop;
import org.moboxlab.mbb.eval.log.EvalLog;
import org.moboxlab.mbb.eval.report.EvalGroupSummaryFormatter;
import org.moboxlab.mbb.eval.report.EvalReportData;
import org.moboxlab.mbb.eval.report.EvalReportWriter;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.PluginLogger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * run 受理与骨架期占位执行（规格 §9.3、§9.4、§13.4）。
 *
 * <p>本类负责把「一次 /eval run」的生命周期管起来：预检 → 生成 run_id → 写 RUNNING 行 →
 * {@link EvalRunLoop} 真实主循环 → 终态 CAS → COMPLETED 时生成报表并回写 report_path。
 * 未装配主循环依赖时保留占位轮询 {@link #placeholderLoop(RunState)}：只观察中止、墙钟与外部终态，
 * 不调用 AI、不写 case/call 行，供生命周期自检使用。</p>
 *
 * <p>并发约束：同一时刻只允许一个 RUNNING run。受理时不仅看内存中的 active，还会查库确认没有
 * 遗留的 RUNNING 行（插件重启后内存状态为空，但库里的行还在），避免同一时间出现两个 RUNNING。
 * 状态行只能由 CAS（{@code WHERE status='RUNNING'}）改写，禁止覆盖式更新（§4.4、§13.4）。</p>
 *
 * <p>命令层只依赖 {@link EvalRunService}：受理与中止都在命令线程内一次调用返回，命令线程自己不轮询、
 * 不等待 run 结束、不发起任何 AI 调用（§9.4）。</p>
 */
public final class EvalRunner implements EvalRunService {

    /** 占位循环的轮询间隔：中止与墙钟检查的响应上限。 */
    private static final long POLL_MILLIS = 200L;
    /** 写 RUNNING 行的最大尝试次数（run_id 冲突时重新生成）。 */
    private static final int INSERT_MAX_ATTEMPTS = 3;
    /** 卸载时等待 runner 线程退出的秒数；超时也不阻塞卸载，残留 RUNNING 行交给下次 init 回收。 */
    private static final long SHUTDOWN_WAIT_SECONDS = 2L;

    /** 受理结果的种类。 */
    public enum AcceptKind {
        /** 已受理，RUNNING 行已写入。 */
        ACCEPTED,
        /** 已有进行中的 run，本次未建行。 */
        BUSY,
        /** 预检拒绝，本次未建行、未产生 AI 调用。 */
        REJECTED,
        /** 内部失败（写库异常、配置缺失等）。 */
        FAILED
    }

    /** 受理结果；除 ACCEPTED 外都带一条 {@link EvalPreflight.Rejection}（code 可为空）。 */
    public static final class AcceptResult {
        private final AcceptKind kind;
        private final String runId;
        private final String tier;
        private final int caseCount;
        private final int planned;
        private final int budgetLimit;
        private final EvalPreflight.Rejection rejection;

        private AcceptResult(AcceptKind kind, String runId, String tier, int caseCount, int planned,
                             int budgetLimit, EvalPreflight.Rejection rejection) {
            this.kind = kind;
            this.runId = runId;
            this.tier = tier;
            this.caseCount = caseCount;
            this.planned = planned;
            this.budgetLimit = budgetLimit;
            this.rejection = rejection;
        }

        public static AcceptResult accepted(String runId, String tier, int caseCount, int planned, int budgetLimit) {
            return new AcceptResult(AcceptKind.ACCEPTED, runId, tier, caseCount, planned, budgetLimit, null);
        }

        public static AcceptResult busy(String runId, String tier, String message, String hint) {
            return new AcceptResult(AcceptKind.BUSY, runId, tier, 0, 0, 0,
                    new EvalPreflight.Rejection(null, message, hint));
        }

        public static AcceptResult rejected(String tier, EvalPreflight.Rejection rejection) {
            return new AcceptResult(AcceptKind.REJECTED, null, tier, 0, 0, 0, rejection);
        }

        public static AcceptResult failed(String tier, String message) {
            return new AcceptResult(AcceptKind.FAILED, null, tier, 0, 0, 0,
                    new EvalPreflight.Rejection(null, message, null));
        }

        public AcceptKind kind() {
            return kind;
        }

        public String runId() {
            return runId;
        }

        public String tier() {
            return tier;
        }

        public int caseCount() {
            return caseCount;
        }

        public int planned() {
            return planned;
        }

        public int budgetLimit() {
            return budgetLimit;
        }

        public String code() {
            return rejection == null ? null : rejection.code();
        }

        public String message() {
            return rejection == null ? null : rejection.message();
        }

        public String hint() {
            return rejection == null ? null : rejection.hint();
        }

        /** 逐条明细（题库前 5 条错误等）；无明细时为空列表，供命令层原样回显。 */
        public List<String> details() {
            return rejection == null ? Collections.<String>emptyList() : rejection.details();
        }

        /** 原始预检拒绝对象；命令层据此调用 EvalFailure / EvalRejectionPresenter 统一渲染。 */
        public EvalPreflight.Rejection rejection() {
            return rejection;
        }
    }

    /** 中止结果的种类。 */
    public enum AbortKind {
        /** 已中止（本次 CAS 成功，或该 run 已是终态按幂等成功处理）。 */
        ABORTED,
        /** 当前没有进行中的 run。 */
        NONE,
        /** 写库失败，调用方可稍后重试。 */
        FAILED
    }

    /** 中止结果。 */
    public static final class AbortResult {
        private final AbortKind kind;
        private final String runId;
        private final String message;

        private AbortResult(AbortKind kind, String runId, String message) {
            this.kind = kind;
            this.runId = runId;
            this.message = message;
        }

        public static AbortResult aborted(String runId) {
            return new AbortResult(AbortKind.ABORTED, runId, null);
        }

        public static AbortResult none() {
            return new AbortResult(AbortKind.NONE, null, null);
        }

        public static AbortResult failed(String message) {
            return new AbortResult(AbortKind.FAILED, null, message);
        }

        public AbortKind kind() {
            return kind;
        }

        public String runId() {
            return runId;
        }

        public String message() {
            return message;
        }
    }

    /** 终态收口结果的种类（§4.4、§13.4）。 */
    public enum FinalizeKind {
        /** CAS 返回正数：本次把 RUNNING 行改写成终态。 */
        APPLIED,
        /** CAS 返回 0：没有 RUNNING 行（已是终态），按幂等成功处理，不重复生成报表。 */
        ALREADY_TERMINAL,
        /** CAS 返回 -1：语句未执行（已记 WARN），按 §13.4 不重试，以数据库现行状态为准。 */
        NOT_EXECUTED
    }

    /** 终态收口结果；updateReturn 原样带回 CAS 的 -1 / 0 / 正数。 */
    public static final class FinalizeResult {
        private final FinalizeKind kind;
        private final String runId;
        private final String status;
        private final int updateReturn;
        private final String message;

        private FinalizeResult(FinalizeKind kind, String runId, String status, int updateReturn,
                               String message) {
            this.kind = kind;
            this.runId = runId;
            this.status = status;
            this.updateReturn = updateReturn;
            this.message = message;
        }

        public FinalizeKind kind() {
            return kind;
        }

        public String runId() {
            return runId;
        }

        public String status() {
            return status;
        }

        public int updateReturn() {
            return updateReturn;
        }

        /** 告警文案；成功时为 null。 */
        public String message() {
            return message;
        }

        public boolean applied() {
            return kind == FinalizeKind.APPLIED;
        }

        /** -1 表示语句未执行：行仍是 RUNNING，留给下次启动的 init 回收（§13.4）。 */
        public boolean notExecuted() {
            return kind == FinalizeKind.NOT_EXECUTED;
        }
    }

    /** /eval status 的展示快照。 */
    public static final class RunSnapshot {
        private final String runId;
        private final String tier;
        private final String status;
        private final int callsUsed;
        private final int callsBudget;
        private final String startedAt;
        private final int planned;
        private final int caseCount;
        private final long remainingMillis;

        private RunSnapshot(String runId, String tier, String status, int callsUsed, int callsBudget,
                            String startedAt, int planned, int caseCount, long remainingMillis) {
            this.runId = runId;
            this.tier = tier;
            this.status = status;
            this.callsUsed = callsUsed;
            this.callsBudget = callsBudget;
            this.startedAt = startedAt;
            this.planned = planned;
            this.caseCount = caseCount;
            this.remainingMillis = remainingMillis;
        }

        public String runId() {
            return runId;
        }

        public String tier() {
            return tier;
        }

        public String status() {
            return status;
        }

        public int callsUsed() {
            return callsUsed;
        }

        public int callsBudget() {
            return callsBudget;
        }

        public String startedAt() {
            return startedAt;
        }

        public int planned() {
            return planned;
        }

        public int caseCount() {
            return caseCount;
        }

        /** 剩余墙钟毫秒；-1 表示该 run 不是本进程持有的（例如插件重启后读到的遗留 RUNNING 行）。 */
        public long remainingMillis() {
            return remainingMillis;
        }

        public boolean running() {
            return EvalStore.STATUS_RUNNING.equals(status);
        }
    }

    /** 卸载收口结果的种类（§16.9 H-4）。 */
    public enum ShutdownKind {
        /** 本进程没有进行中的 run，无需写库。 */
        NO_ACTIVE_RUN,
        /** 该 RUNNING 行已是终态：本次 CAS 成功，或此前已由其他路径收口（幂等）。 */
        ABORTED,
        /** CAS 未执行（-1）：只记 WARN、不重试，留给下次启动的孤儿清理收敛。 */
        CAS_NOT_APPLIED
    }

    /** 卸载收口结果；不承诺成功，调用方不得据此认为卸载一定干净。 */
    public static final class ShutdownResult {
        private final ShutdownKind kind;
        private final String runId;
        private final String reason;
        private final FinalizeResult finalizeResult;
        private final String message;

        private ShutdownResult(ShutdownKind kind, String runId, String reason,
                               FinalizeResult finalizeResult, String message) {
            this.kind = kind;
            this.runId = runId;
            this.reason = reason;
            this.finalizeResult = finalizeResult;
            this.message = message;
        }

        public static ShutdownResult noActiveRun() {
            return new ShutdownResult(ShutdownKind.NO_ACTIVE_RUN, null, null, null, null);
        }

        static ShutdownResult aborted(String runId, String reason, FinalizeResult finalizeResult,
                                      String message) {
            return new ShutdownResult(ShutdownKind.ABORTED, runId, reason, finalizeResult, message);
        }

        static ShutdownResult notApplied(String runId, String reason, FinalizeResult finalizeResult) {
            return new ShutdownResult(ShutdownKind.CAS_NOT_APPLIED, runId, reason, finalizeResult,
                    finalizeResult == null ? null : finalizeResult.message());
        }

        public ShutdownKind kind() {
            return kind;
        }

        /** 本次涉及的 run_id；NO_ACTIVE_RUN 时为 null。 */
        public String runId() {
            return runId;
        }

        /** 写入 error_summary 的原因码；NO_ACTIVE_RUN 时为 null。 */
        public String reason() {
            return reason;
        }

        /** 对应的终态 CAS 结果；NO_ACTIVE_RUN 或未发 CAS 时为 null。 */
        public FinalizeResult finalizeResult() {
            return finalizeResult;
        }

        /** 补充说明；成功收口时为 null。 */
        public String message() {
            return message;
        }

        /** 本次是否真的把 RUNNING 行改写成 ABORTED（CAS 正数）。 */
        public boolean casApplied() {
            return finalizeResult != null && finalizeResult.applied();
        }

        /** 是否仍有 RUNNING 行留给下次启动的孤儿清理兜底。 */
        public boolean needsOrphanRecovery() {
            return kind == ShutdownKind.CAS_NOT_APPLIED;
        }

        /** 本次卸载是否涉及进行中的 run。 */
        public boolean hasActiveRun() {
            return kind != ShutdownKind.NO_ACTIVE_RUN;
        }
    }

    /** 单次 run 的内存状态；只有 active 指向它时才算本进程持有。 */
    private static final class RunState {
        private final String runId;
        private final String tier;
        private final int caseCount;
        private final long startedAtMillis;
        private final EvalRunBudget budget;
        private final EvalRunStopPoint stopPoint;
        /** COMPLETED 完成摘要的回送目标；null 表示本次不回送（旧 accept 入口或自检）。 */
        private final EvalCompletionSink completionSink;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private RunState(String runId, String tier, int caseCount, long startedAtMillis, EvalRunBudget budget,
                         EvalRunStopPoint stopPoint, EvalCompletionSink completionSink) {
            this.runId = runId;
            this.tier = tier;
            this.caseCount = caseCount;
            this.startedAtMillis = startedAtMillis;
            this.budget = budget;
            this.stopPoint = stopPoint;
            this.completionSink = completionSink;
        }
    }

    private final EvalStore store;
    private final EvalConfig config;
    private final EvalPreflight preflight;
    private final PluginLogger logger;
    private final String evalPluginVersion;
    private final ExecutorService executor;
    /** 真实评测主循环；null 表示未装配执行依赖，只做守护轮询（不写 case/call 行、不发终态）。 */
    private final EvalRunLoop runLoop;
    /** 受理时采集九键快照；null 表示未装配，缺键一律空串并告警。 */
    private final EvalRunSnapshotSource snapshotSource;
    /** COMPLETED 后自动创建首个 full 基线；null 表示未装配（只手工切基线）。 */
    private final EvalBaselineService baselineService;
    private final AtomicReference<RunState> active = new AtomicReference<RunState>(null);
    private final AtomicBoolean closing = new AtomicBoolean(false);

    /**
     * @param store             可为 null（数据表未就绪时装配仍要继续，由预检拒绝受理）
     * @param config            插件配置，用于默认档位与 evalCallBudget
     * @param preflight         预检；null 视为无预检（只供自检使用）
     * @param logger            插件日志，可为 null
     * @param evalPluginVersion 写入 run 行的插件版本，可为 null
     */
    public EvalRunner(EvalStore store, EvalConfig config, EvalPreflight preflight, PluginLogger logger,
                      String evalPluginVersion) {
        this(store, config, preflight, logger, evalPluginVersion, null, null, null);
    }

    /**
     * 带主循环的装配。
     *
     * @param runLoop 真实评测主循环；null 表示未装配执行依赖（例如只做生命周期自检的构造），
     *                此时只保留守护轮询，不写 case/call 行、不发终态、不生成报表
     */
    public EvalRunner(EvalStore store, EvalConfig config, EvalPreflight preflight, PluginLogger logger,
                      String evalPluginVersion, EvalRunLoop runLoop) {
        this(store, config, preflight, logger, evalPluginVersion, runLoop, null, null);
    }

    /**
     * 完整装配：额外注入受理快照来源与自动基线服务。
     *
     * @param snapshotSource  九键快照来源；null 时九键写空串并逐键告警
     * @param baselineService COMPLETED 后自动创建首个 full 基线；null 时跳过
     */
    public EvalRunner(EvalStore store, EvalConfig config, EvalPreflight preflight, PluginLogger logger,
                      String evalPluginVersion, EvalRunLoop runLoop,
                      EvalRunSnapshotSource snapshotSource, EvalBaselineService baselineService) {
        this.store = store;
        this.config = config;
        this.preflight = preflight;
        this.logger = logger;
        this.evalPluginVersion = evalPluginVersion;
        this.runLoop = runLoop;
        this.snapshotSource = snapshotSource;
        this.baselineService = baselineService;
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "MBB-Eval-Runner");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /** 构造只读终态探针：直接查库判断该 run 是否仍是 RUNNING（§13.4 以数据库为准）。 */
    private EvalRunStopPoint.RunStatusProbe statusProbe(final String runId) {
        return new EvalRunStopPoint.RunStatusProbe() {
            @Override
            public boolean terminal() {
                JSONObject row = store.findRun(runId);
                return row != null && !EvalStore.STATUS_RUNNING.equals(row.getString("status"));
            }
        };
    }

    /**
     * 受理一次 /eval run。
     *
     * <p>顺序固定为：规范化档位 → 单 run 互斥（内存 + 遗留 RUNNING 行）→ 预检 → 写 RUNNING 行。
     * 任一步失败都不留下半截状态：预检拒绝与 BUSY 不写任何行。</p>
     *
     * @param tier {@code smoke}/{@code full}，null 取 {@code evalDefaultTier}
     */
    public AcceptResult accept(String tier) {
        return accept(tier, null);
    }

    /**
     * 受理一次 /eval run，并登记 COMPLETED 完成摘要的回送目标（§9.3.1、§9.4 第 4 步）。
     *
     * <p>顺序与旧入口完全一致；sink 只存进 {@link RunState}，不改变任何受理判定。
     * 完成摘要只在 COMPLETED 收尾、报表落盘与终态 CAS 应用之后发送一次。</p>
     *
     * @param tier           {@code smoke}/{@code full}，null 取 {@code evalDefaultTier}
     * @param completionSink 完成摘要回送目标；null 表示本次不回送
     */
    public AcceptResult accept(String tier, EvalCompletionSink completionSink) {
        String normalized = tier;
        if (normalized == null && config != null) {
            normalized = config.defaultTier();
        }
        if (!EvalStore.TIER_SMOKE.equals(normalized) && !EvalStore.TIER_FULL.equals(normalized)) {
            return AcceptResult.failed(normalized, "档位只能是 smoke 或 full，当前值为 " + tier);
        }
        synchronized (this) {
            if (closing.get()) {
                return AcceptResult.failed(normalized, "插件正在卸载，暂不接受新的评测");
            }
            if (store == null) {
                EvalPreflight.Rejection rejection = new EvalPreflight.Rejection(
                        null, "数据表未就绪，评测能力已禁用",
                        "请查看插件启动日志中的数据表告警，修复数据库后重启插件再重试 /eval run");
                return AcceptResult.rejected(normalized, rejection);
            }
            if (active.get() != null) {
                RunState running = active.get();
                return busyResult(running.runId, running.tier,
                        running.budget.used() + "/" + running.budget.limit(),
                        "可发 /eval status 查看进度，或 /eval abort 中止该 run");
            }
            JSONObject leftover = store.findRunningRun();
            if (leftover != null) {
                return busyResult(leftover.getString("run_id"), leftover.getString("tier"),
                        leftover.getIntValue("calls_used") + "/" + leftover.getIntValue("calls_budget"),
                        "该行为上次运行遗留，本进程无法收尾，需先发 /eval abort 中止，再 /eval status 查看状态");
            }
            EvalPreflight.Rejection rejection = preflight == null ? null : preflight.check(normalized);
            if (rejection != null) {
                return AcceptResult.rejected(normalized, rejection);
            }
            int planned = EvalPreflight.plannedCalls(normalized);
            int caseCount = EvalPreflight.caseCount(normalized);
            int budgetLimit = config == null ? planned : config.callBudget();
            for (int attempt = 0; attempt < INSERT_MAX_ATTEMPTS; attempt++) {
                String runId;
                try {
                    runId = store.generateRunId(normalized);
                } catch (RuntimeException e) {
                    return AcceptResult.failed(normalized, "生成 run_id 失败：" + e.getMessage());
                }
                long startedAtMillis = System.currentTimeMillis();
                JSONObject runRow = new JSONObject();
                runRow.put("run_id", runId);
                runRow.put("tier", normalized);
                runRow.put("started_at", EvalStore.formatTime(startedAtMillis));
                runRow.put("calls_budget", Integer.valueOf(budgetLimit));
                runRow.put("eval_plugin_version", evalPluginVersion);
                fillSnapshotColumns(runRow);
                boolean inserted = store.insertRunning(runRow);
                if (!inserted) {
                    warn("写入 RUNNING 行失败，重新生成 run_id 后重试（第 " + (attempt + 1) + " 次）");
                    continue;
                }
                long deadlineAtMillis = startedAtMillis
                        + (config == null ? 30L : config.runDeadlineMinutes()) * 60_000L;
                RunState state = new RunState(runId, normalized, caseCount, startedAtMillis,
                        new EvalRunBudget(planned, budgetLimit, deadlineAtMillis),
                        new EvalRunStopPoint(runId, statusProbe(runId)), completionSink);
                active.set(state);
                scheduleRun(state);
                return AcceptResult.accepted(runId, normalized, caseCount, planned, budgetLimit);
            }
            return AcceptResult.failed(normalized, "连续 " + INSERT_MAX_ATTEMPTS + " 次写入 run 行失败");
        }
    }

    /**
     * 受理建行时补齐九键与快照列（§8.2）：
     * {@code bank_hash / rubric_version / persona_hash / config_fingerprint / eval_profile /
     * judge_profile / judge_model / generation_model / eval_api_version}，外加题库、persona、
     * 基线标识与采样次数。任何一列取不到都写空串（{@code sample_k} 为 1）并记
     * 「{键} 未取得」WARN，绝不因为缺一列而拒绝受理。
     */
    private void fillSnapshotColumns(JSONObject runRow) {
        JSONObject snapshot = null;
        if (snapshotSource != null) {
            try {
                snapshot = snapshotSource.snapshot();
            } catch (RuntimeException e) {
                warn("采集九键快照异常，缺键按空串落库：" + e.getMessage());
            }
        }
        String bankFile = snapshotText(snapshot, "bank_file");
        if (bankFile.isEmpty() && config != null) {
            bankFile = config.bankFile();
        }
        String evalProfile = snapshotText(snapshot, "eval_profile");
        if (evalProfile.isEmpty() && config != null) {
            evalProfile = config.profile();
        }
        String judgeProfile = snapshotText(snapshot, "judge_profile");
        if (judgeProfile.isEmpty() && config != null) {
            judgeProfile = config.judgeProfile();
        }
        putRequiredText(runRow, "bank_file", bankFile);
        putRequiredText(runRow, "bank_version", snapshotText(snapshot, "bank_version"));
        putRequiredText(runRow, "bank_hash", snapshotText(snapshot, "bank_hash"));
        putRequiredText(runRow, "persona_id", snapshotText(snapshot, "persona_id"));
        putRequiredText(runRow, "persona_version", snapshotText(snapshot, "persona_version"));
        putRequiredText(runRow, "persona_hash", snapshotText(snapshot, "persona_hash"));
        putRequiredText(runRow, "rubric_version", snapshotText(snapshot, "rubric_version"));
        putRequiredText(runRow, "config_fingerprint", snapshotText(snapshot, "config_fingerprint"));
        putRequiredText(runRow, "eval_profile", evalProfile);
        putRequiredText(runRow, "judge_profile", judgeProfile);
        putRequiredText(runRow, "judge_model", snapshotText(snapshot, "judge_model"));
        putRequiredText(runRow, "generation_model", snapshotText(snapshot, "generation_model"));
        putRequiredText(runRow, "eval_api_version", snapshotText(snapshot, "eval_api_version"));
        runRow.put("sample_k", Integer.valueOf(1));

        String baselineId = "";
        try {
            JSONObject baseline = store.findActiveBaseline();
            if (baseline != null) {
                baselineId = snapshotText(baseline, "baseline_id");
            }
        } catch (RuntimeException e) {
            warn("查询激活基线失败，baseline_id_at_run 按空串落库：" + e.getMessage());
        }
        putRequiredText(runRow, "baseline_id_at_run", baselineId);
    }

    /** 单列落库：空白值写空串并告警，保证列存在且非 null。 */
    private void putRequiredText(JSONObject runRow, String key, String value) {
        String text = value == null ? "" : value.trim();
        runRow.put(key, text);
        if (text.isEmpty()) {
            warn(key + " 未取得");
        }
    }

    /** 从快照取文本；缺键、null、非字符串一律收敛成空串。 */
    private static String snapshotText(JSONObject snapshot, String key) {
        if (snapshot == null || key == null) {
            return "";
        }
        Object value = snapshot.get(key);
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return "-1".equals(text) ? "" : text;
    }

    /**
     * §9.7：第二个 /eval run 必须直接回当前 runId 与当前进度，不排队、不新建行、不报错。
     *
     * <p>{@code progress} 只放 {@code used/limit}，{@code hint} 放下一步动作，由命令层拼成一行回执
     * （命令层负责「当前进度」等说明文字，runner 只提供事实）。</p>
     */
    private AcceptResult busyResult(String runId, String tier, String progress, String hint) {
        return AcceptResult.busy(runId, tier, progress, hint);
    }

    /**
     * 中止当前 run（规格 §9.3、§13.1.3）。
     *
     * <p>内存里有 active 时走「标记中止 + 终态 CAS」；没有 active 但库里有遗留 RUNNING 行时，
     * 直接 CAS 为 ABORTED(user-abort)，这是插件重启后唯一的收尾手段。</p>
     */
    public AbortResult abort() {
        synchronized (this) {
            RunState state = active.get();
            if (state == null) {
                if (store == null) {
                    return AbortResult.none();
                }
                JSONObject leftover = store.findRunningRun();
                if (leftover == null) {
                    return AbortResult.none();
                }
                String runId = leftover.getString("run_id");
                FinalizeResult result = finalizeRun(runId, EvalStore.STATUS_ABORTED, 0L, null,
                        EvalStore.ABORT_REASON_USER);
                if (!result.notExecuted()) {
                    return AbortResult.aborted(runId);
                }
                return AbortResult.failed("终态写库未执行（§13.4 不重试），以数据库现行状态为准；"
                        + "重启后由下次启动的 init 回收");
            }
            state.stopPoint.requestStop(EvalStore.ABORT_REASON_USER);
            state.budget.abort(EvalStore.ABORT_REASON_USER);
            FinalizeResult result = finishAbort(state, EvalStore.ABORT_REASON_USER);
            if (result == null || !result.notExecuted()) {
                return AbortResult.aborted(state.runId);
            }
            return AbortResult.failed("终态写库未执行（§13.4 不重试），以数据库现行状态为准；"
                    + "重启后由下次启动的 init 回收");
        }
    }

    /**
     * 把指定 run 的 RUNNING 行收口为终态（§4.4、§13.4）。
     *
     * <p>返回语义：CAS 正数 → {@link FinalizeKind#APPLIED}；0 → 已是终态，按幂等成功处理，
     * 不重复生成报表；-1 → 只记 WARN、不重试，行按数据库现行状态保留，留给下次启动的
     * init 回收。无论哪种结果都不得用内存状态覆盖数据库。</p>
     *
     * @param runId        已存在的 run_id
     * @param status       {@code COMPLETED} / {@code FAILED} / {@code ABORTED}
     * @param durationMs   运行时长毫秒，负数按 0 落库
     * @param reportPath   COMPLETED 的报表路径；FAILED / ABORTED 传 null
     * @param errorSummary FAILED / ABORTED 的失败或原因摘要；COMPLETED 传 null
     * @throws IllegalArgumentException 存储未就绪、run_id 为空或终态未知
     */
    public FinalizeResult finalizeRun(String runId, String status, long durationMs, String reportPath,
                                      String errorSummary) {
        return casTerminal(store, logger, runId, status, durationMs, reportPath, errorSummary);
    }

    /**
     * 把本进程持有的 active run 收口为终态（§4.4）。
     *
     * <p>用 {@code finished} 做一次性闸门：重复调用或已由占位循环收口时返回 null，不再发 CAS，
     * 避免覆盖已写入的终态。收口后释放单飞锁，无论数据库是否写成功（§13.4 以 DB 为准）。</p>
     *
     * @return 本次收口结果；没有 active 或已被收口时返回 null
     */
    public FinalizeResult finalizeActive(String status, String reportPath, String errorSummary) {
        RunState state = active.get();
        if (state == null) {
            return null;
        }
        if (!state.finished.compareAndSet(false, true)) {
            return null;
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - state.startedAtMillis);
        FinalizeResult result = casTerminal(store, logger, state.runId, status, durationMs, reportPath, errorSummary);
        active.compareAndSet(state, null);
        return result;
    }

    /**
     * 终态 CAS 的唯一实现（含 init 孤儿回收）：把 -1 / 0 / 正数收敛成三态并记 WARN。
     *
     * <p>包内可见，供 {@link EvalRunRecovery} 复用同一套语义，避免两处对 -1 / 0 的处理漂移。</p>
     */
    static FinalizeResult casTerminal(EvalStore store, PluginLogger logger, String runId, String status,
                                      long durationMs, String reportPath, String errorSummary) {
        if (store == null) {
            throw new IllegalArgumentException("存储未就绪，无法收口终态");
        }
        if (runId == null || runId.trim().isEmpty()) {
            throw new IllegalArgumentException("run_id 不能为空");
        }
        if (!EvalStore.STATUS_COMPLETED.equals(status) && !EvalStore.STATUS_FAILED.equals(status)
                && !EvalStore.STATUS_ABORTED.equals(status)) {
            throw new IllegalArgumentException("未知终态：" + status);
        }
        long duration = Math.max(0L, durationMs);
        String endedAt = EvalStore.nowText();
        int result;
        try {
            if (EvalStore.STATUS_COMPLETED.equals(status)) {
                result = store.casCompleted(runId, endedAt, duration, reportPath);
            } else if (EvalStore.STATUS_FAILED.equals(status)) {
                result = store.casFailed(runId, endedAt, duration, errorSummary);
            } else {
                result = store.casAborted(runId, endedAt, duration, errorSummary);
            }
        } catch (RuntimeException e) {
            warn(logger, "终态 CAS 异常（" + status + "，run_id=" + runId + "）：" + e.getMessage());
            result = -1;
        }
        if (result > 0) {
            return new FinalizeResult(FinalizeKind.APPLIED, runId, status, result, null);
        }
        if (result == 0) {
            String message = "终态 CAS 未命中 RUNNING 行（" + status + "，run_id=" + runId
                    + "），按幂等成功处理（§13.4），不重复生成报表";
            warn(logger, message);
            return new FinalizeResult(FinalizeKind.ALREADY_TERMINAL, runId, status, result, message);
        }
        String message = "终态 CAS 未执行（" + status + "，run_id=" + runId
                + "），按 §13.4 记 WARN、不重试，以数据库现行状态为准";
        warn(logger, message);
        return new FinalizeResult(FinalizeKind.NOT_EXECUTED, runId, status, result, message);
    }

    /**
     * 当前 active run 的停止点；返回 null 表示本进程没有进行中的 run。
     *
     * <p>插件重启后库里的遗留 RUNNING 行不会重新进入执行，因此也没有停止点（§13.5：重启不续跑）。</p>
     */
    public EvalRunStopPoint stopPoint() {
        RunState state = active.get();
        return state == null ? null : state.stopPoint;
    }

    /** 最近一次 run 的快照（优先内存中的 active）；没有任何记录返回 null。 */
    public RunSnapshot snapshot() {
        RunState state = active.get();
        if (state != null) {
            return fromState(state);
        }
        if (store == null) {
            return null;
        }
        return fromRow(store.findLatestRun());
    }

    /** 指定 run 的快照；不存在返回 null。 */
    public RunSnapshot snapshot(String runId) {
        if (store == null || !EvalStore.isValidRunId(runId)) {
            return null;
        }
        return fromRow(store.findRun(runId));
    }

    /**
     * 插件卸载时的收尾：标记中止并尽力把 RUNNING 行改成 ABORTED(shutdown)。
     * 写库失败只记 WARN，交给下一次启动的 init 回收（F 区），不阻塞卸载。
     */
    public ShutdownResult shutdown() {
        closing.set(true);
        ShutdownResult result = ShutdownResult.noActiveRun();
        RunState state = active.get();
        if (state != null) {
            state.stopPoint.requestStop(EvalStore.ABORT_REASON_SHUTDOWN);
            state.budget.abort(EvalStore.ABORT_REASON_SHUTDOWN);
            FinalizeResult finalizeResult;
            try {
                finalizeResult = finishAbort(state, EvalStore.ABORT_REASON_SHUTDOWN);
            } catch (RuntimeException e) {
                warn("卸载时收口 ABORTED(shutdown) 异常，run_id=" + state.runId + "：" + e.getMessage());
                finalizeResult = null;
            }
            if (finalizeResult == null) {
                // finished 闸门已关：其他路径已经收口，以数据库为准，不再发 CAS。
                result = ShutdownResult.aborted(state.runId, EvalStore.ABORT_REASON_SHUTDOWN, null,
                        "该 run 已由其他路径收口，以数据库现行状态为准");
                warn("卸载时该 run 已由其他路径收口，run_id=" + state.runId + "，以数据库现行状态为准");
            } else if (finalizeResult.notExecuted()) {
                result = ShutdownResult.notApplied(state.runId, EvalStore.ABORT_REASON_SHUTDOWN, finalizeResult);
                warn("卸载时写入 ABORTED(shutdown) 未执行，run_id=" + state.runId
                        + "，按 §13.4 不重试，将由下次启动的孤儿清理收敛");
            } else {
                result = ShutdownResult.aborted(state.runId, EvalStore.ABORT_REASON_SHUTDOWN,
                        finalizeResult, null);
            }
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    /**
     * 把一次 run 交给 runner 自己的单线程 daemon executor。
     *
     * <p>装配了 {@link EvalRunLoop} 时执行真实评测主循环；未装配时保留守护轮询
     * （只观察中止、墙钟与外部终态，不调用 AI、不写 case/call 行），供生命周期自检使用。</p>
     */
    private void scheduleRun(final RunState state) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (runLoop == null) {
                    placeholderLoop(state);
                } else {
                    runMainLoop(state);
                }
            }
        });
    }

    /**
     * 真实评测主循环：题库子集逐题执行 → 终态 CAS → COMPLETED 时生成报表。
     *
     * <p>所有写库仍走 {@link #casTerminal}（-1 / 0 只 WARN、不重试，§13.4）；外部已收口
     * （TERMINAL_IN_STORE）时只释放内存持有，不得用内存状态覆盖数据库。</p>
     */
    private void runMainLoop(final RunState state) {
        EvalRunLoop.Result result;
        try {
            result = runLoop.run(EvalRunLoop.Request.of(state.runId, state.tier, state.budget,
                    state.stopPoint));
        } catch (RuntimeException e) {
            warn("评测主循环异常，run_id=" + state.runId + "：" + e.getMessage());
            finishFailed(state, "主循环异常：" + e.getClass().getSimpleName());
            return;
        }
        if (result == null) {
            warn("评测主循环未返回结果，run_id=" + state.runId);
            finishFailed(state, "主循环未返回结果");
            return;
        }
        if (result.terminalInStore()) {
            if (state.finished.compareAndSet(false, true)) {
                active.compareAndSet(state, null);
            }
            warn("run 已由其他路径收口，run_id=" + state.runId + "，本轮不再发终态 CAS");
            return;
        }
        if (result.status() == EvalRunLoop.Status.COMPLETED) {
            finishCompleted(state, result);
            return;
        }
        finishAbort(state, result.abortReason());
    }

    /**
     * COMPLETED 收口：先写报表，再把报表相对路径随终态 CAS 一次写入。
     *
     * <p>顺序保证不出「报表存在但 run 行仍是 RUNNING」或「终态 COMPLETED 但 report_path
     * 永久为空」：报表写失败不阻断 COMPLETED（§13.4），CAS 仍以 null 路径收口并记 ERROR；
     * 报表写成功但 CAS 返回 0 / -1 时保留已落盘路径的日志，供人工/重建恢复。</p>
     */
    private void finishCompleted(final RunState state, final EvalRunLoop.Result result) {
        if (!state.finished.compareAndSet(false, true)) {
            return;
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - state.startedAtMillis);
        String reportPath = writeReport(state.runId, result);
        FinalizeResult finalized = casTerminal(store, logger, state.runId, EvalStore.STATUS_COMPLETED,
                durationMs, reportPath, null);
        active.compareAndSet(state, null);
        if (!finalized.applied()) {
            if (reportPath != null) {
                warn("报表已落盘但终态 CAS 未应用，已保留路径供重建，run_id=" + state.runId
                        + " path=" + reportPath);
            }
            return;
        }
        deliverCompletionSummary(state, result, reportPath);
        if (baselineService == null) {
            return;
        }
        try {
            JSONObject row = store.findRun(state.runId);
            if (row != null) {
                baselineService.autoCreateIfFirstFull(store, row);
            }
        } catch (RuntimeException e) {
            warn("自动创建首个 full 基线异常，run_id=" + state.runId + "：" + e.getMessage());
        }
    }

    /**
     * 把 COMPLETED 的完成摘要发回发起会话（§9.3.1、§9.4 第 4 步、G-11 / I-2）。
     *
     * <p>只在「报表已落盘 + 终态 CAS 已应用」之后调用：{@code canSummarize} 只认库中 COMPLETED 的
     * run，CAS 之前装配的数据一定不满足，因此这里重新取一次报表数据。摘要文案全部由
     * {@link EvalGroupSummaryFormatter#toPlainText} 生成，runner 不自行拼文案；sink 未登记、数据
     * 不可用、生成或发送抛异常都只记 WARN，不改变终态与报表（§9.4 判定规则 7）。</p>
     *
     * @param reportPath 本次落盘的报表相对路径；为 null 说明报表写失败，摘要里的路径会失真，跳过发送
     */
    private void deliverCompletionSummary(final RunState state, final EvalRunLoop.Result result,
                                          final String reportPath) {
        EvalCompletionSink sink = state.completionSink;
        if (sink == null) {
            return;
        }
        if (reportPath == null) {
            warn("报表未落盘，跳过发送完成摘要，run_id=" + state.runId);
            return;
        }
        EvalReportData data;
        try {
            data = result.reportData(store);
        } catch (RuntimeException e) {
            warn("完成摘要数据装配失败，跳过发送，run_id=" + state.runId + "：" + e.getMessage());
            return;
        }
        if (!EvalGroupSummaryFormatter.canSummarize(data)) {
            warn("完成摘要数据不可用，跳过发送，run_id=" + state.runId);
            return;
        }
        final String text;
        try {
            text = EvalGroupSummaryFormatter.toPlainText(data);
        } catch (RuntimeException e) {
            warn("完成摘要生成失败，跳过发送，run_id=" + state.runId + "：" + e.getMessage());
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            warn("完成摘要为空，跳过发送，run_id=" + state.runId);
            return;
        }
        try {
            sink.send(text);
        } catch (RuntimeException e) {
            warn("完成摘要发送失败，不影响 run 终态与报表，run_id=" + state.runId + "：" + e.getMessage());
        }
    }

    /**
     * 生成 reports/run-&lt;runId&gt;.md，返回库中应记录的相对路径；失败记 ERROR 并返回 null。
     *
     * <p>不再单独 updateReportPath：report_path 由 COMPLETED 的 CAS 一次写入，避免两次写库
     * 之间出现空窗。群内摘要失败只由 writer 记 WARN，不影响主报表。</p>
     */
    private String writeReport(final String runId, final EvalRunLoop.Result result) {
        File reportsDir = runLoop.reportsDirectory();
        if (reportsDir == null) {
            warn("报表目录未装配，跳过报表生成，run_id=" + runId);
            return null;
        }
        try {
            EvalReportData data = result.reportData(store);
            EvalReportWriter.writeToFile(data, reportsDir, null, logger);
            return EvalReportWriter.relativeReportPath(runId);
        } catch (IOException e) {
            EvalLog.error(logger, "报表写入失败，run_id=" + runId + "：" + e.getMessage());
            return null;
        } catch (RuntimeException e) {
            EvalLog.error(logger, "报表生成异常，run_id=" + runId + "：" + e.getMessage());
            return null;
        }
    }

    /** 主循环异常时的 FAILED 收口；同样遵守 §13.4：CAS 失败只 WARN、不重试。 */
    private FinalizeResult finishFailed(final RunState state, final String summary) {
        if (!state.finished.compareAndSet(false, true)) {
            return null;
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - state.startedAtMillis);
        FinalizeResult result = casTerminal(store, logger, state.runId, EvalStore.STATUS_FAILED, durationMs,
                null, summary);
        active.compareAndSet(state, null);
        info("run 以 FAILED 收口，不发送完成摘要（§9.8），run_id=" + state.runId + "，原因=" + summary);
        return result;
    }

    private void placeholderLoop(RunState state) {
        try {
            while (true) {
                if (state.finished.get()) {
                    return;
                }
                if (state.budget.isAborted()) {
                    // 预算/墙钟/AI 不可用的中止都先同步到停止点，保持「首因生效」。
                    state.stopPoint.requestStop(state.budget.abortReason());
                }
                // 轮询用 poll()：内存中止标志立即生效，数据库探针每秒最多查一次，不压库。
                EvalRunStopPoint.Decision decision = state.stopPoint.poll();
                if (decision.stop()) {
                    if (decision.kind() == EvalRunStopPoint.StopKind.TERMINAL_IN_STORE) {
                        // 数据库已被其他路径收口：只释放内存持有，不再发 CAS，禁止用内存覆盖 DB。
                        if (state.finished.compareAndSet(false, true)) {
                            active.compareAndSet(state, null);
                        }
                        warn("run 已由其他路径收口，run_id=" + state.runId + "，本轮不再发终态 CAS");
                    } else {
                        // §13.4：CAS 失败只记 WARN、不重试，以数据库现行状态为准（遗留 RUNNING 由 init 回收）。
                        finishAbort(state, decision.reason());
                    }
                    return;
                }
                if (state.budget.deadlineExceeded(System.currentTimeMillis())) {
                    state.budget.abort(EvalStore.ABORT_REASON_DEADLINE);
                    continue;
                }
                Thread.sleep(POLL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 把 active run 原子改写为 ABORTED。
     *
     * <p>§13.4：CAS 返回 -1 或 0 都只记 WARN、不重试、不得用内存状态覆盖 DB；因此写库失败时
     * 只清掉内存中的 active（让后续命令以数据库为准），不重置 {@code finished}、不重发 CAS。</p>
     *
     * @return 本次 CAS 结果；null 表示 finished 闸门已被其他路径抢先关闭，本次没有写库
     */
    private FinalizeResult finishAbort(RunState state, String reason) {
        if (!state.finished.compareAndSet(false, true)) {
            return null;
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - state.startedAtMillis);
        String summary = reason == null || reason.trim().isEmpty()
                ? EvalStore.ABORT_REASON_ORPHAN : reason.trim();
        if (reason == null || reason.trim().isEmpty()) {
            warn("中止原因为空，按 orphan 记录，run_id=" + state.runId);
        }
        FinalizeResult result = casTerminal(store, logger, state.runId, EvalStore.STATUS_ABORTED,
                durationMs, null, summary);
        active.compareAndSet(state, null);
        info("run 以 ABORTED 收口，不发送完成摘要（§9.8），run_id=" + state.runId + "，原因=" + summary);
        return result;
    }

    private RunSnapshot fromState(RunState state) {
        long now = System.currentTimeMillis();
        return new RunSnapshot(state.runId, state.tier, EvalStore.STATUS_RUNNING,
                state.budget.used(), state.budget.limit(), EvalStore.formatTime(state.startedAtMillis),
                state.budget.planned(), state.caseCount, state.budget.remainingMillis(now));
    }

    private RunSnapshot fromRow(JSONObject row) {
        if (row == null) {
            return null;
        }
        String tier = row.getString("tier");
        int planned = 0;
        int caseCount = 0;
        if (EvalStore.TIER_SMOKE.equals(tier) || EvalStore.TIER_FULL.equals(tier)) {
            planned = EvalPreflight.plannedCalls(tier);
            caseCount = EvalPreflight.caseCount(tier);
        }
        return new RunSnapshot(row.getString("run_id"), tier, row.getString("status"),
                row.getIntValue("calls_used"), row.getIntValue("calls_budget"),
                row.getString("started_at"), planned, caseCount, -1L);
    }

    private void info(String message) {
        if (logger != null) {
            logger.sendInfo("[MBB-Eval] " + message);
        }
    }

    private void warn(String message) {
        warn(logger, message);
    }

    private static void warn(PluginLogger logger, String message) {
        if (logger != null) {
            logger.sendWarn("[MBB-Eval] " + message);
        }
    }
}
