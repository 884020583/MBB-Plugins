package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Event.GroupMessageEvent;
import org.moboxlab.moboxbot.API.OneBot.OneBotClient;

import java.util.Locale;

/**
 * 角色发言指向与引用解析
 *
 * 负责艾特、引用、角色别名、群员身份、关系称呼和被引用消息读取。
 */
public class RoleplayAddressService {
    private static final int QUOTE_CONTEXT_MAX_CHARS = 60;

    private final RoleplayService service;

    public RoleplayAddressService(RoleplayService service) {
        this.service = service;
    }

    boolean isAddressedToOtherRole(GroupMessageEvent event,String content,long selfID) {
        return mentionsOtherRole(event,content,selfID) && !mentionsSelfRole(event,content,selfID);
    }

    boolean isMultiRoleAddress(GroupMessageEvent event,String content,long selfID) {
        return mentionsOtherRole(event,content,selfID) && mentionsSelfRole(event,content,selfID);
    }

    boolean mentionsSelfRole(GroupMessageEvent event,String content,long selfID) {
        if (isMentioningSelf(event,selfID)) return true;
        String text = service.normalizeAddressText(content);
        if (service.containsRoleAlias(text,service.persona().name)) return true;
        for (String alias : service.persona().aliases) {
            if (service.containsRoleAlias(text,alias)) return true;
        }
        return service.containsRoleAlias(text,service.plugin().getServer().getBotName());
    }

    boolean mentionsOtherRole(GroupMessageEvent event,String content,long selfID) {
        if (event != null && event.getMessage() != null) {
            JSONArray message = event.getMessage();
            for (int i = 0; i < message.size(); i++) {
                JSONObject segment = message.getJSONObject(i);
                if (segment == null || !"at".equals(segment.getString("type"))) continue;
                JSONObject data = segment.getJSONObject("data");
                long atID = data == null ? 0L : data.getLongValue("qq");
                //只有艾特到另一个角色机器人才算"提到其他角色"，普通群友不算
                if (atID > 0 && atID != selfID && service.containsCSVLong(service.config().otherRoleBotQQs,atID)) {
                    return true;
                }
            }
        }
        String text = service.normalizeAddressText(content);
        String names = service.config().otherRoleBotNames;
        for (String item : names.split(",")) {
            String name = item.trim();
            if (name.isEmpty() || service.isOwnRoleName(name)) continue;
            if (service.containsRoleAlias(text,name)) return true;
        }
        return false;
    }

    boolean mentionsOtherMember(GroupMessageEvent event,long selfID) {
        if (event == null || event.getMessage() == null) return false;
        JSONArray message = event.getMessage();
        for (int i = 0; i < message.size(); i++) {
            JSONObject segment = message.getJSONObject(i);
            if (segment == null || !"at".equals(segment.getString("type"))) continue;
            JSONObject data = segment.getJSONObject("data");
            long atID = data == null ? 0L : data.getLongValue("qq");
            if (atID > 0 && atID != selfID && !service.containsCSVLong(service.config().otherRoleBotQQs,atID)) {
                return true;
            }
        }
        return false;
    }

    boolean isDirect(GroupMessageEvent event,String content,long selfID) {
        boolean mentionedSelf = false;
        boolean mentionedOther = false;
        JSONArray message = event.getMessage();
        if (message != null) {
            long lastBot = service.state(event.getGroupID()).lastBotMessageID;
            for (int i = 0; i < message.size(); i++) {
                JSONObject segment = message.getJSONObject(i);
                if (segment == null) continue;
                JSONObject data = segment.getJSONObject("data");
                if ("at".equals(segment.getString("type")) && data != null) {
                    long atID = data.getLongValue("qq");
                    if (atID == selfID) mentionedSelf = true;
                    else if (atID > 0) mentionedOther = true;
                }
                if ("reply".equals(segment.getString("type")) && data != null && lastBot > 0
                        && data.getLongValue("id") == lastBot) return true;
            }
        }
        if (mentionedSelf) return true;
        if (mentionedOther) return false;

        String text = content == null ? "" : content.trim();
        if (service.startsWithAlias(text,service.persona().name) || service.startsWithAlias(text,service.plugin().getServer().getBotName())) return true;
        for (String alias : service.persona().aliases) {
            if (service.startsWithAlias(text,alias)) return true;
        }

        String raw = event.getRawMessage();
        if (raw != null && selfID > 0) {
            if (raw.contains("[CQ:at,qq="+selfID+"]") || raw.contains("[CQ:at,qq=\""+selfID+"\"]")) return true;
        }
        return false;
    }

