package org.moboxlab.mbb.sticker;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Event.PrivateMessageEvent;
import org.moboxlab.moboxbot.API.OneBot.MessageUtil;
import org.moboxlab.moboxbot.API.OneBot.OneBotClient;
import org.moboxlab.moboxbot.API.Plugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 私聊表情包收录服务
 */
public class StickerReceiveService {
    private final Plugin plugin;
    private final StickerLibrary library;
    private final StickerTagger tagger;
    private final Map<Long,Session> sessions = new ConcurrentHashMap<>();

    private static class Session {
        private final long expireAt;
        private int count;

        private Session(long expireAt) {
            this.expireAt = expireAt;
        }

        private boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    private static class SourceFile {
        private final File file;
        private final boolean temporary;

        private SourceFile(File file,boolean temporary) {
            this.file = file;
            this.temporary = temporary;
        }
    }

    public StickerReceiveService(Plugin plugin,StickerLibrary library,StickerTagger tagger) {
        this.plugin = plugin;
        this.library = library;
        this.tagger = tagger;
    }

    public void start(long userID) {
        int minutes = Math.max(1,plugin.getConfig().getInt("receiveTimeoutMinutes",10));
        sessions.put(userID,new Session(System.currentTimeMillis() + minutes * 60000L));
    }

    public void stop(long userID) {
        sessions.remove(userID);
    }

    public boolean active(long userID) {
        Session session = sessions.get(userID);
        if (session == null) return false;
        if (session.expired()) {
            sessions.remove(userID);
            return false;
        }
        return true;
    }

    public int count(long userID) {
        Session session = sessions.get(userID);
        return session == null ? 0 : session.count;
    }

    public boolean accept(long userID) {
        Session session = sessions.get(userID);
        if (session == null || session.expired()) {
            sessions.remove(userID);
            return false;
        }
        int max = Math.max(1,plugin.getConfig().getInt("receiveMaxImages",50));
        if (session.count >= max) return false;
        session.count++;
        return true;
    }

    public void handleImage(PrivateMessageEvent event,JSONObject data) {
        if (event == null || data == null || !active(event.getUserID())) return;
        if (!accept(event.getUserID())) {
            sendQuote(event.getUserID(),event.getMessageID(),"本次收录数量已达上限，请重新执行 /sticker receive。");
            return;
        }
        plugin.getServer().getPluginManager().runTask(plugin,() -> process(event,data));
    }

    private void process(PrivateMessageEvent event,JSONObject data) {
        JSONObject result = importImage(data,"receive",null,"");
        if (!result.getBooleanValue("status")) {
            sendQuote(event.getUserID(),event.getMessageID(),result.getString("message"));
            return;
        }
        if (result.getBooleanValue("duplicate")) {
            sendQuote(event.getUserID(),event.getMessageID(),"表情包已存在：\n"
                    +"ID："+result.getString("id")+"\n"
                    +"标签："+joinTags(result.getJSONArray("tags"))+"\n"
                    +"描述："+(safe(result.getString("description")).isEmpty()
                    ? "无" : result.getString("description"))+"\n"
                    +"文件："+result.getString("file"));
        } else {
            sendQuote(event.getUserID(),event.getMessageID(),"已收录表情包：\n"
                    +"ID："+result.getString("id")+"\n"
                    +"标签："+joinTags(result.getJSONArray("tags"))+"\n"
                    +"描述："+(safe(result.getString("description")).isEmpty()
                    ? "无" : result.getString("description"))+"\n"
                    +"文件："+result.getString("file"));
        }
    }

