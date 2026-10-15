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
 * Roleplay 命令处理器：RoleplayBotCommandHandler
 */
public class RoleplayBotCommandHandler {
    private final RoleplayCommand command;

    public RoleplayBotCommandHandler(RoleplayCommand command) {
        this.command = command;
    }

    void handleBot(CommandSender sender,String[] args) {
        if (args.length < 3 || "status".equalsIgnoreCase(args[2])) {
            sendBotStatus(sender);
            return;
        }
        String action = args[2].toLowerCase();
        if ("chance".equals(action)) {
            if (args.length < 4) {
                sender.sendMessage("用法：/role bot chance <0.3-1>");
                return;
            }
            double chance;
            try {
                chance = Double.parseDouble(args[3]);
            } catch (Exception e) {
                sender.sendMessage("概率格式不正确。");
                return;
            }
            if (chance < 0 || chance > 1) {
                sender.sendMessage("概率必须在 0 到 1 之间。");
                return;
            }
            boolean changed = command.plugin().setOtherRoleBotReplyChance(chance);
            sender.sendMessage(changed
                    ? "机器人互聊概率已设置为："+chance
                    : "保存配置失败，请检查 config.yml 权限。");
            if (changed && (chance < 0.3 || chance > 0.7)) {
                sender.sendMessage("提示：推荐范围是 0.3 到 0.7。");
            }
            return;
        }
        if ("max".equals(action)) {
            if (args.length < 4) {
                sender.sendMessage("用法：/role bot max <1-10>");
                return;
            }
            int count;
            try {
                count = Integer.parseInt(args[3]);
            } catch (Exception e) {
                sender.sendMessage("次数格式不正确。");
                return;
            }
            if (count < 1 || count > 10) {
                sender.sendMessage("次数必须在 1 到 10 之间。");
                return;
            }
            boolean changed = command.plugin().setMaxConsecutiveOtherRoleMessages(count);
            sender.sendMessage(changed
                    ? "连续回应其他角色的上限已设置为："+count
                    : "保存配置失败，请检查 config.yml 权限。");
            return;
        }
        if ("qq".equals(action)) {
            handleBotCsv(sender,args,true);
            return;
        }
        if ("name".equals(action)) {
            handleBotCsv(sender,args,false);
            return;
        }
        sender.sendMessage("用法：/role bot status | chance <0.3-1> | max <1-10> | qq | name");
    }

    void handleBotCsv(CommandSender sender,String[] args,boolean qq) {
        String action = args.length > 3 ? args[3].toLowerCase() : "list";
        String current = qq ? command.plugin().getRoleplayConfig().otherRoleBotQQs : command.plugin().getRoleplayConfig().otherRoleBotNames;
        if ("list".equals(action)) {
            sender.sendMessage(qq
                    ? "其他角色机器人 QQ："+(current == null || current.trim().isEmpty() ? "未配置" : current)
                    : "其他角色机器人名称："+(current == null || current.trim().isEmpty() ? "未配置" : current));
            return;
        }
        if (!qq && "reset".equals(action)) {
            boolean changed = command.plugin().setOtherRoleBotNames(RoleplayConfig.DEFAULT_OTHER_ROLE_BOT_NAMES);
            sender.sendMessage(changed ? "其他角色机器人名称已恢复默认。" : "保存配置失败，请检查 config.yml 权限。");
            return;
        }
        if ("clear".equals(action)) {
            boolean changed = qq ? command.plugin().setOtherRoleBotQQs("") : command.plugin().setOtherRoleBotNames("");
            sender.sendMessage(changed
                    ? (qq ? "其他角色机器人 QQ 已清空。" : "其他角色机器人名称已清空。")
                    : "保存配置失败，请检查 config.yml 权限。");
            return;
        }
        if (!"add".equals(action) && !"remove".equals(action) && !"set".equals(action)) {
            sender.sendMessage("用法：/role bot "+(qq ? "qq" : "name")+" [list|add|remove|set|clear"
                    +(!qq ? "|reset" : "")+"] [值...]");
            return;
        }
        if (args.length < 5) {
            sender.sendMessage("请提供要"+(qq ? "设置的 QQ" : "设置的名称")+"。");
            return;
        }
        Set<String> values = new LinkedHashSet<>();
        if (!"set".equals(action)) values.addAll(command.splitCsv(current));
        for (int i = 4; i < args.length; i++) {
            for (String item : command.splitCsv(args[i])) {
                if (qq) {
                    try {
                        long userID = Long.parseLong(item);
                        if (userID <= 0) throw new NumberFormatException();
                        values.add(String.valueOf(userID));
                    } catch (Exception e) {
                        sender.sendMessage("QQ 格式不正确："+item);
                        return;
                    }
                } else if (!item.isEmpty()) {
                    values.add(item);
                }
            }
        }
        if ("remove".equals(action)) {
            for (int i = 4; i < args.length; i++) {
                for (String item : command.splitCsv(args[i])) values.remove(item);
            }
        }
        String value = command.joinCsv(values);
        boolean changed = qq ? command.plugin().setOtherRoleBotQQs(value) : command.plugin().setOtherRoleBotNames(value);
        sender.sendMessage(changed
                ? (qq ? "其他角色机器人 QQ 已更新：" : "其他角色机器人名称已更新：")
                + (value.isEmpty() ? "空" : value)
                : "保存配置失败，请检查 config.yml 权限。");
    }

    void sendBotStatus(CommandSender sender) {
        RoleplayConfig config = command.plugin().getRoleplayConfig();
        sender.sendMessage("机器人互聊设置：\n"
                +"接话概率："+config.otherRoleBotReplyChance+"\n"
                +"连续上限："+config.maxConsecutiveOtherRoleMessages+" 条\n"
                +"机器人 QQ："+(config.otherRoleBotQQs == null || config.otherRoleBotQQs.isEmpty()
                ? "未配置" : config.otherRoleBotQQs)+"\n"
                +"名称识别："+(config.otherRoleBotNames == null || config.otherRoleBotNames.isEmpty()
                ? "未配置" : config.otherRoleBotNames));
    }
}