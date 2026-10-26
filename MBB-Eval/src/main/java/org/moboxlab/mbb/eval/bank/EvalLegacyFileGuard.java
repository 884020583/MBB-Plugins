package org.moboxlab.mbb.eval.bank;

import java.io.File;

/**
 * 旧题库文件封禁（I-4，对应 docs/MBB-Eval.md §5.8.1 第 7 条、§5.8.2 末条）。
 *
 * <p>MBB-Roleplay 历史上用 {@code evals/roleplay-evals.json} 存放题库；v1 起题库文件名固定为
 * {@code bank-<version>.json}，旧文件一律不读、不改写、不删除。本类只按文件名判断，
 * 不打开文件、不读取内容、不做 JSON 解析，避免把旧文件的解析错误带进启动流程。</p>
 *
 * <p>判断不区分大小写（Windows 文件系统大小写不敏感），并允许传入裸文件名、相对路径或
 * 绝对路径，只要最后一段是旧文件名就拒绝。</p>
 */
public final class EvalLegacyFileGuard {

    /** 旧题库文件名。 */
    public static final String LEGACY_FILE_NAME = "roleplay-evals.json";
    /** 拒绝加载旧文件时对外使用的错误码。 */
    public static final String CODE_LEGACY_BANK_FILE = "E_BOUNDARY_LEGACY_BANK";
    /** 中文提示；调用方可直接写日志。 */
    public static final String MESSAGE = "检测到旧题库文件 roleplay-evals.json，按规格 §5.8.2 不读取该文件；"
            + "请改用 bank-<version>.json 并在 evalBankFile 中配置新文件";

    private EvalLegacyFileGuard() {
    }

    /** 文件名判定结果：allowed 为 false 时 message 为中文拒绝原因。 */
    public static final class Decision {

        private final boolean allowed;
        private final String fileName;
        private final String message;

        private Decision(boolean allowed, String fileName, String message) {
            this.allowed = allowed;
            this.fileName = fileName == null ? "" : fileName;
            this.message = message == null ? "" : message;
        }

        private static Decision allowed(String fileName) {
            return new Decision(true, fileName, "");
        }

        private static Decision rejected(String fileName, String message) {
            return new Decision(false, fileName, message);
        }

        /** 是否允许继续加载；旧文件固定为 false。 */
        public boolean allowed() {
            return allowed;
        }

        /** 被检查的文件名（含路径时保留调用方传入的形态）。 */
        public String fileName() {
            return fileName;
        }

        /** 中文拒绝原因；允许加载时为空字符串。 */
        public String message() {
            return message;
        }
    }

    /**
     * 只按文件名判断是否为旧题库文件。
     *
     * @param nameOrPath 裸文件名、相对路径或绝对路径；null 或空白返回 false
     */
    public static boolean isLegacyFileName(String nameOrPath) {
        if (nameOrPath == null) {
            return false;
        }
        String normalized = nameOrPath.trim().replace('\\', '/');
        if (normalized.isEmpty()) {
            return false;
        }
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return LEGACY_FILE_NAME.equalsIgnoreCase(fileName);
    }

    /** 只按文件名判断 File 是否为旧题库文件；不访问文件内容。 */
    public static boolean isLegacyFile(File file) {
        return file != null && isLegacyFileName(file.getName());
    }

    /** 检查 File 路径，返回允许与否及中文提示；null 视为无需拒绝。 */
    public static Decision check(File file) {
        if (file == null) {
            return Decision.allowed("");
        }
        if (isLegacyFile(file)) {
            return Decision.rejected(file.getName(), messageFor(file.getPath()));
        }
        return Decision.allowed(file.getName());
    }

    /** 检查配置值（裸文件名或相对/绝对路径），返回允许与否及中文提示。 */
    public static Decision check(String configuredValue) {
        if (configuredValue == null || configuredValue.trim().isEmpty()) {
            return Decision.allowed("");
        }
        String value = configuredValue.trim();
        if (isLegacyFileName(value)) {
            return Decision.rejected(value, messageFor(value));
        }
        return Decision.allowed(value);
    }

    /**
     * 加载前自检：命中旧文件名时抛 IllegalArgumentException，由调用方转成拒绝回执，
     * 不得继续打开或解析文件。
     */
    public static void assertNotLegacy(File file) {
        Decision decision = check(file);
        if (!decision.allowed()) {
            throw new IllegalArgumentException(decision.message());
        }
    }

    private static String messageFor(String value) {
        return MESSAGE + "（当前路径：" + value + "）";
    }
}
