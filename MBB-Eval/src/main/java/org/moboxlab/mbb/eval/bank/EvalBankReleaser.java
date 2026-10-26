package org.moboxlab.mbb.eval.bank;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * 内置题库释放器（B-2，对应 §11.1 包职责中的「题库释放」，后续切片由 BankService 统一编排）。
 *
 * <p>严格按 docs/MBB-Eval.md §5.8.1 执行：</p>
 *
 * <ul>
 *   <li>只在目标文件缺失时释放，已存在绝不覆盖、绝不删除；</li>
 *   <li>配置为空时释放内置默认版本；配置指向内置版本文件（如首次释放后钉成的 bank-1.0.0.json）时
 *       按 JAR 资源探测结果重放，指向用户自定义文件时只报告缺失，不创建空文件、不猜测版本；</li>
 *   <li>先写同目录临时文件，再原子改名，失败时清理临时文件，目录内不留半写文件；</li>
 *   <li>evals 目录不可用时按 §5.8.2 报 E_ROLEPLAY_DATA_NOT_FOUND，不自行创建该目录；</li>
 *   <li>释放失败记 ERROR 并在消息中附目标绝对路径。</li>
 * </ul>
 *
 * <p>本类不解析题库内容、不校验字段，校验由后续切片负责。</p>
 */
public final class EvalBankReleaser {

    private EvalBankReleaser() {
    }

    /**
     * 按定位结果释放内置题库。
     *
     * @param location 题库定位结果
     * @param resources JAR 资源读取器
     * @param logger 插件日志；为 null 时静默
     * @return 释放结果，不抛异常
     */
    public static EvalBankReleaseResult release(EvalBankLocation location, EvalBankResources resources,
            PluginLogger logger) {
        if (location == null) {
            return fail(null, EvalBankLocator.CODE_BANK_READ, "题库定位结果为空，无法释放内置题库", logger);
        }
        File target = location.bankFile();
        if (!location.valid()) {
            return fail(target, EvalBankLocator.CODE_BANK_READ, location.invalidReason(), logger);
        }
        if (target != null && target.isFile()) {
            return EvalBankReleaseResult.skipped(EvalBankReleaseResult.Status.SKIPPED_EXISTS, target, "",
                    "题库文件已存在，按规格不覆盖：" + absolute(target));
        }
        if (location.absoluteConfigured()) {
            return EvalBankReleaseResult.skipped(EvalBankReleaseResult.Status.SKIPPED_CUSTOM, target,
                    EvalBankLocator.CODE_BANK_READ,
                    "evalBankFile 使用了绝对路径且文件不存在，按 §5.8.1 第 2 条只向 evals/ 释放，"
                            + "不写绝对路径目标：" + absolute(target));
        }
        if (!location.dataDirectoryUsable()) {
            String message = "评测数据目录不可用（" + location.directoryStatus() + "），跳过内置题库释放："
                    + absolute(location.evalsDirectory()) + "（" + EvalBankLocator.CODE_DATA_NOT_FOUND + "）";
            if (logger != null) {
                logger.sendWarn(message);
            }
            return EvalBankReleaseResult.skipped(EvalBankReleaseResult.Status.SKIPPED_NO_DATA_DIRECTORY, target,
                    EvalBankLocator.CODE_DATA_NOT_FOUND, message);
        }
        if (resources == null) {
            return fail(target, EvalBankLocator.CODE_BANK_READ, "未提供内置资源读取器，无法释放题库", logger);
        }
        String resourcePath = EvalBankLocator.builtinBankResourcePath(location.bankFileName());
        byte[] payload;
        try {
            payload = resources.read(resourcePath);
        } catch (IOException e) {
            return fail(target, EvalBankLocator.CODE_BANK_READ,
                    "读取内置题库资源失败：" + resourcePath + "，原因：" + reason(e), logger);
        }
        if (payload == null || payload.length == 0) {
            if (!location.configBlank()) {
                // 显式配置且 JAR 内没有同名资源：属于用户自定义题库缺失，不创建空文件（§5.8.1 第 5 条）。
                return EvalBankReleaseResult.skipped(EvalBankReleaseResult.Status.SKIPPED_CUSTOM, target,
                        EvalBankLocator.CODE_BANK_READ,
                        "evalBankFile 指向的题库不存在，且不是内置题库版本文件，"
                                + "不创建空文件、不猜测版本：" + absolute(target));
            }
            return fail(target, EvalBankLocator.CODE_BANK_READ,
                    "内置题库资源缺失或为空：" + resourcePath, logger);
        }
        File parent = target == null ? null : target.getParentFile();
        if (parent == null) {
            return fail(target, EvalBankLocator.CODE_BANK_READ, "无法确定题库目标目录", logger);
        }
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            return fail(target, EvalBankLocator.CODE_BANK_READ,
                    "创建题库目标目录失败：" + absolute(parent), logger);
        }

        Path temp = null;
        try {
            temp = Files.createTempFile(parent.toPath(), ".bank-", ".tmp");
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(payload);
            }
            moveIntoPlace(temp, target);
            temp = null;
        } catch (FileAlreadyExistsException e) {
            if (target.isFile()) {
                return EvalBankReleaseResult.skipped(EvalBankReleaseResult.Status.SKIPPED_EXISTS, target, "",
                        "题库文件已存在，按规格不覆盖：" + absolute(target));
            }
            return fail(target, EvalBankLocator.CODE_BANK_READ,
                    "题库目标路径被目录占用，无法写入：" + absolute(target), logger);
        } catch (AccessDeniedException e) {
            if (target.isFile()) {
                return EvalBankReleaseResult.skipped(EvalBankReleaseResult.Status.SKIPPED_EXISTS, target, "",
                        "题库文件已存在，按规格不覆盖：" + absolute(target));
            }
            return fail(target, EvalBankLocator.CODE_BANK_READ,
                    "无权写入题库目标路径：" + absolute(target) + "，原因：" + reason(e), logger);
        } catch (IOException e) {
            return fail(target, EvalBankLocator.CODE_BANK_READ,
                    "写入题库文件失败：" + absolute(target) + "，原因：" + reason(e), logger);
        } finally {
            deleteQuietly(temp);
        }

        if (target == null || !target.isFile() || target.length() != payload.length) {
            return fail(target, EvalBankLocator.CODE_BANK_READ,
                    "题库写入后校验失败，文件缺失或长度不一致：" + absolute(target), logger);
        }
        if (logger != null) {
            logger.sendInfo("已释放内置题库：" + absolute(target) + "（" + payload.length
                    + " 字节，来源 JAR 资源 " + resourcePath + "）");
        }
        return EvalBankReleaseResult.released(target, payload.length);
    }

    /** 优先原子改名；文件系统不支持时退化为普通改名（仍然不覆盖既有文件）。 */
    private static void moveIntoPlace(Path temp, File target) throws IOException {
        try {
            Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target.toPath());
        }
    }

    private static EvalBankReleaseResult fail(File target, String errorCode, String message, PluginLogger logger) {
        String detail = message == null ? "" : message;
        if (target != null) {
            detail = detail + "，目标路径：" + absolute(target);
        }
        if (logger != null) {
            logger.sendError("释放内置题库失败（" + errorCode + "）：" + detail);
        }
        return EvalBankReleaseResult.failed(target, errorCode, detail);
    }

    private static void deleteQuietly(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // 清理失败不改变释放结果，最多留一个临时文件；调用方只关心是否成功。
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
