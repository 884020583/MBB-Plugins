package org.moboxlab.mbb.eval.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.alibaba.fastjson.JSONObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * 指标计算纯函数用例（E-1：规格 §7.2–§7.4、§15.5 的 E2/E3/E5/E6/E7/E8/E9）。
 *
 * <p>所有期望值均按规格口径手工算出后冻结在用例里，不调用被测实现自证。
 * 行集字段名取 §4.2 的列名（snake_case）。</p>
 */
public class EvalMetricsTest {

    private final EvalMetricsService service = new EvalMetricsService();

    // ===== §7.2 分位与 n 阈值（E2、E3） =====

    @Test
    public void latencyShouldReportP95AtSixtySamples() {
        EvalMetrics metrics = service.compute(latencyRows(60), new ArrayList<JSONObject>());

        assertEquals(60L, metrics.latency().n());
        assertEquals(30.5, metrics.latency().mean(), 0.0001);
        assertEquals(30.0, metrics.latency().p50(), 0.0001);
        assertEquals(54.0, metrics.latency().p90(), 0.0001);
        assertEquals(57.0, metrics.latency().p95(), 0.0001);
        assertEquals(60.0, metrics.latency().max(), 0.0001);
        assertNull(metrics.latency().reasonCode());
    }

    @Test
    public void latencyShouldOmitP95AtFiftyNineSamples() {
        EvalMetrics metrics = service.compute(latencyRows(59), new ArrayList<JSONObject>());

        assertEquals(59L, metrics.latency().n());
        assertEquals(30.0, metrics.latency().mean(), 0.0001);
        assertEquals(30.0, metrics.latency().p50(), 0.0001);
        assertEquals(54.0, metrics.latency().p90(), 0.0001);
        assertNull(metrics.latency().p95());
        assertEquals(59.0, metrics.latency().max(), 0.0001);
        assertEquals("SAMPLE_BELOW_60", metrics.latency().reasonCode());
    }

    @Test
    public void latencyShouldOnlyReportMeanAtNineteenSamples() {
        EvalMetrics metrics = service.compute(latencyRows(19), new ArrayList<JSONObject>());

        assertEquals(19L, metrics.latency().n());
        assertEquals(10.0, metrics.latency().mean(), 0.0001);
        assertNull(metrics.latency().p50());
        assertNull(metrics.latency().p90());
        assertNull(metrics.latency().p95());
        assertNull(metrics.latency().max());
        assertEquals("SAMPLE_BELOW_20", metrics.latency().reasonCode());
    }

    @Test
    public void latencyShouldStartQuantilesAtTwentySamples() {
        EvalMetrics metrics = service.compute(latencyRows(20), new ArrayList<JSONObject>());

        assertEquals(20L, metrics.latency().n());
        assertEquals(10.5, metrics.latency().mean(), 0.0001);
        assertEquals(10.0, metrics.latency().p50(), 0.0001);
        assertEquals(18.0, metrics.latency().p90(), 0.0001);
        assertNull(metrics.latency().p95());
        assertEquals(20.0, metrics.latency().max(), 0.0001);
        assertEquals("SAMPLE_BELOW_60", metrics.latency().reasonCode());
    }

    @Test
    public void latencyShouldExcludeErrorCachedAndJudgedCalls() {
        List<JSONObject> calls = new ArrayList<JSONObject>();
        calls.add(call("gen", "ok", 0, 0, "case-1", 0, 1000L));
        calls.add(call("gen", "ok", 0, 0, "case-2", 0, 3000L));
        calls.add(call("gen", "error", 0, 0, "case-3", 0, 9999L));
        calls.add(call("gen", "ok", 1, 0, "case-4", 0, 8888L));
        calls.add(call("judge", "ok", 0, 0, "case-5", 0, 7777L));
        calls.add(call("gen", "ok", 0, 0, "case-6", 0, null));

        EvalMetrics metrics = service.compute(calls, new ArrayList<JSONObject>());

        assertEquals(2L, metrics.latency().n());
        assertEquals(2000.0, metrics.latency().mean(), 0.0001);
        assertEquals(1, metrics.latency().excludedError());
        assertEquals(1, metrics.latency().excludedCached());
        assertEquals(1, metrics.latency().excludedMissingElapsed());
    }

