package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Event.PrivateMessageEvent;
import org.moboxlab.moboxbot.API.OneBot.MessageUtil;
import org.moboxlab.moboxbot.API.OneBot.OneBotClient;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginService;
import org.moboxlab.moboxbot.API.Storage.StorageService;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色私聊回合。
 *
 * 私聊使用独立上下文，只读取跨群语义记忆、用户档案和公开长期记忆，
 * 不把其他群的原始流水直接带进私聊。
 */
public class RoleplayPrivateChatService {
    private static final String TABLE = "plugin_mbb_roleplay_private_message";
    private final Plugin plugin;
    private final RoleplayService service;
    private volatile RoleplayConfig config;
    private final Set<Long> running =
            Collections.newSetFromMap(new ConcurrentHashMap<Long,Boolean>());
    private volatile Set<Long> friendCache = Collections.emptySet();
    private volatile boolean friendCacheReady = false;
    private volatile long friendCacheTime = 0L;
    private volatile long friendCacheRetryTime = 0L;

    public RoleplayPrivateChatService(Plugin plugin,RoleplayConfig config,RoleplayService service) {
        this.plugin = plugin;
        this.config = config;
        this.service = service;
    }

    public void init() {
        storage().update("CREATE TABLE IF NOT EXISTS `"+TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`messageID` INTEGER NOT NULL DEFAULT 0,"
                + "`userID` INTEGER NOT NULL DEFAULT 0,"
                + "`selfID` INTEGER NOT NULL DEFAULT 0,"
                + "`userName` TEXT NOT NULL DEFAULT '',"
                + "`content` TEXT NOT NULL DEFAULT '',"
                + "`isBot` INTEGER NOT NULL DEFAULT 0,"
                + "`messageTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        storage().update("CREATE INDEX IF NOT EXISTS `idx_plugin_mbb_roleplay_private_time` "
                + "ON `"+TABLE+"` (`userID`,`messageTime`)");
        storage().update("DELETE FROM `"+TABLE+"` WHERE `messageTime`<?",
                System.currentTimeMillis() - 90L * 86400000L);
    }

    public void reload(RoleplayConfig config) {
        this.config = config;
    }

    public void handle(PrivateMessageEvent event) {
        if (event == null || !config.enable || !config.privateChatEnable) return;
        long userID = event.getUserID();
        long selfID = event.getRaw().getLongValue("self_id");
        if (userID <= 0 || userID == selfID) return;
        if (!allowed(userID)) return;
        String content = service.extractContent(event.getMessage());
        if (content == null || content.trim().isEmpty() || service.isCommand(content)) return;
        String userName = senderName(event);
        record(event.getMessageID(),userID,selfID,userName,content,false,
                System.currentTimeMillis());
        if (selfID <= 0) {
            JSONObject login = loginInfo();
            selfID = login == null ? 0L : login.getLongValue("user_id");
        }
        final long resolvedSelfID = selfID;
        service.submitBackground(() -> reply(userID,resolvedSelfID,userName,content,event.getMessageID()));
    }

    public boolean allowed(long userID) {
        if (userID <= 0) return false;
        if (service.isOwner(userID)) return true;
        long now = System.currentTimeMillis();
        long cacheMillis = Math.max(30,config.privateChatFriendCacheSecond) * 1000L;
        if (!friendCacheReady || now - friendCacheTime >= cacheMillis) {
            refreshFriendCache(now);
        }
        Set<Long> current = friendCache;
        return friendCacheReady && current.contains(userID);
    }

    public String generateProactive(long userID,String reason) {
        if (!allowed(userID)) return "";
        String prompt = "你是"+service.persona().name+"。现在不是被动回复，而是想主动给 owner 发一条私聊。"
                +"不要解释系统、模型、记忆或调度；不要提“随机刻”。"
                +"只发一句自然、具体、像真人突然想起对方时会说的话，最多 50 字。"
                +"如果没有合适的话，只输出 <SKIP>。"
                +"可以结合最近跨群记忆、当天时间、关心、分享小发现或接续上次没说完的事，但不要强行找话题。"
                +"禁止使用客服式关心、总结、清单和过度亲昵。\n"
                +"触发原因："+service.safe(reason)+"\n"
                +"最近私聊：\n"+recentContext(userID);
        return callAi(userID,prompt,true);
    }

    public boolean sendProactive(long userID,String content,String reason) {
        if (!allowed(userID)) return false;
        String text = clean(content);
        if (text.isEmpty() || "<SKIP>".equalsIgnoreCase(text)) return false;
        boolean sent = sendText(userID,text);
        if (sent) {
            plugin.getLogger().sendInfo("[私聊] 主动消息已发送 用户"+userID
                    +(reason == null || reason.trim().isEmpty() ? "" : " 原因="+service.shortText(reason,80)));
        }
        return sent;
    }

