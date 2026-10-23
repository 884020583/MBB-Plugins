package org.moboxlab.mbb.trpg.ai;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.moboxlab.mbb.trpg.config.TrpgConfig;
import org.moboxlab.mbb.trpg.session.SessionSnapshot;
import org.moboxlab.mbb.trpg.session.SessionState;
import org.moboxlab.mbb.trpg.store.AiTraceRow;
import org.moboxlab.mbb.trpg.testutil.FakeAiClient;
import org.moboxlab.mbb.trpg.testutil.FakeClock;
import org.moboxlab.mbb.trpg.testutil.FakeScheduler;
import org.moboxlab.mbb.trpg.testutil.FakeTraceStore;
import org.moboxlab.mbb.trpg.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * AI 主持引擎测试：覆盖立即触发、合并窗口、排队降级、墙钟、重试、去重、审计与门禁。
 */
public final class AiHostEngineTest {

    private static final long GROUP = 1001L;
    private static final long SESSION = 7L;

    private FakeAiClient client;
    private MutableSessionView view;
    private FakeTraceStore trace;
    private FakeScheduler scheduler;
    private FakeClock clock;
    private RecordingLogger logger;
    private FakeSink sink;
    private FakeFailureRecorder recorder;
    private AiHostEngine engine;

    @Before
    public void setUp() {
        client = new FakeAiClient();
        view = new MutableSessionView(snapshot(GROUP, SESSION, 5L, true, 0L));
        trace = new FakeTraceStore();
        scheduler = new FakeScheduler();
        clock = new FakeClock();
        logger = new RecordingLogger();
        sink = new FakeSink();
        recorder = new FakeFailureRecorder(3);
    }

    @After
    public void tearDown() {
        if (engine != null) {
            engine.shutdown();
            engine = null;
        }
    }

    // ------------------------------------------------------------------
    // 成功与降级
    // ------------------------------------------------------------------

    @Test
    public void successCallsOnceAndSendsNarration() {
        client.setDefaultContent("守夜人推开门，走廊尽头传来滴水声。");
        AiHostEngine host = newEngine(config());
        host.submit(immediateRequest(1L));

        await("等待成功审计", () -> trace.rows().size() == 1);

        assertEquals(1, client.requests().size());
        assertEquals("success", trace.rows().get(0).resultStatus());
        assertEquals(1, trace.rows().get(0).attempts());
        assertEquals(1, sink.narrations().size());
        assertFalse(sink.degradedFlags().get(0).booleanValue());
        assertTrue(sink.narrations().get(0).contains("守夜人"));
        assertEquals(0, recorder.failures());
    }

    @Test
    public void busyCountsFailureAndDegrades() {
        client.enqueue(AiResult.failure(AiErrorTypes.BUSY, true, 1L));
        AiHostEngine host = newEngine(config());
        host.submit(immediateRequest(1L));

        await("等待 busy 降级审计", () -> trace.rows().size() == 1);

        assertEquals(1, client.requests().size());
        assertEquals(1, recorder.failures());
        AiTraceRow row = trace.rows().get(0);
        assertEquals("degraded", row.resultStatus());
        assertEquals("busy", row.degradeReason());
        assertEquals("busy", row.errorType());
        assertEquals("busy", sink.reasons().get(0));
        assertTrue(sink.degradedFlags().get(0).booleanValue());
    }

    @Test
    public void disabledDoesNotCountFailure() {
        client.enqueue(AiResult.failure(AiErrorTypes.DISABLED, false, 1L));
        AiHostEngine host = newEngine(config());
        host.submit(immediateRequest(1L));

        await("等待 disabled 降级审计", () -> trace.rows().size() == 1);

        assertEquals(0, recorder.failures());
        AiTraceRow row = trace.rows().get(0);
        assertEquals("degraded", row.resultStatus());
        assertEquals("unavailable", row.degradeReason());
        assertEquals("unavailable", sink.reasons().get(0));
    }

