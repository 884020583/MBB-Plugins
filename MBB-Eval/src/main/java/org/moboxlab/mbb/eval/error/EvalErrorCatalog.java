package org.moboxlab.mbb.eval.error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.moboxlab.mbb.eval.bank.EvalBankLocator;
import org.moboxlab.mbb.eval.bank.EvalBankProblem;
import org.moboxlab.mbb.eval.gateway.EvalGenerateResult;
import org.moboxlab.mbb.eval.rubric.EvalRubricProblem;

/**
 * 评测错误码目录（规格 #50 切片 H-1；§13.1.1、§13.1.2、§13.2、§16.9）。
 *
 * <p>错误码集合是封闭的，只登记规格已经定义的 32 个码：15 个题库码 E_BANK_*、
 * E_ROLEPLAY_DATA_NOT_FOUND、8 个 rubric 码 E_RUBRIC_* 与 8 个 eval-generate
 * errorType。每个码给一句中文原因与一句可执行的下一步动作，供预检回执与 onEnable
 * 日志共用同一套文案（§13.2 判定规则 3：启动期与命令期不得两套文案）。</p>
 *
 * <p>本类不解析、不校验，只做码到文案的映射；未知码一律拒绝，禁止运行期新增。</p>
 */
public final class EvalErrorCatalog {

    /** 题库校验码分组（§13.1.1）。 */
    public static final String GROUP_BANK = "题库";
    /** 数据目录码分组（§13.1.1 H2）。 */
    public static final String GROUP_DATA = "数据目录";
    /** rubric 校验码分组（§17.1 O20 前按家族使用）。 */
    public static final String GROUP_RUBRIC = "rubric";
    /** eval-generate errorType 分组（§13.1.2）。 */
    public static final String GROUP_GENERATE = "评测接口";

    /** 评测数据目录不存在或不可读（H2）。 */
    public static final String DATA_NOT_FOUND = EvalBankLocator.CODE_DATA_NOT_FOUND;
    /** 评测接口已停用。 */
    public static final String GENERATE_DISABLED = EvalGenerateResult.ERROR_DISABLED;
    /** 评测接口参数非法。 */
    public static final String GENERATE_INVALID_PARAM = EvalGenerateResult.ERROR_INVALID_PARAM;
    /** 对话历史超长。 */
    public static final String GENERATE_HISTORY_TOO_LONG = EvalGenerateResult.ERROR_HISTORY_TOO_LONG;
    /** judge profile 非法。 */
    public static final String GENERATE_PROFILE_INVALID = EvalGenerateResult.ERROR_PROFILE_INVALID;
    /** MBB-AI 缺失（H3）。 */
    public static final String GENERATE_AI_MISSING = EvalGenerateResult.ERROR_AI_MISSING;
    /** 评测接口忙。 */
    public static final String GENERATE_BUSY = EvalGenerateResult.ERROR_BUSY;
    /** 评测调用超时。 */
    public static final String GENERATE_TIMEOUT = EvalGenerateResult.ERROR_TIMEOUT;
    /** 评测接口内部错误。 */
    public static final String GENERATE_INTERNAL = EvalGenerateResult.ERROR_INTERNAL;

    private static final Map<String, Entry> ENTRIES;
    private static final List<String> CODES;

