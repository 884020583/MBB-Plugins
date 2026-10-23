package org.moboxlab.mbb.trpg.ai;

import org.moboxlab.moboxbot.API.PluginLogger;
import org.moboxlab.mbb.trpg.config.TrpgConfig;
import org.moboxlab.mbb.trpg.script.Scene;
import org.moboxlab.mbb.trpg.script.Script;
import org.moboxlab.mbb.trpg.script.ScriptCatalog;
import org.moboxlab.mbb.trpg.session.Clock;
import org.moboxlab.mbb.trpg.session.ScheduledTask;
import org.moboxlab.mbb.trpg.session.Scheduler;
import org.moboxlab.mbb.trpg.session.SessionSnapshot;
import org.moboxlab.mbb.trpg.session.SessionState;
import org.moboxlab.mbb.trpg.store.AiTraceRow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 主持引擎：触发合并、排队与并发、调用、输出校验、降级与审计。
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li>只做编排与降级，不直接发群消息：发送由 {@link AiNarrationSink} 接线；</li>
 *   <li>连续失败阈值、挂起时间戳与失败计数清零由 SessionService 负责，引擎通过
 *       {@link AiFailureRecorder} 回调并只消费返回结果；</li>
 *   <li>提示词装配与叙述解析分别委托 {@link AiPromptAssembler} 与 {@link AiNarrationParser}。</li>
 * </ul>
 *
 * <p>调用必须异步：submit 只做入队，实际 AI 调用在守护线程池中执行，并用
 * Future.get(deadline) 做插件侧墙钟，不阻塞事件监听线程。</p>
 */
public final class AiHostEngine {

    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_DEGRADED = "degraded";
    private static final String RESULT_SUSPENDED = "suspended";
    private static final String RESULT_STALE = "stale";
    private static final String RESULT_SKIPPED = "skipped";

    private static final String REASON_QUEUE_FULL = "queue_full";
    private static final String REASON_QUEUE_AGE = "queue_age";
    private static final String REASON_VERIFICATION = "verification";
    private static final String REASON_TRUNCATED = "truncated";
    private static final String REASON_UNAVAILABLE = "unavailable";
    private static final String REASON_BUSY = "busy";
    private static final String REASON_TIMEOUT = "timeout";
    private static final String REASON_NETWORK = "network";
    private static final String REASON_INTERRUPTED = "interrupted";    private static final String ERROR_HTTP_PREFIX = "http_";
    private static final String FINISH_LENGTH = "length";
    private static final int PREVIEW_MAX_CHARS = 240;

    private final TrpgConfig config;
    private final AiClient client;
    private final SessionView view;
    private final TraceStore trace;
    private final Scheduler scheduler;
    private final Clock clock;
    private final PluginLogger logger;
    private final AiNarrationSink sink;

    /** 全局并发许可，数量等于 config.aiMaxConcurrent()。 */
    private final Semaphore concurrency;
    /** 每群串行工作线程所在的线程池（缓存线程池，守护线程）。 */
    private final ExecutorService groupExecutor;
    /** 真正执行 AI 调用的线程池，容量等于全局并发上限。 */
    private final ExecutorService callExecutor;

    private final Object lock = new Object();
    private final Map<Long, GroupQueue> queues = new HashMap<Long, GroupQueue>();
    private final Map<Long, MergeWindow> windows = new HashMap<Long, MergeWindow>();
    private final Map<String, Long> dedupeExpiry = new HashMap<String, Long>();
    /** 本挂起周期已发过挂起提示的群，避免动作提示与触发提示各发一次（§9.5）。 */
    private final Set<Long> suspensionNotified = new LinkedHashSet<Long>();
    private int globalWaiting;
    private int inFlight;
    private boolean shutdown;

    private volatile AiFailureRecorder failureRecorder;
    private volatile ScriptCatalog scriptCatalog;

    /**
     * 创建引擎。
     *
     * <p>构造器只做依赖注入，不启动任何调用；失败计数端口与剧本目录通过
     * {@link #bindFailureRecorder(AiFailureRecorder)}、{@link #bindScriptCatalog(ScriptCatalog)} 后续绑定。</p>
     */
    public AiHostEngine(TrpgConfig config, AiClient client, SessionView view, TraceStore trace,
                        Scheduler scheduler, Clock clock, PluginLogger logger, AiNarrationSink sink) {
        this(config, client, view, trace, scheduler, clock, logger, sink, 0);
    }

