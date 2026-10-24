package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.Storage.StorageService;

import java.util.List;
import java.util.Locale;

/**
 * 角色和用户的待办工作流。
 */
public class RoleplayTodoService {
    private static final String TABLE = "plugin_mbb_roleplay_todo";
    private final Plugin plugin;
    private final RoleplayService service;
    private final RoleplayReminderService reminderService;
    private final RoleplayTimeService timeService;

    public RoleplayTodoService(Plugin plugin,RoleplayService service,
                               RoleplayReminderService reminderService,
                               RoleplayTimeService timeService) {
        this.plugin = plugin;
        this.service = service;
        this.reminderService = reminderService;
        this.timeService = timeService;
    }

    public void init() {
        storage().update("CREATE TABLE IF NOT EXISTS `"+TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0,"
                + "`userID` INTEGER NOT NULL DEFAULT 0,"
                + "`userName` TEXT NOT NULL DEFAULT '',"
                + "`scope` TEXT NOT NULL DEFAULT 'user',"
                + "`title` TEXT NOT NULL DEFAULT '',"
                + "`status` TEXT NOT NULL DEFAULT 'pending',"
                + "`dueTime` INTEGER NOT NULL DEFAULT 0,"
                + "`remind` INTEGER NOT NULL DEFAULT 0,"
                + "`reminderID` INTEGER NOT NULL DEFAULT 0,"
                + "`createTime` INTEGER NOT NULL DEFAULT 0,"
                + "`updateTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        storage().update("CREATE INDEX IF NOT EXISTS `idx_plugin_mbb_roleplay_todo_scope` "
                + "ON `"+TABLE+"` (`groupID`,`userID`,`status`,`dueTime`)");
    }

    public JSONObject add(long groupID,long userID,String userName,String title,
                          String dueText,String scope,boolean remind) {
        String scopeValue = normalizeScope(scope);
        if ("all".equals(scopeValue)) scopeValue = "user";
        if ("self".equals(scopeValue) && !service.isOwner(userID)) {
            throw new IllegalArgumentException("只有 owner 可以创建角色自身待办");
        }
        String value = safe(title).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("待办内容不能为空");
        if (value.length() > 200) value = value.substring(0,200);
        long now = System.currentTimeMillis();
        long dueTime = 0L;
        if (!safe(dueText).trim().isEmpty()) {
            dueTime = timeService.parse(dueText,now);
            if (dueTime <= now) throw new IllegalArgumentException("待办时间无效或已经过去");
        }
        long reminderID = 0L;
        if (remind && dueTime > 0) {
            boolean created = reminderService.createFromAi(groupID,userID,safe(userName),
                    "朋友",timeService.format(dueTime),value,
                    "self".equals(scopeValue) ? "self" : "user",value);
            if (created) {
                JSONObject pending = reminderService.getPendingForTask(
                        groupID,userID,dueTime,value,"self".equals(scopeValue) ? "self" : "user");
                if (pending != null) reminderID = pending.getLongValue("ID");
            }
        }
        long id = storage().insert("INSERT INTO `"+TABLE+"` "
                        + "(`groupID`,`userID`,`userName`,`scope`,`title`,`status`,`dueTime`,"
                        + "`remind`,`reminderID`,`createTime`,`updateTime`) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                groupID,userID,safe(userName),scopeValue,value,"pending",dueTime,
                remind ? 1 : 0,reminderID,now,now);
        plugin.getLogger().sendInfo("[待办] 新增 #"+id+" 群"+groupID+" 用户"+userID
                +" 范围="+scopeValue+" 内容="+shortText(value,80));
        return item(id,groupID,userID,userName,scopeValue,value,"pending",dueTime,
                remind ? 1 : 0,reminderID,now,now);
    }

    public JSONObject list(long groupID,long userID,boolean owner,String scope,
                           boolean includeDone,int limit) {
        String scopeValue = normalizeScope(scope);
        StringBuilder sql = new StringBuilder("SELECT * FROM `").append(TABLE)
                .append("` WHERE `groupID`=?");
        if ("self".equals(scopeValue)) {
            if (!owner) throw new IllegalArgumentException("只有 owner 可以查看角色自身待办");
            sql.append(" AND `scope`='self'");
        } else if ("user".equals(scopeValue)) {
            sql.append(" AND `scope`='user' AND `userID`=?");
        } else {
            if (owner) sql.append(" AND (`scope`='self' OR (`scope`='user' AND `userID`=?))");
            else sql.append(" AND `scope`='user' AND `userID`=?");
        }
        if (!includeDone) sql.append(" AND `status`='pending'");
        sql.append(" ORDER BY `status` ASC,`dueTime` ASC,`updateTime` DESC LIMIT ?");
        int cap = Math.max(1,Math.min(100,limit <= 0 ? 30 : limit));
        List<JSONObject> rows;
        if ("self".equals(scopeValue)) {
            rows = storage().query(sql.toString(),groupID,cap);
        } else {
            rows = storage().query(sql.toString(),groupID,userID,cap);
        }
        JSONArray items = new JSONArray();
        if (rows != null) {
            long now = System.currentTimeMillis();
            for (JSONObject row : rows) {
                JSONObject item = toItem(row,now);
                items.add(item);
            }
        }
        JSONObject result = new JSONObject(true);
        result.put("items",items);
        result.put("count",items.size());
        result.put("scope",scopeValue);
        return result;
    }

