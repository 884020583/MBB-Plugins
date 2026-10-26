package org.moboxlab.mbb.eval.runner;

import com.alibaba.fastjson.JSONObject;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.gateway.EvalCapabilityClient;
import org.moboxlab.mbb.eval.gateway.RoleplayEvalGateway;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.AiStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.BankStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.CapabilitiesStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.ConfigJudgeProfileSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.DataDirStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.JudgeProfileSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.PendingSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.PersonaStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.RubricStatusSource;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.Stage;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.StageReport;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.StageSource;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * /eval run 的八项预检（规格 §9.5、§13.2）：纯计算、不调 AI、不写任何表，任一检查失败即拒绝且不建 run。
 *
 * <p>执行顺序固定为：接口能力 → 题库 → rubric → persona → judge profile → MBB-AI → 数据目录 →
 * 预算，预算永远最后。接口能力读 {@link EvalCapabilityClient} 的真实快照，只有 AVAILABLE 放行；
 * 其余状态按 §13.2 给出「版本不匹配 / 未启用 / 插件缺失 / 契约异常」的定向文案。</p>
 *
 * <p>题库、rubric、persona、MBB-AI、数据目录尚未接线，默认用
 * {@link EvalPreflightSources.PendingSource} 表达 PENDING：视为通过，但同一实例对同一项只
 * WARN 一次「&lt;项&gt; 尚未接线，跳过预检」。这样骨架期不会因为缺数据源而「永远拒绝」。
 * B-5 / D-4 / F-2 接线时通过 {@link Builder} 替换对应来源即可，不改本类编排。</p>
 *
 * <p>预算计划集中在 {@link EvalPreflightPlan}：full 79 次、smoke 25 次，超出 evalCallBudget 时
 * 按 §13.2 同时给出构成与两条出路（调大上限至 100 或改跑 smoke）。</p>
 *
 * <p>兼容：保留 A-3 的 {@link Rejection} 三参构造、{@link Check} 与
 * {@link CapabilitiesSource}，{@link EvalRunner} 与 EvalA3Check 不需要改动。</p>
 */
public final class EvalPreflight {

    /** full 档生成调用数（40 题）。 */
    public static final int GENERATION_CALLS_FULL = EvalPreflightPlan.GENERATION_CALLS_FULL;
    /** full 档 judge 调用数（36，题集维度加权后固定）。 */
    public static final int JUDGE_CALLS_FULL = EvalPreflightPlan.JUDGE_CALLS_FULL;
    /** smoke 档生成调用数（12 题）。 */
    public static final int GENERATION_CALLS_SMOKE = EvalPreflightPlan.GENERATION_CALLS_SMOKE;
    /** smoke 档 judge 调用数（10）。 */
    public static final int JUDGE_CALLS_SMOKE = EvalPreflightPlan.JUDGE_CALLS_SMOKE;
    /** 校准调用数，固定 3 次且不可配置（§9.5）。 */
    public static final int CALIBRATION_CALLS = EvalPreflightPlan.CALIBRATION_CALLS;
    /** full 档 planned 调用数（40 + 36 + 3）。 */
    public static final int PLANNED_CALLS_FULL = EvalPreflightPlan.PLANNED_CALLS_FULL;
    /** smoke 档 planned 调用数（12 + 10 + 3）。 */
    public static final int PLANNED_CALLS_SMOKE = EvalPreflightPlan.PLANNED_CALLS_SMOKE;
    /** full 档题数（B 区题库接入后改为按题库子集实际条数）。 */
    public static final int CASE_COUNT_FULL = EvalPreflightPlan.CASE_COUNT_FULL;
    /** smoke 档题数（同上）。 */
    public static final int CASE_COUNT_SMOKE = EvalPreflightPlan.CASE_COUNT_SMOKE;

    /** 拒绝后的处置：拒绝建 run，或降级告警试跑（persona 不一致且允许时使用）。 */
    public enum Disposition {
        /** 拒绝：不建 run、不调 AI。 */
        REJECT("拒绝"),
        /** 降级试跑：照常建 run，但回执与日志带告警。 */
        TRIAL("降级试跑");

        private final String label;

