package org.moboxlab.mbb.eval.baseline;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可信度附加标注器（规格 #50 §8.5）。
 *
 * <p>只产出中文标注文本，不改任何分数。三条硬门槛（有效题不足 80%、非 ok 率超过 10%、
 * rubric 已变）由 {@code score.EvalCredibility} 与 {@link EvalDeltaService} 负责；
 * 本类补齐规格 §8.5 列出的附加标注：</p>
 * <ul>
 *   <li>judge_model 与被测 generation_model 同家族 →「同家族 judge，分数仅作趋势」；</li>
 *   <li>校准 evidence 定位失败率 &gt; 10% →「rubric 不自证」；</li>
 *   <li>k=3 全同率 &lt; 70% →「judge 抖动大」；</li>
 *   <li>缓存命中数 ≠ 0 →「缓存键疑似配置错误，性能与 token 不可用」；</li>
 *   <li>calls_used 达到预算（默认 100）→「预算耗尽，样本不完整」。</li>
 * </ul>
 *
 * <p>calibration_json 的结构票面未冻结，本类对常见键名做兼容读取；读不到失败率时
 * 宁可不标注，也不猜测。</p>
 */
public final class EvalDeltaAnnotator {

    /** evidence 定位失败率门槛：超过 10% 视为 rubric 不自证。 */
    public static final double MAX_EVIDENCE_FAILURE_RATE = 0.10d;
    /** k=3 全同率门槛：低于 70% 视为 judge 抖动大。 */
    public static final double MIN_ALL_SAME_RATE = 0.70d;
    /** 默认单 run 调用预算（配置缺失时按 100 处理）。 */
    public static final int DEFAULT_CALL_BUDGET = 100;

    /** 返回本次 run 的附加标注；无命中时返回空列表。 */
    public List<String> annotate(JSONObject run, List<JSONObject> caseRows) {
        List<String> notes = new ArrayList<String>();
        if (run == null) {
            return notes;
        }
        if (sameFamily(run)) {
            notes.add("同家族 judge，分数仅作趋势");
        }
        Double failureRate = evidenceFailureRate(run.getString("calibration_json"));
        if (failureRate != null && failureRate > MAX_EVIDENCE_FAILURE_RATE) {
            notes.add("rubric 不自证：校准 evidence 定位失败率 "
                    + percent(failureRate) + " 超过 10%");
        }
        K3Stats stats = k3Stats(caseRows);
        if (stats.total() > 0 && stats.allSameRate() < MIN_ALL_SAME_RATE) {
            notes.add("judge 抖动大：k=3 全同率 " + percent(stats.allSameRate()) + " 低于 70%");
        }
        if (cachedCount(caseRows) > 0) {
            notes.add("缓存键疑似配置错误，性能与 token 不可用");
        }
        int callsUsed = intValue(run, "calls_used", 0);
        int callsBudget = intValue(run, "calls_budget", DEFAULT_CALL_BUDGET);
        if (callsBudget > 0 && callsUsed >= callsBudget) {
            notes.add("预算耗尽，样本不完整");
        }
        return notes;
    }

    /**
     * judge_model 与被测 generation_model 是否同家族。
     *
     * <p>先判全名相同，再取模型标识的字母前缀（如 qwen2.5→qwen、gpt-4o→gpt），
     * 前缀至少 3 个字母才算家族，避免 qwen 与 qwen2 这类误判以外的短名噪声。</p>
     */
    public static boolean sameFamily(JSONObject run) {
        if (run == null) {
            return false;
        }
        String judge = trimToNull(run.getString("judge_model"));
        String generation = trimToNull(run.getString("generation_model"));
        if (judge == null || generation == null) {
            return false;
        }
        if (judge.equalsIgnoreCase(generation)) {
            return true;
        }
        String judgeFamily = modelFamily(judge);
        String generationFamily = modelFamily(generation);
        return judgeFamily != null && judgeFamily.equals(generationFamily);
    }