    public JSONObject done(long id,long groupID,long userID,boolean owner) {
        JSONObject row = owned(id,groupID,userID,owner);
        if (row == null) throw new IllegalArgumentException("没有找到可操作的待办");
        long now = System.currentTimeMillis();
        storage().update("UPDATE `"+TABLE+"` SET `status`='done',`updateTime`=? WHERE `ID`=?",
                now,id);
        long reminderID = row.getLongValue("reminderID");
        if (reminderID > 0) {
            reminderService.cancel(reminderID,groupID,row.getLongValue("userID"));
        }
        return toItem(storage().queryOne("SELECT * FROM `"+TABLE+"` WHERE `ID`=?",id),now);
    }

    public JSONObject snooze(long id,long groupID,long userID,boolean owner,
                             String dueText,int minutes) {
        JSONObject row = owned(id,groupID,userID,owner);
        if (row == null) throw new IllegalArgumentException("没有找到可操作的待办");
        long now = System.currentTimeMillis();
        long dueTime = 0L;
        if (!safe(dueText).trim().isEmpty()) dueTime = timeService.parse(dueText,now);
        if (dueTime <= now && minutes > 0) {
            dueTime = now + Math.max(1,Math.min(30 * 24 * 60,minutes)) * 60000L;
        }
        if (dueTime <= now) throw new IllegalArgumentException("snooze 需要 dueTime 或正整数 minutes");
        long reminderID = row.getLongValue("reminderID");
        if (reminderID > 0) {
            boolean updated = reminderService.update(reminderID,groupID,
                    row.getLongValue("userID"),dueTime,safe(row.getString("title")));
            if (!updated) reminderID = 0L;
        }
        if (reminderID <= 0 && row.getIntValue("remind") == 1) {
            boolean created = reminderService.createFromAi(groupID,row.getLongValue("userID"),
                    safe(row.getString("userName")),"朋友",timeService.format(dueTime),
                    safe(row.getString("title")),
                    "self".equals(row.getString("scope")) ? "self" : "user",
                    safe(row.getString("title")));
            if (created) {
                JSONObject pending = reminderService.getPendingForTask(groupID,
                        row.getLongValue("userID"),dueTime,safe(row.getString("title")),
                        "self".equals(row.getString("scope")) ? "self" : "user");
                if (pending != null) reminderID = pending.getLongValue("ID");
            }
        }
        storage().update("UPDATE `"+TABLE+"` SET `status`='pending',`dueTime`=?,"
                        + "`reminderID`=?,`updateTime`=? WHERE `ID`=?",
                dueTime,reminderID,now,id);
        return toItem(storage().queryOne("SELECT * FROM `"+TABLE+"` WHERE `ID`=?",id),now);
    }

    private JSONObject owned(long id,long groupID,long userID,boolean owner) {
        JSONObject row = storage().queryOne("SELECT * FROM `"+TABLE+"` WHERE `ID`=? AND `groupID`=?",
                id,groupID);
        if (row == null) return null;
        String scope = safe(row.getString("scope"));
        if ("self".equals(scope)) return owner ? row : null;
        return row.getLongValue("userID") == userID ? row : null;
    }

    private JSONObject toItem(JSONObject row,long now) {
        if (row == null) return new JSONObject(true);
        JSONObject item = new JSONObject(true);
        item.put("id",row.getLongValue("ID"));
        item.put("scope",safe(row.getString("scope")));
        item.put("title",safe(row.getString("title")));
        item.put("status",safe(row.getString("status")));
        item.put("dueTime",row.getLongValue("dueTime"));
        item.put("dueText",row.getLongValue("dueTime") <= 0
                ? "" : timeService.format(row.getLongValue("dueTime")));
        item.put("overdue","pending".equals(row.getString("status"))
                && row.getLongValue("dueTime") > 0 && row.getLongValue("dueTime") < now);
        item.put("reminderID",row.getLongValue("reminderID"));
        item.put("userID",row.getLongValue("userID"));
        item.put("userName",safe(row.getString("userName")));
        item.put("updateTime",row.getLongValue("updateTime"));
        return item;
    }

    private JSONObject item(long id,long groupID,long userID,String userName,
                            String scope,String title,String status,long dueTime,
                            int remind,long reminderID,long createTime,long updateTime) {
        JSONObject row = new JSONObject(true);
        row.put("ID",id);
        row.put("groupID",groupID);
        row.put("userID",userID);
        row.put("userName",safe(userName));
        row.put("scope",scope);
        row.put("title",title);
        row.put("status",status);
        row.put("dueTime",dueTime);
        row.put("remind",remind);
        row.put("reminderID",reminderID);
        row.put("createTime",createTime);
        row.put("updateTime",updateTime);
        return toItem(row,System.currentTimeMillis());
    }

    private String normalizeScope(String scope) {
        String value = safe(scope).trim().toLowerCase(Locale.ROOT);
        if ("self".equals(value) || "user".equals(value)) return value;
        return "all";
    }

    private String shortText(String value,int max) {
        String text = safe(value);
        return text.length() <= max ? text : text.substring(0,max)+"...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private StorageService storage() {
        return plugin.getServer().getStorage();
    }
}