    boolean isQuotingSelf(GroupMessageEvent event,long selfID) {
        JSONArray message = event.getMessage();
        if (message == null) return false;
        long lastBot = service.state(event.getGroupID()).lastBotMessageID;
        if (lastBot <= 0) return false;
        for (int i = 0; i < message.size(); i++) {
            JSONObject segment = message.getJSONObject(i);
            if (segment == null || !"reply".equals(segment.getString("type"))) continue;
            JSONObject data = segment.getJSONObject("data");
            if (data != null && data.getLongValue("id") == lastBot) return true;
        }
        return false;
    }

    long quotedMessageID(GroupMessageEvent event) {
        JSONArray message = event == null ? null : event.getMessage();
        if (message == null) return 0L;
        for (int i = 0; i < message.size(); i++) {
            JSONObject segment = message.getJSONObject(i);
            if (segment == null || !"reply".equals(segment.getString("type"))) continue;
            JSONObject data = segment.getJSONObject("data");
            if (data == null) continue;
            long id = data.getLongValue("id");
            if (id > 0) return id;
        }
        return 0L;
    }

    String quotedText(long groupID,GroupMessageEvent event) {
        long quotedID = quotedMessageID(event);
        if (quotedID <= 0) return "";
        String userName = "";
        String content = "";
        JSONObject row = service.storage().queryOne(
                "SELECT `userName`,`content` FROM `"+RoleplayService.MSG_TABLE+"` WHERE `groupID`=? AND `messageID`=? LIMIT 1",
                groupID,quotedID);
        if (row != null) {
            userName = service.safe(row.getString("userName"));
            content = service.safe(row.getString("content"));
        }
        if (content.trim().isEmpty()) {
            JSONObject fetched = fetchQuotedMessage(quotedID);
            if (fetched != null) {
                userName = quotedUserName(fetched,userName);
                content = service.extractContent(fetched.getJSONArray("message"));
                if (content.trim().isEmpty()) content = service.safe(fetched.getString("raw_message"));
            }
        }
        content = content == null ? "" : content.replaceAll("\\s+"," ").trim();
        if (content.isEmpty()) return "";
        if (content.length() > QUOTE_CONTEXT_MAX_CHARS) {
            content = content.substring(0,QUOTE_CONTEXT_MAX_CHARS)+"...";
        }
        return "[引用 "+(userName.isEmpty() ? "某条消息" : userName)+"："+content+"] ";
    }

    JSONObject fetchQuotedMessage(long messageID) {
        OneBotClient client = service.plugin().getServer().getOneBotClient();
        if (client == null) return null;
        try {
            JSONObject params = new JSONObject(true);
            params.put("message_id",messageID);
            JSONObject response = client.callAction("get_msg",params);
            if (response == null || response.getIntValue("retcode") != 0) return null;
            return response.getJSONObject("data");
        } catch (Exception e) {
            return null;
        }
    }

    String quotedUserName(JSONObject message,String fallback) {
        if (message == null) return fallback;
        JSONObject sender = message.getJSONObject("sender");
        if (sender != null) {
            String card = service.safe(sender.getString("card"));
            if (!card.isEmpty()) return card;
            String nickname = service.safe(sender.getString("nickname"));
            if (!nickname.isEmpty()) return nickname;
        }
        long userID = message.getLongValue("user_id");
        return userID > 0 ? String.valueOf(userID) : fallback;
    }

    boolean isMentioningSelf(GroupMessageEvent event,long selfID) {
        if (selfID <= 0) return false;
        JSONArray message = event.getMessage();
        if (message == null) return false;
        for (int i = 0; i < message.size(); i++) {
            JSONObject segment = message.getJSONObject(i);
            if (segment == null || !"at".equals(segment.getString("type"))) continue;
            JSONObject data = segment.getJSONObject("data");
            if (data != null && data.getLongValue("qq") == selfID) return true;
        }
        return false;
    }

    String relationshipLabel(GroupMessageEvent event,boolean otherRoleBot) {
        if (otherRoleBot) return "其他角色机器人";
        if (event != null && service.isOwner(event.getUserID())) return "妈妈";
        JSONObject sender = event == null ? null : event.getSender();
        String role = sender == null ? "" : service.safe(sender.getString("role")).trim().toLowerCase(Locale.CHINA);
        if ("owner".equals(role) || "admin".equals(role)) return "老师";
        return "朋友";
    }

    boolean isOtherRoleBot(GroupMessageEvent event,long selfID) {
        if (event == null || event.getUserID() <= 0 || event.getUserID() == selfID) return false;
        return service.containsCSVLong(service.config().otherRoleBotQQs,event.getUserID());
    }

    boolean isRoleParticipantUser(long userID) {        return userID > 0 && service.containsCSVLong(service.config().otherRoleBotQQs,userID);
    }
}