    /**
     * 完整构造：{@code maxConcurrent} 为 MBB-AI 侧的全局并发上限，正数时与配置取较小值（§9.7）。
     *
     * @param maxConcurrent MBB-AI 启动时报告的并发数；0 或负数表示不额外限制
     */
    public AiHostEngine(TrpgConfig config, AiClient client, SessionView view, TraceStore trace,
                        Scheduler scheduler, Clock clock, PluginLogger logger, AiNarrationSink sink,
                        int maxConcurrent) {
        if (config == null) {
            throw new IllegalArgumentException("配置不能为空");
        }
        if (client == null) {
            throw new IllegalArgumentException("AI 客户端不能为空");
        }
        if (view == null) {
            throw new IllegalArgumentException("局视图不能为空");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("定时器不能为空");
        }
        if (clock == null) {
            throw new IllegalArgumentException("时钟不能为空");
        }
        if (sink == null) {
            throw new IllegalArgumentException("叙述发送端口不能为空");
        }
        this.config = config;
        this.client = client;
        this.view = view;
        this.trace = trace;
        this.scheduler = scheduler;
        this.clock = clock;
        this.logger = logger;
        this.sink = sink;
        int limit = config.aiMaxConcurrent();
        if (maxConcurrent > 0) {
            limit = Math.min(limit, maxConcurrent);
        }
        limit = Math.max(1, limit);
        this.concurrency = new Semaphore(limit);
        // 每群串行任务会阻塞在并发许可或墙钟上；固定池避免按群无限增线程。
        this.groupExecutor = Executors.newFixedThreadPool(Math.max(2, limit * 2),
                daemonFactory("mbb-trpg-ai-group"));
        this.callExecutor = Executors.newFixedThreadPool(limit, daemonFactory("mbb-trpg-ai-call"));
    }

    /** 绑定失败计数端口（由 SessionService 实现）。未绑定时引擎不记录失败、不清零。 */
    public void bindFailureRecorder(AiFailureRecorder recorder) {
        this.failureRecorder = recorder;
    }

    /** 绑定剧本目录，用于按 snapshot.scriptId() 取剧本传给提示词装配。 */
    public void bindScriptCatalog(ScriptCatalog catalog) {
        this.scriptCatalog = catalog;
    }

    /**
     * 立即触发路径：入队并尽快执行。
     *
     * <p>全局关、本局 AI 关或本局挂起时直接丢弃（不调用、不发送、不审计）。
     * 可合并触发且未标记 immediate 的请求会并入合并窗口。</p>
     */
    public void submit(AiCallRequest request) {
        if (request == null || !accepting()) {
            return;
        }
        if (!allowsOrNotify(request.groupId())) {
            return;
        }
        if (!request.immediate() && request.trigger().mergeable()) {
            List<Long> seqs = request.eventSeqs();
            if (seqs.isEmpty()) {
                enqueue(request);
                return;
            }
            List<String> lines = request.recentEventLines();
            for (int index = 0; index < seqs.size(); index++) {
                String line = index < lines.size() ? lines.get(index) : "";
                addToWindow(request.groupId(), request.sessionNo(), request.sceneVersion(),
                        seqs.get(index).longValue(), line);
            }
            return;
        }
        enqueue(request);
    }

    /**
     * 并入 20 秒 / 5 条合并窗口。
     *
     * <p>第 5 条事件到达时立即结算并取消定时器；否则由 {@link #tick()} 或窗口定时器结算。</p>
     */
    public void submitMerged(long groupId, long sessionId, long sceneVersion, long eventSeq,
                             String actionText) {
        if (!accepting() || eventSeq <= 0L) {
            return;
        }
        if (!allowsOrNotify(groupId)) {
            return;
        }
        addToWindow(groupId, sessionId, sceneVersion, eventSeq, actionText);
    }