        Disposition(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 预检失败结果：错误码可为空（未定义专用码的检查留空，§13.1.1）。 */
    public static final class Rejection {
        private final Stage stage;
        private final String code;
        private final String message;
        private final String hint;
        private final Disposition disposition;
        private final List<String> details;

        /** A-3 兼容构造：无阶段、按拒绝处理。 */
        public Rejection(String code, String message, String hint) {
            this(null, code, message, hint, Disposition.REJECT, null);
        }

        public Rejection(Stage stage, String code, String message, String hint, Disposition disposition) {
            this(stage, code, message, hint, disposition, null);
        }

        public Rejection(Stage stage, String code, String message, String hint, Disposition disposition,
                List<String> details) {
            this.stage = stage;
            this.code = code;
            this.message = message;
            this.hint = hint;
            this.disposition = disposition == null ? Disposition.REJECT : disposition;
            this.details = Collections.unmodifiableList(new ArrayList<String>(
                    details == null ? Collections.<String>emptyList() : details));
        }

        /** 把阶段结论包装为拒绝；非 REJECT 结论不允许走这里。 */
        public static Rejection of(Stage stage, StageReport report) {
            if (report == null || !report.isReject()) {
                throw new IllegalArgumentException("只有 REJECT 结论才能生成拒绝回执");
            }
            return new Rejection(stage, report.code(), report.message(), report.hint(),
                    Disposition.REJECT, report.details());
        }

        /** 命中的预检阶段；A-3 兼容构造返回 null。 */
        public Stage stage() {
            return stage;
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

        public Disposition disposition() {
            return disposition;
        }

        /** 逐条明细（题库前 5 条错误等）；无明细时为空列表。 */
        public List<String> details() {
            return details;
        }
    }

    /** 单项检查：通过返回 null，失败返回拒绝原因。 */
    public interface Check {
        Rejection check(String tier);
    }

    /** capabilities 原始数据来源；抽成接口便于自检替换（A-3 兼容）。 */
    public interface CapabilitiesSource {
        JSONObject fetch();
    }

    private final boolean pipelineEnabled;
    private final List<Check> checks;
    private final EvalConfig config;
    private final PluginLogger logger;
    private final CapabilitiesStatusSource capabilitiesSource;
    private final StageSource bankSource;
    private final StageSource rubricSource;
    private final StageSource personaSource;
    private final StageSource judgeProfileSource;
    private final StageSource aiSource;
    private final StageSource dataDirSource;
    /** 同一实例对同一阶段的 WARN/PENDING 只告警一次（A11 验收：日志仅一次 WARN）。 */
    private final Set<Stage> warnedStages = Collections.synchronizedSet(new HashSet<Stage>());

    /** A-3 兼容构造：只跑外部给定的检查列表，不启用八项管线。 */
    public EvalPreflight(List<Check> checks) {
        this.pipelineEnabled = false;
        this.checks = checks == null
                ? Collections.<Check>emptyList()
                : Collections.unmodifiableList(new ArrayList<Check>(checks));
        this.config = null;
        this.logger = null;
        this.capabilitiesSource = null;
        this.bankSource = null;
        this.rubricSource = null;
        this.personaSource = null;
        this.judgeProfileSource = null;
        this.aiSource = null;
        this.dataDirSource = null;
    }

    private EvalPreflight(Builder builder) {
        this.pipelineEnabled = true;
        this.checks = Collections.emptyList();
        this.config = builder.config;
        this.logger = builder.logger;
        this.capabilitiesSource = builder.capabilities;
        this.bankSource = builder.bank == null ? PendingSource.of(Stage.BANK) : builder.bank;
        this.rubricSource = builder.rubric == null ? PendingSource.of(Stage.RUBRIC) : builder.rubric;
        this.personaSource = builder.persona == null ? PendingSource.of(Stage.PERSONA) : builder.persona;
        if (builder.judgeProfile != null) {
            this.judgeProfileSource = builder.judgeProfile;
        } else if (builder.config != null) {
            this.judgeProfileSource = new ConfigJudgeProfileSource(builder.config, builder.capabilities);
        } else {
            this.judgeProfileSource = PendingSource.of(Stage.JUDGE_PROFILE);
        }
        this.aiSource = builder.ai == null ? PendingSource.of(Stage.MBB_AI) : builder.ai;
        this.dataDirSource = builder.dataDir == null ? PendingSource.of(Stage.DATA_DIR) : builder.dataDir;
    }

    /** 八项预检的可注入装配入口。 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 生产装配：Roleplay 能力快照 + judge profile 回退链 + 其余未接线项按 PENDING 放行。
     *
     * <p>能力探测统一经 {@link RoleplayEvalGateway#fetchCapabilities()}，不在预检里直接
     * 摸 {@code PluginManager}：取服务、调用动作与出参解析都收在网关内；判定与缓存由
     * {@link EvalCapabilityClient} 负责，本类只读快照。</p>
     */
    public static EvalPreflight forPlugin(Plugin plugin, EvalConfig config) {
        final PluginLogger logger = plugin == null ? null : plugin.getLogger();
        final RoleplayEvalGateway gateway = new RoleplayEvalGateway(plugin, logger);
        final EvalCapabilityClient client = new EvalCapabilityClient(new EvalCapabilityClient.CapabilitiesSource() {
            @Override
            public Object fetch() {
                return gateway.fetchCapabilities();
            }
        }, logger);
        return builder()
                .config(config)
                .logger(logger)
                .capabilities(new CapabilitiesStatusSource() {
                    @Override
                    public EvalCapabilityClient.Snapshot snapshot() {
                        return client.snapshot();
                    }
                })
                .build();
    }

    /**
     * 依次执行预检。
     *
     * @return 第一条失败的原因；全部通过返回 null
     * @throws IllegalArgumentException 档位非法
     */
    public Rejection check(String tier) {
        requireTier(tier);
        if (pipelineEnabled) {
            Rejection rejection = checkPipeline(tier);
            if (rejection != null) {
                return rejection;
            }
        }
        for (Check check : checks) {
            Rejection rejection = check.check(tier);
            if (rejection != null) {
                return rejection;
            }
        }
        return null;
    }

    /** 八项按 §9.5 顺序执行，REJECT 立即短路。 */
    private Rejection checkPipeline(String tier) {
        Rejection rejection = evaluate(Stage.CAPABILITIES, capabilitiesReport());
        if (rejection != null) {
            return rejection;
        }
        rejection = evaluate(Stage.BANK, reportOf(bankSource, Stage.BANK, tier));
        if (rejection != null) {
            return rejection;
        }
        rejection = evaluate(Stage.RUBRIC, reportOf(rubricSource, Stage.RUBRIC, tier));
        if (rejection != null) {
            return rejection;
        }
        rejection = evaluate(Stage.PERSONA, reportOf(personaSource, Stage.PERSONA, tier));
        if (rejection != null) {
            return rejection;
        }
        rejection = evaluate(Stage.JUDGE_PROFILE, reportOf(judgeProfileSource, Stage.JUDGE_PROFILE, tier));
        if (rejection != null) {
            return rejection;
        }
        rejection = evaluate(Stage.MBB_AI, reportOf(aiSource, Stage.MBB_AI, tier));
        if (rejection != null) {
            return rejection;
        }
        rejection = evaluate(Stage.DATA_DIR, reportOf(dataDirSource, Stage.DATA_DIR, tier));
        if (rejection != null) {
            return rejection;
        }
        return evaluate(Stage.BUDGET, budgetReport(tier));
    }

    /** 统一收口：REJECT 转拒绝回执；WARN/PENDING 只告警一次后放行。 */
    private Rejection evaluate(Stage stage, StageReport report) {
        StageReport conclusion = report == null ? StageReport.pending(stage) : report;
        if (conclusion.isReject()) {
            if (stage == Stage.CAPABILITIES && warnOnce(stage)) {
                logger.sendWarn("[MBB-Eval] 预检失败：" + conclusion.message()
                        + "（后续同类拒绝不再重复告警）");
            }
            return Rejection.of(stage, conclusion);
        }
        if (conclusion.outcome() != StageReport.Outcome.PASS && warnOnce(stage)) {
            logger.sendWarn("[MBB-Eval] 预检告警：" + conclusion.message()
                    + "（后续同类告警不再重复）");
        }
        return null;
    }

    private boolean warnOnce(Stage stage) {
        if (logger == null) {
            return false;
        }
        return warnedStages.add(stage);
    }

    private StageReport reportOf(StageSource source, Stage stage, String tier) {
        if (source == null) {
            return StageReport.pending(stage);
        }
        try {
            StageReport report = source.report(tier);
            return report == null ? StageReport.pending(stage) : report;
        } catch (RuntimeException e) {
            return StageReport.reject("", stage.label() + " 预检异常：" + e.getMessage(),
                    "请查看插件日志后重试 /eval run；本次未建 run、未产生任何 AI 调用");
        }
    }

    private StageReport capabilitiesReport() {
        EvalCapabilityClient.Snapshot snapshot;
        try {
            snapshot = capabilitiesSource == null ? null : capabilitiesSource.snapshot();
        } catch (RuntimeException e) {
            snapshot = null;
        }
        if (snapshot == null) {
            return StageReport.reject("", "接口能力不可用：MBB-Roleplay 插件缺失或 capabilities 不可调",
                    EvalCapabilityClient.Status.PLUGIN_MISSING.hint());
        }
        if (snapshot.status() == EvalCapabilityClient.Status.AVAILABLE) {
            return StageReport.pass(snapshot.reason());
        }
        return StageReport.reject("", "接口能力不可用：" + snapshot.reason(), snapshot.hint());
    }

    private StageReport budgetReport(String tier) {
        if (config == null) {
            return StageReport.reject("", "配置未就绪，无法计算 planned 预算",
                    "请重启插件后重试 /eval run");
        }
        EvalPreflightPlan plan = EvalPreflightPlan.of(tier);
        int limit = config.callBudget();
        if (plan.fits(limit)) {
            return StageReport.pass("预算充足：planned " + plan.planned() + "/" + limit);
        }
        return StageReport.reject("", plan.shortfallMessage(limit), plan.shortfallHint());
    }

    public static int plannedCalls(String tier) {
        return EvalPreflightPlan.of(tier).planned();
    }

    public static int caseCount(String tier) {
        return EvalPreflightPlan.of(tier).caseCount();
    }

    public static int generationCalls(String tier) {
        return EvalPreflightPlan.of(tier).generationCalls();
    }

    public static int judgeCalls(String tier) {
        return EvalPreflightPlan.of(tier).judgeCalls();
    }

    /**
     * 预计时长下限（分钟）：骨架期没有同档历史 p50，按每次调用 4 秒估算（§9.5）。
     * F 区接入历史 p50 后由报表侧替换。
     */
    public static int estimatedMinutesLow(int planned) {
        return EvalPreflightPlan.estimatedMinutesLow(planned);
    }

    /** 预计时长上限（分钟）：骨架期取单点估算的 ±100%，只用于回执区间展示。 */
    public static int estimatedMinutesHigh(int planned) {
        return EvalPreflightPlan.estimatedMinutesHigh(planned);
    }

    private static void requireTier(String tier) {
        if (!"smoke".equals(tier) && !"full".equals(tier)) {
            throw new IllegalArgumentException("未知评测档位：" + tier);
        }
    }

    /** 预检装配器：未显式注入的来源保持 PENDING。 */
    public static final class Builder {
        private EvalConfig config;
        private PluginLogger logger;
        private CapabilitiesStatusSource capabilities;
        private StageSource bank;
        private StageSource rubric;
        private StageSource persona;
        private StageSource judgeProfile;
        private StageSource ai;
        private StageSource dataDir;

        private Builder() {
        }

        public Builder config(EvalConfig config) {
            this.config = config;
            return this;
        }

        public Builder logger(PluginLogger logger) {
            this.logger = logger;
            return this;
        }

        public Builder capabilities(CapabilitiesStatusSource capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public Builder bank(BankStatusSource bank) {
            this.bank = bank;
            return this;
        }

        public Builder rubric(RubricStatusSource rubric) {
            this.rubric = rubric;
            return this;
        }

        public Builder persona(PersonaStatusSource persona) {
            this.persona = persona;
            return this;
        }

        public Builder judgeProfile(JudgeProfileSource judgeProfile) {
            this.judgeProfile = judgeProfile;
            return this;
        }

        public Builder ai(AiStatusSource ai) {
            this.ai = ai;
            return this;
        }

        public Builder dataDir(DataDirStatusSource dataDir) {
            this.dataDir = dataDir;
            return this;
        }

        public EvalPreflight build() {
            return new EvalPreflight(this);
        }
    }
}
