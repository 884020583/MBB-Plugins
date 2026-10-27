package org.moboxlab.mbb.eval.call;

import com.alibaba.fastjson.JSONObject;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;
import org.moboxlab.mbb.eval.judge.EvalJudgeResult;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * 单次 AI 调用采集器（规格 §4.2、§4.4、§7.5、§13.3）。
 *
 * <p>把 eval-generate 与 judge/calib 的结果映射成 _call 行并写入 EvalStore。
 * 每次尝试（含失败、cached、重试）都要留行；写库失败只记录 WARN，绝不打断评测主流程。</p>
 */
public final class EvalCallRecorder {

    private static final String STAGE_GEN = "gen";
    private static final String STAGE_JUDGE = "judge";
    private static final String STAGE_CALIB = "calib";
    private static final String STATUS_OK = "ok";
    private static final String STATUS_ERROR = "error";

    private final EvalStore store;
    private final PluginLogger logger;

    public EvalCallRecorder(EvalStore store, PluginLogger logger) {
        if (store == null) {
            throw new IllegalArgumentException("EvalStore 不可用，无法采集调用行");
        }
        this.store = store;
        this.logger = logger;
    }

    /**
     * 记录一次 eval-generate 调用。
     *
     * <p>成功行按 §7.5 写齐 profile/model/usage 四列/elapsed_ms/finish_reason/
     * response_chars 与 cached；失败行保留 status/error_type/retryable，但不补 usage
     * 与耗时（§7.6：采集失败不伪造数值）。</p>
     *
     * @return 已映射的行；即使写库失败也返回，便于调用方排查
     */
    public JSONObject recordGenerate(String runId, String caseId, int sampleIndex, int attempt,
            EvalGenerateResult result) {
        if (result == null) {
            throw new IllegalArgumentException("eval-generate 结果不能为空");
        }
        JSONObject row = buildGenerateRow(runId, caseId, sampleIndex, attempt, result);
        write(row);
        return row;
    }

    /** 记录一次 judge 调用；耗时由 runner 以墙钟自计（L2，可信度低于 L1）。 */
    public JSONObject recordJudge(String runId, String caseId, int sampleIndex, int attempt,
            long elapsedMs, EvalJudgeResult result) {
        if (result == null) {
            throw new IllegalArgumentException("judge 结果不能为空");
        }
        JSONObject row = buildJudgeRow(STAGE_JUDGE, runId, caseId, sampleIndex, attempt, elapsedMs, result);
        write(row);
        return row;
    }

    /** 记录一次校准调用；校准样本可以没有 case_id，stage 固定 calib。 */
    public JSONObject recordCalibration(String runId, String caseId, int sampleIndex, int attempt,
            long elapsedMs, EvalJudgeResult result) {
        if (result == null) {
            throw new IllegalArgumentException("校准结果不能为空");
        }
        JSONObject row = buildJudgeRow(STAGE_CALIB, runId, caseId, sampleIndex, attempt, elapsedMs, result);
        write(row);
        return row;
    }

    /**
     * 写入一条调用行。
     *
     * <p>捕获所有运行时异常（含必填列缺失的 IllegalArgumentException），只记 WARN；
     * 返回值供需要感知降级的调用方使用，但不建议中断评测。</p>
     */
    public EvalStore.WriteResult write(JSONObject row) {
        try {
            EvalStore.WriteResult result = store.insertCall(row);
            if (result != EvalStore.WriteResult.APPLIED) {
                warn("调用行写入未生效（" + result + "）：" + describe(row));
            }
            return result;
        } catch (RuntimeException e) {
            warn("调用行写入异常：" + e.getMessage() + "：" + describe(row));
            return EvalStore.WriteResult.NOT_EXECUTED;
        }
    }

    private JSONObject buildGenerateRow(String runId, String caseId, int sampleIndex, int attempt,
            EvalGenerateResult result) {
        JSONObject row = baseRow(runId, caseId, STAGE_GEN, sampleIndex, attempt);
        if (result.status()) {
            row.put("status", STATUS_OK);
            row.put("cached", Integer.valueOf(result.cached() ? 1 : 0));
            row.put("retryable", Integer.valueOf(0));
            putIfPresent(row, "profile", result.profile());
            putIfPresent(row, "model", result.model());
            EvalGenerateResult.Usage usage = result.usage();
            if (usage != null) {
                row.put("usage_prompt", Long.valueOf(usage.promptTokens()));
                row.put("usage_completion", Long.valueOf(usage.completionTokens()));
                row.put("usage_total", Long.valueOf(usage.totalTokens()));
                row.put("usage_cached", Long.valueOf(usage.cachedPromptTokens()));
            }
            row.put("elapsed_ms", Long.valueOf(result.elapsedMs()));
            putIfPresent(row, "finish_reason", result.finishReason());
            String content = result.content();
            row.put("response_chars", Integer.valueOf(content == null ? 0 : content.length()));
        } else {
            row.put("status", STATUS_ERROR);
            row.put("cached", Integer.valueOf(0));
            row.put("retryable", Integer.valueOf(result.retryable() ? 1 : 0));
            putIfPresent(row, "error_type", result.errorType());
        }
        return row;
    }

    private JSONObject buildJudgeRow(String stage, String runId, String caseId, int sampleIndex,
            int attempt, long elapsedMs, EvalJudgeResult result) {
        JSONObject row = baseRow(runId, caseId, stage, sampleIndex, attempt);
        row.put("status", result.isOk() ? STATUS_OK : STATUS_ERROR);
        row.put("cached", Integer.valueOf(EvalJudgeResult.KIND_CACHE.equals(result.errorKind()) ? 1 : 0));
        row.put("retryable", Integer.valueOf(result.isError() && result.retryable() ? 1 : 0));
        if (!result.isOk()) {
            String errorType = result.errorType() == null ? result.errorKind() : result.errorType();
            putIfPresent(row, "error_type", errorType);
        }
        if (elapsedMs >= 0L) {
            row.put("elapsed_ms", Long.valueOf(elapsedMs));
        }
        return row;
    }

    private static JSONObject baseRow(String runId, String caseId, String stage, int sampleIndex,
            int attempt) {
        JSONObject row = new JSONObject();
        row.put("run_id", runId);
        if (caseId != null) {
            row.put("case_id", caseId);
        }
        row.put("stage", stage);
        row.put("sample_index", Integer.valueOf(sampleIndex));
        row.put("attempt", Integer.valueOf(attempt));
        return row;
    }

    private static void putIfPresent(JSONObject row, String key, String value) {
        if (value != null && !value.isEmpty()) {
            row.put(key, value);
        }
    }

    private static String describe(JSONObject row) {
        if (row == null) {
            return "row=null";
        }
        return "run_id=" + row.getString("run_id") + ", stage=" + row.getString("stage")
                + ", case_id=" + row.getString("case_id");
    }

    private void warn(String message) {
        if (logger != null) {
            logger.sendWarn("[MBB-Eval] " + message);
        }
    }
}