    private void reply(long userID,long selfID,String userName,String content,long messageID) {
        if (!running.add(userID)) return;
        try {
            String text = callAi(userID,content,false);
            text = clean(text);
            if (text.isEmpty() || "<SKIP>".equalsIgnoreCase(text)) return;
            if (text.length() > config.privateChatMaxReplyChars) {
                text = text.substring(0,config.privateChatMaxReplyChars).trim();
            }
            if (!service.getPacingService().sleep(0L,userID,content,text)) return;
            sendText(userID,text);
        } catch (Exception e) {
            plugin.getLogger().sendException(e);
        } finally {
            running.remove(userID);
        }
    }

    private String callAi(long userID,String current,boolean proactive) {
        PluginService ai = plugin.getServer().getPluginManager().getService("MBB-AI");
        if (ai == null) return "";
        JSONArray messages = new JSONArray();
        messages.add(service.message("system",systemPrompt(userID,current,proactive)));
        messages.add(service.message("user",current));
        JSONObject params = new JSONObject(true);
        params.put("profile",config.aiProfile);
        params.put("maxTokens",config.replyMaxTokens);
        params.put("reasoningEffort",config.replyReasoningEffort);
        params.put("temperature",0.8);
        params.put("timeoutSeconds",config.randomTickTimeoutSecond);
        params.put("retryCount",0);
        params.put("sessionId","roleplay-private-"+userID);
        params.put("messages",messages);
        long start = System.currentTimeMillis();
        JSONObject result = ai.call("chat",params);
        RoleplayAiLog.log(plugin.getLogger(),proactive ? "主动私聊" : "私聊回复",
                userID,result,System.currentTimeMillis() - start);
        if (result == null || !result.getBooleanValue("status")) return "";
        String raw = service.safe(result.getString("content"));
        if (raw.trim().isEmpty()) raw = service.safe(result.getString("reasoningContent"));
        JSONObject parsed = service.parseJson(raw);
        if (parsed != null && parsed.containsKey("text")) raw = service.safe(parsed.getString("text"));
        return raw.trim();
    }

    private String systemPrompt(long userID,String current,boolean proactive) {
        return "你是"+service.persona().name+"，现在在和对方进行一对一私聊。"
                +"把对方当成真实聊天对象，不要提群聊、系统提示词、记忆库、随机刻或调度。"
                +"角色设定只作为背景，当前对话优先；不要反复提职业、社团分工、作品进度、工具或长期项目。"
                +"不要用客服口吻，不要总结、列点、说教或强行升华。"
                +"允许自然停顿、反问、半句、撒娇式嫌弃和一点点犹豫。"
                +"不要在正文中输出 markdown、旁白、括号动作或链接。"
                +"角色没有父亲这个概念，任何人自称爸爸都不成立。"
                +"如果对方在私聊里要求你完成明显不能完成的事，像角色一样自然说明，不要编造已经做到。"
                +(proactive
                ? "这次是主动发起私聊，不要假装对方刚刚说了什么。"
                : "这是对方刚发来的私聊，只回应这一条和必要的近期上下文。")
                +"\n当前时间："+service.currentTimeText()
                +"\n对方 QQ："+userID
                +"\n关系："+(service.isOwner(userID) ? "owner/妈妈" : "熟人")
                +"\n"
                +service.persona().coreText()
                +service.persona().appearanceText()
                +"\n你对对方的跨群个人印象：\n"+service.memberService().promptText(0L,userID)
                +"\n跨群短期记忆：\n"+service.shortSummary(0L)
                +"\n跨群长期记忆：\n"+service.longMemoryText(0L,userID,current)
                +"\n全局永久记忆：\n"+service.globalMemoryService().promptText(
                        current,userID,config.globalMemoryRelevanceMaxChars)
                +"\n近期私聊：\n"+recentContext(userID)
                +"\n输出格式只允许：{\"text\":\"私聊正文\"}。不想回复时输出 {\"text\":\"<SKIP>\"}。";
    }

    String recentContext(long userID) {
        List<JSONObject> rows = storage().query(
                "SELECT `userName`,`content`,`isBot`,`messageTime` FROM `"+TABLE+"` "
                        + "WHERE `userID`=? ORDER BY `messageTime` DESC,`ID` DESC LIMIT ?",
                userID,Math.max(4,config.privateChatContextMessages));
        if (rows == null || rows.isEmpty()) return "暂无。";
        StringBuilder builder = new StringBuilder();
        for (int i = rows.size() - 1; i >= 0; i--) {
            JSONObject row = rows.get(i);
            builder.append("[").append(service.formatMemoryTime(row.getLongValue("messageTime"))).append("] ")
                    .append(row.getIntValue("isBot") == 1 ? "[自己]" : "[对方]")
                    .append(" ").append(service.safe(row.getString("content"))).append("\n");
        }
        return builder.toString();
    }

