package org.moboxlab.mbb.eval.runner;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.moboxlab.mbb.eval.bank.EvalBankCache;
import org.moboxlab.mbb.eval.bank.EvalBankLocator;
import org.moboxlab.mbb.eval.bank.EvalBankLocation;
import org.moboxlab.mbb.eval.bank.EvalBankReleaser;
import org.moboxlab.mbb.eval.bank.EvalBankReleaseResult;
import org.moboxlab.mbb.eval.bank.EvalBankResources;
import org.moboxlab.mbb.eval.bank.EvalBankValidator;
import org.moboxlab.mbb.eval.bank.EvalRubricReleaseResult;
import org.moboxlab.mbb.eval.bank.EvalRubricReleaser;
import org.moboxlab.mbb.eval.rubric.EvalRubric;
import org.moboxlab.mbb.eval.rubric.EvalRubricCache;
import org.moboxlab.moboxbot.API.PluginLogger;

import com.alibaba.fastjson.JSONObject;

/**
 * onEnable 的评测数据装配（B-5）：先 rubric，再题库；缺失内置题库时释放并钉版本。
 *
 * <p>按 §5.7.4 的顺序执行：rubric 失败时不加载题库（目录级失败除外，仍让题库项报
 * E_ROLEPLAY_DATA_NOT_FOUND 便于预检定位）；题库缺失且数据目录可用时调用
 * {@link EvalBankReleaser} 释放内置资源，成功后返回应写回 evalBankFile 的固定文件名。
 * 全程只记录日志、不抛异常，失败只影响 /eval run 预检，不影响插件启用与只读命令。</p>
 */
public final class EvalDataBootstrap {

    /** 日志前缀（§13.6 接口内部日志）。 */
    public static final String LOG_PREFIX = "[评测] ";

    private final EvalBankLocation location;
    private final EvalBankReleaseResult release;
    private final EvalRubricReleaseResult rubricRelease;
    private final EvalRubricCache rubric;
    private final EvalBankCache bank;
    private final String pinFileName;
    private final List<String> warnings;

