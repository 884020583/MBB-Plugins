package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Event.GroupMessageEvent;
import org.moboxlab.moboxbot.API.Storage.StorageService;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 会话线程与回合管理
 *
 * 按群维护当前活跃线程、参与者、上一位发言者和角色是否被等待回应。
 */
public class RoleplayThreadService {
    private static final String TABLE = "plugin_mbb_roleplay_thread";
    private final RoleplayService service;
    private volatile RoleplayConfig config;

    public RoleplayThreadService(RoleplayService service) {
        this.service = service;
        this.config = service.config();
    }

    public void init() {
        storage().update("CREATE TABLE IF NOT EXISTS `"+TABLE+"` ("
                + "`groupID` INTEGER PRIMARY KEY,"
                + "`threadKey` TEXT NOT NULL DEFAULT '',"
                + "`participants` TEXT NOT NULL DEFAULT '',"
                + "`lastSpeakerID` INTEGER NOT NULL DEFAULT 0,"
                + "`lastSpeakerName` TEXT NOT NULL DEFAULT '',"
                + "`lastMessageID` INTEGER NOT NULL DEFAULT 0,"
                + "`lastTime` INTEGER NOT NULL DEFAULT 0,"
                + "`roleParticipated` INTEGER NOT NULL DEFAULT 0,"
                + "`waitingForRole` INTEGER NOT NULL DEFAULT 0,"
                + "`turn` TEXT NOT NULL DEFAULT 'none',"
                + "`updateTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
    }

    public void reload(RoleplayConfig config) {
        this.config = config;
    }

    public JSONObject observe(long groupID,GroupMessageEvent event,long selfID) {
        JSONObject result = new JSONObject(true);
        result.put("turn","none");
        result.put("hint","");
        if (!config.threadEnable || groupID <= 0 || event == null) return result;
        long now = System.currentTimeMillis();
        JSONObject row = storage().queryOne("SELECT * FROM `"+TABLE+"` WHERE `groupID`=?",groupID);
        String threadKey = row == null ? "" : safe(row.getString("threadKey"));
        long lastTime = row == null ? 0L : row.getLongValue("lastTime");
        if (threadKey.isEmpty() || now - lastTime > config.threadWindowSecond * 1000L) {
            threadKey = UUID.randomUUID().toString().replace("-","");
        }
        Set<String> participants = new LinkedHashSet<>();
        if (row != null) {
            for (String item : safe(row.getString("participants")).split(",")) {
                if (!item.trim().isEmpty()) participants.add(item.trim());
            }
        }
        long userID = event.getUserID();
        participants.add(String.valueOf(userID));
        boolean otherRole = service.isOtherRoleBot(event,selfID);
        boolean waiting = userID != selfID
                && (service.isMentioningSelf(event,selfID) || service.isQuotingSelf(event,selfID));
        boolean roleParticipated = row != null && row.getIntValue("roleParticipated") == 1;
        String turn = userID == selfID ? "role" : (otherRole ? "other-role" : "human");
        String speaker = service.senderName(event);
        if (row == null) {
            storage().insert("INSERT INTO `"+TABLE+"` "
                            + "(`groupID`,`threadKey`,`participants`,`lastSpeakerID`,`lastSpeakerName`,"
                            + "`lastMessageID`,`lastTime`,`roleParticipated`,`waitingForRole`,`turn`,`updateTime`) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    groupID,threadKey,String.join(",",participants),userID,speaker,
                    event.getMessageID(),now,roleParticipated ? 1 : 0,waiting ? 1 : 0,turn,now);
        } else {
            storage().update("UPDATE `"+TABLE+"` SET `threadKey`=?,`participants`=?,"
                            + "`lastSpeakerID`=?,`lastSpeakerName`=?,`lastMessageID`=?,`lastTime`=?,"
                            + "`roleParticipated`=?,`waitingForRole`=?,`turn`=?,`updateTime`=? WHERE `groupID`=?",
                    threadKey,String.join(",",participants),userID,speaker,
                    event.getMessageID(),now,roleParticipated ? 1 : 0,waiting ? 1 : 0,turn,now,groupID);
        }
        result.put("threadKey",threadKey);
        result.put("turn",turn);
        result.put("participants",participants.size());
        result.put("lastSpeakerID",userID);
        result.put("lastSpeakerName",speaker);
        result.put("waitingForRole",waiting);
        result.put("roleParticipated",roleParticipated);
        result.put("hint","会话线程：当前回合="+turn
                +"；参与者="+participants.size()+" 人"
                +"；上一位发言者="+speaker+"（QQ "+userID+"）"
                +"；当前消息"+(waiting ? "正在等待角色回应" : "没有明确等角色回应")
                +"；角色此前"+(roleParticipated ? "参与过该线程" : "尚未参与该线程")+"。");
        return result;
    }

    public JSONObject snapshot(long groupID) {
        JSONObject result = new JSONObject(true);
        if (groupID <= 0) return result;
        JSONObject row = storage().queryOne("SELECT * FROM `"+TABLE+"` WHERE `groupID`=?",groupID);
        if (row == null) return result;
        result.putAll(row);
        return result;
    }

    private StorageService storage() {
        return service.plugin().getServer().getStorage();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
