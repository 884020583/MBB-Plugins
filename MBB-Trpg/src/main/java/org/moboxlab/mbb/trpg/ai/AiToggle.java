package org.moboxlab.mbb.trpg.ai;

/**
 * v1 AI 主持桩对 {@code /trpg ai on|off} 的判定结果（规格 §9.10）。
 *
 * <p>v1 没有 AI 实现：桩只判定全局开关并给出固定分支，不改任何状态。</p>
 */
public enum AiToggle {

    /** 全局开、参数为 on：回「AI 主持将在 v1.1 提供」，不置位。 */
    ON_PLANNED(true),

    /** 全局关、参数为 on：按「当前状态不可用」拒绝（不新增拒绝类别）。 */
    ON_DISABLED(false),

    /** 参数为 off：回「本局 AI 主持保持关闭」，同样不写库。 */
    OFF_ACK(true),

    /** 参数为空或无法识别：回用法提示。 */
    USAGE(false);

    private final boolean accepted;

    AiToggle(boolean accepted) {
        this.accepted = accepted;
    }

    /** 是否按成功回执（其余走拒绝）。 */
    public boolean accepted() {
        return accepted;
    }

}
