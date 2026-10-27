package org.moboxlab.mbb.eval.metrics;

import java.util.List;

/**
 * L3 只读旁证的数据源接口（E-4：规格 §7.5、§16.6）。
 *
 * <p>实现只允许读取 MBB-AI 的 {@code plugin_mbb_ai_usage}（或主程序公开口径的同类用量表），
 * 不得写任何表、不得读写评测评测表。接口单独抽出，便于测试注入内存假源，也把
 * 「可注入」这一点固化在类型上。</p>
 *
 * <p>约定：参数为 {@code yyyy-MM-dd}；无数据返回空列表而不是 null；读取失败可抛
 * {@link RuntimeException}，由服务按「不可得」兜底。返回行允许超出给定 profile / 日期区间，
 * 调用方会再过滤一次。</p>
 */
public interface EvalAiUsageSource {

    /**
     * 按 profile 与日期区间查询线上用量聚合行。
     *
     * @param profile 统计表中的 profile 名，非空
     * @param startDate 起始日期 yyyy-MM-dd（含）
     * @param endDate 结束日期 yyyy-MM-dd（含）
     * @return 每个日期一行的只读快照；无数据时为空列表
     */
    List<EvalAiUsageDay> queryDailyUsage(String profile, String startDate, String endDate);
}
