package org.moboxlab.mbb.eval.bank;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;

/**
 * onEnable 期一次性加载的题库缓存（B-5）。
 *
 * <p>按 docs/MBB-Eval.md §5.7.4：插件启用时定位、必要时释放、全量校验并缓存原始字节、
 * JSON 对象、校验结果、身份与摘要（文件名 / version / bank_hash / 用例总数与 smoke 数）；
 * 运行期不重读、不热重载，改题库必须重启（§5.1 第 8 条）。预检与后续 runner 只读本缓存。</p>
 *
 * <p>失败不抛异常，只置错误码与中文原因：错误码封闭在 §13.1.1（E_BANK_READ + 14 个校验码，
 * 目录级失败用 E_ROLEPLAY_DATA_NOT_FOUND）；明细最多 5 条，顺序沿用
 * {@link EvalBankCheckResult#visibleProblems()} 的稳定排序，供命令层逐条回显。</p>
 */
public final class EvalBankCache {

    /** 对外日志前缀（§13.6）。 */
    public static final String LOG_PREFIX = "[评测] ";

    private final EvalBankLocation location;
    private final byte[] raw;
    private final JSONObject json;
    private final EvalBankCheckResult check;
    private final EvalBankIdentity identity;
    private final EvalBankPersonaBinding binding;
    private final String fileName;
    private final String absolutePath;
    private final String version;
    private final String bankHash;
    private final int caseCount;
    private final int smokeCount;
    private final String code;
    private final String message;
    private final String hint;
    private final List<String> errors;
    private final String summary;
    private final boolean ioFailure;

    private EvalBankCache(EvalBankLocation location, byte[] raw, JSONObject json,
            EvalBankCheckResult check, EvalBankIdentity identity, EvalBankPersonaBinding binding,
            String fileName, String absolutePath, String version, String bankHash, int caseCount,
            int smokeCount, String code, String message, String hint, List<String> errors,
            String summary, boolean ioFailure) {
        this.location = location;
        this.raw = raw;
        this.json = json;
        this.check = check;
        this.identity = identity;
        this.binding = binding;
        this.fileName = fileName;
        this.absolutePath = absolutePath;
        this.version = version;
        this.bankHash = bankHash;
        this.caseCount = caseCount;
        this.smokeCount = smokeCount;
        this.code = code;
        this.message = message;
        this.hint = hint;
        this.errors = Collections.unmodifiableList(new ArrayList<String>(
                errors == null ? Collections.<String>emptyList() : errors));
        this.summary = summary;
        this.ioFailure = ioFailure;
    }

