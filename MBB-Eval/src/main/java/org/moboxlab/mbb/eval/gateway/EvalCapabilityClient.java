package org.moboxlab.mbb.eval.gateway;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginLogger;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MBB-Eval 侧能力协商客户端（规格 §10.1、§13.2、§15.3 C2/C3/C4）。
 *
 * <p>启动期调用一次 MBB-Roleplay 的 {@code capabilities}，把 {@code evalApiVersion}
 * 与 {@code actions} 作为唯一可用性判据，结果缓存为不可变快照。协商失败按三选一收口：
 * 版本不匹配 / 未启用 / 插件缺失；出参缺字段、类型不符或不是 {@code JSONObject} 时归为
 * 接口契约异常。四条路径都只禁用 run 类能力（{@link #isRunAllowed()} 为 false），
 * 不隐藏 {@code /eval list}、{@code /eval help} 等只读命令——命令接线归 C-4。</p>
 *
 * <p>与 {@code EvalPreflight.capabilitiesCheck} 的关系：两者判定同源（同一组字段、同一组
 * 三选一文案），本类是启动期缓存版，供 C-4 与 G-2 读取。本切片按边界不改
 * {@code EvalPreflight}，因此两份判定暂时并存；C-4 接管预检时应改为读本类快照，
 * 届时删掉 {@code EvalPreflight} 的私有实现即可，不要反向让 gateway 依赖 runner 包。</p>
 *
 * <p>线程安全：快照不可变，缓存字段 {@code volatile}，探测与刷新都在实例锁内完成；
 * 同一次失败只 WARN/ERROR 一次，异常不穿出客户端。</p>
 */
public final class EvalCapabilityClient {

    /** 能力探测动作名，与角色侧同名常量对齐，避免两处字面量漂移。 */
    public static final String ACTION_CAPABILITIES = RoleplayEvalGateway.ACTION_CAPABILITIES;
    /** 生成动作名，actions 必须包含它才允许 run。 */
    public static final String ACTION_EVAL_GENERATE = RoleplayEvalGateway.ACTION_EVAL_GENERATE;
    /** 唯一接受的契约版本（§10.6.2）。 */
    public static final int EVAL_API_VERSION = 1;

    /** 能力协商状态：三选一拒绝原因 + 契约异常，只有 AVAILABLE 允许 run。 */
    public enum Status {
        /** 版本、actions 与全部字段均通过。 */
        AVAILABLE("可用", "无需处理"),
        /** evalApiVersion 缺失、非数字或不等于 1（§10.1 规则 6：不得猜字段降级）。 */
        VERSION_MISMATCH("版本不匹配",
                "请升级 MBB-Roleplay 或 MBB-Eval 到兼容 evalApiVersion=1 的版本后重启 MBB-Eval"),
        /** evalApiEnable=false 或 actions 不含 eval-generate（§10.1 规则 2）。 */
        DISABLED("未启用",
                "请开启 MBB-Roleplay 的 evalApiEnable（旧键 evalEnable 一版内兼容）后重启 MBB-Eval；/eval list 仍可用"),
        /** capabilities 不可调：服务取不到或调用失败（§10.6.2 规则 3）。 */
        PLUGIN_MISSING("插件缺失",
                "请安装并启用 MBB-Roleplay；已启用仍失败则升级到支持 evalApiVersion=1 的版本后重启 MBB-Eval"),
        /** 出参不是 JSONObject、status=false、缺必需字段或类型不符（§10.1 规则 3）。 */
        CONTRACT_ERROR("接口契约异常",
                "请升级 MBB-Roleplay 到支持 evalApiVersion=1 的版本；若已是最新，请把本次日志中的契约差异反馈给维护者");

        private final String label;
        private final String hint;

        Status(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }

        /** 三选一的中文短标签，可直接用于日志与回执。 */
        public String label() {
            return label;
        }

        /** 给用户的下一步提示。 */
        public String hint() {
            return hint;
        }
    }

    /**
     * capabilities 数据源。
     *
     * <p>与 {@code EvalPreflight.CapabilitiesSource} 同职责，区别只有返回类型：这里保留原始载荷，
     * 让非 {@code JSONObject} 的出参也能在客户端收敛为 {@link Status#CONTRACT_ERROR}，
     * 而不是在适配层崩溃或静默当成 null。生产实现只经
     * {@link RoleplayEvalGateway#fetchCapabilities()}；已有的 JSONObject 数据源可用方法引用直接适配。</p>
     */
    public interface CapabilitiesSource {
        /** 返回 capabilities 原始出参；服务不可调时返回 {@code null}。 */
        Object fetch();
    }

    /** 一份不可变的能力快照；状态非 AVAILABLE 时 {@link #reason()} 带具体原因。 */
    public static final class Snapshot {
        private final Status status;
        private final String reason;
        private final int evalApiVersion;
        private final Set<String> actions;
        private final boolean evalApiEnable;
        private final String personaId;
        private final String personaHash;
        private final String configFingerprint;
        private final String aiProfile;
        private final String evalProfile;
        private final int replyMaxTokens;
        private final int promptTotalChars;
        private final boolean toolEnable;
        private final boolean replyReviewEnable;
        private final int maxHistoryRounds;
        private final int maxHistoryChars;

        private Snapshot(Status status, String reason, int evalApiVersion, Set<String> actions,
                         boolean evalApiEnable, String personaId, String personaHash, String configFingerprint,
                         String aiProfile, String evalProfile, int replyMaxTokens, int promptTotalChars,
                         boolean toolEnable, boolean replyReviewEnable, int maxHistoryRounds, int maxHistoryChars) {
            this.status = status;
            this.reason = reason;
            this.evalApiVersion = evalApiVersion;
            this.actions = Collections.unmodifiableSet(new LinkedHashSet<String>(actions));
            this.evalApiEnable = evalApiEnable;
            this.personaId = personaId;
            this.personaHash = personaHash;
            this.configFingerprint = configFingerprint;
            this.aiProfile = aiProfile;
            this.evalProfile = evalProfile;
            this.replyMaxTokens = replyMaxTokens;
            this.promptTotalChars = promptTotalChars;
            this.toolEnable = toolEnable;
            this.replyReviewEnable = replyReviewEnable;
            this.maxHistoryRounds = maxHistoryRounds;
            this.maxHistoryChars = maxHistoryChars;
        }

        private static Snapshot failure(Status status, String reason) {
            return new Snapshot(status, reason, -1, Collections.<String>emptySet(), false,
                    "", "", "", "", "", 0, 0, false, false, 0, 0);
        }

        public Status status() {
            return status;
        }

        /** 中文原因；AVAILABLE 时说明协商通过的结果。 */
        public String reason() {
            return reason;
        }

        /** 给用户的下一步提示，随状态固定。 */
        public String hint() {
            return status.hint();
        }

        public boolean isRunAllowed() {
            return status == Status.AVAILABLE;
        }

        /** 协商成功时的契约版本；失败快照为 -1。 */
        public int evalApiVersion() {
            return evalApiVersion;
        }

        /** 只读动作集合；失败快照为空集。 */
        public Set<String> actions() {
            return actions;
        }

        public boolean supports(String action) {
            return action != null && actions.contains(action);
        }

        public boolean evalApiEnable() {
            return evalApiEnable;
        }

        public String personaId() {
            return personaId;
        }

        public String personaHash() {
            return personaHash;
        }

        public String configFingerprint() {
            return configFingerprint;
        }

        public String aiProfile() {
            return aiProfile;
        }

        public String evalProfile() {
            return evalProfile;
        }

        public int replyMaxTokens() {
            return replyMaxTokens;
        }

        public int promptTotalChars() {
            return promptTotalChars;
        }

        public boolean toolEnable() {
            return toolEnable;
        }

        public boolean replyReviewEnable() {
            return replyReviewEnable;
        }

        public int maxHistoryRounds() {
            return maxHistoryRounds;
        }

        public int maxHistoryChars() {
            return maxHistoryChars;
        }
    }

    private final CapabilitiesSource source;
    private final PluginLogger logger;
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);
    private final AtomicBoolean successLogged = new AtomicBoolean(false);
    private volatile Snapshot snapshot;

    /** 生产装配：每次探测都经网关重新取服务，MBB-Roleplay 重载后不会命中旧实例。 */
    public EvalCapabilityClient(Plugin plugin, PluginLogger logger) {
        this(new RoleplayEvalGateway(plugin, logger), logger);
    }

    /** 便于在已有网关实例上复用同一个服务出口。 */
    public EvalCapabilityClient(RoleplayEvalGateway gateway, PluginLogger logger) {
        this(new GatewaySource(gateway), logger);
    }

    /** 自检注入口：直接提供 capabilities 原始出参。 */
    public EvalCapabilityClient(CapabilitiesSource source, PluginLogger logger) {
        this.source = source;
        this.logger = logger;
    }

    /**
     * 启动期探测入口：首次调用探测并缓存，之后直接返回缓存。
     *
     * <p>读路径（{@link #status()}、{@link #isRunAllowed()} 等）也会按需触发一次探测，
     * 因此即使装配方漏掉启动调用，也不会在每次读取时重复协商。</p>
     */
    public Snapshot probe() {
        Snapshot current = snapshot;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (snapshot == null) {
                snapshot = load();
            }
            return snapshot;
        }
    }

    /** 强制重新协商并替换缓存；用于人工恢复或 C-4 的重新预检。 */
    public Snapshot refresh() {
        synchronized (this) {
            snapshot = load();
            return snapshot;
        }
    }

    /** 当前快照，必要时先探测一次。 */
    public Snapshot snapshot() {
        return probe();
    }

    public Status status() {
        return probe().status();
    }

    /** 只禁用 run 类路径：false 不代表插件不可用，/eval list 等只读命令仍应可用。 */
    public boolean isRunAllowed() {
        return probe().isRunAllowed();
    }

    /**
     * 给命令回执与日志用的禁用原因；可用时返回 {@code null}。
     *
     * <p>格式为「中文原因；下一步提示」，原因固定落在三选一之一或「接口契约异常」，
     * 三种拒绝原因互不包含对方的关键词（§13.2 判定规则 3 的同源文案）。</p>
     */
    public String disableReason() {
        Snapshot current = probe();
        if (current.status() == Status.AVAILABLE) {
            return null;
        }
        return current.reason() + "；" + current.hint();
    }

    private Snapshot load() {
        Snapshot result = fetchAndValidate();
        log(result);
        return result;
    }

    private Snapshot fetchAndValidate() {
        Object raw;
        try {
            raw = source == null ? null : source.fetch();
        } catch (RuntimeException e) {
            return contract("调用 capabilities 抛异常：" + describe(e));
        }
        if (raw == null) {
            return Snapshot.failure(Status.PLUGIN_MISSING,
                    "MBB-Roleplay 插件缺失：capabilities 不可调（服务取不到或调用失败）");
        }
        if (!(raw instanceof JSONObject)) {
            return contract("capabilities 出参不是 JSONObject：" + raw.getClass().getName());
        }
        JSONObject capabilities = (JSONObject) raw;
        if (Boolean.FALSE.equals(capabilities.get("status"))) {
            String message = capabilities.getString("message");
            return contract("capabilities 返回 status=false"
                    + (message == null || message.trim().isEmpty() ? "" : "：" + message));
        }
        // 版本优先判定：不认识主版本时不得按 v1 字段名猜测降级（§10.1 规则 3、6）。
        Object versionValue = capabilities.get("evalApiVersion");
        if (versionValue == null) {
            return versionMismatch("capabilities 缺少 evalApiVersion");
        }
        if (!(versionValue instanceof Number)) {
            return versionMismatch("evalApiVersion 类型不符（" + versionValue.getClass().getSimpleName()
                    + "），需要整数 1");
        }
        int evalApiVersion = ((Number) versionValue).intValue();
        if (evalApiVersion != EVAL_API_VERSION) {
            return versionMismatch("evalApiVersion=" + evalApiVersion + "，需要 " + EVAL_API_VERSION);
        }
        try {
            if (!Boolean.TRUE.equals(capabilities.get("status"))) {
                throw new ContractViolation("字段 status 缺失或不为 true");
            }
            Object actionValue = capabilities.get("action");
            if (!ACTION_CAPABILITIES.equals(actionValue)) {
                throw new ContractViolation("字段 action 应为 " + ACTION_CAPABILITIES
                        + "，实际为 " + describeValue(actionValue));
            }
            JSONArray actionArray = requireArray(capabilities, "actions");
            Set<String> actions = new LinkedHashSet<String>();
            for (Object item : actionArray) {
                if (!(item instanceof String)) {
                    throw new ContractViolation("字段 actions 含非字符串项");
                }
                actions.add((String) item);
            }
            if (!actions.contains(ACTION_EVAL_GENERATE)) {
                // 角色侧关掉评测时 actions 只剩 capabilities；若同时明确给出 evalApiEnable=false，
                // 两个信号一起写进原因，用户不必猜是哪一个开关没开。
                if (Boolean.FALSE.equals(capabilities.get("evalApiEnable"))) {
                    return disabled("actions 不含 " + ACTION_EVAL_GENERATE + " 且 evalApiEnable=false");
                }
                return disabled("actions 不含 " + ACTION_EVAL_GENERATE);
            }
            boolean evalApiEnable = requireBoolean(capabilities, "evalApiEnable");
            if (!evalApiEnable) {
                return disabled("evalApiEnable=false");
            }
            String personaId = requireText(capabilities, "personaId");
            String personaHash = requireText(capabilities, "personaHash");
            String configFingerprint = requireText(capabilities, "configFingerprint");
            String aiProfile = requireText(capabilities, "aiProfile");
            String evalProfile = requireText(capabilities, "evalProfile");
            int replyMaxTokens = requireInt(capabilities, "replyMaxTokens");
            int promptTotalChars = requireInt(capabilities, "promptTotalChars");
            boolean toolEnable = requireBoolean(capabilities, "toolEnable");
            boolean replyReviewEnable = requireBoolean(capabilities, "replyReviewEnable");
            int maxHistoryRounds = requireInt(capabilities, "maxHistoryRounds");
            int maxHistoryChars = requireInt(capabilities, "maxHistoryChars");
            return new Snapshot(Status.AVAILABLE,
                    "评测接口可用：evalApiVersion=" + evalApiVersion + "，actions=" + actions,
                    evalApiVersion, actions, evalApiEnable, personaId, personaHash, configFingerprint,
                    aiProfile, evalProfile, replyMaxTokens, promptTotalChars, toolEnable, replyReviewEnable,
                    maxHistoryRounds, maxHistoryChars);
        } catch (ContractViolation e) {
            return contract(e.getMessage());
        }
    }

    private static Snapshot versionMismatch(String detail) {
        return Snapshot.failure(Status.VERSION_MISMATCH, "评测接口版本不匹配：" + detail);
    }

    private static Snapshot disabled(String detail) {
        return Snapshot.failure(Status.DISABLED, "MBB-Roleplay 评测接口未启用（" + detail + "）");
    }

    private static Snapshot contract(String detail) {
        return Snapshot.failure(Status.CONTRACT_ERROR, "评测接口契约异常：" + detail);
    }

    private static JSONArray requireArray(JSONObject capabilities, String field) {
        Object value = capabilities.get(field);
        if (!(value instanceof JSONArray)) {
            throw new ContractViolation("字段 " + field + " 缺失或不是数组");
        }
        return (JSONArray) value;
    }

    private static String requireText(JSONObject capabilities, String field) {
        Object value = capabilities.get(field);
        if (!(value instanceof String)) {
            throw new ContractViolation("字段 " + field + " 缺失或不是字符串");
        }
        return (String) value;
    }

    private static int requireInt(JSONObject capabilities, String field) {
        Object value = capabilities.get(field);
        if (!(value instanceof Number)) {
            throw new ContractViolation("字段 " + field + " 缺失或不是数字");
        }
        return ((Number) value).intValue();
    }

    private static boolean requireBoolean(JSONObject capabilities, String field) {
        Object value = capabilities.get(field);
        if (!(value instanceof Boolean)) {
            throw new ContractViolation("字段 " + field + " 缺失或不是布尔值");
        }
        return ((Boolean) value).booleanValue();
    }

    /** 首次成功记一条 INFO（含 evalApiVersion 与 actions），之后刷新不重复刷屏。 */
    private void log(Snapshot result) {
        try {
            if (result.status() == Status.AVAILABLE) {
                if (successLogged.compareAndSet(false, true) && logger != null) {
                    logger.sendInfo("[MBB-Eval] 能力协商通过：" + result.reason());
                }
                return;
            }
            if (failureLogged.compareAndSet(false, true) && logger != null) {
                String line = "[MBB-Eval] 能力协商失败（" + result.status().label() + "）："
                        + result.reason() + "；" + result.hint() + "（后续同类失败不再重复告警）";
                if (result.status() == Status.CONTRACT_ERROR) {
                    logger.sendError(line);
                } else {
                    logger.sendWarn(line);
                }
            }
        } catch (RuntimeException ignored) {
            // 日志出口异常不得影响协商结果，也不得穿出客户端。
        }
    }

    /** 契约违例用私有异常在解析块内收口，不穿出客户端。 */
    private static final class ContractViolation extends RuntimeException {
        private ContractViolation(String message) {
            super(message);
        }
    }

    /** 生产数据源：每次 fetch 都经网关重新取服务。 */
    private static final class GatewaySource implements CapabilitiesSource {
        private final RoleplayEvalGateway gateway;

        private GatewaySource(RoleplayEvalGateway gateway) {
            this.gateway = gateway;
        }

        @Override
        public Object fetch() {
            return gateway == null ? null : gateway.fetchCapabilities();
        }
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static String describeValue(Object value) {
        if (value == null) {
            return "缺失";
        }
        if (value instanceof String) {
            return "\"" + value + "\"";
        }
        return value.getClass().getSimpleName();
    }
}