    @Test
    public void latencyShouldTreatMissingCachedAsNotCached() {
        List<JSONObject> calls = new ArrayList<JSONObject>();
        calls.add(call("gen", "ok", null, 0, "case-1", 0, 1234L));

        EvalMetrics metrics = service.compute(calls, new ArrayList<JSONObject>());

        assertEquals(1L, metrics.latency().n());
        assertEquals(1234.0, metrics.latency().mean(), 0.0001);
    }

    @Test
    public void latencyShouldReportNoSamplesForEmptySet() {
        EvalMetrics metrics = service.compute(new ArrayList<JSONObject>(), new ArrayList<JSONObject>());

        assertEquals(0L, metrics.latency().n());
        assertNull(metrics.latency().mean());
        assertNull(metrics.latency().p50());
        assertNull(metrics.latency().p90());
        assertNull(metrics.latency().p95());
        assertNull(metrics.latency().max());
        assertEquals("NO_SAMPLES", metrics.latency().reasonCode());
        assertNotNull(metrics.toJson());
    }

    // ===== §7.3–§7.4 调用、样本、缓存、错误（E5、E6、E8、E9） =====

    @Test
    public void callMetricsShouldCountEveryAttempt() {
        EvalMetrics metrics = service.compute(datasetCalls(), datasetCases());

        assertEquals(10L, metrics.calls().total());
        assertEquals(7L, metrics.calls().ok());
        assertEquals(3L, metrics.calls().error());
        assertEquals(0.7, metrics.calls().successRate(), 0.000000001);
        assertEquals(7L, metrics.calls().byStage().get("gen").longValue());
        assertEquals(2L, metrics.calls().byStage().get("judge").longValue());
        assertEquals(1L, metrics.calls().byStage().get("calib").longValue());
        assertEquals(2L, metrics.calls().retryCount());
    }

    @Test
    public void cacheHitRateShouldUseAllCalls() {
        EvalMetrics metrics = service.compute(datasetCalls(), datasetCases());

        assertEquals(1L, metrics.calls().cached());
        assertEquals(0.1, metrics.calls().cacheHitRate(), 0.000000001);
    }

    @Test
    public void errorTypeDistributionShouldUseOnlyErrorRows() {
        EvalMetrics metrics = service.compute(datasetCalls(), datasetCases());

        assertEquals(1L, metrics.calls().errorTypes().get("busy").longValue());
        assertEquals(1L, metrics.calls().errorTypes().get("timeout").longValue());
        assertEquals(1L, metrics.calls().errorTypes().get("internal").longValue());
        assertEquals(0L, metrics.calls().errorTypes().get("disabled").longValue());
        assertEquals(0L, metrics.calls().errorTypes().get("invalid-param").longValue());
        assertEquals(0L, metrics.calls().errorTypes().get("history-too-long").longValue());
        assertEquals(0L, metrics.calls().errorTypes().get("profile-invalid").longValue());
        assertEquals(0L, metrics.calls().errorTypes().get("ai-missing").longValue());
        assertEquals(1.0 / 3.0, metrics.calls().errorTypeRatios().get("busy"), 0.000000001);
        assertEquals(0.0, metrics.calls().errorTypeRatios().get("disabled"), 0.000000001);
    }

    @Test
    public void sampleMetricsShouldUseFinalAttemptAndExcludeCachedSamples() {
        EvalMetrics metrics = service.compute(datasetCalls(), datasetCases());

        assertEquals(4L, metrics.samples().total());
        assertEquals(3L, metrics.samples().ok());
        assertEquals(1L, metrics.samples().failed());
        assertEquals(0.75, metrics.samples().successRate(), 0.000000001);
        assertEquals(2L, metrics.samples().retried());
        assertEquals(0.5, metrics.samples().retryRate(), 0.000000001);
        assertEquals(1.5, metrics.samples().averageAttempts(), 0.000000001);
    }

    @Test
    public void datasetLatencyShouldExcludeErrorsAndCachedCalls() {
        EvalMetrics metrics = service.compute(datasetCalls(), datasetCases());

        assertEquals(4L, metrics.latency().n());
        assertEquals(2750.0, metrics.latency().mean(), 0.0001);
        assertNull(metrics.latency().p50());
        assertEquals(2, metrics.latency().excludedError());
        assertEquals(1, metrics.latency().excludedCached());
    }

