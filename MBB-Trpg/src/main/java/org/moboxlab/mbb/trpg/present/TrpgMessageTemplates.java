package org.moboxlab.mbb.trpg.present;

import org.moboxlab.mbb.trpg.script.Clue;
import org.moboxlab.mbb.trpg.script.Script;
import org.moboxlab.mbb.trpg.session.SessionSnapshot;
import org.moboxlab.mbb.trpg.store.CardData;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 消息模板（纯函数）：输入值对象，输出未拆分的单个字符串。
 *
 * <p>模板只决定「说什么」，「怎么截断、要不要拆条」由 {@link TrpgMessageFormatter} 与
 * {@link TrpgMessageSplitter} 决定。正文硬上限按呈现规范 §3 的表格逐条给出。</p>
 */
public final class TrpgMessageTemplates {

    /** 分数与人数区间的连接符。 */
    private static final String SEPARATOR = " · ";
    private static final String ALIGN = "　";
    private static final TrpgMessageFormatter FORMATTER = new TrpgMessageFormatter();
    /** 时间显示格式（本地时区）。 */
    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm";
    private static final String DATE_PATTERN = "yyyy-MM-dd";
    /** 标题行预留字数：模板截断只作用于正文，标题另算。 */
    private static final int TITLE_HEADROOM = 32;
    /*
     * 以下是模板级正文上限，与 presentation.* 配置的分工：
     * 模板先把内容收敛到合理体量，发送前的拆分/折叠再按 config.groupMaxChars() / privateMaxChars() 处理。
     */
    private static final int BODY_MAX_SHORT = 300 + TITLE_HEADROOM;
    private static final int BODY_MAX_ENDED = 1200;
    private static final int BODY_MAX_REPORT_TEXT = 600;
    private static final int BODY_MAX_KP_HINT = 900;
    private static final int BODY_MAX_LIST = 500;

    private TrpgMessageTemplates() {
    }

