package org.moboxlab.mbb.eval.bank;

import java.io.File;

/**
 * 内置 rubric 释放结果（R-1）。
 *
 * <p>与 {@link EvalBankReleaseResult} 同一口径：用状态表达「已释放 / 已存在跳过 / 目录不可用 / 失败」，
 * 让 onEnable 只记日志、不抛异常（§5.8.1、§5.7.4）。</p>
 */
public final class EvalRubricReleaseResult {

    /** 释放动作的最终状态。 */
    public enum Status {
        /** 已写入内置 rubric。 */
        RELEASED,
        /** evals 目录下已存在 rubric 文件，按 §5.8.1 第 2 条不覆盖、不删除、不改写。 */
        SKIPPED_EXISTS,
        /** evals 目录不存在或不可用，按 §5.8.2 报 E_ROLEPLAY_DATA_NOT_FOUND。 */
        SKIPPED_NO_DATA_DIRECTORY,
        /** 读取内置资源、写入前自检或写盘失败，报 E_RUBRIC_*。 */
        FAILED
    }

    private final Status status;
    private final File rubricFile;
    private final int bytesWritten;
    private final String errorCode;
    private final String message;

    private EvalRubricReleaseResult(Status status, File rubricFile, int bytesWritten,
            String errorCode, String message) {
        this.status = status;
        this.rubricFile = rubricFile;
        this.bytesWritten = bytesWritten;
        this.errorCode = errorCode == null ? "" : errorCode;
        this.message = message == null ? "" : message;
    }

    static EvalRubricReleaseResult released(File rubricFile, int bytesWritten) {
        return new EvalRubricReleaseResult(Status.RELEASED, rubricFile, bytesWritten, "", "已释放内置 rubric");
    }

    static EvalRubricReleaseResult skipped(Status status, File rubricFile, String errorCode, String message) {
        return new EvalRubricReleaseResult(status, rubricFile, 0, errorCode, message);
    }

    static EvalRubricReleaseResult failed(File rubricFile, String errorCode, String message) {
        return new EvalRubricReleaseResult(Status.FAILED, rubricFile, 0, errorCode, message);
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
    public File rubricFile() {
        return rubricFile;
    }

    public int bytesWritten() {
        return bytesWritten;
    }

    /** 错误码：E_RUBRIC_* 或 E_ROLEPLAY_DATA_NOT_FOUND；成功或正常跳过时为空字符串。 */
    public String errorCode() {
        return errorCode;
    }

    /** 中文说明，失败时包含目标绝对路径（§5.8.1 第 6 条）。 */
    public String message() {
        return message;
    }
}
