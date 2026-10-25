package org.moboxlab.mbb.eval.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.moboxlab.moboxbot.API.Util.PluginConfig;

/**
 * MBB-Eval 配置读取器。
 *
 * <p>负责 docs/MBB-Eval.md §12.1 的 14 个 eval* 配置键：默认值回退、取值范围钳制与非法值告警。
 * 配置来源是插件自动生成的 config.yml（框架 {@link PluginConfig} 按行解析），本类不直接读写文件。</p>
 *
 * <p>钳制口径见 §12.4：缺键取默认值；数值越界收敛到区间端点并记录 WARN；档位与布尔值非法时
 * 回退默认值。告警由调用方逐条写入日志，不得静默吞掉。v1 不提供热重载，配置在插件启用时读取一次。</p>
 */
public final class EvalConfig {

    private enum Type {
        TEXT, INTEGER, BOOL
    }

    /** 单个配置键的定义：键名、默认值、类型与数值区间（非数值键忽略区间）。 */
    private static final class KeySpec {
        private final String key;
        private final String defaultValue;
        private final Type type;
        private final int min;
        private final int max;

        private KeySpec(String key, String defaultValue, Type type) {
            this(key, defaultValue, type, 0, 0);
        }

        private KeySpec(String key, String defaultValue, Type type, int min, int max) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.type = type;
            this.min = min;
            this.max = max;
        }
    }

    private static final Map<String, KeySpec> SPECS;

    static {
        Map<String, KeySpec> specs = new LinkedHashMap<String, KeySpec>();
        add(specs, new KeySpec("evalCallBudget", "100", Type.INTEGER, 1, 100));
        add(specs, new KeySpec("evalRunDeadlineMinutes", "30", Type.INTEGER, 5, 120));
        add(specs, new KeySpec("evalKeepRuns", "30", Type.INTEGER, 5, 200));
        add(specs, new KeySpec("evalDefaultTier", "smoke", Type.TEXT));
        add(specs, new KeySpec("evalTimeoutSecond", "60", Type.INTEGER, 20, 300));
        add(specs, new KeySpec("evalJudgeTimeoutSecond", "60", Type.INTEGER, 20, 120));
        add(specs, new KeySpec("evalGenerateRetries", "1", Type.INTEGER, 0, 1));
        add(specs, new KeySpec("evalCachedResamples", "3", Type.INTEGER, 1, 3));
        add(specs, new KeySpec("evalFailStreakAbort", "3", Type.INTEGER, 2, 5));
        add(specs, new KeySpec("evalFailBackoffSeconds", "30", Type.INTEGER, 5, 120));
        add(specs, new KeySpec("evalBankFile", "", Type.TEXT));
        add(specs, new KeySpec("evalAllowPersonaMismatch", "false", Type.BOOL));
        add(specs, new KeySpec("evalProfile", "", Type.TEXT));
        add(specs, new KeySpec("evalJudgeProfile", "", Type.TEXT));

        SPECS = Collections.unmodifiableMap(specs);
    }

    private final Map<String, String> values;
    private final List<String> warnings;

    private EvalConfig(Map<String, String> values, List<String> warnings) {
        this.values = Collections.unmodifiableMap(values);
        this.warnings = Collections.unmodifiableList(warnings);
    }

    /**
     * 从框架配置读取全部键；{@code config} 为 {@code null} 时等价于空配置，全部取默认值。
     */
    public static EvalConfig load(PluginConfig config) {
        if (config == null) {
            return fromValues(Collections.<String, String>emptyMap());
        }
        Map<String, String> raw = new LinkedHashMap<String, String>();
        for (String key : config.keys()) {
            raw.put(key, config.getString(key));
        }
        return fromValues(raw);
    }

    /**
     * 从原始键值构建配置快照，便于在无框架配置对象时自检。
     */
    public static EvalConfig fromValues(Map<String, String> rawValues) {
        List<String> warnings = new ArrayList<String>();
        Map<String, String> normalized = new LinkedHashMap<String, String>();

        if (rawValues != null) {
            for (Map.Entry<String, String> entry : rawValues.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().trim();
                if (key.isEmpty()) {
                    continue;
                }
                KeySpec spec = SPECS.get(key);
                if (spec == null) {
                    warnings.add("未知配置项：" + key + "，已忽略");
                    continue;
                }
                String value = entry.getValue() == null ? "" : entry.getValue().trim();
                if (!isValid(spec, value)) {
                    warnings.add("配置项 " + key + " 的值 " + value + " 非法，回退默认值 " + spec.defaultValue);
                    value = spec.defaultValue;
                }
                normalized.put(key, value);
            }
        }
        for (KeySpec spec : SPECS.values()) {
            if (!normalized.containsKey(spec.key)) {
                normalized.put(spec.key, spec.defaultValue);
            }
        }

        for (KeySpec spec : SPECS.values()) {
            if (spec.type != Type.INTEGER) {
                continue;
            }
            int value = Integer.parseInt(normalized.get(spec.key));
            int clamped = Math.max(spec.min, Math.min(spec.max, value));
            if (clamped != value) {
                warnings.add("配置项 " + spec.key + "=" + value + " 超出范围 " + spec.min + ".." + spec.max
                        + "，已按 " + clamped + " 处理");
                normalized.put(spec.key, String.valueOf(clamped));
            }
        }

        String tier = normalized.get("evalDefaultTier");
        if (!"smoke".equals(tier) && !"full".equals(tier)) {
            warnings.add("配置项 evalDefaultTier 的值 " + tier + " 非法，可选 smoke 或 full，回退默认值 smoke");
            normalized.put("evalDefaultTier", "smoke");
        }

        return new EvalConfig(normalized, warnings);
    }

    private static void add(Map<String, KeySpec> specs, KeySpec spec) {
        specs.put(spec.key, spec);
    }

    private static boolean isValid(KeySpec spec, String value) {
        if (value == null) {
            return false;
        }
        if (spec.type == Type.TEXT) {
            return true;
        }
        if (spec.type == Type.BOOL) {
            return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
        }
        if (value.isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 全部配置键的默认值，顺序与 config.yml 模板一致。 */
    public static Map<String, String> defaults() {
        Map<String, String> defaults = new LinkedHashMap<String, String>();
        for (KeySpec spec : SPECS.values()) {
            defaults.put(spec.key, spec.defaultValue);
        }
        return Collections.unmodifiableMap(defaults);
    }

    /** 全部配置键（只读）。 */
    public static Set<String> keys() {
        return SPECS.keySet();
    }

    /** 本次加载产生的告警；无告警时为空列表。 */
    public List<String> warnings() {
        return warnings;
    }

    /** 钳制后的原始字符串值；未知键抛 {@link IllegalArgumentException}。 */
    public String raw(String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("未知配置项：" + key);
        }
        return value;
    }

    public int callBudget() {
        return intValue("evalCallBudget");
    }

    public int runDeadlineMinutes() {
        return intValue("evalRunDeadlineMinutes");
    }

    public int keepRuns() {
        return intValue("evalKeepRuns");
    }

    public String defaultTier() {
        return raw("evalDefaultTier");
    }

    public int timeoutSecond() {
        return intValue("evalTimeoutSecond");
    }

    public int judgeTimeoutSecond() {
        return intValue("evalJudgeTimeoutSecond");
    }

    public int generateRetries() {
        return intValue("evalGenerateRetries");
    }

    public int cachedResamples() {
        return intValue("evalCachedResamples");
    }

    public int failStreakAbort() {
        return intValue("evalFailStreakAbort");
    }

    public int failBackoffSeconds() {
        return intValue("evalFailBackoffSeconds");
    }

    public String bankFile() {
        return raw("evalBankFile");
    }

    public boolean allowPersonaMismatch() {
        return Boolean.parseBoolean(raw("evalAllowPersonaMismatch"));
    }

    public String profile() {
        return raw("evalProfile");
    }

    public String judgeProfile() {
        return raw("evalJudgeProfile");
    }

    private int intValue(String key) {
        return Integer.parseInt(raw(key));
    }
}
