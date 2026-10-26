package org.moboxlab.mbb.eval.runner;

/**
 * 预检预算计划（规格 §9.5、§9.3.1、§13.2）。
 *
 * <p>把 full / smoke 两档的调用构成、题数与预计时长收敛到一处：生成调用 + judge 调用 +
 * 固定 3 次校准调用 = planned。full 档为 40 + 36 + 3 = 79，smoke 档为 12 + 10 + 3 = 25；
 * 校准调用恒为 3 次且不可配置，题数与生成调用数同源。</p>
 *
 * <p>{@code evalCallBudget} 默认 100、取值 1..100（超 100 由配置层按 100 钳制并告警），
 * 预算判定只看 planned 是否超过上限；拒绝时按 §13.2 同时给出构成与两条出路。预计时长在
 * 没有同档历史 p50 时按每次调用 4 秒估算，只用于回执展示，不参与预算判定。</p>
 */
public final class EvalPreflightPlan {

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
    public static final int PLANNED_CALLS_FULL = GENERATION_CALLS_FULL + JUDGE_CALLS_FULL + CALIBRATION_CALLS;
    /** smoke 档 planned 调用数（12 + 10 + 3）。 */
    public static final int PLANNED_CALLS_SMOKE = GENERATION_CALLS_SMOKE + JUDGE_CALLS_SMOKE + CALIBRATION_CALLS;
    /** full 档题数（B 区题库接入后改为按题库子集实际条数）。 */
    public static final int CASE_COUNT_FULL = 40;
    /** smoke 档题数（同上）。 */
    public static final int CASE_COUNT_SMOKE = 12;
    /** evalCallBudget 的硬上限（§9.5：超过 100 按 100 生效）。 */
    public static final int MAX_CALL_BUDGET = 100;
    /** 无同档历史 p50 时单次调用的估算秒数（§9.5）。 */
    public static final int SECONDS_PER_CALL = 4;

    private static final EvalPreflightPlan FULL =
            new EvalPreflightPlan("full", GENERATION_CALLS_FULL, JUDGE_CALLS_FULL, CASE_COUNT_FULL);
    private static final EvalPreflightPlan SMOKE =
            new EvalPreflightPlan("smoke", GENERATION_CALLS_SMOKE, JUDGE_CALLS_SMOKE, CASE_COUNT_SMOKE);

    private final String tier;
    private final int generationCalls;
    private final int judgeCalls;
    private final int planned;
    private final int caseCount;

    private EvalPreflightPlan(String tier, int generationCalls, int judgeCalls, int caseCount) {
        this.tier = tier;
        this.generationCalls = generationCalls;
        this.judgeCalls = judgeCalls;
        this.caseCount = caseCount;
        this.planned = generationCalls + judgeCalls + CALIBRATION_CALLS;
    }

    /** 按档位取计划；档位非法时抛 {@link IllegalArgumentException}。 */
    public static EvalPreflightPlan of(String tier) {
        if ("full".equals(tier)) {
            return FULL;
        }
        if ("smoke".equals(tier)) {
            return SMOKE;
        }
        throw new IllegalArgumentException("未知评测档位：" + tier);
    }

    public String tier() {
        return tier;
    }

    public int generationCalls() {
        return generationCalls;
    }

    public int judgeCalls() {
        return judgeCalls;
    }

    public int calibrationCalls() {
        return CALIBRATION_CALLS;
    }

    public int planned() {
        return planned;
    }

    public int caseCount() {
        return caseCount;
    }

    /** planned 的构成文案，直接用于回执与日志，例如「生成 40 + judge 36 + 校准 3 = 79」。 */
    public String composition() {
        return "生成 " + generationCalls + " + judge " + judgeCalls + " + 校准 " + CALIBRATION_CALLS
                + " = " + planned;
    }

    /** planned 是否不超过给定的 evalCallBudget。 */
    public boolean fits(int limit) {
        return planned <= limit;
    }

    /** 预计时长下限（分钟）：按每次调用 4 秒估算，无历史 p50 时的兜底（§9.5）。 */
    public int minutesLow() {
        return estimatedMinutesLow(planned);
    }

    /** 预计时长上限（分钟）：取单点估算的 ±100%，只用于回执区间展示。 */
    public int minutesHigh() {
        return estimatedMinutesHigh(planned);
    }

    /** 生成 §9.3.1 的回执数字；limit 为本次 run 实际生效的 evalCallBudget。 */
    public Receipt receipt(int limit) {
        return new Receipt(tier, planned, limit, caseCount, minutesLow(), minutesHigh());
    }

    /** 预算不足时的原因文案（§13.2），带档位、构成与实际上限。 */
    public String shortfallMessage(int limit) {
        return "预算不足：" + tier + " 档 planned " + planned + " 次（" + composition()
                + "；重试与缓存重采另计，需要额外余量）超过 evalCallBudget=" + limit
                + "（上限 " + MAX_CALL_BUDGET + "）";
    }

    /** 预算不足时的两条出路（§13.2 判定规则 5）：调大上限到 planned 或改跑 smoke。 */
    public String shortfallHint() {
        if ("smoke".equals(tier)) {
            return "请调大 evalCallBudget 至至少 " + planned + "（上限 " + MAX_CALL_BUDGET
                    + "）；smoke 已是最低档，无法再改跑更低档；重试与缓存重采会额外消耗预算，请预留余量；"
                    + "修复后重试 /eval run；本次未建 run、未产生任何 AI 调用";
        }
        return "两条出路：调大 evalCallBudget 至至少 " + planned + "（上限 " + MAX_CALL_BUDGET
                + "），或改跑 smoke 档（planned " + PLANNED_CALLS_SMOKE + "、题数 " + CASE_COUNT_SMOKE
                + "）；重试与缓存重采会额外消耗预算，请预留余量；"
                + "修复后重试 /eval run；本次未建 run、未产生任何 AI 调用";
    }

    /** §9.3.1 回执第二行的数字载体。 */
    public static final class Receipt {
        private final String tier;
        private final int planned;
        private final int limit;
        private final int caseCount;
        private final int minutesLow;
        private final int minutesHigh;

        private Receipt(String tier, int planned, int limit, int caseCount, int minutesLow, int minutesHigh) {
            this.tier = tier;
            this.planned = planned;
            this.limit = limit;
            this.caseCount = caseCount;
            this.minutesLow = minutesLow;
            this.minutesHigh = minutesHigh;
        }

        public String tier() {
            return tier;
        }

        public int planned() {
            return planned;
        }

        public int limit() {
            return limit;
        }

        public int caseCount() {
            return caseCount;
        }

        public int minutesLow() {
            return minutesLow;
        }

        public int minutesHigh() {
            return minutesHigh;
        }

        /** 回执第二行文案，例如「题数 12（smoke）／预计调用 25/100／预计 2-4 分钟」。 */
        public String summary() {
            return "题数 " + caseCount + "（" + tier + "）／预计调用 " + planned + "/" + limit
                    + "／预计 " + minutesLow + "-" + minutesHigh + " 分钟";
        }
    }

    /** 预计时长下限：每次调用 4 秒，至少 1 分钟。 */
    public static int estimatedMinutesLow(int planned) {
        return Math.max(1, (planned * SECONDS_PER_CALL + 59) / 60);
    }

    /** 预计时长上限：每次调用 8 秒，且至少比下限多 1 分钟。 */
    public static int estimatedMinutesHigh(int planned) {
        return Math.max(estimatedMinutesLow(planned) + 1, (planned * SECONDS_PER_CALL * 2 + 59) / 60);
    }
}
