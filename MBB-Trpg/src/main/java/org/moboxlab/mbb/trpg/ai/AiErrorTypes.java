package org.moboxlab.mbb.trpg.ai;

/**
 * MBB-AI 适配层的底层错误类型，对应 {@link AiResult#errorType()}。
 *
 * <p>与剧本生成的六类 {@code E_AI_*} 是两层：这里描述失败来源，生成服务负责折叠成对外错误码。</p>
 */
public final class AiErrorTypes {

    /** 未安装 MBB-AI 服务，或服务处于禁用状态。 */
    public static final String DISABLED = "disabled";

    /** 全局并发已满。 */
    public static final String BUSY = "busy";

    /** 超时或中断。 */
    public static final String TIMEOUT = "timeout";

    /** 请求参数不合法。 */
    public static final String PARAMS = "params";

    /** 其他未知失败。 */
    public static final String UNKNOWN = "unknown";

    private AiErrorTypes() {
    }
}
