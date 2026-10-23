package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Event.GroupMessageEvent;
import org.moboxlab.moboxbot.API.OneBot.MessageUtil;
import org.moboxlab.moboxbot.API.OneBot.OneBotClient;
import org.moboxlab.moboxbot.API.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 训练语料采集
 *
 * 对着某条消息回复 /role learn [备注]，把那条消息和前后文一起存成一条训练样本，
 * 目标消息不限于角色自己发的，任何人的消息都可以采集。
 * 不回复也可以：/role learn 会把最近一条群消息当作目标。
 *
 * 产物是 train-corpus/learned.jsonl，一行一条记录，可以直接喂给后续的微调流程。
 */
public class RoleplayLearnService {
    private static final String FILE_NAME = "learned.jsonl";

    private final RoleplayService service;

    public RoleplayLearnService(RoleplayService service) {
        this.service = service;
    }

    /**
     * 拦截 /role learn 命令。返回 true 表示这条消息已经处理完，不再进入正常回复链路。
     */
    boolean handleCommand(GroupMessageEvent event) {
        if (event == null) return false;
        if (!service.config().learnEnable) return false;
        String content = service.extractContent(event.getMessage());
        String[] args = parseArgs(content);
        if (args == null) return false;
        long groupID = event.getGroupID();
        long userID = event.getUserID();
        if (groupID <= 0) {
            reply(event,"采集训练语料只能在群里使用。");
            return true;
        }
        if (!isOperator(userID)) {
            reply(event,"采集训练语料需要管理员或 owner 权限。");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if ("stats".equals(sub)) {
            reply(event,statsText());
            return true;
        }
        if ("recent".equals(sub)) {
            reply(event,recentText(parseInt(args,1,5)));
            return true;
        }
        JSONObject record = capture(event,groupID,userID,joinArgs(args,0));
        if (record == null) {
            reply(event,"没有找到要采集的消息。回复某条消息再发 /role learn，或者先让群里说一句话。");
            return true;
        }
        if (!append(record)) {
            reply(event,"写入语料文件失败，请查看控制台日志。");
            return true;
        }
        JSONObject target = record.getJSONObject("target");
        service.plugin().getLogger().sendInfo("[语料] 采集 群"+groupID+" 目标消息"
                +target.getLongValue("messageID")+" 来自 "+target.getString("userName")
                +" 前文"+record.getJSONArray("contextBefore").size()+"条"
                +" 后文"+record.getJSONArray("contextAfter").size()+"条");
        reply(event,"已采集：\n发送者："+target.getString("userName")
                +"\n内容："+service.shortText(target.getString("text"),60)
                +"\n上下文："+record.getJSONArray("contextBefore").size()+" 条前文 / "
                +record.getJSONArray("contextAfter").size()+" 条后文"
                +(record.getString("note").isEmpty() ? "" : "\n备注："+record.getString("note")));
        return true;
    }

    /**
     * 解析 /role learn 或 /role corpus，支持自定义命令前缀和前置艾特
     */
    private String[] parseArgs(String content) {
        String text = service.safe(content).trim();
        text = text.replaceFirst("^@[0-9]+\\s*","").trim();
        if (text.isEmpty()) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        String prefixes = service.config().commandPrefixes;
        if (prefixes == null || prefixes.trim().isEmpty()) prefixes = "/";
        for (String item : prefixes.split(",")) {
            String prefix = item == null ? "" : item.trim();
            if (prefix.isEmpty()) continue;
            String head = (prefix+"role").toLowerCase(Locale.ROOT);
            if (!lower.startsWith(head)) continue;
            String rest = text.substring(prefix.length()+4).trim();
            String restLower = rest.toLowerCase(Locale.ROOT);
            if (restLower.equals("learn")) return new String[0];
            if (restLower.startsWith("learn ")) return splitArgs(rest.substring(5).trim());
            if (restLower.equals("corpus")) return new String[0];
            if (restLower.startsWith("corpus ")) return splitArgs(rest.substring(6).trim());
        }
        return null;
    }

    private String[] splitArgs(String text) {
        String value = service.safe(text).trim();
        if (value.isEmpty()) return new String[0];
        return value.split("\\s+");
    }

    /**
     * 组装一条训练样本：目标消息 + 前后文 + 备注
     */
    private JSONObject capture(GroupMessageEvent event,long groupID,long userID,String note) {
        JSONObject target = null;
        long quotedID = service.quotedMessageID(event);
        if (quotedID > 0) target = findMessage(groupID,quotedID);
        if (target == null) target = latestMessage(groupID,event.getMessageID());
        if (target == null) return null;

        long rowID = target.getLongValue("ID");
        int before = Math.max(0,service.config().learnContextBefore);
        int after = Math.max(0,service.config().learnContextAfter);
        List<JSONObject> beforeRows = service.storage().query(
                "SELECT * FROM `"+RoleplayService.MSG_TABLE+"` WHERE `groupID`=? AND `ID`<? "
                        + "ORDER BY `ID` DESC LIMIT ?",
                groupID,rowID,before);
        List<JSONObject> afterRows = service.storage().query(
                "SELECT * FROM `"+RoleplayService.MSG_TABLE+"` WHERE `groupID`=? AND `ID`>? "
                        + "ORDER BY `ID` ASC LIMIT ?",
                groupID,rowID,after);
        Collections.reverse(beforeRows == null ? new ArrayList<JSONObject>() : beforeRows);

        JSONObject record = new JSONObject(true);
        record.put("id","learn-"
                +new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.CHINA).format(new Date())
                +"-"+String.format("%04d",ThreadLocalRandom.current().nextInt(10000)));
        record.put("role",roleName());
        record.put("personaFile",personaFile());
        record.put("groupID",groupID);
        record.put("target",messageJson(target));
        record.put("contextBefore",messagesJson(beforeRows,event.getMessageID()));
        record.put("contextAfter",messagesJson(afterRows,event.getMessageID()));
        record.put("note",service.safe(note).trim());
        record.put("learnedBy",userID);
        record.put("learnedAt",System.currentTimeMillis());
        record.put("learnedAtText",
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA).format(new Date()));
        return record;
    }

    private JSONObject findMessage(long groupID,long messageID) {
        if (messageID <= 0) return null;
        List<JSONObject> rows = service.storage().query(
                "SELECT * FROM `"+RoleplayService.MSG_TABLE+"` WHERE `groupID`=? AND `messageID`=? "
                        + "ORDER BY `ID` DESC LIMIT 1",
                groupID,messageID);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private JSONObject latestMessage(long groupID,long excludeMessageID) {
        List<JSONObject> rows = service.storage().query(
                "SELECT * FROM `"+RoleplayService.MSG_TABLE+"` WHERE `groupID`=? "
                        + "ORDER BY `ID` DESC LIMIT 5",
                groupID);
        if (rows == null) return null;
        for (JSONObject row : rows) {
            if (row == null) continue;
            if (excludeMessageID > 0 && row.getLongValue("messageID") == excludeMessageID) continue;
            return row;
        }
        return null;
    }

    private JSONArray messagesJson(List<JSONObject> rows,long excludeMessageID) {
        JSONArray array = new JSONArray();
        if (rows == null) return array;
        for (JSONObject row : rows) {
            if (row == null) continue;
            if (excludeMessageID > 0 && row.getLongValue("messageID") == excludeMessageID) continue;
            array.add(messageJson(row));
        }
        return array;
    }

    private JSONObject messageJson(JSONObject row) {
        JSONObject json = new JSONObject(true);
        json.put("messageID",row.getLongValue("messageID"));
        json.put("userID",row.getLongValue("userID"));
        json.put("userName",service.safe(row.getString("userName")));
        json.put("isBot",row.getIntValue("isBot") == 1);
        json.put("time",formatTime(row.getLongValue("messageTime")));
        json.put("text",service.safe(row.getString("content")));
        return json;
    }

    private boolean append(JSONObject record) {
        try {
            File directory = new File(service.plugin().getDataFolder(),directoryName());
            if (!directory.exists()) directory.mkdirs();
            File file = new File(directory,FILE_NAME);
            Files.write(file.toPath(),(record.toJSONString()+"\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            return true;
        } catch (Exception e) {
            service.plugin().getLogger().sendException(e);
            return false;
        }
    }

    private String statsText() {
        File file = corpusFile();
        int total = countLines(file);
        int today = 0;
        String todayTag = new SimpleDateFormat("yyyy-MM-dd",Locale.CHINA).format(new Date());
        for (JSONObject record : readRecords(0)) {
            String learned = service.safe(record.getString("learnedAtText"));
            if (learned.startsWith(todayTag)) today++;
        }
        return "训练语料采集\n文件："+file.getAbsolutePath()
                +"\n累计："+total+" 条，今日："+today+" 条"
                +"\n角色："+roleName()
                +"\n用法：回复某条消息发 /role learn [备注]；不带引用时采集最近一条群消息";
    }

    private String recentText(int limit) {
        List<JSONObject> records = readRecords(Math.max(1,Math.min(limit,10)));
        if (records.isEmpty()) return "还没有采集过语料。";
        StringBuilder builder = new StringBuilder("最近采集（"+records.size()+" 条）：");
        for (JSONObject record : records) {
            JSONObject target = record.getJSONObject("target");
            if (target == null) continue;
            builder.append("\n- ").append(service.safe(record.getString("learnedAtText")))
                    .append(" ").append(target.getString("userName"))
                    .append("：").append(service.shortText(target.getString("text"),40));
            String note = service.safe(record.getString("note"));
            if (!note.isEmpty()) builder.append("（备注：").append(service.shortText(note,20)).append("）");
        }
        return builder.toString();
    }

    /**
     * 读最近 limit 条记录，limit<=0 表示全部
     */
    private List<JSONObject> readRecords(int limit) {
        List<JSONObject> result = new ArrayList<>();
        File file = corpusFile();
        if (!file.exists()) return result;
        try {
            List<String> lines = Files.readAllLines(file.toPath(),StandardCharsets.UTF_8);
            int start = limit <= 0 ? 0 : Math.max(0,lines.size()-limit);
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line == null || line.trim().isEmpty()) continue;
                try {
                    JSONObject record = JSONObject.parseObject(line);
                    if (record != null) result.add(record);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            service.plugin().getLogger().sendWarn("[语料] 读取采集文件失败："+e.getMessage());
        }
        return result;
    }

    private int countLines(File file) {
        if (!file.exists()) return 0;
        try {
            List<String> lines = Files.readAllLines(file.toPath(),StandardCharsets.UTF_8);
            int count = 0;
            for (String line : lines) {
                if (line != null && !line.trim().isEmpty()) count++;
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private File corpusFile() {
        return new File(new File(service.plugin().getDataFolder(),directoryName()),FILE_NAME);
    }

    private String directoryName() {
        String value = service.config().learnDirectory;
        if (value == null || value.trim().isEmpty()) return "train-corpus";
        return value.trim();
    }

    private String roleName() {
        RoleplayPersona persona = service.persona();
        return persona == null || persona.name == null ? "" : persona.name;
    }

    private String personaFile() {
        Plugin plugin = service.plugin();
        if (!(plugin instanceof RoleplayPlugin)) return "";
        try {
            String name = ((RoleplayPlugin) plugin).getPersonaFileName();
            return name == null ? "" : name;
        } catch (Exception e) {
            return "";
        }
    }

    private String formatTime(long millis) {
        if (millis <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA).format(new Date(millis));
    }

    private int parseInt(String[] args,int index,int defaultValue) {
        if (args == null || args.length <= index) return defaultValue;
        try {
            return Integer.parseInt(args[index]);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String joinArgs(String[] args,int start) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; args != null && i < args.length; i++) {
            if (builder.length() > 0) builder.append(" ");
            builder.append(args[i]);
        }
        return builder.toString().trim();
    }

    private boolean isOperator(long userID) {
        List<Long> owners = service.plugin().getServer().getOwnerList();
        if (owners != null && owners.contains(userID)) return true;
        List<Long> admins = service.plugin().getServer().getAdminList();
        return admins != null && admins.contains(userID);
    }

    private void reply(GroupMessageEvent event,String text) {
        OneBotClient client = service.plugin().getServer().getOneBotClient();
        if (client == null) return;
        JSONArray message = event.getMessageID() > 0
                ? MessageUtil.message(MessageUtil.reply(event.getMessageID()),MessageUtil.text(text))
                : MessageUtil.message(MessageUtil.text(text));
        client.sendGroupMessage(event.getGroupID(),message);
    }
}
