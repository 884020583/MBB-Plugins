package org.moboxlab.mbb.eval.bank;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 题库定位结果（B-2）。
 *
 * <p>只描述「题库文件在哪、是否已存在、来自内置版本还是自定义配置」，
 * 不读取文件内容、不创建目录、不写盘。目录可用性按 docs/MBB-Eval.md §5.8.2 的降级表分类：
 * plugins 父目录不可用或 evals 目录缺失/不可读时，调用方应报 E_ROLEPLAY_DATA_NOT_FOUND。</p>
 */
public final class EvalBankLocation {

    /** evals 目录状态，对应 §5.8.2 降级表的第一行。 */
    public enum DirectoryStatus {
        /** 目录存在且可读，可以加载或释放题库。 */
        READY,
        /** 无法从插件数据目录推导出 plugins 根目录，或该目录不存在。 */
        PLUGINS_ROOT_MISSING,
        /** MBB-Roleplay/evals 目录不存在。 */
        EVALS_MISSING,
        /** evals 路径存在但不是目录。 */
        EVALS_NOT_DIRECTORY,
        /** evals 目录存在但不可读（权限不足）。 */
        EVALS_UNREADABLE
    }

    private final File dataFolder;
    private final File pluginsRoot;
    private final File evalsDirectory;
    private final File bankFile;
    private final String bankFileName;
    private final String invalidReason;
    private final boolean fromBuiltin;
    private final boolean absoluteConfigured;
    private final boolean valid;
    private final boolean configBlank;
    private final boolean exists;
    private final DirectoryStatus directoryStatus;
    private final List<String> warnings;

    EvalBankLocation(File dataFolder, File pluginsRoot, File evalsDirectory, File bankFile, String bankFileName,
            String invalidReason, boolean fromBuiltin, boolean absoluteConfigured, boolean valid, boolean configBlank,
            boolean exists, DirectoryStatus directoryStatus, List<String> warnings) {
        this.dataFolder = dataFolder;
        this.pluginsRoot = pluginsRoot;
        this.evalsDirectory = evalsDirectory;
        this.bankFile = bankFile;
        this.bankFileName = bankFileName;
        this.invalidReason = invalidReason;
        this.fromBuiltin = fromBuiltin;
        this.absoluteConfigured = absoluteConfigured;
        this.valid = valid;
        this.configBlank = configBlank;
        this.exists = exists;
        this.directoryStatus = directoryStatus;
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public File dataFolder() {
        return dataFolder;
    }

    public File pluginsRoot() {
        return pluginsRoot;
    }

    /** evals 目录路径（未必存在）。 */
    public File evalsDirectory() {
        return evalsDirectory;
    }

    /** 解析后的题库文件路径，可能不存在；非法配置时保留候选路径供报错回显。 */
    public File bankFile() {
        return bankFile;
    }

    /** 题库文件名，例如 bank-1.0.0.json。 */
    public String bankFileName() {
        return bankFileName;
    }

    /** 配置是否合法；非法时 {@link #invalidReason()} 给出中文原因。 */
    public boolean valid() {
        return valid;
    }

    public String invalidReason() {
        return invalidReason;
    }

    /**
     * 是否使用内置默认题库（evalBankFile 为空）。
     *
     * <p>显式配置（例如首次释放后钉成 bank-1.0.0.json）时为 false；此类配置是否指向内置版本文件
     * 需要按 JAR 资源探测，见 {@link EvalBankReleaser}。</p>
     */
    public boolean fromBuiltin() {
        return fromBuiltin;
    }

    /** evalBankFile 是否写了绝对路径（§5.1 要求对外统一写相对路径）。 */
    public boolean absoluteConfigured() {
        return absoluteConfigured;
    }

    /** evalBankFile 是否为空（首次启动需要钉版本）。 */
    public boolean configBlank() {
        return configBlank;
    }

    /** 题库文件当前是否存在且为普通文件。 */
    public boolean exists() {
        return exists;
    }

    /** evals 目录是否可读写使用（只有 READY 才允许释放或加载）。 */
    public boolean dataDirectoryUsable() {
        return directoryStatus == DirectoryStatus.READY;
    }

    public DirectoryStatus directoryStatus() {
        return directoryStatus;
    }

    /** 路径相关的非致命提示（绝对路径、越出 evals 目录等），由调用方决定是否记日志。 */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * 首次释放后应写回 evalBankFile 的取值；不需要钉版本时返回空字符串。
     *
     * <p>按 §5.8.1 第 4 条，只有配置为空且使用内置版本时才钉成当时默认文件名；
     * 自定义配置一律不改动。</p>
     */
    public String pinTargetFileName() {
        if (!valid || !configBlank || !fromBuiltin) {
            return "";
        }
        return bankFileName;
    }
}
