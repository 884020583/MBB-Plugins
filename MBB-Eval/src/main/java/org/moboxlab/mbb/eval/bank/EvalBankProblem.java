package org.moboxlab.mbb.eval.bank;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 题库校验问题（规格 §5.7.1：{@code code + JSON path + 中文消息}）。
 *
 * <p>{@code code} 只能取 §5.7.3 冻结的 15 个 {@code E_BANK_*} 之一；{@code path} 是 JSON 路径，
 * 文件级问题为空串。排序按 cases 下标数值升序、同一用例内按字段顺序，见 {@link #sortKey()}。</p>
 */
public final class EvalBankProblem implements Comparable<EvalBankProblem> {

    /** 文件不可读或缺失。 */
    public static final String READ = "E_BANK_READ";
    /** JSON 语法错误或编码错误。 */
    public static final String JSON = "E_BANK_JSON";
    /** 文件超过 512 KB。 */
    public static final String SIZE = "E_BANK_SIZE";
    /** schemaVersion 缺失、非整数或不为 1。 */
    public static final String SCHEMA_VERSION = "E_BANK_SCHEMA_VERSION";
    /** version 不可解析或与文件名不一致。 */
    public static final String VERSION = "E_BANK_VERSION";
    /** 必填字段缺失、类型不符或场景字段越界。 */
    public static final String FIELD = "E_BANK_FIELD";
    /** 用例 id 格式、前缀或唯一性不合法。 */
    public static final String ID = "E_BANK_ID";
    /** dimension 不在五维或不在 rubric 维度表内。 */
    public static final String DIMENSION = "E_BANK_DIMENSION";
    /** 用例规模或五维、smoke 配比不满足。 */
    public static final String COVERAGE = "E_BANK_COVERAGE";
    /** style 挂载项不在 rubric 风格目录内或覆写形状不合法。 */
    public static final String STYLE_MOUNT = "E_BANK_STYLE_MOUNT";
    /** knowledge 判分定义不合法。 */
    public static final String KNOWLEDGE = "E_BANK_KNOWLEDGE";
    /** memory.evidenceTurns 结构不合法。 */
    public static final String MEMORY = "E_BANK_MEMORY";
    /** tier 取值不合法。 */
    public static final String TIER = "E_BANK_TIER";
    /** persona 声明不合法。 */
    public static final String PERSONA = "E_BANK_PERSONA";
    /** 声明的 hash 与实际计算值不一致。 */
    public static final String HASH = "E_BANK_HASH";

    /** §5.7.3 冻结的封闭错误码集合。 */
    public static final List<String> CODES = Collections.unmodifiableList(Arrays.asList(
            READ, JSON, SIZE, SCHEMA_VERSION, VERSION, FIELD, ID, DIMENSION,
            COVERAGE, STYLE_MOUNT, KNOWLEDGE, MEMORY, TIER, PERSONA, HASH));

    /** 同一用例内的字段顺序（§5.3 / §5.4）；未列出的字段排在最后。 */
    private static final Map<String, Integer> RANKS = new HashMap<String, Integer>();

    static {
        putRank("schemaVersion", 1);
        putRank("version", 2);
        putRank("persona", 3);
        putRank("meta", 4);
        putRank("cases", 5);
        putRank("hash", 6);

        putRank("id", 1);
        putRank("dimension", 2);
        putRank("tier", 3);
        putRank("prompt", 4);
        putRank("focus", 5);
        putRank("reference", 6);
        putRank("source", 7);
        putRank("sourceNote", 8);
        putRank("history", 9);
        putRank("addressed", 10);
        putRank("relationship", 11);
        putRank("style", 12);
        putRank("knowledge", 13);
        putRank("memory", 14);

        putRank("file", 1);
        putRank("summary", 2);
        putRank("personaHash", 3);
        putRank("title", 1);
        putRank("description", 2);
        putRank("hard", 1);
        putRank("soft", 2);
        putRank("mode", 1);
        putRank("choices", 2);
        putRank("expected", 3);
        putRank("negative", 4);
        putRank("unitAliases", 5);
        putRank("label", 1);
        putRank("aliases", 2);
        putRank("text", 1);
        putRank("correct", 2);
        putRank("speaker", 1);
        putRank("userName", 2);
        putRank("evidenceTurns", 1);
        putRank("min", 1);
        putRank("max", 2);
    }

    private final String code;
    private final String path;
    private final String message;

    public EvalBankProblem(String code, String path, String message) {
        if (!CODES.contains(code)) {
            throw new IllegalArgumentException("未知的题库错误码：" + code);
        }
        this.code = code;
        this.path = path == null ? "" : path;
        this.message = message == null ? "" : message;
    }

    public String code() {
        return code;
    }

    public String path() {
        return path;
    }

    public String message() {
        return message;
    }

    /** 稳定排序键：文件级 &lt; 顶层字段 &lt; cases 聚合 &lt; 用例级（下标数值升序，逐层字段顺序）。 */
    public String sortKey() {
        if (path.isEmpty()) {
            return "0";
        }
        if (path.startsWith("cases")) {
            if ("cases".equals(path)) {
                return "1" + token("cases");
            }
            int open = path.indexOf('[');
            int close = open < 0 ? -1 : path.indexOf(']', open);
            if (open == 5 && close > open) {
                String digits = path.substring(open + 1, close);
                if (isDigits(digits) && digits.length() <= 9) {
                    StringBuilder key = new StringBuilder("2");
                    key.append(pad(Integer.parseInt(digits), 6));
                    appendSegments(key, path.substring(close + 1));
                    return key.toString();
                }
            }
        }
        StringBuilder key = new StringBuilder("1");
        appendSegments(key, path);
        return key.toString();
    }

    @Override
    public int compareTo(EvalBankProblem other) {
        int result = sortKey().compareTo(other.sortKey());
        if (result != 0) {
            return result;
        }
        result = code.compareTo(other.code);
        if (result != 0) {
            return result;
        }
        return message.compareTo(other.message);
    }

    /** 单行展示，形如 {@code E_BANK_ID（cases[3].id）：消息}。 */
    public String display() {
        if (path.isEmpty()) {
            return code + "：" + message;
        }
        return code + "（" + path + "）：" + message;
    }

    @Override
    public String toString() {
        return display();
    }

    private static void putRank(String name, int rank) {
        RANKS.put(name, rank);
    }

    private static String token(String name) {
        Integer rank = RANKS.get(name);
        return pad(rank == null ? 999 : rank.intValue(), 3) + ":" + name;
    }

    private static void appendSegments(StringBuilder key, String rest) {
        int index = 0;
        while (index < rest.length()) {
            char current = rest.charAt(index);
            if (current == '.') {
                index++;
                continue;
            }
            if (current == '[') {
                int close = rest.indexOf(']', index);
                if (close < 0) {
                    key.append("/?").append(rest.substring(index));
                    return;
                }
                String digits = rest.substring(index + 1, close);
                if (isDigits(digits) && digits.length() <= 9) {
                    key.append("/#").append(pad(Integer.parseInt(digits), 6));
                } else {
                    key.append("/#").append(digits);
                }
                index = close + 1;
                continue;
            }
            int end = index;
            while (end < rest.length() && rest.charAt(end) != '.' && rest.charAt(end) != '[') {
                end++;
            }
            key.append('/').append(token(rest.substring(index, end)));
            index = end;
        }
    }

    private static String pad(int value, int width) {
        StringBuilder text = new StringBuilder(String.valueOf(value));
        while (text.length() < width) {
            text.insert(0, '0');
        }
        return text.toString();
    }

    private static boolean isDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
