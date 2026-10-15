package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONObject;
import java.util.List;

/**
 * 角色数据库结构
 *
 * 负责建表、补列、索引和话题疲劳过期清理。
 */
public class RoleplaySchemaService {
    private final RoleplayService service;

    public RoleplaySchemaService(RoleplayService service) {
        this.service = service;
    }

    void initSchema() {
        service.storage().update("CREATE TABLE IF NOT EXISTS `"+RoleplayService.MSG_TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`messageID` INTEGER NOT NULL DEFAULT 0,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0,"
                + "`userID` INTEGER NOT NULL DEFAULT 0,"
                + "`userName` TEXT NOT NULL DEFAULT '',"
                + "`messageTime` INTEGER NOT NULL DEFAULT 0,"
                + "`content` TEXT NOT NULL DEFAULT '',"
                + "`isBot` INTEGER NOT NULL DEFAULT 0"
                + ")");
        service.storage().update("CREATE TABLE IF NOT EXISTS `"+RoleplayService.MEMORY_TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0,"
                + "`memoryType` TEXT NOT NULL DEFAULT '',"
                + "`subjectID` INTEGER NOT NULL DEFAULT 0,"
                + "`content` TEXT NOT NULL DEFAULT '',"
                + "`importance` INTEGER NOT NULL DEFAULT 1,"
                + "`updateTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        service.storage().update("CREATE TABLE IF NOT EXISTS `"+RoleplayService.STATE_TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0 UNIQUE,"
                + "`shortSummary` TEXT NOT NULL DEFAULT '',"
                + "`lastMemoryTime` INTEGER NOT NULL DEFAULT 0,"
                + "`lastMemoryID` INTEGER NOT NULL DEFAULT 0,"
                + "`lastMergeTime` INTEGER NOT NULL DEFAULT 0,"
                + "`updateTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        ensureColumn(RoleplayService.STATE_TABLE,"lastMemoryID","INTEGER NOT NULL DEFAULT 0");
        ensureColumn(RoleplayService.STATE_TABLE,"lastMergeTime","INTEGER NOT NULL DEFAULT 0");
        service.storage().update("CREATE TABLE IF NOT EXISTS `"+RoleplayService.GROUP_TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0 UNIQUE,"
                + "`enabled` INTEGER NOT NULL DEFAULT 0,"
                + "`contextToken` TEXT NOT NULL DEFAULT '',"
                + "`updateTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        service.storage().update("CREATE TABLE IF NOT EXISTS `"+RoleplayService.TOPIC_TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0,"
                + "`topicKey` TEXT NOT NULL DEFAULT '',"
                + "`lastTime` INTEGER NOT NULL DEFAULT 0,"
                + "`count` INTEGER NOT NULL DEFAULT 0,"
                + "`selfCount` INTEGER NOT NULL DEFAULT 0,"
                + "`lastSelfTime` INTEGER NOT NULL DEFAULT 0,"
                + "`updateTime` INTEGER NOT NULL DEFAULT 0,"
                + "UNIQUE(`groupID`,`topicKey`)"
                + ")");
        service.storage().update("DELETE FROM `"+RoleplayService.TOPIC_TABLE+"` WHERE `updateTime`<?",
                System.currentTimeMillis() - Math.max(1,service.config().topicFatigueKeepHours) * 3600000L);
        ensureColumn(RoleplayService.GROUP_TABLE,"contextToken","TEXT NOT NULL DEFAULT ''");
        service.storage().update("CREATE INDEX IF NOT EXISTS `idx_plugin_mbb_roleplay_msg_group` ON `"+RoleplayService.MSG_TABLE+"` (`groupID`,`messageTime`)");
    }

    void ensureColumn(String table,String column,String definition) {
        List<JSONObject> columns = service.storage().query("PRAGMA table_info(`"+table+"`)");
        if (columns != null) {
            for (JSONObject item : columns) {
                if (column.equalsIgnoreCase(service.safe(item.getString("name")))) return;
            }
        }
        service.storage().update("ALTER TABLE `"+table+"` ADD COLUMN `"+column+"` "+definition);
    }
}