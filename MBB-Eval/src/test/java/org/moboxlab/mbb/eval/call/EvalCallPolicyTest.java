package org.moboxlab.mbb.eval.call;

import com.alibaba.fastjson.JSONObject;
import org.junit.Test;
import org.moboxlab.mbb.eval.gateway.EvalGenerateRequest;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 生成调用重试与 cached 重采判定测试（规格 §10.6.1、§13.1、§13.2、§15.5 H4/H5/H6）。
 *
 * <p>判定只看生成出参：retryable=true 才退避重试，其余错误立即按样本 error 处理；
 * cached=true 换会话重采，超出重采上限才判样本 error。所有边界值都按规格收紧，
 * 配置越界时静默夹取，不抛参数异常。</p>
 */
public class EvalCallPolicyTest {

    private static final String RUN_ID = "r-20261026120000-smoke-0004";
    private static final String CASE_ID = "case-001";

    @Test
    public void retryableErrorShouldRetryWithFirstBackoff() {
        EvalCallPolicy policy = new EvalCallPolicy(1, 3, new int[] {2, 5});

        EvalCallPolicy.Decision decision = policy.decide(error("busy", true), 0, 0);

        assertEquals(EvalCallPolicy.Action.RETRY, decision.action());
        assertEquals("第一次重试取第一个退避值", 2, decision.backoffSeconds());
        assertTrue("必须写明重试原因", decision.reason() != null && decision.reason().length() > 0);
    }

    @Test
    public void retriesExhaustedShouldFailSample() {
        EvalCallPolicy policy = new EvalCallPolicy(1, 3, new int[] {2, 5});

        EvalCallPolicy.Decision decision = policy.decide(error("timeout", true), 1, 0);

        assertEquals(EvalCallPolicy.Action.FAIL, decision.action());
        assertEquals("失败判定不带退避", 0, decision.backoffSeconds());
    }

    @Test
    public void nonRetryableErrorShouldFailImmediatelyEvenForBusyCode() {
        EvalCallPolicy policy = new EvalCallPolicy(1, 3, new int[] {2, 5});

        EvalCallPolicy.Decision decision = policy.decide(error("busy", false), 0, 0);

        assertEquals("不能按错误码猜可重试性", EvalCallPolicy.Action.FAIL, decision.action());
    }

    @Test
    public void retriesDisabledShouldFailRetryableError() {
        EvalCallPolicy policy = new EvalCallPolicy(0, 3, new int[] {2, 5});

        EvalCallPolicy.Decision decision = policy.decide(error("timeout", true), 0, 0);

        assertEquals(EvalCallPolicy.Action.FAIL, decision.action());
    }

    @Test
    public void cachedHitShouldResampleUntilLimitThenFail() {
        EvalCallPolicy policy = new EvalCallPolicy(1, 3, new int[] {2, 5});

        for (int cachedHits = 1; cachedHits <= 3; cachedHits++) {
            EvalCallPolicy.Decision decision = policy.decide(success(true), 0, cachedHits);
            assertEquals("第 " + cachedHits + " 次 cached 命中仍应重采",
                    EvalCallPolicy.Action.RESAMPLE, decision.action());
        }

        EvalCallPolicy.Decision exhausted = policy.decide(success(true), 0, 4);
        assertEquals("重采用尽后仍 cached 判样本 error", EvalCallPolicy.Action.FAIL, exhausted.action());
    }

    @Test
    public void cachedFlagOnFailureShouldNotResample() {
        EvalCallPolicy policy = new EvalCallPolicy(1, 3, new int[] {2, 5});

        EvalCallPolicy.Decision decision = policy.decide(error("busy", false), 0, 0);

        assertEquals(EvalCallPolicy.Action.FAIL, decision.action());
    }

    @Test
    public void successWithoutCacheShouldAccept() {
        EvalCallPolicy policy = new EvalCallPolicy(1, 3, new int[] {2, 5});

        assertEquals(EvalCallPolicy.Action.ACCEPT, policy.decide(success(false), 0, 0).action());
    }

    @Test
    public void configurationShouldClampToSpecRanges() {
        EvalCallPolicy tooLarge = new EvalCallPolicy(9, 9, new int[] {1});
        assertEquals("evalGenerateRetries 上限为 1", 1, tooLarge.maxRetries());
        assertEquals("evalCachedResamples 上限为 3", 3, tooLarge.maxCachedResamples());

        EvalCallPolicy tooSmall = new EvalCallPolicy(-3, 0, new int[] {1});
        assertEquals("evalGenerateRetries 下限为 0", 0, tooSmall.maxRetries());
        assertEquals("evalCachedResamples 下限为 1", 1, tooSmall.maxCachedResamples());
    }

    @Test
    public void injectedBackoffShouldBeUsedAndReused() {
        EvalCallPolicy policy = new EvalCallPolicy(1, 3, new int[] {7});
        assertEquals("注入的退避秒数必须生效", 7, policy.decide(error("busy", true), 0, 0).backoffSeconds());

        EvalCallPolicy zeroBackoff = new EvalCallPolicy(1, 3, new int[] {0});
        assertEquals("退避允许为 0 以便测试", 0, zeroBackoff.decide(error("busy", true), 0, 0).backoffSeconds());
    }

    @Test
    public void emptyBackoffShouldFallBackToDefaults() {
        EvalCallPolicy policy = new EvalCallPolicy(1, 3, null);

        assertEquals("默认退避序列为 2、5 秒", 2, policy.decide(error("busy", true), 0, 0).backoffSeconds());
        assertTrue(EvalCallPolicy.DEFAULT_BACKOFF_SECONDS.length >= 2);
        assertEquals(2, EvalCallPolicy.DEFAULT_BACKOFF_SECONDS[0]);
        assertEquals(5, EvalCallPolicy.DEFAULT_BACKOFF_SECONDS[1]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullResultShouldBeRejected() {
        new EvalCallPolicy(1, 3, new int[] {1}).decide(null, 0, 0);
    }

    private static EvalGenerateResult error(String errorType, boolean retryable) {
        return EvalGenerateResult.error(errorType, retryable, "模拟失败");
    }

    private static EvalGenerateResult success(boolean cached) {
        String sessionId = EvalGenerateRequest.encodeSessionId(RUN_ID, CASE_ID, 2, 1);
        JSONObject usage = new JSONObject();
        usage.put("promptTokens", Integer.valueOf(11));
        usage.put("completionTokens", Integer.valueOf(22));
        usage.put("totalTokens", Integer.valueOf(33));
        usage.put("cachedPromptTokens", Integer.valueOf(3));

        JSONObject wire = new JSONObject();
        wire.put("action", "eval-generate");
        wire.put("status", Boolean.TRUE);
        wire.put("content", "你好，世界");
        wire.put("skip", Boolean.FALSE);
        wire.put("usage", usage);
        wire.put("elapsedMs", Long.valueOf(1234L));
        wire.put("cached", Boolean.valueOf(cached));
        wire.put("attempts", Integer.valueOf(1));
        wire.put("sessionId", sessionId);
        wire.put("requestId", sessionId);
        wire.put("finishReason", "stop");
        wire.put("model", "mbb-test-model");
        wire.put("profile", "eval");
        wire.put("personaId", "persona-001");
        wire.put("personaHash", "hash-001");
        return EvalGenerateResult.parse(wire, sessionId);
    }
}
