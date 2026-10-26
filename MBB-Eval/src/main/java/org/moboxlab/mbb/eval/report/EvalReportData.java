package org.moboxlab.mbb.eval.report;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.mbb.eval.baseline.EvalDeltaResult;
import org.moboxlab.mbb.eval.baseline.EvalDeltaService;
import org.moboxlab.mbb.eval.metrics.EvalAiUsageEvidence;
import org.moboxlab.mbb.eval.metrics.EvalAiUsageEvidenceService;
import org.moboxlab.mbb.eval.metrics.EvalAiUsageSource;
import org.moboxlab.mbb.eval.metrics.EvalMetrics;
import org.moboxlab.mbb.eval.log.EvalLog;
import org.moboxlab.mbb.eval.metrics.EvalMetricsService;
import org.moboxlab.moboxbot.API.PluginLogger;
import org.moboxlab.mbb.eval.score.EvalDimensionWeights;
import org.moboxlab.mbb.eval.score.EvalScoreAggregator;
import org.moboxlab.mbb.eval.store.EvalStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表数据装配（F-4：规格 §8.3 九节、§8.4 对比口径、§8.5 可信度）。
 *
 * <p>一次 {@code /eval report <run_id>} 只装配一次：run 行、case 行、call 行、指标、九键、
 * 最近 {@value #TREND_LIMIT} 次 run 的总分趋势、当前激活基线。库是唯一事实来源（§8.7），
 * 报表文件可随时从库重建。</p>
 *
 * <p>装配失败不抛到命令层（§13.4 报表失败不得阻断 run 终态）：任何读库异常都收敛成
 * {@link #runFound()} 为 false 且带原因的数据对象，渲染时统一标「数据缺失」。</p>
 *
 * <p>隐私：case 行只按白名单取 §4.2.2 允许展示的列，prompt、模型回复原文、昵称、userID
 * 一律不进内存结构（§15.9 I2）。</p>
 *
 * <p>权重只从 rubric 注入（{@code EvalDimensionWeights.from(rubric)}）：本类不内置任何缺省
 * 权重。没有注入权重时总分标为缺失并记 WARN，绝不用硬编码系数凑一个看起来正常的分数。</p>
 */
public final class EvalReportData {

    /** 趋势表固定取最近 10 次 run（§8.3 第 9 节）。 */
    public static final int TREND_LIMIT = 10;

    /** evidence 摘要上限：超出截断，避免把整段判分说明或回复原文带进报表（§8.3 第 6 节）。 */
    public static final int EVIDENCE_LIMIT = 120;

    /** 不可得值的统一文案（§8.3、§8.5）。 */
    public static final String MISSING = "数据缺失";

    /** 维度枚举，取值集合封闭（§6.1）；顺序即报表顺序。 */
    public static final String DIM_PERSONA = "persona";
    public static final String DIM_BOUNDARY = "boundary";
    public static final String DIM_MEMORY = "memory";
    public static final String DIM_KNOWLEDGE = "knowledge";
    public static final String DIM_STYLE = "style";

    /** 维度状态：全部 error/invalid 且无有效样本（§8.5）。 */
    public static final String STATUS_DIMENSION_MISSING = "dimension_missing";
    /** 维度状态：采样数少于应有样本数（§8.3 第 3 节「样本不足标 partial」）。 */
    public static final String STATUS_PARTIAL = "partial";
    /** 维度状态：正常。 */
    public static final String STATUS_OK = "ok";

    /** 维度中文名（§6.1 逐字）。 */
    private static final Map<String, String> DIMENSION_LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put(DIM_PERSONA, "人设一致性");
        labels.put(DIM_BOUNDARY, "边界安全");
        labels.put(DIM_MEMORY, "记忆召回");
        labels.put(DIM_KNOWLEDGE, "知识正确性");
        labels.put(DIM_STYLE, "风格匹配");
        DIMENSION_LABELS = Collections.unmodifiableMap(labels);
    }

    /** 九键名称，顺序与 §8.2 一致；趋势表与基线比较都依赖这组键。 */
    private static final String[] NINE_KEYS = {
        "bank_hash", "rubric_version", "persona_hash", "config_fingerprint",
        "eval_profile", "judge_profile", "judge_model", "generation_model", "eval_api_version"
    };

    private final String runId;
    private final boolean runFound;
    private final String note;
    private final String tier;
    private final String status;
    private final String startedAt;
    private final String endedAt;
    private final Long durationMs;
    private final String bankFile;
    private final String bankVersion;
    private final String bankHash;
    private final String personaId;
    private final String personaVersion;
    private final String personaHash;
    private final String rubricVersion;
    private final String evalApiVersion;
    private final String evalProfile;
    private final String judgeProfile;
    private final String judgeModel;
    private final String generationModel;
    private final String configFingerprint;
    private final String configSnapshotJson;
    private final String calibrationJson;
    private final String errorSummary;
    private final String reportPath;
    private final int sampleK;
    private final int callsUsed;
    private final int callsBudget;
    private final List<CaseRecord> cases;
    private final boolean casesComplete;
    private final int casesExpected;
    private final boolean callsComplete;
    private final int callsExpected;
    private final int callsRead;
    private final EvalMetrics metrics;
    /** 原始 call 行的只读副本；仅供性能节计算输出吞吐与评测自身开销（§7.2）。 */
    private final List<JSONObject> callRows;
    private final List<Dimension> dimensions;
    private final Double totalScore;
    private final int validCaseCount;
    private final List<TrendEntry> trend;
    private final BaselineView baseline;
    /** 基线与本 run 的配对比较（F-6）；装配失败或未接入数据源时为 null。 */
    private final EvalDeltaResult delta;
    /** L3 用量旁证（E-4）；未注入数据源时为 null，渲染按「不可得」处理。 */
    private final EvalAiUsageEvidence aiUsage;
    private final Map<String, Double> weights;

    private EvalReportData(Builder builder) {
        this.runId = builder.runId;
        this.runFound = builder.runFound;
        this.note = builder.note;
        this.tier = builder.tier;
        this.status = builder.status;
        this.startedAt = builder.startedAt;
        this.endedAt = builder.endedAt;
        this.durationMs = builder.durationMs;
        this.bankFile = builder.bankFile;
        this.bankVersion = builder.bankVersion;
        this.bankHash = builder.bankHash;
        this.personaId = builder.personaId;
        this.personaVersion = builder.personaVersion;
        this.personaHash = builder.personaHash;
        this.rubricVersion = builder.rubricVersion;
        this.evalApiVersion = builder.evalApiVersion;
        this.evalProfile = builder.evalProfile;
        this.judgeProfile = builder.judgeProfile;
        this.judgeModel = builder.judgeModel;
        this.generationModel = builder.generationModel;
        this.configFingerprint = builder.configFingerprint;
        this.configSnapshotJson = builder.configSnapshotJson;
        this.calibrationJson = builder.calibrationJson;
        this.errorSummary = builder.errorSummary;
        this.reportPath = builder.reportPath;
        this.sampleK = builder.sampleK;
        this.callsUsed = builder.callsUsed;
        this.callsBudget = builder.callsBudget;
        this.cases = Collections.unmodifiableList(new ArrayList<CaseRecord>(builder.cases));
        this.casesComplete = builder.casesComplete;
        this.casesExpected = builder.casesExpected;
        this.callsComplete = builder.callsComplete;
        this.callsExpected = builder.callsExpected;
        this.callsRead = builder.callsRead;
        this.metrics = builder.metrics;
        this.callRows = Collections.unmodifiableList(new ArrayList<JSONObject>(builder.callRows));
        this.weights = Collections.unmodifiableMap(new LinkedHashMap<String, Double>(builder.weights));
        this.dimensions = Collections.unmodifiableList(new ArrayList<Dimension>(builder.dimensions));
        this.totalScore = builder.totalScore;
        this.validCaseCount = builder.validCaseCount;
        this.trend = Collections.unmodifiableList(new ArrayList<TrendEntry>(builder.trend));
        this.baseline = builder.baseline;
        this.delta = builder.delta;
        this.aiUsage = builder.aiUsage;
    }

    /**
     * 从库装配一次 run 的报表数据（§8.7 库是唯一事实来源）。
     *
     * <p>不注入权重、不注入日志，等价于「权重缺失 + 静默」；生产路径应调用带
     * {@link PluginLogger} 的重载。</p>
     *
     * @param store 评测库门面；null 时返回「数据缺失」数据对象
     * @param runId run_id；空值或库中不存在时返回「数据缺失」数据对象
     * @return 永不返回 null，也永不抛出读库异常
     */
    public static EvalReportData assemble(EvalStore store, String runId) {
        return assemble(store, runId, null, null, null);
    }

    /**
     * 同上，注入判分规约的维度权重（§6.1：权重只由 rubric 定义）。
     *
     * @param weightOverride 维度 → 权重；null 或空表示未注入，总分标缺失并告警
     */
    public static EvalReportData assemble(EvalStore store, String runId,
                                         Map<String, Double> weightOverride) {
        return assemble(store, runId, weightOverride, null, null);
    }

    /**
     * 同上，并注入 L3 用量旁证的数据源（E-4：规格 §7.5、§15.5 E11）。
     *
     * <p>数据源为 null 时不采集旁证（{@link #aiUsage()} 为 null，性能节保留固定「L3 旁证」
     * 口径行）；数据源读表失败或无行时由 {@link EvalAiUsageEvidenceService} 收敛成
     * 「不可得」结果，不抛异常、不阻断报表装配。基线与 delta 仍由库中激活基线计算。</p>
     *
     * @param weightOverride 维度 → 权重；null 或空表示未注入，总分标缺失并告警
     * @param usageSource    L3 只读数据源；null 表示本次不采集旁证
     */
    public static EvalReportData assemble(EvalStore store, String runId,
                                         Map<String, Double> weightOverride,
                                         EvalAiUsageSource usageSource) {
        return assemble(store, runId, weightOverride, usageSource, null);
    }

    /**
     * 完整装配入口：额外注入日志器，让被吞掉的读库 / 权重 / delta 异常留下 WARN 轨迹。
     *
     * @param weightOverride 维度 → 权重；null 或空表示未注入（总分缺失 + WARN）
     * @param usageSource    L3 只读数据源；null 表示本次不采集旁证
     * @param logger         插件日志器；null 表示静默
     */
    public static EvalReportData assemble(EvalStore store, String runId,
                                         Map<String, Double> weightOverride,
                                         EvalAiUsageSource usageSource,
                                         PluginLogger logger) {
        String safeRunId = runId == null ? "" : runId.trim();
        Map<String, Double> weights = mergeWeights(weightOverride);
        if (store == null) {
            return missing(safeRunId, "评测存储不可用");
        }
        if (safeRunId.isEmpty()) {
            return missing("", "未提供 run_id");
        }
        try {
            JSONObject run = store.findRun(safeRunId);
            if (run == null) {
                return missing(safeRunId, "库中无该 run 的运行行");
            }
            return assembleFound(store, run, weights, usageSource, logger);
        } catch (RuntimeException e) {
            EvalLog.warn(logger, "[MBB-Eval] 报表装配失败，run_id=" + safeRunId + "：" + describe(e));
            return missing(safeRunId, "报表数据装配失败：" + describe(e));
        }
    }

    /** 缺数据时的数据对象：九节结构仍可渲染，所有值标「数据缺失」；权重缺失即 0。 */
    public static EvalReportData missing(String runId, String note) {
        Builder builder = new Builder();
        builder.runId = runId == null ? "" : runId;
        builder.note = note == null ? MISSING : note;
        for (String dimension : DIMENSION_LABELS.keySet()) {
            builder.weights.put(dimension, Double.valueOf(0D));
            builder.dimensions.add(Dimension.noData(dimension, Double.valueOf(0D)));
        }
        return new EvalReportData(builder);
    }

    private static EvalReportData assembleFound(EvalStore store, JSONObject run,
                                                Map<String, Double> weights,
                                                EvalAiUsageSource usageSource,
                                                PluginLogger logger) {
        String runId = text(run, "run_id");
        EvalStore.RowSet caseSet = store.findCaseResults(runId);
        EvalStore.RowSet callSet = store.findCalls(runId);

        List<CaseRecord> records = new ArrayList<CaseRecord>();
        for (JSONObject row : caseSet.rows()) {
            records.add(CaseRecord.fromRow(runId, row));
        }

        EvalMetrics metrics;
        try {
            metrics = new EvalMetricsService().compute(callSet.rows(), caseSet.rows());
        } catch (RuntimeException e) {
            metrics = null;
        }

        Builder builder = new Builder();
        builder.runId = runId;
        builder.runFound = true;
        builder.tier = text(run, "tier");
        builder.status = text(run, "status");
        builder.startedAt = text(run, "started_at");
        builder.endedAt = text(run, "ended_at");
        builder.durationMs = longValue(run, "duration_ms");
        builder.bankFile = text(run, "bank_file");
        builder.bankVersion = text(run, "bank_version");
        builder.bankHash = text(run, "bank_hash");
        builder.personaId = text(run, "persona_id");
        builder.personaVersion = text(run, "persona_version");
        builder.personaHash = text(run, "persona_hash");
        builder.rubricVersion = text(run, "rubric_version");
        builder.evalApiVersion = text(run, "eval_api_version");
        builder.evalProfile = text(run, "eval_profile");
        builder.judgeProfile = text(run, "judge_profile");
        builder.judgeModel = text(run, "judge_model");
        builder.generationModel = text(run, "generation_model");
        builder.configFingerprint = text(run, "config_fingerprint");
        builder.configSnapshotJson = text(run, "config_snapshot_json");
        builder.calibrationJson = text(run, "calibration_json");
        builder.errorSummary = text(run, "error_summary");
        builder.reportPath = text(run, "report_path");
        builder.sampleK = intValue(run, "sample_k", 1);
        builder.callsUsed = intValue(run, "calls_used", 0);
        builder.callsBudget = intValue(run, "calls_budget", 0);
        builder.cases.addAll(records);
        builder.casesComplete = caseSet.isComplete();
        builder.casesExpected = caseSet.expectedCount();
        builder.callsComplete = callSet.isComplete();
        builder.callsExpected = callSet.expectedCount();
        builder.callsRead = callSet.rows().size();
        builder.metrics = metrics;
        builder.callRows.addAll(callSet.rows());
        builder.weights.putAll(weights);
        if (weights.isEmpty()) {
            builder.note = "维度权重缺失：未从 rubric 注入";
            EvalLog.warn(logger, "[MBB-Eval] 维度权重缺失，报表总分与 delta 标为数据缺失，run_id="
                    + runId);
        }

        List<Dimension> raw = buildDimensions(records, weights);
        int validCases = 0;
        Map<String, Double> scoresById = new LinkedHashMap<String, Double>();
        for (Dimension dimension : raw) {
            if (dimension.score() != null) {
                scoresById.put(dimension.id(), dimension.score());
            }
            validCases += dimension.validCount();
        }
        // 总分复用聚合器口径（Σw·dimScore/Σw），不再在本类里重写一遍权重求和。
        builder.totalScore = EvalScoreAggregator.weightedTotal(scoresById, weights);
        builder.validCaseCount = validCases;
        double weightSum = 0D;
        for (Dimension dimension : raw) {
            if (dimension.score() != null) {
                weightSum += dimension.weight();
            }
        }
        List<Dimension> normalized = new ArrayList<Dimension>();
        for (Dimension dimension : raw) {
            double normalizedWeight = 0D;
            if (weightSum > 0D && dimension.score() != null) {
                normalizedWeight = dimension.weight() / weightSum;
            }
            normalized.add(dimension.withNormalizedWeight(normalizedWeight));
        }
        builder.dimensions.addAll(normalized);

        builder.trend.addAll(buildTrend(store, runId, run, builder.totalScore, weights, logger));
        builder.baseline = buildBaseline(store, run, builder.totalScore);
        builder.delta = computeDelta(store, runId, weights, logger);
        builder.aiUsage = collectAiUsage(usageSource, run);
        return new EvalReportData(builder);
    }

    /**
     * 计算基线与本 run 的配对 delta（F-6：§8.4、§8.5）。
     *
     * <p>只读库中激活基线；计算失败（权重异常、bootstrap 异常等）返回 null，不影响
     * 九节其余数据的装配，渲染侧回退「未计算」占位。</p>
     */
    private static EvalDeltaResult computeDelta(EvalStore store, String runId,
                                                Map<String, Double> weights,
                                                PluginLogger logger) {
        try {
            return new EvalDeltaService(store, EvalDimensionWeights.of(weights))
                    .compareToActiveBaseline(runId);
        } catch (RuntimeException e) {
            EvalLog.warn(logger, "[MBB-Eval] 基线 delta 计算失败，按未计算处理，run_id=" + runId
                    + "：" + describe(e));
            return null;
        }
    }

    /**
     * 采集 L3 用量旁证（E-4：§7.5）；缺数据源、run 无 profile 或日期不可解析时返回 null。
     *
     * <p>日期取 run 的 started_at 至 ended_at 的日粒度区间；服务内部已把读表失败收敛成
     * 「不可得」，这里只兜住参数异常，不记日志、不写库。</p>
     */
    private static EvalAiUsageEvidence collectAiUsage(EvalAiUsageSource usageSource, JSONObject run) {
        if (usageSource == null) {
            return null;
        }
        String profile = text(run, "eval_profile");
        String startDate = dayOf(text(run, "started_at"));
        if (profile.isEmpty() || startDate.isEmpty()) {
            return null;
        }
        String endDate = dayOf(text(run, "ended_at"));
        try {
            return new EvalAiUsageEvidenceService(usageSource)
                    .collect(profile, startDate, endDate.isEmpty() ? startDate : endDate);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 从 {@code yyyy-MM-dd HH:mm:ss} 取日期部分；格式不符返回空串。 */
    private static String dayOf(String timestamp) {
        if (timestamp == null || timestamp.length() < 10) {
            return "";
        }
        String day = timestamp.substring(0, 10);
        if (day.charAt(4) != '-' || day.charAt(7) != '-') {
            return "";
        }
        return day;
    }

    /**
     * 只收调用方注入的权重：不做任何缺省打底，非法项丢弃（非法项导致的缺失由装配层告警）。
     */
    private static Map<String, Double> mergeWeights(Map<String, Double> override) {
        Map<String, Double> merged = new LinkedHashMap<String, Double>();
        if (override != null) {
            for (Map.Entry<String, Double> entry : override.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    double weight = entry.getValue().doubleValue();
                    if (weight >= 0D && !Double.isNaN(weight) && !Double.isInfinite(weight)) {
                        merged.put(entry.getKey(), Double.valueOf(weight));
                    }
                }
            }
        }
        return merged;
    }

    /** 五维聚合（§6.1）：dimScore = 100 × mean(caseScore)，分母只含 ok + vetoed。 */
    private static List<Dimension> buildDimensions(List<CaseRecord> records,
                                                   Map<String, Double> weights) {
        List<Dimension> dimensions = new ArrayList<Dimension>();
        for (Map.Entry<String, String> entry : DIMENSION_LABELS.entrySet()) {
            String dimensionId = entry.getKey();
            double weight = weights.containsKey(dimensionId)
                    ? weights.get(dimensionId).doubleValue() : 0D;

            int ok = 0;
            int vetoed = 0;
            int error = 0;
            int invalid = 0;
            int missingScore = 0;
            int sampleCount = 0;
            int cachedCount = 0;
            int knownSampleRows = 0;
            int expectedSamples = 0;
            int kUsed = 1;
            double scoreSum = 0D;
            int rowCount = 0;

            for (CaseRecord record : records) {
                if (!dimensionId.equals(record.dimension())) {
                    continue;
                }
                rowCount++;
                String status = record.status();
                if ("ok".equals(status)) {
                    if (record.score() == null) {
                        missingScore++;
                    } else {
                        ok++;
                        scoreSum += record.score().doubleValue();
                    }
                } else if ("vetoed".equals(status)) {
                    // vetoed 是硬项命中：计 0 分并留在分母里（§6.1、§6.3.5）。
                    vetoed++;
                } else if ("error".equals(status)) {
                    error++;
                } else {
                    invalid++;
                }
                if (record.sampleCount() != null) {
                    knownSampleRows++;
                    sampleCount += record.sampleCount().intValue();
                }
                if (record.cachedCount() != null) {
                    cachedCount += record.cachedCount().intValue();
                }
                int rowK = record.kUsed() == null ? 1 : Math.max(1, record.kUsed().intValue());
                kUsed = Math.max(kUsed, rowK);
                expectedSamples += rowK;
            }

            int valid = ok + vetoed;
            Double score = valid == 0 ? null : Double.valueOf(scoreSum / valid);
            String status;
            if (rowCount == 0) {
                status = MISSING;
            } else if (valid == 0) {
                status = STATUS_DIMENSION_MISSING;
            } else if (knownSampleRows == rowCount && expectedSamples > 0
                    && sampleCount < expectedSamples) {
                status = STATUS_PARTIAL;
            } else {
                status = STATUS_OK;
            }
            dimensions.add(new Dimension(dimensionId, entry.getValue(), weight, 0D, score, status,
                    rowCount, ok, vetoed, error, invalid, sampleCount, cachedCount, kUsed,
                    missingScore));
        }
        return dimensions;
    }

    /** 趋势表：最近 10 次 run，各自总分由库中 case 行重算，delta 与上一次 run 比较。 */
    private static List<TrendEntry> buildTrend(EvalStore store, String currentRunId,
                                               JSONObject currentRun, Double currentTotal,
                                               Map<String, Double> weights,
                                               PluginLogger logger) {
        List<TrendEntry> entries = new ArrayList<TrendEntry>();
        List<JSONObject> recent;
        try {
            recent = store.findRecentRuns(TREND_LIMIT);
        } catch (RuntimeException e) {
            EvalLog.warn(logger, "[MBB-Eval] 趋势表读取失败，按空趋势渲染：" + describe(e));
            return entries;
        }
        if (recent == null) {
            return entries;
        }
        for (JSONObject row : recent) {
            if (row == null) {
                continue;
            }
            String runId = text(row, "run_id");
            Double total;
            if (runId != null && runId.equals(currentRunId)) {
                total = currentTotal;
            } else {
                total = totalOf(store, runId, weights, logger);
            }
            entries.add(new TrendEntry(runId, text(row, "started_at"), text(row, "tier"), total,
                    row, null, "不可比"));
        }
        // delta 与「上一次 run」比较：列表按时间倒序，索引 i 的上一次是 i + 1。
        List<TrendEntry> ranked = new ArrayList<TrendEntry>();
        for (int i = 0; i < entries.size(); i++) {
            TrendEntry current = entries.get(i);
            TrendEntry previous = i + 1 < entries.size() ? entries.get(i + 1) : null;
            ranked.add(current.withComparison(previous));
        }
        return ranked;
    }

    /** 单次 run 的总分（趋势表用）：读该 run 的 case 行按 §6.1 重算。 */
    private static Double totalOf(EvalStore store, String runId, Map<String, Double> weights,
                                  PluginLogger logger) {
        if (runId == null || runId.isEmpty()) {
            return null;
        }
        try {
            EvalStore.RowSet caseSet = store.findCaseResults(runId);
            List<CaseRecord> records = new ArrayList<CaseRecord>();
            for (JSONObject row : caseSet.rows()) {
                records.add(CaseRecord.fromRow(runId, row));
            }
            if (records.isEmpty()) {
                return null;
            }
            Map<String, Double> scoresById = new LinkedHashMap<String, Double>();
            for (Dimension dimension : buildDimensions(records, weights)) {
                if (dimension.score() != null) {
                    scoresById.put(dimension.id(), dimension.score());
                }
            }
            return EvalScoreAggregator.weightedTotal(scoresById, weights);
        } catch (RuntimeException e) {
            EvalLog.warn(logger, "[MBB-Eval] 趋势行总分重算失败，按数据缺失处理，run_id=" + runId
                    + "：" + describe(e));
            return null;
        }
    }

    /** 当前激活基线（§8.2、§8.4）：只取比较所需的键、总分与维度分，不复制基线判定逻辑。 */
    private static BaselineView buildBaseline(EvalStore store, JSONObject run, Double currentTotal) {
        JSONObject baselineRow;
        try {
            baselineRow = store.findActiveBaseline();
        } catch (RuntimeException e) {
            baselineRow = null;
        }
        if (baselineRow == null) {
            return BaselineView.none();
        }
        String baselineRunId = text(baselineRow, "run_id");
        String createdAt = text(baselineRow, "created_at");
        Double baselineTotal = number(baselineRow, "total_score");
        Map<String, Double> dimensionScores = parseDimensionScores(
                text(baselineRow, "dimension_scores_json"));
        List<String> differing = new ArrayList<String>();
        for (String key : NINE_KEYS) {
            String left = text(run, key);
            String right = text(baselineRow, key);
            if (!equalText(left, right)) {
                differing.add(key);
            }
        }
        String tier = text(run, "tier");
        boolean comparable = differing.isEmpty()
                && EvalStore.TIER_FULL.equals(tier)
                && baselineTotal != null
                && currentTotal != null;
        String reason = null;
        if (!differing.isEmpty()) {
            reason = "九键不同";
        } else if (!EvalStore.TIER_FULL.equals(tier)) {
            reason = "smoke 档不作为比较基线";
        } else if (baselineTotal == null || currentTotal == null) {
            reason = "总分缺失";
        }
        return new BaselineView(true, baselineRunId, createdAt, baselineTotal, comparable,
                differing, dimensionScores, reason);
    }

    /** dimension_scores_json 兼容 {"persona": 81.4} 与 {"persona": {"score": 81.4}} 两种形态。 */
    private static Map<String, Double> parseDimensionScores(String json) {
        Map<String, Double> scores = new LinkedHashMap<String, Double>();
        if (json == null || json.isEmpty()) {
            return scores;
        }
        try {
            JSONObject object = JSONObject.parseObject(json);
            for (String key : object.keySet()) {
                Object value = object.get(key);
                Double score = null;
                if (value instanceof Number) {
                    score = Double.valueOf(((Number) value).doubleValue());
                } else if (value instanceof JSONObject) {
                    score = number((JSONObject) value, "score");
                }
                if (score != null) {
                    scores.put(key, score);
                }
            }
        } catch (RuntimeException e) {
            return scores;
        }
        return scores;
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName() : message.trim();
    }

    private static boolean equalText(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    /** 列值转文本：空白收敛成 null，避免把空串当成有效值渲染。 */
    private static String text(JSONObject row, String key) {
        if (row == null || key == null) {
            return null;
        }
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Double number(JSONObject row, String key) {
        if (row == null || key == null) {
            return null;
        }
        Object value = row.get(key);
        if (value instanceof Number) {
            return Double.valueOf(((Number) value).doubleValue());
        }
        if (value instanceof String) {
            try {
                return Double.valueOf(Double.parseDouble(((String) value).trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Long longValue(JSONObject row, String key) {
        if (row == null || key == null) {
            return null;
        }
        Object value = row.get(key);
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        if (value instanceof String) {
            try {
                return Long.valueOf(Long.parseLong(((String) value).trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static int intValue(JSONObject row, String key, int fallback) {
        Long value = longValue(row, key);
        return value == null ? fallback : value.intValue();
    }

    /** 九键名称（顺序固定，渲染与比较共用）。 */
    public static List<String> nineKeyNames() {
        return Collections.unmodifiableList(Arrays.asList(NINE_KEYS.clone()));
    }

    /** 九键中某个键的值；不存在返回 null。 */
    public String nineKey(String name) {
        if ("bank_hash".equals(name)) {
            return bankHash;
        }
        if ("rubric_version".equals(name)) {
            return rubricVersion;
        }
        if ("persona_hash".equals(name)) {
            return personaHash;
        }
        if ("config_fingerprint".equals(name)) {
            return configFingerprint;
        }
        if ("eval_profile".equals(name)) {
            return evalProfile;
        }
        if ("judge_profile".equals(name)) {
            return judgeProfile;
        }
        if ("judge_model".equals(name)) {
            return judgeModel;
        }
        if ("generation_model".equals(name)) {
            return generationModel;
        }
        if ("eval_api_version".equals(name)) {
            return evalApiVersion;
        }
        return null;
    }

    public String runId() {
        return runId;
    }

    /** 是否从库中读到了该 run。 */
    public boolean runFound() {
        return runFound;
    }

    /** 装配失败或数据缺失的原因；正常装配时为 null。 */
    public String note() {
        return note;
    }

    public String tier() {
        return tier;
    }

    public String status() {
        return status;
    }

    public String startedAt() {
        return startedAt;
    }

    public String endedAt() {
        return endedAt;
    }

    public Long durationMs() {
        return durationMs;
    }

    public String bankFile() {
        return bankFile;
    }

    public String bankVersion() {
        return bankVersion;
    }

    public String bankHash() {
        return bankHash;
    }

    public String personaId() {
        return personaId;
    }

    public String personaVersion() {
        return personaVersion;
    }

    public String personaHash() {
        return personaHash;
    }

    public String rubricVersion() {
        return rubricVersion;
    }

    public String evalApiVersion() {
        return evalApiVersion;
    }

    public String evalProfile() {
        return evalProfile;
    }

    public String judgeProfile() {
        return judgeProfile;
    }

    public String judgeModel() {
        return judgeModel;
    }

    public String generationModel() {
        return generationModel;
    }

    public String configFingerprint() {
        return configFingerprint;
    }

    public String configSnapshotJson() {
        return configSnapshotJson;
    }

    public String calibrationJson() {
        return calibrationJson;
    }

    public String errorSummary() {
        return errorSummary;
    }

    public String reportPath() {
        return reportPath;
    }

    public int sampleK() {
        return sampleK;
    }

    public int callsUsed() {
        return callsUsed;
    }

    public int callsBudget() {
        return callsBudget;
    }

    public List<CaseRecord> cases() {
        return cases;
    }

    /** case 行是否完整读出；false 时明细里必须标「数据缺失」（§4.7）。 */
    public boolean casesComplete() {
        return casesComplete;
    }

    public int casesExpected() {
        return casesExpected;
    }

    public boolean callsComplete() {
        return callsComplete;
    }

    public int callsExpected() {
        return callsExpected;
    }

    /** call 行实际读到的条数；-1 表示未读取（数据缺失分支）。 */
    public int callsRead() {
        return callsRead;
    }

    /** 指标；装配失败时为 null，渲染标「数据缺失」。 */
    public EvalMetrics metrics() {
        return metrics;
    }

    /**
     * call 行的只读副本：性能节需要逐调用 elapsed_ms / usage_completion 计算输出吞吐
     * 与评测自身开销（§7.2），指标快照未冻结这两个口径。渲染侧不得写出原始行内容。
     */
    List<JSONObject> callRows() {
        return callRows;
    }

    public List<Dimension> dimensions() {
        return dimensions;
    }

    /** 维度 → 权重（含判分规约覆盖后的值）。 */
    public Map<String, Double> weights() {
        return weights;
    }

    public Double totalScore() {
        return totalScore;
    }

    public int validCaseCount() {
        return validCaseCount;
    }

    public List<TrendEntry> trend() {
        return trend;
    }

    public BaselineView baseline() {
        return baseline;
    }

    /**
     * 基线与本 run 的配对比较结果（F-6：§8.4、§8.5）。
     *
     * <p>没有激活基线时仍返回「不可比」结果（{@code available=false}），渲染可据此写原因；
     * 装配失败或未计算时为 null，第 5 节回退「未计算」占位。</p>
     */
    public EvalDeltaResult delta() {
        return delta;
    }

    /**
     * L3 用量旁证（E-4：§7.5）；未注入数据源或日期不可解析时为 null。
     *
     * <p>不为 null 时，可用性由 {@link EvalAiUsageEvidence#available()} 判定；不可用时
     * 渲染写「不可得」与原因，不补数、不换算。</p>
     */
    public EvalAiUsageEvidence aiUsage() {
        return aiUsage;
    }

    /** 装配用可变构造器：只在类内使用，避免 40 个参数的长构造方法。 */
    private static final class Builder {
        private String runId = "";
        private boolean runFound;
        private String note;
        private String tier;
        private String status;
        private String startedAt;
        private String endedAt;
        private Long durationMs;
        private String bankFile;
        private String bankVersion;
        private String bankHash;
        private String personaId;
        private String personaVersion;
        private String personaHash;
        private String rubricVersion;
        private String evalApiVersion;
        private String evalProfile;
        private String judgeProfile;
        private String judgeModel;
        private String generationModel;
        private String configFingerprint;
        private String configSnapshotJson;
        private String calibrationJson;
        private String errorSummary;
        private String reportPath;
        private int sampleK = 1;
        private int callsUsed;
        private int callsBudget;
        private final List<CaseRecord> cases = new ArrayList<CaseRecord>();
        private boolean casesComplete;
        private int casesExpected = -1;
        private boolean callsComplete;
        private int callsExpected = -1;
        private int callsRead = -1;
        private EvalMetrics metrics;
        private final List<JSONObject> callRows = new ArrayList<JSONObject>();
        private final List<Dimension> dimensions = new ArrayList<Dimension>();
        private Double totalScore;
        private int validCaseCount;
        private final List<TrendEntry> trend = new ArrayList<TrendEntry>();
        private BaselineView baseline = BaselineView.none();
        private EvalDeltaResult delta;
        private EvalAiUsageEvidence aiUsage;
        private final Map<String, Double> weights = new LinkedHashMap<String, Double>();
    }

    /**
     * 单题结果（§8.3 第 6 节）。
     *
     * <p>只保留白名单列：case_result_id / case_id / dimension / status / score / label / evidence。
     * 行里即便混入 prompt、回复原文、昵称或 userID，也不会进入本对象（§15.9 I2）。</p>
     */
    public static final class CaseRecord {
        private final String caseResultId;
        private final String caseId;
        private final String dimension;
        private final String status;
        private final Double score;
        private final String label;
        private final String evidenceSummary;
        private final Integer kUsed;
        private final Integer sampleCount;
        private final Integer cachedCount;

        private CaseRecord(String caseResultId, String caseId, String dimension, String status,
                           Double score, String label, String evidenceSummary, Integer kUsed,
                           Integer sampleCount, Integer cachedCount) {
            this.caseResultId = caseResultId;
            this.caseId = caseId;
            this.dimension = dimension;
            this.status = status;
            this.score = score;
            this.label = label;
            this.evidenceSummary = evidenceSummary;
            this.kUsed = kUsed;
            this.sampleCount = sampleCount;
            this.cachedCount = cachedCount;
        }

        static CaseRecord fromRow(String runId, JSONObject row) {
            String caseId = text(row, "case_id");
            String caseResultId = text(row, "case_result_id");
            if (caseResultId == null && caseId != null) {
                caseResultId = EvalStore.deriveCaseResultId(runId, caseId);
            }
            return new CaseRecord(caseResultId, caseId, text(row, "dimension"),
                    text(row, "status"), number(row, "score"), text(row, "label"),
                    clip(text(row, "evidence"), EVIDENCE_LIMIT),
                    integer(row, "k_used"), integer(row, "sample_count"),
                    integer(row, "cached_count"));
        }

        public String caseResultId() {
            return caseResultId;
        }

        public String caseId() {
            return caseId;
        }

        public String dimension() {
            return dimension;
        }

        public String status() {
            return status;
        }

        public Double score() {
            return score;
        }

        public String label() {
            return label;
        }

        /** evidence 摘要：换行与竖线已收敛，长度不超过 {@link #EVIDENCE_LIMIT}。 */
        public String evidenceSummary() {
            return evidenceSummary;
        }

        public Integer kUsed() {
            return kUsed;
        }

        public Integer sampleCount() {
            return sampleCount;
        }

        public Integer cachedCount() {
            return cachedCount;
        }
    }

    /**
     * 五维中的一维（§8.3 第 3 节、§6.1 聚合口径）。
     */
    public static final class Dimension {
        private final String id;
        private final String label;
        private final double weight;
        private final double normalizedWeight;
        private final Double score;
        private final String status;
        private final int caseCount;
        private final int okCount;
        private final int vetoedCount;
        private final int errorCount;
        private final int invalidCount;
        private final int sampleCount;
        private final int cachedCount;
        private final int kUsed;
        private final int missingScoreCount;

        private Dimension(String id, String label, double weight, double normalizedWeight,
                          Double score, String status, int caseCount, int okCount, int vetoedCount,
                          int errorCount, int invalidCount, int sampleCount, int cachedCount,
                          int kUsed, int missingScoreCount) {
            this.id = id;
            this.label = label;
            this.weight = weight;
            this.normalizedWeight = normalizedWeight;
            this.score = score;
            this.status = status;
            this.caseCount = caseCount;
            this.okCount = okCount;
            this.vetoedCount = vetoedCount;
            this.errorCount = errorCount;
            this.invalidCount = invalidCount;
            this.sampleCount = sampleCount;
            this.cachedCount = cachedCount;
            this.kUsed = kUsed;
            this.missingScoreCount = missingScoreCount;
        }

        static Dimension noData(String id, Double weight) {
            return new Dimension(id, DIMENSION_LABELS.get(id),
                    weight == null ? 0D : weight.doubleValue(), 0D, null, MISSING,
                    0, 0, 0, 0, 0, 0, 0, 1, 0);
        }

        Dimension withNormalizedWeight(double value) {
            return new Dimension(id, label, weight, value, score, status, caseCount, okCount,
                    vetoedCount, errorCount, invalidCount, sampleCount, cachedCount, kUsed,
                    missingScoreCount);
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public double weight() {
            return weight;
        }

        /** 缺维归一后的权重；该维不计入总分时为 0（§8.5）。 */
        public double normalizedWeight() {
            return normalizedWeight;
        }

        /** 维度得分 0..100；无有效样本时为 null。 */
        public Double score() {
            return score;
        }

        public String status() {
            return status;
        }

        public int caseCount() {
            return caseCount;
        }

        public int okCount() {
            return okCount;
        }

        public int vetoedCount() {
            return vetoedCount;
        }

        public int errorCount() {
            return errorCount;
        }

        public int invalidCount() {
            return invalidCount;
        }

        public int validCount() {
            return okCount + vetoedCount;
        }

        public int sampleCount() {
            return sampleCount;
        }

        public int cachedCount() {
            return cachedCount;
        }

        public int kUsed() {
            return kUsed;
        }

        public int missingScoreCount() {
            return missingScoreCount;
        }

        /** judge 维（persona / boundary / memory）标 k，确定性维（knowledge / style）标「确定性」。 */
        public boolean judge() {
            return !DIM_KNOWLEDGE.equals(id) && !DIM_STYLE.equals(id);
        }
    }

    /**
     * 趋势表一行（§8.3 第 9 节）。
     *
     * <p>总分只报点值，不做置信区间与显著性判定（§8.4），因此「显著」列在可比时写「不适用」；
     * 九键不同、档位不同或缺分时写「不可比」。</p>
     */
    public static final class TrendEntry {
        private final String runId;
        private final String startedAt;
        private final String tier;
        private final Double totalScore;
        private final Double delta;
        private final String significance;
        private final JSONObject row;

        private TrendEntry(String runId, String startedAt, String tier, Double totalScore,
                           JSONObject row, Double delta, String significance) {
            this.runId = runId;
            this.startedAt = startedAt;
            this.tier = tier;
            this.totalScore = totalScore;
            this.row = row;
            this.delta = delta;
            this.significance = significance;
        }

        private TrendEntry withComparison(TrendEntry previous) {
            if (previous == null || totalScore == null || previous.totalScore == null) {
                return new TrendEntry(runId, startedAt, tier, totalScore, row, null, "不可比");
            }
            if (!EvalStore.TIER_FULL.equals(tier) || !EvalStore.TIER_FULL.equals(previous.tier)) {
                return new TrendEntry(runId, startedAt, tier, totalScore, row, null, "不可比");
            }
            for (String key : NINE_KEYS) {
                if (!equalText(text(row, key), text(previous.row, key))) {
                    return new TrendEntry(runId, startedAt, tier, totalScore, row, null, "不可比");
                }
            }
            return new TrendEntry(runId, startedAt, tier, totalScore, row,
                    Double.valueOf(totalScore.doubleValue() - previous.totalScore.doubleValue()),
                    "不适用");
        }

        public String runId() {
            return runId;
        }

        public String startedAt() {
            return startedAt;
        }

        public String tier() {
            return tier;
        }

        public Double totalScore() {
            return totalScore;
        }

        /** 与上一次 run 的总分差；缺分、跨档或九键不同时为 null。 */
        public Double delta() {
            return delta;
        }

        /** 四态结论；总分不可比时写「不可比」，可比时写「不适用」（§8.4）。 */
        public String significance() {
            return significance;
        }
    }

    /**
     * 与基线的对比视图（§8.3 第 5 节）。
     *
     * <p>本切片只给九键比较、总分与维度点值 delta；区间（ci_low / ci_high）与显著性判定
     * 属 F-6 的 bootstrap 计算，未接入时报表写「未计算」。</p>
     */
    public static final class BaselineView {
        private static final BaselineView NONE = new BaselineView(false, null, null, null, false,
                Collections.<String>emptyList(), Collections.<String, Double>emptyMap(), "无基线");

        private final boolean present;
        private final String runId;
        private final String createdAt;
        private final Double totalScore;
        private final boolean comparable;
        private final List<String> differingKeys;
        private final Map<String, Double> dimensionScores;
        private final String reason;

        private BaselineView(boolean present, String runId, String createdAt, Double totalScore,
                             boolean comparable, List<String> differingKeys,
                             Map<String, Double> dimensionScores, String reason) {
            this.present = present;
            this.runId = runId;
            this.createdAt = createdAt;
            this.totalScore = totalScore;
            this.comparable = comparable;
            this.differingKeys = Collections.unmodifiableList(
                    new ArrayList<String>(differingKeys));
            this.dimensionScores = Collections.unmodifiableMap(
                    new LinkedHashMap<String, Double>(dimensionScores));
            this.reason = reason;
        }

        static BaselineView none() {
            return NONE;
        }

        public boolean present() {
            return present;
        }

        public String runId() {
            return runId;
        }

        public String createdAt() {
            return createdAt;
        }

        public Double totalScore() {
            return totalScore;
        }

        public boolean comparable() {
            return comparable;
        }

        /** 九键中不一致的键名，按 §8.2 顺序。 */
        public List<String> differingKeys() {
            return differingKeys;
        }

        /** 基线冻结的维度分（dimension_scores_json）。 */
        public Map<String, Double> dimensionScores() {
            return dimensionScores;
        }

        /** 不可比原因；可比时为 null。 */
        public String reason() {
            return reason;
        }
    }

    private static Integer integer(JSONObject row, String key) {
        Long value = longValue(row, key);
        return value == null ? null : Integer.valueOf(value.intValue());
    }

    /** evidence 收敛：换行压成空格、竖线转义，超长截断并加省略号。 */
    private static String clip(String value, int limit) {
        if (value == null) {
            return null;
        }
        String flat = value.replace('\r', ' ').replace('\n', ' ').replace("|", "\\|").trim();
        if (flat.length() <= limit) {
            return flat;
        }
        return flat.substring(0, limit) + "…";
    }

    /** 便于调试与日志：只暴露 run 与是否找到，不含任何行内容。 */
    @Override
    public String toString() {
        return "EvalReportData{runId=" + runId + ", found=" + runFound
                + ", cases=" + cases.size() + "}";
    }
}
