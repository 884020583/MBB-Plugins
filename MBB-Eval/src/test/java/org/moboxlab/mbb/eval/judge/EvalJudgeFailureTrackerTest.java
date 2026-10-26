package org.moboxlab.mbb.eval.judge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.judge.EvalJudgeFailureTracker.Decision;
import org.moboxlab.mbb.eval.judge.EvalJudgeFailureTracker.Stage;

/**
 * 判分失败熔断状态机用例（规格 §13.3 规则 4、7，§15.5 H8）。
 *
 * <p>覆盖：连续失败达到阈值先退避一次、退避后仍连续同次数失败才中止为
 * {@code ai-unavailable}、中止后粘滞且不再重复退避、成功清零、invalid / 截断不计入失败、
 * 阈值与退避时长的钳制与配置接线。</p>
 */
public class EvalJudgeFailureTrackerTest {

    @Test
    public void failuresBelowThresholdDoNotBackOffOrAbort() {
        RecordingSleeper sleeper = new RecordingSleeper();
        EvalJudgeFailureTracker tracker = tracker(3, 30, sleeper);

        assertEquals(Decision.CONTINUE, tracker.recordFailure());
        assertEquals(Decision.CONTINUE, tracker.recordFailure());

        assertEquals(2, tracker.streak());
        assertEquals(Stage.NORMAL, tracker.stage());
        assertFalse(tracker.isAborted());
        assertNull(tracker.abortReason());
        assertTrue("未达阈值不得退避", sleeper.seconds.isEmpty());
    }

    @Test
    public void thresholdBacksOffExactlyOnce() {
        RecordingSleeper sleeper = new RecordingSleeper();
        EvalJudgeFailureTracker tracker = tracker(3, 30, sleeper);

        assertEquals(Decision.CONTINUE, tracker.recordFailure());
        assertEquals(Decision.CONTINUE, tracker.recordFailure());
        assertEquals(Decision.BACKOFF, tracker.recordFailure());

        assertEquals("退避时长取配置值", Arrays.asList(Integer.valueOf(30)), sleeper.seconds);
        assertEquals("退避后重新计数", 0, tracker.streak());
        assertEquals(Stage.BACKED_OFF, tracker.stage());
        assertEquals(1, tracker.backoffCount());
        assertFalse("首次退避不得直接中止", tracker.isAborted());
        assertNull(tracker.abortReason());
    }

    @Test
    public void sameStreakAfterBackoffAbortsWithAiUnavailable() {
        RecordingSleeper sleeper = new RecordingSleeper();
        EvalJudgeFailureTracker tracker = tracker(3, 30, sleeper);

        for (int i = 0; i < 3; i++) {
            tracker.recordFailure();
        }
        assertEquals(Decision.CONTINUE, tracker.recordFailure());
        assertEquals(Decision.CONTINUE, tracker.recordFailure());
        assertFalse("退避后未再满阈值前不得中止", tracker.isAborted());
        assertEquals(Decision.ABORT, tracker.recordFailure());

        assertTrue(tracker.isAborted());
        assertEquals("ai-unavailable", tracker.abortReason());
        assertEquals(Stage.ABORTED, tracker.stage());
        assertEquals("中止后不再二次退避", 1, sleeper.seconds.size());
        assertEquals("中止后计数归零", 0, tracker.streak());

        assertEquals("已中止后继续失败仍返回 ABORT", Decision.ABORT, tracker.recordFailure());
        assertEquals("不得重复退避", 1, sleeper.seconds.size());
        assertEquals("已中止后成功不得解除", Decision.ABORT, tracker.recordSuccess());
        assertTrue(tracker.isAborted());
    }

    @Test
    public void successResetsStreakAndStage() {
        RecordingSleeper sleeper = new RecordingSleeper();
        EvalJudgeFailureTracker tracker = tracker(3, 30, sleeper);

        tracker.recordFailure();
        tracker.recordFailure();
        assertEquals(Decision.CONTINUE, tracker.recordSuccess());

        assertEquals(0, tracker.streak());
        assertEquals(Stage.NORMAL, tracker.stage());

        assertEquals(Decision.CONTINUE, tracker.recordFailure());
        assertEquals(Decision.CONTINUE, tracker.recordFailure());
        assertTrue("成功后重新累计，两次失败不到阈值", sleeper.seconds.isEmpty());
        assertEquals(Decision.BACKOFF, tracker.recordFailure());
        assertEquals("第三次失败才退避", Arrays.asList(Integer.valueOf(30)), sleeper.seconds);
    }

