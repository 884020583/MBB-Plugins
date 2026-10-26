package org.moboxlab.mbb.eval.deterministic;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 风格维判分器（规格 §6.5）。
 *
 * <p>从 rubric 的 deterministic.style 节点读取硬项 / 软项 impl 与参数，
 * 按 defaultMount 继承、按题目 style 覆写后逐项判定。硬项违规题分记 0，
 * 且不再计算软项；软项题分为通过数 / 挂载数。emoji 只观测不计分。</p>
 */
public final class EvalStyleScorer {

    private final JSONObject hardItems;
    private final JSONObject softItems;
    private final List<String> defaultHard;
    private final List<String> defaultSoft;

    public EvalStyleScorer(JSONObject deterministicStyle) {
        if (deterministicStyle == null) {
            throw new IllegalArgumentException("deterministic.style 不能为空");
        }
        this.hardItems = requireObject(deterministicStyle, "hard");
        this.softItems = requireObject(deterministicStyle, "soft");
        JSONObject mount = requireObject(deterministicStyle, "defaultMount");
        this.defaultHard = ids(mount.getJSONArray("hard"), "defaultMount.hard");
        this.defaultSoft = ids(mount.getJSONArray("soft"), "defaultMount.soft");
    }

    /**
     * 判一道风格题。
     *
     * @param caseStyle 题目 style 覆写，可为 null；hard 整组替换，soft 值 true 启用 / false 停用 / 对象覆写参数
     * @param content   待判回复原文
     */
    public EvalStyleResult score(JSONObject caseStyle, String content) {
        String text = content == null ? "" : content;
        Mount mount = resolveMount(caseStyle);

        List<String> hardFails = new ArrayList<String>();
        for (String id : mount.hard) {
            JSONObject item = hardItems.getJSONObject(id);
            if (item == null) {
                throw new IllegalArgumentException("rubric 缺少硬项：" + id);
            }
            if (violatesHard(id, item, text)) {
                hardFails.add(id);
            }
        }

        int emojiCount = countEmoji(text);
        if (!hardFails.isEmpty()) {
            return new EvalStyleResult(hardFails, new ArrayList<EvalStyleResult.Soft>(), emojiCount, 0.0);
        }

        List<EvalStyleResult.Soft> soft = new ArrayList<EvalStyleResult.Soft>();
        int passed = 0;
        for (Map.Entry<String, JSONObject> entry : mount.soft.entrySet()) {
            String id = entry.getKey();
            JSONObject item = softItems.getJSONObject(id);
            if (item == null) {
                throw new IllegalArgumentException("rubric 缺少软项：" + id);
            }
            EvalStyleResult.Soft one = judgeSoft(id, item, entry.getValue(), text);
            soft.add(one);
            if (one.passed()) {
                passed++;
            }
        }
        double score = soft.isEmpty() ? 1.0 : (double) passed / (double) soft.size();
        return new EvalStyleResult(hardFails, soft, emojiCount, score);
    }

    private Mount resolveMount(JSONObject caseStyle) {
        Mount mount = new Mount();
        for (String id : defaultHard) {
            mount.hard.add(id);
        }
        for (String id : defaultSoft) {
            mount.soft.put(id, baseParams(id));
        }
        if (caseStyle == null) {
            return mount;
        }
        JSONArray hardOverride = caseStyle.getJSONArray("hard");
        if (hardOverride != null) {
            mount.hard = ids(hardOverride, "style.hard");
        }
        JSONObject softOverride = caseStyle.getJSONObject("soft");
        if (softOverride != null) {
            for (Map.Entry<String, Object> entry : softOverride.entrySet()) {
                String id = entry.getKey();
                Object value = entry.getValue();
                JSONObject params = softItems.getJSONObject(id) == null ? null : baseParams(id);
                if (params == null) {
                    throw new IllegalArgumentException("case 覆写了 rubric 未定义的软项：" + id);
                }
                if (value instanceof Boolean) {
                    if (Boolean.TRUE.equals(value)) {
                        if (!mount.soft.containsKey(id)) {
                            mount.soft.put(id, params);
                        }
                    } else {
                        mount.soft.remove(id);
                    }
                } else if (value instanceof JSONObject) {
                    JSONObject merged = new JSONObject(true);
                    merged.putAll(params);
                    merged.putAll((JSONObject) value);
                    mount.soft.put(id, merged);
                } else {
                    throw new IllegalArgumentException("style.soft 的值必须是布尔或对象：" + id);
                }
            }
        }
        return mount;
    }