    /**
     * 公共导入入口。只接受 OneBot 图片段字段，不能直接传本地路径。
     * 角色决定保存时由 MBB-Sticker 统一完成下载、查重、识图和落库。
     */
    public JSONObject importImage(JSONObject data,String source,List<String> tags,String description) {
        JSONObject result = new JSONObject(true);
        SourceFile sourceFile = null;
        File target = null;
        boolean stored = false;
        try {
            sourceFile = resolveSource(data);
            if (sourceFile == null || sourceFile.file == null || !sourceFile.file.exists()) {
                return error(result,"图片获取失败");
            }
            File image = sourceFile.file;
            long maxBytes = Math.max(1,plugin.getConfig().getInt("maxFileSizeKB",8192)) * 1024L;
            if (image.length() > maxBytes) {
                return error(result,"图片超过大小限制："+maxBytes / 1024L+" KB");
            }
            String fileUnique = fileUnique(data);
            String hash = sha256(image);
            StickerEntry existing = library.findByHash(hash);
            if (existing == null && !fileUnique.isEmpty()) {
                existing = library.findByFileUnique(fileUnique);
            }
            if (existing != null) {
                library.touch(existing,fileUnique);
                return success(result,existing,true);
            }
            File stickerDir = new File(plugin.getDataFolder(),"stickers");
            if (!stickerDir.exists()) stickerDir.mkdirs();
            String extension = extension(image.getName());
            if (extension.isEmpty()) extension = "png";
            target = new File(stickerDir,"sticker-"+System.currentTimeMillis()+"-"
                    +ThreadLocalRandom.current().nextInt(10000)+"."+extension);
            Files.copy(image.toPath(),target.toPath());
            List<String> finalTags = library.normalizeTags(tags);
            String finalDescription = safe(description).trim();
            int visionVersion = 0;
            if (finalTags.isEmpty() || finalDescription.isEmpty()) {
                JSONObject tagged = tagger.tag(target,mime(extension),fileUnique);
                if (finalTags.isEmpty()) finalTags = toStringList(tagged.getJSONArray("tags"));
                if (finalDescription.isEmpty()) {
                    finalDescription = safe(tagged.getString("description"));
                }
                visionVersion = tagged.getIntValue("visionVersion");
            }
            StickerEntry entry = library.add(target,finalTags,safe(source).isEmpty() ? "import" : source,
                    finalDescription,hash,fileUnique,visionVersion);
            stored = true;
            plugin.getLogger().sendInfo("[表情包] 新增 ID="+entry.id+" hash="+shortHash(hash)
                    +" fileUnique="+fileUnique+" 来源="+safe(source)
                    +" 标签="+String.join(", ",entry.tags)
                    +" 描述="+shortText(entry.description,100));
            return success(result,entry,false);
        } catch (Exception e) {
            plugin.getLogger().sendWarn("收录表情包失败："+e.getMessage());
            return error(result,"收录失败："+safe(e.getMessage()));
        } finally {
            if (sourceFile != null && sourceFile.temporary) deleteTemporary(sourceFile.file);
            if (!stored && target != null && target.exists()) deleteTemporary(target);
        }
    }

    private JSONObject success(JSONObject result,StickerEntry entry,boolean duplicate) {
        result.put("status",true);
        result.put("message",duplicate ? "表情包已存在" : "已收录表情包");
        result.put("id",entry.id);
        result.put("duplicate",duplicate);
        result.put("file",library.toUri(entry.file));
        result.put("description",entry.description);
        JSONArray tags = new JSONArray();
        tags.addAll(entry.tags);
        result.put("tags",tags);
        return result;
    }

    private JSONObject error(JSONObject result,String message) {
        result.put("status",false);
        result.put("message",safe(message));
        return result;
    }

