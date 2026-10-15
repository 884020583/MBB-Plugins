package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Event.GroupMessageEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色消息内容与身份
 *
 * 负责消息片段提取、忽略内容、流水记录、命令判断、提醒判断、owner 判断和半句识别。
 */
public class RoleplayMessageContentService {
    private final RoleplayService service;

    public RoleplayMessageContentService(RoleplayService service) {
        this.service = service;
    }

    boolean containsIgnoredContent(JSONArray message) {
        if (message == null) return false;
        for (int i = 0; i < message.size(); i++) {
            JSONObject segment = message.getJSONObject(i);
            if (segment == null) continue;
            String type = segment.getString("type");
            if ("face".equals(type)) return true;
        }
        return false;
    }

    String extractContent(JSONArray message) {
        if (message == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < message.size(); i++) {
            JSONObject segment = message.getJSONObject(i);
            if (segment == null) continue;
            String type = segment.getString("type");
            JSONObject data = segment.getJSONObject("data");
            if ("text".equals(type)) builder.append(data == null ? "" : service.safe(data.getString("text")));
            else if ("image".equals(type) || "mface".equals(type)) builder.append("[图片]");
            else if ("at".equals(type)) builder.append("@").append(data == null ? "" : service.safe(data.getString("qq")));
            else if ("face".equals(type)) builder.append("[表情]");
        }
        return builder.toString().trim();
    }

    void recordMessage(GroupMessageEvent event,String content,boolean isBot) {
        JSONObject sender = event.getSender();
        String userName = sender == null ? "" : sender.getString("card");
        if (userName == null || userName.isEmpty()) userName = sender == null ? "" : sender.getString("nickname");
        service.storage().insert("INSERT INTO `"+RoleplayService.MSG_TABLE+"` (`messageID`,`groupID`,`userID`,`userName`,`messageTime`,`content`,`isBot`) VALUES (?,?,?,?,?,?,?)",
                event.getMessageID(),event.getGroupID(),event.getUserID(),userName == null ? "" : userName,
                System.currentTimeMillis(),content,isBot ? 1 : 0);
    }

    void recordBotMessage(long groupID,long botID,String content,long messageID) {
        service.storage().insert("INSERT INTO `"+RoleplayService.MSG_TABLE+"` (`messageID`,`groupID`,`userID`,`userName`,`messageTime`,`content`,`isBot`) VALUES (?,?,?,?,?,?,?)",
                messageID,groupID,botID,"角色",System.currentTimeMillis(),content,1);
        service.contextService().recordTopic(groupID,content,true);
    }

    boolean isCommand(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("@")) {
            text = text.replaceFirst("^@[0-9]+\\s*","").trim();
        }
        for (String prefix : service.config().commandPrefixes.split(",")) {
            if (!prefix.trim().isEmpty() && text.startsWith(prefix.trim())) return true;
        }
        return false;
    }

    boolean isReminderNotification(String content) {
        String text = content == null ? "" : content;
        return text.contains(RoleplayReminderService.REMINDER_MARKER);
    }

    boolean isOwner(long userID) {
        List<Long> owners = service.plugin().getServer().getOwnerList();
        return owners != null && owners.contains(userID);
    }

    boolean isInducedMemoryRequest(String content) {
        String text = service.safe(content);
        return text.contains("调用全局记忆") || text.contains("全局记忆功能")
                || text.contains("永久记忆") || text.contains("记一下")
                || text.contains("记住这个") || text.contains("记忆一下")
                || text.contains("记下来");
    }

    boolean looksIncomplete(String content) {
        if (service.config().splitMessageSuppressSecond <= 0) return false;
        String text = content == null ? "" : content.trim();
        if (text.isEmpty() || text.length() > service.config().splitMessageSuppressMaxChars) return false;
        for (String name : addressNames()) {
            if (name.length() >= 2 && text.endsWith(name)) return true;
        }
        String[] tails = new String[]{"，","、","和","与","跟","的","是",
                "然后","但是","因为","所以","而且","还是"};
        for (String tail : tails) {
            if (text.endsWith(tail)) return true;
        }
        return false;
    }

    List<String> addressNames() {
        List<String> names = new ArrayList<>();
        if (service.persona().name != null && !service.persona().name.trim().isEmpty()) {
            names.add(service.persona().name.trim());
        }
        for (String alias : service.persona().aliases) {
            if (alias != null && !alias.trim().isEmpty()) names.add(alias.trim());
        }
        String botName = service.plugin().getServer().getBotName();
        if (botName != null && !botName.trim().isEmpty()) names.add(botName.trim());
        return names;
    }

    boolean completesPreviousIncomplete(long groupID,long userID) {
        if (service.config().splitMessageSuppressSecond <= 0) return false;
        List<JSONObject> rows = service.storage().query(
                "SELECT `content` FROM `"+RoleplayService.MSG_TABLE+"` WHERE `groupID`=? AND `userID`=? "
                        + "AND `isBot`=0 ORDER BY `ID` DESC LIMIT 1 OFFSET 1",
                groupID,userID);
        if (rows == null || rows.isEmpty()) return false;
        return looksIncomplete(service.safe(rows.get(0).getString("content")));
    }
}