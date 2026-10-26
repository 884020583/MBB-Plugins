package org.moboxlab.mbb.eval.bank;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.moboxlab.mbb.eval.rubric.EvalRubric;
import org.moboxlab.mbb.eval.rubric.EvalRubricLoadResult;
import org.moboxlab.mbb.eval.rubric.EvalRubricLoader;
import org.moboxlab.mbb.eval.rubric.EvalRubricProblem;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * 内置 rubric 释放器（R-1，对应 §11.1「内置题库与默认 rubric 资源，首次启动释放」）。
 *
 * <p>严格按 §5.8.1 的释放口径执行：</p>
 *
 * <ul>
 *   <li>只在 evals 目录下不存在任何 rubric-*.json 时释放；已存在同名或其它版本的 rubric
 *       一律不覆盖、不删除、不改写（B28 允许用户编辑 rubric 副本，删掉整个文件后才会重新释放）；</li>
 *   <li>写入前把内置资源写到同目录临时子目录，用 {@link EvalRubricLoader} 做一次完整自检
 *       （version 与内容 sha256 一致、文件名一致、promptVersion 匹配、五维与权重合规），
 *       自检不通过报 E_RUBRIC_* 并记 ERROR，目标文件一个字节都不写；</li>
 *   <li>自检通过后用原子改名落盘，文件系统不支持原子改名时退化为普通改名；</li>
 *   <li>evals 目录不可用时按 §5.8.2 报 E_ROLEPLAY_DATA_NOT_FOUND，不自行创建该目录；</li>
 *   <li>释放成功记 INFO 摘要（文件名、version、promptVersion、五维权重）。</li>
 * </ul>
 *
 * <p>生产环境的资源读取由 {@link EvalBankResources}（JAR 读取接口）提供，单测可注入伪造字节。</p>
 */
public final class EvalRubricReleaser {

    /** 内置 rubric 语义版本（§6.5.4 首版按 1.1.0 落地，后缀为内容 sha256 前 8 位）。 */
    public static final String BUILTIN_RUBRIC_VERSION = "1.1.0-c32119f4";
    /** 内置 rubric 文件名，必须与 version 自指。 */
    public static final String BUILTIN_RUBRIC_FILE_NAME = "rubric-" + BUILTIN_RUBRIC_VERSION + ".json";
    /** 内置 rubric 的 promptVersion：与代码支持的 judge prompt 版本一致（EvalJudgePrompt.PROMPT_VERSION）。 */
    public static final String BUILTIN_RUBRIC_PROMPT_VERSION = "1";
    /** JAR 内资源路径（§11.1：src/main/resources/bank/）。 */
    public static final String BUILTIN_RUBRIC_RESOURCE_PATH = "bank/" + BUILTIN_RUBRIC_FILE_NAME;

    private EvalRubricReleaser() {
    }

