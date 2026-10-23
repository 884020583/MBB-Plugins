package org.moboxlab.mbb.trpg.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import org.moboxlab.mbb.trpg.present.TrpgMessageTemplates;
import org.moboxlab.mbb.trpg.present.TrpgTitles;
import org.moboxlab.mbb.trpg.script.Script;
import org.moboxlab.mbb.trpg.script.ScriptInfo;
import org.moboxlab.mbb.trpg.session.SessionActor;
import org.moboxlab.mbb.trpg.session.SessionResult;
import org.moboxlab.mbb.trpg.store.BlacklistRow;

/**
 * 库与权限 handler：script（KP 换本局剧本 + 剧本库管理）与 blacklist。
 *
 * <p>剧本库管理只对 BOT_ADMIN / OWNER 开放；黑名单由群管理员管本群，BOT_ADMIN / OWNER
 * 可在私聊按群号远程解封。导入、删除与生成属于后续切片，这里只做识别与「后续启用」回执。</p>
 */
final class AdminCommandHandler {

    private static final int LIBRARY_PAGE_SIZE = 10;
    private static final String UPCOMING_NEXT = "将在后续版本启用。";
    private static final String PERMISSION_NEXT = "本指令仅限管理员使用。";

    private AdminCommandHandler() {
    }

    static Map<TrpgSubcommand, TrpgSubcommandHandler> handlers() {
        Map<TrpgSubcommand, TrpgSubcommandHandler> map =
                new EnumMap<TrpgSubcommand, TrpgSubcommandHandler>(TrpgSubcommand.class);
        map.put(TrpgSubcommand.SCRIPT, new TrpgSubcommandHandler() {
            @Override
            public void handle(TrpgCommandContext context, SessionActor actor, String[] params) {
                script(context, actor, params);
            }
        });
        map.put(TrpgSubcommand.BLACKLIST, new TrpgSubcommandHandler() {
            @Override
            public void handle(TrpgCommandContext context, SessionActor actor, String[] params) {
                blacklist(context, actor, params);
            }
        });
        return Collections.unmodifiableMap(map);
    }

    static void script(TrpgCommandContext context, SessionActor actor, String[] params) {
        String action = arg(params, 1);
        String lower = action.toLowerCase(Locale.ROOT);
        boolean libraryAction = "list".equals(lower) || "show".equals(lower)
                || "import".equals(lower) || "delete".equals(lower) || "gen".equals(lower);
        // 判定顺序固定为权限先于参数（§5.4）：库管理先卡 BOT_ADMIN，再校验具体参数。
        if (libraryAction && !requireBotAdmin(context, actor)) {
            return;
        }
        if (action.isEmpty()) {
            context.rejectError(actor, "script-argument", "参数不对：缺少剧本 ID 或库管理动作",
                    "发送 /trpg help 查看用法。");
            return;
        }
        if ("list".equals(lower)) {
            listScripts(context, actor, params);
            return;
        }
        if ("show".equals(lower)) {
            showScript(context, actor, params);
            return;
        }
        if ("import".equals(lower) || "delete".equals(lower) || "gen".equals(lower)) {
            context.rejectNotice(actor, "script-" + lower, "剧本" + scriptActionLabel(lower) + UPCOMING_NEXT);
            return;
        }
        // 其余情况按剧本 ID 处理：换本局剧本只允许群内、由局面层判定 KP 与招募期。
        if (!actor.inGroup()) {
            context.rejectError(actor, "script-private", "请在群内使用", "私聊只支持 help 与 /roll。");
            return;
        }
        SessionResult result = context.sessions().changeScript(actor, action);
        if (!context.accept(actor, result)) {
            return;
        }
        context.reply(actor, TrpgMessageTemplates.scriptChanged(
                context.loadScript(result.snapshot() == null ? action : result.snapshot().scriptId())));
    }

    /** 库管理动作的中文名（导入 / 删除 / 生成）。 */
    private static String scriptActionLabel(String lower) {
        if ("import".equals(lower)) {
            return "导入";
        }
        if ("delete".equals(lower)) {
            return "删除";
        }
        return "生成";
    }

