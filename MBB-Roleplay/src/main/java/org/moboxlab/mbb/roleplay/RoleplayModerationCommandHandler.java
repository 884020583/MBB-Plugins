package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Command.CommandPermission;
import org.moboxlab.moboxbot.API.Command.CommandSender;
import org.moboxlab.moboxbot.API.Util.ImageUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Roleplay 命令处理器：RoleplayModerationCommandHandler
 */
public class RoleplayModerationCommandHandler {
    private final RoleplayCommand command;

    public RoleplayModerationCommandHandler(RoleplayCommand command) {
        this.command = command;
    }

    void handleBlacklist(CommandSender sender,String[] args) {
        long groupID = sender.getGroupID();
        if (groupID <= 0) {
            sender.sendMessage("请在群聊中使用。");
            return;
        }
        String action = args.length > 2 ? args[2].toLowerCase() : "list";
        if ("list".equals(action)) {
            JSONArray entries = command.service().getBlacklistService().list(groupID);
            if (entries == null || entries.isEmpty()) {
                sender.sendMessage("当前群黑名单为空。");
                return;
            }
            StringBuilder builder = new StringBuilder("当前群黑名单：");
            for (Object object : entries) {
                JSONObject item = (JSONObject) object;
                builder.append("\n").append(item.getLongValue("userID"));
                String reason = item.getString("reason");
                if (reason != null && !reason.trim().isEmpty()) {
                    builder.append("  ").append(reason.trim());
                }
            }
            sender.sendMessage(builder.toString());
            return;
        }
        if ("clear".equals(action)) {
            command.service().getBlacklistService().clear(groupID);
            sender.sendMessage("当前群黑名单已清空。");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("用法：/role blacklist add <QQ> [原因] | remove <QQ> | clear | list");
            return;
        }
        long userID = command.parseUserID(args[3]);
        if (userID <= 0) {
            sender.sendMessage("QQ 格式不正确。");
            return;
        }
        if ("add".equals(action)) {
            String reason = command.joinArgs(args,4);
            command.service().blacklistAdd(groupID,userID,reason,sender.getUserID());
            sender.sendMessage("用户 "+userID+" 已加入当前群黑名单。");
            return;
        }
        if ("remove".equals(action)) {
            boolean removed = command.service().getBlacklistService().remove(groupID,userID);
            sender.sendMessage(removed
                    ? "用户 "+userID+" 已移出黑名单。"
                    : "用户 "+userID+" 不在黑名单中。");
            return;
        }
        sender.sendMessage("用法：/role blacklist add <QQ> [原因] | remove <QQ> | clear | list");
    }

    void handleQuiet(CommandSender sender,String[] args) {
        long groupID = sender.getGroupID();
        if (groupID <= 0) {
            sender.sendMessage("请在群聊中使用。");
            return;
        }
        RoleplayQuietService quiet = command.service().getQuietService();
        String action = args.length > 2 ? args[2].toLowerCase() : "status";
        if ("on".equals(action) || "off".equals(action)) {
            if (!sender.hasPermission(CommandPermission.BOT_ADMIN)) {
                sender.sendMessage("开关免打扰名单需要机器人管理员权限。");
                return;
            }
            boolean enabled = "on".equals(action);
            quiet.setEnabled(groupID,enabled);
            sender.sendMessage("本群免打扰名单已"+("on".equals(action) ? "启用" : "停用")+"。");
            return;
        }
        if ("list".equals(action)) {
            if (!sender.hasPermission(CommandPermission.BOT_ADMIN)) {
                sender.sendMessage("查看免打扰名单需要机器人管理员权限。");
                return;
            }
            JSONArray entries = quiet.list(groupID);
            StringBuilder builder = new StringBuilder("本群免打扰名单"
                    +"（"+(quiet.isEnabled(groupID) ? "已启用" : "已停用")+"）：");
            if (entries == null || entries.isEmpty()) {
                builder.append("\n空");
            } else {
                for (Object object : entries) {
                    builder.append("\n").append(((JSONObject) object).getLongValue("userID"));
                }
            }
            builder.append("\n名单内的群员只在主动叫到角色时才得到回复，其他群员不受影响。");
            sender.sendMessage(builder.toString());
            return;
        }
        if ("clear".equals(action)) {
            if (!sender.hasPermission(CommandPermission.BOT_ADMIN)) {
                sender.sendMessage("清空免打扰名单需要机器人管理员权限。");
                return;
            }
            quiet.clear(groupID);
            sender.sendMessage("本群免打扰名单已清空。");
            return;
        }
        if ("remove".equals(action)) {
            long targetID = sender.getUserID();
            if (args.length > 3) {
                targetID = command.parseUserID(args[3]);
                if (targetID <= 0) {
                    sender.sendMessage("QQ 格式不正确。");
                    return;
                }
                if (targetID != sender.getUserID()
                        && !sender.hasPermission(CommandPermission.BOT_ADMIN)) {
                    sender.sendMessage("只能把自己移出免打扰名单，移除他人需要机器人管理员权限。");
                    return;
                }
            }
            boolean removed = quiet.remove(groupID,targetID);
            sender.sendMessage(removed
                    ? "用户 "+targetID+" 已移出免打扰名单。"
                    : "用户 "+targetID+" 不在免打扰名单中。");
            return;
        }
        if ("add".equals(action)) {
            long targetID = sender.getUserID();
            if (args.length > 3) {
                //群员自助：只允许把自己加入名单，避免被他人强制免打扰
                targetID = command.parseUserID(args[3]);
                if (targetID <= 0) {
                    sender.sendMessage("QQ 格式不正确。");
                    return;
                }
                if (targetID != sender.getUserID()
                        && !sender.hasPermission(CommandPermission.BOT_ADMIN)) {
                    sender.sendMessage("只能把自己加入免打扰名单，添加他人需要机器人管理员权限。");
                    return;
                }
            }
            quiet.add(groupID,targetID,sender.getUserID());
            sender.sendMessage("用户 "+targetID+" 已加入本群免打扰名单，"
                    +"只有主动叫到角色时才会得到回复。");
            return;
        }
        sender.sendMessage("用法：/role quiet [on|off|list|add <QQ>|remove <QQ>|clear]"
                +"\n群员可自助使用 /role quiet add 与 /role quiet remove。");
    }
}