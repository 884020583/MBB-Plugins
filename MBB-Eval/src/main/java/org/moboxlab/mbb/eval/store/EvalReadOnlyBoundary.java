package org.moboxlab.mbb.eval.store;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.moboxlab.mbb.eval.bank.EvalBankLocator;
import org.moboxlab.mbb.eval.bank.EvalLegacyFileGuard;

/**
 * 只读边界集中声明与自检（I-4，对应 docs/MBB-Eval.md §5.1、§5.8、§11.4、§16.10）。
 *
 * <p>把「MBB-Eval 可以读写什么」收敛到一处，供运行期自检与单测共用：</p>
 *
 * <ul>
 *   <li>写库：只允许四张 {@code plugin_mbb_eval_*} 表；SQL 必须含 {@code plugin_}、不得含 {@code bot_}，
 *       且不得出现白名单外的 {@code plugin_*} 表名（含 Roleplay 表与 trace）；</li>
 *   <li>写盘：只允许插件数据目录（报表落 {@code <dataFolder>/reports/}）与
 *       {@code ./MoBoxBot/plugins/MBB-Roleplay/evals/} 下直接的 {@code bank-<version>.json}；
 *       rubric、旧 {@code roleplay-evals.json} 与其他文件一律不在写边界内；</li>
 *   <li>读盘：旧 {@code roleplay-evals.json} 只按文件名拒绝，不读内容（§5.8.2）。</li>
 * </ul>
 *
 * <p>路径判断先转绝对路径再逐级归一（消除 {@code ..}），并且对空 dataFolder 采取 fail-closed：
 * 无法确定边界时一律判为越界，绝不因为配置缺失而放宽。</p>
 */
public final class EvalReadOnlyBoundary {

    /** 报表目录名，位于插件数据目录下。 */
    public static final String REPORTS_DIRECTORY = "reports";
    /** 对外统一书写的报表路径前缀（§5.1 第 1 条）。 */
    public static final String REPORT_BASE_PATH = "./MoBoxBot/plugins/MBB-Eval/reports/";
    /** 对外统一书写的题库目录前缀（§5.1 第 1 条）。 */
    public static final String EVALS_BASE_PATH = "./MoBoxBot/plugins/MBB-Roleplay/evals/";
    /** SQL 越界错误码。 */
    public static final String CODE_SQL_OUT_OF_BOUNDARY = "E_BOUNDARY_SQL";
    /** 写盘路径越界错误码。 */
    public static final String CODE_PATH_OUT_OF_BOUNDARY = "E_BOUNDARY_PATH";
    /** 路径写法不符合 §5.1 第 1 条时的错误码。 */
    public static final String CODE_PATH_STYLE = "E_BOUNDARY_PATH_STYLE";

    /** SQL 中 plugin_ 前缀的表名片段。 */
    private static final Pattern PLUGIN_TABLE_TOKEN = Pattern.compile("plugin_[a-z0-9_]+");
    /** 题库释放目标文件名：bank-&lt;version&gt;.json（大小写不敏感）。 */
    private static final Pattern BANK_FILE_NAME = Pattern.compile("^bank-.+\\.json$", Pattern.CASE_INSENSITIVE);
    /** 白名单事实源直接复用建表登记表，避免两处漂移。 */
    private static final List<String> ALLOWED_TABLES = Collections.unmodifiableList(
            new ArrayList<String>(EvalSchemaService.tableNames()));

    private final File dataFolder;
    private final File reportsDirectory;
    private final File evalsDirectory;
    private final Path dataFolderPath;
    private final Path evalsPath;

    /**
     * @param dataFolder 插件数据目录（Plugin.getDataFolder()）；为 null 时边界 fail-closed
     */
    public EvalReadOnlyBoundary(File dataFolder) {
        this.dataFolder = normalize(dataFolder);
        this.reportsDirectory = this.dataFolder == null ? null : new File(this.dataFolder, REPORTS_DIRECTORY);
        this.evalsDirectory = EvalBankLocator.evalsDirectory(this.dataFolder);
        this.dataFolderPath = pathOf(this.dataFolder);
        this.evalsPath = pathOf(this.evalsDirectory);
    }

    /** 允许写的四张表，顺序与建表登记表一致。 */
    public static List<String> allowedTables() {
        return ALLOWED_TABLES;
    }

