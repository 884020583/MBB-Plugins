package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.Storage.StorageService;

import java.util.List;

/**
 * 对话链路 Trace
 *
 * 记录每轮路由、资料和回复结果，便于定位角色为什么这样回复。
 */
public class RoleplayTraceService {
    private static final String TABLE = "plugin_mbb_roleplay_trace";
    private final Plugin plugin;
    private volatile RoleplayConfig config;

    public RoleplayTraceService(Plugin plugin,RoleplayConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void init() {
        storage().update("CREATE TABLE IF NOT EXISTS `"+TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0,"
                + "`userID` INTEGER NOT NULL DEFAULT 0,"
                + "`messageID` INTEGER NOT NULL DEFAULT 0,"
                + "`stage` TEXT NOT NULL DEFAULT '',"
                + "`data` TEXT NOT NULL DEFAULT '',"
                + "`createTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        storage().update("CREATE INDEX IF NOT EXISTS `idx_plugin_mbb_roleplay_trace_group` "
                + "ON `"+TABLE+"` (`groupID`,`createTime`)");
        cleanup();
    }

    public void reload(RoleplayConfig config) {
        this.config = config;
    }

    public void record(long groupID,long userID,long messageID,String stage,JSONObject data) {
        if (!config.traceEnable || groupID <= 0) return;
        JSONObject payload = data == null ? new JSONObject(true) : data;
        storage().insert("INSERT INTO `"+TABLE+"` "
                        + "(`groupID`,`userID`,`messageID`,`stage`,`data`,`createTime`) VALUES (?,?,?,?,?,?)",
                groupID,userID,messageID,stage == null ? "" : stage,
                payload.toJSONString(),System.currentTimeMillis());
    }

    public JSONArray list(long groupID,int limit) {
        JSONArray result = new JSONArray();
        if (groupID <= 0) return result;
        if (limit < 1) limit = 1;
        if (limit > 50) limit = 50;
        List<JSONObject> rows = storage().query("SELECT `ID`,`userID`,`messageID`,`stage`,`data`,`createTime` "
                + "FROM `"+TABLE+"` WHERE `groupID`=? ORDER BY `ID` DESC LIMIT "+limit,groupID);
        if (rows == null) return result;
        for (JSONObject row : rows) {
            JSONObject item = new JSONObject(true);
            item.put("id",row.getLongValue("ID"));
            item.put("userID",row.getLongValue("userID"));
            item.put("messageID",row.getLongValue("messageID"));
            item.put("stage",row.getString("stage"));
            item.put("createTime",row.getLongValue("createTime"));
            try {
                item.put("data",JSON.parseObject(row.getString("data")));
            } catch (Exception ignored) {
                item.put("data",new JSONObject(true));
            }
            result.add(item);
        }
        return result;
    }

    public void cleanup() {
        long cutoff = System.currentTimeMillis() - Math.max(1,config.traceKeepHours) * 3600000L;
        storage().update("DELETE FROM `"+TABLE+"` WHERE `createTime`<?",cutoff);
        storage().update("DELETE FROM `"+TABLE+"` WHERE `ID` NOT IN "
                + "(SELECT `ID` FROM `"+TABLE+"` ORDER BY `ID` DESC LIMIT "+Math.max(100,config.traceMaxRows)+")");
    }

    private StorageService storage() {
        return plugin.getServer().getStorage();
    }
}
