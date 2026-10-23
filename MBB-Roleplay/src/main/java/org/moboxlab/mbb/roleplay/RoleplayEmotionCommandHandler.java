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
 * Roleplay 命令处理器：RoleplayEmotionCommandHandler
 */
public class RoleplayEmotionCommandHandler {
    private final RoleplayCommand command;

    public RoleplayEmotionCommandHandler(RoleplayCommand command) {
        this.command = command;
    }

    void handleMood(CommandSender sender,String[] args) {
        long groupID = sender.getGroupID();
        if (groupID <= 0) {
            sender.sendMessage("请在群聊中使用，或指定群号。");
            return;
        }
        RoleplayEmotionService emotionService = command.service().getEmotionService();
        if (args.length > 2 && "reset".equalsIgnoreCase(args[2])) {
            if (!sender.hasPermission(CommandPermission.OWNER)) {
                sender.sendMessage("重置群情绪仅 owner 可用。");
                return;
            }
            emotionService.resetMood(groupID);
            sender.sendMessage("当前群情绪已重置。");
            return;
        }
        int page = command.parsePage(args,2);
        JSONObject data = emotionService.moodStats(groupID,100);
        byte[] image = RoleplayEmotionImageRenderer.render(data,page,8);
        if (image != null) {
            sender.sendImage(ImageUtil.toBase64Uri(image));
            return;
        }
        sender.sendMessage("当前群情绪："
                +data.getJSONObject("mood").getString("label")
                +"\n心情："+data.getJSONObject("mood").getIntValue("valence")
                +"\n精力："+data.getJSONObject("mood").getIntValue("energy")
                +"\n耐心："+data.getJSONObject("mood").getIntValue("patience"));
    }

    void handleEmotion(CommandSender sender,String[] args) {
        RoleplayEmotionService emotionService = command.service().getEmotionService();
        long groupID = sender.getGroupID();
        if (groupID <= 0) {
            sender.sendMessage("请在群聊中使用，或指定群号。");
            return;
        }
        if (args.length > 2 && "reason".equalsIgnoreCase(args[2])) {
            handleEmotionReason(sender,args,groupID);
            return;
        }
        if (args.length > 2 && "affinity".equalsIgnoreCase(args[2])) {
            handleEmotionAffinity(sender,args,groupID);
            return;
        }
        if (args.length > 2 && "log".equalsIgnoreCase(args[2])) {
            int page = command.parsePage(args,3);
            JSONObject data = emotionService.moodStats(groupID,100);
            byte[] image = RoleplayEmotionImageRenderer.render(data,page,8);
            if (image != null) {
                sender.sendImage(ImageUtil.toBase64Uri(image));
                return;
            }
            sender.sendMessage("当前群情绪事件已输出到控制台日志。");
            return;
        }
        long userID = sender.getUserID();
        int page = 1;
        if (args.length > 2) {
            if ("reset".equalsIgnoreCase(args[2])) {
                if (args.length > 3) userID = command.parseUserID(args[3]);
                if (userID <= 0) {
                    sender.sendMessage("QQ 格式不正确。");
                    return;
                }
                if (!sender.hasPermission(CommandPermission.OWNER)
                        && userID != sender.getUserID()) {
                    sender.sendMessage("重置其他人的关系仅 owner 可用。");
                    return;
                }
                emotionService.resetRelation(groupID,userID);
                sender.sendMessage("用户 "+userID+" 的关系状态已重置。");
                return;
            }
            long parsed = command.parseUserID(args[2]);
            if (parsed > 0) userID = parsed;
            page = userID == parsed ? command.parsePage(args,3) : command.parsePage(args,2);
        }
        if (userID <= 0) {
            sender.sendMessage("QQ 格式不正确。");
            return;
        }
        JSONObject data = emotionService.emotionStats(groupID,userID,100);
        byte[] image = RoleplayEmotionImageRenderer.render(data,page,8);
        if (image != null) {
            sender.sendImage(ImageUtil.toBase64Uri(image));
            return;
        }
        JSONObject relation = data.getJSONObject("relation");
        sender.sendMessage("用户关系 QQ："+userID
                +"\n态度："+relation.getString("emotionLabel")
                +"\n好感："+command.formatDecimal(relation.getDoubleValue("affinity"))
                +"\n信任："+command.formatDecimal(relation.getDoubleValue("trust"))
                +"\n厌烦："+command.formatDecimal(relation.getDoubleValue("annoyance"))
                +"\n原因："+relation.getString("emotionReason"));
    }

    void handleEmotionReason(CommandSender sender,String[] args,long groupID) {
        String action = args.length > 3 ? args[3].toLowerCase() : "help";
        if ("set".equals(action)) {
            if (!sender.hasPermission(CommandPermission.OWNER)) {
                sender.sendMessage("设置关系原因仅 owner 可用。");
                return;
            }
            if (args.length < 6) {
                sender.sendMessage("用法：/role emotion reason set <QQ|@群友> <原因>");
                return;
            }
            long userID = command.parseUserID(args[4]);
            String reason = command.joinArgs(args,5);
            if (userID <= 0 || reason.isEmpty()) {
                sender.sendMessage("QQ 或原因格式不正确。");
                return;
            }
            command.service().getEmotionService().setRelationReason(groupID,userID,reason);
            sender.sendMessage("已设置用户 "+userID+" 的情绪原因。");
            return;
        }
        if ("clear".equals(action)) {
            if (!sender.hasPermission(CommandPermission.OWNER)) {
                sender.sendMessage("清空关系原因仅 owner 可用。");
                return;
            }
            if (args.length < 5) {
                sender.sendMessage("用法：/role emotion reason clear <QQ|@群友>");
                return;
            }
            long userID = command.parseUserID(args[4]);
            if (userID <= 0) {
                sender.sendMessage("QQ 格式不正确。");
                return;
            }
            command.service().getEmotionService().clearRelationReason(groupID,userID);
            sender.sendMessage("已清空用户 "+userID+" 的情绪原因。");
            return;
        }
        sender.sendMessage("用法：/role emotion reason set <QQ|@群友> <原因> | clear <QQ|@群友>");
    }

    void handleEmotionAffinity(CommandSender sender,String[] args,long groupID) {
        if (args.length < 6 || !"set".equalsIgnoreCase(args[3])) {
            sender.sendMessage("用法：/role emotion affinity set <QQ|@群友> <0-100>");
            return;
        }
        long userID = command.parseUserID(args[4]);
        double affinity;
        try {
            affinity = Double.parseDouble(args[5]);
        } catch (Exception e) {
            sender.sendMessage("好感度格式不正确。");
            return;
        }
        if (userID <= 0 || affinity < 0 || affinity > 100) {
            sender.sendMessage("QQ 或好感度范围不正确。");
            return;
        }
        command.service().getEmotionService().setRelationAffinity(groupID,userID,affinity);
        sender.sendMessage("用户 "+userID+" 的好感度已设置为 "+command.formatDecimal(affinity)+"。");
    }
}
