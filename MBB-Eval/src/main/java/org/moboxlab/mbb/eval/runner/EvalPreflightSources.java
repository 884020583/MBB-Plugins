package org.moboxlab.mbb.eval.runner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.gateway.EvalCapabilityClient;

/**
 * 预检八项的来源接口与判定结果（规格 §9.5、§13.2）。
 *
 * <p>顺序固定为：接口能力 → 题库 → rubric → persona → judge profile → MBB-AI → 数据目录 →
 * 预算。每项都由 {@link StageSource} 注入，未接线的项用 {@link PendingSource} 显式表达：
 * 视为通过但记一次 WARN「&lt;项&gt; 尚未接线，跳过预检」，避免骨架期出现「永远拒绝」。
 * B-5 / D-4 / F-2 接线时只需替换对应来源，不改编排。</p>
 */
public final class EvalPreflightSources {

    private EvalPreflightSources() {
    }

    /** 预检阶段；声明顺序即 §9.5 的执行顺序。 */
    public enum Stage {
        CAPABILITIES("接口能力"),
        BANK("题库"),
        RUBRIC("rubric"),
        PERSONA("persona"),
        JUDGE_PROFILE("judge profile"),
        MBB_AI("MBB-AI"),
        DATA_DIR("数据目录"),
        BUDGET("预算");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        /** 中文短标签，用于日志、回执与告警文案。 */
        public String label() {
            return label;
        }
    }

    /** 单项预检的结论。 */
    public static final class StageReport {

        /** 结论种类：只有 REJECT 会中断预检。 */
        public enum Outcome {
            /** 通过。 */
            PASS,
            /** 通过但需告警（例如 persona 允许不一致时降级试跑）。 */
            WARN,
            /** 尚未接线：按通过处理，只告警一次。 */
            PENDING,
            /** 拒绝。 */
            REJECT
        }

        private final Outcome outcome;
        private final String code;
        private final String message;
        private final String hint;
        private final List<String> details;

        private StageReport(Outcome outcome, String code, String message, String hint,
                List<String> details) {
            this.outcome = outcome;
            this.code = code;
            this.message = message;
            this.hint = hint;
            this.details = Collections.unmodifiableList(new ArrayList<String>(
                    details == null ? Collections.<String>emptyList() : details));
        }

        public static StageReport pass(String message) {
            return new StageReport(Outcome.PASS, "", message, "", null);
        }

        public static StageReport warn(String code, String message, String hint) {
            return new StageReport(Outcome.WARN, code, message, hint, null);
        }

        /** 尚未接线的显式状态：消息固定为「&lt;项&gt; 尚未接线，跳过预检」。 */
        public static StageReport pending(Stage stage) {
            return new StageReport(Outcome.PENDING, "",
                    stage.label() + " 尚未接线，跳过预检",
                    "该项由后续切片替换为真实检查；接线前按通过处理", null);
        }

        public static StageReport reject(String code, String message, String hint) {
            return new StageReport(Outcome.REJECT, code, message, hint, null);
        }

        /** 拒绝并附带逐条明细（最多 5 条，已按稳定顺序排好）；供命令层原样回显。 */
        public static StageReport reject(String code, String message, String hint,
                List<String> details) {
            return new StageReport(Outcome.REJECT, code, message, hint, details);
        }

        public Outcome outcome() {
            return outcome;
        }

        public String code() {
            return code;
        }

        public String message() {
            return message;
        }

        public String hint() {
            return hint;
        }

        /** 逐条明细，例如题库的前 5 条「错误码（JSON path）：中文说明」；无明细时为空列表。 */
        public List<String> details() {
            return details;
        }

        public boolean isReject() {
            return outcome == Outcome.REJECT;
        }
    }

    /** 单项预检来源；tier 为归一化后的 smoke / full。 */
    public interface StageSource {
        StageReport report(String tier);
    }

    /** 题库可读性来源（§9.5 第二项，错误码见 §13.1.1 的 E_BANK_*）。 */
    public interface BankStatusSource extends StageSource {
    }

    /** rubric 可加载且 promptVersion 与代码支持版本一致。 */
    public interface RubricStatusSource extends StageSource {
    }

    /** persona 绑定一致性（题库 personaHash 与 capabilities.personaHash 比对）。 */
    public interface PersonaStatusSource extends StageSource {
    }

    /** judge profile 可解析（回退链 evalJudgeProfile → evalProfile → aiProfile）。 */
    public interface JudgeProfileSource extends StageSource {
    }

    /** MBB-AI 在线可调。 */
    public interface AiStatusSource extends StageSource {
    }

    /** 数据目录 ./MoBoxBot/plugins/MBB-Roleplay/evals 可读。 */
    public interface DataDirStatusSource extends StageSource {
    }

    /** capabilities 快照来源；生产实现是 {@link EvalCapabilityClient#snapshot()}。 */
    public interface CapabilitiesStatusSource {
        EvalCapabilityClient.Snapshot snapshot();
    }

    /** 未接线项的占位来源：固定返回 PENDING，且只绑定一个阶段。 */
    public static final class PendingSource implements BankStatusSource, RubricStatusSource,
            PersonaStatusSource, AiStatusSource, DataDirStatusSource {

        private final Stage stage;

        private PendingSource(Stage stage) {
            this.stage = stage;
        }

        public static PendingSource of(Stage stage) {
            return new PendingSource(stage);
        }

        @Override
        public StageReport report(String tier) {
            return StageReport.pending(stage);
        }
    }

    /**
     * judge profile 的真实检查：按 evalJudgeProfile → evalProfile → aiProfile 回退。
     *
     * <p>三者都为空才拒绝；本切片只判定「有可解析的 profile 值」，具体 profile 是否存在
     * 依赖 MBB-AI 侧配置，留给 F 区接线时补。</p>
     */
    public static final class ConfigJudgeProfileSource implements JudgeProfileSource {

        private final EvalConfig config;
        private final CapabilitiesStatusSource capabilities;

        public ConfigJudgeProfileSource(EvalConfig config, CapabilitiesStatusSource capabilities) {
            this.config = config;
            this.capabilities = capabilities;
        }

        @Override
        public StageReport report(String tier) {
            if (config != null) {
                String judgeProfile = trim(config.judgeProfile());
                if (judgeProfile.length() > 0) {
                    return StageReport.pass("judge profile 可用：evalJudgeProfile=" + judgeProfile);
                }
                String evalProfile = trim(config.profile());
                if (evalProfile.length() > 0) {
                    return StageReport.pass("judge profile 可用：evalProfile=" + evalProfile);
                }
            }
            String aiProfile = "";
            EvalCapabilityClient.Snapshot snapshot = capabilities == null ? null : capabilities.snapshot();
            if (snapshot != null) {
                aiProfile = trim(snapshot.aiProfile());
            }
            if (aiProfile.length() > 0) {
                return StageReport.pass("judge profile 可用：aiProfile=" + aiProfile);
            }
            return StageReport.reject("",
                    "judge profile 缺失：evalJudgeProfile / evalProfile / aiProfile 均为空",
                    "请设置 evalJudgeProfile 或 evalProfile；若两者留空，确认 MBB-Roleplay 的 aiProfile 非空");
        }

        private static String trim(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
