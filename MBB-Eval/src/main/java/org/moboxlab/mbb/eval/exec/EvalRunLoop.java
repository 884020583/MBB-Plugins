package org.moboxlab.mbb.eval.exec;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.moboxlab.mbb.eval.call.EvalBudgetLedger;
import org.moboxlab.mbb.eval.call.EvalCallInvoker;
import org.moboxlab.mbb.eval.call.EvalCallPolicy;
import org.moboxlab.mbb.eval.call.EvalCallRecorder;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.report.EvalReportData;
import org.moboxlab.mbb.eval.rubric.EvalRubric;
import org.moboxlab.mbb.eval.runner.EvalRunBudget;
import org.moboxlab.mbb.eval.runner.EvalRunStopPoint;
import org.moboxlab.mbb.eval.score.EvalCaseScore;
import org.moboxlab.mbb.eval.score.EvalRunScore;
import org.moboxlab.mbb.eval.score.EvalScoreAggregator;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * run 主循环（规格 §9.4、§9.5、§9.6）：按档位取题库子集，逐题构造 {@link EvalCaseContext}
 * 交给 {@link EvalCaseExecutor}，在每题前后检查停止点、预算与墙钟，最后聚合维度分并组装报表数据。
 *
 * <p>本类不写终态：COMPLETED / ABORTED 的 CAS 仍由 {@code EvalRunner} 统一完成，避免出现第二个
 * 终态写入口（§4.4、§13.4）。单题执行内部的停止点检查、预算记账与 case_result 落行都在
 * {@link EvalCaseExecutor} 内完成，本类只负责题间控制与结果汇聚。</p>
 */
public final class EvalRunLoop {

    /** 主循环的收尾状态。 */
    public enum Status {
        /** 全部题目执行完毕。 */
        COMPLETED,
        /** 被停止点、预算、墙钟、AI 不可用或用户中止打断。 */
        ABORTED
    }

    /** 单题默认采样次数（§6.2：persona/boundary/memory 各采样 3 次取中位数）。 */
    private static final int DEFAULT_SAMPLE_COUNT = 3;
    /** 摘要文本的最大行数（§16.7：回执摘要不超过 15 行）。 */
    private static final int SUMMARY_MAX_LINES = 15;

    /** 主循环依赖；全部在插件启用时一次性装配，运行期不热重载。 */
    public static final class Deps {
        private EvalStore store;
        private EvalConfig config;
        private JSONObject bank;
        private EvalRubric rubric;
        private EvalCaseGeneratePort generatePort;
        private EvalCaseJudgePort judgePort;
        private File reportsDirectory;
        private PluginLogger logger;
        private EvalCaseClock clock;

        public Deps store(EvalStore store) {
            this.store = store;
            return this;
        }

        public Deps config(EvalConfig config) {
            this.config = config;
            return this;
        }

        public Deps bank(JSONObject bank) {
            this.bank = bank;
            return this;
        }

        public Deps rubric(EvalRubric rubric) {
            this.rubric = rubric;
            return this;
        }

        public Deps generatePort(EvalCaseGeneratePort generatePort) {
            this.generatePort = generatePort;
            return this;
        }

        public Deps judgePort(EvalCaseJudgePort judgePort) {
            this.judgePort = judgePort;
            return this;
        }

        public Deps reportsDirectory(File reportsDirectory) {
            this.reportsDirectory = reportsDirectory;
            return this;
        }

        public Deps logger(PluginLogger logger) {
            this.logger = logger;
            return this;
        }

        public Deps clock(EvalCaseClock clock) {
            this.clock = clock;
            return this;
        }

        public EvalStore store() {
            return store;
        }

        public EvalConfig config() {
            return config;
        }

        public JSONObject bank() {
            return bank;
        }

        public EvalRubric rubric() {
            return rubric;
        }

        public EvalCaseGeneratePort generatePort() {
            return generatePort;
        }

        public EvalCaseJudgePort judgePort() {
            return judgePort;
        }

        public File reportsDirectory() {
            return reportsDirectory;
        }

        public PluginLogger logger() {
            return logger;
        }

        public EvalCaseClock clock() {
            return clock;
        }
    }

    /** 一次主循环的请求：run 行为与运行期控制对象全部来自 EvalRunner。 */
    public static final class Request {
        private final String runId;
        private final String tier;
        private final EvalRunBudget budget;
        private final EvalRunStopPoint stopPoint;

        private Request(String runId, String tier, EvalRunBudget budget, EvalRunStopPoint stopPoint) {
            this.runId = runId;
            this.tier = tier;
            this.budget = budget;
            this.stopPoint = stopPoint;
        }