    private String joinTags(JSONArray tags) {
        if (tags == null || tags.isEmpty()) return "无";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) builder.append(", ");
            builder.append(tags.getString(i));
        }
        return builder.toString();
    }

    private SourceFile resolveSource(JSONObject data) throws Exception {
        String url = safe(data.getString("url"));
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return download(url);
        }
        String file = safe(data.getString("file"));
        if (file.startsWith("file://")) {
            File local = StickerLibrary.fromFileUri(file);
            if (local != null && local.exists()) return new SourceFile(local,false);
        }
        if (!file.isEmpty()) {
            File local = new File(file);
            if (local.exists() && local.isFile()) return new SourceFile(local,false);
        }
        if (!file.isEmpty()) {
            JSONObject params = new JSONObject(true);
            params.put("file",file);
            OneBotClient client = plugin.getServer().getOneBotClient();
            JSONObject response = client == null ? null : client.callAction("get_image",params);
            JSONObject result = response == null ? null : response.getJSONObject("data");
            if (result != null) {
                String resolvedUrl = safe(result.getString("url"));
                if (resolvedUrl.startsWith("http://") || resolvedUrl.startsWith("https://")) {
                    return download(resolvedUrl);
                }
                File local = new File(safe(result.getString("file")));
                if (local.exists()) return new SourceFile(local,false);
            }
        }
        return null;
    }

    private SourceFile download(String url) throws Exception {
        long maxBytes = Math.max(1,plugin.getConfig().getInt("maxFileSizeKB",8192)) * 1024L;
        HttpURLConnection connection = null;
        InputStream input = null;
        FileOutputStream output = null;
        File temp = null;
        boolean success = false;
        try {
            connection = (HttpURLConnection)new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent","MoBoxBot/0.1");
            String extension = extensionFromContentType(connection.getContentType());
            if (extension.isEmpty()) extension = extension(new URL(url).getPath());
            if (extension.isEmpty()) extension = "png";
            input = connection.getInputStream();
            File dir = new File(plugin.getDataFolder(),"receive");
            if (!dir.exists()) dir.mkdirs();
            temp = new File(dir,"receive-"+System.currentTimeMillis()+"-"
                    +ThreadLocalRandom.current().nextInt(10000)+"."+extension);
            output = new FileOutputStream(temp);
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) > 0) {
                total += read;
                if (total > maxBytes) throw new IllegalArgumentException("图片超过大小限制");
                output.write(buffer,0,read);
            }
            output.close();
            output = null;
            success = true;
            return new SourceFile(temp,true);
        } finally {
            try { if (output != null) output.close(); } catch (Exception ignored) {}
            try { if (input != null) input.close(); } catch (Exception ignored) {}
            if (connection != null) connection.disconnect();
            if (!success) deleteTemporary(temp);
        }
    }

    private void deleteTemporary(File file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Exception e) {
            plugin.getLogger().sendWarn("清理表情包临时文件失败："+e.getMessage());
        }
    }

    private void sendQuote(long userID,long messageID,String text) {
        OneBotClient client = plugin.getServer().getOneBotClient();
        if (client == null) return;
        client.sendPrivateMessage(userID,MessageUtil.message(
                MessageUtil.reply(messageID),MessageUtil.text(text)));
    }

    private List<String> toStringList(JSONArray array) {
        List<String> result = new java.util.ArrayList<>();
        if (array == null) return result;
        for (Object item : array) {
            if (item != null) result.add(String.valueOf(item));
        }
        return result;
    }

    private String extension(String name) {
        if (name == null) return "";
        int index = name.lastIndexOf('.');
        if (index < 0) return "";
        String value = name.substring(index + 1).toLowerCase(Locale.ROOT);
        if ("jpg".equals(value) || "jpeg".equals(value) || "png".equals(value)
                || "gif".equals(value) || "webp".equals(value)) return value;
        return "";
    }

    private String fileUnique(JSONObject data) {
        if (data == null) return "";
        String value = safe(data.getString("file_unique"));
        if (value.isEmpty()) value = safe(data.getString("file_id"));
        return value;
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(file.toPath());
        byte[] value = digest.digest(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte item : value) builder.append(String.format("%02x",item & 0xff));
        return builder.toString();
    }

    private String shortHash(String hash) {
        if (hash == null || hash.length() <= 12) return safe(hash);
        return hash.substring(0,12);
    }

    private String shortText(String value,int maxLength) {
        if (value == null) return "";
        String text = value.replace("\r"," ").replace("\n"," ").trim();
        if (text.length() <= maxLength) return text;
        return text.substring(0,maxLength)+"...";
    }

    private String extensionFromContentType(String contentType) {
        if (contentType == null) return "";
        String value = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if ("image/jpeg".equals(value)) return "jpg";
        if ("image/png".equals(value)) return "png";
        if ("image/gif".equals(value)) return "gif";
        if ("image/webp".equals(value)) return "webp";
        return "";
    }

    private String mime(String extension) {
        if ("jpg".equals(extension) || "jpeg".equals(extension)) return "image/jpeg";
        if ("gif".equals(extension)) return "image/gif";
        if ("webp".equals(extension)) return "image/webp";
        return "image/png";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