    /** 招募公告。 */
    public static String recruit(Script script, String scriptId, String kpName, String metaLine) {
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.RECRUIT)).append("《")
                .append(scriptName(script)).append("》开始招募\n");
        body.append("KP：").append(kpName).append(ALIGN).append("剧本：内置 ").append(scriptId);
        String meta = metaLine == null || metaLine.trim().isEmpty()
                ? scriptMeta(script) : metaLine.trim();
        if (!meta.isEmpty()) {
            body.append('\n').append(meta);
        }
        String description = FORMATTER.clean(script == null ? "" : script.description());
        if (!description.isEmpty()) {
            body.append('\n').append(description);
        }
        body.append("\n\n发送 /trpg join 加入；发送 /trpg status 查看局面。");
        return FORMATTER.truncate(body.toString(), BODY_MAX_SHORT);
    }

    /** 剧本元信息行：标签 · 人数 · 时长。 */
    public static String scriptMeta(Script script) {
        if (script == null) {
            return "";
        }
        List<String> parts = new ArrayList<String>();
        for (String tag : script.tags()) {
            String value = tag == null ? "" : tag.trim();
            if (!value.isEmpty()) {
                parts.add(value);
            }
        }
        parts.add(script.minPlayers() + "～" + script.maxPlayers() + " 人");
        if (script.estimatedMinutes() > 0) {
            parts.add("约 " + script.estimatedMinutes() + " 分钟");
        }
        return join(parts, SEPARATOR);
    }

    /** 加入。 */
    public static String joined(String name, int current, int maxPlayers, String occupation) {
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.JOIN, name)).append('\n');
        body.append(name).append(" 加入了本局，当前 ").append(current).append('/')
                .append(maxPlayers).append(" 人。");
        if (occupation != null && !occupation.trim().isEmpty()) {
            body.append("职业：").append(occupation.trim()).append('。');
        }
        return body.toString();
    }

    /** 离开。 */
    public static String left(String name, int current, int maxPlayers, boolean cardKept) {
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.LEAVE, name)).append('\n');
        body.append(name).append(" 离开了本局，当前 ").append(current).append('/')
                .append(maxPlayers).append(" 人。");
        if (cardKept) {
            body.append("角色卡已保留，招募期内可再次加入。");
        }
        return body.toString();
    }

    /** 邀请（KP 拉人）。 */
    public static String invited(String name, int current, int maxPlayers) {
        return TrpgTitles.of(TrpgTitles.INVITE, name) + "\n" + name + " 被邀请加入本局，当前 "
                + current + "/" + maxPlayers + " 人。";
    }

    /** KP 接管。 */
    public static String takenOver(String oldKpName, String newKpName) {
        return TrpgTitles.of(TrpgTitles.TAKE, newKpName) + "\nKP 已由 " + oldKpName
                + " 移交给 " + newKpName + "。";
    }

    /** 主持方式提示。 */
    public static String host(boolean aiEnabled) {
        return TrpgTitles.of(TrpgTitles.HOST) + "\n当前主持方式："
                + (aiEnabled ? "AI 叙述" : "人主持（AI 已关闭）") + "。";
    }

    /** 开局公告。 */
    public static String begin(Script script, String kpName, List<String> playerNames,
                               String firstSceneTitle) {
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.BEGIN)).append("《").append(scriptName(script))
                .append("》\n");
        body.append("KP：").append(kpName).append(ALIGN).append("玩家：")
                .append(join(playerNames, "、"));
        if (firstSceneTitle != null && !firstSceneTitle.trim().isEmpty()) {
            body.append('\n').append("第一幕：").append(firstSceneTitle.trim());
        }
        return body.toString();
    }

    /** 场景叙述（纯文本，图片降级时也用这条）。 */
    public static String scene(String sceneTitle, String narration) {
        String text = narration == null ? "" : narration;
        return TrpgTitles.of(TrpgTitles.SCENE, sceneTitle) + "\n" + text;
    }

    /** 情景卡降级正文：场景叙述 + 下一步提示。 */
    public static String sceneFallback(String sceneTitle, String narration) {
        return scene(sceneTitle, narration) + "\n\n发送 /trpg status 查看局面。";
    }

    /** 线索公开钩子（全文只私聊 KP）。 */
    public static String clue(Clue clue) {
        String name = clue == null ? "未知线索" : clue.name();
        String locked = clue == null ? "" : clue.lockedSummary();
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.CLUE, name)).append('\n');
        if (!locked.trim().isEmpty()) {
            body.append(locked).append('\n');
        }
        body.append("线索全文已私聊 KP。");
        return body.toString();
    }

    /** 技能检定。 */
    public static String check(TrpgCheckView view) {
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.CHECK, view.skillLabel())).append(view.rollerName())
                .append('\n');
        body.append("1D100 = ").append(view.rolled()).append(" / ").append(view.target())
                .append(' ').append(degreeClause(view));
        String note = view.note().trim();
        if (!note.isEmpty()) {
            body.append('\n').append(note);
        }
        return body.toString();
    }

    /** 理智检定。 */
    public static String san(TrpgCheckView view) {
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.SAN, view.rollerName())).append('\n');
        body.append("1D100 = ").append(view.rolled()).append(" / ").append(view.target())
                .append(' ').append(degreeClause(view)).append('\n');
        body.append("SAN ").append(view.sanBefore()).append(" → ").append(view.sanAfter())
                .append("（损失 ").append(view.sanLoss()).append("）");
        return body.toString();
    }

    /** 对抗检定。 */
    public static String opposed(TrpgCheckView left, TrpgCheckView right, boolean leftWin,
                                 String tieBreakLabel) {
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.OPPOSED, left.skillLabel())).append('\n');
        body.append(left.rollerName()).append("：1D100 = ").append(left.rolled()).append(" / ")
                .append(left.target()).append(' ').append(degreeClause(left)).append('\n');
        body.append(right.rollerName()).append("：1D100 = ").append(right.rolled()).append(" / ")
                .append(right.target()).append(' ').append(degreeClause(right)).append('\n');
        if (tieBreakLabel == null || tieBreakLabel.trim().isEmpty()) {
            body.append(leftWin ? left.rollerName() : right.rollerName()).append(" 胜。");
        } else {
            body.append(leftWin ? left.rollerName() : right.rollerName()).append(" 胜（")
                    .append(tieBreakLabel.trim()).append("）。");
        }
        return body.toString();
    }

    /** 成长结算明细。 */
    public static String growth(List<String> lines) {
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.GROWTH));
        if (lines == null || lines.isEmpty()) {
            body.append("\n本局没有技能涨点。");
        } else {
            for (String line : lines) {
                body.append('\n').append(line);
            }
        }
        return FORMATTER.truncate(body.toString(), BODY_MAX_SHORT);
    }

    /** 数值调整。 */
    public static String adjust(String name, String item, String from, String to) {
        return TrpgTitles.of(TrpgTitles.ADJUST, name) + "\n" + item + "：" + from + " → " + to + "。";
    }

    /** KP 触发提示（只私聊 KP）。 */
    public static String trigger(String sceneTitle, String hint) {
        return TrpgTitles.of(TrpgTitles.TRIGGER, sceneTitle) + "\n" + (hint == null ? "" : hint);
    }

    /** AI 叙述（含建议动作与落款）。 */
    public static String aiNarration(String sceneTitle, String text, List<String> suggestions,
                                     String kpName, boolean degraded) {
        String body = FORMATTER.truncate(FORMATTER.clean(text), 300);
        StringBuilder message = new StringBuilder();
        // 冻结样例（规范 §4.3）：标题行是「【AI 叙述】」直接接场景名，不加分隔符。
        message.append(TrpgTitles.of(TrpgTitles.AI_NARRATION));
        if (sceneTitle != null && !sceneTitle.trim().isEmpty()) {
            message.append(sceneTitle.trim());
        }
        message.append('\n').append(body);
        if (suggestions != null && !suggestions.isEmpty()) {
            message.append("\n\n建议：").append(join(suggestions, ALIGN));
        }
        if (degraded) {
            // 降级款与正文接在同一行，对应呈现规范 §4.3 的冻结样例。
            message.append("—— 主持人 · ").append(kpName).append("（AI 暂不可用，本条为剧本原文）");
        } else {
            message.append("\n\n—— 主持人 · ").append(kpName).append("（AI 叙述）");
        }
        return message.toString();
    }

    /** 重启恢复提示（招募期的局不发）。 */
    public static String recovered(Script script, SessionSnapshot snapshot, int playerCount) {
        String name = scriptName(script);
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.RECOVER)).append("《").append(name).append("》已恢复\n");
        String sceneTitle = snapshot == null ? "" : snapshot.sceneTitle();
        int sceneNo = snapshot == null ? 1 : snapshot.sceneIndex() + 1;
        int sceneTotal = script == null || script.scenes().isEmpty() ? sceneNo : script.scenes().size();
        if (!sceneTitle.trim().isEmpty()) {
            body.append("当前场景：").append(sceneTitle).append("（第 ").append(sceneNo).append('/')
                    .append(sceneTotal).append(" 幕）").append(ALIGN);
        }
        body.append("已恢复 ").append(Math.max(0, playerCount)).append(" 名玩家。\n");
        body.append("本局在上次重启前正在进行，KP 可发送 /trpg resume 继续。\n\n");
        body.append("/trpg status 可以随时查看局面。");
        return body.toString();
    }

    /** 一次性提示。 */
    public static String notice(String text) {
        return TrpgTitles.of(TrpgTitles.NOTICE) + "\n" + (text == null ? "" : text);
    }

    /** 固定错误：原因 + 下一步怎么做。 */
    public static String error(String reason, String next) {
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.ERROR)).append('\n').append(reason);
        if (next != null && !next.trim().isEmpty()) {
            body.append('\n').append(next.trim());
        }
        return body.toString();
    }

    /** 结束确认。 */
    public static String endConfirm(Script script, String method, int confirmSeconds) {
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.END_CONFIRM)).append("《").append(scriptName(script))
                .append("》\n");
        body.append("确认方式：").append(method).append("。\n");
        body.append(confirmSeconds).append(" 秒内再次发送 /trpg end 生效。");
        return body.toString();
    }

    /** 局结束（先战报图，再这条文字）。 */
    public static String ended(TrpgReport report, boolean reportImageSent) {
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.END)).append("《").append(report.scriptName())
                .append("》· 结局「").append(report.endingTitle()).append("」\n");
        String narration = FORMATTER.clean(report.endingNarration());
        if (!narration.isEmpty()) {
            body.append(narration).append('\n');
        }
        body.append('\n');
        body.append("时间 ").append(report.durationText())
                .append(SEPARATOR).append("线索 ").append(report.clueNames().size()).append('/')
                .append(report.clueTotal())
                .append(SEPARATOR).append("检定 ").append(report.checkTotal()).append(" 次");
        if (report.checkTotal() > 0) {
            body.append("（成功 ").append(report.checkSuccess()).append('）');
        }
        // 冻结样例（规范 §4.4）：成功数括号后紧接「· 成长」，无空格。
        body.append(report.checkTotal() > 0 ? "· 成长 " : SEPARATOR + "成长 ")
                .append(report.growthLines().size()).append(" 人次\n");
        body.append(reportImageSent
                ? "战报图见上一条；文字明细发送 /trpg log 查看（保留 " + report.retentionDays() + " 天）。"
                : "文字明细发送 /trpg log 查看（保留 " + report.retentionDays() + " 天）。");
        return FORMATTER.truncate(body.toString(), BODY_MAX_ENDED);
    }

    /** 战报纯文本（图失败时的回退，字段与图一致）。 */
    public static String reportText(TrpgReport report) {
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.REPORT)).append("《").append(report.scriptName())
                .append("》· 结局「").append(report.endingTitle()).append("」\n");
        body.append("结束时间：").append(dateTime(report.endedAt())).append(ALIGN)
                .append("时长：").append(report.durationText()).append('\n');
        body.append("KP：").append(report.kpName()).append(ALIGN).append("玩家：")
                .append(join(report.playerNames(), "、")).append('\n');
        body.append("线索：");
        if (report.clueNames().isEmpty()) {
            body.append("无（0/").append(report.clueTotal()).append("）");
        } else {
            body.append(join(report.clueNames(), "、")).append("（")
                    .append(report.clueNames().size()).append('/').append(report.clueTotal())
                    .append("）");
        }
        body.append('\n').append("检定：");
        if (report.checkResults().isEmpty()) {
            body.append("无独立检定");
        } else {
            body.append(join(report.checkResults(), "、")).append("（共 ")
                    .append(report.checkTotal()).append(" 次）");
        }
        body.append('\n').append("成长：");
        body.append(report.growthLines().isEmpty()
                ? "本局无涨点" : join(report.growthLines(), "、"));
        body.append('\n').append("日志：/trpg log 可查看本局记录（保留 ")
                .append(report.retentionDays()).append(" 天）");
        return FORMATTER.truncate(body.toString(), BODY_MAX_REPORT_TEXT);
    }

    /** 角色卡全文（只私聊本人）。 */
    public static String card(String name, CardData card) {
        CardData data = card == null ? CardData.empty() : card;
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.CARD, name)).append('\n');
        List<String> attributes = CardData.ATTRIBUTES;
        for (int index = 0; index < attributes.size(); index += 4) {
            List<String> lineParts = new ArrayList<String>();
            for (int inner = index; inner < Math.min(index + 4, attributes.size()); inner++) {
                String key = attributes.get(inner);
                lineParts.add(key + " " + data.attribute(key));
            }
            body.append(join(lineParts, ALIGN)).append('\n');
        }
        body.append("SAN ").append(data.san()).append(ALIGN).append("HP ").append(data.hp())
                .append(ALIGN).append("MP ").append(data.mp()).append('\n');
        Map<String, Integer> skills = data.skills();
        if (skills.isEmpty()) {
            body.append("技能：无");
        } else {
            List<String> skillParts = new ArrayList<String>();
            for (Map.Entry<String, Integer> entry : skills.entrySet()) {
                skillParts.add(entry.getKey() + " " + entry.getValue());
            }
            body.append("技能：").append(join(skillParts, "、"));
        }
        body.append('\n').append("物品：")
                .append(data.items().isEmpty() ? "无" : join(data.items(), "、"));
        return FORMATTER.truncate(body.toString(), BODY_MAX_REPORT_TEXT);
    }

    /** KP 提示（只私聊 KP）：场景、触发、线索全文、下一场景。 */
    public static String kpHint(String sceneTitle, String triggerHint, List<String> clueTexts,
                                String nextSceneTitle) {
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.KP_HINT, sceneTitle)).append('\n');
        if (triggerHint != null && !triggerHint.trim().isEmpty()) {
            body.append("触发：").append(triggerHint.trim()).append('\n');
        }
        if (clueTexts != null && !clueTexts.isEmpty()) {
            body.append("线索全文：\n");
            for (String text : clueTexts) {
                body.append(text).append('\n');
            }
        }
        if (nextSceneTitle != null && !nextSceneTitle.trim().isEmpty()) {
            body.append("下一场景：").append(nextSceneTitle.trim());
        }
        return FORMATTER.truncate(body.toString(), BODY_MAX_KP_HINT);
    }

    /** 剧本库列表（每页 10 条）。 */
    public static String scriptList(List<String> entries, int page, int totalPages) {
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.SCRIPT_LIBRARY)).append("第 ").append(page).append('/')
                .append(Math.max(1, totalPages)).append(" 页");
        for (String entry : entries) {
            body.append('\n').append(entry);
        }
        body.append("\n\n发送 /trpg script show <id> 查看详情。");
        return FORMATTER.truncate(body.toString(), BODY_MAX_LIST);
    }

    /** 剧本详情（私聊）。 */
    public static String scriptDetail(Script script) {
        if (script == null) {
            return TrpgTitles.of(TrpgTitles.SCRIPT) + "\n剧本不存在。";
        }
        StringBuilder body = new StringBuilder();
        body.append(TrpgTitles.of(TrpgTitles.SCRIPT, script.name())).append('\n');
        body.append("id：").append(script.id()).append(ALIGN).append("作者：")
                .append(script.author().isEmpty() ? "未知" : script.author()).append('\n');
        body.append(scriptMeta(script)).append('\n');
        body.append("场景 ").append(script.scenes().size()).append(" 个")
                .append(ALIGN).append("线索 ").append(script.clues().size()).append(" 个")
                .append(ALIGN).append("结局 ").append(script.endings().size()).append(" 个");
        if (!script.description().isEmpty()) {
            body.append('\n').append(script.description());
        }
        return FORMATTER.truncate(body.toString(), BODY_MAX_LIST);
    }

    /** /r 与 /roll 回复：不套标题。 */
    public static String dice(String expression, List<String> segmentLines, int total) {
        StringBuilder body = new StringBuilder();
        if (segmentLines != null) {
            for (String line : segmentLines) {
                body.append(line).append('\n');
            }
        }
        body.append(expression).append(" = ").append(total);
        return body.toString();
    }

    /** 表达式解析失败：不套标题的一行。 */
    public static String diceUnsupported(String expression) {
        return "表达式不支持：" + expression + "（只支持 NdM 与加法，最多 3 段）";
    }

    /** 检定等级子句，例如 {@code → 常规成功}、{@code → 大成功（01）}。 */
    public static String degreeClause(TrpgCheckView view) {
        if (view.degree() == TrpgCheckView.DEGREE_CRITICAL) {
            return "→ 大成功（" + twoDigits(view.rolled()) + "）";
        }
        return "→ " + view.degreeLabel();
    }

    /** 时间格式：{@code yyyy-MM-dd HH:mm}。 */
    public static String dateTime(long millis) {
        if (millis <= 0L) {
            return "未知";
        }
        return new SimpleDateFormat(DATE_TIME_PATTERN, Locale.CHINA).format(new Date(millis));
    }

    /** 日期格式：{@code yyyy-MM-dd}。 */
    public static String date(long millis) {
        if (millis <= 0L) {
            return "未知";
        }
        return new SimpleDateFormat(DATE_PATTERN, Locale.CHINA).format(new Date(millis));
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    private static String scriptName(Script script) {
        return script == null || script.name().isEmpty() ? "未知剧本" : script.name();
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(separator);
            }
            builder.append(value);
        }
        return builder.toString();
    }
}
