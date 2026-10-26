package org.moboxlab.mbb.eval.baseline;

import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.moboxlab.mbb.eval.store.EvalStore;

/**
 * 九键比较器（规格 #50 §8.2、§8.4，验收 F13 / F14）。
 *
 * <p>基线可比性的唯一判据：两次 run 都是 full 档，且九键（顺序固定）逐键相等。
 * 九键依次为 bank_hash、rubric_version、persona_hash、config_fingerprint、eval_profile、
 * judge_profile、judge_model、generation_model、eval_api_version；顺序与
 * {@code EvalReportData.nineKeyNames()} 一致，报表 §2 与 §5 共用同一份口径。</p>
 *
 * <p>比较粒度是**键值字符串**，不是配置文件字节：F14 明确 config_fingerprint 由
 * {@code sha256(configVersion + "|" + 规范化 k=v 串)} 计算，config.yml 的注释、键序、迁移
 * 过程都会改变文件字节但不改变指纹。因此本类只比较指纹值，绝不读取或散列
 * config_snapshot_json，快照文本差异不影响判定。</p>
 *
 * <p>缺失键（任一行为 null 或空白）单独归入 {@link Result#missingKeys()}，不混入
 * {@link Result#differingKeys()}；缺失同样判为不可比，但报表要能区分「口径变了」与
 * 「快照没采到」。差异字段列出全部，不只前 N 个（F13）。</p>
 *
 * <p>本类只做判定，不写库、不写报表；报表接线由 F-4 的调用方完成。</p>
 */
public final class EvalRunComparability {

    /** §8.2 九键，顺序固定，1..9。 */
    public static final List<String> KEY_NAMES = Collections.unmodifiableList(Arrays.asList(
            "bank_hash", "rubric_version", "persona_hash", "config_fingerprint",
            "eval_profile", "judge_profile", "judge_model", "generation_model",
            "eval_api_version"));

    /** full 档是唯一可参与比较的档位（§8.1）。 */
    private static final String TIER_FULL = EvalStore.TIER_FULL;

    /** 不可比原因：档位不是 full（smoke 永不作比较，§8.1 #37）。 */
    public static final String REASON_TIER = "档位不是 full（smoke 不参与比较）";
    /** 不可比原因：九键有缺失。 */
    public static final String REASON_MISSING = "九键缺失";
    /** 不可比原因：九键取值不同。 */
    public static final String REASON_DIFFERENT = "九键不同";
    /** 不可比原因：run 行缺失或查询失败。 */
    public static final String REASON_RUN_MISSING = "run 行缺失";

    private EvalRunComparability() {
        // 工具类不允许实例化
    }

    /** 九键名称（只读，顺序同 §8.2）。 */
    public static List<String> keyNames() {
        return KEY_NAMES;
    }

