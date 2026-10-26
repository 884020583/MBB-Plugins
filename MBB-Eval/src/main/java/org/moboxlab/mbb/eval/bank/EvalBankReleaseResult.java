package org.moboxlab.mbb.eval.bank;

import java.io.File;

/**
 * 内置题库释放结果（B-2）。
 *
 * <p>用状态而不是异常表达「已释放 / 已存在跳过 / 自定义文件跳过 / 目录不可用 / 配置非法 / 失败」，
 * 便于 onEnable 只记日志、不中断插件启用（§5.8.1 第 6 条、§5.7.4 加载时机）。</p>
 */
public final class EvalBankReleaseResult {

    /** 释放动作的最终状态。 */
    public enum Status {
        /** 已写入内置题库。 */
        RELEASED,
        /** 目标文件已存在，按 §5.8.1 第 2 条不覆盖、不删除。 */
        SKIPPED_EXISTS,
        /** 配置指向用户自定义文件且文件缺失，不创建空文件（§5.8.1 第 5 条）。 */
        SKIPPED_CUSTOM,
        /** evals 目录不存在或不可用，按 §5.8.2 报 E_ROLEPLAY_DATA_NOT_FOUND。 */
        SKIPPED_NO_DATA_DIRECTORY,
        /** evalBankFile 配置非法，无法定位题库。 */
        SKIPPED_INVALID,
        /** 读取内置资源或写盘失败，按 §5.8.2 报 E_BANK_READ。 */
        FAILED
    }

    private final Status status;
    private final File bankFile;
    private final int bytesWritten;
    private final String errorCode;
    private final String message;

    private EvalBankReleaseResult(Status status, File bankFile, int bytesWritten, String errorCode, String message) {
        this.status = status;
        this.bankFile = bankFile;
        this.bytesWritten = bytesWritten;
        this.errorCode = errorCode == null ? "" : errorCode;
        this.message = message == null ? "" : message;
    }

    static EvalBankReleaseResult released(File bankFile, int bytesWritten) {
        return new EvalBankReleaseResult(Status.RELEASED, bankFile, bytesWritten, "", "已释放内置题库");
    }

    static EvalBankReleaseResult skipped(Status status, File bankFile, String errorCode, String message) {
        return new EvalBankReleaseResult(status, bankFile, 0, errorCode, message);
    }

    static EvalBankReleaseResult failed(File bankFile, String errorCode, String message) {
        return new EvalBankReleaseResult(Status.FAILED, bankFile, 0, errorCode, message);
    }

    public Status status() {
        return status;
    }

    public boolean released() {
        return status == Status.RELEASED;
    }

    public boolean failed() {
        return status == Status.FAILED;
    }

    /** 目标文件路径，可能为 null（定位结果为空时）。 */
    public File bankFile() {
        return bankFile;
    }

    public int bytesWritten() {
        return bytesWritten;
    }

    /** 错误码：E_BANK_READ / E_ROLEPLAY_DATA_NOT_FOUND；成功或正常跳过时为空字符串。 */
    public String errorCode() {
        return errorCode;
    }

    /** 中文说明，失败时包含目标绝对路径（§5.8.1 第 6 条）。 */
    public String message() {
        return message;
    }
}
