package org.moboxlab.mbb.eval.runner;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.gateway.RoleplayEvalGateway;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginLogger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * /eval run 的预检（规格 §9.5、§13.2）：纯计算、不调 AI、不写任何表，任一检查失败即拒绝且不建 run。
 *
 * <p>本切片（A-3）只落地 §9.5 表中的两项：接口能力（{@code evalApiVersion=1} 且
 * {@code actions} 含 {@code eval-generate}）与 planned 预算。题库、rubric、persona、
 * judge profile、MBB-AI 在线与数据目录可读依赖 B/C/D 区接入的数据，届时应按 §9.5 的
 * 顺序补在两个检查之间，并补 §13.1.1 的错误码。</p>
 *
 * <p>骨架期占位执行不读题库、不判分，因此这里不拦截题库与 rubric，否则 /eval run 永远无法
 * 受理，I8 的「运行中卸载」路径也无法验证。判定顺序仍按 §9.5：接口能力在前、预算在后。</p>
 *
 * <p>同一实例对接口不可用只 WARN 一次（A11 验收原文「日志仅一次 WARN」），避免每次
 * /eval run 重复刷同一条告警。</p>
 */
public final class EvalPreflight {

    /** full 档生成调用数（40 题）。 */
    public static final int GENERATION_CALLS_FULL = 40;
    /** full 档 judge 调用数（36，题集维度加权后固定）。 */
    public static final int JUDGE_CALLS_FULL = 36;
    /** smoke 档生成调用数（12 题）。 */
    public static final int GENERATION_CALLS_SMOKE = 12;
    /** smoke 档 judge 调用数（10）。 */
    public static final int JUDGE_CALLS_SMOKE = 10;
    /** 校准调用数，固定 3 次且不可配置（§9.5）。 */
    public static final int CALIBRATION_CALLS = 3;
    /** full 档 planned 调用数（40 + 36 + 3）。 */
    public static final int PLANNED_CALLS_FULL = 79;
    /** smoke 档 planned 调用数（12 + 10 + 3）。 */
    public static final int PLANNED_CALLS_SMOKE = 25;
    /** full 档题数（B 区题库接入后改为按题库子集实际条数）。 */
    public static final int CASE_COUNT_FULL = 40;
    /** smoke 档题数（同上）。 */
    public static final int CASE_COUNT_SMOKE = 12;

    /** 预检失败结果：错误码可为空（题库相关失败由 B 区填入 §13.1.1 的错误码）。 */
    public static final class Rejection {
        private final String code;
        private final String message;
        private final String hint;

        public Rejection(String code, String message, String hint) {
            this.code = code;
            this.message = message;
            this.hint = hint;
        }

        public String code() {
            return code;
        }

        public String message() {
            return message;
        }

        public String hint() {
            return hint;
        }
    }

    /** 单项检查：通过返回 null，失败返回拒绝原因。 */
    public interface Check {
        Rejection check(String tier);
    }

    /** capabilities 数据来源；抽成接口便于自检替换，生产实现只经 getService("MBB-Roleplay") 取数。 */
    public interface CapabilitiesSource {
        JSONObject fetch();
    }

    private final List<Check> checks;

    public EvalPreflight(List<Check> checks) {
        if (checks == null) {
            this.checks = Collections.emptyList();
        } else {
            this.checks = Collections.unmodifiableList(new ArrayList<Check>(checks));
        }
    }

    /**
     * 生产装配：Roleplay 接口探测 + planned 预算（§9.5 首项与末项，其余检查待 B/C/D 区接入）。
     *
     * <p>能力探测统一经 {@link RoleplayEvalGateway#fetchCapabilities()}，不在预检里直接
     * 摸 {@code PluginManager}：取服务、调用动作与出参解析都收在网关内。</p>
     */
    public static EvalPreflight forPlugin(Plugin plugin, EvalConfig config) {
        final PluginLogger logger = plugin == null ? null : plugin.getLogger();
        final RoleplayEvalGateway gateway = new RoleplayEvalGateway(plugin, logger);
        CapabilitiesSource source = new CapabilitiesSource() {
            @Override
            public JSONObject fetch() {
                return gateway.fetchCapabilities();
            }
        };
        return new EvalPreflight(Arrays.asList(capabilitiesCheck(source, logger), budgetCheck(config)));
    }

    /**
     * 依次执行检查。
     *
     * @return 第一条失败的原因；全部通过返回 null
     * @throws IllegalArgumentException 档位非法
     */
    public Rejection check(String tier) {
        requireTier(tier);
        for (Check check : checks) {
            Rejection rejection = check.check(tier);
            if (rejection != null) {
                return rejection;
            }
        }
        return null;
    }

    private static Check capabilitiesCheck(final CapabilitiesSource source, final PluginLogger logger) {
        return new Check() {
            /** A11 验收要求「日志仅一次 WARN」：同一预检实例对接口能力失败只告警一次，避免刷屏。 */
            private final AtomicBoolean warned = new AtomicBoolean(false);

            @Override
            public Rejection check(String tier) {
                JSONObject capabilities;
                try {
                    capabilities = source == null ? null : source.fetch();
                } catch (RuntimeException e) {
                    capabilities = null;
                }
                Rejection rejection = validateCapabilities(capabilities);
                if (rejection != null && warned.compareAndSet(false, true) && logger != null) {
                    logger.sendWarn("[MBB-Eval] 预检失败：" + rejection.message()
                            + "（后续同类拒绝不再重复告警）");
                }
                return rejection;
            }
        };
    }

