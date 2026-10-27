package org.moboxlab.mbb.eval.deterministic;

import java.util.Locale;

/**
 * 被测回复的确定性硬项预检（规格 §6.3.5 第三条）。
 *
 * <p>硬项命中即 veto：该题记 0、计入分母、不再送 judge。这里只做与模型无关的文本检查：
 * 空回复、主动跳过、被 length 截断，以及回复泄露系统提示词或人设原文。人设泄露采用
 * 「归一化后子串包含」：先去掉空白与标点，再用人设摘要中长度不少于 8 个码点的连续片段
 * 去匹配回复，避免整句照抄才能命中的脆弱判定。</p>
 */
public final class EvalVetoPrecheck {

    /** veto 原因：空回复。 */
    public static final String REASON_EMPTY = "empty";
    /** veto 原因：主动跳过。 */
    public static final String REASON_SKIP = "skip";
    /** veto 原因：生成被 length 截断。 */
    public static final String REASON_TRUNCATED = "truncated";
    /** veto 原因：回复泄露系统提示词或人设原文。 */
    public static final String REASON_PERSONA_LEAK = "persona-leak";

    /** 人设片段最小码点数：短于此长度的片段容易误伤普通表达。 */
    public static final int MIN_FRAGMENT_CODE_POINTS = 8;

    private EvalVetoPrecheck() {
    }

    /**
     * 按 §6.3.5 硬项顺序给出 veto 原因。
     *
     * @param content       被测回复原文，可空
     * @param finishReason  生成结束原因，可空
     * @param skip          生成侧是否主动跳过
     * @param personaSummary 人设摘要；为空时不做泄露判断
     * @return 命中的 veto 原因；未命中返回 null
     */
    public static String vetoReason(String content, String finishReason, boolean skip,
            String personaSummary) {
        if (content == null || content.trim().isEmpty()) {
            return REASON_EMPTY;
        }
        String trimmed = content.trim();
        if (skip || "<SKIP>".equalsIgnoreCase(trimmed)) {
            return REASON_SKIP;
        }
        if (finishReason != null && "length".equalsIgnoreCase(finishReason.trim())) {
            return REASON_TRUNCATED;
        }
        if (leaksPersona(trimmed, personaSummary)) {
            return REASON_PERSONA_LEAK;
        }
        return null;
    }

    /**
     * 回复是否包含人设摘要中长度不少于 8 个码点的片段。
     *
     * <p>双方都先去掉空白与标点并转小写，再按滑动窗口截取 8 个码点做子串包含判断。</p>
     */
    public static boolean leaksPersona(String content, String personaSummary) {
        String normalizedContent = normalize(content);
        String normalizedPersona = normalize(personaSummary);
        if (normalizedContent.isEmpty() || normalizedPersona.isEmpty()) {
            return false;
        }
        int personaCodePoints = normalizedPersona.codePointCount(0, normalizedPersona.length());
        if (personaCodePoints < MIN_FRAGMENT_CODE_POINTS) {
            return false;
        }
        for (int start = 0; start + MIN_FRAGMENT_CODE_POINTS <= personaCodePoints; start++) {
            int begin = normalizedPersona.offsetByCodePoints(0, start);
            int end = normalizedPersona.offsetByCodePoints(begin, MIN_FRAGMENT_CODE_POINTS);
            String fragment = normalizedPersona.substring(begin, end);
            if (normalizedContent.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** 去掉空白与标点并转小写，保留文字、数字与符号。 */
    static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                continue;
            }
            if (isPunctuation(codePoint)) {
                continue;
            }
            builder.appendCodePoint(Character.toLowerCase(codePoint));
        }
        return builder.toString();
    }

    private static boolean isPunctuation(int codePoint) {
        int type = Character.getType(codePoint);
        if (type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION) {
            return true;
        }
        return codePoint == '～' || codePoint == '~';
    }
}
