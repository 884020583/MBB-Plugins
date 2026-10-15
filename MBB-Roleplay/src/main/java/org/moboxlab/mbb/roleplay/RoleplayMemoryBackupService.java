package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 记忆备份与恢复
 *
 * 负责短期、长期、永久记忆、情绪、黑名单和群员印象的快照导出与恢复。
 */
public class RoleplayMemoryBackupService {
    private final RoleplayService service;

    public RoleplayMemoryBackupService(RoleplayService service) {
        this.service = service;
    }

    public synchronized String backupAllMemories(String reason) {
        try {
            File directory = backupDirectory();
            JSONObject root = new JSONObject(true);
            root.put("version",3);
            root.put("type","mbb-roleplay-memory-backup");
            root.put("createTime",System.currentTimeMillis());
            root.put("reason",service.safe(reason));
            root.put("shortTerm",exportShortTerm());
            root.put("longTerm",exportLongTerm());
            root.put("globalMemory",service.globalMemoryService().exportAll());
            root.put("emotion",service.emotionService().exportState());
            root.put("blacklist",service.blacklistService().exportAll());
            root.put("member",service.memberService().exportAll());
            String name = "memory-backup-"
                    +new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.CHINA).format(new Date())
                    +".json";
            File file = new File(directory,name);
            Files.write(Paths.get(file.getAbsolutePath()),
                    root.toJSONString().getBytes(StandardCharsets.UTF_8));
            service.plugin().getLogger().sendInfo("[记忆] 已备份全部记忆到 "+file.getAbsolutePath());
            return name;
        } catch (Exception e) {
            service.plugin().getLogger().sendWarn("备份全部记忆失败："+e.getMessage());
            return "";
        }
    }

    public List<String> listMemoryBackups() {
        List<String> result = new java.util.ArrayList<>();
        File[] files = backupDirectory().listFiles();
        if (files == null) return result;
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.startsWith("memory-backup-") && name.endsWith(".json")) {
                result.add(name);
            }
        }
        Collections.sort(result,Collections.reverseOrder());
        return result;
    }

    public synchronized JSONObject restoreAllMemories(String fileName) {
        JSONObject result = new JSONObject(true);
        String name = fileName == null ? "" : fileName.trim();
        if (name.isEmpty()) {
            result.put("status",false);
            result.put("message","请提供备份文件名。");
            return result;
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            result.put("status",false);
            result.put("message","备份文件名不合法。");
            return result;
        }
        File file = new File(backupDirectory(),name);
        if (!file.exists()) {
            result.put("status",false);
            result.put("message","找不到备份文件："+name);
            return result;
        }
        try {
            String text = new String(Files.readAllBytes(Paths.get(file.getAbsolutePath())),
                    StandardCharsets.UTF_8);
            JSONObject root = JSONObject.parseObject(text);
            if (root == null || !"mbb-roleplay-memory-backup".equals(root.getString("type"))) {
                result.put("status",false);
                result.put("message","备份文件格式不正确。");
                return result;
            }
            JSONArray shortTerm = root.getJSONArray("shortTerm");
            JSONArray longTerm = root.getJSONArray("longTerm");
            JSONArray globalMemory = root.getJSONArray("globalMemory");
            JSONObject emotion = root.getJSONObject("emotion");
            JSONArray blacklist = root.getJSONArray("blacklist");
            JSONArray member = root.getJSONArray("member");
            backupAllMemories("before-restore-"+name);
            service.storage().update("DELETE FROM `"+RoleplayService.MEMORY_TABLE+"`");
            service.storage().update("DELETE FROM `"+RoleplayService.STATE_TABLE+"`");
            service.globalMemoryService().replaceAll(globalMemory);
            service.emotionService().restoreState(emotion);
            service.blacklistService().restoreAll(blacklist);
            service.memberService().restoreAll(member);
            int shortSaved = restoreShortTerm(shortTerm);
            int longSaved = restoreLongTerm(longTerm);
            service.clearRuntimeMemoryState();
            result.put("status",true);
            result.put("message","记忆已恢复。");
            result.put("shortTerm",shortSaved);
            result.put("longTerm",longSaved);
            result.put("globalMemory",globalMemory == null ? 0 : globalMemory.size());
            result.put("emotion",emotion != null);
            result.put("blacklist",blacklist == null ? 0 : blacklist.size());
            result.put("member",member == null ? 0 : member.size());
            service.plugin().getLogger().sendInfo("[记忆] 已从 "+name+" 恢复记忆：短期 "+shortSaved
                    +" 条，长期 "+longSaved+" 条，永久 "
                    +(globalMemory == null ? 0 : globalMemory.size())+" 条");
            return result;
        } catch (Exception e) {
            service.plugin().getLogger().sendException(e);
            result.put("status",false);
            result.put("message","恢复失败："+e.getMessage());
            return result;
        }
    }
    private File backupDirectory() {
        File directory = new File(service.plugin().getDataFolder(),"backup");
        if (!directory.exists()) directory.mkdirs();
        return directory;
    }

    private JSONArray exportShortTerm() {
        JSONArray result = new JSONArray();
        List<JSONObject> rows = service.storage().query(
                "SELECT `groupID`,`shortSummary`,`lastMemoryTime`,`lastMemoryID`,`lastMergeTime`,`updateTime` "
                        + "FROM `"+RoleplayService.STATE_TABLE+"` ORDER BY `groupID` ASC");
        if (rows == null) return result;
        for (JSONObject row : rows) {
            JSONObject item = new JSONObject(true);
            item.put("groupID",row.getLongValue("groupID"));
            item.put("shortSummary",row.getString("shortSummary"));
            item.put("lastMemoryTime",row.getLongValue("lastMemoryTime"));
            item.put("lastMemoryID",row.getLongValue("lastMemoryID"));
            item.put("lastMergeTime",row.getLongValue("lastMergeTime"));
            item.put("updateTime",row.getLongValue("updateTime"));
            result.add(item);
        }
        return result;
    }

    private JSONArray exportLongTerm() {
        JSONArray result = new JSONArray();
        List<JSONObject> rows = service.storage().query(
                "SELECT `groupID`,`memoryType`,`subjectID`,`content`,`importance`,`updateTime` "
                        + "FROM `"+RoleplayService.MEMORY_TABLE+"` ORDER BY `groupID` ASC,`ID` ASC");
        if (rows == null) return result;
        for (JSONObject row : rows) {
            JSONObject item = new JSONObject(true);
            item.put("groupID",row.getLongValue("groupID"));
            item.put("type",row.getString("memoryType"));
            item.put("subjectID",row.getLongValue("subjectID"));
            item.put("content",row.getString("content"));
            item.put("importance",row.getIntValue("importance"));
            item.put("updateTime",row.getLongValue("updateTime"));
            result.add(item);
        }
        return result;
    }

    private int restoreShortTerm(JSONArray memories) {
        if (memories == null) return 0;
        int saved = 0;
        for (Object object : memories) {
            if (!(object instanceof JSONObject)) continue;
            JSONObject item = (JSONObject) object;
            long groupID = item.getLongValue("groupID");
            if (groupID <= 0) continue;
            service.storage().insert("INSERT OR REPLACE INTO `"+RoleplayService.STATE_TABLE+"` "
                            + "(`groupID`,`shortSummary`,`lastMemoryTime`,`lastMemoryID`,`lastMergeTime`,`updateTime`) "
                            + "VALUES (?,?,?,?,?,?)",
                    groupID,service.safe(item.getString("shortSummary")),
                    item.getLongValue("lastMemoryTime"),item.getLongValue("lastMemoryID"),
                    item.getLongValue("lastMergeTime"),item.getLongValue("updateTime"));
            saved++;
        }
        return saved;
    }

    private int restoreLongTerm(JSONArray memories) {
        if (memories == null) return 0;
        int saved = 0;
        for (Object object : memories) {
            if (!(object instanceof JSONObject)) continue;
            JSONObject item = (JSONObject) object;
            long groupID = item.getLongValue("groupID");
            String content = service.safe(item.getString("content")).trim();
            if (groupID <= 0 || content.isEmpty()) continue;
            service.storage().insert("INSERT INTO `"+RoleplayService.MEMORY_TABLE+"` "
                            + "(`groupID`,`memoryType`,`subjectID`,`content`,`importance`,`updateTime`) "
                            + "VALUES (?,?,?,?,?,?)",
                    groupID,service.safe(item.getString("type")),item.getLongValue("subjectID"),
                    content,item.getIntValue("importance"),item.getLongValue("updateTime"));
            saved++;
        }
        return saved;
    }
}