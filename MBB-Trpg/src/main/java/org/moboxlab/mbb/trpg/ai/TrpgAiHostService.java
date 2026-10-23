package org.moboxlab.mbb.trpg.ai;

import java.util.Locale;

import org.moboxlab.mbb.trpg.config.TrpgConfig;

/**
 * AI 主持契约桩（规格 §9.10）：v1 只锁契约，不做任何 AI 调用。
 *
 * <p>v1 交付范围：读取 {@code aiHost.*} 配置、判定全局开关、解析 {@code /trpg ai} 参数、
 * 给出固定分支；<b>不调用 AI、不写审计、不注册定时器、不落库 {@code aiEnabled}</b>。
 * 因为没有持有 {@code AiClient} / {@code TraceStore} / {@code Scheduler} 端口，零调用与零审计是结构性的，
 * 不依赖运行期判断。真实实现（排队、降级、审计、合并窗口）随 v1.1 在本类内落地。</p>
 */
public final class TrpgAiHostService {

    /** 全局开时 {@code /trpg ai on} 的回执说明（§9.10）。 */
    public static final String DETAIL_PLANNED = "AI 主持将在 v1.1 提供";

    /** 全局关时 {@code /trpg ai on} 的拒绝说明（§6.3 的「当前状态不可用」）。 */
    public static final String DETAIL_UNAVAILABLE = "当前状态不可用";

    /** {@code /trpg ai off} 的回执说明：v1 从未开启过，因此是保持关闭。 */
    public static final String DETAIL_OFF = "本局 AI 主持保持关闭";


    private final TrpgConfig config;
    private SessionView sessionView;

    public TrpgAiHostService(TrpgConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("配置不能为空");
        }
        this.config = config;
    }

    /** 全局开关：只在插件加载时读取配置（§9.2），运行期不再重读。 */
    public boolean globallyEnabled() {
        return config.aiHostEnabled();
    }

    /** 解析开关词：on / 开 为开启，off / 关 为关闭，其余返回 null。 */
    public static Boolean parseWord(String rawWord) {
        String word = rawWord == null ? "" : rawWord.trim().toLowerCase(Locale.ROOT);
        if ("on".equals(word) || "开".equals(word)) {
            return Boolean.TRUE;
        }
        if ("off".equals(word) || "关".equals(word)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 判定 {@code /trpg ai} 的 v1 分支；权限与状态由局面服务先行判定。 */
    public AiToggle toggle(String rawWord) {
        Boolean parsed = parseWord(rawWord);
        if (parsed == null) {
            return AiToggle.USAGE;
        }
        if (!parsed.booleanValue()) {
            return AiToggle.OFF_ACK;
        }
        return globallyEnabled() ? AiToggle.ON_PLANNED : AiToggle.ON_DISABLED;
    }

    /**
     * 绑定局面只读视图（§9.11 的测试缝）。
     *
     * <p>v1 桩不读局面；v1.1 装配提示词时才通过它取快照。允许重复绑定，最后一次生效。</p>
     */
    public void bindSessionView(SessionView view) {
        this.sessionView = view;
    }

    /** 已绑定的局面只读视图；未装配时为 null。 */
    public SessionView sessionView() {
        return sessionView;
    }

}
