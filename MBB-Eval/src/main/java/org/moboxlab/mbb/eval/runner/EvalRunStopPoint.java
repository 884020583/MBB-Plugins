package org.moboxlab.mbb.eval.runner;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.moboxlab.mbb.eval.log.EvalLog;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * 单次 run 的停止点（规格 §9.6、§13.4、§16.9 H-4）。
 *
 * <p>中止只做「标记 + 终态 CAS」，不打断在途调用（§9.6）。runner 在每一个写库点调用
 * {@link #beforeCaseWrite()} / {@link #beforeCall()}：一旦中止标志已置位，或数据库里该行已被
 * 其他路径（外部 abort、孤儿清理、人工修库）收口为终态，就返回 stop 结论，调用方立即停止，
 * 不再写新的 case/call 行、不再发起新的调用。已经写入的行保持原样可查，不删除、不回滚。</p>
 *
 * <p>停止点只观察数据库，不写数据库：终态 CAS 由 runner 统一走 {@link EvalRunner} 的三态语义。
 * 探针（{@link RunStatusProbe}）是可选的，只读查询失败时按「未收口」处理，避免查询抖动导致误停；
 * 轮询入口 {@link #poll()} 的探针限频为每秒最多一次，占位循环的高频轮询不会压库；写库点的
 * {@link #check()} 不限频，始终看数据库现行状态。</p>
 */
public final class EvalRunStopPoint {

    /** 停止点检查的结论种类。 */
    public enum StopKind {
        /** 没有中止请求、数据库里该 run 仍是 RUNNING：继续执行。 */
        CONTINUE,
        /** 已收到中止请求（用户 abort、预算、墙钟、AI 不可用、卸载）：在下一个写库点停止。 */
        ABORT_REQUESTED,
        /** 数据库里该 run 已不是 RUNNING：以数据库为准停止，不再发终态 CAS。 */
        TERMINAL_IN_STORE
    }

    /** 一次停止点检查的结论；{@link #stop()} 为 true 时调用方不得再写新的行。 */
    public static final class Decision {
        private static final Decision CONTINUE = new Decision(StopKind.CONTINUE, null);

        private final StopKind kind;
        private final String reason;

        private Decision(StopKind kind, String reason) {
            this.kind = kind;
            this.reason = reason;
        }

        static Decision continueRun() {
            return CONTINUE;
        }

        static Decision abortRequested(String reason) {
            return new Decision(StopKind.ABORT_REQUESTED, reason);
        }

        static Decision terminalInStore() {
            return new Decision(StopKind.TERMINAL_IN_STORE, null);
        }

        public StopKind kind() {
            return kind;
        }

        /** 是否必须在当前写库点停止。 */
        public boolean stop() {
            return kind != StopKind.CONTINUE;
        }

        /** 停止原因码（仅 {@link StopKind#ABORT_REQUESTED} 有值），用于写 run 行 error_summary。 */
        public String reason() {
            return reason;
        }

        /** 便于日志的中文说明。 */
        public String message() {
            if (kind == StopKind.ABORT_REQUESTED) {
                return "收到中止请求（原因 " + reason + "）：在当前写库点停止，已写入的 case/call 行保留";
            }
            if (kind == StopKind.TERMINAL_IN_STORE) {
                return "数据库中该 run 已不是 RUNNING：以数据库为准，在当前写库点停止，不再发终态 CAS";
            }
            return "继续执行";
        }
    }

    /**
     * 可选的只读终态探针：查询数据库中该 run 是否已被其他路径收口。
     *
     * <p>实现方只做查询，不写库、不缓存终态；查询异常由 {@link #check()} 吞掉并按「未收口」处理。</p>
     */
    public interface RunStatusProbe {
        /** true 表示数据库中该 run 已不是 RUNNING。 */
        boolean terminal();
    }

    /** 终态探针的最小查询间隔：占位循环 200ms 轮询一次，不必每次都查库（中止响应仍由内存标志保证）。 */
    private static final long PROBE_MIN_INTERVAL_MILLIS = 1000L;

    private final String runId;
    private final RunStatusProbe probe;
    private final PluginLogger logger;
    private final AtomicReference<String> requestedReason = new AtomicReference<String>(null);
    private final AtomicBoolean terminalSeen = new AtomicBoolean(false);
    /** 上次查询探针的时刻；只用于限频，不参与停止判定。 */
    private volatile long lastProbeAtMillis;

    /** 没有终态探针的停止点：只依赖进程内的中止标志。 */
    public EvalRunStopPoint(String runId) {
        this(runId, null, null);
    }

    /**
     * @param runId 本次 run 的编号
     * @param probe 终态探针，可为 null；只读查询，不写库
     */
    public EvalRunStopPoint(String runId, RunStatusProbe probe) {
        this(runId, probe, null);
    }

    /**
     * @param logger 插件日志，可为 null；只用于探针查询异常时的 WARN
     */
    public EvalRunStopPoint(String runId, RunStatusProbe probe, PluginLogger logger) {
        if (runId == null || runId.trim().isEmpty()) {
            throw new IllegalArgumentException("run_id 不能为空，无法创建停止点");
        }
        this.runId = runId;
        this.probe = probe;
        this.logger = logger;
    }

    public String runId() {
        return runId;
    }

    /**
     * 请求停止：幂等，首次原因生效（§13.1.3 只记第一个真实原因），后续调用不覆盖。
     *
     * @param reason 原因码，例如 {@code user-abort} / {@code budget} / {@code shutdown}；
     *               为 null 或空时按 {@code user-abort} 记录（原因集合封闭在 §13.1.3）
     */
    public void requestStop(String reason) {
        String normalized = reason == null || reason.trim().isEmpty()
                ? EvalStore.ABORT_REASON_USER : reason;
        requestedReason.compareAndSet(null, normalized);
    }

    public boolean stopRequested() {
        return requestedReason.get() != null;
    }

    /** 首次生效的停止原因码；没有请求时返回 null。 */
    public String requestedReason() {
        return requestedReason.get();
    }

    /** 是否已观察到数据库终态（观察后不再重复查询）。 */
    public boolean terminalInStore() {
        return terminalSeen.get();
    }

    /** 每个 case 落库前检查；返回 stop 时不得写这一条 case 行。 */
    public Decision beforeCaseWrite() {
        return check();
    }

    /** 每次调用（含重试、缓存重采）发起前检查；返回 stop 时不得发起调用、不得写调用行。 */
    public Decision beforeCall() {
        return check();
    }

    /**
     * 停止点检查（写库点专用）：先看进程内中止标志，再看数据库终态。
     *
     * <p>中止标志优先：即使数据库已被其他路径收口，也按第一次识别到的停止原因处理。探针只读，
     * 查询异常按「未收口」处理，不误停。写库点必须看到数据库现行状态，因此这里不限频。</p>
     */
    public Decision check() {
        Decision requested = requestedDecision();
        if (requested != null) {
            return requested;
        }
        return probeDecision();
    }

    /**
     * 轮询检查（循环空转时专用）：结论与 {@link #check()} 相同，但终态探针每秒最多查一次。
     *
     * <p>占位循环与真实评测循环都会在等待期间反复调用本方法；内存中止标志始终立即生效，
     * 只有数据库探针被限频，避免 200ms 轮询把只读查询打满。写库点仍应使用 {@link #check()}。</p>
     */
    public Decision poll() {
        Decision requested = requestedDecision();
        if (requested != null) {
            return requested;
        }
        if (probe == null || !shouldQueryProbe(System.currentTimeMillis())) {
            return Decision.continueRun();
        }
        return probeDecision();
    }

    /** 进程内标志优先：中止请求先于数据库终态；没有结论时返回 null。 */
    private Decision requestedDecision() {
        String reason = requestedReason.get();
        if (reason != null) {
            return Decision.abortRequested(reason);
        }
        if (terminalSeen.get()) {
            return Decision.terminalInStore();
        }
        return null;
    }

    /** 查询一次数据库终态；已观察过终态后不再查询。查询后记录时刻，供轮询限频复用。 */
    private Decision probeDecision() {
        if (probe == null) {
            return Decision.continueRun();
        }
        lastProbeAtMillis = System.currentTimeMillis();
        boolean terminal;
        try {
            terminal = probe.terminal();
        } catch (RuntimeException e) {
            EvalLog.warn(logger, "[MBB-Eval] 终态探针查询异常，按未收口处理，run_id=" + runId
                    + "：" + e.getMessage());
            terminal = false;
        }
        if (terminal) {
            terminalSeen.set(true);
            return Decision.terminalInStore();
        }
        return Decision.continueRun();
    }

    /** 探针限频：距上次查询（含写库点的查询）不足一个间隔时跳过，避免高频轮询压库。 */
    private boolean shouldQueryProbe(long nowMillis) {
        return nowMillis - lastProbeAtMillis >= PROBE_MIN_INTERVAL_MILLIS;
    }
}
