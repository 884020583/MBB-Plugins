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
 * Roleplay 命令处理器：RoleplayMemberCommandHandler
 */
public class RoleplayMemberCommandHandler {
    private final RoleplayCommand command;

    public RoleplayMemberCommandHandler(RoleplayCommand command) {
        this.command = command;
    }

    void handleMember(CommandSender sender,String[] args) {
        long groupID = sender.getGroupID();
        if (groupID <= 0) {
            sender.sendMessage("请在群聊中使用。");
            return;
        }
        String action = args.length > 2 ? args[2].toLowerCase() : "";
        if ("clear".equals(action)) {
            if (args.length < 4) {
                sender.sendMessage("用法：/role member clear <QQ>");
                return;
            }
            long targetID = command.parseUserID(args[3]);
            if (targetID <= 0) {
                sender.sendMessage("QQ 格式不正确。");
                return;
            }
            sender.sendMessage(command.service().getMemberService().remove(groupID,targetID)
                    ? "已清空对 "+targetID+" 的个人印象。"
                    : "没有找到 "+targetID+" 的个人印象。");
            return;
        }
        long userID = sender.getUserID();
        if (args.length > 2 && !action.isEmpty()) {
            userID = command.parseUserID(args[2]);
            if (userID <= 0) {
                sender.sendMessage("QQ 格式不正确。");
                return;
            }
        }
        JSONObject profile = command.service().getMemberService().profile(groupID,userID);
        if (profile == null) {
            sender.sendMessage("还没有对 "+userID+" 的个人印象。");
            return;
        }
        sender.sendMessage("对 "+userID+"（"+profile.getString("userName")+"）的个人印象："
                +"\n称呼："+command.orDash(profile.getString("alias"))
                +"\n喜欢："+command.orDash(profile.getString("likes"))
                +"\n不喜欢："+command.orDash(profile.getString("dislikes"))
                +"\n相处方式："+command.orDash(profile.getString("notes")));
    }

    void handleHelp(CommandSender sender,String[] args) {
        String category = args.length > 2 ? args[2].toLowerCase() : "all";
        if ("basic".equals(category)) {
            sender.sendMessage("基础命令："
                    +"\n/role status"
                    +"\n/role enable [群号]"
                    +"\n/role disable [群号]"
                    +"\n/role groups"
                    +"\n/role reload"
                    +"\n/role config [repair]");
            return;
        }
        if ("quiet".equals(category)) {
            sender.sendMessage("免打扰："
                    +"\n/role quiet add [QQ]"
                    +"\n/role quiet remove [QQ]"
                    +"\n/role quiet list"
                    +"\n/role quiet on|off"
                    +"\n/role quiet clear");
            return;
        }
        if ("blacklist".equals(category)) {
            sender.sendMessage("黑名单："
                    +"\n/role blacklist list"
                    +"\n/role blacklist add <QQ> [原因]"
                    +"\n/role blacklist remove <QQ>"
                    +"\n/role blacklist clear");
            return;
        }
        if ("emotion".equals(category)) {
            sender.sendMessage("情绪与关系："
                    +"\n/role mood [reset]"
                    +"\n/role emotion [QQ] [页码]"
                    +"\n/role emotion reset [QQ]"
                    +"\n/role emotion log [页码]"
                    +"\n/role emotion affinity set <QQ> <0-100>"
                    +"\n/role emotion reason set|clear <QQ> [原因]");
            return;
        }
        if ("memory".equals(category)) {
            sender.sendMessage("记忆："
                    +"\n/role memory [页码|merge|backup|backups|restore <文件名>]"
                    +"\n/role memory timeline [页码]"
                    +"\n/role memory conflicts [条数]"
                    +"\n/role gmemory [页码|backup|merge|group ...]"
                    +"\n/role forget"
                    +"\n/role review [now]");
            return;
        }
        if ("role".equals(category)) {
            sender.sendMessage("角色与机器人："
                    +"\n/role persona [文件名]"
                    +"\n/role persona reset <文件名>"
                    +"\n/role bot [status|chance|max|qq|name] ...");
            return;
        }
        if ("content".equals(category)) {
            sender.sendMessage("语料与知识库："
                    +"\n/role speech [stats|reload|search <文本>|on|off]"
                    +"\n/role kb [list|reload|search <文本>|on|off]");
            return;
        }
        if ("feedback".equals(category)) {
            sender.sendMessage("反馈与表达："
                    +"\n/role feedback [reset]"
                    +"\n/role expression list [pending|approved|rejected]"
                    +"\n/role expression promote <ID>"
                    +"\n/role expression reject <ID>"
                    +"\n/role expression clear"
                    +"\n/role attention [reset]"
                    +"\n/role trace [条数]"
                    +"\n/role eval [list|run <id|all>]");
            return;
        }
        sender.sendMessage("MBB-Roleplay 命令分类："
                +"\n/role help basic        基础与开关"
                +"\n/role help quiet        免打扰"
                +"\n/role help blacklist    黑名单"
                +"\n/role help emotion      情绪与关系"
                +"\n/role help memory       记忆"
                +"\n/role help role         角色与机器人"
                +"\n/role help content      语料与知识库"
                +"\n/role help feedback     反馈与表达"
                +"\n/role help all          查看全部命令");
        if ("all".equals(category)) {
            sender.sendMessage("完整命令表较长，请按上面的分类逐项查看。");
        }
    }
}
