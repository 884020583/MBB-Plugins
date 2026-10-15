package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.OneBot.MessageUtil;
import org.moboxlab.moboxbot.API.OneBot.OneBotClient;
import org.moboxlab.moboxbot.API.PluginService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 角色表情包
 *
 * 负责表情包标签提示、随机发送、旧标签解析和等待窗口内的情绪缓存。
 */
public class RoleplayStickerService {
    private static final Pattern STICKER_PAIR = Pattern.compile(
            "(?is)<\\s*sticker\\s*>(.*?)<\\s*/\\s*sticker\\s*>");
    private static final Pattern STICKER_OPEN = Pattern.compile(
            "(?is)<\\s*sticker\\s*/?\\s*>");

    private final RoleplayService service;
    private final Map<String,RoleplayService.RecentSticker> recentStickerMap = new ConcurrentHashMap<>();

    public RoleplayStickerService(RoleplayService service) {
        this.service = service;
    }

    RoleplayService.StickerMarkerResult extractStickerMarker(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new RoleplayService.StickerMarkerResult(false,"","");
        }
        Matcher pair = STICKER_PAIR.matcher(text);
        if (pair.find()) {
            String reply = (text.substring(0,pair.start())+text.substring(pair.end())).trim();
            return new RoleplayService.StickerMarkerResult(true,reply,pair.group(1).trim());
        }
        Matcher open = STICKER_OPEN.matcher(text);
        if (open.find()) {
            String reply = text.substring(0,open.start()).trim();
            String tags = text.substring(open.end()).trim();
            return new RoleplayService.StickerMarkerResult(true,reply,tags);
        }
        return new RoleplayService.StickerMarkerResult(false,text.trim(),"");
    }

    String stickerPrompt() {
        PluginService sticker = service.plugin().getServer().getPluginManager().getService("MBB-Sticker");
        if (sticker == null) return "";
        JSONObject result = sticker.call("tags",null);
        if (result == null || !result.getBooleanValue("status")) return "";
        JSONArray tags = result.getJSONArray("tags");
        if (tags == null || tags.isEmpty()) return "";
        StringBuilder builder = new StringBuilder("当前可用表情包标签：");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) builder.append(", ");
            builder.append(tags.getString(i));
        }
        builder.append("\n表情包是可选表达，不是每句话都必须带；只有情绪或场景明显合适时才偶尔使用，")
                .append("避免连续多条回复都发表情包。适合时输出 ")
                .append("{\"type\":\"sticker\",\"args\":{\"tags\":\"tag1,tag2\"}}；")
                .append("只能使用上面的标签，没有合适标签时不要输出。");
        return builder.toString();
    }

    void sendSticker(long groupID,long userID,String tags) {
        PluginService sticker = service.plugin().getServer().getPluginManager().getService("MBB-Sticker");
        if (sticker == null) return;
        JSONObject params = new JSONObject(true);
        JSONArray tagArray = new JSONArray();
        for (String item : service.safe(tags).split("[,，]")) {
            if (!item.trim().isEmpty()) tagArray.add(item.trim());
        }
        if (tagArray.isEmpty()) return;
        params.put("tags",tagArray);
        params.put("groupID",groupID);
        params.put("userID",userID);
        JSONObject result = sticker.call("random",params);
        if (result == null || !result.getBooleanValue("status")) return;
        OneBotClient client = service.plugin().getServer().getOneBotClient();
        if (client == null) return;
        client.sendGroupMessage(groupID,MessageUtil.message(MessageUtil.image(result.getString("file"))));
    }

    void rememberStickerEmotion(long groupID,long userID,String emotion) {
        if (emotion == null || emotion.trim().isEmpty()) return;
        recentStickerMap.put(service.recentImageKey(groupID,userID),new RoleplayService.RecentSticker(
                emotion.trim(),System.currentTimeMillis() + service.config().stickerAttachWindowSecond * 1000L));
    }

    String recentStickerEmotion(long groupID,long userID) {
        String key = service.recentImageKey(groupID,userID);
        RoleplayService.RecentSticker recent = recentStickerMap.get(key);
        if (recent == null) return "";
        if (recent.expired()) {
            recentStickerMap.remove(key);
            return "";
        }
        return recent.emotion;
    }

    String mergeStickerEmotion(String content,String emotion) {
        String text = service.safe(content);
        String sticker = service.safe(emotion).trim();
        if (sticker.isEmpty()) return text;
        return text+"\n"+sticker;
    }

    void clearRuntimeState() {
        recentStickerMap.clear();
    }
}