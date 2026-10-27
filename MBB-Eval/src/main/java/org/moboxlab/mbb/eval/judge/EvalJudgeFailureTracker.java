package org.moboxlab.mbb.eval.judge;

import org.moboxlab.mbb.eval.config.EvalConfig;

/**
 * 判分失败熔断状态机（规格 §13.3 规则 4、7，§15.5 H8）。
 *
 * <p>只对「传输失败」计数：解析失败会先按原 prompt 重试一次，最终记 invalid 单列并剔除分母，
 * 说明 AI 尚可应答，属于样本级问题，不升级为 run 中止。连续失败达到
 * {@code evalFailStreakAbort} 次时先退避 {@code evalFailBackoffSeconds} 秒并清零计数；
 * 退避后再次连续同次数失败才判定 AI 不可用，交给上层以 {@code ai-unavailable} 中止 run。
 * 中止状态粘滞，成功也不解除，避免退避后恢复调用造成反复抖动。</p>
 */
public final class EvalJudgeFailureTracker {

    /** 终态原因码：AI 连续不可用（store 侧暂未定义，接线时由 runner 透传）。 */
    public static final String ABORT_REASON_AI_UNAVAILABLE = "ai-unavailable";

    public static final int DEFAULT_FAIL_STREAK_ABORT = 3;
    public static final int MIN_FAIL_STREAK_ABORT = 2;
    public static final int MAX_FAIL_STREAK_ABORT = 5;

    public static final int DEFAULT_BACKOFF_SECONDS = 30;
    public static final int MIN_BACKOFF_SECONDS = 5;
    public static final int MAX_BACKOFF_SECONDS = 120;

    /** 一次失败记录后的处置。 */
    public enum Decision {
        /** 继续，未达阈值。 */
        CONTINUE,
        /** 已退避，等待下一次判定。 */
        BACKOFF,
        /** 退避后仍连续失败，run 应中止。 */
        ABORT
    }

    /** 熔断阶段。 */
    public enum Stage {
        /** 正常判定。 */
        NORMAL,
        /** 已退避一次，失败会升级为中止。 */
        BACKED_OFF,
        /** 已中止，粘滞。 */
        ABORTED
    }

    /** 退避睡眠，测试注入以避免真实等待。 */
    public interface Sleeper {

        void sleepSeconds(int seconds);
    }

    private static final Sleeper THREAD_SLEEPER = new Sleeper() {
        @Override
        public void sleepSeconds(int seconds) {
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException e) {
                // 恢复中断标志，交由外层尽快收尾；不把中断当作 AI 可用。
                Thread.currentThread().interrupt();
            }
        }
    };

    private final int failStreakAbort;
    private final int backoffSeconds;
    private final Sleeper sleeper;

    private int streak;
    private int backoffCount;
    private Stage stage = Stage.NORMAL;
    private String abortReason;

    public EvalJudgeFailureTracker(int failStreakAbort, int backoffSeconds, Sleeper sleeper) {
        if (sleeper == null) {
            throw new IllegalArgumentException("sleeper 不能为空");
        }
        this.failStreakAbort = clamp(failStreakAbort, MIN_FAIL_STREAK_ABORT, MAX_FAIL_STREAK_ABORT);
        this.backoffSeconds = clamp(backoffSeconds, MIN_BACKOFF_SECONDS, MAX_BACKOFF_SECONDS);
        this.sleeper = sleeper;
    }

    public static EvalJudgeFailureTracker fromConfig(EvalConfig config) {
        return fromConfig(config, THREAD_SLEEPER);
    }

    public static EvalJudgeFailureTracker fromConfig(EvalConfig config, Sleeper sleeper) {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为空");
        }
        return new EvalJudgeFailureTracker(config.failStreakAbort(), config.failBackoffSeconds(), sleeper);
    }

    /** 成功一次：清零计数并回到正常阶段；已中止则保持中止。 */
    public synchronized Decision recordSuccess() {
        if (stage == Stage.ABORTED) {
            return Decision.ABORT;
        }
        streak = 0;
        stage = Stage.NORMAL;
        return Decision.CONTINUE;
    }

    /** 失败一次：达到阈值先退避一次，退避后再满阈值则中止。 */
    public Decision recordFailure() {
        int sleep = 0;
        Decision decision;
        synchronized (this) {
            if (stage == Stage.ABORTED) {
                return Decision.ABORT;
            }
            streak++;
            if (streak < failStreakAbort) {
                return Decision.CONTINUE;
            }
            streak = 0;
            if (stage == Stage.BACKED_OFF) {
                stage = Stage.ABORTED;
                abortReason = ABORT_REASON_AI_UNAVAILABLE;
                return Decision.ABORT;
            }
            stage = Stage.BACKED_OFF;
            backoffCount++;
            sleep = backoffSeconds;
            decision = Decision.BACKOFF;
        }
        // 睡眠放到锁外，避免真实退避期间阻塞其他线程的读取。
        sleeper.sleepSeconds(sleep);
        return decision;
    }

    /**
     * 按判分结果计数：OK 视为成功，ERROR（传输失败且重试已用尽）视为失败，
     * INVALID（解析失败或截断）既不清零也不计失败，符合「只以一次调用最终结果为准」的口径。
     */
    public Decision record(EvalJudgeResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result 不能为空");
        }
        if (result.isOk()) {
            return recordSuccess();
        }
        if (result.isError()) {
            return recordFailure();
        }
        return Decision.CONTINUE;
    }

    public synchronized boolean isAborted() {
        return stage == Stage.ABORTED;
    }

    public synchronized String abortReason() {
        return abortReason;
    }

    public synchronized int streak() {
        return streak;
    }

    public synchronized int backoffCount() {
        return backoffCount;
    }

    public synchronized Stage stage() {
        return stage;
    }

    public int failStreakAbort() {
        return failStreakAbort;
    }

    public int backoffSeconds() {
        return backoffSeconds;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