    private static Rejection validateCapabilities(JSONObject capabilities) {
        if (capabilities == null) {
            return new Rejection(null,
                    "MBB-Roleplay 插件缺失或未启用，评测接口不可用",
                    "请安装并启用 MBB-Roleplay；已启用仍失败则升级到支持 evalApiVersion=1 的版本后重试 /eval run");
        }
        Object version = capabilities.get("evalApiVersion");
        if (!(version instanceof Number)) {
            return new Rejection(null, "评测接口版本不匹配：capabilities 缺少 evalApiVersion",
                    "请升级 MBB-Roleplay 后重试 /eval run");
        }
        int apiVersion = ((Number) version).intValue();
        if (apiVersion != 1) {
            return new Rejection(null, "评测接口版本不匹配：evalApiVersion=" + apiVersion + "，需要 1",
                    "请升级 MBB-Roleplay 或 MBB-Eval 到兼容版本后重试 /eval run");
        }
        JSONArray actions = capabilities.getJSONArray("actions");
        if (actions == null) {
            return new Rejection(null, "评测接口不可用：capabilities 缺少 actions 字段",
                    "请升级 MBB-Roleplay 后重试 /eval run");
        }
        if (!actions.contains("eval-generate")) {
            return new Rejection(null, "MBB-Roleplay 评测接口未启用（actions 不含 eval-generate）",
                    "请开启 MBB-Roleplay 的 evalApiEnable（旧键 evalEnable 一版内兼容）后重试 /eval run");
        }
        Object enabled = capabilities.get("evalApiEnable");
        if (enabled instanceof Boolean && !((Boolean) enabled).booleanValue()) {
            return new Rejection(null, "MBB-Roleplay 评测接口未启用（evalApiEnable=false）",
                    "请开启 evalApiEnable 后重试 /eval run");
        }
        return null;
    }

    private static Check budgetCheck(final EvalConfig config) {
        return new Check() {
            @Override
            public Rejection check(String tier) {
                if (config == null) {
                    return new Rejection(null, "配置未就绪，无法计算 planned 预算",
                            "请重启插件后重试 /eval run");
                }
                int planned = plannedCalls(tier);
                int budget = config.callBudget();
                if (planned <= budget) {
                    return null;
                }
                String composition = "生成 " + generationCalls(tier) + " + judge " + judgeCalls(tier)
                        + " + 校准 " + CALIBRATION_CALLS + " = " + planned;
                String way = "full".equals(tier)
                        ? "调大 evalCallBudget（上限 100）或改跑 smoke 档后重试 /eval run"
                        : "调大 evalCallBudget（上限 100）后重试 /eval run；smoke 已是最低档";
                return new Rejection(null,
                        "预算不足：" + tier + " 档 planned " + planned + " 次（" + composition
                                + "，另有重试余量）超过 evalCallBudget=" + budget + "（上限 100）",
                        way + "；本次未建 run、未产生任何 AI 调用");
            }
        };
    }

    public static int plannedCalls(String tier) {
        if ("full".equals(tier)) {
            return PLANNED_CALLS_FULL;
        }
        if ("smoke".equals(tier)) {
            return PLANNED_CALLS_SMOKE;
        }
        throw new IllegalArgumentException("未知评测档位：" + tier);
    }

    public static int caseCount(String tier) {
        if ("full".equals(tier)) {
            return CASE_COUNT_FULL;
        }
        if ("smoke".equals(tier)) {
            return CASE_COUNT_SMOKE;
        }
        throw new IllegalArgumentException("未知评测档位：" + tier);
    }

    public static int generationCalls(String tier) {
        if ("full".equals(tier)) {
            return GENERATION_CALLS_FULL;
        }
        if ("smoke".equals(tier)) {
            return GENERATION_CALLS_SMOKE;
        }
        throw new IllegalArgumentException("未知评测档位：" + tier);
    }

    public static int judgeCalls(String tier) {
        if ("full".equals(tier)) {
            return JUDGE_CALLS_FULL;
        }
        if ("smoke".equals(tier)) {
            return JUDGE_CALLS_SMOKE;
        }
        throw new IllegalArgumentException("未知评测档位：" + tier);
    }

    /**
     * 预计时长下限（分钟）：骨架期没有同档历史 p50，按每次调用 4 秒估算（§9.5）。
     * F 区接入历史 p50 后由报表侧替换。
     */
    public static int estimatedMinutesLow(int planned) {
        return Math.max(1, (int) Math.ceil(planned * 4.0 / 60.0));
    }

    /** 预计时长上限（分钟）：骨架期取单点估算的 ±100%，只用于回执区间展示。 */
    public static int estimatedMinutesHigh(int planned) {
        return Math.max(estimatedMinutesLow(planned) + 1, (int) Math.ceil(planned * 8.0 / 60.0));
    }

    private static void requireTier(String tier) {
        if (!"smoke".equals(tier) && !"full".equals(tier)) {
            throw new IllegalArgumentException("未知评测档位：" + tier);
        }
    }
}