    /** 表名是否在白名单内（大小写不敏感）。 */
    public static boolean isAllowedTable(String table) {
        if (table == null || table.trim().isEmpty()) {
            return false;
        }
        return ALLOWED_TABLES.contains(table.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 校验一条 SQL 是否落在只读边界内。
     *
     * @param statement 待执行 SQL；不能为空
     * @param table 该语句声明的目标表；为空时只校验语句本身
     * @return 空字符串表示通过，否则为中文越界原因
     */
    public static String sqlViolation(String statement, String table) {
        if (statement == null || statement.trim().isEmpty()) {
            return "SQL 语句为空，无法判定只读边界";
        }
        String lower = statement.toLowerCase(Locale.ROOT);
        if (lower.contains("bot_")) {
            return "SQL 不得访问 bot_ 前缀的对象：" + statement;
        }
        List<String> referenced = new ArrayList<String>();
        Matcher matcher = PLUGIN_TABLE_TOKEN.matcher(lower);
        while (matcher.find()) {
            String token = matcher.group();
            if (!referenced.contains(token)) {
                referenced.add(token);
            }
        }
        for (String token : referenced) {
            if (!ALLOWED_TABLES.contains(token)) {
                return "SQL 访问了白名单外的表 " + token + "，只允许四张 plugin_mbb_eval_* 表";
            }
        }
        if (table != null && !table.trim().isEmpty()) {
            String declared = table.trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_TABLES.contains(declared)) {
                return "声明表不在白名单内：" + table.trim() + "，只允许四张 plugin_mbb_eval_* 表";
            }
            if (!referenced.contains(declared)) {
                return "SQL 与声明表不一致，语句中未出现 " + table.trim();
            }
            return "";
        }
        if (referenced.isEmpty()) {
            return "SQL 未引用任何 plugin_ 表，必须含 plugin_ 且不得含 bot_：" + statement;
        }
        return "";
    }

    /** 同 {@link #sqlViolation}，越界时抛 IllegalArgumentException，供运行期自检使用。 */
    public static void assertSql(String statement, String table) {
        String violation = sqlViolation(statement, table);
        if (!violation.isEmpty()) {
            throw new IllegalArgumentException(violation);
        }
    }

    /**
     * 校验对外路径写法（§5.1 第 1 条）：统一 {@code ./MoBoxBot/plugins/...}，
     * 不得写绝对路径、{@code data/plugins/...} 或无前缀的 {@code plugins/...}。
     *
     * @param path 对外展示或写入配置的路径；空白视为无需校验
     * @return 空字符串表示通过，否则为中文越界原因
     */
    public static String pathStyleViolation(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "";
        }
        String normalized = path.trim().replace('\\', '/');
        if (normalized.startsWith("/") || normalized.startsWith("//")
                || normalized.matches("^[A-Za-z]:/.*")) {
            return "路径不得写成绝对路径，必须相对启动目录：" + path;
        }
        if (normalized.startsWith("data/plugins") || normalized.startsWith("./data/plugins")) {
            return "路径不得写成 data/plugins/...，统一写 ./MoBoxBot/plugins/...：" + path;
        }
        if (normalized.equals("plugins") || normalized.startsWith("plugins/")
                || normalized.startsWith("./plugins/")) {
            return "路径不得省略 MoBoxBot 前缀，统一写 ./MoBoxBot/plugins/...：" + path;
        }
        if (normalized.startsWith("MoBoxBot/plugins")) {
            return "路径缺少 ./ 前缀，统一写 ./MoBoxBot/plugins/...：" + path;
        }
        return "";
    }

    /** 同 {@link #pathStyleViolation}，写法越界时抛 IllegalArgumentException。 */
    public static void assertPathStyle(String path) {
        String violation = pathStyleViolation(path);
        if (!violation.isEmpty()) {
            throw new IllegalArgumentException(violation);
        }
    }

    /** 插件数据目录（已归一的绝对路径）；构造参数为空时为 null。 */
    public File dataFolder() {
        return dataFolder;
    }

    /** 报表目录：&lt;dataFolder&gt;/reports；数据目录为空时为 null。 */
    public File reportsDirectory() {
        return reportsDirectory;
    }

    /** 题库目录：./MoBoxBot/plugins/MBB-Roleplay/evals；无法推导时为 null。 */
    public File evalsDirectory() {
        return evalsDirectory;
    }

    /** 路径是否在写边界内：插件数据目录或其 evals 目录之下。 */
    public boolean pathInBoundary(File file) {
        return pathViolation(file).isEmpty();
    }

    /** 路径越界的中文原因；在边界内返回空字符串。 */
    public String pathViolation(File file) {
        if (file == null) {
            return "路径为空，无法判定是否越界";
        }
        if (dataFolderPath == null) {
            return "插件数据目录为空，无法判定路径边界（fail-closed 拒绝）：" + file.getPath();
        }
        Path candidate = pathOf(file);
        if (candidate == null) {
            return "路径非法，无法判定是否越界：" + file.getPath();
        }
        if (candidate.startsWith(dataFolderPath)) {
            return "";
        }
        if (evalsPath != null && candidate.startsWith(evalsPath)) {
            return "";
        }
        return "路径越界，只允许插件数据目录与题库目录：" + file.getPath();
    }

    /**
     * 是否为合法的题库释放目标：必须是 evals 目录下直接的 bank-&lt;version&gt;.json，
     * 且不是旧 roleplay-evals.json。rubric 由人工/发布包维护，不在本边界内。
     */
    public boolean writableBankTarget(File file) {
        if (file == null || evalsPath == null) {
            return false;
        }
        Path candidate = pathOf(file);
        if (candidate == null || candidate.getParent() == null || !evalsPath.equals(candidate.getParent())) {
            return false;
        }
        String fileName = candidate.getFileName().toString();
        if (EvalLegacyFileGuard.isLegacyFileName(fileName)) {
            return false;
        }
        return BANK_FILE_NAME.matcher(fileName).matches();
    }

    /**
     * 是否允许读取该题库文件：旧 roleplay-evals.json 一律拒绝，其余由定位结果与预检决定
     * （§5.8.2 不回退读旧文件；本方法只判文件名，不打开内容）。
     */
    public boolean readableBankFile(File file) {
        return file != null && !EvalLegacyFileGuard.isLegacyFile(file);
    }

    /** 是否旧题库文件；只判文件名，不读内容。 */
    public boolean legacyBankFile(File file) {
        return EvalLegacyFileGuard.isLegacyFile(file);
    }

    private static File normalize(File file) {
        if (file == null) {
            return null;
        }
        return file.getAbsoluteFile().toPath().normalize().toFile();
    }

    private static Path pathOf(File file) {
        return file == null ? null : file.getAbsoluteFile().toPath().normalize();
    }
}
