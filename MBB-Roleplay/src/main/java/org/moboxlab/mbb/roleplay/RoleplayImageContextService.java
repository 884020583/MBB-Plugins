package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Event.GroupMessageEvent;
import org.moboxlab.moboxbot.API.PluginService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色图片上下文与识图
 *
 * 负责图片片段识别、表情包片段判定、最近图片缓存、识图限流和异步识图。
 */
public class RoleplayImageContextService {
    private final RoleplayService service;
    private final Map<Long,long[]> imageVisionRateMap = new ConcurrentHashMap<>();
    private final Map<String,RoleplayService.RecentImage> recentImageMap = new ConcurrentHashMap<>();

    public RoleplayImageContextService(RoleplayService service) {
        this.service = service;
    }

    boolean hasImageContent(JSONArray message) {
        if (message == null) return false;
        for (int i = 0; i < message.size(); i++) {
            JSONObject segment = message.getJSONObject(i);
            if (segment == null) continue;
            String type = segment.getString("type");
            if ("image".equals(type) || "mface".equals(type)) return true;
        }
        return false;
    }

    boolean hasMeaningfulText(JSONArray message) {
        if (message == null) return false;
        for (int i = 0; i < message.size(); i++) {
            JSONObject segment = message.getJSONObject(i);
            if (segment == null) continue;
            String type = segment.getString("type");
            JSONObject data = segment.getJSONObject("data");
            if ("text".equals(type) && data != null && !service.safe(data.getString("text")).trim().isEmpty()) {
                return true;
            }
            if ("at".equals(type) && data != null && !service.safe(data.getString("qq")).trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    RoleplayService.RecentImage rememberImageContext(GroupMessageEvent event,long groupID) {
        JSONArray message = event.getMessage();
        if (message == null) return null;
        JSONObject latest = null;
        int latestIndex = -1;
        for (int i = 0; i < message.size(); i++) {
            JSONObject segment = message.getJSONObject(i);
            if (segment == null) continue;
            String type = segment.getString("type");
            JSONObject data = segment.getJSONObject("data");
            if (!("image".equals(type) || "mface".equals(type))) continue;
            if (isStickerSegment(type,data)) continue;
            latest = data;
            latestIndex = i;
        }
        if (latest == null) return null;
        JSONObject copy = new JSONObject(true);
        copy.put("file_unique",service.safe(latest.getString("file_unique")));
        copy.put("file_id",service.safe(latest.getString("file_id")));
        copy.put("url",service.safe(latest.getString("url")));
        copy.put("file",service.safe(latest.getString("file")));
        copy.put("sub_type",latest.get("sub_type"));
        RoleplayService.RecentImage recent = new RoleplayService.RecentImage(copy,
                System.currentTimeMillis() + service.config().imageContextTimeoutSecond * 1000L);
        recent.hubKey = hubKey(groupID,event.getMessageID(),latestIndex);
        recentImageMap.put(recentImageKey(groupID,event.getUserID()),recent);
        return recent;
    }

    /**
     * 同群多 bot 共享识图结果的 key。
     * 用 群号 + 消息ID + 片段序号，保证两个 bot 收到同一条消息时算出同一把 key。
     */
    String hubKey(long groupID,long messageID,int segmentIndex) {
        return "g"+groupID+"-m"+messageID+"-i"+segmentIndex;
    }

    RoleplayService.RecentImage findRecentImageContext(long groupID,long userID,String content) {
        String key = recentImageKey(groupID,userID);
        RoleplayService.RecentImage recent = recentImageMap.get(key);
        if (recent == null) return null;
        if (recent.expired()) {
            recentImageMap.remove(key);
            return null;
        }
        return referencesImage(content) ? recent : null;
    }

    boolean referencesImage(String content) {
        String value = service.safe(content);
        String[] keywords = new String[]{"图","照片","截图","这个","这张","里面","上面","什么",
                "谁","哪里","颜色","文字","写","看","识别","描述"};
        for (String keyword : keywords) {
            if (value.contains(keyword)) return true;
        }
        return false;
    }

    JSONObject imageParams(RoleplayService.RecentImage recent) {
        JSONObject params = new JSONObject(true);
        JSONObject data = recent == null ? null : recent.data;
        params.put("fileUnique",data == null ? "" : service.safe(data.getString("file_unique")));
        if (service.safe(params.getString("fileUnique")).isEmpty() && data != null) {
            params.put("fileUnique",service.safe(data.getString("file_id")));
        }
        params.put("url",data == null ? "" : service.safe(data.getString("url")));
        params.put("file",data == null ? "" : service.safe(data.getString("file")));
        return params;
    }

    JSONObject imageData(RoleplayService.RecentImage recent) {
        PluginService vision = service.plugin().getServer().getPluginManager().getService("MBB-Vision");
        if (vision == null || recent == null) return null;
        return vision.call("dataUri",imageParams(recent));
    }

    String imageSummary(RoleplayService.RecentImage recent) {
        if (recent == null) return "";
        if (recent.summary != null && !recent.summary.trim().isEmpty()) return recent.summary;
        PluginService vision = service.plugin().getServer().getPluginManager().getService("MBB-Vision");
        if (vision == null) return "";
        JSONObject result = service.hubService().describe(vision,imageParams(recent),recent.hubKey);
        if (result == null || !result.getBooleanValue("status")) return "";
        String summary = service.safe(result.getString("summary"));
        if (summary.isEmpty()) summary = service.safe(result.getString("description"));
        recent.summary = summary;
        return summary;
    }

    boolean sameImage(JSONObject left,JSONObject right) {
        if (left == null || right == null) return false;
        String leftUnique = service.safe(left.getString("file_unique"));
        String rightUnique = service.safe(right.getString("file_unique"));
        if (!leftUnique.isEmpty() || !rightUnique.isEmpty()) return leftUnique.equals(rightUnique);
        String leftUrl = service.safe(left.getString("url"));
        String rightUrl = service.safe(right.getString("url"));
        if (!leftUrl.isEmpty() || !rightUrl.isEmpty()) return leftUrl.equals(rightUrl);
        String leftFile = service.safe(left.getString("file"));
        String rightFile = service.safe(right.getString("file"));
        return !leftFile.isEmpty() && leftFile.equals(rightFile);
    }

    String recentImageKey(long groupID,long userID) {
        return groupID+"|"+userID;
    }

    boolean shouldUnderstandImages(GroupMessageEvent event,String content,
                                           boolean direct,boolean sameUserContinuation) {
        if (!service.config().imageUnderstandingEnable || "off".equals(service.config().imageUnderstandingMode)) return false;
        if (hasStickerContent(event.getMessage())) return true;
        if ("all".equals(service.config().imageUnderstandingMode)) return true;
        return direct || sameUserContinuation;
    }

    boolean hasStickerContent(JSONArray message) {
        if (message == null) return false;
        for (int i = 0; i < message.size(); i++) {
            JSONObject segment = message.getJSONObject(i);
            if (segment == null) continue;
            if (isStickerSegment(segment.getString("type"),segment.getJSONObject("data"))) return true;
        }
        return false;
    }

    boolean isStickerSegment(String type,JSONObject data) {
        if ("mface".equals(type)) return true;
        if (data == null) return false;
        String file = service.safe(data.getString("file"));
        if (file.contains("marketface")) return true;
        Object subType = data.get("sub_type");
        if (subType == null) return false;
        String value = String.valueOf(subType).trim();
        return !value.isEmpty() && !"0".equals(value);
    }

    void dispatchImageUnderstanding(GroupMessageEvent event,long groupID,
                                            RoleplayService.RecentImage imageContext,boolean hasSticker,
                                            boolean direct,boolean sameUserContinuation) {
        if (!shouldUnderstandImages(event,"",direct,sameUserContinuation)) return;
        final long userID = event.getUserID();
        final boolean sticker = hasSticker;
        service.submitBackground(() -> {
            try {
                String content = enrichImageContent(event,"",groupID,imageContext);
                if (sticker) {
                    String emotion = content == null || content.trim().isEmpty()
                            ? "[表情包]" : content.trim();
                    service.finishStickerRecognition(groupID,userID,emotion);
                }
            } catch (Exception e) {
                service.plugin().getLogger().sendException(e);
            }
        });
    }

    String enrichImageContent(GroupMessageEvent event,String context,long groupID,
                                      RoleplayService.RecentImage currentImage) {
        PluginService vision = service.plugin().getServer().getPluginManager().getService("MBB-Vision");
        if (vision == null) return context;
        if (!reserveImageVision(groupID)) {
            service.plugin().getLogger().sendWarn("[识图] 群"+groupID+" 已达到每小时识图上限，跳过图片理解。");
            return context;
        }
        StringBuilder builder = new StringBuilder();
        JSONArray message = event.getMessage();
        for (int i = 0; i < message.size(); i++) {
            JSONObject segment = message.getJSONObject(i);
            if (segment == null) continue;
            String type = segment.getString("type");
            JSONObject data = segment.getJSONObject("data");
            if ("text".equals(type)) {
                builder.append(data == null ? "" : service.safe(data.getString("text")));
            } else if ("at".equals(type)) {
                builder.append("@").append(data == null ? "" : service.safe(data.getString("qq")));
            } else if ("image".equals(type) || "mface".equals(type)) {
                builder.append(describeImageSegment(vision,type,data,context,currentImage,
                        hubKey(groupID,event.getMessageID(),i)));
            } else if ("face".equals(type)) {
                builder.append("[表情]");
            }
        }
        return builder.toString().trim();
    }

    String describeImageSegment(PluginService vision,String type,JSONObject data,String context,
                                        RoleplayService.RecentImage currentImage,String hubKey) {
        boolean sticker = isStickerSegment(type,data);
        JSONObject params = new JSONObject(true);
        params.put("kind",sticker ? "sticker" : "image");
        String fileUnique = data == null ? "" : service.safe(data.getString("file_unique"));
        if (fileUnique.isEmpty() && data != null) fileUnique = service.safe(data.getString("file_id"));
        params.put("fileUnique",fileUnique);
        params.put("url",data == null ? "" : service.safe(data.getString("url")));
        params.put("file",data == null ? "" : service.safe(data.getString("file")));
        params.put("context",context);
        params.put("reference",service.visionReferenceText());
        params.put("profile",service.config().imageUnderstandingProfile);
        JSONObject result = service.hubService().describe(vision,params,hubKey);
        if (result == null || !result.getBooleanValue("status")) {
            return sticker ? "[表情包]" : "[图片]";
        }
        String summary = service.safe(result.getString("summary"));
        if (summary.isEmpty()) summary = service.safe(result.getString("description"));
        if (currentImage != null && !sticker && sameImage(data,currentImage.data)) {
            currentImage.summary = summary;
        }
        String ocr = service.safe(result.getString("ocr"));
        String tags = service.joinArray(result.getJSONArray("emotionTags"));
        StringBuilder builder = new StringBuilder(sticker ? "[表情包" : "[图片");
        if (!summary.isEmpty()) builder.append("：").append(service.shortText(summary,service.config().imageUnderstandingMaxChars));
        if (sticker && !tags.isEmpty()) builder.append("；情绪：").append(tags);
        if (!sticker && service.config().imageUnderstandingInjectOcr && !ocr.isEmpty()) {
            builder.append("；文字：").append(service.shortText(ocr,120));
        }
        return builder.append("]").toString();
    }

    synchronized boolean reserveImageVision(long groupID) {
        if (service.config().imageUnderstandingMaxPerHour <= 0) return true;
        long now = System.currentTimeMillis();
        long[] state = imageVisionRateMap.get(groupID);
        if (state == null || now - state[0] >= 3600000L) {
            imageVisionRateMap.put(groupID,new long[]{now,1});
            return true;
        }
        if (state[1] >= service.config().imageUnderstandingMaxPerHour) return false;
        state[1]++;
        return true;
    }

    void clearRuntimeState() {
        imageVisionRateMap.clear();
        recentImageMap.clear();
    }
}