    /**
     * 从定位结果加载并缓存题库；不做任何文件系统写入（释放由调用方在缺失时先执行）。
     *
     * @param location      定位结果，可为 null
     * @param options       校验选项；应带文件名、rubric 维度表与风格目录
     * @param personaId     capabilities 的 persona 标识，可空
     * @param personaHash   capabilities 的 personaHash，可空
     * @param allowMismatch evalAllowPersonaMismatch
     */
    public static EvalBankCache load(EvalBankLocation location, EvalBankValidator.Options options,
            String personaId, String personaHash, boolean allowMismatch) {
        if (location == null) {
            return failure(null, null, "", "", EvalBankLocator.CODE_DATA_NOT_FOUND,
                    "题库定位结果为空，无法确定题库文件位置",
                    "请检查插件数据目录与 evalBankFile 配置后重启插件；/eval report 仍可用", false);
        }
        String absolute = absolutePathOf(location);
        String fileName = location.bankFileName() == null ? "" : location.bankFileName();

        if (EvalLegacyFileGuard.isLegacyFileName(fileName)
                || EvalLegacyFileGuard.isLegacyFileName(absolute)) {
            String legacy = EvalLegacyFileGuard.check(fileName).message();
            String text = legacy + "；旧文件已废弃，请使用新文件名 bank-<version>.json"
                    + "（当前路径：" + absolute + "）";
            return failure(location, null, fileName, absolute, EvalBankProblem.READ, text,
                    "请把题库文件改名为 bank-<version>.json 并在 evalBankFile 中配置新文件名后重启插件；"
                            + "旧文件不会被读取，/eval report 仍可用", false);
        }
        if (!location.valid()) {
            String reason = location.invalidReason() == null || location.invalidReason().isEmpty()
                    ? "evalBankFile 配置非法"
                    : location.invalidReason();
            return failure(location, null, fileName, absolute, EvalBankProblem.READ,
                    reason + "（当前路径：" + absolute + "）",
                    "请修正 evalBankFile，只填写 evals 目录下的 bank-<version>.json 文件名后重启插件", false);
        }
        if (!location.dataDirectoryUsable()) {
            File evals = location.evalsDirectory();
            String directory = evals == null ? absolute : evals.getAbsolutePath();
            return failure(location, null, fileName, absolute, EvalBankLocator.CODE_DATA_NOT_FOUND,
                    "评测数据目录不可用（" + location.directoryStatus() + "）：" + directory,
                    "请确认 plugins/MBB-Roleplay/evals 目录存在且可读（当前插件数据目录："
                            + absolutePathOfFolder(location.dataFolder()) + "），修复后重启插件；"
                            + "内置题库会在重启时释放到该目录；/eval report 仍可用", false);
        }
        File bankFile = location.bankFile();
        if (bankFile == null || !bankFile.isFile()) {
            return failure(location, null, fileName, absolute, EvalBankProblem.READ,
                    "题库文件不存在：<abs>".replace("<abs>", absolute),
                    "请确认 evalBankFile 指向 evals 目录下的 bank-<version>.json；文件："
                            + absolute + "；若使用内置题库，请检查目录写权限后重启插件以重新释放；"
                            + "/eval report 仍可用", false);
        }

        byte[] raw;
        try {
            raw = Files.readAllBytes(bankFile.toPath());
        } catch (IOException e) {
            return failure(location, null, fileName, absolute, EvalBankProblem.READ,
                    "读取题库文件失败：" + absolute + "，原因：" + e.getMessage(),
                    "请检查文件权限与磁盘状态后重启插件；/eval report 仍可用", true);
        } catch (RuntimeException e) {
            return failure(location, null, fileName, absolute, EvalBankProblem.READ,
                    "读取题库文件失败：" + absolute + "，原因：" + e.getMessage(),
                    "请检查文件权限与磁盘状态后重启插件；/eval report 仍可用", true);
        }

        EvalBankValidator.Options actual = options == null
                ? EvalBankValidator.Options.defaults() : options;
        EvalBankCheckResult check = EvalBankValidator.validate(raw, actual);
        JSONObject json = parse(raw);
        EvalBankIdentity identity = EvalBankIdentity.parse(raw);
        String version = json == null ? "" : stringOf(json.get("version"));
        int caseCount = caseCountOf(json);
        int smokeCount = smokeCountOf(json);
        String bankHash = identity != null && identity.computedHash() != null
                && !identity.computedHash().isEmpty()
                ? identity.computedHash() : EvalHashUtil.bankHash(raw);
        EvalBankPersonaBinding binding = EvalBankPersonaBinding.bind(
                identity, personaId, personaHash, allowMismatch);

        if (!check.ok()) {
            List<String> errors = new ArrayList<String>();
            for (EvalBankProblem problem : check.visibleProblems()) {
                errors.add(problem.display());
            }
            String firstCode = check.problems().isEmpty()
                    ? EvalBankProblem.READ : check.problems().get(0).code();
            String message = "题库校验失败：共 " + check.problems().size() + " 个错误（" + firstCode
                    + "），文件：" + absolute;
            String hint = "请按上面列出的 JSON path 修正题库后重启插件（运行期不热重载）；文件："
                    + absolute + "；修复后重跑 /eval run；/eval report 仍可用";
            return new EvalBankCache(location, raw, json, check, identity, binding, fileName,
                    absolute, version, bankHash, caseCount, smokeCount, firstCode, message, hint,
                    errors, message + "\n" + check.summary(), false);
        }

        String summary = "题库已加载：" + fileName + "，version=" + version
                + "，bank_hash=" + bankHash + "，共 " + caseCount + " 条用例（smoke "
                + smokeCount + " 条），文件：" + absolute;
        return new EvalBankCache(location, raw, json, check, identity, binding, fileName,
                absolute, version, bankHash, caseCount, smokeCount, "", "", "", null,
                summary, false);
    }

