package org.moboxlab.mbb.eval.runner;

import java.io.File;

import org.moboxlab.mbb.eval.bank.EvalBankCache;
import org.moboxlab.mbb.eval.bank.EvalBankIdentity;
import org.moboxlab.mbb.eval.bank.EvalBankLocator;
import org.moboxlab.mbb.eval.bank.EvalBankPersonaBinding;
import org.moboxlab.mbb.eval.bank.EvalBankProblem;
import org.moboxlab.mbb.eval.gateway.EvalCapabilityClient;
import org.moboxlab.mbb.eval.rubric.EvalRubricCache;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.AiStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.BankStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.CapabilitiesStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.DataDirStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.PersonaStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.RubricStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.StageReport;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginManager;

/**
 * 预检各阶段的生产来源（B-5、W-3）：题库、rubric、persona、MBB-AI、数据目录。
 *
 * <p>题库、rubric、persona 只读 onEnable 缓存，不重复读文件（§5.7.4）；题库或 rubric 失败时
 * 返回 REJECT，并把缓存里已排好序的前 5 条明细放进 {@link StageReport#details()}，由命令层
 * 逐条回显。persona 在每次预检时用 capabilities 的实时 personaHash 与题库身份重新比对，
 * {@code evalAllowPersonaMismatch=true} 时降级为 WARN 并允许试跑（§5.2.1、§8.5）。</p>
 *
 * <p>MBB-AI 与数据目录是 §9.5 八项预检的第六、七项，判定看运行期真实状态：
 * MBB-AI 插件必须存在且已启用（§10.1），数据目录必须存在、reports/ 子目录可建且可写。
 * 两者失败都返回 REJECT，并给出绝对路径/修复建议，供 EvalRejectionPresenter 统一渲染。</p>
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

    /** MBB-AI 插件名（与 MBB-AI 自身的 plugin.json 一致）。 */
    public static final String AI_PLUGIN_NAME = "MBB-AI";
    /** 数据目录不可用时的统一修复建议。 */
    private static final String DATA_DIR_HINT =
            "请检查数据目录与 reports/ 子目录的权限或磁盘空间后重启插件；/eval report 仍可用";
    /** 数据目录缺失/不可读时的修复建议（§9.5 第七项）。 */
    private static final String EVALS_DIR_HINT =
            "请确认 ./MoBoxBot/plugins/MBB-Roleplay/evals 存在且可读，并放入 bank-<version>.json 后重跑 /eval run；"
                    + "/eval report 仍可用";

    /**
     * MBB-AI 来源（§9.5 第六项、§10.1）：插件存在且已启用才 PASS。
     *
     * @param pluginManager 服务器插件管理器；null 表示服务器未就绪
     */
    public static AiStatusSource ai(final PluginManager pluginManager) {
        return new AiStatusSource() {
            @Override
            public StageReport report(String tier) {
                if (pluginManager == null) {
                    return StageReport.reject("",
                            "MBB-AI 状态不可用：服务器未就绪，无法查询插件管理器",
                            "请确认服务器与 aiHost 配置后重启插件再重跑 /eval run");
                }
                return aiReport(pluginManager.getPlugin(AI_PLUGIN_NAME));
            }
        };
    }

    /** 纯判定 MBB-AI 插件状态；抽出以便单测直接注入 null（未安装）。 */
    static StageReport aiReport(Plugin ai) {
        if (ai == null) {
            return StageReport.reject("",
                    "MBB-AI 未安装或未启用：插件列表中没有 " + AI_PLUGIN_NAME,
                    "请安装并启用 MBB-AI 插件，并检查 aiHost 配置（§10.1）后重跑 /eval run");
        }
        if (!ai.isEnabled()) {
            return StageReport.reject("",
                    "MBB-AI 插件存在但未启用：" + AI_PLUGIN_NAME,
                    "请启用 MBB-AI 插件，并检查 aiHost 配置（§10.1）后重跑 /eval run");
        }
        return StageReport.pass("MBB-AI 已启用：" + AI_PLUGIN_NAME);
    }

    /**
     * 数据目录来源（§9.5 第七项）：evals 目录存在可读，reports/ 子目录可建且可写。
     *
     * @param evalsDirectory   评测数据目录（MBB-Roleplay/evals），可为 null 表示无法推导
     * @param reportsDirectory 报表输出目录（插件数据目录/reports），可为 null 表示未装配
     */
    public static DataDirStatusSource dataDir(final File evalsDirectory,
            final File reportsDirectory) {
        return new DataDirStatusSource() {
            @Override
            public StageReport report(String tier) {
                if (evalsDirectory == null) {
                    return StageReport.reject(EvalBankLocator.CODE_DATA_NOT_FOUND,
                            "评测数据目录不可用：无法从插件数据目录推导 evals 路径",
                            "请确认插件数据目录位于 ./MoBoxBot/plugins/MBB-Eval 下后重启插件");
                }
                if (!evalsDirectory.isDirectory()) {
                    return StageReport.reject(EvalBankLocator.CODE_DATA_NOT_FOUND,
                            "评测数据目录不存在：" + absolute(evalsDirectory), EVALS_DIR_HINT);
                }
                if (!evalsDirectory.canRead()) {
                    return StageReport.reject(EvalBankLocator.CODE_DATA_NOT_FOUND,
                            "评测数据目录不可读：" + absolute(evalsDirectory), EVALS_DIR_HINT);
                }
                if (reportsDirectory == null) {
                    return StageReport.reject("",
                            "评测数据目录不可写：reports 输出目录未装配", DATA_DIR_HINT);
                }
                boolean created = reportsDirectory.isDirectory() || reportsDirectory.mkdirs();
                if (!created || !reportsDirectory.canWrite()) {
                    return StageReport.reject("",
                            "评测数据目录不可写：" + absolute(reportsDirectory), DATA_DIR_HINT);
                }
                return StageReport.pass("评测数据目录可用：" + absolute(evalsDirectory));
            }
        };
    }

    private static String absolute(File file) {
        try {
            return file.getAbsolutePath();
        } catch (RuntimeException e) {
            return file.getPath();
        }
    }
}