    /**
     * 按定位结果释放内置 rubric。
     *
     * @param location  题库定位结果（只用来取 evals 目录与目录可用性），可为 null
     * @param resources JAR 资源读取器，可为 null
     * @param logger    插件日志；为 null 时静默
     * @return 释放结果，不抛异常
     */
    public static EvalRubricReleaseResult release(EvalBankLocation location,
            EvalBankResources resources, PluginLogger logger) {
        if (location == null) {
            return fail(null, EvalRubricProblem.CODE_READ,
                    "评测数据目录定位结果为空，无法释放内置 rubric", logger);
        }
        File evals = location.evalsDirectory();
        File target = evals == null ? null : new File(evals, BUILTIN_RUBRIC_FILE_NAME);
        if (!location.dataDirectoryUsable()) {
            String message = "评测数据目录不可用（" + location.directoryStatus()
                    + "），跳过内置 rubric 释放：" + absolute(evals)
                    + "（" + EvalBankLocator.CODE_DATA_NOT_FOUND + "）";
            if (logger != null) {
                logger.sendWarn(message);
            }
            return EvalRubricReleaseResult.skipped(
                    EvalRubricReleaseResult.Status.SKIPPED_NO_DATA_DIRECTORY, target,
                    EvalBankLocator.CODE_DATA_NOT_FOUND, message);
        }
        File existing = findExistingRubric(evals);
        if (existing != null) {
            // 已存在任何版本的 rubric：按 §5.8.1 不覆盖、不删除、不改写，也不再读资源。
            return EvalRubricReleaseResult.skipped(EvalRubricReleaseResult.Status.SKIPPED_EXISTS,
                    target, "", "已存在 rubric 文件，按规格不覆盖：" + absolute(existing));
        }
        if (resources == null) {
            return fail(target, EvalRubricProblem.CODE_READ,
                    "未提供内置资源读取器，无法释放 rubric", logger);
        }
        byte[] payload;
        try {
            payload = resources.read(BUILTIN_RUBRIC_RESOURCE_PATH);
        } catch (IOException e) {
            return fail(target, EvalRubricProblem.CODE_READ,
                    "读取内置 rubric 资源失败：" + BUILTIN_RUBRIC_RESOURCE_PATH
                            + "，原因：" + reason(e), logger);
        }
        if (payload == null || payload.length == 0) {
            return fail(target, EvalRubricProblem.CODE_READ,
                    "内置 rubric 资源缺失或为空：" + BUILTIN_RUBRIC_RESOURCE_PATH, logger);
        }

        // 写入前自检：临时子目录里的文件名与 version 一致，loader 才能完整校验 version 自指、
        // 内容 sha256、promptVersion、五维 id 与权重和；自检不过则目标文件不落盘。
        Path tempDir = null;
        Path tempFile = null;
        EvalRubricLoadResult check;
        try {
            tempDir = Files.createTempDirectory(evals.toPath(), ".rubric-check-");
            tempFile = tempDir.resolve(BUILTIN_RUBRIC_FILE_NAME);
            Files.write(tempFile, payload);
            check = EvalRubricLoader.load(tempFile.toFile(), BUILTIN_RUBRIC_PROMPT_VERSION);
        } catch (IOException e) {
            deleteQuietly(tempFile);
            deleteQuietly(tempDir);
            return fail(target, EvalRubricProblem.CODE_READ,
                    "写入 rubric 自检临时文件失败：" + absolute(evals) + "，原因：" + reason(e), logger);
        }
        if (!check.ok()) {
            String code = EvalRubricProblem.CODE_FIELD;
            String detail = "未返回具体问题";
            List<EvalRubricProblem> problems = check.problems();
            if (problems != null && !problems.isEmpty()) {
                code = problems.get(0).code();
                StringBuilder text = new StringBuilder();
                for (EvalRubricProblem problem : problems) {
                    if (text.length() > 0) {
                        text.append("；");
                    }
                    text.append(problem.display());
                }
                detail = text.toString();
            }
            deleteQuietly(tempFile);
            deleteQuietly(tempDir);
            return fail(target, code, "内置 rubric 自检未通过：" + detail, logger);
        }

        try {
            moveIntoPlace(tempFile, target);
            tempFile = null;
        } catch (IOException e) {
            deleteQuietly(tempFile);
            deleteQuietly(tempDir);
            return fail(target, EvalRubricProblem.CODE_READ,
                    "写入 rubric 文件失败：" + absolute(target) + "，原因：" + reason(e), logger);
        } finally {
            deleteQuietly(tempFile);
            deleteQuietly(tempDir);
        }

        if (target == null || !target.isFile() || target.length() != payload.length) {
            return fail(target, EvalRubricProblem.CODE_READ,
                    "rubric 写入后校验失败，文件缺失或长度不一致：" + absolute(target), logger);
        }
        EvalRubric rubric = check.rubric();
        if (logger != null) {
            logger.sendInfo("已释放内置 rubric：" + absolute(target) + "（" + payload.length
                    + " 字节，来源 JAR 资源 " + BUILTIN_RUBRIC_RESOURCE_PATH + "）；version="
                    + (rubric == null ? BUILTIN_RUBRIC_VERSION : rubric.version())
                    + "，promptVersion="
                    + (rubric == null ? BUILTIN_RUBRIC_PROMPT_VERSION : rubric.promptVersion())
                    + "，权重 " + weights(rubric));
        }
        return EvalRubricReleaseResult.released(target, payload.length);
    }

    /** evals 目录下第一个 rubric-*.json；不存在时返回 null。 */
    private static File findExistingRubric(File evals) {
        if (evals == null || !evals.isDirectory()) {
            return null;
        }
        File[] files = evals.listFiles();
        if (files == null) {
            return null;
        }
        List<File> found = new ArrayList<File>();
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String name = file.getName();
            if (name.startsWith("rubric-") && name.endsWith(".json")) {
                found.add(file);
            }
        }
        return found.isEmpty() ? null : found.get(0);
    }

    /** 优先原子改名；文件系统不支持时退化为普通改名（目标不存在时不会覆盖）。 */
    private static void moveIntoPlace(Path temp, File target) throws IOException {
        try {
            Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target.toPath());
        }
    }

    private static String weights(EvalRubric rubric) {
        if (rubric == null) {
            return "（自检结果缺失）";
        }
        StringBuilder text = new StringBuilder();
        for (EvalRubric.Dimension dimension : rubric.dimensions()) {
            if (text.length() > 0) {
                text.append("、");
            }
            text.append(dimension.id()).append('=')
                    .append(BigDecimal.valueOf(dimension.weight())
                            .setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        return text.toString();
    }

    private static EvalRubricReleaseResult fail(File target, String errorCode, String message,
            PluginLogger logger) {
        String detail = message == null ? "" : message;
        if (target != null) {
            detail = detail + "，目标路径：" + absolute(target);
        }
        if (logger != null) {
            logger.sendError("释放内置 rubric 失败（" + errorCode + "）：" + detail);
        }
        return EvalRubricReleaseResult.failed(target, errorCode, detail);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不改变释放结果，最多留一个临时文件。
        }
    }

    private static String absolute(File file) {
        return file == null ? "" : file.getAbsolutePath();
    }

    private static String reason(IOException e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