        public static Request of(String runId, String tier, EvalRunBudget budget,
                                 EvalRunStopPoint stopPoint) {
            return new Request(runId, tier, budget, stopPoint);
        }

        public String runId() {
            return runId;
        }

        public String tier() {
            return tier;
        }

        public EvalRunBudget budget() {
            return budget;
        }

        public EvalRunStopPoint stopPoint() {
            return stopPoint;
        }
    }

    /** 主循环结果：终态建议、聚合分、报表数据入口与 ≤15 行摘要。 */
    public static final class Result {
        private final String runId;
        private final Status status;
        private final String abortReason;
        private final boolean terminalInStore;
        private final EvalRunScore score;
        private final int plannedCases;
        private final int executedCases;
        private final int callsUsed;
        private final List<String> summaryLines;

        private Result(String runId, Status status, String abortReason, boolean terminalInStore,
                       EvalRunScore score, int plannedCases, int executedCases, int callsUsed,
                       List<String> summaryLines) {
            this.runId = runId;
            this.status = status;
            this.abortReason = abortReason;
            this.terminalInStore = terminalInStore;
            this.score = score;
            this.plannedCases = plannedCases;
            this.executedCases = executedCases;
            this.callsUsed = callsUsed;
            this.summaryLines = summaryLines;
        }

        public String runId() {
            return runId;
        }

        public Status status() {
            return status;
        }

        /** 中止原因码；COMPLETED 时为 null。 */
        public String abortReason() {
            return abortReason;
        }

        /** true 表示数据库里该 run 已被其他路径收口：runner 只释放内存，不再发终态 CAS。 */
        public boolean terminalInStore() {
            return terminalInStore;
        }

        /** 聚合分；没有任何可计分题目时为 null（不是 0 分）。 */
        public EvalRunScore score() {
            return score;
        }

        public int plannedCases() {
            return plannedCases;
        }

        public int executedCases() {
            return executedCases;
        }

        public int callsUsed() {
            return callsUsed;
        }

        public List<String> summaryLines() {
            return summaryLines;
        }

        /** 回执摘要文本，最多 15 行。 */
        public String summaryText() {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < summaryLines.size(); i++) {
                if (i > 0) {
                    builder.append('\n');
                }
                builder.append(summaryLines.get(i));
            }
            return builder.toString();
        }