    private static EvalBankCache failure(EvalBankLocation location, byte[] raw, String fileName,
            String absolutePath, String code, String message, String hint, boolean ioFailure) {
        return new EvalBankCache(location, raw, null, null, null, null, fileName, absolutePath,
                "", "", 0, 0, code, message, hint, null,
                code + "：" + message + "；" + hint, ioFailure);
    }

    /** 缓存是否可用于跑评测（文件可读且全量校验通过）。 */
    public boolean ok() {
        return code == null || code.isEmpty();
    }

    public EvalBankLocation location() {
        return location;
    }

    /** 题库原始字节；加载失败时为 null。 */
    public byte[] raw() {
        return raw;
    }

    /** 题库 JSON 对象；加载失败时为 null。 */
    public JSONObject json() {
        return json;
    }

    /** 校验结果；读取阶段失败时为 null。 */
    public EvalBankCheckResult check() {
        return check;
    }

    public EvalBankIdentity identity() {
        return identity;
    }

    /** onEnable 时的 persona 绑定结果；题库未加载时为 null。 */
    public EvalBankPersonaBinding binding() {
        return binding;
    }

    public String fileName() {
        return fileName;
    }

    /** 题库文件绝对路径；用于失败回显与日志（§5.8.2、§13.1.1）。 */
    public String absolutePath() {
        return absolutePath;
    }

    public String version() {
        return version;
    }

    /** 实际内容 sha256 前 12 位小写十六进制，从缓存字节现算，不读声明值。 */
    public String bankHash() {
        return bankHash;
    }

    public int caseCount() {
        return caseCount;
    }

    /** tier=smoke 的用例数。 */
    public int smokeCount() {
        return smokeCount;
    }

    /** 首选错误码；成功时为空字符串。 */
    public String code() {
        return code == null ? "" : code;
    }

    public String message() {
        return message == null ? "" : message;
    }

    /** 可执行的中文修复提示。 */
    public String hint() {
        return hint == null ? "" : hint;
    }

    /** 可见错误明细，最多 5 条，顺序稳定。 */
    public List<String> errors() {
        return errors;
    }

    /** 成功时为摘要行；失败时为「错误码：消息」加校验摘要。 */
    public String summary() {
        return summary;
    }

    /** 是否读取阶段失败（IO），供调用方决定 ERROR 还是 WARN。 */
    public boolean ioFailure() {
        return ioFailure;
    }

    private static JSONObject parse(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            return JSON.parseObject(new String(EvalHashUtil.stripUtf8Bom(raw), StandardCharsets.UTF_8),
                    Feature.OrderedField);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int caseCountOf(JSONObject json) {
        if (json == null) {
            return 0;
        }
        JSONArray cases = json.getJSONArray("cases");
        return cases == null ? 0 : cases.size();
    }

    private static int smokeCountOf(JSONObject json) {
        if (json == null) {
            return 0;
        }
        JSONArray cases = json.getJSONArray("cases");
        if (cases == null) {
            return 0;
        }
        int smoke = 0;
        for (int i = 0; i < cases.size(); i++) {
            Object item = cases.get(i);
            if (item instanceof JSONObject) {
                Object tier = ((JSONObject) item).get("tier");
                if ("smoke".equals(tier)) {
                    smoke++;
                }
            }
        }
        return smoke;
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String absolutePathOf(EvalBankLocation location) {
        File file = location.bankFile();
        if (file != null) {
            return file.getAbsolutePath();
        }
        File evals = location.evalsDirectory();
        if (evals != null) {
            return new File(evals, location.bankFileName() == null ? "" : location.bankFileName())
                    .getAbsolutePath();
        }
        return "";
    }

    private static String absolutePathOfFolder(File folder) {
        return folder == null ? "" : folder.getAbsolutePath();
    }
}
