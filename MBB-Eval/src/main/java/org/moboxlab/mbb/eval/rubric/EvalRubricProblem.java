package org.moboxlab.mbb.eval.rubric;

/**
 * rubric 加载问题：错误码 + JSON path + 中文消息。
 *
 * <p>错误码口径见 docs/MBB-Eval.md §13.1 与 §17.1 O20：规格尚未冻结 rubric 专属错误码，
 * v1 暂按 E_BANK_* 家族口径使用 E_RUBRIC_*，待 O20 定稿后统一。校验不抛异常，
 * 由 {@link EvalRubricLoader} 收集后交给预检层与命令层展示。</p>
 */
public final class EvalRubricProblem {

    /** 文件不存在、不可读或不是合法 UTF-8。 */
    public static final String CODE_READ = "E_RUBRIC_READ";
    /** JSON 解析失败或顶层不是对象。 */
    public static final String CODE_JSON = "E_RUBRIC_JSON";
    /** 语义版本非法，或文件名版本与内容 version 不一致。 */
    public static final String CODE_VERSION = "E_RUBRIC_VERSION";
    /** 内容 sha256 前 8 位非法或与 version 后缀不一致。 */
    public static final String CODE_HASH = "E_RUBRIC_HASH";
    /** promptVersion 缺失或与代码支持版本不匹配。 */
    public static final String CODE_PROMPT_VERSION = "E_RUBRIC_PROMPT_VERSION";
    /** 字段缺失、类型错误或取值非法。 */
    public static final String CODE_FIELD = "E_RUBRIC_FIELD";
    /** dimensions 条数或维度 id 不符合 §6.1 封闭集合。 */
    public static final String CODE_DIMENSION = "E_RUBRIC_DIMENSION";
    /** 五维权重之和不为 1.00。 */
    public static final String CODE_WEIGHT = "E_RUBRIC_WEIGHT";

    private final String code;
    private final String jsonPath;
    private final String message;

    public EvalRubricProblem(String code, String jsonPath, String message) {
        this.code = code == null ? "" : code;
        this.jsonPath = jsonPath == null ? "" : jsonPath;
        this.message = message == null ? "" : message;
    }

    public String code() {
        return code;
    }

    /** JSON path，例如 dimensions[0].scale.2；文件级问题为空串。 */
    public String jsonPath() {
        return jsonPath;
    }

    public String message() {
        return message;
    }

    /** 供日志与命令层展示的单行文本。 */
    public String display() {
        if (jsonPath.isEmpty()) {
            return code + "：" + message;
        }
        return code + "（" + jsonPath + "）：" + message;
    }

    @Override
    public String toString() {
        return display();
    }
}
