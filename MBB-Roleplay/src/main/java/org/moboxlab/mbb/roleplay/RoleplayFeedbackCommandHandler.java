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
 * Roleplay 命令处理器：RoleplayFeedbackCommandHandler
 */
public class RoleplayFeedbackCommandHandler {
    private final RoleplayCommand command;

    public RoleplayFeedbackCommandHandler(RoleplayCommand command) {
        this.command = command;
    }

    void handleFeedback(CommandSender sender,String[] args) {
        long groupID = sender.getGroupID();
        if (groupID <= 0) {
            sender.sendMessage("请在群聊中使用。");
            return;
        }
        RoleplayReplyFeedbackService feedback = command.service().getReplyFeedbackService();
        if (args.length > 2 && "reset".equalsIgnoreCase(args[2])) {
            feedback.reset(groupID);
            sender.sendMessage("当前群主动发言反馈统计已重置。");
            return;
        }
        JSONObject stats = feedback.stats(groupID);
        sender.sendMessage("主动发言反馈："
                +"\n累计被接话："+stats.getLongValue("successCount")
                +"\n累计无人接："+stats.getLongValue("ignoreCount")
                +"\n最近样本："+stats.getIntValue("recentSuccess")+"/"+stats.getIntValue("recentCount")
                +"\n连续没人接时，非直接消息的回复概率会自动降低。");
    }

    void handleExpression(CommandSender sender,String[] args) {
        long groupID = sender.getGroupID();
        if (groupID <= 0) {
            sender.sendMessage("请在群聊中使用。");
            return;
        }
        RoleplayExpressionService expressions = command.service().getExpressionService();
        String action = args.length > 2 ? args[2].toLowerCase() : "list";
        if ("list".equals(action)) {
            String status = args.length > 3 ? args[3].toLowerCase() : "pending";
            JSONArray entries = expressions.list(groupID,status,30);
            if (entries.isEmpty()) {
                sender.sendMessage("当前群没有 "+status+" 表达候选。");
                return;
            }
            StringBuilder builder = new StringBuilder("表达候选（"+status+"）：");
            for (Object object : entries) {
                JSONObject item = (JSONObject) object;
                builder.append("\n#").append(item.getLongValue("id"))
                        .append(" ").append(item.getString("content"))
                        .append("（"+item.getIntValue("count")+" 次 / "
                        +item.getIntValue("userCount")+" 人）");
            }
            sender.sendMessage(builder.toString());
            return;
        }
        if ("promote".equals(action) || "reject".equals(action)) {
            if (args.length <= 3) {
                sender.sendMessage("用法：/role expression "+action+" <ID>");
                return;
            }
            long id = command.parseUserID(args[3]);
            boolean changed = expressions.setStatus(id,
                    "promote".equals(action) ? "approved" : "rejected");
            sender.sendMessage(changed ? "表达候选已更新。" : "没有找到该表达候选。");
            return;
        }
        if ("clear".equals(action)) {
            int count = expressions.clear(groupID);
            sender.sendMessage("当前群表达候选已清空，共删除 "+count+" 条。");
            return;
        }
        sender.sendMessage("用法：/role expression list [pending|approved|rejected]"
                +" | promote <ID> | reject <ID> | clear");
    }

    void handleReview(CommandSender sender,String[] args) {
        long groupID = sender.getGroupID();
        if (groupID <= 0) {
            sender.sendMessage("请在群聊中使用。");
            return;
        }
        if (args.length <= 2 || "now".equalsIgnoreCase(args[2])) {
            command.service().triggerDailyReview(groupID);
            sender.sendMessage("当前群每日复盘已开始，完成后会把结果写入短期记忆。");
            return;
        }
        sender.sendMessage("用法：/role review [now]");
    }

    void handleAttention(CommandSender sender,String[] args) {
        long groupID = sender.getGroupID();
        if (groupID <= 0) {
            sender.sendMessage("请在群聊中使用。");
            return;
        }
        if (args.length > 2 && "reset".equalsIgnoreCase(args[2])) {
            command.service().getAttentionService().reset(groupID);
            sender.sendMessage("当前群注意力已重置。");
            return;
        }
        double score = command.service().getAttentionService().score(groupID);
        sender.sendMessage("当前群注意力："+String.format(java.util.Locale.ROOT,"%.2f",score)
                +"\n0.5 为基线，低于阈值会降低非直接消息的接话概率。");
    }
}