    @Test
    public void networkFailureDegradesAndCounts() {
        client.enqueue(AiResult.failure("network", true, 1L));
        AiHostEngine host = newEngine(config());
        host.submit(immediateRequest(1L));

        await("等待 network 降级审计", () -> trace.rows().size() == 1);

        assertEquals(1, recorder.failures());
        AiTraceRow row = trace.rows().get(0);
        assertEquals("degraded", row.resultStatus());
        assertEquals("network", row.degradeReason());
        assertEquals("network", row.errorType());
        assertEquals("network", sink.reasons().get(0));
    }

    @Test
    public void http4xxFailureDegradesAndCounts() {
        client.enqueue(AiResult.failure("http_401", true, 1L));
        AiHostEngine host = newEngine(config());
        host.submit(immediateRequest(1L));

        await("等待 http_401 降级审计", () -> trace.rows().size() == 1);

        assertEquals(1, recorder.failures());
        AiTraceRow row = trace.rows().get(0);
        assertEquals("degraded", row.resultStatus());
        assertEquals("http_401", row.degradeReason());
        assertEquals("http_401", row.errorType());
    }

    @Test
    public void http5xxFailureDegradesAndCounts() {
        client.enqueue(AiResult.failure("http_500", true, 1L));
        AiHostEngine host = newEngine(config());
        host.submit(immediateRequest(1L));

        await("等待 http_500 降级审计", () -> trace.rows().size() == 1);

        assertEquals(1, recorder.failures());
        AiTraceRow row = trace.rows().get(0);
        assertEquals("degraded", row.resultStatus());
        assertEquals("http_500", row.degradeReason());
        assertEquals("http_500", row.errorType());
    }

    @Test
    public void finishReasonLengthMarksTruncatedButSucceeds() {
        client.enqueue(AiResult.success("走廊尽头传来滴水声。", "length", "{}", false, 1L));
        AiHostEngine host = newEngine(config());
        host.submit(immediateRequest(1L));

        await("等待 length 截断审计", () -> trace.rows().size() == 1);

        assertEquals(0, recorder.failures());
        AiTraceRow row = trace.rows().get(0);
        assertEquals("success", row.resultStatus());
        assertEquals("truncated", row.degradeReason());
        assertEquals("length", row.finishReason());
        assertEquals("走廊尽头传来滴水声。", sink.narrations().get(0));
        assertFalse(sink.degradedFlags().get(0).booleanValue());
    }

    @Test
    public void timeoutIsWallClockBounded() {
        SlowClient slow = new SlowClient(5000L);
        AiHostEngine host = newEngine(config(single("aiHost.deadlineSeconds", "1")), slow);
        long startAt = System.currentTimeMillis();
        host.submit(immediateRequest(1L));

        await("等待 timeout 降级审计", () -> trace.rows().size() == 1);

        long elapsed = System.currentTimeMillis() - startAt;
        assertTrue("墙钟应远小于 AI 客户端耗时，实际 " + elapsed, elapsed < 3500L);
        AiTraceRow row = trace.rows().get(0);
        assertEquals("degraded", row.resultStatus());
        assertEquals("timeout", row.degradeReason());
        assertEquals("timeout", row.errorType());
        assertEquals(1, recorder.failures());
        assertEquals("timeout", sink.reasons().get(0));
    }

    @Test
    public void validationFailureRetriesOnceAndCountsOneFailure() {
        client.enqueue(AiResult.success("", "stop", "{}", false, 1L));
        client.enqueue(AiResult.success("   ", "stop", "{}", false, 1L));
        AiHostEngine host = newEngine(config());
        host.submit(immediateRequest(1L));

        await("等待验证失败审计", () -> trace.rows().size() == 1);

        assertEquals(2, client.requests().size());
        assertEquals(1, trace.rows().size());
        AiTraceRow row = trace.rows().get(0);
        assertEquals("degraded", row.resultStatus());
        assertEquals("verification", row.degradeReason());
        assertEquals(2, row.attempts());
        assertEquals(1, recorder.failures());
        assertEquals(0, sink.suspendedNotifications());
    }