    @Test
    public void invalidAndTruncatedResultsAreNeutral() {
        RecordingSleeper sleeper = new RecordingSleeper();
        EvalJudgeFailureTracker tracker = tracker(3, 30, sleeper);

        assertEquals("invalid 不计失败", Decision.CONTINUE, tracker.record(invalid(EvalJudgeResult.KIND_PARSE)));
        assertEquals(0, tracker.streak());

        tracker.recordFailure();
        tracker.recordFailure();
        assertEquals("可应答但输出不合规，不清零",
                Decision.CONTINUE, tracker.record(invalid(EvalJudgeResult.KIND_TRUNCATED)));
        assertEquals(2, tracker.streak());
        assertEquals("紧接的错误凑满阈值", Decision.BACKOFF, tracker.recordFailure());
        assertEquals(Arrays.asList(Integer.valueOf(30)), sleeper.seconds);
    }

    @Test
    public void okResultResetsAndErrorResultAccumulates() {
        RecordingSleeper sleeper = new RecordingSleeper();
        EvalJudgeFailureTracker tracker = tracker(3, 30, sleeper);

        assertEquals(Decision.CONTINUE, tracker.record(error()));
        assertEquals(1, tracker.streak());
        assertEquals(Decision.CONTINUE, tracker.record(ok()));
        assertEquals(0, tracker.streak());
        assertEquals(Stage.NORMAL, tracker.stage());
        assertTrue(sleeper.seconds.isEmpty());
    }

    @Test
    public void constructorClampsThresholdAndBackoff() {
        EvalJudgeFailureTracker lower = new EvalJudgeFailureTracker(0, 1, new RecordingSleeper());
        assertEquals(2, lower.failStreakAbort());
        assertEquals(5, lower.backoffSeconds());

        EvalJudgeFailureTracker upper = new EvalJudgeFailureTracker(9, 999, new RecordingSleeper());
        assertEquals(5, upper.failStreakAbort());
        assertEquals(120, upper.backoffSeconds());
    }

    @Test
    public void fromConfigUsesConfiguredValuesAndDefaults() {
        Map<String, String> values = new HashMap<String, String>();
        values.put("evalFailStreakAbort", "2");
        values.put("evalFailBackoffSeconds", "5");
        RecordingSleeper sleeper = new RecordingSleeper();
        EvalJudgeFailureTracker tracker = EvalJudgeFailureTracker.fromConfig(
                EvalConfig.fromValues(values), sleeper);

        assertEquals(2, tracker.failStreakAbort());
        assertEquals(5, tracker.backoffSeconds());
        assertEquals(Decision.CONTINUE, tracker.recordFailure());
        assertEquals(Decision.BACKOFF, tracker.recordFailure());
        assertEquals(Arrays.asList(Integer.valueOf(5)), sleeper.seconds);

        EvalJudgeFailureTracker defaults = EvalJudgeFailureTracker.fromConfig(
                EvalConfig.fromValues(new HashMap<String, String>()), new RecordingSleeper());
        assertEquals(3, defaults.failStreakAbort());
        assertEquals(30, defaults.backoffSeconds());
    }

    // ---------- 辅助 ----------

    private static EvalJudgeFailureTracker tracker(int streak, int backoff, RecordingSleeper sleeper) {
        return new EvalJudgeFailureTracker(streak, backoff, sleeper);
    }

    private static EvalJudgeResult ok() {
        return EvalJudgeResult.ok("persona", 2, "「你上次说的那本《夜航》」", 1, "{}", "sid", 0);
    }

    private static EvalJudgeResult error() {
        return EvalJudgeResult.error("persona", EvalJudgeResult.KIND_TRANSPORT,
                "timeout", true, 2, null, "超时", "sid", 0);
    }

    private static EvalJudgeResult invalid(String kind) {
        return EvalJudgeResult.invalid("persona", kind, 2, "不是 JSON", "解析失败", "sid", 0);
    }

    /** 记录退避调用，避免测试真的等待。 */
    private static final class RecordingSleeper implements EvalJudgeFailureTracker.Sleeper {

        private final List<Integer> seconds = new ArrayList<Integer>();

        @Override
        public void sleepSeconds(int value) {
            seconds.add(Integer.valueOf(value));
        }
    }
}