    static {
        Map<String, Entry> entries = new LinkedHashMap<String, Entry>();

        // 题库校验码：顺序与 EvalBankProblem.CODES 一致
        register(entries, EvalBankProblem.READ, GROUP_BANK,
                "题库文件无法读取或不存在，评测无法加载题目",
                "请确认 evalBankFile 指向 evals 目录下存在的 bank-<version>.json；若目录缺失，请确认 plugins/MBB-Roleplay/evals 可读后重启插件以释放内置题库");
        register(entries, EvalBankProblem.JSON, GROUP_BANK,
                "题库不是合法 JSON，无法解析",
                "请用 JSON 校验工具定位语法错误（常见为多余逗号、注释、单引号），修正后重启插件");
        register(entries, EvalBankProblem.SIZE, GROUP_BANK,
                "题库文件大小超出允许范围",
                "请删减用例或拆分题库，保持单文件不超过题库规格允许的大小后重启插件");
        register(entries, EvalBankProblem.SCHEMA_VERSION, GROUP_BANK,
                "题库 schemaVersion 与当前插件不兼容",
                "请把 schemaVersion 改为当前插件支持的版本（见 docs/MBB-Eval.md 题库章节），修正后重启插件");
        register(entries, EvalBankProblem.VERSION, GROUP_BANK,
                "题库 version 或 hash 声明不合法",
                "请按题库规格填写 version 与 hash（hash 取内容 sha256 前 12 位小写十六进制），修正后重启插件");
        register(entries, EvalBankProblem.FIELD, GROUP_BANK,
                "必填字段缺失、类型不符或场景字段越界",
                "请按错误清单里的 JSON path 补齐或修正字段类型，保存后重启插件；运行期不热重载题目");
        register(entries, EvalBankProblem.ID, GROUP_BANK,
                "用例 id 重复或不符合命名规则",
                "请保证每个用例 id 唯一且符合 <dimension>-NN 形式，修正后重启插件");
        register(entries, EvalBankProblem.DIMENSION, GROUP_BANK,
                "用例维度不在五个维度内或数量不达标",
                "请把用例分配到 persona/style/knowledge/boundary/memory 五个维度并满足数量要求，修正后重启插件");
        register(entries, EvalBankProblem.COVERAGE, GROUP_BANK,
                "全量或 smoke 用例覆盖不满足要求",
                "请补齐缺失维度或 smoke 用例，使覆盖达到题库规格要求的数量后重启插件");
        register(entries, EvalBankProblem.STYLE_MOUNT, GROUP_BANK,
                "style 挂载的硬性项或软性项不合法",
                "请把 style 挂载项改成 rubric 中登记的硬性/软性键，修正后重启插件");
        register(entries, EvalBankProblem.KNOWLEDGE, GROUP_BANK,
                "knowledge 题目的选项、答案或要点不合法",
                "请检查 knowledge 用例的 choices、expected 与要点字段，确保答案在选项内，修正后重启插件");
        register(entries, EvalBankProblem.MEMORY, GROUP_BANK,
                "memory 题目的记忆点或追问不合法",
                "请检查 memory 用例的记忆点与追问是否成对且非空，修正后重启插件");
        register(entries, EvalBankProblem.TIER, GROUP_BANK,
                "tier 只能是 full 或 smoke，且数量必须匹配",
                "请把 tier 改为 full 或 smoke，并保证数量与 tier 匹配，修正后重启插件");
        register(entries, EvalBankProblem.PERSONA, GROUP_BANK,
                "题库 personaId 或 personaHash 与当前 persona 不一致",
                "请对齐题库 personaId/personaHash 与当前 persona；确需跨 persona 试跑时设置 evalAllowPersonaMismatch=true 后重启");
        register(entries, EvalBankProblem.HASH, GROUP_BANK,
                "题库 bank_hash 与内容不一致或格式不合法",
                "请重新计算题库内容的 sha256 前 12 位并回填 hash 字段，修正后重启插件");

        register(entries, EvalBankLocator.CODE_DATA_NOT_FOUND, GROUP_DATA,
                "评测数据目录（plugins/MBB-Roleplay/evals）不存在或不可读，题库与 rubric 无法加载",
                "请确认 plugins/MBB-Roleplay/evals 目录存在且可读，修复后重启插件；内置题库会在重启时释放到该目录；/eval report 仍可用");

        // rubric 校验码：规格 §17.1 O20 前按 E_RUBRIC_* 家族使用
        register(entries, EvalRubricProblem.CODE_READ, GROUP_RUBRIC,
                "rubric 文件无法读取或缺失",
                "请确认 evals 目录下存在与题库匹配的 rubric-<version>.json，修复后重启插件");
        register(entries, EvalRubricProblem.CODE_JSON, GROUP_RUBRIC,
                "rubric 不是合法 JSON，无法解析",
                "请用 JSON 校验工具修正 rubric 语法错误后重启插件");
        register(entries, EvalRubricProblem.CODE_VERSION, GROUP_RUBRIC,
                "rubric version 与当前插件不一致",
                "请使用当前 promptVersion 重新生成 rubric，并把 version 改为 1.0.0-<内容 sha8> 后重启插件");
        register(entries, EvalRubricProblem.CODE_HASH, GROUP_RUBRIC,
                "rubric 内容 hash 与 version 后缀不一致",
                "请按 rubric 内容重新计算 sha8 并同步 version 后缀，修正后重启插件");
        register(entries, EvalRubricProblem.CODE_PROMPT_VERSION, GROUP_RUBRIC,
                "rubric promptVersion 与当前判分提示词版本不一致",
                "请用当前插件版本重新生成 rubric 后重启插件");
        register(entries, EvalRubricProblem.CODE_FIELD, GROUP_RUBRIC,
                "rubric 必填字段缺失或类型不符",
                "请按错误清单里的 JSON path 修正 rubric 字段后重启插件");
        register(entries, EvalRubricProblem.CODE_DIMENSION, GROUP_RUBRIC,
                "rubric 维度缺失或权重之和不等于 1",
                "请补齐 persona/style/knowledge/boundary/memory 五个维度并使权重之和为 1.0 后重启插件");
        register(entries, EvalRubricProblem.CODE_WEIGHT, GROUP_RUBRIC,
                "rubric 权重或档位文案不合法",
                "请检查各维度 weight 与 0/1 档锚点文案，修正后重启插件");

        // eval-generate errorType：重试必须以接口出参 retryable 为准
        register(entries, EvalGenerateResult.ERROR_DISABLED, GROUP_GENERATE,
                "评测接口已被 MBB-Roleplay 停用",
                "请在 MBB-Roleplay 配置中启用 evalApiVersion=1（或对应开关）后重启插件");
        register(entries, EvalGenerateResult.ERROR_INVALID_PARAM, GROUP_GENERATE,
                "评测接口拒绝了本次参数（模型、提示词或结构不合法）",
                "请查看插件日志中的接口返回并按参数提示修正；这通常不是题库问题，原样重试通常不会成功");
        register(entries, EvalGenerateResult.ERROR_HISTORY_TOO_LONG, GROUP_GENERATE,
                "对话历史超过评测接口上限",
                "请缩短历史轮数或改用更短的用例后重试");
        register(entries, EvalGenerateResult.ERROR_PROFILE_INVALID, GROUP_GENERATE,
                "judge profile 配置不合法或已被删除",
                "请检查 evalJudgeProfile/evalProfile 指向的 profile 是否存在且可用，修正后重跑 /eval run");
        register(entries, EvalGenerateResult.ERROR_AI_MISSING, GROUP_GENERATE,
                "MBB-AI 服务缺失或未启用，无法发起评测调用",
                "请检查 aiHost 配置与 MBB-AI 服务状态（启用开关、地址、端口、鉴权），修复后重跑 /eval run");
        register(entries, EvalGenerateResult.ERROR_BUSY, GROUP_GENERATE,
                "评测接口正忙（并发或队列已满）",
                "请稍后重试；持续出现时检查 MBB-Roleplay 与 MBB-AI 的并发配置");
        register(entries, EvalGenerateResult.ERROR_TIMEOUT, GROUP_GENERATE,
                "评测调用超时，未在预算时间内返回",
                "请检查 aiHost 网络与服务负载；可稍后重试或调小单次调用规模");
        register(entries, EvalGenerateResult.ERROR_INTERNAL, GROUP_GENERATE,
                "评测接口内部错误",
                "请查看插件日志中的异常堆栈并把日志反馈维护者；修复前重试通常无效");

        ENTRIES = Collections.unmodifiableMap(entries);
        CODES = Collections.unmodifiableList(new ArrayList<String>(entries.keySet()));
    }

