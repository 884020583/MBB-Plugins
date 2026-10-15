package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Storage.StorageService;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 角色情绪状态仓库
 *
 * 负责情绪/关系/事件表、持久化、导入导出、JSON 映射和本地日期键。
 */
public class RoleplayEmotionStateRepository {
    private final RoleplayEmotionService service;
    private final AtomicInteger eventWriteCount = new AtomicInteger();

    public RoleplayEmotionStateRepository(RoleplayEmotionService service) {
        this.service = service;
    }

    public void init() {
        service.storage().update("CREATE TABLE IF NOT EXISTS `"+RoleplayEmotionService.MOOD_TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0 UNIQUE,"
                + "`valence` INTEGER NOT NULL DEFAULT 0,"
                + "`energy` INTEGER NOT NULL DEFAULT 0,"
                + "`patience` INTEGER NOT NULL DEFAULT 0,"
                + "`label` TEXT NOT NULL DEFAULT '',"
                + "`reason` TEXT NOT NULL DEFAULT '',"
                + "`lastEventTime` INTEGER NOT NULL DEFAULT 0,"
                + "`lastDecayTime` INTEGER NOT NULL DEFAULT 0,"
                + "`updateTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        service.storage().update("CREATE TABLE IF NOT EXISTS `"+RoleplayEmotionService.RELATION_TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0,"
                + "`userID` INTEGER NOT NULL DEFAULT 0,"
                + "`affinity` REAL NOT NULL DEFAULT 0,"
                + "`trust` REAL NOT NULL DEFAULT 0,"
                + "`annoyance` REAL NOT NULL DEFAULT 0,"
                + "`emotionLabel` TEXT NOT NULL DEFAULT '普通',"
                + "`emotionReason` TEXT NOT NULL DEFAULT '',"
                + "`reasonStrength` INTEGER NOT NULL DEFAULT 0,"
                + "`reasonSource` TEXT NOT NULL DEFAULT '',"
                + "`reasonSince` INTEGER NOT NULL DEFAULT 0,"
                + "`reasonExpire` INTEGER NOT NULL DEFAULT 0,"
                + "`lastInteraction` INTEGER NOT NULL DEFAULT 0,"
                + "`updateTime` INTEGER NOT NULL DEFAULT 0,"
                + "`dayKey` INTEGER NOT NULL DEFAULT 0,"
                + "`dayBaseAffinity` REAL NOT NULL DEFAULT 0,"
                + "`dayBaseTrust` REAL NOT NULL DEFAULT 0,"
                + "`dayBaseAnnoyance` REAL NOT NULL DEFAULT 0,"
                + "`dayDeltaAffinity` REAL NOT NULL DEFAULT 0,"
                + "`dayDeltaTrust` REAL NOT NULL DEFAULT 0,"
                + "`dayDeltaAnnoyance` REAL NOT NULL DEFAULT 0"
                + ")");
        service.storage().update("CREATE TABLE IF NOT EXISTS `"+RoleplayEmotionService.EVENT_TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0,"
                + "`userID` INTEGER NOT NULL DEFAULT 0,"
                + "`messageID` INTEGER NOT NULL DEFAULT 0,"
                + "`eventType` TEXT NOT NULL DEFAULT '',"
                + "`valenceDelta` INTEGER NOT NULL DEFAULT 0,"
                + "`energyDelta` INTEGER NOT NULL DEFAULT 0,"
                + "`patienceDelta` INTEGER NOT NULL DEFAULT 0,"
                + "`affinityDelta` REAL NOT NULL DEFAULT 0,"
                + "`trustDelta` REAL NOT NULL DEFAULT 0,"
                + "`annoyanceDelta` REAL NOT NULL DEFAULT 0,"
                + "`reason` TEXT NOT NULL DEFAULT '',"
                + "`source` TEXT NOT NULL DEFAULT '',"
                + "`createTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        ensureColumn(RoleplayEmotionService.RELATION_TABLE,"dayKey","INTEGER NOT NULL DEFAULT 0");
        ensureColumn(RoleplayEmotionService.RELATION_TABLE,"dayBaseAffinity","REAL NOT NULL DEFAULT 0");
        ensureColumn(RoleplayEmotionService.RELATION_TABLE,"dayBaseTrust","REAL NOT NULL DEFAULT 0");
        ensureColumn(RoleplayEmotionService.RELATION_TABLE,"dayBaseAnnoyance","REAL NOT NULL DEFAULT 0");
        ensureColumn(RoleplayEmotionService.RELATION_TABLE,"dayDeltaAffinity","REAL NOT NULL DEFAULT 0");
        ensureColumn(RoleplayEmotionService.RELATION_TABLE,"dayDeltaTrust","REAL NOT NULL DEFAULT 0");
        ensureColumn(RoleplayEmotionService.RELATION_TABLE,"dayDeltaAnnoyance","REAL NOT NULL DEFAULT 0");
        service.storage().update("CREATE UNIQUE INDEX IF NOT EXISTS `idx_plugin_mbb_roleplay_relation_key` "
                + "ON `"+RoleplayEmotionService.RELATION_TABLE+"` (`groupID`,`userID`)");
        service.storage().update("CREATE INDEX IF NOT EXISTS `idx_plugin_mbb_roleplay_emotion_event_group` "
                + "ON `"+RoleplayEmotionService.EVENT_TABLE+"` (`groupID`,`createTime`)");
        service.storage().update("CREATE INDEX IF NOT EXISTS `idx_plugin_mbb_roleplay_emotion_event_user` "
                + "ON `"+RoleplayEmotionService.EVENT_TABLE+"` (`groupID`,`userID`,`createTime`)");
        pruneEvents();
    }

    public synchronized JSONObject exportState() {
        JSONObject root = new JSONObject(true);
        root.put("mood",exportMood());
        root.put("relations",exportRelations());
        root.put("events",exportEvents());
        return root;
    }

    public synchronized void restoreState(JSONObject state) {
        if (state == null) return;
        service.storage().update("DELETE FROM `"+RoleplayEmotionService.MOOD_TABLE+"`");
        service.storage().update("DELETE FROM `"+RoleplayEmotionService.RELATION_TABLE+"`");
        service.storage().update("DELETE FROM `"+RoleplayEmotionService.EVENT_TABLE+"`");
        service.clearCache();
        JSONArray moods = state.getJSONArray("mood");
        if (moods != null) {
            for (Object object : moods) {
                if (!(object instanceof JSONObject)) continue;
                JSONObject json = (JSONObject) object;
                RoleplayEmotionService.MoodState mood = moodFromJson(json);
                saveMood(mood);
                service.moodCache.put(mood.groupID,mood);
            }
        }
        JSONArray relations = state.getJSONArray("relations");
        if (relations != null) {
            for (Object object : relations) {
                if (!(object instanceof JSONObject)) continue;
                JSONObject json = (JSONObject) object;
                RoleplayEmotionService.RelationState relation = relationFromJson(json);
                saveRelation(relation);
                service.relationCache.put(service.relationKey(relation.groupID,relation.userID),relation);
            }
        }
        JSONArray events = state.getJSONArray("events");
        if (events != null) {
            for (Object object : events) {
                if (!(object instanceof JSONObject)) continue;
                JSONObject json = (JSONObject) object;
                insertEvent(json.getLongValue("groupID"),json.getLongValue("userID"),
                        json.getLongValue("messageID"),json.getString("eventType"),
                        json.getIntValue("valenceDelta"),json.getIntValue("energyDelta"),
                        json.getIntValue("patienceDelta"),json.getDoubleValue("affinityDelta"),
                        json.getDoubleValue("trustDelta"),json.getDoubleValue("annoyanceDelta"),
                        json.getString("reason"),json.getString("source"),
                        json.getLongValue("createTime"));
            }
        }
    }

    RoleplayEmotionService.MoodState moodFromJson(JSONObject json) {
        RoleplayEmotionService.MoodState mood = new RoleplayEmotionService.MoodState();
        mood.groupID = json.getLongValue("groupID");
        mood.valence = service.clamp(json.getIntValue("valence"),0,100);
        mood.energy = service.clamp(json.getIntValue("energy"),0,100);
        mood.patience = service.clamp(json.getIntValue("patience"),0,100);
        mood.label = service.safe(json.getString("label"));
        mood.reason = service.safe(json.getString("reason"));
        mood.lastEventTime = json.getLongValue("lastEventTime");
        mood.lastDecayTime = json.getLongValue("lastDecayTime");
        mood.updateTime = json.getLongValue("updateTime");
        if (mood.label.isEmpty()) mood.label = service.moodLabel(mood);
        return mood;
    }

    RoleplayEmotionService.RelationState relationFromJson(JSONObject json) {
        RoleplayEmotionService.RelationState relation = new RoleplayEmotionService.RelationState();
        relation.groupID = json.getLongValue("groupID");
        relation.userID = json.getLongValue("userID");
        relation.affinity = service.clampDouble(json.getDoubleValue("affinity"),0,100);
        relation.trust = service.clampDouble(json.getDoubleValue("trust"),0,100);
        relation.annoyance = service.clampDouble(json.getDoubleValue("annoyance"),0,100);
        relation.emotionLabel = service.safe(json.getString("emotionLabel"));
        relation.emotionReason = service.safe(json.getString("emotionReason"));
        relation.reasonStrength = service.clamp(json.getIntValue("reasonStrength"),0,100);
        relation.reasonSource = service.safe(json.getString("reasonSource"));
        relation.reasonSince = json.getLongValue("reasonSince");
        relation.reasonExpire = json.getLongValue("reasonExpire");
        relation.lastInteraction = json.getLongValue("lastInteraction");
        relation.updateTime = json.getLongValue("updateTime");
        relation.dayKey = json.getIntValue("dayKey");
        relation.dayBaseAffinity = json.getDoubleValue("dayBaseAffinity");
        relation.dayBaseTrust = json.getDoubleValue("dayBaseTrust");
        relation.dayBaseAnnoyance = json.getDoubleValue("dayBaseAnnoyance");
        relation.dayDeltaAffinity = json.getDoubleValue("dayDeltaAffinity");
        relation.dayDeltaTrust = json.getDoubleValue("dayDeltaTrust");
        relation.dayDeltaAnnoyance = json.getDoubleValue("dayDeltaAnnoyance");
        if (relation.emotionLabel.isEmpty()) relation.emotionLabel = service.relationLabel(relation);
        return relation;
    }

    JSONObject moodJson(RoleplayEmotionService.MoodState mood) {
        JSONObject json = new JSONObject(true);
        json.put("groupID",mood.groupID);
        json.put("valence",mood.valence);
        json.put("energy",mood.energy);
        json.put("patience",mood.patience);
        json.put("label",mood.label);
        json.put("reason",mood.reason);
        json.put("lastEventTime",mood.lastEventTime);
        json.put("updateTime",mood.updateTime);
        return json;
    }

    JSONObject relationJson(RoleplayEmotionService.RelationState relation) {
        JSONObject json = new JSONObject(true);
        json.put("groupID",relation.groupID);
        json.put("userID",relation.userID);
        json.put("affinity",relation.affinity);
        json.put("trust",relation.trust);
        json.put("annoyance",relation.annoyance);
        json.put("emotionLabel",relation.emotionLabel);
        json.put("emotionReason",relation.emotionReason);
        json.put("reasonStrength",relation.reasonStrength);
        json.put("reasonSource",relation.reasonSource);
        json.put("reasonSince",relation.reasonSince);
        json.put("reasonExpire",relation.reasonExpire);
        json.put("lastInteraction",relation.lastInteraction);
        json.put("updateTime",relation.updateTime);
        json.put("dayKey",relation.dayKey);
        json.put("dayBaseAffinity",relation.dayBaseAffinity);
        json.put("dayBaseTrust",relation.dayBaseTrust);
        json.put("dayBaseAnnoyance",relation.dayBaseAnnoyance);
        json.put("dayDeltaAffinity",relation.dayDeltaAffinity);
        json.put("dayDeltaTrust",relation.dayDeltaTrust);
        json.put("dayDeltaAnnoyance",relation.dayDeltaAnnoyance);
        return json;
    }

    JSONArray exportMood() {
        JSONArray result = new JSONArray();
        List<JSONObject> rows = service.storage().query("SELECT * FROM `"+RoleplayEmotionService.MOOD_TABLE+"` ORDER BY `groupID` ASC");
        if (rows == null) return result;
        for (JSONObject row : rows) result.add(moodJson(moodFromJson(row)));
        return result;
    }

    JSONArray exportRelations() {
        JSONArray result = new JSONArray();
        List<JSONObject> rows = service.storage().query("SELECT * FROM `"+RoleplayEmotionService.RELATION_TABLE
                +"` ORDER BY `groupID` ASC,`userID` ASC");
        if (rows == null) return result;
        for (JSONObject row : rows) result.add(relationJson(relationFromJson(row)));
        return result;
    }

    JSONArray exportEvents() {
        JSONArray result = new JSONArray();
        List<JSONObject> rows = service.storage().query("SELECT * FROM `"+RoleplayEmotionService.EVENT_TABLE+"` ORDER BY `ID` ASC");
        if (rows == null) return result;
        for (JSONObject row : rows) {
            JSONObject item = new JSONObject(true);
            item.put("groupID",row.getLongValue("groupID"));
            item.put("userID",row.getLongValue("userID"));
            item.put("messageID",row.getLongValue("messageID"));
            item.put("eventType",row.getString("eventType"));
            item.put("valenceDelta",row.getIntValue("valenceDelta"));
            item.put("energyDelta",row.getIntValue("energyDelta"));
            item.put("patienceDelta",row.getIntValue("patienceDelta"));
            item.put("affinityDelta",row.getDoubleValue("affinityDelta"));
            item.put("trustDelta",row.getDoubleValue("trustDelta"));
            item.put("annoyanceDelta",row.getDoubleValue("annoyanceDelta"));
            item.put("reason",row.getString("reason"));
            item.put("source",row.getString("source"));
            item.put("createTime",row.getLongValue("createTime"));
            result.add(item);
        }
        return result;
    }

    JSONArray listEvents(long groupID,long userID,int limit) {
        if (limit < 1) limit = 12;
        if (limit > 100) limit = 100;
        List<JSONObject> rows;
        if (userID > 0) {
            rows = service.storage().query("SELECT * FROM `"+RoleplayEmotionService.EVENT_TABLE
                            +"` WHERE `groupID`=? AND `userID`=? ORDER BY `ID` DESC LIMIT ?",
                    groupID,userID,limit);
        } else {
            rows = service.storage().query("SELECT * FROM `"+RoleplayEmotionService.EVENT_TABLE
                    +"` WHERE `groupID`=? ORDER BY `ID` DESC LIMIT ?",groupID,limit);
        }
        JSONArray result = new JSONArray();
        if (rows == null) return result;
        for (int i = rows.size() - 1; i >= 0; i--) {
            JSONObject row = rows.get(i);
            JSONObject item = new JSONObject(true);
            item.put("groupID",row.getLongValue("groupID"));
            item.put("userID",row.getLongValue("userID"));
            item.put("messageID",row.getLongValue("messageID"));
            item.put("eventType",row.getString("eventType"));
            item.put("valenceDelta",row.getIntValue("valenceDelta"));
            item.put("energyDelta",row.getIntValue("energyDelta"));
            item.put("patienceDelta",row.getIntValue("patienceDelta"));
            item.put("affinityDelta",row.getDoubleValue("affinityDelta"));
            item.put("trustDelta",row.getDoubleValue("trustDelta"));
            item.put("annoyanceDelta",row.getDoubleValue("annoyanceDelta"));
            item.put("reason",row.getString("reason"));
            item.put("source",row.getString("source"));
            item.put("createTime",row.getLongValue("createTime"));
            result.add(item);
        }
        return result;
    }

    void saveMood(RoleplayEmotionService.MoodState mood) {
        service.storage().insert("INSERT OR REPLACE INTO `"+RoleplayEmotionService.MOOD_TABLE+"` "
                        + "(`groupID`,`valence`,`energy`,`patience`,`label`,`reason`,"
                        + "`lastEventTime`,`lastDecayTime`,`updateTime`) VALUES (?,?,?,?,?,?,?,?,?)",
                mood.groupID,mood.valence,mood.energy,mood.patience,mood.label,mood.reason,
                mood.lastEventTime,mood.lastDecayTime,mood.updateTime);
    }

    void saveRelation(RoleplayEmotionService.RelationState relation) {
        service.storage().insert("INSERT OR REPLACE INTO `"+RoleplayEmotionService.RELATION_TABLE+"` "
                        + "(`groupID`,`userID`,`affinity`,`trust`,`annoyance`,`emotionLabel`,"
                        + "`emotionReason`,`reasonStrength`,`reasonSource`,`reasonSince`,"
                        + "`reasonExpire`,`lastInteraction`,`updateTime`,`dayKey`,"
                        + "`dayBaseAffinity`,`dayBaseTrust`,`dayBaseAnnoyance`,"
                        + "`dayDeltaAffinity`,`dayDeltaTrust`,`dayDeltaAnnoyance`) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                relation.groupID,relation.userID,relation.affinity,relation.trust,
                relation.annoyance,relation.emotionLabel,relation.emotionReason,
                relation.reasonStrength,relation.reasonSource,relation.reasonSince,
                relation.reasonExpire,relation.lastInteraction,relation.updateTime,
                relation.dayKey,relation.dayBaseAffinity,relation.dayBaseTrust,
                relation.dayBaseAnnoyance,relation.dayDeltaAffinity,relation.dayDeltaTrust,
                relation.dayDeltaAnnoyance);
    }

    void insertEvent(long groupID,long userID,long messageID,String eventType,
                             int valence,int energy,int patience,double affinity,double trust,
                             double annoyance,String reason,String source,long createTime) {
        long time = createTime <= 0 ? System.currentTimeMillis() : createTime;
        service.storage().insert("INSERT INTO `"+RoleplayEmotionService.EVENT_TABLE+"` "
                        + "(`groupID`,`userID`,`messageID`,`eventType`,`valenceDelta`,`energyDelta`,"
                        + "`patienceDelta`,`affinityDelta`,`trustDelta`,`annoyanceDelta`,"
                        + "`reason`,`source`,`createTime`) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                groupID,userID,messageID,service.safe(eventType),valence,energy,patience,affinity,
                trust,annoyance,service.trimReason(reason),service.safe(source),time);
        if (eventWriteCount.incrementAndGet() % 200 == 0) pruneEvents();
    }

    void pruneEvents() {
        long cutoff = System.currentTimeMillis() - service.config().emotionEventRetentionDays * RoleplayEmotionService.DAY_MILLIS;
        service.storage().update("DELETE FROM `"+RoleplayEmotionService.EVENT_TABLE+"` WHERE `createTime`<?",cutoff);
    }

    void ensureColumn(String table,String column,String definition) {
        List<JSONObject> columns = service.storage().query("PRAGMA table_info(`"+table+"`)");
        if (columns != null) {
            for (JSONObject item : columns) {
                if (column.equalsIgnoreCase(item.getString("name"))) return;
            }
        }
        service.storage().update("ALTER TABLE `"+table+"` ADD COLUMN `"+column+"` "+definition);
    }

    long dayStart(long time) {
        TimeZone zone = TimeZone.getTimeZone(service.config().timeZone == null
                || service.config().timeZone.trim().isEmpty() ? "Asia/Shanghai" : service.config().timeZone.trim());
        Calendar calendar = Calendar.getInstance(zone,Locale.CHINA);
        calendar.setTime(new Date(time));
        calendar.set(Calendar.HOUR_OF_DAY,0);
        calendar.set(Calendar.MINUTE,0);
        calendar.set(Calendar.SECOND,0);
        calendar.set(Calendar.MILLISECOND,0);
        return calendar.getTimeInMillis();
    }

    int dayKey(long time) {
        TimeZone zone = TimeZone.getTimeZone(service.config().timeZone == null
                || service.config().timeZone.trim().isEmpty() ? "Asia/Shanghai" : service.config().timeZone.trim());
        Calendar calendar = Calendar.getInstance(zone,Locale.CHINA);
        calendar.setTime(new Date(time));
        return calendar.get(Calendar.YEAR) * 10000
                + (calendar.get(Calendar.MONTH) + 1) * 100
                + calendar.get(Calendar.DAY_OF_MONTH);
    }
}