package org.moboxlab.mbb.eval.judge;

/**
 * MBB-AI {@code chat} 的最小注入接口（规格 §6.3.4）。
 *
 * <p>只承担一次同步调用，不做重试、不读配置、不装配 prompt；重试与缓存处理由
 * {@link EvalJudge} 统一负责。实现方负责把 {@link EvalJudgeChatParams} 的
 * temperature / maxTokens / timeoutSeconds / retryCount / sessionId 原样传给 MBB-AI，
 * reasoningEffort 留空时不得补默认值。</p>
 */
public interface EvalJudgeChatClient {

    /**
     * 发起一次 judge 调用。
     *
     * @param prompt 已装配好的 system + user 提示词
     * @param params 调用参数快照
     * @return 统一响应；实现方不得返回 {@code null}
     */
    EvalJudgeChatResponse chat(EvalJudgePrompt prompt, EvalJudgeChatParams params);
}