    private JSONObject baseParams(String id) {
        JSONObject item = softItems.getJSONObject(id);
        if (item == null) {
            throw new IllegalArgumentException("rubric 缺少软项：" + id);
        }
        JSONObject params = item.getJSONObject("params");
        return params == null ? new JSONObject(true) : new JSONObject(params);
    }

    /** 硬项是否违规；impl 由 rubric 决定，未知 impl 直接拒绝。 */
    private boolean violatesHard(String id, JSONObject item, String text) {
        String impl = item.getString("impl");
        if (impl == null) {
            throw new IllegalArgumentException("硬项缺少 impl：" + id);
        }
        JSONObject params = item.getJSONObject("params");
        if (params == null) {
            params = new JSONObject();
        }
        if ("markdown-residue".equals(impl)) {
            return hasMarkdownResidue(text);
        }
        if ("substring-any".equals(impl)) {
            return containsAny(text, params.getJSONArray("needles"));
        }
        if ("substring-or-regex-any".equals(impl)) {
            if (containsAny(text, params.getJSONArray("needles"))) {
                return true;
            }
            return matchesAnyRegex(text, params.getJSONArray("patterns"),
                    params.containsKey("dotall") && params.getBooleanValue("dotall"));
        }
        if ("paired-quote".equals(impl)) {
            String open = params.getString("open");
            String close = params.getString("close");
            return open != null && close != null && text.contains(open) && text.contains(close);
        }
        throw new IllegalArgumentException("未知的硬项 impl：" + impl);
    }