    @Test
    public void tokenTotalsShouldExcludeCachedCallsAndSkipNullFields() {
        EvalMetrics metrics = service.compute(datasetCalls(), datasetCases());

        assertEquals(5L, metrics.tokens().prompt().n());
        assertEquals(73L, metrics.tokens().prompt().sum());
        assertEquals(14.6, metrics.tokens().prompt().mean(), 0.000000001);

        assertEquals(6L, metrics.tokens().completion().n());
        assertEquals(39L, metrics.tokens().completion().sum());
        assertEquals(6.5, metrics.tokens().completion().mean(), 0.000000001);

        assertEquals(5L, metrics.tokens().total().n());
        assertEquals(108L, metrics.tokens().total().sum());
        assertEquals(21.6, metrics.tokens().total().mean(), 0.000000001);

        assertEquals(5L, metrics.tokens().cached().n());
        assertEquals(13L, metrics.tokens().cached().sum());
        assertEquals(2.6, metrics.tokens().cached().mean(), 0.000000001);

        assertEquals(1L, metrics.tokens().cachedCallsExcluded());
    }

    @Test
    public void budgetRatioShouldUseHardCapOneHundred() {
        EvalMetrics metrics = service.compute(datasetCalls(), datasetCases());

        assertEquals(10L, metrics.budget().used());
        assertEquals(100L, metrics.budget().budget());
        assertEquals(0.1, metrics.budget().ratio(), 0.000000001);
    }

    @Test
    public void caseStatsShouldKeepInvalidSeparateFromError() {
        EvalMetrics metrics = service.compute(datasetCalls(), datasetCases());

        assertEquals(5L, metrics.cases().total());
        assertEquals(2L, metrics.cases().ok());
        assertEquals(1L, metrics.cases().vetoed());
        assertEquals(1L, metrics.cases().error());
        assertEquals(1L, metrics.cases().invalid());
        assertEquals(3L, metrics.cases().valid());
        assertEquals(0.2, metrics.cases().errorRate(), 0.000000001);
        assertEquals(0.2, metrics.cases().invalidRate(), 0.000000001);
        assertEquals(0.4, metrics.cases().nonOkRate(), 0.000000001);
    }

    // ===== 空集与全失败 =====

    @Test
    public void emptySetShouldYieldNullRates() {
        EvalMetrics metrics = service.compute(null, null);

        assertEquals(0L, metrics.calls().total());
        assertNull(metrics.calls().successRate());
        assertNull(metrics.calls().cacheHitRate());
        assertEquals(0L, metrics.calls().retryCount());

        assertEquals(0L, metrics.samples().total());
        assertNull(metrics.samples().successRate());
        assertNull(metrics.samples().retryRate());
        assertNull(metrics.samples().averageAttempts());

        assertEquals(0L, metrics.cases().total());
        assertNull(metrics.cases().errorRate());
        assertNull(metrics.cases().invalidRate());
        assertNull(metrics.cases().nonOkRate());

        assertEquals(0L, metrics.tokens().prompt().n());
        assertEquals(0L, metrics.tokens().prompt().sum());
        assertNull(metrics.tokens().prompt().mean());

        assertEquals(0L, metrics.budget().used());
        assertEquals(0.0, metrics.budget().ratio(), 0.000000001);
    }

    @Test
    public void allFailedCallsShouldYieldZeroSuccessRate() {
        List<JSONObject> calls = new ArrayList<JSONObject>();
        for (int i = 0; i < 3; i++) {
            JSONObject row = call("gen", "error", 0, 0, "case-" + i, 0, 100L);
            row.put("error_type", "timeout");
            calls.add(row);
        }
        List<JSONObject> cases = new ArrayList<JSONObject>();
        for (int i = 0; i < 3; i++) {
            cases.add(caseRow("case-" + i, "error"));
        }

        EvalMetrics metrics = service.compute(calls, cases);

        assertEquals(0.0, metrics.calls().successRate(), 0.000000001);
        assertEquals(3L, metrics.latency().excludedError());
        assertEquals(0L, metrics.latency().n());
        assertEquals("NO_SAMPLES", metrics.latency().reasonCode());
        assertEquals(0.0, metrics.samples().successRate(), 0.000000001);
        assertEquals(0.0, metrics.samples().retryRate(), 0.000000001);
        assertEquals(1.0, metrics.samples().averageAttempts(), 0.000000001);
        assertEquals(0L, metrics.tokens().prompt().n());
        assertEquals(1.0, metrics.cases().nonOkRate(), 0.000000001);
    }

