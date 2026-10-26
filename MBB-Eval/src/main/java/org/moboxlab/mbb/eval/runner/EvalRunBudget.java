package org.moboxlab.mbb.eval.runner;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单 run 的调用预算与墙钟硬顶（规格 §2.5、§9.5）。
 *
 * <p>调用计数必须原子递增：运行中每次调用前先 {@link #tryConsume()}，重试与缓存重采都计数；
 * 达到 {@code evalCallBudget} 后返回 false，runner 停止后续调用并落 {@code ABORTED(budget)}。
 * 墙钟只做比较，不创建线程；deadline 由 runner 的占位循环或定时检查触发。</p>
 *
 * <p>本类只保存内存状态，不写库：终态只能由 {@link EvalRunner} 经 CAS 写入（§4.4、§13.4）。</p>
 */
public final class EvalRunBudget {

    private final int planned;
    private final int limit;
    private final long deadlineAtMillis;
    private final AtomicInteger used = new AtomicInteger(0);
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final AtomicReference<String> abortReason = new AtomicReference<String>(null);

    /**
     * @param planned         预检算出的 planned 调用数（full 79 / smoke 25），只用于展示与自检
     * @param limit           {@code evalCallBudget} 生效值，必须为正数
     * @param deadlineAtMillis 墙钟 deadline 的毫秒时间戳
     */
    public EvalRunBudget(int planned, int limit, long deadlineAtMillis) {
        if (limit <= 0) {
            throw new IllegalArgumentException("evalCallBudget 必须为正数：" + limit);
        }
        this.planned = planned;
        this.limit = limit;
        this.deadlineAtMillis = deadlineAtMillis;
    }

    /**
     * 原子占用一次调用额度。
     *
     * @return 未达上限时不抛出、返回 true；已达上限或已中止时返回 false
     */
    public boolean tryConsume() {
        while (true) {
            if (aborted.get()) {
                return false;
            }
            int current = used.get();
            if (current >= limit) {
                return false;
            }
            if (used.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** 标记本 run 需要中止；原因只记录第一次写入的值，重复调用幂等。 */
    public void abort(String reason) {
        abortReason.compareAndSet(null, reason);
        aborted.set(true);
    }

    public boolean isAborted() {
        return aborted.get();
    }

    /** 中止原因（user-abort / budget / deadline / ai-unavailable / shutdown）；未中止时为 null。 */
    public String abortReason() {
        return abortReason.get();
    }

    public boolean deadlineExceeded(long nowMillis) {
        return nowMillis >= deadlineAtMillis;
    }

    public int planned() {
        return planned;
    }

    public int limit() {
        return limit;
    }

    public int used() {
        return used.get();
    }

    public long remainingMillis(long nowMillis) {
        return Math.max(0L, deadlineAtMillis - nowMillis);
    }
}
