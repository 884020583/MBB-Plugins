package org.moboxlab.mbb.trpg.present;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 超长消息拆分与折叠（纯函数，规格 §6.1）。
 *
 * <p>拆分点优先级：段落空行 → 换行 → 中文强标点（。！？…）→ 中文弱标点（，、；：），
 * 标点回扫窗口 60 字，找不到就硬切。群内最多拆 {@code maxParts} 条，
 * 第 2 条起标题追加 {@code " · 续 n/m"}；仍有剩余时折叠成一行提示。私聊不拆只折叠。</p>
 */
public final class TrpgMessageSplitter {

    /** 标点回扫窗口（按码点计）。 */
    public static final int SCAN_WINDOW = 60;
    /** 群内折叠提示模板。 */
    public static final String GROUP_FOLD_PREFIX = "……（本条还有 ";
    public static final String GROUP_FOLD_SUFFIX = " 字，发送 /trpg log 查看）";
    /** 私聊折叠提示后缀。 */
    public static final String PRIVATE_FOLD_SUFFIX = "……（私聊不再展开）";

    private static final String STRONG_PUNCTUATION = "。！？…";
    private static final String WEAK_PUNCTUATION = "，、；：";

    private final TrpgMessageFormatter formatter;

    public TrpgMessageSplitter(TrpgMessageFormatter formatter) {
        this.formatter = formatter == null ? new TrpgMessageFormatter() : formatter;
    }

    /** 单条正文是否放得下（不计标题）。 */
    public boolean fits(String body, int maxChars) {
        return formatter.count(body) <= maxChars;
    }

    /**
     * 群内拆分：返回 1..maxParts 条完整消息（含标题行）。
     *
     * @param title    第一条与后续条目的标题（续页会追加 {@code " · 续 n/m"}）
     * @param body     正文（已清洗）
     * @param maxChars 单条正文上限（不含标题与折叠行）
     * @param maxParts 最多拆几条，至少 1
     */
    public List<String> split(String title, String body, int maxChars, int maxParts) {
        if (title == null) {
            throw new IllegalArgumentException("标题不能为空");
        }
        String text = body == null ? "" : body;
        int parts = Math.max(1, maxParts);
        int limit = Math.max(1, maxChars);
        List<String> messages = new ArrayList<String>();
        if (formatter.count(text) <= limit) {
            messages.add(title + "\n" + text);
            return Collections.unmodifiableList(messages);
        }
        String remaining = text;
        for (int index = 1; index <= parts; index++) {
            String partTitle = index == 1 ? title : continuedTitle(title, index, parts);
            boolean lastPart = index == parts;
            if (lastPart) {
                messages.add(partTitle + "\n" + formatter.clean(
                        foldGroup(remaining, limit)));
                return Collections.unmodifiableList(messages);
            }
            int cut = findCut(remaining, limit);
            if (cut >= formatter.count(remaining)) {
                messages.add(partTitle + "\n" + remaining);
                return Collections.unmodifiableList(messages);
            }
            String head = formatter.take(remaining, cut);
            messages.add(partTitle + "\n" + head);
            remaining = remaining.substring(head.length());
        }
        return Collections.unmodifiableList(messages);
    }

    /** 续页标题：在收尾方括号之前插入 {@code " · 续 n/m"}，保留原子标题。 */
    static String continuedTitle(String title, int index, int total) {
        String suffix = " · 续 " + index + "/" + total;
        if (title.endsWith("】")) {
            return title.substring(0, title.length() - 1) + suffix + "】";
        }
        return title + suffix;
    }

    /** 私聊折叠：单条，超长截断并在末尾补折叠提示。 */
    public String fold(String title, String body, int maxChars) {
        if (title == null) {
            throw new IllegalArgumentException("标题不能为空");
        }
        String text = body == null ? "" : body;
        int limit = Math.max(1, maxChars);
        if (formatter.count(text) <= limit) {
            return title + "\n" + text;
        }
        return title + "\n" + foldPrivate(text, limit);
    }

    /** 群内最后一条的折叠：正文填满上限后补「还有 N 字」。 */
    String foldGroup(String remaining, int limit) {
        int total = formatter.count(remaining);
        if (total <= limit) {
            return remaining;
        }
        String suffix = GROUP_FOLD_PREFIX + total + GROUP_FOLD_SUFFIX;
        int keep = limit - formatter.count(suffix);
        if (keep < 1) {
            return formatter.truncate(remaining, limit);
        }
        String head = formatter.take(remaining, keep);
        int rest = total - formatter.count(head);
        if (rest <= 0) {
            return head;
        }
        return head + GROUP_FOLD_PREFIX + rest + GROUP_FOLD_SUFFIX;
    }

    /** 私聊折叠：正文填满上限后补固定提示。 */
    String foldPrivate(String remaining, int limit) {
        int total = formatter.count(remaining);
        if (total <= limit) {
            return remaining;
        }
        int keep = limit - formatter.count(PRIVATE_FOLD_SUFFIX);
        if (keep < 1) {
            return formatter.truncate(remaining, limit);
        }
        return formatter.take(remaining, keep) + PRIVATE_FOLD_SUFFIX;
    }

    /** 找到 <= limit 的最佳切点（码点下标），找不到返回 limit。 */
    int findCut(String text, int limit) {
        int total = formatter.count(text);
        if (total <= limit) {
            return total;
        }
        int charLimit = text.offsetByCodePoints(0, limit);
        int paragraph = text.lastIndexOf("\n\n", Math.max(0, charLimit - 1));
        if (paragraph > 0) {
            return formatter.count(text.substring(0, paragraph + 2));
        }
        int line = text.lastIndexOf('\n', Math.max(0, charLimit - 1));
        if (line > 0) {
            return formatter.count(text.substring(0, line + 1));
        }
        int strong = lastPunctuation(text, charLimit, STRONG_PUNCTUATION);
        if (strong > 0) {
            return formatter.count(text.substring(0, strong));
        }
        int weak = lastPunctuation(text, charLimit, WEAK_PUNCTUATION);
        if (weak > 0) {
            return formatter.count(text.substring(0, weak));
        }
        return limit;
    }

    /** 在窗口内从后向前找标点，返回值是切点（标点之后）的字符下标。 */
    private int lastPunctuation(String text, int charLimit, String punctuation) {
        int windowStart = Math.max(0, charLimit - SCAN_WINDOW * 2);
        for (int index = charLimit - 1; index >= windowStart; index--) {
            char current = text.charAt(index);
            if (punctuation.indexOf(current) >= 0) {
                if (current == '…' && index > 0 && text.charAt(index - 1) == '…') {
                    return index + 1;
                }
                return index + 1;
            }
        }
        return -1;
    }
}
