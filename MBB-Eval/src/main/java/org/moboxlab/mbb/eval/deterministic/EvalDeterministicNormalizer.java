package org.moboxlab.mbb.eval.deterministic;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 确定性判分的文本归一化器（规格 §6.4.3）。
 *
 * <p>第 1–6 步由 rubric {@code deterministic.knowledge.normalize} 数组驱动，声明哪几步生效；
 * 执行顺序固定为规格表格的顺序，不随数组顺序改变。第 7 步（{@code unitAliases} 显式等价）
 * 与第 8 步（子串包含）是常量规则，体现在 {@link #matches(String, String, JSONArray)} 里。</p>
 *
 * <p>本类只做文本变换与匹配，不读 rubric 文件；rubric 加载与校验属 D-2。</p>
 */
public final class EvalDeterministicNormalizer {

    /** 规格 §6.4.3 的 6 个归一化步骤 id，顺序即执行顺序。 */
    public static final List<String> STEP_IDS = Collections.unmodifiableList(Arrays.asList(
            "fullwidth", "whitespace", "punctuation", "lowercase", "cn-number", "measure-word"));

    /** 规格 §6.4.3 第 3 步删除的中英文标点集合，逐字取自规格。 */
    private static final String PUNCTUATIONS = "，。！？；：、…—～\"'“”‘’（）()【】[]《》〈〉·,.;:!?";

    /** 第 6 步：数字后紧跟量词时删除量词（个/名/位/只/条/把/支/张/枚/件/次/岁）。 */
    private static final Pattern MEASURE_WORD = Pattern.compile("([0-9]+)[个名位只条把支张枚件次岁]+");

    /** 中文数字字符集（零〇一二两三四五六七八九）。 */
    private static final String CN_DIGITS = "零〇一二两三四五六七八九";

    /** 中文数字单位字符集（十百千）。 */
    private static final String CN_UNITS = "十百千";

    /** unitAliases 展开的变体上限，防止异常 rubric 造成组合爆炸。 */
    private static final int MAX_UNIT_VARIANTS = 64;

    private final boolean[] enabledSteps;

    /**
     * 按 rubric 的 normalize 数组创建归一化器。
     *
     * @param steps 第 1–6 步的 id 数组；未知 id 直接拒绝，不静默忽略
     */
    public EvalDeterministicNormalizer(JSONArray steps) {
        if (steps == null) {
            throw new IllegalArgumentException("归一化步骤数组缺失：deterministic.knowledge.normalize");
        }
        this.enabledSteps = new boolean[STEP_IDS.size()];
        for (int i = 0; i < steps.size(); i++) {
            String step = steps.getString(i);
            int index = STEP_IDS.indexOf(step);
            if (index < 0) {
                throw new IllegalArgumentException("未知的归一化步骤：" + step);
            }
            this.enabledSteps[index] = true;
        }
    }

    /**
     * 从 rubric 的 {@code deterministic.knowledge} 节点创建归一化器。
     *
     * @param deterministicKnowledge rubric {@code deterministic.knowledge} 节点
     */
    public static EvalDeterministicNormalizer fromRubric(JSONObject deterministicKnowledge) {
        if (deterministicKnowledge == null) {
            throw new IllegalArgumentException("rubric 缺少 deterministic.knowledge 节点");
        }
        JSONArray steps = deterministicKnowledge.getJSONArray("normalize");
        if (steps == null) {
            throw new IllegalArgumentException("rubric 缺少 deterministic.knowledge.normalize");
        }
        return new EvalDeterministicNormalizer(steps);
    }

    /**
     * 按声明生效的步骤依次归一化。
     *
     * @param raw 原文，允许 null（按空串处理）
     * @return 归一化文本
     */
    public String normalize(String raw) {
        String value = raw == null ? "" : raw;
        for (int i = 0; i < STEP_IDS.size(); i++) {
            if (!enabledSteps[i]) {
                continue;
            }
            switch (i) {
                case 0:
                    value = fullwidthToHalfwidth(value);
                    break;
                case 1:
                    value = removeWhitespace(value);
                    break;
                case 2:
                    value = removePunctuation(value);
                    break;
                case 3:
                    value = value.toLowerCase(Locale.ROOT);
                    break;
                case 4:
                    value = convertChineseNumbers(value);
                    break;
                case 5:
                    value = MEASURE_WORD.matcher(value).replaceAll("$1");
                    break;
                default:
                    break;
            }
        }
        return value;
    }

    /** 不带单位等价的匹配（第 8 步：子串包含）。 */
    public boolean matches(String text, String needle) {
        return matches(text, needle, null);
    }

    /**
     * 第 7–8 步：先声明单位等价，再做子串包含。
     *
     * @param text         被匹配的原文（如模型回复）
     * @param needle       期望文本（如 alias）
     * @param unitAliases  等价单位对，形如 {@code [["分钟","分"]]}；也接受扁平 {@code ["分钟","分"]}
     */
    public boolean matches(String text, String needle, JSONArray unitAliases) {
        String normalizedText = normalize(text);
        String normalizedNeedle = normalize(needle);
        if (normalizedNeedle.isEmpty()) {
            return false;
        }
        List<String[]> pairs = parseUnitAliases(unitAliases);
        List<String> textVariants = expand(normalizedText, pairs);
        List<String> needleVariants = expand(normalizedNeedle, pairs);
        for (String haystack : textVariants) {
            for (String candidate : needleVariants) {
                if (!candidate.isEmpty() && haystack.contains(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 第 1 步：全角 ASCII 与全角空格转半角（规格允许用 NFKC 实现）。 */
    private static String fullwidthToHalfwidth(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC);
    }

    /** 第 2 步：删除全部空白（含全角空格与各类 Unicode 空白）。 */
    private static String removeWhitespace(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                result.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        return result.toString();
    }

    /** 第 3 步：删除规格列出的中英文标点。 */
    private static String removePunctuation(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (PUNCTUATIONS.indexOf(ch) < 0) {
                result.append(ch);
            }
        }
        return result.toString();
    }

    /**
     * 第 5 步：中文数字转阿拉伯数字，支持 0–9999。
     *
     * <p>含十/百/千单位的按单位组合解析（一百二十 → 120）；不含单位的按位拼接
     * （二零二六 → 2026）。无法解析的片段原样保留。</p>
     */
    private static String convertChineseNumbers(String value) {
        StringBuilder result = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char ch = value.charAt(i);
            if (!isChineseNumberChar(ch)) {
                result.append(ch);
                i++;
                continue;
            }
            int end = i;
            while (end < value.length() && isChineseNumberChar(value.charAt(end))) {
                end++;
            }
            String run = value.substring(i, end);
            result.append(convertChineseNumberRun(run));
            i = end;
        }
        return result.toString();
    }

    private static boolean isChineseNumberChar(char ch) {
        return CN_DIGITS.indexOf(ch) >= 0 || CN_UNITS.indexOf(ch) >= 0;
    }

    private static String convertChineseNumberRun(String run) {
        boolean hasUnit = false;
        for (int i = 0; i < run.length(); i++) {
            if (CN_UNITS.indexOf(run.charAt(i)) >= 0) {
                hasUnit = true;
                break;
            }
        }
        if (!hasUnit) {
            return convertDigitByDigit(run);
        }
        int total = 0;
        int pending = 0;
        for (int i = 0; i < run.length(); i++) {
            char ch = run.charAt(i);
            int unit = unitValue(ch);
            if (unit > 0) {
                if (pending == 0) {
                    // 「十二」的十、「一百」的百：没有前置数字时按 1 处理
                    pending = 1;
                }
                total += pending * unit;
                pending = 0;
            } else {
                pending = digitValue(ch);
            }
        }
        return Integer.toString(total + pending);
    }

    private static String convertDigitByDigit(String run) {
        StringBuilder digits = new StringBuilder(run.length());
        for (int i = 0; i < run.length(); i++) {
            digits.append(digitValue(run.charAt(i)));
        }
        int begin = 0;
        while (begin < digits.length() - 1 && digits.charAt(begin) == '0') {
            begin++;
        }
        return digits.substring(begin);
    }

    private static int digitValue(char ch) {
        switch (ch) {
            case '零':
            case '〇':
                return 0;
            case '一':
                return 1;
            case '二':
            case '两':
                return 2;
            case '三':
                return 3;
            case '四':
                return 4;
            case '五':
                return 5;
            case '六':
                return 6;
            case '七':
                return 7;
            case '八':
                return 8;
            case '九':
                return 9;
            default:
                return 0;
        }
    }

    private static int unitValue(char ch) {
        switch (ch) {
            case '十':
                return 10;
            case '百':
                return 100;
            case '千':
                return 1000;
            default:
                return 0;
        }
    }

    /** 解析 unitAliases；支持数组套数组，也支持扁平的一对字符串。 */
    private List<String[]> parseUnitAliases(JSONArray unitAliases) {
        List<String[]> pairs = new ArrayList<String[]>();
        if (unitAliases == null || unitAliases.isEmpty()) {
            return pairs;
        }
        List<String> flat = new ArrayList<String>();
        for (int i = 0; i < unitAliases.size(); i++) {
            Object item = unitAliases.get(i);
            if (item instanceof JSONArray) {
                JSONArray pair = (JSONArray) item;
                if (pair.size() >= 2 && pair.getString(0) != null && pair.getString(1) != null) {
                    pairs.add(new String[] {pair.getString(0), pair.getString(1)});
                }
            } else if (item instanceof String) {
                flat.add((String) item);
            }
        }
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            pairs.add(new String[] {flat.get(i), flat.get(i + 1)});
        }
        List<String[]> normalized = new ArrayList<String[]>(pairs.size());
        for (String[] pair : pairs) {
            String left = normalize(pair[0]);
            String right = normalize(pair[1]);
            if (!left.isEmpty() && !right.isEmpty()) {
                normalized.add(new String[] {left, right});
            }
        }
        return normalized;
    }

    /** 把文本按等价单位对展开为若干变体，供子串匹配。 */
    private static List<String> expand(String value, List<String[]> pairs) {
        Set<String> seen = new LinkedHashSet<String>();
        seen.add(value);
        List<String> pending = new ArrayList<String>(seen);
        for (int index = 0; index < pending.size(); index++) {
            String current = pending.get(index);
            for (String[] pair : pairs) {
                addReplacedVariant(seen, pending, current, pair[0], pair[1]);
                addReplacedVariant(seen, pending, current, pair[1], pair[0]);
                if (pending.size() >= MAX_UNIT_VARIANTS) {
                    return new ArrayList<String>(pending);
                }
            }
        }
        return new ArrayList<String>(pending);
    }

    private static void addReplacedVariant(Set<String> seen, List<String> pending,
                                           String current, String from, String to) {
        if (from.isEmpty() || from.equals(to) || !current.contains(from)) {
            return;
        }
        String variant = current.replace(from, to);
        if (seen.add(variant) && pending.size() < MAX_UNIT_VARIANTS) {
            pending.add(variant);
        }
    }
}