    @Test
    public void validationFailureThenSuccessKeepsAttemptsTwo() {
        client.enqueue(AiResult.success("", "stop", "{}", false, 1L));
        client.setDefaultContent("檐角的铜铃被风吹响。");
        AiHostEngine host = newEngine(config());
        host.submit(immediateRequest(1L));

        await("等待重试成功审计", () -> trace.rows().size() == 1);

        assertEquals(2, client.requests().size());
        AiTraceRow row = trace.rows().get(0);
        assertEquals("success", row.resultStatus());
        assertEquals(2, row.attempts());
        assertEquals(0, recorder.failures());
    }

    @Test
    public void narrationOverLimitIsTruncatedToThreeHundred() {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < 80; index++) {
            builder.append("风声穿过走廊。");
        }
        client.setDefaultContent(builder.toString());
        AiHostEngine host = newEngine(config());
        host.submit(immediateRequest(1L));

        await("等待截断审计", () -> trace.rows().size() == 1);

        String narration = sink.narrations().get(0);
        assertTrue("叙述应被截断到 300 字以内，实际 " + narration.length(), narration.length() <= 300);
        assertTrue(narration.length() > 0);
        AiTraceRow row = trace.rows().get(0);
        assertEquals("success", row.resultStatus());
        assertEquals("truncated", row.degradeReason());
    }

    // ------------------------------------------------------------------
    // 合并窗口
    // ------------------------------------------------------------------

    @Test
    public void fiveMergedEventsSettleImmediately() {
        AiHostEngine host = newEngine(config());
        for (int seq = 1; seq <= 5; seq++) {
            host.submitMerged(GROUP, SESSION, 5L, seq, "行动 " + seq);
        }

        assertFalse(host.windowOpen(GROUP));
        await("等待合并调用", () -> client.requests().size() == 1);

        assertEquals("merged", client.requests().get(0).triggerType());
        await("等待合并审计", () -> trace.rows().size() == 1);
        AiTraceRow row = trace.rows().get(0);
        assertEquals("merged", row.triggerType());
        assertEquals(5L, row.eventSeq());
        assertEquals("success", row.resultStatus());
    }

    @Test
    public void mergedWindowCarriesPlayerActionTextIntoPrompt() {
        AiHostEngine host = newEngine(config());
        host.submitMerged(GROUP, SESSION, 5L, 31L, "我推开门，观察屋内");
        clock.advance(20_000L);
        host.tick();

        await("等待合并调用", () -> client.requests().size() == 1);
        assertTrue("玩家行动原文应进入提示词",
                client.requests().get(0).dynamic().contains("我推开门，观察屋内"));
    }

    @Test
    public void mergedWindowSettlesOnTickAfterTwentySeconds() {
        AiHostEngine host = newEngine(config());
        host.submitMerged(GROUP, SESSION, 5L, 11L, "推门");
        host.submitMerged(GROUP, SESSION, 5L, 12L, "环顾");
        assertTrue(host.windowOpen(GROUP));
        assertEquals(0, client.requests().size());

        clock.advance(20_000L);
        host.tick();

        assertFalse(host.windowOpen(GROUP));
        await("等待窗口到期调用", () -> client.requests().size() == 1);
        await("等待窗口到期审计", () -> trace.rows().size() == 1);
        assertEquals("merged", trace.rows().get(0).triggerType());
        assertEquals(12L, trace.rows().get(0).eventSeq());
    }

    @Test
    public void mergedEventsAcrossScenesStayInOneWindow() {
        AiHostEngine host = newEngine(config());
        host.submitMerged(GROUP, SESSION, 5L, 21L, "推门");
        host.submitMerged(GROUP, SESSION, 6L, 22L, "环顾");

        // §9.3：窗口键只认局号，同局内场景推进不打断合并，结算时用最新场景版本。
        assertTrue("同局窗口应保持打开", host.windowOpen(GROUP));
        assertEquals(0, trace.rows().size());

        view.set(snapshot(GROUP, SESSION, 6L, true, 0L));
        clock.advance(20_000L);
        host.tick();

        await("等待合并窗口结算", () -> trace.rows().size() == 1);
        assertEquals(22L, trace.rows().get(0).eventSeq());
        assertEquals(1, client.requests().size());
        assertFalse(host.windowOpen(GROUP));
    }

    // ------------------------------------------------------------------
    // 排队降级
    // ------------------------------------------------------------------

    @Test
    public void queueFullDegradesWithoutQueueing() throws Exception {
        BlockingClient blocking = new BlockingClient();
        AiHostEngine host = newEngine(config(), blocking);
        host.submit(immediateRequest(1L));
        assertTrue("首个请求应进入调用", blocking.awaitEntered());

        for (long seq = 2L; seq <= 5L; seq++) {
            host.submit(immediateRequest(seq));
        }
        await("等待 queue_full 审计", () -> hasDegradeReason("queue_full"));
        assertEquals("队列满应立即降级，不排队", 1, countRows("queue_full"));
        blocking.releaseAll();
        await("等待队列排空", () -> host.pendingCount() == 0);
        assertEquals(4, blocking.calls());
    }

    @Test
    public void queueAgeDegradesAfterSixtyFiveSeconds() throws Exception {
        BlockingClient blocking = new BlockingClient();
        AiHostEngine host = newEngine(config(), blocking);
        host.submit(immediateRequest(1L));
        assertTrue(blocking.awaitEntered());
        host.submit(immediateRequest(2L));
        await("等待第二个请求入队", () -> host.pendingCount() == 2);

        clock.advance(66_000L);
        host.tick();

        AiTraceRow row = lastRow();
        assertEquals("degraded", row.resultStatus());
        assertEquals("queue_age", row.degradeReason());
        assertEquals("queue_age", sink.reasons().get(0));
        assertEquals(1, blocking.calls());
        blocking.releaseAll();
        await("等待在途调用收尾", () -> host.pendingCount() == 0);
    }

    // ------------------------------------------------------------------
    // 场景版本、去重与门禁
    // ------------------------------------------------------------------

    @Test
    public void staleResultIsDiscarded() {
        AiClient staleClient = new AiClient() {
            @Override
            public AiResult chat(AiRequest request) {
                view.set(snapshot(GROUP, SESSION, 6L, true, 0L));
                return AiResult.success("守夜人点亮了提灯。", "stop", "{}", false, 1L);
            }
        };
        AiHostEngine host = newEngine(config(), staleClient);
        host.submit(immediateRequestAtVersion(1L, 5L));

        await("等待 stale 审计", () -> trace.rows().size() == 1);

        assertEquals("stale", trace.rows().get(0).resultStatus());
        assertEquals(0, sink.narrations().size());
        assertEquals(0, recorder.failures());
    }

    @Test
    public void dedupeBlocksSameKeyForSixtySeconds() {
        AiHostEngine host = newEngine(config());
        host.submit(immediateRequest(1L));
        await("等待首次调用审计", () -> trace.rows().size() == 1);

        host.submit(immediateRequest(1L));
        assertEquals(1, client.requests().size());

        clock.advance(61_000L);
        host.submit(immediateRequest(1L));
        await("等待去重过期后的第二次调用", () -> client.requests().size() == 2);
        await("等待第二次审计", () -> trace.rows().size() == 2);
    }

    @Test
    public void clearDedupeAllowsImmediateRecall() {
        AiHostEngine host = newEngine(config());
        host.submit(immediateRequest(1L));
        await("等待首次调用审计", () -> trace.rows().size() == 1);

        host.clearDedupe(SESSION);
        host.submit(immediateRequest(1L));
        await("等待清缓存后的调用", () -> client.requests().size() == 2);
    }

    @Test
    public void globalOffMeansNoCallNoSendNoTrace() {
        AiHostEngine host = newEngine(config(single("aiHost.enabled", "false")));
        host.submit(immediateRequest(1L));
        host.submitMerged(GROUP, SESSION, 5L, 2L, "推门");
        host.tick();
        sleep(200L);

        assertEquals(0, client.requests().size());
        assertEquals(0, trace.rows().size());
        assertEquals(0, sink.narrations().size());
        assertEquals(0, sink.degradedFlags().size());
        assertEquals(0, host.pendingCount());
        assertFalse(host.windowOpen(GROUP));
    }

    @Test
    public void sessionAiOffMeansNoCallNoTrace() {
        view.set(snapshot(GROUP, SESSION, 5L, false, 0L));
        AiHostEngine host = newEngine(config());
        host.submit(immediateRequest(1L));
        sleep(200L);

        assertEquals(0, client.requests().size());
        assertEquals(0, trace.rows().size());
        assertEquals(0, sink.narrations().size());
    }

    @Test
    public void suspendedSessionMeansNoCall() {
        view.set(snapshot(GROUP, SESSION, 5L, true, clock.nowMillis() + 60_000L));
        AiHostEngine host = newEngine(config());
        host.submit(immediateRequest(1L));
        sleep(200L);

        assertEquals(0, client.requests().size());
        assertEquals(0, trace.rows().size());
    }

    // ------------------------------------------------------------------
    // 挂起
    // ------------------------------------------------------------------

    @Test
    public void failureThatSuspendsClearsQueueAndNotifiesOnce() throws Exception {
        recorder = new FakeFailureRecorder(1);
        BlockingFailureClient blocking = new BlockingFailureClient();
        AiHostEngine host = newEngine(config(), blocking);
        host.submit(immediateRequest(1L));
        assertTrue(blocking.awaitEntered());
        host.submit(immediateRequest(2L));
        host.submit(immediateRequest(3L));
        await("等待后两个请求入队", () -> host.pendingCount() == 3);

        blocking.releaseAll();
        await("等待挂起后队列清空", () -> host.pendingCount() == 0 && trace.rows().size() == 3);

        assertEquals(1, blocking.calls());
        assertEquals(1, sink.suspendedNotifications());
        assertEquals("suspended", trace.rows().get(0).resultStatus());
        assertEquals("skipped", trace.rows().get(1).resultStatus());
        assertEquals("skipped", trace.rows().get(2).resultStatus());
    }

    // ------------------------------------------------------------------
    // 构造器与门面
    // ------------------------------------------------------------------

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsNullView() {
        new AiHostEngine(config(), client, null, trace, scheduler, clock, logger, sink);
    }

    @Test
    public void shutdownStopsAcceptingRequests() {
        AiHostEngine host = newEngine(config());
        host.shutdown();
        host.submit(immediateRequest(1L));
        host.submitMerged(GROUP, SESSION, 5L, 2L, "推门");
        sleep(100L);

        assertEquals(0, client.requests().size());
        assertEquals(0, trace.rows().size());
        assertEquals(0, host.pendingCount());
    }

    // ------------------------------------------------------------------
    // 挂起门禁与提示
    // ------------------------------------------------------------------

    @Test
    public void suspendedTriggerNotifiesOncePerSuspensionPeriod() {
        view.set(snapshot(GROUP, SESSION, 5L, true, clock.nowMillis() + 600_000L));
        AiHostEngine host = newEngine(config());
        host.submit(immediateRequest(1L));
        await("等待挂起提示", () -> sink.suspendedNotifications() == 1);

        host.submit(immediateRequest(2L));
        sleep(80L);

        assertEquals(1, sink.suspendedNotifications());
        assertEquals(0, client.requests().size());
        assertEquals(0, sink.narrations().size());
    }

    @Test
    public void failureAfterSuspensionIsDiscardedAsSkipped() throws Exception {
        BlockingFailureClient blocking = new BlockingFailureClient();
        AiHostEngine host = newEngine(config(), blocking);
        host.submit(immediateRequest(1L));
        assertTrue(blocking.awaitEntered());

        // 调用在途期间局进入挂起；在途失败应丢弃，不发叙述、不计失败（§9.6）。
        view.set(snapshot(GROUP, SESSION, 5L, true, clock.nowMillis() + 600_000L));
        blocking.releaseAll();

        await("等待 skipped 审计", () -> trace.rows().size() == 1);
        assertEquals("skipped", trace.rows().get(0).resultStatus());
        assertEquals(0, sink.narrations().size());
        assertEquals(0, recorder.failures());
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    private AiHostEngine newEngine(TrpgConfig config) {
        return newEngine(config, client);
    }

    private AiHostEngine newEngine(TrpgConfig config, AiClient aiClient) {
        engine = new AiHostEngine(config, aiClient, view, trace, scheduler, clock, logger, sink);
        engine.bindFailureRecorder(recorder);
        return engine;
    }

    private AiCallRequest immediateRequest(long eventSeq) {
        return immediateRequestAtVersion(eventSeq, 5L);
    }

    private AiCallRequest immediateRequestAtVersion(long eventSeq, long sceneVersion) {
        List<Long> seqs = new ArrayList<Long>();
        seqs.add(Long.valueOf(eventSeq));
        List<String> lines = new ArrayList<String>();
        lines.add("玩家行动 " + eventSeq);
        return new AiCallRequest(GROUP, SESSION, AiTrigger.PLAYER_ACTION, seqs,
                eventSeq, eventSeq, sceneVersion, true, lines);
    }

    private static SessionSnapshot snapshot(long groupId, long sessionNo, long sceneVersion,
                                            boolean aiEnabled, long suspendedUntil) {
        return SessionSnapshot.builder()
                .groupId(groupId)
                .sessionId(sessionNo)
                .state(SessionState.RUNNING)
                .scriptId("script-1")
                .ruleSetId("d100")
                .sceneVersion(sceneVersion)
                .aiEnabled(aiEnabled)
                .aiSuspendedUntil(suspendedUntil)
                .build();
    }

    private static TrpgConfig config() {
        return config(Collections.<String, String>emptyMap());
    }

    private static TrpgConfig config(Map<String, String> overrides) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("aiHost.enabled", "true");
        values.put("aiHost.deadlineSeconds", "5");
        values.put("aiHost.providerTimeoutSeconds", "1");
        values.put("aiHost.maxConcurrent", "2");
        values.put("aiHost.perGroupQueue", "3");
        values.put("aiHost.globalQueue", "4");
        values.put("aiHost.queueMaxAgeSeconds", "65");
        values.put("aiHost.mergeWindowSeconds", "20");
        values.put("aiHost.mergeWindowEvents", "5");
        values.put("aiHost.dedupeCacheSeconds", "60");
        values.put("aiHost.traceEnabled", "true");
        values.put("aiHost.narrationMaxChars", "300");
        values.put("aiHost.promptBudgetChars", "6000");
        values.put("aiHost.dynamicMinChars", "1500");
        values.putAll(overrides);
        return TrpgConfig.fromValues(values);
    }

    private static Map<String, String> single(String key, String value) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put(key, value);
        return map;
    }

    private boolean hasDegradeReason(String reason) {
        for (AiTraceRow row : trace.rows()) {
            if (reason.equals(row.degradeReason())) {
                return true;
            }
        }
        return false;
    }

    private int countRows(String degradeReason) {
        int count = 0;
        for (AiTraceRow row : trace.rows()) {
            if (degradeReason.equals(row.degradeReason())) {
                count++;
            }
        }
        return count;
    }

    private AiTraceRow lastRow() {
        List<AiTraceRow> rows = trace.rows();
        return rows.get(rows.size() - 1);
    }

    private static void await(String message, Condition condition) {
        long deadline = System.currentTimeMillis() + 4000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.matches()) {
                return;
            }
            sleep(10L);
        }
        fail("等待超时：" + message);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            fail("等待被中断");
        }
    }

    private interface Condition {
        boolean matches();
    }

    /** 可修改快照的局视图。 */
    private static final class MutableSessionView implements SessionView {

        private volatile SessionSnapshot snapshot;

        MutableSessionView(SessionSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        void set(SessionSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public SessionSnapshot readSnapshot(long groupId) {
            SessionSnapshot current = snapshot;
            if (current != null && current.groupId() == groupId) {
                return current;
            }
            return SessionSnapshot.none(groupId);
        }
    }

    /** 记录发送内容的叙述端口假件。 */
    private static final class FakeSink implements AiNarrationSink {

        private final List<String> narrations = new ArrayList<String>();
        private final List<Boolean> degradedFlags = new ArrayList<Boolean>();
        private final List<String> reasons = new ArrayList<String>();
        private int suspendedNotifications;

        @Override
        public synchronized void send(long groupId, String narration, boolean degraded,
                                      String degradeReason) {
            narrations.add(narration);
            degradedFlags.add(Boolean.valueOf(degraded));
            reasons.add(degradeReason);
        }

        @Override
        public synchronized void notifySuspended(long groupId) {
            suspendedNotifications++;
        }

        synchronized List<String> narrations() {
            return new ArrayList<String>(narrations);
        }

        synchronized List<Boolean> degradedFlags() {
            return new ArrayList<Boolean>(degradedFlags);
        }

        synchronized List<String> reasons() {
            return new ArrayList<String>(reasons);
        }

        synchronized int suspendedNotifications() {
            return suspendedNotifications;
        }
    }

    /** 失败计数端口假件：达到阈值后返回已挂起。 */
    private static final class FakeFailureRecorder implements AiFailureRecorder {

        private final int suspendAfter;
        private int failures;
        private boolean suspended;

        FakeFailureRecorder(int suspendAfter) {
            this.suspendAfter = suspendAfter;
        }

        @Override
        public synchronized AiFailureOutcome recordFailure(long groupId) {
            failures++;
            if (failures >= suspendAfter) {
                suspended = true;
            }
            return AiFailureOutcome.of(SESSION, suspended, suspended ? 600_000L : 0L, failures);
        }

        @Override
        public synchronized boolean recordSuccess(long groupId) {
            failures = 0;
            return true;
        }

        synchronized int failures() {
            return failures;
        }
    }

    /** 调用会阻塞在门闩上的假 AI 客户端。 */
    private static final class BlockingClient implements AiClient {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AiResult chat(AiRequest request) {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return AiResult.failure("interrupted", false, 0L);
            }
            return AiResult.success("假 AI 叙述", "stop", "{}", false, 1L);
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.await(3L, TimeUnit.SECONDS);
        }

        void releaseAll() {
            release.countDown();
        }

        int calls() {
            return calls.get();
        }
    }

    /** 阻塞后返回 busy 失败的假 AI 客户端，用于挂起清队列测试。 */
    private static final class BlockingFailureClient implements AiClient {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AiResult chat(AiRequest request) {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return AiResult.failure("interrupted", false, 0L);
            }
            return AiResult.failure(AiErrorTypes.BUSY, true, 1L);
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.await(3L, TimeUnit.SECONDS);
        }

        void releaseAll() {
            release.countDown();
        }

        int calls() {
            return calls.get();
        }
    }

    /** 固定睡眠的假 AI 客户端，用于墙钟测试。 */
    private static final class SlowClient implements AiClient {

        private final long sleepMillis;

        SlowClient(long sleepMillis) {
            this.sleepMillis = sleepMillis;
        }

        @Override
        public AiResult chat(AiRequest request) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return AiResult.failure("interrupted", false, 0L);
            }
            return AiResult.success("慢速叙述", "stop", "{}", false, 1L);
        }
    }
}
