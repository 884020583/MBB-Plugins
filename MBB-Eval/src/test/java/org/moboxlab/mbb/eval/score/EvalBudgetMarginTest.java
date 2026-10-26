package org.moboxlab.mbb.eval.score;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.moboxlab.mbb.eval.runner.EvalPreflightPlan;

/**
 * 预算余量用例（D-4：规格 §15.4 D19）。
 *
 * <p>口径：full 档 planned 79（生成 40 + judge ≤36 + 校准 3），硬顶 100，因此预留
 * 21 次重试余量；已用达到 100 次后不得再发起调用，未判题只能记 error。
 * 期望值按规格常量手工冻结。</p>
 */
public class EvalBudgetMarginTest {

    @Test
    public void d19DefaultFullPlanShouldBeSeventyNineAndHardCapHundred() {
        assertEquals("full 档 planned 79", 79, EvalPreflightPlan.PLANNED_CALLS_FULL);
        assertEquals("硬顶 100", 100, EvalPreflightPlan.MAX_CALL_BUDGET);
        assertEquals("重试余量 21", 21,
                EvalPreflightPlan.MAX_CALL_BUDGET - EvalPreflightPlan.PLANNED_CALLS_FULL);
    }

    @Test
    public void d19UsedZeroShouldLeaveFullPlanAndFullHardCap() {
        EvalBudgetMargin margin = EvalBudgetMargin.full(0);

        assertEquals(79, margin.planned());
        assertEquals(100, margin.hardCap());
        assertEquals("计划余量 79", 79, margin.remaining());
        assertEquals("距硬顶 100", 100, margin.remainingBeforeHardCap());
        assertTrue(margin.withinPlan());
        assertTrue(margin.withinHardCap());
        assertTrue(margin.canCall());
    }

    @Test
    public void d19UsedAtPlanShouldExhaustPlanButKeepTwentyOneRetryCalls() {
        EvalBudgetMargin margin = EvalBudgetMargin.full(79);

        assertEquals("计划余量 0", 0, margin.remaining());
        assertEquals("重试余量 21", 21, margin.remainingBeforeHardCap());
        assertTrue(margin.withinPlan());
        assertTrue(margin.canCall());
        assertEquals("预算 79，已用 79，余量 0", margin.summary());
    }

    @Test
    public void d19UsedOverPlanButUnderHardCapShouldFlagPlanOverrun() {
        EvalBudgetMargin margin = EvalBudgetMargin.full(80);

        assertEquals("超计划 1 次", -1, margin.remaining());
        assertEquals("距硬顶 20", 20, margin.remainingBeforeHardCap());
        assertFalse(margin.withinPlan());
        assertTrue("仍低于硬顶，可继续消耗重试余量", margin.withinHardCap());
        assertTrue(margin.canCall());
        assertEquals("预算 79，已用 80，超计划 1（距硬顶 20）", margin.summary());
    }

    @Test
    public void d19UsedAtHardCapShouldForbidFurtherCalls() {
        EvalBudgetMargin margin = EvalBudgetMargin.full(100);

        assertEquals(0, margin.remainingBeforeHardCap());
        assertTrue(margin.withinHardCap());
        assertFalse("达到 100 次不得再调用", margin.canCall());
        assertEquals("预算 79，已用 100，超计划 21（距硬顶 0）", margin.summary());
    }

    @Test
    public void d19UsedAboveHardCapShouldBeRejectedAsInvalidLedger() {
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                EvalBudgetMargin.full(101);
            }
        }, "已用不能超过硬顶 100");
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                EvalBudgetMargin.full(-1);
            }
        }, "已用不能为负");
    }

    @Test
    public void d19PlannedAboveHardCapShouldBeRejected() {
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                EvalBudgetMargin.of(0, 101, 100);
            }
        }, "planned 不得超过硬顶");
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                EvalBudgetMargin.of(0, 79, 0);
            }
        }, "硬顶必须为正");
    }

    private static void assertIllegalArgument(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
