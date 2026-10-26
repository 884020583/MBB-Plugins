package org.moboxlab.mbb.eval.runner;

import com.alibaba.fastjson.JSONObject;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.PluginLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * run 受理与骨架期占位执行（规格 §9.3、§9.4、§13.4）。
 *
 * <p>本切片只负责把「一次 /eval run」的生命周期管起来：预检 → 生成 run_id → 写 RUNNING 行 →
 * 占位执行 → 终态 CAS。占位执行不调用 AI、不写 case/call 行，只是让中止与墙钟路径可以真实走到，
 * 具体评测流程由 F 区替换 {@link #placeholderLoop(RunState)}。</p>
 *
 * <p>并发约束：同一时刻只允许一个 RUNNING run。受理时不仅看内存中的 active，还会查库确认没有
 * 遗留的 RUNNING 行（插件重启后内存状态为空，但库里的行还在），避免同一时间出现两个 RUNNING。
 * 状态行只能由 CAS（{@code WHERE status='RUNNING'}）改写，禁止覆盖式更新（§4.4、§13.4）。</p>
 */
public final class EvalRunner {

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

        static AcceptResult accepted(String runId, String tier, int caseCount, int planned, int budgetLimit) {
            return new AcceptResult(AcceptKind.ACCEPTED, runId, tier, caseCount, planned, budgetLimit, null);
        }

        static AcceptResult busy(String runId, String tier, String message, String hint) {
            return new AcceptResult(AcceptKind.BUSY, runId, tier, 0, 0, 0,
                    new EvalPreflight.Rejection(null, message, hint));
        }

        static AcceptResult rejected(String tier, EvalPreflight.Rejection rejection) {
            return new AcceptResult(AcceptKind.REJECTED, null, tier, 0, 0, 0, rejection);
        }

        static AcceptResult failed(String tier, String message) {
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

        static AbortResult aborted(String runId) {
            return new AbortResult(AbortKind.ABORTED, runId, null);
        }

        static AbortResult none() {
            return new AbortResult(AbortKind.NONE, null, null);
        }

        static AbortResult failed(String message) {
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

    /** 单次 run 的内存状态；只有 active 指向它时才算本进程持有。 */
    private static final class RunState {
        private final String runId;
        private final String tier;
        private final int caseCount;
        private final long startedAtMillis;
        private final EvalRunBudget budget;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private RunState(String runId, String tier, int caseCount, long startedAtMillis, EvalRunBudget budget) {
            this.runId = runId;
            this.tier = tier;
            this.caseCount = caseCount;
            this.startedAtMillis = startedAtMillis;
            this.budget = budget;
        }
    }

    private final EvalStore store;
    private final EvalConfig config;
    private final EvalPreflight preflight;
    private final PluginLogger logger;
    private final String evalPluginVersion;
    private final ExecutorService executor;
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
        this.store = store;
        this.config = config;
        this.preflight = preflight;
        this.logger = logger;
        this.evalPluginVersion = evalPluginVersion;
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "MBB-Eval-Runner");
                thread.setDaemon(true);
                return thread;
            }
        });
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
                        "当前进度 " + running.budget.used() + "/" + running.budget.limit()
                                + "（本进程持有该 run）");
            }
            JSONObject leftover = store.findRunningRun();
            if (leftover != null) {
                return busyResult(leftover.getString("run_id"), leftover.getString("tier"),
                        "当前进度 " + leftover.getIntValue("calls_used") + "/"
                                + leftover.getIntValue("calls_budget") + "（上次运行遗留的 RUNNING 行）");
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
                boolean inserted = store.insertRunning(runId, normalized, EvalStore.formatTime(startedAtMillis),
                        budgetLimit, evalPluginVersion);
                if (!inserted) {
                    warn("写入 RUNNING 行失败，重新生成 run_id 后重试（第 " + (attempt + 1) + " 次）");
                    continue;
                }
                long deadlineAtMillis = startedAtMillis
                        + (config == null ? 30L : config.runDeadlineMinutes()) * 60_000L;
                RunState state = new RunState(runId, normalized, caseCount, startedAtMillis,
                        new EvalRunBudget(planned, budgetLimit, deadlineAtMillis));
                active.set(state);
                schedulePlaceholder(state);
                return AcceptResult.accepted(runId, normalized, caseCount, planned, budgetLimit);
            }
            return AcceptResult.failed(normalized, "连续 " + INSERT_MAX_ATTEMPTS + " 次写入 run 行失败");
        }
    }

    /** §9.7：第二个 /eval run 必须直接回当前 runId 与当前进度，不排队、不新建行、不报错。 */
    private AcceptResult busyResult(String runId, String tier, String progress) {
        return AcceptResult.busy(runId, tier, progress,
                "可发 /eval status 查看详情，或 /eval abort 中止该 run");
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
                int result = store.casAborted(runId, EvalStore.nowText(), 0L, EvalStore.ABORT_REASON_USER);
                if (result >= 0) {
                    return AbortResult.aborted(runId);
                }
                return AbortResult.failed("终态写库未执行（§13.4 不重试），以数据库现行状态为准；"
                        + "重启后由下次启动的 init 回收");
            }
            state.budget.abort(EvalStore.ABORT_REASON_USER);
            if (finishAbort(state, EvalStore.ABORT_REASON_USER)) {
                return AbortResult.aborted(state.runId);
            }
            return AbortResult.failed("终态写库未执行（§13.4 不重试），以数据库现行状态为准；"
                    + "重启后由下次启动的 init 回收");
        }
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
    public void shutdown() {
        closing.set(true);
        RunState state = active.get();
        if (state != null) {
            state.budget.abort(EvalStore.ABORT_REASON_SHUTDOWN);
            if (!finishAbort(state, EvalStore.ABORT_REASON_SHUTDOWN)) {
                warn("卸载时写入 ABORTED 失败，run_id=" + state.runId + "，将由下次启动的 init 回收");
            }
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 骨架期占位执行：不调用 AI、不写 case/call 行，只轮询中止与墙钟，命中后落终态。
     * F 区接入真实评测流程时整体替换本方法，终态写入仍复用 {@link #finishAbort(RunState, String)}。
     */
    private void schedulePlaceholder(final RunState state) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                placeholderLoop(state);
            }
        });
    }

    private void placeholderLoop(RunState state) {
        try {
            while (true) {
                if (state.finished.get()) {
                    return;
                }
                if (state.budget.isAborted()) {
                    // §13.4：CAS 失败只记 WARN、不重试，以数据库现行状态为准（遗留 RUNNING 由 init 回收）。
                    finishAbort(state, state.budget.abortReason());
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
     * @return true 表示该 run 已是终态（本次成功或此前已成功）；false 表示写库失败（不重试）
     */
    private boolean finishAbort(RunState state, String reason) {
        if (!state.finished.compareAndSet(false, true)) {
            return true;
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - state.startedAtMillis);
        String summary = reason == null ? "aborted" : reason;
        int result;
        try {
            result = store.casAborted(state.runId, EvalStore.nowText(), durationMs, summary);
        } catch (RuntimeException e) {
            warn("终态 CAS 异常（ABORTED，run_id=" + state.runId + "）：" + e.getMessage());
            result = -1;
        }
        if (result < 0) {
            active.compareAndSet(state, null);
            warn("终态 CAS 未执行（ABORTED，run_id=" + state.runId
                    + "），按 §13.4 记告警、不重试，以数据库现行状态为准");
            return false;
        }
        active.compareAndSet(state, null);
        return true;
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

    private void warn(String message) {
        if (logger != null) {
            logger.sendWarn("[MBB-Eval] " + message);
        }
    }
}