    private EvalDataBootstrap(EvalBankLocation location, EvalBankReleaseResult release,
            EvalRubricReleaseResult rubricRelease, EvalRubricCache rubric, EvalBankCache bank,
            String pinFileName, List<String> warnings) {
        this.location = location;
        this.release = release;
        this.rubricRelease = rubricRelease;
        this.rubric = rubric;
        this.bank = bank;
        this.pinFileName = pinFileName == null ? "" : pinFileName;
        this.warnings = warnings == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    /**
     * 装配 onEnable 所需的题库与 rubric 缓存。
     *
     * @param dataFolder        插件数据目录（Plugin.getDataFolder()），可为 null
     * @param bankFile          配置项 evalBankFile，空白表示使用内置版本默认文件名
     * @param promptVersion     代码支持的 judge promptVersion（EvalJudgePrompt.PROMPT_VERSION）
     * @param personaId         运行时 persona id（capabilities.personaId()），可为 null
     * @param personaHash       运行时 personaHash（capabilities.personaHash()），可为 null
     * @param allowMismatch     evalAllowPersonaMismatch
     * @param resources         内置资源读取器（JAR），可为 null（缺失时无法释放）
     * @param logger            插件日志，可为 null
     * @return 装配结果；任何失败都通过缓存的状态字段表达，不抛异常
     */
    public static EvalDataBootstrap load(File dataFolder, String bankFile, String promptVersion,
            String personaId, String personaHash, boolean allowMismatch,
            EvalBankResources resources, PluginLogger logger) {
        // 旧调用方只释放题库；rubric 释放由 R-1 的新重载显式传入资源读取器后启用。
        return load(dataFolder, bankFile, promptVersion, personaId, personaHash, allowMismatch,
                resources, null, logger);
    }

    /**
     * 装配 onEnable 所需的题库与 rubric 缓存，并在 rubric 缺失时释放内置 rubric（R-1）。
     *
     * <p>顺序按 §5.8：定位目录 → 题库缺失时释放 → rubric 缺失时释放 → 加载 rubric。
     * rubric 释放只影响 onEnable 的日志与预检结果，不抛异常。</p>
     *
     * @param rubricResources 内置 rubric 资源读取器；为 null 时跳过 rubric 释放
     */
    public static EvalDataBootstrap load(File dataFolder, String bankFile, String promptVersion,
            String personaId, String personaHash, boolean allowMismatch,
            EvalBankResources resources, EvalBankResources rubricResources, PluginLogger logger) {
        List<String> warnings = new ArrayList<String>();

        // §5.8.1：先定位并（仅在缺失时）释放内置题库，确保首次启动时 evals 目录已就绪；
        // §5.7.4 的校验顺序不变：rubric 先于题库，rubric 失败时题库不加载（目录级失败除外）。
        EvalBankLocation location = EvalBankLocator.locate(dataFolder, bankFile);
        warnings.addAll(location.warnings());

        EvalBankReleaseResult release = null;
        if (!location.exists() && location.valid() && location.dataDirectoryUsable()) {
            // 仅文件缺失时释放；不覆盖、不删除（§5.8.1 第 2 条）。
            release = EvalBankReleaser.release(location, resources, logger);
            if (release != null && release.released()) {
                info(logger, "内置题库已释放并进入完整性校验：" + absolute(release.bankFile()));
            }
        }

        String pinFileName = "";
        EvalBankLocation effective = location;
        if (release != null && release.released()) {
            pinFileName = location.pinTargetFileName();
            if (!pinFileName.isEmpty()) {
                effective = EvalBankLocator.locate(dataFolder, pinFileName,
                        EvalBankLocator.BUILTIN_BANK_VERSION);
            }
        }

        EvalRubricReleaseResult rubricRelease = null;
        if (rubricResources != null) {
            // §5.8.1：rubric 也只在缺失时释放；已存在任何版本的 rubric 一律不覆盖、不删除、不改写。
            rubricRelease = EvalRubricReleaser.release(effective, rubricResources, logger);
            if (rubricRelease.released()) {
                info(logger, "内置 rubric 已释放并进入完整性校验："
                        + absolute(rubricRelease.rubricFile()) + "，version="
                        + EvalRubricReleaser.BUILTIN_RUBRIC_VERSION);
            } else if (rubricRelease.failed()) {
                warnings.add("内置 rubric 释放失败（" + rubricRelease.errorCode() + "）："
                        + rubricRelease.message());
            }
        }

        EvalRubricCache rubric = EvalRubricCache.load(dataFolder, promptVersion);
        warnings.addAll(rubric.warnings());
        if (rubric.ok()) {
            info(logger, rubric.summary());
        } else {
            warn(logger, "rubric 加载失败（" + rubric.code() + "）：" + rubric.message()
                    + "；下一步：" + rubric.hint());
        }

        EvalBankCache bank = null;
        boolean directoryLevelFailure = EvalBankLocator.CODE_DATA_NOT_FOUND.equals(rubric.code());
        if (!rubric.ok() && !directoryLevelFailure) {
            warn(logger, "题库未加载：rubric 未通过（" + rubric.code()
                    + "），按 §5.7.4 先修复 rubric 后重启插件；/eval run 将被预检拒绝");
        } else {
            bank = EvalBankCache.load(effective, options(effective, rubric), personaId,
                    personaHash, allowMismatch);
            if (bank.ok()) {
                info(logger, bank.summary());
                if (bank.binding() != null && bank.binding().warning()) {
                    warn(logger, "persona 绑定降级：" + bank.binding().message());
                }
            } else if (bank.ioFailure()) {
                error(logger, "题库读取失败（" + bank.code() + "）：" + bank.message()
                        + "；下一步：" + bank.hint());
            } else {
                warn(logger, bank.summary());
                warn(logger, "题库未通过校验，/eval run 将被预检拒绝；下一步：" + bank.hint());
            }
        }

        return new EvalDataBootstrap(location, release, rubricRelease, rubric, bank, pinFileName,
                warnings);
    }

    private static EvalBankValidator.Options options(EvalBankLocation location,
            EvalRubricCache rubric) {
        EvalBankValidator.Options options = EvalBankValidator.Options.defaults()
                .expectedFileName(location == null ? null : location.bankFileName());
        EvalRubric loaded = rubric == null ? null : rubric.rubric();
        if (loaded == null) {
            return options;
        }
        List<String> dimensionIds = new ArrayList<String>();
        for (EvalRubric.Dimension dimension : loaded.dimensions()) {
            dimensionIds.add(dimension.id());
        }
        options.dimensionIds(dimensionIds);
        JSONObject deterministic = loaded.deterministic();
        JSONObject style = deterministic == null ? null : deterministic.getJSONObject("style");
        List<String> hardIds = new ArrayList<String>();
        List<String> softIds = new ArrayList<String>();
        if (style != null) {
            JSONObject hard = style.getJSONObject("hard");
            if (hard != null) {
                hardIds.addAll(hard.keySet());
            }
            JSONObject soft = style.getJSONObject("soft");
            if (soft != null) {
                softIds.addAll(soft.keySet());
            }
        }
        options.styleCatalog(hardIds, softIds);
        return options;
    }

    private static void info(PluginLogger logger, String message) {
        if (logger != null) {
            logger.sendInfo(LOG_PREFIX + message);
        }
    }

    private static void warn(PluginLogger logger, String message) {
        if (logger != null) {
            logger.sendWarn(LOG_PREFIX + message);
        }
    }

    private static void error(PluginLogger logger, String message) {
        if (logger != null) {
            logger.sendError(LOG_PREFIX + message);
        }
    }

    private static String absolute(File file) {
        return file == null ? "（无法推导路径）" : file.getAbsolutePath();
    }

    public EvalBankLocation location() {
        return location;
    }

    public EvalBankReleaseResult release() {
        return release;
    }

    /** 内置 rubric 的释放结果；未启用 rubric 释放时为 null。 */
    public EvalRubricReleaseResult rubricRelease() {
        return rubricRelease;
    }

    public EvalRubricCache rubric() {
        return rubric;
    }

    public EvalBankCache bank() {
        return bank;
    }

    /** 首次释放后应写回 evalBankFile 的文件名；无需钉版本时为空串。 */
    public String pinFileName() {
        return pinFileName;
    }

    public List<String> warnings() {
        return warnings;
    }
}