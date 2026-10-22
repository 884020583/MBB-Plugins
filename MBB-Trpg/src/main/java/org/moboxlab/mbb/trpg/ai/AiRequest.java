package org.moboxlab.mbb.trpg.ai;

/**
 * 一次 AI 叙述请求（不可变）。
 *
 * <p>按提示词三段拆分：稳定前缀、可缓存中段、动态区；总预算由调用方控制。</p>
 */
public final class AiRequest {

    private final String triggerType;
    private final String stablePrefix;
    private final String cacheable;
    private final String dynamic;
    private final int timeoutSeconds;

    public AiRequest(String triggerType, String stablePrefix, String cacheable, String dynamic,
                     int timeoutSeconds) {
        this.triggerType = triggerType == null ? "" : triggerType;
        this.stablePrefix = stablePrefix == null ? "" : stablePrefix;
        this.cacheable = cacheable == null ? "" : cacheable;
        this.dynamic = dynamic == null ? "" : dynamic;
        this.timeoutSeconds = timeoutSeconds;
    }

    public String triggerType() {
        return triggerType;
    }

    public String stablePrefix() {
        return stablePrefix;
    }

    public String cacheable() {
        return cacheable;
    }

    public String dynamic() {
        return dynamic;
    }

    /** 传给服务方的超时秒数。 */
    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    /** 三段拼接后的字符数，用于预算校验。 */
    public int totalChars() {
        return stablePrefix.length() + cacheable.length() + dynamic.length();
    }

    /** 拼成完整提示词。 */
    public String prompt() {
        StringBuilder builder = new StringBuilder();
        builder.append(stablePrefix).append('\n').append(cacheable).append('\n').append(dynamic);
        return builder.toString();
    }
}