    /**
     * 结算到期合并窗口并清理超龄排队请求；由 Scheduler 每 1 秒调用一次。
     */
    public void tick() {
        if (!accepting()) {
            return;
        }
        long now = clock.nowMillis();
        if (!globallyEnabled()) {
            dropWindows();
            synchronized (lock) {
                suspensionNotified.clear();
            }
            return;
        }
        pruneSuspensionNotices();
        List<MergeWindow> due = new ArrayList<MergeWindow>();
        List<PendingCall> expired = new ArrayList<PendingCall>();
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            long windowMillis = mergeWindowMillis();
            Iterator<Map.Entry<Long, MergeWindow>> windowIterator = windows.entrySet().iterator();
            while (windowIterator.hasNext()) {
                MergeWindow window = windowIterator.next().getValue();
                if (now - window.openedAt >= windowMillis) {
                    windowIterator.remove();
                    due.add(window);
                }
            }
            long maxAgeMillis = queueMaxAgeMillis();
            for (GroupQueue queue : queues.values()) {
                Iterator<PendingCall> iterator = queue.waiting.iterator();
                while (iterator.hasNext()) {
                    PendingCall pending = iterator.next();
                    if (now - pending.enqueuedAt > maxAgeMillis) {
                        iterator.remove();
                        globalWaiting--;
                        expired.add(pending);
                    }
                }
            }
        }
        for (MergeWindow window : due) {
            cancel(window.timer);
            enqueueMerged(window);
        }
        for (PendingCall pending : expired) {
            degradeQueueAge(pending, now - pending.enqueuedAt);
        }
    }

    /** 停止接受新请求并取消窗口定时器；在途调用自行收尾。 */
    public void shutdown() {
        List<MergeWindow> pendingWindows = new ArrayList<MergeWindow>();
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            shutdown = true;
            pendingWindows.addAll(windows.values());
            windows.clear();
            queues.clear();
            dedupeExpiry.clear();
            suspensionNotified.clear();
            globalWaiting = 0;
            inFlight = 0;
        }
        for (MergeWindow window : pendingWindows) {
            cancel(window.timer);
        }
        groupExecutor.shutdownNow();
        callExecutor.shutdownNow();
    }

    /** 当前排队中与执行中的请求总数。 */
    public int pendingCount() {
        synchronized (lock) {
            return globalWaiting + inFlight;
        }
    }

    /** 指定群是否还有合并窗口未结算。 */
    public boolean windowOpen(long groupId) {
        synchronized (lock) {
            return windows.containsKey(Long.valueOf(groupId));
        }
    }

    /** 清理某一局的去重缓存（ai on 时调用）。 */
    public void clearDedupe(long sessionNo) {
        String suffix = "-" + sessionNo;
        synchronized (lock) {
            Iterator<Map.Entry<String, Long>> iterator = dedupeExpiry.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Long> entry = iterator.next();
                String key = entry.getKey();
                String sessionKey = key.substring(0, key.indexOf('|'));
                if (sessionKey.endsWith(suffix)) {
                    iterator.remove();
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 入队与合并窗口
    // ------------------------------------------------------------------

    /** 入队；超限不排队直接 queue_full 降级，去重命中静默跳过。 */
    private void enqueue(AiCallRequest request) {
        if (request == null || !accepting()) {
            return;
        }
        boolean full = false;
        boolean accepted = false;
        boolean startWorker = false;
        long groupId = request.groupId();
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            GroupQueue queue = queues.get(Long.valueOf(groupId));
            if (queue == null) {
                queue = new GroupQueue(groupId);
                queues.put(Long.valueOf(groupId), queue);
            }
            int perGroupLimit = Math.max(1, config.aiPerGroupQueue());
            int globalLimit = Math.max(1, config.aiGlobalQueue());
            if (queue.waiting.size() >= perGroupLimit || globalWaiting >= globalLimit) {
                full = true;
            } else if (!registerDedupeLocked(request)) {
                // 同键 60 秒内已调用或已入队，静默跳过。
            } else {
                queue.waiting.addLast(new PendingCall(request, clock.nowMillis()));
                globalWaiting++;
                accepted = true;
                if (!queue.workerRunning) {
                    queue.workerRunning = true;
                    startWorker = true;
                }
            }
        }
        if (full) {
            degradeQueueFull(request);
            return;
        }
        if (!accepted) {
            return;
        }
        if (startWorker) {
            startWorker(groupId);
        }
    }

    private void startWorker(final long groupId) {
        try {
            groupExecutor.execute(new GroupWorker(groupId));
        } catch (RejectedExecutionException error) {
            warn("AI 队列线程提交失败，群 " + groupId + "：" + error);
        }
    }

    /** 每群串行工作线程：取队首、检查超龄与门禁、占全局并发槽位、执行。 */
    private final class GroupWorker implements Runnable {

        private final long groupId;

        GroupWorker(long groupId) {
            this.groupId = groupId;
        }

        @Override
        public void run() {
            while (true) {
                PendingCall pending = takeNext(groupId);
                if (pending == null) {
                    return;
                }
                try {
                    if (isExpired(pending)) {
                        degradeQueueAge(pending, clock.nowMillis() - pending.enqueuedAt);
                        continue;
                    }
                    if (!allowsOrNotify(groupId)) {
                        continue;
                    }
                    int slot = acquireSlot(pending);
                    if (slot < 0) {
                        return;
                    }
                    if (slot == 0) {
                        // 等并发槽位时已超龄：按超龄降级，不再调用（§9.7）。
                        degradeQueueAge(pending, clock.nowMillis() - pending.enqueuedAt);
                        continue;
                    }
                    try {
                        execute(pending);
                    } finally {
                        concurrency.release();
                    }
                } catch (RuntimeException error) {
                    warn("AI 调用线程异常，群 " + groupId + "：" + error);
                } finally {
                    finishOne();
                }
            }
        }
    }

    private PendingCall takeNext(long groupId) {
        synchronized (lock) {
            GroupQueue queue = queues.get(Long.valueOf(groupId));
            if (queue == null || queue.waiting.isEmpty()) {
                if (queue != null) {
                    queue.workerRunning = false;
                    // 空队列立即从表里摘掉，避免长跑按群累积。
                    queues.remove(Long.valueOf(groupId));
                }
                return null;
            }
            PendingCall pending = queue.waiting.pollFirst();
            globalWaiting--;
            inFlight++;
            return pending;
        }
    }

    private void finishOne() {
        synchronized (lock) {
            if (inFlight > 0) {
                inFlight--;
            }
        }
    }

    /**
     * 占全局并发槽位，最多等到本条请求超龄为止。
     *
     * @return 1 已获得；0 等待期间超龄；-1 线程被中断（插件停用）
     */
    private int acquireSlot(PendingCall pending) {
        long maxAge = queueMaxAgeMillis();
        long remaining = maxAge - (clock.nowMillis() - pending.enqueuedAt);
        if (remaining <= 0L) {
            return 0;
        }
        try {
            return concurrency.tryAcquire(remaining, TimeUnit.MILLISECONDS) ? 1 : 0;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private void addToWindow(long groupId, long sessionNo, long sceneVersion, long eventSeq,
                             String actionText) {
        MergeWindow settled = null;
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            MergeWindow window = windows.get(Long.valueOf(groupId));
            // 窗口键只认局号：同一局内场景推进不应打断玩家行动的合并（§9.3）。
            if (window != null && window.sessionNo != sessionNo) {
                windows.remove(Long.valueOf(groupId));
                settled = window;
                window = null;
            }
            if (window == null) {
                window = new MergeWindow(groupId, sessionNo, sceneVersion, clock.nowMillis());
                windows.put(Long.valueOf(groupId), window);
                window.timer = scheduler.runLater(new WindowTimer(window), mergeWindowMillis());
            }
            // 结算时用最新场景版本做 stale 判断，窗口期间场景推进不算过期。
            window.sceneVersion = sceneVersion;
            if (!window.seqs.contains(Long.valueOf(eventSeq))) {
                window.seqs.add(Long.valueOf(eventSeq));
                window.lines.add(actionText == null ? "" : actionText);
                if (window.firstSeq == 0L) {
                    window.firstSeq = eventSeq;
                }
                window.lastSeq = eventSeq;
            }
            if (window.seqs.size() >= Math.max(1, config.aiMergeWindowEvents())) {
                windows.remove(Long.valueOf(groupId));
                settled = window;
            }
        }
        if (settled != null) {
            cancel(settled.timer);
            enqueueMerged(settled);
        }
    }

    private void settleWindow(MergeWindow window) {
        synchronized (lock) {
            if (windows.get(Long.valueOf(window.groupId)) != window) {
                return;
            }
            windows.remove(Long.valueOf(window.groupId));
        }
        cancel(window.timer);
        enqueueMerged(window);
    }

    private void enqueueMerged(MergeWindow window) {
        AiCallRequest request = new AiCallRequest(window.groupId, window.sessionNo,
                AiTrigger.MERGED, new ArrayList<Long>(window.seqs),
                window.firstSeq, window.lastSeq, window.sceneVersion,
                false, new ArrayList<String>(window.lines));
        enqueue(request);
    }

    private void dropWindows() {
        List<MergeWindow> dropped = new ArrayList<MergeWindow>();
        synchronized (lock) {
            dropped.addAll(windows.values());
            windows.clear();
        }
        for (MergeWindow window : dropped) {
            cancel(window.timer);
        }
    }

    private final class WindowTimer implements Runnable {

        private final MergeWindow window;

        WindowTimer(MergeWindow window) {
            this.window = window;
        }

        @Override
        public void run() {
            settleWindow(window);
        }
    }

    // ------------------------------------------------------------------
    // 调用与结果处理
    // ------------------------------------------------------------------

    private void execute(PendingCall pending) {
        AiCallRequest request = pending.request;
        long startAt = System.currentTimeMillis();
        long deadlineAt = startAt + deadlineMillis();
        SessionSnapshot snapshot = snapshot(request.groupId());
        if (!enabled(snapshot) || suspended(snapshot) || !running(snapshot)) {
            return;
        }
        AiRequest prompt;
        try {
            prompt = buildRequest(request, snapshot);
        } catch (RuntimeException error) {
            warn("AI 提示词装配失败，群 " + request.groupId() + "：" + error);
            degrade(request, REASON_UNAVAILABLE, "", 0, 0L, null, "");
            return;
        }
        AiResult result = invoke(prompt, deadlineAt);
        int attempts = 1;
        if (result == null) {
            failTimeout(request, attempts, startAt);
            return;
        }
        if (!result.success()) {
            failResult(request, result, attempts, startAt);
            return;
        }
        Validation validation = validate(result.content(), result.finishReason());
        if (!validation.valid) {
            if (deadlineAt - System.currentTimeMillis() > 0L) {
                result = invoke(prompt, deadlineAt);
                attempts = 2;
                if (result == null) {
                    failTimeout(request, attempts, startAt);
                    return;
                }
                if (!result.success()) {
                    failResult(request, result, attempts, startAt);
                    return;
                }
                validation = validate(result.content(), result.finishReason());
            }
            if (!validation.valid) {
                // 墙钟已过时无法重试：只发生过 1 次调用，按 §9.5 不计失败。
                boolean counts = attempts > 1;
                failVerification(request, result, validation.rejectReason, attempts, startAt, counts);
                return;
            }
        }
        succeed(request, result, validation, attempts, startAt);
    }

    /** 异步调用并做插件侧墙钟；超时返回 null，中断与线程拒绝按不计数失败返回。 */
    private AiResult invoke(final AiRequest prompt, long deadlineAt) {
        long remaining = deadlineAt - System.currentTimeMillis();
        if (remaining <= 0L) {
            return null;
        }
        Future<AiResult> future;
        try {
            future = callExecutor.submit(new Callable<AiResult>() {
                @Override
                public AiResult call() {
                    return client.chat(prompt);
                }
            });
        } catch (RejectedExecutionException error) {
            return AiResult.failure(AiErrorTypes.DISABLED, false, 0L);
        }
        try {
            return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            return null;
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return AiResult.failure(REASON_INTERRUPTED, false, 0L);
        } catch (ExecutionException error) {
            warn("AI 调用异常：" + error.getCause());
            return AiResult.failure(AiErrorTypes.UNKNOWN, false, 0L);
        }
    }

    private AiRequest buildRequest(AiCallRequest request, SessionSnapshot snapshot) {
        Script script = null;
        ScriptCatalog catalog = scriptCatalog;
        if (catalog != null) {
            try {
                script = catalog.get(snapshot.scriptId());
            } catch (RuntimeException error) {
                warn("读取剧本失败，剧本 " + snapshot.scriptId() + "：" + error);
            }
        }
        AiPromptParts parts = AiPromptAssembler.fit(snapshot, script, request.recentEventLines(),
                config.aiPromptBudgetChars(), config.aiDynamicMinChars());
        return new AiRequest(request.trigger().traceName(), parts.stablePrefix(), parts.cacheable(),
                parts.dynamic(), config.aiProviderTimeoutSeconds(), 0, 0.7D, request.sessionKey());
    }

    private Validation validate(String content, String finishReason) {
        if (content == null) {
            return Validation.invalid("empty");
        }
        AiNarrationParser.ParseResult parsed;
        try {
            parsed = AiNarrationParser.parse(content, Math.max(1, config.aiNarrationMaxChars()));
        } catch (RuntimeException error) {
            warn("AI 叙述解析异常：" + error);
            return Validation.invalid("parse_error");
        }
        if (parsed == null || !parsed.valid()) {
            String reason = parsed == null ? "parse_error" : parsed.rejectReason();
            return Validation.invalid(reason == null ? "invalid" : reason);
        }
        String text = parsed.narration();
        if (text == null || text.trim().isEmpty()) {
            return Validation.invalid("empty");
        }
        List<String> suggestions = parsed.suggestions();
        if (suggestions != null && !suggestions.isEmpty()) {
            StringBuilder builder = new StringBuilder(text);
            for (String suggestion : suggestions) {
                if (suggestion == null || suggestion.trim().isEmpty()) {
                    continue;
                }
                builder.append('\n').append(formatSuggestion(suggestion));
            }
            text = builder.toString();
        }
        boolean truncated = parsed.truncated() || FINISH_LENGTH.equalsIgnoreCase(finishReason);
        return Validation.ok(text, truncated);
    }

    private void succeed(AiCallRequest request, AiResult result, Validation validation,
                         int attempts, long startAt) {
        SessionSnapshot latest = snapshot(request.groupId());
        if (latest == null || !enabled(latest)) {
            return;
        }
        if (!latest.active()) {
            audit(request, RESULT_SKIPPED, "", "", attempts, elapsed(startAt),
                    result.finishReason(), result.usageJson(), result.cached(), "");
            return;
        }
        if (suspended(latest)) {
            audit(request, RESULT_SKIPPED, "", "", attempts, elapsed(startAt),
                    result.finishReason(), result.usageJson(), result.cached(), "");
            return;
        }
        if (latest.sessionId() != request.sessionNo()
                || latest.sceneVersion() != request.sceneVersion()) {
            audit(request, RESULT_STALE, "", "", attempts, elapsed(startAt),
                    result.finishReason(), result.usageJson(), result.cached(), "");
            return;
        }
        recordSuccess(request.groupId());
        send(request.groupId(), validation.text, false, "");
        audit(request, RESULT_SUCCESS, validation.truncated ? REASON_TRUNCATED : "",
                "", attempts, elapsed(startAt), result.finishReason(), result.usageJson(),
                result.cached(), validation.text);
    }

    private void failResult(AiCallRequest request, AiResult result, int attempts, long startAt) {
        if (!deliverable(request, attempts, startAt)) {
            return;
        }
        String errorType = result.errorType() == null ? AiErrorTypes.UNKNOWN : result.errorType();
        boolean counts = countsAsFailure(errorType);
        String reason = degradeReason(errorType);
        boolean suspendedNow = counts && recordFailure(request.groupId());
        send(request.groupId(), fallbackNarration(request.groupId()), true, reason);
        audit(request, suspendedNow ? RESULT_SUSPENDED : RESULT_DEGRADED, reason, errorType,
                attempts, elapsed(startAt), result.finishReason(), result.usageJson(),
                result.cached(), "");
        if (suspendedNow) {
            notifySuspended(request.groupId());
            clearQueueOnSuspend(request.groupId());
        }
    }

    private void failTimeout(AiCallRequest request, int attempts, long startAt) {
        if (!deliverable(request, attempts, startAt)) {
            return;
        }
        boolean suspendedNow = recordFailure(request.groupId());
        send(request.groupId(), fallbackNarration(request.groupId()), true, REASON_TIMEOUT);
        audit(request, suspendedNow ? RESULT_SUSPENDED : RESULT_DEGRADED, REASON_TIMEOUT,
                AiErrorTypes.TIMEOUT, attempts, elapsed(startAt), "", "", false, "");
        if (suspendedNow) {
            notifySuspended(request.groupId());
            clearQueueOnSuspend(request.groupId());
        }
    }

    private void failVerification(AiCallRequest request, AiResult result, String rejectReason,
                                  int attempts, long startAt, boolean counts) {
        if (!deliverable(request, attempts, startAt)) {
            return;
        }
        boolean suspendedNow = counts && recordFailure(request.groupId());
        String errorType = rejectReason == null ? "" : rejectReason;
        send(request.groupId(), fallbackNarration(request.groupId()), true, REASON_VERIFICATION);
        audit(request, suspendedNow ? RESULT_SUSPENDED : RESULT_DEGRADED, REASON_VERIFICATION,
                errorType, attempts, elapsed(startAt), result.finishReason(), result.usageJson(),
                result.cached(), "");
        if (suspendedNow) {
            notifySuspended(request.groupId());
            clearQueueOnSuspend(request.groupId());
        }
    }

    private void degrade(AiCallRequest request, String reason, String errorType, int attempts,
                         long elapsedMs, AiResult result, String preview) {
        if (!deliverable(request, attempts, clock.nowMillis() - elapsedMs)) {
            return;
        }
        send(request.groupId(), fallbackNarration(request.groupId()), true, reason);
        audit(request, RESULT_DEGRADED, reason, errorType, attempts, elapsedMs,
                result == null ? "" : result.finishReason(),
                result == null ? "" : result.usageJson(),
                result != null && result.cached(), preview);
    }

    private void degradeQueueFull(AiCallRequest request) {
        if (!deliverableQueued(request)) {
            return;
        }
        send(request.groupId(), fallbackNarration(request.groupId()), true, REASON_QUEUE_FULL);
        audit(request, RESULT_DEGRADED, REASON_QUEUE_FULL, "", 0, 0L, "", "", false, "");
    }

    private void degradeQueueAge(PendingCall pending, long waitedMs) {
        if (!deliverableQueued(pending.request)) {
            return;
        }
        send(pending.request.groupId(), fallbackNarration(pending.request.groupId()), true, REASON_QUEUE_AGE);
        audit(pending.request, RESULT_DEGRADED, REASON_QUEUE_AGE, "", 0, waitedMs,
                "", "", false, "");
    }

    /** 队列阶段的门禁：还没开始调用，审计里的耗时固定为 0。 */
    private boolean deliverableQueued(AiCallRequest request) {
        SessionSnapshot current = snapshot(request.groupId());
        if (current == null || !enabled(current)) {
            return false;
        }
        if (!current.active() || suspended(current)) {
            audit(request, RESULT_SKIPPED, "", "", 0, 0L, "", "", false, "");
            return false;
        }
        if (current.sessionId() != request.sessionNo()
                || current.sceneVersion() != request.sceneVersion()) {
            audit(request, RESULT_STALE, "", "", 0, 0L, "", "", false, "");
            return false;
        }
        return true;
    }

    /**
     * 投递门禁（§9.6）：本局 AI 关时静默丢弃（零审计），局已结束、挂起、换局或换场景时只记审计。
     *
     * @return true 表示可以发送叙述或降级提示
     */
    private boolean deliverable(AiCallRequest request, int attempts, long startAt) {
        SessionSnapshot current = snapshot(request.groupId());
        if (current == null || !enabled(current)) {
            return false;
        }
        if (!current.active() || suspended(current)) {
            audit(request, RESULT_SKIPPED, "", "", attempts, elapsed(startAt), "", "", false, "");
            return false;
        }
        if (current.sessionId() != request.sessionNo()
                || current.sceneVersion() != request.sceneVersion()) {
            audit(request, RESULT_STALE, "", "", attempts, elapsed(startAt), "", "", false, "");
            return false;
        }
        return true;
    }

    /** 记录一次计入失败的失败；返回是否已触发挂起。 */
    private boolean recordFailure(long groupId) {
        AiFailureRecorder recorder = failureRecorder;
        if (recorder == null) {
            return false;
        }
        try {
            AiFailureOutcome outcome = recorder.recordFailure(groupId);
            return outcome != null && outcome.available() && outcome.suspendedNow();
        } catch (RuntimeException error) {
            warn("AI 失败计数写入异常，群 " + groupId + "：" + error);
            return false;
        }
    }

    private void recordSuccess(long groupId) {
        AiFailureRecorder recorder = failureRecorder;
        if (recorder == null) {
            return;
        }
        try {
            recorder.recordSuccess(groupId);
        } catch (RuntimeException error) {
            warn("AI 失败计数清零异常，群 " + groupId + "：" + error);
        }
    }

    private void notifySuspended(long groupId) {
        // 挂起动作本身先占位，挂起后的首次触发就不会重复发同一条提示。
        synchronized (lock) {
            suspensionNotified.add(Long.valueOf(groupId));
        }
        try {
            sink.notifySuspended(groupId);
        } catch (RuntimeException error) {
            warn("AI 挂起提示发送失败，群 " + groupId + "：" + error);
        }
    }

    /** 清掉已不需要提示标记的群：局结束、本局 AI 关闭或挂起已解除后，下一轮挂起要能重新提示。 */
    private void pruneSuspensionNotices() {
        List<Long> groups = new ArrayList<Long>();
        synchronized (lock) {
            groups.addAll(suspensionNotified);
        }
        for (Long group : groups) {
            SessionSnapshot snapshot = view.readSnapshot(group.longValue());
            if (!enabled(snapshot) || suspended(snapshot) == false) {
                synchronized (lock) {
                    suspensionNotified.remove(group);
                }
            }
        }
    }

    /** 挂起后清空本群待发队列；被清项只记 skipped 审计，不发提示。 */
    private void clearQueueOnSuspend(long groupId) {
        List<PendingCall> cleared = new ArrayList<PendingCall>();
        synchronized (lock) {
            GroupQueue queue = queues.get(Long.valueOf(groupId));
            if (queue != null && !queue.waiting.isEmpty()) {
                cleared.addAll(queue.waiting);
                globalWaiting -= queue.waiting.size();
                queue.waiting.clear();
            }
        }
        for (PendingCall pending : cleared) {
            audit(pending.request, RESULT_SKIPPED, "", "", 0, 0L, "", "", false, "");
        }
    }

    private void send(long groupId, String narration, boolean degraded, String degradeReason) {
        try {
            sink.send(groupId, narration == null ? "" : narration, degraded,
                    degradeReason == null ? "" : degradeReason);
        } catch (RuntimeException error) {
            warn("AI 叙述发送失败，群 " + groupId + "：" + error);
        }
    }

    // ------------------------------------------------------------------
    // 审计
    // ------------------------------------------------------------------

    private void audit(AiCallRequest request, String resultStatus, String degradeReason,
                       String errorType, int attempts, long elapsedMs, String finishReason,
                       String usageJson, boolean cached, String preview) {
        if (trace == null || !config.aiTraceEnabled()) {
            return;
        }
        AiTraceRow row = new AiTraceRow(0L, request.groupId(), request.sessionNo(),
                request.latestEventSeq(), request.trigger().traceName(), resultStatus,
                degradeReason == null ? "" : degradeReason,
                errorType == null ? "" : errorType,
                Math.max(0, attempts), Math.max(0L, elapsedMs),
                finishReason == null ? "" : finishReason,
                usageJson == null ? "" : usageJson, cached,
                truncate(preview, PREVIEW_MAX_CHARS), clock.nowMillis());
        try {
            if (!trace.record(row)) {
                warn("AI 审计写入失败，群 " + request.groupId());
            }
        } catch (RuntimeException error) {
            warn("AI 审计写入异常，群 " + request.groupId() + "：" + error);
        }
    }

    // ------------------------------------------------------------------
    // 去重、门禁与配置换算
    // ------------------------------------------------------------------

    private boolean registerDedupeLocked(AiCallRequest request) {
        long now = clock.nowMillis();
        long cacheMillis = Math.max(0L, config.aiDedupeCacheSeconds()) * 1000L;
        Iterator<Map.Entry<String, Long>> iterator = dedupeExpiry.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue().longValue() <= now) {
                iterator.remove();
            }
        }
        String key = request.dedupeKey();
        Long expiry = dedupeExpiry.get(key);
        if (expiry != null && expiry.longValue() > now) {
            return false;
        }
        dedupeExpiry.put(key, Long.valueOf(now + cacheMillis));
        return true;
    }

    private boolean globallyEnabled() {
        return config.aiHostEnabled();
    }

    private boolean accepting() {
        synchronized (lock) {
            return !shutdown;
        }
    }

    /** 全局开关、本局 AI 开关、进行中与挂起状态四重门禁（只判定，不发提示）。 */
    private boolean allowsWithoutNotice(long groupId) {
        if (!globallyEnabled()) {
            return false;
        }
        SessionSnapshot snapshot = view.readSnapshot(groupId);
        return enabled(snapshot) && running(snapshot) && !suspended(snapshot);
    }

    /**
     * 触发入口门禁：挂起期间不执行，但每个挂起周期只回一次挂起提示（§9.5），
     * 避免 KP 的每条指令都静默消失。
     */
    private boolean allowsOrNotify(long groupId) {
        if (allowsWithoutNotice(groupId)) {
            // 挂起已解除：清掉标记，下一轮挂起周期重新允许提示。
            synchronized (lock) {
                suspensionNotified.remove(Long.valueOf(groupId));
            }
            return true;
        }
        if (!globallyEnabled()) {
            return false;
        }
        SessionSnapshot snapshot = view.readSnapshot(groupId);
        if (!enabled(snapshot) || !suspended(snapshot)) {
            return false;
        }
        long until = snapshot.aiSuspendedUntil();
        if (until <= clock.nowMillis()) {
            return false;
        }
        boolean first;
        synchronized (lock) {
            first = suspensionNotified.add(Long.valueOf(groupId));
        }
        if (first) {
            notifySuspended(groupId);
        }
        return false;
    }

    private static boolean enabled(SessionSnapshot snapshot) {
        return snapshot != null && snapshot.aiEnabled();
    }

    /** 只在进行中调用（§9.3）；招募期、已结束与挂起局一律不调。 */
    private static boolean running(SessionSnapshot snapshot) {
        return snapshot != null && snapshot.state() == SessionState.RUNNING;
    }

    private boolean suspended(SessionSnapshot snapshot) {
        return snapshot != null && snapshot.aiSuspendedUntil() > clock.nowMillis();
    }

    private SessionSnapshot snapshot(long groupId) {
        return view.readSnapshot(groupId);
    }

    /**
     * 取当前场景的模板叙述（§9.5）：优先 {@code fallbackNarration}，没有则退回 {@code narration}。
     *
     * <p>降级时必须先发模板叙述再发提示；取不到剧本时返回空串，由发送端口只发提示。</p>
     */
    private String fallbackNarration(long groupId) {
        ScriptCatalog catalog = scriptCatalog;
        if (catalog == null) {
            return "";
        }
        try {
            SessionSnapshot snapshot = view.readSnapshot(groupId);
            if (snapshot == null) {
                return "";
            }
            Script script = catalog.get(snapshot.scriptId());
            if (script == null || script.scenes().isEmpty()) {
                return "";
            }
            int index = snapshot.sceneIndex();
            if (index < 0 || index >= script.scenes().size()) {
                return "";
            }
            Scene scene = script.scenes().get(index);
            String text = scene.fallbackNarration();
            return text.isEmpty() ? scene.narration() : text;
        } catch (RuntimeException error) {
            warn("读取模板叙述失败，群 " + groupId + "：" + error);
            return "";
        }
    }

    private boolean isExpired(PendingCall pending) {
        return clock.nowMillis() - pending.enqueuedAt > queueMaxAgeMillis();
    }

    private long deadlineMillis() {
        return Math.max(1, config.aiDeadlineSeconds()) * 1000L;
    }

    private long mergeWindowMillis() {
        return Math.max(1, config.aiMergeWindowSeconds()) * 1000L;
    }

    private long queueMaxAgeMillis() {
        return Math.max(1, config.aiQueueMaxAgeSeconds()) * 1000L;
    }

    private static boolean countsAsFailure(String errorType) {
        if (errorType == null || errorType.isEmpty()) {
            return false;
        }
        String lower = errorType.toLowerCase(Locale.ROOT);
        return AiErrorTypes.BUSY.equals(lower)
                || AiErrorTypes.TIMEOUT.equals(lower)
                || REASON_NETWORK.equals(lower)
                || lower.startsWith(ERROR_HTTP_PREFIX);
    }

    private static String degradeReason(String errorType) {
        if (errorType == null || errorType.isEmpty()) {
            return REASON_UNAVAILABLE;
        }
        String lower = errorType.toLowerCase(Locale.ROOT);
        if (AiErrorTypes.BUSY.equals(lower)) {
            return REASON_BUSY;
        }
        if (AiErrorTypes.TIMEOUT.equals(lower)) {
            return REASON_TIMEOUT;
        }
        if (REASON_NETWORK.equals(lower) || lower.startsWith(ERROR_HTTP_PREFIX)) {
            return lower;
        }
        if (AiErrorTypes.INTERRUPTED.equals(lower)) {
            return REASON_INTERRUPTED;
        }
        return REASON_UNAVAILABLE;
    }

    private static String formatSuggestion(String suggestion) {
        String text = suggestion.trim();
        if (text.startsWith("建议")) {
            return text;
        }
        return "建议：" + text;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    private static long elapsed(long startAt) {
        return System.currentTimeMillis() - startAt;
    }

    private void cancel(ScheduledTask task) {
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (RuntimeException error) {
            warn("AI 合并窗口定时器取消失败：" + error);
        }
    }

    private void warn(String message) {
        if (logger != null) {
            logger.sendWarn("[MBB-Trpg] " + message);
        }
    }

    private static ThreadFactory daemonFactory(final String prefix) {
        final AtomicInteger counter = new AtomicInteger();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    // ------------------------------------------------------------------
    // 内部数据结构
    // ------------------------------------------------------------------

    private static final class PendingCall {

        private final AiCallRequest request;
        private final long enqueuedAt;

        PendingCall(AiCallRequest request, long enqueuedAt) {
            this.request = request;
            this.enqueuedAt = enqueuedAt;
        }
    }

    private static final class GroupQueue {

        private final long groupId;
        private final Deque<PendingCall> waiting = new ArrayDeque<PendingCall>();
        private boolean workerRunning;

        GroupQueue(long groupId) {
            this.groupId = groupId;
        }
    }

    private static final class MergeWindow {

        private final long groupId;
        private final long sessionNo;
        private long sceneVersion;
        private final long openedAt;
        private final List<Long> seqs = new ArrayList<Long>();
        private final List<String> lines = new ArrayList<String>();
        private long firstSeq;
        private long lastSeq;
        private ScheduledTask timer;

        MergeWindow(long groupId, long sessionNo, long sceneVersion, long openedAt) {
            this.groupId = groupId;
            this.sessionNo = sessionNo;
            this.sceneVersion = sceneVersion;
            this.openedAt = openedAt;
        }
    }

    private static final class Validation {

        private final boolean valid;
        private final String text;
        private final boolean truncated;
        private final String rejectReason;

        private Validation(boolean valid, String text, boolean truncated, String rejectReason) {
            this.valid = valid;
            this.text = text;
            this.truncated = truncated;
            this.rejectReason = rejectReason;
        }

        static Validation ok(String text, boolean truncated) {
            return new Validation(true, text, truncated, "");
        }

        static Validation invalid(String rejectReason) {
            return new Validation(false, "", false, rejectReason);
        }
    }
}
