package org.moboxlab.mbb.eval.exec;

import com.alibaba.fastjson.JSONObject;

import org.moboxlab.mbb.eval.call.EvalBudgetLedger;
import org.moboxlab.mbb.eval.call.EvalCallInvoker;
import org.moboxlab.mbb.eval.rubric.EvalRubric;
import org.moboxlab.mbb.eval.runner.EvalRunStopPoint;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * 单 case 执行所需的全部依赖（规格 §6.6、§10.2、§13.1.3）。
 *
 * <p>执行器本身不读文件、不建连接、不取时间：run 标识、档位、题目 JSON、rubric、
 * 预算账本、调用器、生成/判分端口、停止点、时间源与日志全部由本上下文注入，
 * 因此可以在不联网、不启动插件宿主的情况下完整测试。</p>
 *
 * <p>维度 {@code knowledge} / {@code style} 走本地确定性判分，只使用
 * {@link #deterministicConfig()} 与 {@link #content()}；其余三维走
 * eval-generate + judge，使用 {@link #generatePort()}、{@link #judgePort()} 与
 * {@link #sampleCount()} 次采样。</p>
 */
public final class EvalCaseContext {

    /** 默认采样数 k；§6.7.1 规定 boundary 固定 k=3。 */
    public static final int DEFAULT_SAMPLE_COUNT = 3;

    private final String runId;
    private final String tier;
    private final String caseId;
    private final String dimension;
    private final JSONObject caseJson;
    private final EvalRubric rubric;
    private final JSONObject deterministicConfig;
    private final EvalBudgetLedger ledger;
    private final EvalCallInvoker invoker;
    private final EvalStore store;
    private final EvalCaseGeneratePort generatePort;
    private final EvalCaseJudgePort judgePort;
    private final EvalCaseClock clock;
    private final PluginLogger logger;
    private final EvalRunStopPoint stopPoint;
    private final int sampleCount;
    private final String personaSummary;
    private final String generateProfile;
    private final int generateTimeoutSeconds;
    private final String promptVersion;
    private final String content;

    private EvalCaseContext(Builder builder) {
        this.runId = builder.runId;
        this.tier = builder.tier;
        this.caseId = builder.caseId;
        this.dimension = builder.dimension;
        this.caseJson = builder.caseJson;
        this.rubric = builder.rubric;
        this.deterministicConfig = builder.deterministicConfig;
        this.ledger = builder.ledger;
        this.invoker = builder.invoker;
        this.store = builder.store;
        this.generatePort = builder.generatePort;
        this.judgePort = builder.judgePort;
        this.clock = builder.clock;
        this.logger = builder.logger;
        this.stopPoint = builder.stopPoint;
        this.sampleCount = builder.sampleCount;
        this.personaSummary = builder.personaSummary;
        this.generateProfile = builder.generateProfile;
        this.generateTimeoutSeconds = builder.generateTimeoutSeconds;
        this.promptVersion = builder.promptVersion;
        this.content = builder.content;
    }

    public static Builder builder(String runId, String caseId) {
        return new Builder(runId, caseId);
    }

    public String runId() {
        return runId;
    }

    /** 档位：smoke / full。 */
    public String tier() {
        return tier;
    }

    public String caseId() {
        return caseId;
    }

    /** 维度 id：persona / boundary / memory / knowledge / style。 */
    public String dimension() {
        return dimension;
    }

    /** 题库用例节点，字段见 bank-1.0.0.json。 */
    public JSONObject caseJson() {
        return caseJson;
    }

    /** 判分规约；未注入时为 null（测试只跑确定性维或假 judge 时可不提供）。 */
    public EvalRubric rubric() {
        return rubric;
    }

    /**
     * 确定性判分配置段（rubric 的 {@code deterministic}）。
     *
     * <p>显式注入优先；否则取 rubric 的副本；都没有时返回空对象，由判分器按缺失配置拒绝。</p>
     */
    public JSONObject deterministicConfig() {
        if (deterministicConfig != null) {
            return deterministicConfig;
        }
        if (rubric != null) {
            return rubric.deterministic();
        }
        return new JSONObject();
    }

    public EvalBudgetLedger ledger() {
        return ledger;
    }

    public EvalCallInvoker invoker() {
        return invoker;
    }

    public EvalStore store() {
        return store;
    }

    public EvalCaseGeneratePort generatePort() {
        return generatePort;
    }

    public EvalCaseJudgePort judgePort() {
        return judgePort;
    }

    public EvalCaseClock clock() {
        return clock;
    }

    public PluginLogger logger() {
        return logger;
    }

    public EvalRunStopPoint stopPoint() {
        return stopPoint;
    }

    /** 采样数 k；确定性维忽略。 */
    public int sampleCount() {
        return sampleCount;
    }

    /** 角色档案摘要，judge 入参使用（1–400 码点，§6.3.3）。 */
    public String personaSummary() {
        return personaSummary;
    }

    /** eval-generate 的 profile 参数。 */
    public String generateProfile() {
        return generateProfile;
    }

    /** eval-generate 的 timeoutSeconds；0 表示不传，由服务端默认。 */
    public int generateTimeoutSeconds() {
        return generateTimeoutSeconds;
    }

    /** judge prompt 结构版本；优先取 rubric，其次取显式注入值。 */
    public String promptVersion() {
        if (rubric != null && rubric.promptVersion() != null) {
            return rubric.promptVersion();
        }
        return promptVersion;
    }

    /** 确定性维（knowledge / style）的被测回复原文。 */
    public String content() {
        return content;
    }

    /** 上下文装配器；必填项缺失立即抛 {@link IllegalArgumentException}，不做静默回退。 */
    public static final class Builder {

        private final String runId;
        private final String caseId;
        private String tier = EvalStore.TIER_SMOKE;
        private String dimension;
        private JSONObject caseJson;
        private EvalRubric rubric;
        private JSONObject deterministicConfig;
        private EvalBudgetLedger ledger;
        private EvalCallInvoker invoker;
        private EvalStore store;
        private EvalCaseGeneratePort generatePort;
        private EvalCaseJudgePort judgePort;
        private EvalCaseClock clock;
        private PluginLogger logger;
        private EvalRunStopPoint stopPoint;
        private int sampleCount = DEFAULT_SAMPLE_COUNT;
        private String personaSummary = "";
        private String generateProfile = "eval";
        private int generateTimeoutSeconds;
        private String promptVersion;
        private String content = "";

        private Builder(String runId, String caseId) {
            this.runId = runId;
            this.caseId = caseId;
        }

        public Builder tier(String value) {
            this.tier = value;
            return this;
        }

        public Builder dimension(String value) {
            this.dimension = value;
            return this;
        }

        public Builder caseJson(JSONObject value) {
            this.caseJson = value;
            return this;
        }

        public Builder rubric(EvalRubric value) {
            this.rubric = value;
            return this;
        }

        public Builder deterministicConfig(JSONObject value) {
            this.deterministicConfig = value;
            return this;
        }

        public Builder ledger(EvalBudgetLedger value) {
            this.ledger = value;
            return this;
        }

        public Builder invoker(EvalCallInvoker value) {
            this.invoker = value;
            return this;
        }

        public Builder store(EvalStore value) {
            this.store = value;
            return this;
        }

        public Builder generatePort(EvalCaseGeneratePort value) {
            this.generatePort = value;
            return this;
        }

        public Builder judgePort(EvalCaseJudgePort value) {
            this.judgePort = value;
            return this;
        }

        public Builder clock(EvalCaseClock value) {
            this.clock = value;
            return this;
        }

        public Builder logger(PluginLogger value) {
            this.logger = value;
            return this;
        }

        public Builder stopPoint(EvalRunStopPoint value) {
            this.stopPoint = value;
            return this;
        }

        public Builder sampleCount(int value) {
            this.sampleCount = value;
            return this;
        }

        public Builder personaSummary(String value) {
            this.personaSummary = value;
            return this;
        }

        public Builder generateProfile(String value) {
            this.generateProfile = value;
            return this;
        }

        public Builder generateTimeoutSeconds(int value) {
            this.generateTimeoutSeconds = value;
            return this;
        }

        public Builder promptVersion(String value) {
            this.promptVersion = value;
            return this;
        }

        public Builder content(String value) {
            this.content = value;
            return this;
        }

        public EvalCaseContext build() {
            requireText("runId", runId);
            requireText("caseId", caseId);
            requireText("dimension", dimension);
            if (caseJson == null) {
                throw new IllegalArgumentException("缺少用例 JSON（caseJson）");
            }
            if (ledger == null) {
                throw new IllegalArgumentException("缺少预算账本（ledger）");
            }
            if (invoker == null) {
                throw new IllegalArgumentException("缺少调用器（invoker）");
            }
            if (store == null) {
                throw new IllegalArgumentException("缺少存储门面（store）");
            }
            if (generatePort == null) {
                throw new IllegalArgumentException("缺少生成端口（generatePort）");
            }
            if (judgePort == null) {
                throw new IllegalArgumentException("缺少判分端口（judgePort）");
            }
            if (clock == null) {
                throw new IllegalArgumentException("缺少时间源（clock）");
            }
            if (stopPoint == null) {
                throw new IllegalArgumentException("缺少停止点（stopPoint）");
            }
            if (sampleCount < 1 || sampleCount > 99) {
                throw new IllegalArgumentException("采样数 k 越界：" + sampleCount + "，取值 1..99");
            }
            if (tier == null || (!EvalStore.TIER_SMOKE.equals(tier) && !EvalStore.TIER_FULL.equals(tier))) {
                throw new IllegalArgumentException("未知评测档位：" + tier);
            }
            return new EvalCaseContext(this);
        }

        private static void requireText(String field, String value) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("缺少必填字段 " + field);
            }
        }
    }
}