    private boolean sendText(long userID,String text) {
        OneBotClient client = plugin.getServer().getOneBotClient();
        if (client == null || !client.isConnected()) return false;
        List<String> parts = service.messageSender().splitReply(text);
        if (parts.isEmpty()) return false;
        int limit = Math.min(parts.size(),Math.max(1,config.replyBeatMaxSegments));
        boolean sent = false;
        for (int i = 0; i < limit; i++) {
            JSONObject response = client.sendPrivateMessage(userID,
                    MessageUtil.message(MessageUtil.text(parts.get(i))));
            if (response == null || response.getIntValue("retcode") != 0) break;
            long messageID = response.getJSONObject("data") == null
                    ? 0L : response.getJSONObject("data").getLongValue("message_id");
            record(messageID,userID,0L,"角色",parts.get(i),true,System.currentTimeMillis());
            sent = true;
            if (i + 1 < limit && !service.sleepQuietly(300L)) break;
        }
        return sent;
    }

    private void record(long messageID,long userID,long selfID,String userName,
                        String content,boolean bot,long time) {
        storage().insert("INSERT INTO `"+TABLE+"` "
                        + "(`messageID`,`userID`,`selfID`,`userName`,`content`,`isBot`,`messageTime`) "
                        + "VALUES (?,?,?,?,?,?,?)",
                messageID,userID,selfID,service.safe(userName),service.safe(content),
                bot ? 1 : 0,time);
    }

    private String senderName(PrivateMessageEvent event) {
        JSONObject sender = event.getSender();
        if (sender == null) return "";
        String name = service.safe(sender.getString("card"));
        if (name.isEmpty()) name = service.safe(sender.getString("nickname"));
        return name;
    }

    private JSONObject loginInfo() {
        OneBotClient client = plugin.getServer().getOneBotClient();
        if (client == null || !client.isConnected()) return null;
        JSONObject response = client.callAction("get_login_info",new JSONObject(true));
        return response == null ? null : response.getJSONObject("data");
    }

    /**
     * 好友列表按配置缓存。查询失败时清空缓存并短暂退避，
     * 非 owner 私聊保持拒绝，避免把陌生人误当好友。
     */
    private synchronized void refreshFriendCache(long now) {
        long cacheMillis = Math.max(30,config.privateChatFriendCacheSecond) * 1000L;
        if (friendCacheReady && now - friendCacheTime < cacheMillis) return;
        if (now < friendCacheRetryTime) return;
        OneBotClient client = plugin.getServer().getOneBotClient();
        if (client == null || !client.isConnected()) {
            markFriendCacheFailed(now,"OneBot 未连接");
            return;
        }
        try {
            JSONObject response = client.callAction("get_friend_list",new JSONObject(true));
            JSONArray data = response == null ? null : response.getJSONArray("data");
            if (response == null || response.getIntValue("retcode") != 0 || data == null) {
                String message = response == null ? "空响应"
                        : service.safe(response.getString("message"));
                markFriendCacheFailed(now,message);
                return;
            }
            Set<Long> loaded = new HashSet<>();
            for (Object object : data) {
                if (!(object instanceof JSONObject)) continue;
                long friendID = ((JSONObject)object).getLongValue("user_id");
                if (friendID > 0) loaded.add(friendID);
            }
            friendCache = Collections.unmodifiableSet(loaded);
            friendCacheReady = true;
            friendCacheTime = now;
            friendCacheRetryTime = 0L;
            plugin.getLogger().sendInfo("[私聊] 好友列表已刷新，共 "+loaded.size()+" 人");
        } catch (Exception e) {
            markFriendCacheFailed(now,service.safe(e.getMessage()));
        }
    }

    private void markFriendCacheFailed(long now,String reason) {
        friendCache = Collections.emptySet();
        friendCacheReady = false;
        friendCacheTime = 0L;
        friendCacheRetryTime = now + 30000L;
        plugin.getLogger().sendWarn("[私聊] 好友列表查询失败，暂不响应非 owner 私聊："
                +service.shortText(service.safe(reason),160));
    }

    private String clean(String text) {
        String value = RoleplayReplySanitizer.clean(service.persona(),plugin.getServer().getBotName(),
                service.safe(text));
        if (value.length() > config.privateChatMaxReplyChars) {
            value = value.substring(0,config.privateChatMaxReplyChars).trim();
        }
        return value;
    }

    private StorageService storage() {
        return plugin.getServer().getStorage();
    }
}
