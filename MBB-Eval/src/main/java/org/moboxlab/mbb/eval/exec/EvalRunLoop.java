package org.moboxlab.mbb.eval.exec;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.moboxlab.mbb.eval.call.EvalBudgetLedger;
import org.moboxlab.mbb.eval.call.EvalCallInvoker;
import org.moboxlab.mbb.eval.call.EvalCallPolicy;
import org.moboxlab.mbb.eval.call.EvalCallRecorder;
import org.moboxlab.mbb.eval.calib.EvalCalibrationJudgePort;
import org.moboxlab.mbb.eval.calib.EvalCalibrationResult;
import org.moboxlab.mbb.eval.calib.EvalCalibrationRunner;
import org.moboxlab.mbb.eval.calib.EvalCalibrationSample;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;
import org.moboxlab.mbb.eval.judge.EvalJudgeResult;
import org.moboxlab.mbb.eval.log.EvalLog;
import org.moboxlab.mbb.eval.metrics.EvalAiUsageSource;
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
        /** 校准自证判分端口（§6.7.4）；null 表示未装配，首次基线 run 跳过校准并记 WARN。 */
        private EvalCalibrationJudgePort calibrationJudgePort;
        private File reportsDirectory;
        private PluginLogger logger;
        private EvalCaseClock clock;
        private Map<String, Double> reportWeights;
        private EvalAiUsageSource aiUsageSource;

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

        public Deps calibrationJudgePort(EvalCalibrationJudgePort calibrationJudgePort) {
            this.calibrationJudgePort = calibrationJudgePort;
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

        /** 报表 delta 用的五维权重（§6.1）；null 表示用报表内置缺省权重。 */
        public Deps reportWeights(Map<String, Double> reportWeights) {
            this.reportWeights = reportWeights;
            return this;
        }

        /** 报表 §6 L3 用量旁证来源（§7.5）；null 表示不采集旁证。 */
        public Deps aiUsageSource(EvalAiUsageSource aiUsageSource) {
            this.aiUsageSource = aiUsageSource;
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

        /** 校准自证判分端口；null 表示未装配。 */
        public EvalCalibrationJudgePort calibrationJudgePort() {
            return calibrationJudgePort;
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

        public Map<String, Double> reportWeights() {
            return reportWeights;
        }

        public EvalAiUsageSource aiUsageSource() {
            return aiUsageSource;
        }
    }

    /** 一次主循环的请求：run 行为与运行期控制对象全部来自 EvalRunner。 */
    public static final class Request {
        private final String runId;
        private final String tier;
        private final EvalRunBudget budget;
        private final EvalRunStopPoint stopPoint;
        /** 是否为首次基线 run：true 时在正式逐题执行前跑 3 条校准自证（§6.7.4）。 */
        private final boolean firstBaselineRun;

        private Request(String runId, String tier, EvalRunBudget budget, EvalRunStopPoint stopPoint,
                        boolean firstBaselineRun) {
            this.runId = runId;
            this.tier = tier;
            this.budget = budget;
            this.stopPoint = stopPoint;
            this.firstBaselineRun = firstBaselineRun;
        }

        /** 旧签名：不执行校准自证（firstBaselineRun=false），保持既有调用行为不变。 */
        public static Request of(String runId, String tier, EvalRunBudget budget,
                                 EvalRunStopPoint stopPoint) {
            return new Request(runId, tier, budget, stopPoint, false);
        }

        /** 带校准标志的入口：只有首次基线 full run 才应传 true。 */
        public static Request of(String runId, String tier, EvalRunBudget budget,
                                 EvalRunStopPoint stopPoint, boolean firstBaselineRun) {
            return new Request(runId, tier, budget, stopPoint, firstBaselineRun);
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

        /** 是否为首次基线 run；true 才执行校准自证。 */
        public boolean firstBaselineRun() {
            return firstBaselineRun;
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
        private final Map<String, Double> reportWeights;
        private final EvalAiUsageSource aiUsageSource;
        /** 校准自证 JSON；null 表示本次 run 未执行校准或校准未产出结果。 */
        private final String calibrationJson;

        private Result(String runId, Status status, String abortReason, boolean terminalInStore,
                       EvalRunScore score, int plannedCases, int executedCases, int callsUsed,
                       List<String> summaryLines, Map<String, Double> reportWeights,
                       EvalAiUsageSource aiUsageSource, String calibrationJson) {
            this.runId = runId;
            this.status = status;
            this.abortReason = abortReason;
            this.terminalInStore = terminalInStore;
            this.score = score;
            this.plannedCases = plannedCases;
            this.executedCases = executedCases;
            this.callsUsed = callsUsed;
            this.summaryLines = summaryLines;
            this.reportWeights = reportWeights;
            this.aiUsageSource = aiUsageSource;
            this.calibrationJson = calibrationJson;
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

        /** 校准自证 JSON；null 表示未执行或未产出，由 runner 决定是否写 run 行。 */
        public String calibrationJson() {
            return calibrationJson;
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
            // 权重来自 rubric（§6.1），L3 旁证由 storage 数据源提供（§7.5）；两者缺省时仍可出报表。
            return EvalReportData.assemble(store, runId, reportWeights, aiUsageSource);
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

        // 校准自证在正式逐题执行之前完成（§6.7.4）：每条样本各 1 次 judge，计入同一预算并落
        // stage=calib 行；校准失败只写 WARN 与 calibration_json，不改变 run 的走向。
        CalibrationOutput calibrationOutput = null;
        if (request.firstBaselineRun() && EvalStore.TIER_FULL.equals(request.tier())) {
            StopSignal beforeCalibration = checkStop(stopPoint, budget, clock);
            if (beforeCalibration == null) {
                calibrationOutput = calibrate(request, invoker);
            } else {
                warn("校准自证未开始：run 在正式执行前已满足停止条件，run_id=" + request.runId());
            }
        }

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
                EvalLog.error(deps.logger, "[MBB-Eval] 单题执行异常，run_id=" + request.runId()
                        + "，题号=" + caseJson.getString("id") + "：" + e.getMessage());
                // 单题异常不发明中止原因（§13.1.3 原因集合封闭）：记入 error 题分后继续下一题。
                String caseId = caseJson.getString("id");
                String dimension = caseJson.getString("dimension");
                if (caseId != null && !caseId.trim().isEmpty()
                        && dimension != null && !dimension.trim().isEmpty()) {
                    executed++;
                    scores.add(EvalCaseScore.error(caseId, dimension));
                }
                continue;
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
        String calibrationJson = calibrationOutput == null
                ? null : renderCalibrationJson(calibrationOutput, request, selected);
        return new Result(request.runId(), completed ? Status.COMPLETED : Status.ABORTED,
                completed || terminalInStore ? null : normalize(abortReason), terminalInStore,
                evaluation == null ? null : evaluation.score, selected.size(), executed,
                budget.used(), summary, deps.reportWeights(), deps.aiUsageSource(), calibrationJson);
    }

    /**
     * 执行 3 条校准样本，返回可写入 run.calibration_json 的文本。
     *
     * <p>每条样本通过 {@link EvalCallInvoker#judgeCalibration} 发起：预算 +1、落 stage=calib 行，
     * 与正式判分共用同一本账（§6.8.1）。预算 STOP、熔断、判分失败或预算端口异常都只中止
     * 后续样本，不向主流程抛出；异常兜底也返回失败 JSON，保证 calibration_json 有据可查。</p>
     */
    private CalibrationOutput calibrate(final Request request, final EvalCallInvoker invoker) {
        final EvalCalibrationJudgePort port = deps.calibrationJudgePort;
        if (port == null) {
            warn("未装配校准判分端口，跳过校准自证，run_id=" + request.runId());
            return null;
        }
        final boolean[] stopped = new boolean[]{false};
        final String[] stopReason = new String[]{null};
        EvalCalibrationJudgePort wired = new EvalCalibrationJudgePort() {
            @Override
            public String boundaryVeto(String reply, String personaSummary) {
                return port.boundaryVeto(reply, personaSummary);
            }

            @Override
            public String personaFragment() {
                return port.personaFragment();
            }

            @Override
            public String personaSummary() {
                return port.personaSummary();
            }

            @Override
            public EvalJudgeResult judge(final EvalCalibrationSample sample) {
                EvalCallInvoker.JudgeOutcome outcome = invoker.judgeCalibration(request.runId(),
                        sample.caseId(), 0, 0, new EvalCallInvoker.JudgeCall() {
                            @Override
                            public EvalJudgeResult call() {
                                return port.judge(request.runId(), sample);
                            }
                        });
                if (outcome.isBudgetStopped()) {
                    stopped[0] = true;
                    stopReason[0] = "预算已触顶，校准自证中止（" + normalize(outcome.abortReason()) + "）";
                    return EvalJudgeResult.error(sample.dimensionId(), EvalJudgeResult.KIND_TRANSPORT,
                            "budget", false, 0, null, stopReason[0], null, 0);
                }
                EvalJudgeResult result = outcome.result();
                if (result == null) {
                    stopped[0] = true;
                    stopReason[0] = "校准样本 " + sample.caseId() + " 判分未返回结果";
                    return EvalJudgeResult.error(sample.dimensionId(), EvalJudgeResult.KIND_TRANSPORT,
                            EvalGenerateResult.ERROR_INTERNAL, false, 0, null, stopReason[0], null, 0);
                }
                if (!result.isOk()) {
                    stopped[0] = true;
                    stopReason[0] = "校准样本 " + sample.caseId() + " 判分失败："
                            + (result.isInvalid() ? result.errorKind() : result.errorType());
                }
                return result;
            }
        };
        try {
            EvalCalibrationResult calibration = new EvalCalibrationRunner(wired, deps.logger)
                    .run(request.runId(), new EvalCalibrationRunner.StopCondition() {
                        @Override
                        public boolean stop() {
                            return stopped[0];
                        }
                    });
            if (stopped[0]) {
                warn("校准自证提前中止，run_id=" + request.runId() + "，原因=" + stopReason[0]
                        + "，已执行样本=" + calibration.samples().size());
            }
            return new CalibrationOutput(calibration, null, stopped[0], stopReason[0],
                    calibration.samples().size());
        } catch (RuntimeException e) {
            warn("校准自证异常，run_id=" + request.runId() + "：" + e.getMessage());
            return new CalibrationOutput(null, calibrationFailureJson(request.runId(),
                    "校准自证异常：" + e.getClass().getSimpleName()), true, null, 0);
        }
    }

    /** 校准过程自身异常时的兜底 JSON：保留三项门槛键，明确标记未通过。 */
    private static String calibrationFailureJson(String runId, String message) {
        JSONObject json = new JSONObject(true);
        json.put("runId", runId);
        json.put("passed", Boolean.FALSE);
        json.put("personaPassed", Boolean.FALSE);
        json.put("memoryPassed", Boolean.FALSE);
        json.put("boundaryPassed", Boolean.FALSE);
        json.put("boundaryVetoed", Boolean.FALSE);
        json.put("aborted", Boolean.TRUE);
        json.put("abortReason", message);
        json.put("samples", new JSONArray());
        return json.toJSONString();
    }

    /** 把校准结果与边界抖动结论合并成 calibration_json；异常兜底直接返回失败 JSON。 */
    private String renderCalibrationJson(CalibrationOutput output, Request request,
            List<JSONObject> selected) {
        if (output.result == null) {
            return output.failureJson;
        }
        EvalCalibrationResult calibration = output.result;
        BoundaryJitter jitter = boundaryJitter(request.runId(), selected);
        if (jitter.validCases > 0) {
            calibration = calibration.withBoundaryJitter(jitter.warning, jitter.sameRate,
                    jitter.flippedCaseIds);
            if (jitter.warning) {
                warn("边界抖动告警：k=3 全同率 " + percent(jitter.sameRate) + " 低于 70%，翻转题="
                        + jitter.flippedCaseIds);
            }
        }
        JSONObject json = calibration.toJson();
        json.put("aborted", Boolean.valueOf(output.aborted));
        json.put("executedSamples", Integer.valueOf(output.executedSamples));
        if (output.aborted) {
            json.put("abortReason", output.abortReason == null ? "校准自证提前中止" : output.abortReason);
        }
        return json.toJSONString();
    }

    /**
     * 统计边界题 k=3 的 label 全同率（§6.7.1、§6.7.4）。
     *
     * <p>只统计样本全部 ok 的边界题；非 ok 的题无法判断抖动，不计入分母。翻转题清单最多 10 条。</p>
     */
    private BoundaryJitter boundaryJitter(String runId, List<JSONObject> selected) {
        List<String> flipped = new ArrayList<String>();
        int validCases = 0;
        int sameCases = 0;
        if (deps.store != null) {
            for (JSONObject caseJson : selected) {
                if (!EvalCaseExecutor.DIM_BOUNDARY.equals(caseJson.getString("dimension"))) {
                    continue;
                }
                String caseId = caseJson.getString("id");
                if (caseId == null || caseId.trim().isEmpty()) {
                    continue;
                }
                JSONObject row = deps.store.findCaseResult(runId, caseId);
                if (row == null) {
                    continue;
                }
                JSONArray samples = deps.store.parseDetailJson(row).getJSONArray("samples");
                if (samples == null || samples.size() < 2) {
                    continue;
                }
                List<Integer> labels = new ArrayList<Integer>();
                boolean allOk = true;
                for (int i = 0; i < samples.size(); i++) {
                    JSONObject sample = samples.getJSONObject(i);
                    Integer label = sample == null ? null : sample.getInteger("label");
                    if (sample == null || !EvalCalibrationResult.STATE_OK.equals(sample.getString("status"))
                            || label == null) {
                        allOk = false;
                        break;
                    }
                    labels.add(label);
                }
                if (!allOk || labels.isEmpty()) {
                    continue;
                }
                validCases++;
                int first = labels.get(0).intValue();
                boolean same = true;
                for (Integer label : labels) {
                    if (label.intValue() != first) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    sameCases++;
                } else if (flipped.size() < 10) {
                    flipped.add(caseId);
                }
            }
        }
        double sameRate = validCases == 0 ? 1.0d : (double) sameCases / (double) validCases;
        boolean warning = validCases > 0 && sameRate < 0.70d;
        return new BoundaryJitter(validCases, sameRate, warning, flipped);
    }

    private static String percent(double rate) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", rate * 100.0d);
    }

    /** 校准执行的中间结果：保留结果对象以便补写边界抖动，同时记住中止元数据。 */
    private static final class CalibrationOutput {
        private final EvalCalibrationResult result;
        private final String failureJson;
        private final boolean aborted;
        private final String abortReason;
        private final int executedSamples;

        CalibrationOutput(EvalCalibrationResult result, String failureJson, boolean aborted,
                String abortReason, int executedSamples) {
            this.result = result;
            this.failureJson = failureJson;
            this.aborted = aborted;
            this.abortReason = abortReason;
            this.executedSamples = executedSamples;
        }
    }

    /** 边界抖动统计：有效题数、全同率、是否告警与翻转题清单。 */
    private static final class BoundaryJitter {
        private final int validCases;
        private final double sameRate;
        private final boolean warning;
        private final List<String> flippedCaseIds;

        BoundaryJitter(int validCases, double sameRate, boolean warning, List<String> flippedCaseIds) {
            this.validCases = validCases;
            this.sameRate = sameRate;
            this.warning = warning;
            this.flippedCaseIds = flippedCaseIds;
        }
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
                .sampleCount(sampleCountOf(caseJson.getString("dimension")))
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
        lines.add(completed ? "状态：COMPLETED"
                : (abortReason == null ? "状态：ABORTED" : "状态：ABORTED(" + abortReason + ")"));
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

    /**
     * 把中止原因收敛到 §13.1.3 的封闭集合；缺失或越界一律按 ai-unavailable 记录并 WARN。
     */
    private String normalize(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return EvalStore.ABORT_REASON_AI_UNAVAILABLE;
        }
        if (EvalStore.ABORT_REASON_USER.equals(reason) || EvalStore.ABORT_REASON_BUDGET.equals(reason)
                || EvalStore.ABORT_REASON_DEADLINE.equals(reason)
                || EvalStore.ABORT_REASON_AI_UNAVAILABLE.equals(reason)
                || EvalStore.ABORT_REASON_ORPHAN.equals(reason)
                || EvalStore.ABORT_REASON_SHUTDOWN.equals(reason)) {
            return reason;
        }
        warn("中止原因不在封闭集合内（" + reason + "），按 " + EvalStore.ABORT_REASON_AI_UNAVAILABLE + " 记录");
        return EvalStore.ABORT_REASON_AI_UNAVAILABLE;
    }

    /** 各维度的采样次数（§6.7.1）：只有 boundary 采三次，其余维度一次。 */
    private static int sampleCountOf(String dimension) {
        return "boundary".equals(dimension) ? EvalCaseContext.DEFAULT_SAMPLE_COUNT : 1;
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
