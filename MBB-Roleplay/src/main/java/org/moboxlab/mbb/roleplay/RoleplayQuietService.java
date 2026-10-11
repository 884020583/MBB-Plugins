package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.Storage.StorageService;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 免打扰名单
 *
 * 名单内的群员不主动参与：角色只在对方主动叫到自己的时候才回复，
 * 其他情况一律保持沉默，其他群员不受影响。规则比黑名单轻。
 */
public class RoleplayQuietService {
    private static final String TABLE = "plugin_mbb_roleplay_quiet";
    /** 按群保存是否启用，未记录的群按默认值处理 */
    private static final String ENABLE_KEY = "roleplay-quiet-enable";

    private final Plugin plugin;
    private volatile RoleplayConfig config;
    private final Map<Long,Boolean> enabledCache = new ConcurrentHashMap<>();
    private final Map<Long,Set<Long>> userCache = new ConcurrentHashMap<>();

    public RoleplayQuietService(Plugin plugin,RoleplayConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void init() {
        storage().update("CREATE TABLE IF NOT EXISTS `"+TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0,"
                + "`userID` INTEGER NOT NULL DEFAULT 0,"
                + "`operatorID` INTEGER NOT NULL DEFAULT 0,"
                + "`createTime` INTEGER NOT NULL DEFAULT 0,"
                + "`updateTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        storage().update("CREATE UNIQUE INDEX IF NOT EXISTS `idx_plugin_mbb_roleplay_quiet_key` "
                + "ON `"+TABLE+"` (`groupID`,`userID`)");
        enabledCache.clear();
        userCache.clear();
    }

    public void reload(RoleplayConfig config) {
        this.config = config;
        //群默认开关可能变化，清理缓存后会按新配置重新取值
        enabledCache.clear();
    }

    /**
     * 当前群是否启用免打扰名单，未记录过时返回默认值
     */
    public boolean isEnabled(long groupID) {
        if (groupID <= 0) return false;
        Boolean cached = enabledCache.get(groupID);
        if (cached != null) return cached;
        String value = storage().get(plugin,ENABLE_KEY+"."+groupID);
        boolean enabled = value == null || value.trim().isEmpty()
                ? defaultEnabled() : "true".equalsIgnoreCase(value.trim());
        enabledCache.put(groupID,enabled);
        return enabled;
    }

    public boolean setEnabled(long groupID,boolean enabled) {
        if (groupID <= 0) return false;
        storage().set(plugin,ENABLE_KEY+"."+groupID,enabled ? "true" : "false");
        enabledCache.put(groupID,enabled);
        return true;
    }

    /**
     * 某个群员在当前群是否处于免打扰状态
     */
    public boolean contains(long groupID,long userID) {
        if (groupID <= 0 || userID <= 0) return false;
        return users(groupID).contains(userID);
    }

    /**
     * 是否应该对这条消息保持沉默
     * 名单未启用、群员不在名单、或对方主动叫到角色时都返回 false
     */
    public boolean shouldStayQuiet(long groupID,long userID,boolean addressedToSelf) {
        if (addressedToSelf) return false;
        if (!isEnabled(groupID)) return false;
        return contains(groupID,userID);
    }

    public boolean add(long groupID,long userID,long operatorID) {
        if (groupID <= 0 || userID <= 0) return false;
        long now = System.currentTimeMillis();
        long id = storage().insert("INSERT OR REPLACE INTO `"+TABLE+"` "
                        + "(`groupID`,`userID`,`operatorID`,`createTime`,`updateTime`) "
                        + "VALUES (?,?,?,?,?)",
                groupID,userID,operatorID,now,now);
        if (id <= 0) return false;
        users(groupID).add(userID);
        plugin.getLogger().sendInfo("[免打扰] 群"+groupID+" 用户"+userID+" 已加入名单"
                +"（操作者 "+operatorID+"）");
        return true;
    }

    public boolean remove(long groupID,long userID) {
        if (groupID <= 0 || userID <= 0) return false;
        int changed = storage().update("DELETE FROM `"+TABLE
                +"` WHERE `groupID`=? AND `userID`=?",groupID,userID);
        Set<Long> users = userCache.get(groupID);
        if (users != null) users.remove(userID);
        return changed > 0;
    }

    public void clear(long groupID) {
        if (groupID <= 0) return;
        storage().update("DELETE FROM `"+TABLE+"` WHERE `groupID`=?",groupID);
        userCache.remove(groupID);
    }

    public JSONArray list(long groupID) {
        JSONArray result = new JSONArray();
        if (groupID <= 0) return result;
        List<JSONObject> rows = storage().query("SELECT * FROM `"+TABLE
                +"` WHERE `groupID`=? ORDER BY `updateTime` DESC,`ID` DESC",groupID);
        if (rows == null) return result;
        for (JSONObject row : rows) {
            JSONObject item = new JSONObject(true);
            item.put("userID",row.getLongValue("userID"));
            item.put("operatorID",row.getLongValue("operatorID"));
            item.put("createTime",row.getLongValue("createTime"));
            item.put("updateTime",row.getLongValue("updateTime"));
            result.add(item);
        }
        return result;
    }

    public int count(long groupID) {
        if (groupID <= 0) return 0;
        JSONObject row = storage().queryOne("SELECT COUNT(*) AS `count` FROM `"+TABLE
                +"` WHERE `groupID`=?",groupID);
        return row == null ? 0 : row.getIntValue("count");
    }

    private boolean defaultEnabled() {
        RoleplayConfig current = config;
        return current == null || current.quietReplyEnable;
    }

    /**
     * 按群懒加载免打扰用户集合，避免每条消息都查库
     */
    private Set<Long> users(long groupID) {
        Set<Long> cached = userCache.get(groupID);
        if (cached != null) return cached;
        Set<Long> loaded = Collections.newSetFromMap(new ConcurrentHashMap<Long,Boolean>());
        List<JSONObject> rows = storage().query("SELECT `userID` FROM `"+TABLE
                +"` WHERE `groupID`=?",groupID);
        if (rows != null) {
            for (JSONObject row : rows) {
                long userID = row.getLongValue("userID");
                if (userID > 0) loaded.add(userID);
            }
        }
        Set<Long> previous = userCache.putIfAbsent(groupID,loaded);
        return previous == null ? loaded : previous;
    }

    private StorageService storage() {
        return plugin.getServer().getStorage();
    }
}
