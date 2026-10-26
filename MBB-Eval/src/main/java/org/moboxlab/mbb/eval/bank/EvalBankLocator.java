package org.moboxlab.mbb.eval.bank;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 题库路径定位（B-2，对应 §11.1 包职责中的「题库定位」，后续切片以 BankService 承担加载校验）。
 *
 * <p>按 docs/MBB-Eval.md §5.1 的路径口径推导题库位置：</p>
 *
 * <pre>
 * File pluginsRoot = new File(getDataFolder()).getParentFile();   // ./MoBoxBot/plugins
 * File evalsDir = new File(pluginsRoot, "MBB-Roleplay/evals");
 * File bankFile = new File(evalsDir, evalBankFile);                // bank-&lt;version&gt;.json
 * </pre>
 *
 * <p>本类只做纯推导与只读探测：不创建目录、不写文件、不解析题库内容。
 * evalBankFile 为空时使用内置版本默认文件名（§12.1）；相对路径相对 evals/；
 * 绝对路径允许但给出提示（§5.1 第 1 条要求对外统一写相对路径）。</p>
 */
public final class EvalBankLocator {

    /** 评测数据目录名（位于 MBB-Roleplay 插件数据目录下）。 */
    public static final String EVALS_DIRECTORY = "evals";
    /** MBB-Roleplay 插件目录名。 */
    public static final String ROLEPLAY_PLUGIN_DIRECTORY = "MBB-Roleplay";
    /** 当前内置题库版本；升级时只新增对应版本的 JAR 资源（§5.8.1 第 3 条）。 */
    public static final String BUILTIN_BANK_VERSION = "1.0.0";
    /** JAR 内置题库资源目录。 */
    public static final String BUILTIN_RESOURCE_DIRECTORY = "bank/";
    /** 默认题库文件名。 */
    public static final String DEFAULT_BANK_FILE_NAME = "bank-" + BUILTIN_BANK_VERSION + ".json";
    /** 降级表：plugins 父目录不可用或 evals 不存在/不可读（§5.8.2）。 */
    public static final String CODE_DATA_NOT_FOUND = "E_ROLEPLAY_DATA_NOT_FOUND";
    /** 降级表：题库缺失且内置释放失败（§5.8.2）。 */
    public static final String CODE_BANK_READ = "E_BANK_READ";
    /** 提示：evalBankFile 写了绝对路径。 */
    public static final String WARN_ABSOLUTE_PATH = "evalBankFile 使用了绝对路径，建议改为相对 evals/ 的路径";
    /** 提示：相对路径越出 evals/ 目录。 */
    public static final String WARN_ESCAPES_EVALS = "evalBankFile 相对路径越出了 evals/ 目录";

    private EvalBankLocator() {
    }

    /** 用当前内置版本定位题库。 */
    public static EvalBankLocation locate(File dataFolder, String evalBankFile) {
        return locate(dataFolder, evalBankFile, BUILTIN_BANK_VERSION);
    }

    /**
     * 定位题库文件。
     *
     * @param dataFolder 插件数据目录（Plugin.getDataFolder()），其父目录即 ./MoBoxBot/plugins
     * @param evalBankFile 配置值；null 或空白视为未配置
     * @param builtinVersion 内置题库版本，用于推导默认文件名
     * @return 只读定位结果；目录不可用或路径非法时通过状态字段表达，不抛异常
     */
    public static EvalBankLocation locate(File dataFolder, String evalBankFile, String builtinVersion) {
        String version = isBlank(builtinVersion) ? BUILTIN_BANK_VERSION : builtinVersion.trim();
        String configured = evalBankFile == null ? "" : evalBankFile.trim();
        boolean configBlank = configured.isEmpty();

        File pluginsRoot = dataFolder == null ? null : dataFolder.getParentFile();
        File evalsDirectory = pluginsRoot == null ? null
                : new File(new File(pluginsRoot, ROLEPLAY_PLUGIN_DIRECTORY), EVALS_DIRECTORY);
        EvalBankLocation.DirectoryStatus status = inspectDirectory(pluginsRoot, evalsDirectory);

        List<String> warnings = new ArrayList<String>();
        String bankFileName;
        File bankFile;
        boolean fromBuiltin;
        boolean absoluteConfigured = false;
        boolean valid = true;
        String invalidReason = "";

        if (configBlank) {
            bankFileName = defaultBankFileName(version);
            fromBuiltin = true;
            bankFile = evalsDirectory == null ? null : new File(evalsDirectory, bankFileName);
            if (evalsDirectory == null) {
                valid = false;
                invalidReason = "无法从插件数据目录推导 plugins 目录，不能定位内置题库";
            }
        } else if (isAbsolutePath(configured)) {
            bankFileName = configured;
            fromBuiltin = false;
            absoluteConfigured = true;
            bankFile = new File(configured);
            warnings.add(WARN_ABSOLUTE_PATH);
        } else {
            String relative = normalize(configured);
            bankFileName = relative;
            fromBuiltin = false;
            bankFile = evalsDirectory == null ? null : new File(evalsDirectory, relative);
            String illegal = illegalReason(relative);
            if (!illegal.isEmpty()) {
                valid = false;
                invalidReason = illegal;
            } else if (escapesEvals(relative)) {
                warnings.add(WARN_ESCAPES_EVALS);
            }
            if (evalsDirectory == null && valid) {
                valid = false;
                invalidReason = "无法从插件数据目录推导 plugins 目录，不能定位题库";
            }
        }

        // §5.8.2：旧 roleplay-evals.json 一律不读；只判文件名，不打开内容。
        if (valid && !configBlank && EvalLegacyFileGuard.isLegacyFileName(configured)) {
            valid = false;
            invalidReason = EvalLegacyFileGuard.check(configured).message();
        }

        boolean exists = valid && bankFile != null && bankFile.isFile();
        return new EvalBankLocation(dataFolder, pluginsRoot, evalsDirectory, bankFile, bankFileName, invalidReason,
                fromBuiltin, absoluteConfigured, valid, configBlank, exists, status, warnings);
    }

