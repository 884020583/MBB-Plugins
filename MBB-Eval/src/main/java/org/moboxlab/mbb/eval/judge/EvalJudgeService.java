package org.moboxlab.mbb.eval.judge;

import org.moboxlab.mbb.eval.judge.EvalJudgeFailureTracker.Decision;
import org.moboxlab.mbb.eval.log.EvalLog;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * 判分服务：串起判分失败分类、连续失败熔断与中文日志（规格 §13.1.4、§13.3，§15.5 H7 / H8）。
 *
 * <p>单题解析失败由 {@link EvalJudge} 按原 prompt 重试一次，仍失败则返回 invalid；
 * 本服务把它记入单列、剔除分母，不影响其他题，也不升级为 run 中止。只有传输失败才计入
 * 连续失败，达到阈值先退避一次，退避后仍连续同次数失败才对外给出
 * {@code ai-unavailable}，此后不再发起判分调用。runner 接线由 Lead 负责。</p>
 */
public final class EvalJudgeService {

    private final EvalJudge judge;
    private final EvalJudgeFailureTracker tracker;
    private final PluginLogger logger;

    public EvalJudgeService(EvalJudge judge, EvalJudgeFailureTracker tracker) {
        this(judge, tracker, null);
    }

    public EvalJudgeService(EvalJudge judge, EvalJudgeFailureTracker tracker, PluginLogger logger) {
        if (judge == null) {
            throw new IllegalArgumentException("judge 不能为空");
        }
        if (tracker == null) {
            throw new IllegalArgumentException("tracker 不能为空");
        }
        this.judge = judge;
        this.tracker = tracker;
        this.logger = logger;
    }

    /** 执行一次判分；已熔断时短路，不发起调用。 */
    public EvalJudgeOutcome evaluate(EvalJudgeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        if (tracker.isAborted()) {
            EvalLog.judgeWarn(logger, "已判定 AI 不可用，跳过判分 case=" + request.caseId()
                    + " 维度=" + request.dimensionId());
            return EvalJudgeOutcome.skipped(tracker);
        }
        return accept(judge.judge(request));
    }

    /** 接受一次已完成的判分结果，按结果计数并给出处置。 */
    public EvalJudgeOutcome accept(EvalJudgeResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result 不能为空");
        }
        boolean wasAborted = tracker.isAborted();
        // 已中止时无论结果如何都保持中止；熔断状态粘滞，不能因一题 invalid 被误判为可继续。
        Decision decision = wasAborted ? Decision.ABORT : tracker.record(result);

        if (result.isInvalid()) {
            EvalLog.judgeWarn(logger, "判分无效 维度=" + result.dimensionId()
                    + " 原因=" + result.errorKind() + " 已重试并剔除分母");
        } else if (result.isError()) {
            EvalLog.judgeWarn(logger, "判分失败 维度=" + result.dimensionId()
                    + " 错误类=" + result.errorKind() + " 类型=" + result.errorType()
                    + " 连续失败=" + tracker.streak());
        }
        if (!wasAborted && decision == Decision.BACKOFF) {
            EvalLog.judgeWarn(logger, "连续失败达到 " + tracker.failStreakAbort()
                    + " 次，退避 " + tracker.backoffSeconds() + " 秒后继续");
        } else if (!wasAborted && decision == Decision.ABORT) {
            EvalLog.judgeError(logger, "退避后仍连续失败 " + tracker.failStreakAbort()
                    + " 次，判定 AI 不可用，run 将中止（" + tracker.abortReason() + "）");
        }
        return EvalJudgeOutcome.of(result, decision, tracker);
    }

    public boolean runAborted() {
        return tracker.isAborted();
    }

    public EvalJudgeFailureTracker tracker() {
        return tracker;
    }
}
