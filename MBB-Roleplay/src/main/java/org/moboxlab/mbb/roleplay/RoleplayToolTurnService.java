package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.Storage.StorageService;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具回合持久化。
 *
 * 每个工具回合保存原始事件、消息上下文和请求参数，进程中断后可继续生成最终回复。
 */
public class RoleplayToolTurnService {
    private static final String TABLE = "plugin_mbb_roleplay_tool_turn";
    private final Plugin plugin;
    private volatile RoleplayConfig config;

    public RoleplayToolTurnService(Plugin plugin,RoleplayConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void init() {
        storage().update("CREATE TABLE IF NOT EXISTS `"+TABLE+"` ("
                + "`turnToken` TEXT PRIMARY KEY,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0,"
                + "`userID` INTEGER NOT NULL DEFAULT 0,"
                + "`messageID` INTEGER NOT NULL DEFAULT 0,"
                + "`selfID` INTEGER NOT NULL DEFAULT 0,"
                + "`userName` TEXT NOT NULL DEFAULT '',"
                + "`relationship` TEXT NOT NULL DEFAULT '',"
                + "`message` TEXT NOT NULL DEFAULT '',"
                + "`eventRaw` TEXT NOT NULL DEFAULT '',"
                + "`messages` TEXT NOT NULL DEFAULT '[]',"
                + "`liveMessages` TEXT NOT NULL DEFAULT '[]',"
                + "`params` TEXT NOT NULL DEFAULT '{}',"
                + "`status` TEXT NOT NULL DEFAULT 'running',"
                + "`createTime` INTEGER NOT NULL DEFAULT 0,"
                + "`updateTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        ensureColumn("liveMessages","TEXT NOT NULL DEFAULT '[]'");
        storage().update("CREATE INDEX IF NOT EXISTS `idx_mbb_roleplay_tool_turn_status` "
                + "ON `"+TABLE+"` (`status`,`updateTime`)");
        cleanup();
    }

    public void reload(RoleplayConfig config) {
        this.config = config;
    }

    public void begin(String token,RoleplayToolContext context,JSONArray messages,JSONObject params) {
        if (!enabled() || token == null || context == null) return;
        long now = System.currentTimeMillis();
        storage().insert("INSERT OR REPLACE INTO `"+TABLE+"` "
                        + "(`turnToken`,`groupID`,`userID`,`messageID`,`selfID`,`userName`,"
                        + "`relationship`,`message`,`eventRaw`,`messages`,`liveMessages`,`params`,`status`,"
                        + "`createTime`,`updateTime`) VALUES (?,?,?,?,?,?,?,?,?,?,'[]',?,'running',?,?)",
                token,context.groupID,context.userID,context.messageID,context.selfID,
                safe(context.userName),safe(context.relationship),safe(context.message),
                safe(context.eventRaw),messages == null ? "[]" : messages.toJSONString(),
                params == null ? "{}" : params.toJSONString(),now,now);
    }

    public void update(String token,JSONArray messages,JSONObject params) {
        if (!enabled() || token == null) return;
        storage().update("UPDATE `"+TABLE+"` SET `messages`=?,`params`=?,`updateTime`=? "
                        + "WHERE `turnToken`=?",
                messages == null ? "[]" : messages.toJSONString(),
                params == null ? "{}" : params.toJSONString(),System.currentTimeMillis(),token);
    }

    public void appendLive(String token,JSONObject message) {
        if (!enabled() || token == null || message == null) return;
        JSONArray current = liveMessages(token);
        current.add(message);
        storage().update("UPDATE `"+TABLE+"` SET `liveMessages`=?,`updateTime`=? WHERE `turnToken`=?",
                current.toJSONString(),System.currentTimeMillis(),token);
    }

    public void clearLive(String token) {
        if (!enabled() || token == null) return;
        storage().update("UPDATE `"+TABLE+"` SET `liveMessages`='[]',`updateTime`=? WHERE `turnToken`=?",
                System.currentTimeMillis(),token);
    }

    public void finish(String token,String status) {
        if (!enabled() || token == null) return;
        storage().update("UPDATE `"+TABLE+"` SET `status`=?,`updateTime`=? WHERE `turnToken`=?",
                safe(status).isEmpty() ? "finished" : safe(status),
                System.currentTimeMillis(),token);
    }

    public List<RecoveredTurn> recoverable() {
        List<RecoveredTurn> result = new ArrayList<>();
        if (!enabled()) return result;
        long cutoff = System.currentTimeMillis()
                -Math.max(5,config.toolRecoverMaxMinutes) * 60000L;
        List<JSONObject> rows = storage().query("SELECT * FROM `"+TABLE+"` "
                + "WHERE `status` IN ('running','interrupted','recovering') AND `updateTime`>=? "
                + "ORDER BY `updateTime` ASC LIMIT 20",cutoff);
        if (rows == null) return result;
        for (JSONObject row : rows) {
            RecoveredTurn turn = fromRow(row);
            if (turn != null) result.add(turn);
        }
        return result;
    }

    public void cleanup() {
        long cutoff = System.currentTimeMillis()
                -Math.max(1,config.toolTurnKeepHours) * 3600000L;
        storage().update("DELETE FROM `"+TABLE+"` WHERE `updateTime`<?",cutoff);
    }

    private RecoveredTurn fromRow(JSONObject row) {
        try {
            RecoveredTurn turn = new RecoveredTurn();
            turn.token = safe(row.getString("turnToken"));
            turn.context = new RoleplayToolContext();
            turn.context.groupID = row.getLongValue("groupID");
            turn.context.userID = row.getLongValue("userID");
            turn.context.messageID = row.getLongValue("messageID");
            turn.context.selfID = row.getLongValue("selfID");
            turn.context.userName = safe(row.getString("userName"));
            turn.context.relationship = safe(row.getString("relationship"));
            turn.context.message = safe(row.getString("message"));
            turn.context.eventRaw = safe(row.getString("eventRaw"));
            turn.messages = JSONArray.parseArray(safe(row.getString("messages")));
            turn.liveMessages = JSONArray.parseArray(safe(row.getString("liveMessages")));
            turn.params = JSONObject.parseObject(safe(row.getString("params")));
            if (turn.messages == null) turn.messages = new JSONArray();
            if (turn.liveMessages == null) turn.liveMessages = new JSONArray();
            if (turn.params == null) turn.params = new JSONObject(true);
            turn.updatedAt = row.getLongValue("updateTime");
            return turn;
        } catch (Exception e) {
            plugin.getLogger().sendWarn("[工具] 读取未完成工具回合失败："+safe(e.getMessage()));
            return null;
        }
    }

    private boolean enabled() {
        return config != null && config.toolEnable && config.toolTurnPersistEnable;
    }

    private void ensureColumn(String name,String definition) {
        List<JSONObject> columns = storage().query("PRAGMA table_info(`"+TABLE+"`)");
        if (columns != null) {
            for (JSONObject column : columns) {
                if (name.equalsIgnoreCase(column.getString("name"))) return;
            }
        }
        storage().update("ALTER TABLE `"+TABLE+"` ADD COLUMN `"+name+"` "+definition);
    }

    private JSONArray liveMessages(String token) {
        JSONObject row = storage().queryOne("SELECT `liveMessages` FROM `"+TABLE+"` "
                + "WHERE `turnToken`=?",token);
        JSONArray result = row == null ? null
                : JSONArray.parseArray(safe(row.getString("liveMessages")));
        return result == null ? new JSONArray() : result;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private StorageService storage() {
        return plugin.getServer().getStorage();
    }

    public static class RecoveredTurn {
        public String token;
        public RoleplayToolContext context;
        public JSONArray messages;
        public JSONArray liveMessages;
        public JSONObject params;
        public long updatedAt;
    }
}