    /** 取模型家族：去掉提供方前缀后，取开头连续的小写字母，至少 3 个。 */
    public static String modelFamily(String model) {
        String value = trimToNull(model);
        if (value == null) {
            return null;
        }
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) {
            value = value.substring(slash + 1);
        }
        value = value.trim().toLowerCase();
        int end = 0;
        while (end < value.length() && isAsciiLetter(value.charAt(end))) {
            end++;
        }
        return end >= 3 ? value.substring(0, end) : null;
    }

    /**
     * 从 calibration_json 读取 evidence 定位失败率（0..1）。
     *
     * <p>兼容三种写法：直接给比率（evidenceFailureRate），给失败数与总数
     * （evidenceFailures / evidenceTotal），或放在 calibration/evidence 嵌套对象里。
     * 值大于 1 时按百分数换算。读不到返回 null。</p>
     */
    public static Double evidenceFailureRate(String calibrationJson) {
        JSONObject object = parseObject(calibrationJson);
        return object == null ? null : rateFrom(object, 0);
    }

    /**
     * 统计 k=3 题的全同率与翻转清单（§6.7.2）。
     *
     * <p>只统计 status=ok 且 k_used=3 的题；detail_json.samples 里 cached=true 的样本
     * 作废，不参与判定。有效 label 少于 2 个的题无法判断抖动，既不进分子也不进分母。</p>
     */
    public static K3Stats k3Stats(List<JSONObject> caseRows) {
        if (caseRows == null || caseRows.isEmpty()) {
            return new K3Stats(0, 0, Collections.<String>emptyList());
        }
        int total = 0;
        int same = 0;
        List<String> flipped = new ArrayList<String>();
        for (JSONObject row : caseRows) {
            if (row == null || !"ok".equalsIgnoreCase(row.getString("status"))) {
                continue;
            }
            if (intValue(row, "k_used", 0) != 3) {
                continue;
            }
            List<Double> labels = sampleLabels(row.getString("detail_json"));
            if (labels.size() < 2) {
                continue;
            }
            total++;
            if (allEqual(labels)) {
                same++;
            } else {
                flipped.add(row.getString("case_id"));
            }
        }
        return new K3Stats(total, same, flipped);
    }

    /** 优惠统计：缓存命中数；cached_count 列缺失时退回 samples 里的 cached 标记。 */
    public static int cachedCount(List<JSONObject> caseRows) {
        if (caseRows == null) {
            return 0;
        }
        int cached = 0;
        for (JSONObject row : caseRows) {
            if (row == null) {
                continue;
            }
            Integer column = row.getInteger("cached_count");
            if (column != null) {
                cached += Math.max(0, column);
            } else {
                cached += cachedSamples(row.getString("detail_json"));
            }
        }
        return cached;
    }

    /** 读取 detail_json.samples 中未缓存的有效 label。 */
    public static List<Double> sampleLabels(String detailJson) {
        JSONObject detail = parseObject(detailJson);
        if (detail == null) {
            return Collections.emptyList();
        }
        JSONArray samples = detail.getJSONArray("samples");
        if (samples == null || samples.isEmpty()) {
            return Collections.emptyList();
        }
        List<Double> labels = new ArrayList<Double>();
        for (int i = 0; i < samples.size(); i++) {
            JSONObject sample = samples.getJSONObject(i);
            if (sample == null || isCached(sample)) {
                continue;
            }
            Double label = doubleValue(sample.get("label"));
            if (label != null) {
                labels.add(label);
            }
        }
        return labels;
    }

    private static int cachedSamples(String detailJson) {
        JSONObject detail = parseObject(detailJson);
        if (detail == null) {
            return 0;
        }
        JSONArray samples = detail.getJSONArray("samples");
        if (samples == null) {
            return 0;
        }
        int cached = 0;
        for (int i = 0; i < samples.size(); i++) {
            JSONObject sample = samples.getJSONObject(i);
            if (sample != null && isCached(sample)) {
                cached++;
            }
        }
        return cached;
    }

    private static boolean isCached(JSONObject sample) {
        Object value = sample.get("cached");
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean allEqual(List<Double> labels) {
        double first = labels.get(0);
        for (Double label : labels) {
            if (Math.abs(label - first) > 1e-9d) {
                return false;
            }
        }
        return true;
    }

    private static Double rateFrom(JSONObject object, int depth) {
        if (object == null || depth > 3) {
            return null;
        }
        String[] directKeys = {
                "evidenceFailureRate", "evidenceLocateFailureRate",
                "evidenceFailRate", "evidence_failure_rate"
        };
        for (String key : directKeys) {
            Double value = doubleValue(object.get(key));
            if (value != null) {
                return value > 1.0d ? value / 100.0d : value;
            }
        }
        String[] failureKeys = {
                "evidenceFailures", "evidenceFailureCount", "evidenceMisses",
                "locateFailures", "missCount"
        };
        String[] totalKeys = {
                "evidenceTotal", "evidenceChecks", "evidenceCount",
                "evidenceSamples", "checkedCount", "sampleCount", "total"
        };
        Double failures = firstNumber(object, failureKeys);
        Double total = firstNumber(object, totalKeys);
        if (failures != null && total != null && total > 0.0d) {
            return failures / total;
        }
        String[] nestedKeys = {"calibration", "evidence", "evidenceLocate", "locate"};
        for (String key : nestedKeys) {
            Object nested = object.get(key);
            if (nested instanceof JSONObject) {
                Double value = rateFrom((JSONObject) nested, depth + 1);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static Double firstNumber(JSONObject object, String[] keys) {
        for (String key : keys) {
            Double value = doubleValue(object.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int intValue(JSONObject row, String key, int fallback) {
        Object value = row.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static JSONObject parseObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            Object parsed = JSONObject.parse(json.trim());
            return parsed instanceof JSONObject ? (JSONObject) parsed : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String percent(double rate) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", rate * 100.0d);
    }

    /** k=3 抖动统计结果。 */
    public static final class K3Stats {

        private final int total;
        private final int allSameCount;
        private final List<String> flippedCaseIds;

        K3Stats(int total, int allSameCount, List<String> flippedCaseIds) {
            this.total = total;
            this.allSameCount = allSameCount;
            this.flippedCaseIds = Collections.unmodifiableList(new ArrayList<String>(flippedCaseIds));
        }

        /** 可判的 k=3 题数（有效 label 至少 2 个）。 */
        public int total() {
            return total;
        }

        public int allSameCount() {
            return allSameCount;
        }

        /** 全同率；没有可判题时为 NaN。 */
        public double allSameRate() {
            return total > 0 ? (double) allSameCount / (double) total : Double.NaN;
        }

        /** 采样不全同的 case_id。 */
        public List<String> flippedCaseIds() {
            return flippedCaseIds;
        }
    }
}
