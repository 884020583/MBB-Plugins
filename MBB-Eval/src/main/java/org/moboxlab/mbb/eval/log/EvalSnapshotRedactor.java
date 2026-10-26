package org.moboxlab.mbb.eval.log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.moboxlab.moboxbot.API.PluginLogger;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * config_snapshot_json 脱敏器（规格 §4.2、§12.4、§16.10 I-3）。
 *
 * <p>只保留白名单内的评测配置与九键快照；任何含 token / key / secret / password
 * 等敏感词元的键一律剔除并 WARN（不写值）。未知键若是对象或对象数组，会递归过滤；
 * 未知的标量与标量数组直接丢弃。</p>
 */
public final class EvalSnapshotRedactor {

    /** 九键快照 + 银行/人格定位键 + 评测配置键；比较时忽略大小写与下划线。 */
    private static final Set<String> ALLOWED_KEYS = new HashSet<String>(Arrays.asList(
            normalize("bank_hash"),
            normalize("rubric_version"),
            normalize("persona_hash"),
            normalize("config_fingerprint"),
            normalize("eval_profile"),
            normalize("judge_profile"),
            normalize("judge_model"),
            normalize("generation_model"),
            normalize("eval_api_version"),
            normalize("bank_file"),
            normalize("bank_version"),
            normalize("persona_id"),
            normalize("persona_version"),
            normalize("evalCallBudget"),
            normalize("evalRunDeadlineMinutes"),
            normalize("evalKeepRuns"),
            normalize("evalDefaultTier"),
            normalize("evalTimeoutSecond"),
            normalize("evalJudgeTimeoutSecond"),
            normalize("evalGenerateRetries"),
            normalize("evalCachedResamples"),
            normalize("evalFailStreakAbort"),
            normalize("evalFailBackoffSeconds"),
            normalize("evalBankFile"),
            normalize("evalAllowPersonaMismatch"),
            normalize("evalProfile"),
            normalize("evalJudgeProfile"),
            normalize("aiProfile"),
            normalize("judgeProfile"),
            normalize("generationProfile"),
            normalize("apiProfile"),
            normalize("evalApiEnable"),
            normalize("evalApi"),
            normalize("maxTokens"),
            normalize("temperature"),
            normalize("retryCount"),
            normalize("timeout"),
            normalize("cacheSecond"),
            normalize("sampleK"),
            normalize("sample_k")));

    /** 命中即视为敏感的词元；复数 tokens 也视为敏感，仅 maxTokens 作为计数项单独放行。 */
    private static final Set<String> SENSITIVE_WORDS = new HashSet<String>(Arrays.asList(
            "token", "secret", "password", "passwd", "key", "credential", "credentials"));

    /** 全小写紧凑写法（无分隔符）下的敏感片段。 */
    private static final String[] SENSITIVE_FRAGMENTS = {
            "apikey", "secretkey", "privatekey", "publickey", "accesskey",
            "password", "passwd", "credential", "secret"
    };

    private final PluginLogger logger;
    private final Set<String> warnedKeys = new LinkedHashSet<String>();
    private JSONObject lastRedacted;

    public EvalSnapshotRedactor(PluginLogger logger) {
        this.logger = logger;
    }

    /**
     * 脱敏配置或九键快照。
     *
     * @param source 原始配置；null 视为空配置
     * @return 只含白名单键的新对象，调用方修改返回值不影响入参
     */
    public JSONObject redact(JSONObject source) {
        warnedKeys.clear();
        JSONObject result = new JSONObject(true);
        lastRedacted = result;
        if (source != null) {
            copyInto(source, result, "");
        }
        return result;
    }

    /** 最近一次脱敏结果的紧凑 JSON；未脱敏时返回空对象。 */
    public String toJson() {
        return lastRedacted == null ? "{}" : lastRedacted.toJSONString();
    }

    private void copyInto(JSONObject source, JSONObject target, String path) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            String childPath = path.isEmpty() ? key : path + "." + key;
            if (isSensitiveKey(key)) {
                warnSensitive(key, childPath);
                continue;
            }
            Object value = entry.getValue();
            boolean allowed = isAllowedKey(key);
            if (value instanceof JSONObject) {
                JSONObject nested = new JSONObject(true);
                copyInto((JSONObject) value, nested, childPath);
                if (!nested.isEmpty()) {
                    target.put(key, nested);
                }
            } else if (value instanceof JSONArray) {
                JSONArray nested = redactArray((JSONArray) value, childPath, allowed);
                if (!nested.isEmpty()) {
                    target.put(key, nested);
                }
            } else if (allowed) {
                target.put(key, value);
            }
        }
    }

    private JSONArray redactArray(JSONArray source, String path, boolean allowed) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.size(); i++) {
            Object element = source.get(i);
            String childPath = path + "[" + i + "]";
            if (element instanceof JSONObject) {
                JSONObject nested = new JSONObject(true);
                copyInto((JSONObject) element, nested, childPath);
                if (!nested.isEmpty()) {
                    result.add(nested);
                }
            } else if (element instanceof JSONArray) {
                JSONArray nested = redactArray((JSONArray) element, childPath, allowed);
                if (!nested.isEmpty()) {
                    result.add(nested);
                }
            } else if (allowed) {
                result.add(element);
            }
        }
        return result;
    }

    private void warnSensitive(String key, String path) {
        if (!warnedKeys.add(normalize(key))) {
            return;
        }
        EvalLog.warn(logger, "快照剔除敏感键 path=" + path);
    }

    private static boolean isAllowedKey(String key) {
        return key != null && ALLOWED_KEYS.contains(normalize(key));
    }

    static boolean isSensitiveKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        if (hasSensitiveWord(key)) {
            return true;
        }
        String compact = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String fragment : SENSITIVE_FRAGMENTS) {
            if (compact.contains(fragment)) {
                return true;
            }
        }
        // maxTokens 是 I4 明确保留的计数项，不是凭据；其余含 token 的键（含复数）一律剔除。
        if ("maxtokens".equals(compact)) {
            return false;
        }
        return compact.contains("token");
    }

    private static boolean hasSensitiveWord(String key) {
        StringBuilder word = new StringBuilder();
        char previous = 0;
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            boolean boundary = !Character.isLetterOrDigit(ch)
                    || (Character.isUpperCase(ch)
                    && (Character.isLowerCase(previous) || Character.isDigit(previous)));
            if (boundary && word.length() > 0) {
                if (SENSITIVE_WORDS.contains(word.toString())) {
                    return true;
                }
                word.setLength(0);
            }
            if (Character.isLetterOrDigit(ch)) {
                word.append(Character.toLowerCase(ch));
            } else {
                previous = 0;
                continue;
            }
            previous = ch;
        }
        return word.length() > 0 && SENSITIVE_WORDS.contains(word.toString());
    }

    private static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}