    private static void listScripts(TrpgCommandContext context, SessionActor actor, String[] params) {
        List<ScriptInfo> infos = new ArrayList<ScriptInfo>();
        Lock lock = context.catalog().acquireReadLock();
        lock.lock();
        try {
            List<ScriptInfo> all = context.catalog().list();
            if (all != null) {
                infos.addAll(all);
            }
        } finally {
            lock.unlock();
        }
        int totalPages = Math.max(1, (infos.size() + LIBRARY_PAGE_SIZE - 1) / LIBRARY_PAGE_SIZE);
        int page = pageOf(arg(params, 2), totalPages);
        List<String> entries = new ArrayList<String>();
        int begin = (page - 1) * LIBRARY_PAGE_SIZE;
        for (int index = begin; index < Math.min(begin + LIBRARY_PAGE_SIZE, infos.size()); index++) {
            ScriptInfo info = infos.get(index);
            entries.add("《" + info.name() + "》（" + info.id() + "）· " + info.source().label());
        }
        context.reply(actor, TrpgMessageTemplates.scriptList(entries, page, totalPages));
    }

    private static void showScript(TrpgCommandContext context, SessionActor actor, String[] params) {
        String scriptId = arg(params, 2);
        if (scriptId.isEmpty()) {
            context.rejectError(actor, "script-show", "参数不对：缺少剧本 ID",
                    "发送 /trpg help 查看用法。");
            return;
        }
        Script script = context.loadScript(scriptId);
        if (script == null) {
            context.rejectError(actor, "script-missing", "剧本不存在：" + scriptId,
                    "发送 /trpg script list 查看可用剧本。");
            return;
        }
        context.presenter().sendScriptPage(actor.groupId(), actor.userId(), script);
    }

    static void blacklist(TrpgCommandContext context, SessionActor actor, String[] params) {
        if (context.blacklist() == null) {
            context.rejectError(actor, "blacklist-unavailable", "黑名单服务不可用，请稍后重试",
                    "可以稍后再发送一次。");
            return;
        }
        // 判定顺序固定为权限先于参数（§5.4）：群内要群管理员，私聊要 BOT_ADMIN。
        if (actor.inGroup() ? !actor.groupAdmin() : !actor.botAdmin()) {
            context.rejectError(actor, "blacklist-permission",
                    actor.inGroup() ? "只有群管理员能管理本群黑名单" : "私聊解封仅限 BOT_ADMIN 或 OWNER",
                    PERMISSION_NEXT);
            return;
        }
        String action = arg(params, 1).toLowerCase(Locale.ROOT);
        if ("add".equals(action)) {
            addToBlacklist(context, actor, params);
            return;
        }
        if ("remove".equals(action)) {
            removeFromBlacklist(context, actor, params);
            return;
        }
        if ("list".equals(action)) {
            listBlacklist(context, actor);
            return;
        }
        context.rejectError(actor, "blacklist-argument", "参数不对：黑名单只支持 add / remove / list",
                "发送 /trpg help 查看用法。");
    }

    private static void addToBlacklist(TrpgCommandContext context, SessionActor actor,
                                       String[] params) {
        if (!actor.inGroup()) {
            context.rejectError(actor, "blacklist-permission", "拉黑只能在群内进行",
                    "私聊用法：/trpg blacklist remove <群号> <QQ>");
            return;
        }
        long target = TrpgCommandService.parseUserId(arg(params, 2));
        if (target <= 0L) {
            context.rejectError(actor, "blacklist-target", "参数不对：无法识别要拉黑的成员",
                    "用法：/trpg blacklist add <@玩家> [原因]");
            return;
        }
        String reason = joinFrom(params, 3);
        boolean added = context.blacklist().add(actor.groupId(), target, actor.userId(), reason,
                context.clock().nowMillis());
        String name = context.nameOf(actor.groupId(), target);
        if (!added) {
            // add 幂等：false 只表示这次没写进去（含 -1），重试即可。
            context.rejectError(actor, "blacklist-write", "拉黑失败，请稍后重试",
                    "可以再发送一次 /trpg blacklist add 重试。");
            return;
        }
        context.reply(actor, TrpgMessageTemplates.notice("已把 " + name + " 加入本群黑名单。"));
    }