    /**
     * 取出九键值；缺失或空白统一为 null，便于调用方区分「有值但不相等」与「没采到」。
     *
     * @param row run 行或基线行；null 视为全部缺失
     */
    public static Map<String, String> nineKeyValues(JSONObject row) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String key : KEY_NAMES) {
            values.put(key, text(row, key));
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * 比较两次 run（§8.2、F13）：只有双方都是 full 且九键全等才可比。
     *
     * @param runA 基线侧 run 行
     * @param runB 本次 run 行
     */
    public static Result compare(JSONObject runA, JSONObject runB) {
        if (runA == null || runB == null) {
            return new Result(false, REASON_RUN_MISSING,
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap());
        }
        return compareRows(runA, runB, true);
    }

    /**
     * 比较一次 run 与一条基线行（基线行没有 tier 列，只有 run 侧校验档位）。
     *
     * @param run 本次 run 行；必须是 full
     * @param baselineRow plugin_mbb_eval_baseline 行（含九键快照列）
     */
    public static Result compareToBaseline(JSONObject run, JSONObject baselineRow) {
        if (run == null || baselineRow == null) {
            return new Result(false, REASON_RUN_MISSING,
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap());
        }
        return compareRows(run, baselineRow, false);
    }

    /** 按 run_id 从库中取行再比较；缺行按不可比返回，报表侧据此写「数据缺失」。 */
    public static Result compareRuns(EvalStore store, String runIdA, String runIdB) {
        if (store == null) {
            throw new IllegalArgumentException("EvalStore 不能为空");
        }
        JSONObject runA = store.findRun(runIdA);
        JSONObject runB = store.findRun(runIdB);
        return compare(runA, runB);
    }

    /** 两侧都是 run 行时校验双档位；基线侧只校验 run。 */
    private static Result compareRows(JSONObject left, JSONObject right, boolean bothRuns) {
        String tierProblem = tierProblem(left, right, bothRuns);
        Map<String, String> valuesLeft = nineKeyValues(left);
        Map<String, String> valuesRight = nineKeyValues(right);
        List<String> missingKeys = new ArrayList<String>();
        List<String> differingKeys = new ArrayList<String>();
        for (String key : KEY_NAMES) {
            String valueLeft = valuesLeft.get(key);
            String valueRight = valuesRight.get(key);
            if (valueLeft == null || valueRight == null) {
                missingKeys.add(key);
            } else if (!valueLeft.equals(valueRight)) {
                differingKeys.add(key);
            }
        }
        String reason = null;
        if (tierProblem != null) {
            reason = tierProblem;
        } else if (!missingKeys.isEmpty()) {
            reason = REASON_MISSING;
        } else if (!differingKeys.isEmpty()) {
            reason = REASON_DIFFERENT;
        }
        boolean comparable = reason == null;
        return new Result(comparable, reason, differingKeys, missingKeys, valuesLeft, valuesRight);
    }

    /** 档位判定：任一侧不是 full（含 null 缺失）即不可比。 */
    private static String tierProblem(JSONObject left, JSONObject right, boolean bothRuns) {
        String tierLeft = text(left, "tier");
        String tierRight = bothRuns ? text(right, "tier") : null;
        if (tierLeft == null || (bothRuns && tierRight == null)) {
            return REASON_TIER;
        }
        if (!TIER_FULL.equals(tierLeft)) {
            return REASON_TIER;
        }
        if (bothRuns && !TIER_FULL.equals(tierRight)) {
            return REASON_TIER;
        }
        return null;
    }

    /** 取文本列：null、空白字符串统一为 null（与 EvalStore.optionalText 口径一致）。 */
    private static String text(JSONObject row, String key) {
        if (row == null || key == null) {
            return null;
        }
        String value = row.getString(key);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value;
    }

    /** 一次比较的完整结论：可比性、原因、全部差异键、缺失键与两侧取值。 */
    public static final class Result {

        private final boolean comparable;
        private final String reason;
        private final List<String> differingKeys;
        private final List<String> missingKeys;
        private final Map<String, String> leftValues;
        private final Map<String, String> rightValues;

        private Result(boolean comparable, String reason, List<String> differingKeys,
                List<String> missingKeys, Map<String, String> leftValues,
                Map<String, String> rightValues) {
            this.comparable = comparable;
            this.reason = reason;
            this.differingKeys = Collections.unmodifiableList(new ArrayList<String>(differingKeys));
            this.missingKeys = Collections.unmodifiableList(new ArrayList<String>(missingKeys));
            this.leftValues = Collections.unmodifiableMap(new LinkedHashMap<String, String>(leftValues));
            this.rightValues = Collections.unmodifiableMap(new LinkedHashMap<String, String>(rightValues));
        }

        /** 仅 full + 九键全等时为 true。 */
        public boolean comparable() {
            return comparable;
        }

        /** 不可比原因；可比时为 null。 */
        public String reason() {
            return reason;
        }

        /** 全部取值不同的键（§8.2 顺序，不只前 N 个）。 */
        public List<String> differingKeys() {
            return differingKeys;
        }

        /** 任一行为 null / 空白的键，单独标记，不重复计入差异键。 */
        public List<String> missingKeys() {
            return missingKeys;
        }

        public boolean hasMissingKeys() {
            return !missingKeys.isEmpty();
        }

        public boolean hasDifferingKeys() {
            return !differingKeys.isEmpty();
        }

        /** 左侧（基线/旧 run）九键取值，缺失为 null。 */
        public Map<String, String> leftValues() {
            return leftValues;
        }

        /** 右侧（本次 run/基线）九键取值，缺失为 null。 */
        public Map<String, String> rightValues() {
            return rightValues;
        }

        /** 报表 §5 可直接使用的差异字段串；无差异时返回空串。 */
        public String differingKeysText() {
            StringBuilder sb = new StringBuilder();
            for (String key : differingKeys) {
                if (sb.length() > 0) {
                    sb.append('\u3001');
                }
                sb.append(key);
            }
            return sb.toString();
        }
    }
}
