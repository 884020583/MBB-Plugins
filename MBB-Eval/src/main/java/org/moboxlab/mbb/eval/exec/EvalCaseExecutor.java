package org.moboxlab.mbb.eval.exec;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import org.moboxlab.mbb.eval.call.EvalCallInvoker;
import org.moboxlab.mbb.eval.deterministic.EvalKnowledgeResult;
import org.moboxlab.mbb.eval.deterministic.EvalKnowledgeScorer;
import org.moboxlab.mbb.eval.deterministic.EvalStyleResult;
import org.moboxlab.mbb.eval.deterministic.EvalStyleScorer;
import org.moboxlab.mbb.eval.gateway.EvalGenerateRequest;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;
import org.moboxlab.mbb.eval.judge.EvalJudgeRequest;
import org.moboxlab.mbb.eval.judge.EvalJudgeResult;
import org.moboxlab.mbb.eval.log.EvalLog;
import org.moboxlab.mbb.eval.runner.EvalRunStopPoint;
import org.moboxlab.mbb.eval.score.EvalCaseScore;
import org.moboxlab.mbb.eval.store.EvalStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 单 case 评测执行器（规格 §6.4–§6.7、§10.2、§10.6.1、§13.1.3）。
 *
 * <p>语义：</p>
 * <ol>
 *   <li>{@code knowledge} / {@code style} 维本地确定性判分，只算一次，不发起任何 AI 调用；</li>
 *   <li>其余维度（persona / boundary / memory）走 eval-generate + judge，按 k 采样后聚合：
 *       只取 ok 样本的 label 排序取中位数，不取浮点均值（§6.6、§6.7.2）；</li>
 *   <li>所有 eval-generate 都经 {@link EvalCallInvoker}，由它做预算原子记账、重试 / 缓存重采，
 *       并为每次物理调用落 _call 行；</li>
 *   <li>出参失败按 §10.6.1：disabled 停止整 run；busy / timeout 由调用器退避重试；
 *       invalid-param / history-too-long / profile-invalid / ai-missing / internal 记样本 error，
 *       不重试；</li>
 *   <li>写结果行前过 {@link EvalRunStopPoint#beforeCaseWrite()}，每次 AI 调用前过
 *       {@link EvalRunStopPoint#beforeCall()}；命中即返回中止结果且不写行。</li>
 * </ol>
 */
public final class EvalCaseExecutor {

    public static final String DIM_PERSONA = "persona";
    public static final String DIM_BOUNDARY = "boundary";
    public static final String DIM_MEMORY = "memory";
    public static final String DIM_KNOWLEDGE = "knowledge";
    public static final String DIM_STYLE = "style";

    /** 结果行 detail_json 的字符上限；超过先丢 raw_content，再降级为空对象并记 WARN。 */
    private static final int MAX_DETAIL_JSON_CHARS = 60000;
    /** 单条样本 raw_content 的落库上限，避免把整段长回复写进 detail_json。 */
    private static final int MAX_RAW_CONTENT_CHARS = 2000;
    /** 评测对话里模拟用户的固定 ID 基数。 */
    private static final long USER_ID_BASE = 10000L;

    /**
     * 执行一个 case。
     *
     * @param context 已装配好的依赖
     * @return 终态、分数、调用次数与中止信息；不会返回 null
     */
    public EvalCaseResult execute(EvalCaseContext context) {
        if (context == null) {
            throw new IllegalArgumentException("case 上下文不能为空");
        }
        long start = context.clock().currentTimeMillis();
        String dimension = context.dimension();
        try {
            if (DIM_KNOWLEDGE.equals(dimension)) {
                return executeKnowledge(context, start);
            }
            if (DIM_STYLE.equals(dimension)) {
                return executeStyle(context, start);
            }
            if (DIM_PERSONA.equals(dimension) || DIM_BOUNDARY.equals(dimension)
                    || DIM_MEMORY.equals(dimension)) {
                return executeJudge(context, start);
            }
            EvalLog.warn(context.logger(), "未知维度，按 invalid 落库：case=" + context.caseId()
                    + " 维度=" + dimension);
            return writeRow(context, start, EvalCaseScore.invalid(context.caseId(), dimension),
                    1, 1, 0, 0L, 0L, 0L, 0, emptyDetail(), "未知维度：" + dimension);
        } catch (RuntimeException e) {
            EvalLog.warn(context.logger(), "case 执行异常，按 error 落库：case=" + context.caseId()
                    + " 维度=" + dimension + " 异常=" + e.getClass().getSimpleName());
            return writeRow(context, start, EvalCaseScore.error(context.caseId(), dimension),
                    1, 1, 0, 0L, 0L, 0L, 0, emptyDetail(), "执行异常：" + e.getClass().getSimpleName());
        }
    }

    /** 知识维：本地判分一次，零 AI 调用（§6.4）。 */
    private EvalCaseResult executeKnowledge(EvalCaseContext context, long start) {
        EvalKnowledgeResult result;
        try {
            JSONObject config = context.deterministicConfig().getJSONObject("knowledge");
            result = new EvalKnowledgeScorer(config)
                    .score(context.caseJson().getJSONObject("knowledge"), context.content());
        } catch (RuntimeException e) {
            EvalLog.warn(context.logger(), "知识维判分配置不可用，按 invalid 落库：case="
                    + context.caseId() + " 原因=" + e.getClass().getSimpleName());
            return writeRow(context, start, EvalCaseScore.invalid(context.caseId(), DIM_KNOWLEDGE),
                    1, 1, 0, 0L, 0L, 0L, 0, emptyDetail(), "知识维配置不可用");
        }
        double score = clamp01(result.caseScore());
        JSONObject detail = new JSONObject(true);
        detail.put("knowledge", result.toJson());
        String evidence = "命中 " + hitCount(result) + "/" + result.expectedTotal()
                + "，negativeHit=" + result.negativeHit();
        EvalCaseScore caseScore = EvalCaseScore.fromDeterministic(context.caseId(), DIM_KNOWLEDGE, score);
        return writeRow(context, start, caseScore, 1, 1, 0, 0L, 0L, 0L, 0, detail, evidence);
    }

    /** 风格维：本地判分一次，零 AI 调用（§6.5）。 */
    private EvalCaseResult executeStyle(EvalCaseContext context, long start) {
        EvalStyleResult result;
        try {
            JSONObject config = context.deterministicConfig().getJSONObject("style");
            result = new EvalStyleScorer(config)
                    .score(context.caseJson().getJSONObject("style"), context.content());
        } catch (RuntimeException e) {
            EvalLog.warn(context.logger(), "风格维判分配置不可用，按 invalid 落库：case="
                    + context.caseId() + " 原因=" + e.getClass().getSimpleName());
            return writeRow(context, start, EvalCaseScore.invalid(context.caseId(), DIM_STYLE),
                    1, 1, 0, 0L, 0L, 0L, 0, emptyDetail(), "风格维配置不可用");
        }
        double score = clamp01(result.caseScore());
        JSONObject detail = new JSONObject(true);
        detail.put("style", result.toJson());
        int passed = 0;
        for (EvalStyleResult.Soft soft : result.soft()) {
            if (soft.passed()) {
                passed++;
            }
        }
        String evidence = "硬项失败 " + result.hardFails().size() + " 项，软项通过 "
                + passed + "/" + result.soft().size() + "，emoji=" + result.emojiCount();
        EvalCaseScore caseScore = EvalCaseScore.fromDeterministic(context.caseId(), DIM_STYLE, score);
        return writeRow(context, start, caseScore, 1, 1, 0, 0L, 0L, 0L, 0, detail, evidence);
    }

    /**
     * LLM 维：每题只生成一次，再按 k 采样判分并聚合中位数（§6.7）。
     *
     * <p>生成侧按 §9.5 计一次调用；judge 的采样次数 k 由调用方按维度注入（boundary=3，其余=1），
     * 确定性维不经过本方法。生成失败时按 k 个 error 样本落库（调用数仍是实际发生的次数）。</p>
     */
    private EvalCaseResult executeJudge(EvalCaseContext context, long start) {
        EvalGenerateRequest prototype = generateRequest(context, 0, 0);
        EvalGenerateRequest.Problem problem = prototype.validate();
        if (problem != null) {
            EvalLog.warn(context.logger(), "eval-generate 入参校验失败，按 invalid 落库：case="
                    + context.caseId() + " errorType=" + problem.errorType());
            return writeRow(context, start, EvalCaseScore.invalid(context.caseId(), context.dimension()),
                    1, 1, 0, 0L, 0L, 0L, 0, emptyDetail(), "入参校验失败：" + problem.errorType());
        }

        Collect collect = new Collect();
        int k = context.sampleCount();
        EvalRunStopPoint.Decision stop = context.stopPoint().beforeCall();
        if (stop.stop()) {
            return stopped(context, start, stop, collect.calls);
        }
        EvalCallInvoker.GenerateOutcome generated = context.invoker().generate(
                context.runId(), context.caseId(), 0,
                new EvalCallInvoker.GenerateCall() {
                    @Override
                    public EvalGenerateResult call(EvalCallInvoker.GenerateTarget target) {
                        return context.generatePort().generate(
                                generateRequest(context, target.sessionSampleIndex(), target.attempt()));
                    }
                });
        collect.calls += generated.calls();
        collect.cached += generated.cachedHits();
        if (generated.isBudgetStopped()) {
            return aborted(context, start, collect.calls, generated.abortReason(), "预算触顶");
        }
        EvalGenerateResult generatedResult = generated.result();
        if (generated.kind() != EvalCallInvoker.GenerateKind.OK || generatedResult == null) {
            String errorType = generatedResult == null ? EvalGenerateResult.ERROR_INTERNAL
                    : generatedResult.errorType();
            if (EvalGenerateResult.ERROR_DISABLED.equals(errorType)) {
                EvalLog.error(context.logger(), "eval-generate 返回 disabled，停止整 run：case="
                        + context.caseId());
                return aborted(context, start, collect.calls, EvalGenerateResult.ERROR_DISABLED,
                        "接口已关闭");
            }
            EvalLog.errorOf(context.logger(), context.caseId(), errorType,
                    context.clock().currentTimeMillis() - start);
            // 生成只发生一次；失败按 k 个 error 样本记录，聚合为 error 而不是折算 0 分。
            for (int i = 0; i < k; i++) {
                collect.samples.add(Sample.error(errorType));
            }
            EvalCaseScore errorScore = aggregate(context, collect, k);
            JSONObject errorDetail = detailOf(collect);
            return writeRow(context, start, errorScore, k, collect.samples.size(), collect.cached,
                    collect.tokensPrompt, collect.tokensCompletion, collect.tokensTotal, collect.calls,
                    errorDetail, aggregateEvidence(collect, k));
        }
        if (!generatedResult.cached()) {
            collect.addUsage(generatedResult.usage());
        }
        String veto = vetoReason(generatedResult);
        if (veto != null) {
            collect.samples.add(Sample.vetoed(veto, generatedResult.finishReason()));
        } else {
            String content = generatedResult.content();
            for (int i = 0; i < k; i++) {
                stop = context.stopPoint().beforeCall();
                if (stop.stop()) {
                    return stopped(context, start, stop, collect.calls);
                }
                final int judgeSampleIndex = i;
                EvalCallInvoker.JudgeOutcome judged = context.invoker().judge(
                        context.runId(), context.caseId(), judgeSampleIndex, 0,
                        new EvalCallInvoker.JudgeCall() {
                            @Override
                            public EvalJudgeResult call() {
                                return context.judgePort().judge(judgeInput(context, judgeSampleIndex,
                                        content));
                            }
                        });
                collect.calls++;
                if (judged.isBudgetStopped()) {
                    return aborted(context, start, collect.calls, judged.abortReason(), "预算触顶");
                }
                if (context.judgePort().aborted()) {
                    String reason = context.judgePort().abortReason();
                    EvalLog.error(context.logger(), "判分熔断，停止整 run：case=" + context.caseId()
                            + " 原因=" + reason);
                    return aborted(context, start, collect.calls,
                            reason == null ? EvalStore.ABORT_REASON_AI_UNAVAILABLE : reason, "判分熔断");
                }
                EvalJudgeResult judgeResult = judged.result();
                if (judgeResult == null) {
                    judgeResult = EvalJudgeResult.error(context.dimension(), EvalJudgeResult.KIND_TRANSPORT,
                            EvalGenerateResult.ERROR_INTERNAL, false, 0, null, "判分返回 null", null, 0);
                }
                if (judgeResult.isOk()) {
                    collect.samples.add(Sample.ok(judgeResult.label(), judgeResult.evidence(),
                            content, generatedResult.finishReason(), generatedResult.cached()));
                } else if (judgeResult.isInvalid()) {
                    collect.samples.add(Sample.invalid(judgeResult.errorKind(),
                            generatedResult.finishReason()));
                } else {
                    collect.samples.add(Sample.error(judgeResult.errorType()));
                }
            }
        }

        EvalCaseScore caseScore = aggregate(context, collect, k);
        JSONObject detail = detailOf(collect);
        return writeRow(context, start, caseScore, k, collect.samples.size(), collect.cached,
                collect.tokensPrompt, collect.tokensCompletion, collect.tokensTotal, collect.calls,
                detail, aggregateEvidence(collect, k));
    }

    /** 聚合：任一硬项 veto 判 vetoed；否则取 ok 样本 label 的中位数（§6.6、§6.7.2）。 */
    private EvalCaseScore aggregate(EvalCaseContext context, Collect collect, int k) {
        for (Sample sample : collect.samples) {
            if (sample.vetoed) {
                return EvalCaseScore.vetoed(context.caseId(), context.dimension());
            }
        }
        List<Integer> labels = new ArrayList<Integer>();
        for (Sample sample : collect.samples) {
            if (sample.ok) {
                labels.add(Integer.valueOf(sample.label));
            }
        }
        if (!labels.isEmpty()) {
            Collections.sort(labels);
            int median = labels.get((labels.size() - 1) / 2).intValue();
            if (labels.size() < k) {
                EvalLog.warn(context.logger(), "有效样本不足：case=" + context.caseId()
                        + " ok=" + labels.size() + "/" + k + "，按现有样本取中位数");
            }
            return EvalCaseScore.ok(context.caseId(), context.dimension(),
                    median / 2.0D);
        }
        for (Sample sample : collect.samples) {
            if (sample.error) {
                return EvalCaseScore.error(context.caseId(), context.dimension());
            }
        }
        return EvalCaseScore.invalid(context.caseId(), context.dimension());
    }

    /** 写库前的统一出口：先过停止点，命中则返回中止结果且不写行。 */
    private EvalCaseResult writeRow(EvalCaseContext context, long start, EvalCaseScore caseScore,
            int kUsed, int sampleCount, int cachedCount, long tokensPrompt, long tokensCompletion,
            long tokensTotal, int calls, JSONObject detail, String evidence) {
        EvalRunStopPoint.Decision stop = context.stopPoint().beforeCaseWrite();
        if (stop.stop()) {
            return stopped(context, start, stop, calls);
        }
        String statusText = caseScore.status().name().toLowerCase(Locale.CHINA);
        String detailText = detailText(context, detail);
        JSONObject row = new JSONObject(true);
        row.put("run_id", context.runId());
        row.put("case_id", context.caseId());
        row.put("dimension", context.dimension());
        row.put("status", statusText);
        if (caseScore.caseScore() != null) {
            row.put("score", Double.valueOf(caseScore.caseScore().doubleValue() * 100.0D));
        }
        row.put("max_score", Double.valueOf(100.0D));
        row.put("label", labelOf(caseScore));
        row.put("scale", scaleOf(context.dimension()));
        row.put("k_used", Integer.valueOf(kUsed));
        row.put("sample_count", Integer.valueOf(sampleCount));
        row.put("cached_count", Integer.valueOf(cachedCount));
        row.put("tokens_prompt", Integer.valueOf((int) Math.min(Integer.MAX_VALUE, tokensPrompt)));
        row.put("tokens_completion", Integer.valueOf((int) Math.min(Integer.MAX_VALUE, tokensCompletion)));
        row.put("tokens_total", Integer.valueOf((int) Math.min(Integer.MAX_VALUE, tokensTotal)));
        long durationMs = Math.max(0L, context.clock().currentTimeMillis() - start);
        row.put("duration_ms", Long.valueOf(durationMs));
        row.put("evidence", evidence);
        row.put("detail_json", detailText);

        EvalStore.WriteResult writeResult = context.store().insertCaseResult(row);
        if (writeResult != EvalStore.WriteResult.APPLIED) {
            EvalLog.warn(context.logger(), "用例结果未写入（case=" + context.caseId() + "，status="
                    + statusText + "，writeResult=" + writeResult + "）");
        }
        EvalLog.info(context.logger(), "case 完成：case=" + context.caseId()
                + " 维度=" + context.dimension() + " 状态=" + statusText
                + " 调用=" + calls + " 耗时=" + durationMs + "ms");
        return new EvalCaseResult(context.caseId(), context.dimension(), caseScore.status(),
                caseScore.caseScore(), labelOf(caseScore), scaleOf(context.dimension()), evidence,
                calls, false, null, false, writeResult, durationMs);
    }

    private EvalCaseResult stopped(EvalCaseContext context, long start,
            EvalRunStopPoint.Decision stop, int calls) {
        long durationMs = Math.max(0L, context.clock().currentTimeMillis() - start);
        EvalLog.info(context.logger(), "case 中止（未写行）：case=" + context.caseId()
                + " 原因=" + stop.reason() + " 调用=" + calls);
        return new EvalCaseResult(context.caseId(), context.dimension(), null, null, null, null,
                null, calls, true, stop.reason(), true, null, durationMs);
    }

    private EvalCaseResult aborted(EvalCaseContext context, long start, int calls, String reason,
            String message) {
        long durationMs = Math.max(0L, context.clock().currentTimeMillis() - start);
        EvalLog.warn(context.logger(), "case 中止（未写行）：case=" + context.caseId()
                + " 原因=" + reason + "，" + message);
        return new EvalCaseResult(context.caseId(), context.dimension(), null, null, null, null,
                null, calls, true, reason, true, null, durationMs);
    }

    private EvalGenerateRequest generateRequest(EvalCaseContext context, int sampleIndex, int attempt) {
        EvalGenerateRequest request = new EvalGenerateRequest(context.runId(), context.caseId())
                .sampleIndex(sampleIndex)
                .attempt(attempt)
                .message(new EvalGenerateRequest.Message(USER_ID_BASE, "评测用户",
                        context.caseJson().getString("prompt")));
        JSONArray history = context.caseJson().getJSONArray("history");
        if (history != null && !history.isEmpty()) {
            List<EvalGenerateRequest.HistoryMessage> messages =
                    new ArrayList<EvalGenerateRequest.HistoryMessage>();
            for (int i = 0; i < history.size(); i++) {
                JSONObject turn = history.getJSONObject(i);
                if (turn == null) {
                    continue;
                }
                messages.add(new EvalGenerateRequest.HistoryMessage(turn.getString("speaker"),
                        USER_ID_BASE + i, turn.getString("userName"), turn.getString("text")));
            }
            request.history(messages);
        }
        if (context.generateProfile() != null && !context.generateProfile().isEmpty()) {
            request.profile(context.generateProfile());
        }
        if (context.generateTimeoutSeconds() > 0) {
            request.timeoutSeconds(context.generateTimeoutSeconds());
        }
        return request;
    }

    private EvalCaseJudgeInput judgeInput(EvalCaseContext context, int sampleIndex, String replyContent) {
        List<EvalJudgeRequest.Turn> turns = new ArrayList<EvalJudgeRequest.Turn>();
        JSONArray history = context.caseJson().getJSONArray("history");
        if (history != null) {
            for (int i = 0; i < history.size(); i++) {
                JSONObject turn = history.getJSONObject(i);
                if (turn == null) {
                    continue;
                }
                turns.add(new EvalJudgeRequest.Turn(turn.getString("speaker"),
                        turn.getString("userName"), turn.getString("text")));
            }
        }
        List<Integer> evidenceTurns = new ArrayList<Integer>();
        if (DIM_MEMORY.equals(context.dimension())) {
            JSONObject memory = context.caseJson().getJSONObject("memory");
            JSONArray array = memory == null ? null : memory.getJSONArray("evidenceTurns");
            if (array != null) {
                for (int i = 0; i < array.size(); i++) {
                    Integer index = array.getInteger(i);
                    if (index != null && index.intValue() >= 0 && index.intValue() < turns.size()) {
                        evidenceTurns.add(index);
                    }
                }
            }
        }
        return new EvalCaseJudgeInput(context.runId(), context.caseId(), sampleIndex, 0,
                context.dimension(), context.personaSummary(), turns, evidenceTurns,
                context.caseJson().getString("prompt"), context.caseJson().getString("focus"),
                replyContent, Collections.<String>emptyList(), context.promptVersion());
    }

    /** §6.3.5 / J10 的被测侧硬项：空回复、主动跳过、生成被 length 截断都判 veto。 */
    private static String vetoReason(EvalGenerateResult result) {
        String content = result.content();
        if (content == null || content.trim().isEmpty()) {
            return "empty";
        }
        String trimmed = content.trim();
        if (result.skip() || "<SKIP>".equalsIgnoreCase(trimmed)) {
            return "skip";
        }
        if (result.finishReason() != null
                && "length".equalsIgnoreCase(result.finishReason())) {
            return "truncated";
        }
        return null;
    }

    private static String detailText(EvalCaseContext context, JSONObject detail) {
        String text = JSON.toJSONString(detail);
        if (text.length() <= MAX_DETAIL_JSON_CHARS) {
            return text;
        }
        JSONArray samples = detail.getJSONArray("samples");
        if (samples != null) {
            for (int i = 0; i < samples.size(); i++) {
                JSONObject sample = samples.getJSONObject(i);
                if (sample != null) {
                    sample.remove("raw_content");
                }
            }
            text = JSON.toJSONString(detail);
            if (text.length() <= MAX_DETAIL_JSON_CHARS) {
                EvalLog.warn(context.logger(), "detail_json 过长，已丢弃 raw_content：case="
                        + context.caseId());
                return text;
            }
        }
        EvalLog.warn(context.logger(), "detail_json 仍过长，降级为空对象：case=" + context.caseId());
        return JSON.toJSONString(emptyDetail());
    }

    private static JSONObject detailOf(Collect collect) {
        JSONObject detail = new JSONObject(true);
        JSONArray samples = new JSONArray();
        for (Sample sample : collect.samples) {
            JSONObject row = new JSONObject(true);
            row.put("status", sample.status);
            if (sample.ok) {
                row.put("label", Integer.valueOf(sample.label));
            }
            row.put("evidence", sample.evidence);
            row.put("raw_content", truncate(sample.rawContent));
            row.put("finish_reason", sample.finishReason);
            row.put("cached", Boolean.valueOf(sample.cached));
            if (sample.reason != null) {
                row.put("reason", sample.reason);
            }
            samples.add(row);
        }
        detail.put("samples", samples);
        return detail;
    }

    private static String aggregateEvidence(Collect collect, int k) {
        List<Integer> labels = new ArrayList<Integer>();
        int veto = 0;
        int errors = 0;
        for (Sample sample : collect.samples) {
            if (sample.ok) {
                labels.add(Integer.valueOf(sample.label));
            } else if (sample.vetoed) {
                veto++;
            } else {
                errors++;
            }
        }
        if (veto > 0) {
            return "存在硬项 veto " + veto + " 个样本，题分记 0";
        }
        if (labels.isEmpty()) {
            return "无有效样本（error=" + errors + "，k=" + k + "）";
        }
        Collections.sort(labels);
        int median = labels.get((labels.size() - 1) / 2).intValue();
        int same = 1;
        int maxSame = 1;
        for (int i = 1; i < labels.size(); i++) {
            if (labels.get(i).intValue() == labels.get(i - 1).intValue()) {
                same++;
            } else {
                same = 1;
            }
            maxSame = Math.max(maxSame, same);
        }
        return "中位档=" + median + "，全同 " + maxSame + "/" + labels.size()
                + "，ok 样本 " + labels.size() + "/" + k;
    }

    private static String labelOf(EvalCaseScore caseScore) {
        if (caseScore.status() == EvalCaseScore.Status.VETOED) {
            return "veto";
        }
        if (caseScore.status() != EvalCaseScore.Status.OK || caseScore.caseScore() == null) {
            return null;
        }
        if (DIM_KNOWLEDGE.equals(caseScore.dimensionId()) || DIM_STYLE.equals(caseScore.dimensionId())) {
            return caseScore.caseScore().doubleValue() >= 1.0D ? "hit" : "miss";
        }
        int label = (int) Math.round(caseScore.caseScore().doubleValue() * 2.0D);
        return String.valueOf(label);
    }

    private static String scaleOf(String dimension) {
        if (DIM_KNOWLEDGE.equals(dimension) || DIM_STYLE.equals(dimension)) {
            return "0/1";
        }
        return "0/1/2";
    }

    private static JSONObject emptyDetail() {
        JSONObject detail = new JSONObject(true);
        detail.put("samples", new JSONArray());
        return detail;
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_RAW_CONTENT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_RAW_CONTENT_CHARS);
    }

    private static double clamp01(double value) {
        if (value < 0.0D) {
            return 0.0D;
        }
        if (value > 1.0D) {
            return 1.0D;
        }
        return value;
    }

    private static int hitCount(EvalKnowledgeResult result) {
        int count = 0;
        for (EvalKnowledgeResult.Hit hit : result.hits()) {
            if (hit.matched()) {
                count++;
            }
        }
        return count;
    }

    /** 采样过程中的累加器。 */
    private static final class Collect {
        private final List<Sample> samples = new ArrayList<Sample>();
        private int calls;
        private int cached;
        private long tokensPrompt;
        private long tokensCompletion;
        private long tokensTotal;

        private void addUsage(EvalGenerateResult.Usage usage) {
            if (usage == null) {
                return;
            }
            tokensPrompt += usage.promptTokens();
            tokensCompletion += usage.completionTokens();
            tokensTotal += usage.totalTokens();
        }
    }

    /** 单样本事实：成功样本带档位与证据，失败样本只带错误类。 */
    private static final class Sample {
        private final boolean ok;
        private final boolean vetoed;
        private final boolean error;
        private final String status;
        private final int label;
        private final String evidence;
        private final String rawContent;
        private final String finishReason;
        private final boolean cached;
        private final String reason;

        private Sample(boolean ok, boolean vetoed, boolean error, String status, int label,
                String evidence, String rawContent, String finishReason, boolean cached, String reason) {
            this.ok = ok;
            this.vetoed = vetoed;
            this.error = error;
            this.status = status;
            this.label = label;
            this.evidence = evidence;
            this.rawContent = rawContent;
            this.finishReason = finishReason;
            this.cached = cached;
            this.reason = reason;
        }

        private static Sample ok(int label, String evidence, String rawContent,
                String finishReason, boolean cached) {
            return new Sample(true, false, false, "ok", label, evidence, rawContent, finishReason,
                    cached, null);
        }

        private static Sample vetoed(String reason, String finishReason) {
            return new Sample(false, true, false, "vetoed", -1, null, null, finishReason, false, reason);
        }

        private static Sample invalid(String errorKind, String finishReason) {
            return new Sample(false, false, false, "invalid", -1, null, null, finishReason, false,
                    errorKind);
        }

        private static Sample error(String errorType) {
            return new Sample(false, false, true, "error", -1, null, null, null, false, errorType);
        }
    }
}