        /**
         * 报表数据：由数据库现行状态现算，因此在终态 CAS 之后调用才能读到 COMPLETED。
         * run 行不存在或装配失败时抛出 RuntimeException，由 runner 记 WARN。
         */
        public EvalReportData reportData(EvalStore store) {
            return EvalReportData.assemble(store, runId);
        }
    }

    /** 题间停止信号。 */
    private static final class StopSignal {
        private final String reason;
        private final boolean terminalInStore;

        private StopSignal(String reason, boolean terminalInStore) {
            this.reason = reason;
            this.terminalInStore = terminalInStore;
        }
    }

    private final Deps deps;

    public EvalRunLoop(Deps deps) {
        if (deps == null) {
            throw new IllegalArgumentException("主循环依赖不能为空");
        }
        if (deps.store == null) {
            throw new IllegalArgumentException("主循环依赖缺少 EvalStore");
        }
        if (deps.bank == null || deps.bank.getJSONArray("cases") == null) {
            throw new IllegalArgumentException("主循环依赖缺少题库用例（bank.cases）");
        }
        if (deps.rubric == null) {
            throw new IllegalArgumentException("主循环依赖缺少 rubric");
        }
        if (deps.generatePort == null || deps.judgePort == null) {
            throw new IllegalArgumentException("主循环依赖缺少生成/判分端口");
        }
        this.deps = deps;
    }

    /** 报表输出目录；未装配时为 null。 */
    public File reportsDirectory() {
        return deps.reportsDirectory;
    }

    /**
     * 执行一次 run 的主循环。
     *
     * <p>不写终态、不写报表文件：COMPLETED 的 CAS 与报表落盘由 runner 在循环返回后完成，
     * 保证「先收口再生成报表」的顺序，报表里的 run 状态因此是 COMPLETED。</p>
     */
    public Result run(Request request) {
        validate(request);
        List<JSONObject> selected = selectCases(request.tier());
        EvalRunBudget budget = request.budget();
        EvalRunStopPoint stopPoint = request.stopPoint();
        EvalCaseClock clock = clock();
        EvalBudgetLedger ledger = new EvalBudgetLedger(request.runId(), budget, deps.store, deps.logger);
        EvalCallInvoker invoker = new EvalCallInvoker(ledger,
                new EvalCallRecorder(deps.store, deps.logger), policy());
        EvalCaseExecutor executor = new EvalCaseExecutor();

        List<EvalCaseScore> scores = new ArrayList<EvalCaseScore>();
        String abortReason = null;
        boolean terminalInStore = false;
        int executed = 0;

        for (JSONObject caseJson : selected) {
            StopSignal signal = checkStop(stopPoint, budget, clock);
            if (signal != null) {
                abortReason = signal.reason;
                terminalInStore = signal.terminalInStore;
                break;
            }
            EvalCaseResult result;
            try {
                result = executor.execute(context(request, caseJson, ledger, invoker, clock));
            } catch (RuntimeException e) {
                warn("单题执行异常，run_id=" + request.runId() + "，题号=" + caseJson.getString("id")
                        + "：" + e.getMessage());
                abortReason = "case-error";
                break;
            }
            if (result != null) {
                if (result.status() != null) {
                    executed++;
                    scores.add(EvalCaseScore.of(result.caseId(), result.dimension(), result.status(),
                            result.caseScore()));
                }
                if (result.abortRun()) {
                    if (stopPoint != null && stopPoint.terminalInStore()) {
                        terminalInStore = true;
                        abortReason = null;
                    } else {
                        abortReason = normalize(result.abortReason());
                    }
                    break;
                }
            }
            signal = checkStop(stopPoint, budget, clock);
            if (signal != null) {
                abortReason = signal.reason;
                terminalInStore = signal.terminalInStore;
                break;
            }
        }

        Evaluation evaluation = evaluate(scores);
        boolean completed = abortReason == null && !terminalInStore;
        List<String> summary = summary(request, selected.size(), executed, budget, evaluation,
                completed, abortReason);
        return new Result(request.runId(), completed ? Status.COMPLETED : Status.ABORTED,
                completed ? null : (abortReason == null ? "aborted" : abortReason), terminalInStore,
                evaluation == null ? null : evaluation.score, selected.size(), executed,
                budget.used(), summary);
    }

    private void validate(Request request) {
        if (request == null) {
            throw new IllegalArgumentException("主循环请求不能为空");
        }
        if (request.runId() == null || request.runId().trim().isEmpty()) {
            throw new IllegalArgumentException("run_id 不能为空");
        }
        if (!EvalStore.TIER_SMOKE.equals(request.tier()) && !EvalStore.TIER_FULL.equals(request.tier())) {
            throw new IllegalArgumentException("档位只能是 smoke 或 full：" + request.tier());
        }
        if (request.budget() == null || request.stopPoint() == null) {
            throw new IllegalArgumentException("主循环请求缺少预算或停止点");
        }
    }

    private List<JSONObject> selectCases(String tier) {
        JSONArray cases = deps.bank.getJSONArray("cases");
        List<JSONObject> selected = new ArrayList<JSONObject>();
        boolean full = EvalStore.TIER_FULL.equals(tier);
        for (int i = 0; i < cases.size(); i++) {
            JSONObject caseJson = cases.getJSONObject(i);
            if (caseJson == null) {
                continue;
            }
            if (full || EvalStore.TIER_SMOKE.equals(caseJson.getString("tier"))) {
                selected.add(caseJson);
            }
        }
        return selected;
    }

    private EvalCaseContext context(Request request, JSONObject caseJson, EvalBudgetLedger ledger,
                                    EvalCallInvoker invoker, EvalCaseClock clock) {
        EvalConfig config = deps.config;
        return EvalCaseContext.builder(request.runId(), caseJson.getString("id"))
                .tier(request.tier())
                .dimension(caseJson.getString("dimension"))
                .caseJson(caseJson)
                .rubric(deps.rubric)
                .ledger(ledger)
                .invoker(invoker)
                .store(deps.store)
                .generatePort(deps.generatePort)
                .judgePort(deps.judgePort)
                .clock(clock)
                .logger(deps.logger)
                .stopPoint(request.stopPoint())
                .sampleCount(DEFAULT_SAMPLE_COUNT)
                .personaSummary(personaSummary())
                .generateProfile(profile(config))
                .generateTimeoutSeconds(config == null ? 60 : config.timeoutSecond())
                .build();
    }

    private String profile(EvalConfig config) {
        if (config == null) {
            return "eval";
        }
        String profile = config.profile();
        return profile == null || profile.trim().isEmpty() ? "eval" : profile;
    }

    private String personaSummary() {
        JSONObject persona = deps.bank.getJSONObject("persona");
        String summary = persona == null ? null : persona.getString("summary");
        return summary == null ? "" : summary;
    }

    /**
     * 题间检查顺序：停止点（内存中止与数据库终态）→ 预算 → 墙钟。
     * 墙钟命中时把中止原因同步进预算，保证终态写的是 deadline 而不是 budget。
     */
    private StopSignal checkStop(EvalRunStopPoint stopPoint, EvalRunBudget budget, EvalCaseClock clock) {
        EvalRunStopPoint.Decision decision = stopPoint.check();
        if (decision.stop()) {
            return new StopSignal(decision.reason(),
                    decision.kind() == EvalRunStopPoint.StopKind.TERMINAL_IN_STORE);
        }
        if (budget.isAborted()) {
            return new StopSignal(normalize(budget.abortReason()), false);
        }
        if (budget.deadlineExceeded(clock.currentTimeMillis())) {
            budget.abort(EvalStore.ABORT_REASON_DEADLINE);
            return new StopSignal(EvalStore.ABORT_REASON_DEADLINE, false);
        }
        return null;
    }

    private EvalCallPolicy policy() {
        if (deps.config == null) {
            return new EvalCallPolicy(1, 3, new int[]{0, 0});
        }
        return EvalCallPolicy.fromConfig(deps.config);
    }

    private EvalCaseClock clock() {
        if (deps.clock != null) {
            return deps.clock;
        }
        return new EvalCaseClock() {
            @Override
            public long currentTimeMillis() {
                return System.currentTimeMillis();
            }
        };
    }

    /** 聚合结果；空题集时为 null（缺失维度不能折算成 0 分）。 */
    private Evaluation evaluate(List<EvalCaseScore> scores) {
        if (scores.isEmpty()) {
            return null;
        }
        EvalRunScore score = new EvalScoreAggregator().aggregate(scores, deps.rubric);
        return new Evaluation(score);
    }

    private List<String> summary(Request request, int plannedCases, int executed, EvalRunBudget budget,
                                 Evaluation evaluation, boolean completed, String abortReason) {
        List<String> lines = new ArrayList<String>();
        lines.add("档位 " + request.tier() + "：计划 " + plannedCases + " 题，执行 " + executed + " 题");
        lines.add("调用 " + budget.used() + "/" + budget.limit() + "（预检计划 " + budget.planned() + "）");
        lines.add(completed ? "状态：COMPLETED" : "状态：ABORTED("
                + (abortReason == null ? "aborted" : abortReason) + ")");
        if (evaluation != null && evaluation.score != null && evaluation.score.total() != null) {
            lines.add("总分 " + format(evaluation.score.total().doubleValue()));
        } else {
            lines.add("总分：无可计分题目（不折算为 0 分）");
        }
        if (evaluation != null && evaluation.score != null) {
            StringBuilder dimensions = new StringBuilder("维度：");
            for (org.moboxlab.mbb.eval.score.EvalDimensionScore dimension : evaluation.score.dimensions()) {
                if (dimension.missing()) {
                    continue;
                }
                if (dimensions.length() > 3) {
                    dimensions.append(" / ");
                }
                dimensions.append(dimension.dimensionId()).append(' ')
                        .append(format(dimension.score().doubleValue()));
            }
            lines.add(dimensions.toString());
            lines.add("有效题 " + evaluation.score.validCaseCount() + "/" + evaluation.score.caseCount()
                    + "，非 ok " + evaluation.score.errorCaseIds().size()
                    + "，错误 " + evaluation.score.invalidCaseIds().size());
            lines.add("可信度：" + (evaluation.score.credibility().credible() ? "可信" : "不可信"));
            for (String annotation : evaluation.score.credibility().annotations()) {
                lines.add(annotation);
            }
        }
        if (lines.size() > SUMMARY_MAX_LINES) {
            return Collections.unmodifiableList(new ArrayList<String>(
                    lines.subList(0, SUMMARY_MAX_LINES)));
        }
        return Collections.unmodifiableList(lines);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(value));
    }

    private static String normalize(String reason) {
        return reason == null || reason.trim().isEmpty() ? "aborted" : reason;
    }

    private void warn(String message) {
        if (deps.logger != null) {
            deps.logger.sendWarn("[MBB-Eval] " + message);
        }
    }

    /** 聚合结果包装，避免空题集时构造无意义的 EvalRunScore。 */
    private static final class Evaluation {
        private final EvalRunScore score;

        private Evaluation(EvalRunScore score) {
            this.score = score;
        }
    }
}