    // ===== 序列化形状 =====

    @Test
    public void toJsonShouldExposeFrozenShape() {
        JSONObject json = service.compute(datasetCalls(), datasetCases()).toJson();

        assertEquals(4L, json.getJSONObject("latency").getLongValue("n"));
        assertNull(json.getJSONObject("latency").get("p50"));
        assertEquals(7L, json.getJSONObject("calls").getJSONObject("byStage").getLongValue("gen"));
        assertEquals(0.75, json.getJSONObject("samples").getDoubleValue("successRate"), 0.000000001);
        assertEquals(73L, json.getJSONObject("tokens").getJSONObject("prompt").getLongValue("sum"));
        assertEquals(100L, json.getJSONObject("budget").getLongValue("budget"));
        assertEquals(5L, json.getJSONObject("cases").getLongValue("total"));
    }

    // ===== 构造辅助 =====

    private static List<JSONObject> datasetCalls() {
        List<JSONObject> calls = new ArrayList<JSONObject>();

        JSONObject row1 = call("gen", "ok", 0, 0, "case-a", 0, 1000L);
        usage(row1, 10, 5, 15, 2);
        calls.add(row1);

        JSONObject row2 = call("gen", "ok", 0, 1, "case-a", 0, 3000L);
        usage(row2, 20, 10, 30, 4);
        calls.add(row2);

        JSONObject row3 = call("gen", "ok", 0, 0, "case-b", 0, 2000L);
        usage(row3, 30, 15, 45, 6);
        calls.add(row3);

        JSONObject row4 = call("gen", "error", 0, 0, "case-c", 0, null);
        row4.put("error_type", "busy");
        calls.add(row4);

        JSONObject row5 = call("gen", "error", 0, 1, "case-c", 0, null);
        row5.put("error_type", "timeout");
        calls.add(row5);

        JSONObject row6 = call("judge", "ok", 0, 0, "case-a", 0, 500L);
        usage(row6, 5, 2, 7, 1);
        calls.add(row6);

        JSONObject row7 = call("calib", "ok", 0, 0, null, null, 700L);
        usage(row7, 8, 3, 11, 0);
        calls.add(row7);

        JSONObject row8 = call("gen", "ok", 1, 0, "case-d", 0, 9999L);
        usage(row8, 100, 50, 150, 100);
        calls.add(row8);

        JSONObject row9 = call("judge", "error", 0, 0, "case-b", 0, null);
        row9.put("error_type", "internal");
        calls.add(row9);

        JSONObject row10 = call("gen", "ok", 0, 0, "case-e", 0, 5000L);
        row10.put("usage_completion", 4);
        calls.add(row10);

        return calls;
    }

    private static List<JSONObject> datasetCases() {
        List<JSONObject> cases = new ArrayList<JSONObject>();
        cases.add(caseRow("case-a", "ok"));
        cases.add(caseRow("case-b", "vetoed"));
        cases.add(caseRow("case-c", "error"));
        cases.add(caseRow("case-d", "invalid"));
        cases.add(caseRow("case-e", "ok"));
        return cases;
    }

    private static void usage(JSONObject row, int prompt, int completion, int total, int cached) {
        row.put("usage_prompt", prompt);
        row.put("usage_completion", completion);
        row.put("usage_total", total);
        row.put("usage_cached", cached);
    }

    private static JSONObject caseRow(String caseId, String status) {
        JSONObject row = new JSONObject();
        row.put("case_id", caseId);
        row.put("status", status);
        return row;
    }


    private static List<JSONObject> latencyRows(int n) {
        List<JSONObject> rows = new ArrayList<JSONObject>();
        for (int i = 1; i <= n; i++) {
            rows.add(call("gen", "ok", 0, 0, "case-" + i, 0, (long) i));
        }
        return rows;
    }

    private static JSONObject call(String stage, String status, Integer cached, Integer attempt,
            String caseId, Integer sampleIndex, Long elapsedMs) {
        JSONObject row = new JSONObject();
        row.put("stage", stage);
        row.put("status", status);
        row.put("cached", cached);
        row.put("attempt", attempt);
        row.put("case_id", caseId);
        row.put("sample_index", sampleIndex);
        row.put("elapsed_ms", elapsedMs);
        return row;
    }
}
