package org.moboxlab.mbb.eval.runner;

import org.moboxlab.mbb.eval.bank.EvalBankCache;
import org.moboxlab.mbb.eval.bank.EvalBankIdentity;
import org.moboxlab.mbb.eval.bank.EvalBankPersonaBinding;
import org.moboxlab.mbb.eval.bank.EvalBankProblem;
import org.moboxlab.mbb.eval.gateway.EvalCapabilityClient;
import org.moboxlab.mbb.eval.rubric.EvalRubricCache;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.BankStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.CapabilitiesStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.PersonaStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.RubricStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.StageReport;

/**
 * 预检前三项的真实来源（B-5）：题库、rubric、persona。
 *
 * <p>三个来源只读 onEnable 缓存，不重复读文件（§5.7.4）；题库或 rubric 失败时返回 REJECT，
 * 并把缓存里已排好序的前 5 条明细放进 {@link StageReport#details()}，由命令层逐条回显。
 * persona 在每次预检时用 capabilities 的实时 personaHash 与题库身份重新比对，
 * {@code evalAllowPersonaMismatch=true} 时降级为 WARN 并允许试跑（§5.2.1、§8.5）。</p>
 */
public final class EvalStageSources {

    private EvalStageSources() {
    }

    /** 题库来源：成功 PASS，失败 REJECT（错误码与明细来自 {@link EvalBankCache}）。 */
    public static BankStatusSource bank(final EvalBankCache cache) {
        return new BankStatusSource() {
            @Override
            public StageReport report(String tier) {
                if (cache == null) {
                    return StageReport.reject(EvalBankProblem.READ,
                            "题库尚未加载：插件启用时的初始化未完成",
                            "请重启插件后重跑 /eval run；/eval report 仍可用");
                }
                if (cache.ok()) {
                    return StageReport.pass("题库已加载：" + cache.fileName() + "，version="
                            + cache.version() + "，共 " + cache.caseCount() + " 条用例（smoke "
                            + cache.smokeCount() + " 条）");
                }
                return StageReport.reject(cache.code(), cache.message(), cache.hint(),
                        cache.errors());
            }
        };
    }

    /** rubric 来源：成功 PASS，失败 REJECT（错误码按 §17.1 O20 映射到 §13.1.1 封闭集合）。 */
    public static RubricStatusSource rubric(final EvalRubricCache cache) {
        return new RubricStatusSource() {
            @Override
            public StageReport report(String tier) {
                if (cache == null) {
                    return StageReport.reject(EvalBankProblem.READ,
                            "rubric 尚未加载：插件启用时的初始化未完成",
                            "请重启插件后重跑 /eval run；/eval report 仍可用");
                }
                if (cache.ok()) {
                    return StageReport.pass("rubric 已加载：" + cache.file().getName()
                            + "，promptVersion=" + cache.rubric().promptVersion());
                }
                return StageReport.reject(cache.code(), cache.message(), cache.hint(),
                        cache.errors());
            }
        };
    }

    /**
     * persona 来源：题库 personaHash 与 capabilities.personaHash 比对。
     *
     * @param cache         题库缓存，可为 null
     * @param capabilities  实时 capabilities 快照来源，可为 null
     * @param allowMismatch evalAllowPersonaMismatch
     */
    public static PersonaStatusSource persona(final EvalBankCache cache,
            final CapabilitiesStatusSource capabilities, final boolean allowMismatch) {
        return new PersonaStatusSource() {
            @Override
            public StageReport report(String tier) {
                if (cache == null || !cache.ok()) {
                    return StageReport.reject(EvalBankPersonaBinding.CODE_PERSONA,
                            "persona 绑定不可用：题库未加载，无法比对 personaHash",
                            "请先修复题库并重启插件后重跑 /eval run；/eval report 仍可用");
                }
                EvalBankIdentity identity = cache.identity();
                if (identity == null) {
                    return StageReport.reject(EvalBankPersonaBinding.CODE_PERSONA,
                            "persona 绑定不可用：题库身份信息缺失",
                            "请检查题库顶层 persona 字段后重启插件；/eval report 仍可用");
                }
                EvalCapabilityClient.Snapshot snapshot = capabilities == null
                        ? null : capabilities.snapshot();
                if (snapshot == null) {
                    return StageReport.reject(EvalBankPersonaBinding.CODE_PERSONA,
                            "persona 绑定不可用：capabilities 快照缺失",
                            "请确认 MBB-Roleplay 插件已启用后重跑 /eval run");
                }
                EvalBankPersonaBinding binding = EvalBankPersonaBinding.bind(identity,
                        snapshot.personaId(), snapshot.personaHash(), allowMismatch);
                if (binding == null) {
                    return StageReport.reject(EvalBankPersonaBinding.CODE_PERSONA,
                            "persona 绑定不可用：无法读取题库或运行时 personaHash",
                            "请确认题库 personaHash 与 MBB-Roleplay 的 capabilities 均有效后重跑 /eval run");
                }
                if (!binding.allowed()) {
                    return StageReport.reject(EvalBankPersonaBinding.CODE_PERSONA,
                            binding.message(),
                            "请确认 MBB-Roleplay 的 persona 与题库 persona.file 一致；"
                                    + "或临时把 evalAllowPersonaMismatch 设为 true 降级试跑（结果按不可信标注）");
                }
                if (binding.warning()) {
                    return StageReport.warn(EvalBankPersonaBinding.CODE_PERSONA,
                            binding.message(),
                            "已降级为告警并允许试跑；结果按不可信标注，建议对齐 personaHash 后重跑");
                }
                return StageReport.pass(binding.message());
            }
        };
    }
}