    private EvalErrorCatalog() {
    }

    /** 单个错误码的中文原因与下一步动作。 */
    public static final class Entry {
        private final String code;
        private final String group;
        private final String reason;
        private final String nextAction;

        private Entry(String code, String group, String reason, String nextAction) {
            this.code = code;
            this.group = group;
            this.reason = reason;
            this.nextAction = nextAction;
        }

        public String code() {
            return code;
        }

        /** 所属分组：题库 / 数据目录 / rubric / 评测接口。 */
        public String group() {
            return group;
        }

        /** 一句中文原因。 */
        public String reason() {
            return reason;
        }

        /** 一句可执行的下一步动作。 */
        public String nextAction() {
            return nextAction;
        }
    }

    /** 是否在封闭错误码集合内。 */
    public static boolean isKnown(String code) {
        return code != null && ENTRIES.containsKey(code);
    }

    /** 登记项；未知码返回 null，绝不临时造码。 */
    public static Entry entry(String code) {
        return code == null ? null : ENTRIES.get(code);
    }

    /** 中文原因；未知码返回空串（由呈现层标记未登记错误码）。 */
    public static String reason(String code) {
        Entry entry = entry(code);
        return entry == null ? "" : entry.reason();
    }

    /** 下一步动作；未知码返回空串。 */
    public static String nextAction(String code) {
        Entry entry = entry(code);
        return entry == null ? "" : entry.nextAction();
    }

    /** 所属分组；未知码返回空串。 */
    public static String group(String code) {
        Entry entry = entry(code);
        return entry == null ? "" : entry.group();
    }

    /** 全部错误码，顺序固定且不可变。 */
    public static List<String> codes() {
        return CODES;
    }

    private static void register(Map<String, Entry> entries, String code, String group,
            String reason, String nextAction) {
        Entry previous = entries.put(code, new Entry(code, group, reason, nextAction));
        if (previous != null) {
            throw new IllegalStateException("错误码重复登记：" + code);
        }
    }
}