    /** §6.5.1：强调、代码块、行首标题或两行以上行首列表标记。 */
    private boolean hasMarkdownResidue(String text) {
        if (text.contains("**") || text.contains("```")) {
            return true;
        }
        String[] lines = text.split("\n", -1);
        int listLines = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                return true;
            }
            if (isListLine(trimmed)) {
                listLines++;
            }
        }
        return listLines >= 2;
    }

    private boolean isListLine(String line) {
        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
            return true;
        }
        int i = 0;
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            i++;
        }
        if (i == 0 || i >= line.length()) {
            return false;
        }
        char sep = line.charAt(i);
        return (sep == '.' || sep == ')') && i + 1 < line.length()
                && Character.isWhitespace(line.charAt(i + 1));
    }

    private boolean containsAny(String text, JSONArray needles) {
        if (needles == null) {
            return false;
        }
        for (int i = 0; i < needles.size(); i++) {
            String needle = needles.getString(i);
            if (needle != null && !needle.isEmpty() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyRegex(String text, JSONArray patterns, boolean dotall) {
        if (patterns == null) {
            return false;
        }
        int flags = dotall ? Pattern.DOTALL : 0;
        for (int i = 0; i < patterns.size(); i++) {
            String pattern = patterns.getString(i);
            if (pattern != null && Pattern.compile(pattern, flags).matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /** 软项判定；params 已合并题目覆写。 */
    private EvalStyleResult.Soft judgeSoft(String id, JSONObject item, JSONObject params, String text) {
        String impl = item.getString("impl");
        if (impl == null) {
            throw new IllegalArgumentException("软项缺少 impl：" + id);
        }
        if ("char-range".equals(impl)) {
            int min = params.containsKey("min") ? params.getIntValue("min") : 0;
            int max = params.containsKey("max") ? params.getIntValue("max") : Integer.MAX_VALUE;
            int length = text.codePointCount(0, text.length());
            boolean passed = length >= min && length <= max;
            return new EvalStyleResult.Soft(id, passed,
                    "长度 " + length + " 码点，要求 [" + min + ", " + max + "]");
        }
        if ("alias-mention".equals(impl)) {
            JSONArray aliases = params.getJSONArray("aliases");
            String matched = null;
            if (aliases != null) {
                for (int i = 0; i < aliases.size(); i++) {
                    String alias = aliases.getString(i);
                    if (alias != null && !alias.isEmpty() && text.contains(alias)) {
                        matched = alias;
                        break;
                    }
                }
            }
            if (matched != null) {
                return new EvalStyleResult.Soft(id, true, "命中自称：" + matched);
            }
            return new EvalStyleResult.Soft(id, false, "未出现任何自称：" + join(aliases));
        }
        if ("catchphrase".equals(impl)) {
            return judgeCatchphrase(id, params, text);
        }
        if ("sentence-variance".equals(impl)) {
            int minSentences = params.containsKey("minSentences") ? params.getIntValue("minSentences") : 3;
            int minGap = params.containsKey("minGap") ? params.getIntValue("minGap") : 3;
            List<Integer> lengths = sentenceLengths(text);
            if (lengths.size() < minSentences) {
                return new EvalStyleResult.Soft(id, true,
                        "句数 " + lengths.size() + " < " + minSentences + "，视为通过");
            }
            int shortest = Integer.MAX_VALUE;
            int longest = 0;
            for (Integer one : lengths) {
                shortest = Math.min(shortest, one);
                longest = Math.max(longest, one);
            }
            int gap = longest - shortest;
            return new EvalStyleResult.Soft(id, gap >= minGap,
                    "句数 " + lengths.size() + "，最长 " + longest + " / 最短 " + shortest
                            + "，差值 " + gap + "，要求 >= " + minGap);
        }
        throw new IllegalArgumentException("未知的软项 impl：" + impl);
    }

    private EvalStyleResult.Soft judgeCatchphrase(String id, JSONObject params, String text) {
        JSONArray phrases = params.getJSONArray("phrases");
        JSONArray startOnly = params.getJSONArray("startOnly");
        int maxHits = params.containsKey("maxHits") ? params.getIntValue("maxHits") : 1;
        List<String> startOnlyList = new ArrayList<String>();
        if (startOnly != null) {
            for (int i = 0; i < startOnly.size(); i++) {
                startOnlyList.add(startOnly.getString(i));
            }
        }
        int total = 0;
        List<String> problems = new ArrayList<String>();
        if (phrases != null) {
            for (int i = 0; i < phrases.size(); i++) {
                String phrase = phrases.getString(i);
                if (phrase == null || phrase.isEmpty()) {
                    continue;
                }
                int from = 0;
                while (true) {
                    int at = text.indexOf(phrase, from);
                    if (at < 0) {
                        break;
                    }
                    total++;
                    if (startOnlyList.contains(phrase) && !isSentenceStart(text, at)) {
                        problems.add("「" + phrase + "」不在句首");
                    }
                    from = at + phrase.length();
                }
            }
        }
        if (!problems.isEmpty()) {
            return new EvalStyleResult.Soft(id, false, joinProblems(problems));
        }
        if (total > maxHits) {
            return new EvalStyleResult.Soft(id, false, "命中 " + total + " 次，上限 " + maxHits);
        }
        return new EvalStyleResult.Soft(id, true, "命中 " + total + " 次，上限 " + maxHits);
    }

    private boolean isSentenceStart(String text, int index) {
        if (index == 0) {
            return true;
        }
        char previous = text.charAt(index - 1);
        return previous == '。' || previous == '！' || previous == '？'
                || previous == '!' || previous == '?' || previous == '\n';
    }

    private List<Integer> sentenceLengths(String text) {
        List<Integer> lengths = new ArrayList<Integer>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '。' || cp == '！' || cp == '？' || cp == '!' || cp == '?'
                    || cp == '；' || cp == ';' || cp == '\n') {
                if (current.length() > 0) {
                    lengths.add(current.codePointCount(0, current.length()));
                    current.setLength(0);
                }
            } else {
                current.appendCodePoint(cp);
            }
        }
        if (current.length() > 0) {
            lengths.add(current.codePointCount(0, current.length()));
        }
        return lengths;
    }

    /** emoji 只观测：0x1F300–0x1FAFF、0x2600–0x27BF，变体选择符 0xFE0F 也计入。 */
    private int countEmoji(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xFE0F
                    || (cp >= 0x1F300 && cp <= 0x1FAFF)
                    || (cp >= 0x2600 && cp <= 0x27BF)) {
                count++;
            }
        }
        return count;
    }

    private static JSONObject requireObject(JSONObject parent, String key) {
        JSONObject child = parent.getJSONObject(key);
        if (child == null) {
            throw new IllegalArgumentException("deterministic.style 缺少节点：" + key);
        }
        return child;
    }

    private static List<String> ids(JSONArray array, String field) {
        if (array == null) {
            throw new IllegalArgumentException("缺少 " + field);
        }
        List<String> result = new ArrayList<String>();
        for (int i = 0; i < array.size(); i++) {
            String id = array.getString(i);
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException(field + " 含空 id");
            }
            result.add(id);
        }
        return result;
    }

    private static String join(JSONArray array) {
        if (array == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < array.size(); i++) {
            if (i > 0) {
                builder.append(" / ");
            }
            builder.append(array.getString(i));
        }
        return builder.toString();
    }

    private static String joinProblems(List<String> problems) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < problems.size(); i++) {
            if (i > 0) {
                builder.append("；");
            }
            builder.append(problems.get(i));
        }
        return builder.toString();
    }

    private static final class Mount {
        private List<String> hard = new ArrayList<String>();
        private final Map<String, JSONObject> soft = new LinkedHashMap<String, JSONObject>();
    }
}