    private static void removeFromBlacklist(TrpgCommandContext context, SessionActor actor,
                                            String[] params) {
        long groupId;
        long target;
        if (actor.inGroup()) {
            groupId = actor.groupId();
            target = TrpgCommandService.parseUserId(arg(params, 2));
        } else {
            Long parsedGroup = longValue(arg(params, 2));
            groupId = parsedGroup == null ? 0L : parsedGroup.longValue();
            target = TrpgCommandService.parseUserId(arg(params, 3));
        }
        if (groupId <= 0L || target <= 0L) {
            context.rejectError(actor, "blacklist-target", "参数不对：无法识别群号或成员",
                    "私聊用法：/trpg blacklist remove <群号> <QQ>");
            return;
        }
        boolean removed = context.blacklist().remove(groupId, target);
        String name = context.nameOf(groupId, target);
        if (!removed) {
            // false 有两种来源：本来就不在名单，或 -1 语句未执行。再读一次名单区分。
            if (context.blacklist().contains(groupId, target)) {
                context.rejectError(actor, "blacklist-write", "解封失败，请稍后重试",
                        "可以再发送一次重试。");
            } else {
                context.rejectNotice(actor, "blacklist-absent", "对方不在该群黑名单中：" + name);
            }
            return;
        }
        context.reply(actor, TrpgMessageTemplates.notice(
                "已把 " + name + " 移出群 " + groupId + " 的黑名单。"));
    }

    private static void listBlacklist(TrpgCommandContext context, SessionActor actor) {
        if (!actor.inGroup()) {
            context.rejectError(actor, "blacklist-permission", "黑名单列表只能在群内查看",
                    "私聊用法：/trpg blacklist remove <群号> <QQ>");
            return;
        }
        List<BlacklistRow> rows = context.blacklist().list(actor.groupId());
        // 名单可能超过【提示】的 120 字上限，这里按群内正文上限由拆分器处理，不套 notice 模板。
        StringBuilder message = new StringBuilder(TrpgTitles.of(TrpgTitles.NOTICE))
                .append('\n').append("本群黑名单（").append(rows.size()).append(" 人）");
        if (rows.isEmpty()) {
            message.append("：暂无。");
        } else {
            for (BlacklistRow row : rows) {
                message.append('\n').append(context.nameOf(actor.groupId(), row.userId()));
                if (row.reason() != null && !row.reason().trim().isEmpty()) {
                    message.append("（").append(row.reason().trim()).append("）");
                }
            }
        }
        context.reply(actor, message.toString());
    }

    private static boolean requireBotAdmin(TrpgCommandContext context, SessionActor actor) {
        if (actor.botAdmin()) {
            return true;
        }
        context.rejectError(actor, "script-library-permission",
                "剧本库管理仅限 BOT_ADMIN 或 OWNER", PERMISSION_NEXT);
        return false;
    }

    private static int pageOf(String raw, int totalPages) {
        Long value = longValue(raw);
        int page = value == null ? 1 : value.intValue();
        if (page < 1) {
            return 1;
        }
        return Math.min(page, totalPages);
    }

    private static String arg(String[] params, int index) {
        if (params == null || index < 0 || index >= params.length || params[index] == null) {
            return "";
        }
        return params[index].trim();
    }

    private static String joinFrom(String[] params, int from) {
        StringBuilder text = new StringBuilder();
        if (params == null) {
            return "";
        }
        for (int index = from; index < params.length; index++) {
            String value = arg(params, index);
            if (value.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(value);
        }
        return text.toString();
    }

    private static Long longValue(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(token.trim());
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