    /** 字符串便捷重载，供 onEnable 直接传入 Plugin.getDataFolder()。 */
    public static EvalBankLocation locate(String dataFolder, String evalBankFile) {
        return locate(isBlank(dataFolder) ? null : new File(dataFolder), evalBankFile, BUILTIN_BANK_VERSION);
    }

    /** 配置值是否指向旧题库文件（roleplay-evals.json，§5.8.2 不读）；只判文件名，不读内容。 */
    public static boolean isLegacyBankFile(String evalBankFile) {
        return EvalLegacyFileGuard.isLegacyFileName(evalBankFile);
    }

    /** 默认题库文件名：bank-&lt;version&gt;.json。 */
    public static String defaultBankFileName(String version) {
        String safe = isBlank(version) ? BUILTIN_BANK_VERSION : version.trim();
        return "bank-" + safe + ".json";
    }

    /** JAR 内置资源路径：bank/&lt;文件名&gt;。 */
    public static String builtinBankResourcePath(String bankFileName) {
        return BUILTIN_RESOURCE_DIRECTORY + bankFileName;
    }

    /** 从插件数据目录推导 evals 目录；无法推导时返回 null。 */
    public static File evalsDirectory(File dataFolder) {
        if (dataFolder == null || dataFolder.getParentFile() == null) {
            return null;
        }
        return new File(new File(dataFolder.getParentFile(), ROLEPLAY_PLUGIN_DIRECTORY), EVALS_DIRECTORY);
    }

    private static EvalBankLocation.DirectoryStatus inspectDirectory(File pluginsRoot, File evalsDirectory) {
        if (pluginsRoot == null || !pluginsRoot.isDirectory()) {
            return EvalBankLocation.DirectoryStatus.PLUGINS_ROOT_MISSING;
        }
        if (evalsDirectory == null || !evalsDirectory.exists()) {
            return EvalBankLocation.DirectoryStatus.EVALS_MISSING;
        }
        if (!evalsDirectory.isDirectory()) {
            return EvalBankLocation.DirectoryStatus.EVALS_NOT_DIRECTORY;
        }
        if (!evalsDirectory.canRead() || evalsDirectory.list() == null) {
            return EvalBankLocation.DirectoryStatus.EVALS_UNREADABLE;
        }
        return EvalBankLocation.DirectoryStatus.READY;
    }

    /** 统一用斜杠；去掉开头的 ./，避免拼出 evals/./bank.json。 */
    private static String normalize(String configured) {
        String relative = configured.replace('\\', '/');
        while (relative.startsWith("./")) {
            relative = relative.substring(2);
        }
        return relative;
    }

    /** 判断是否为绝对路径：Windows 盘符、UNC、以分隔符开头，或 java.io.File 认为的绝对路径。 */
    private static boolean isAbsolutePath(String configured) {
        if (configured.startsWith("/") || configured.startsWith("\\")) {
            return true;
        }
        if (configured.length() >= 2 && Character.isLetter(configured.charAt(0)) && configured.charAt(1) == ':') {
            return true;
        }
        return new File(configured).isAbsolute();
    }

    /** 非法相对路径：空、以分隔符结尾、含 NUL，或就是目录占位符。 */
    private static String illegalReason(String relative) {
        if (relative.isEmpty() || ".".equals(relative) || "..".equals(relative)) {
            return "evalBankFile 为空或只是目录占位符，必须指向 evals/ 下的题库文件";
        }
        if (relative.endsWith("/")) {
            return "evalBankFile 以分隔符结尾，必须指向 evals/ 下的题库文件：" + relative;
        }
        if (relative.indexOf('\0') >= 0) {
            return "evalBankFile 含非法字符，无法作为文件路径";
        }
        return "";
    }

    /** 相对路径逐级归一后是否越出 evals/。 */
    private static boolean escapesEvals(String relative) {
        int depth = 0;
        String[] segments = relative.split("/");
        for (String segment : segments) {
            if (".".equals(segment) || segment.isEmpty()) {
                continue;
            }
            if ("..".equals(segment)) {
                depth--;
                if (depth < 0) {
                    return true;
                }
            } else {
                depth++;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
