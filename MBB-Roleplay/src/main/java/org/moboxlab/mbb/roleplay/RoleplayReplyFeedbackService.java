package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.Storage.StorageService;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主动发言效果反馈
 *
 * 记录角色每次主动发言之后，窗口内有没有真人接话。连续被忽略时降低主动接话概率，
 * 经常有人接话时保持正常参与意愿。统计按群保存，重启后恢复累计结果。
 */
public class RoleplayReplyFeedbackService {
    private static final String TABLE = "plugin_mbb_roleplay_reply_feedback";

    private static class Pending {
        private final long time;
        private long messageID;
        private boolean engaged;

        private Pending(long time,long messageID) {
            this.time = time;
            this.messageID = messageID;
        }
    }

    private final Plugin plugin;
    private volatile RoleplayConfig config;
    private final Map<Long,Deque<Boolean>> recent = new ConcurrentHashMap<>();
    private final Map<Long,long[]> totals = new ConcurrentHashMap<>();
    private final Map<Long,Pending> pending = new ConcurrentHashMap<>();

    public RoleplayReplyFeedbackService(Plugin plugin,RoleplayConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void init() {
        storage().update("CREATE TABLE IF NOT EXISTS `"+TABLE+"` ("
                + "`groupID` INTEGER PRIMARY KEY,"
                + "`successCount` INTEGER NOT NULL DEFAULT 0,"
                + "`ignoreCount` INTEGER NOT NULL DEFAULT 0,"
                + "`updateTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        loadTotals();
    }

    public void reload(RoleplayConfig config) {
        this.config = config;
    }

    /**
     * 角色成功发送一条消息后调用。短时间内的连续分段视为同一次发言。
     */
    public synchronized void onBotMessage(long groupID,long messageID,String text) {
        if (!config.replyFeedbackEnable || groupID <= 0) return;
        long now = System.currentTimeMillis();
        Pending current = pending.get(groupID);
        if (current != null && now - current.time < 5000L) {
            current.messageID = messageID;
            return;
        }
        finalizePending(groupID,now);
        pending.put(groupID,new Pending(now,messageID));
    }

    /**
     * 真人消息进入时调用。窗口内出现真人发言就视为这次主动发言被接住。
     */
    public synchronized void observeHumanMessage(long groupID,long userID,String content,long messageID) {
        if (!config.replyFeedbackEnable || groupID <= 0 || userID <= 0) return;
        finalizeIfExpired(groupID,System.currentTimeMillis());
        Pending current = pending.get(groupID);
        if (current == null) return;
        if (System.currentTimeMillis() - current.time > config.replyFeedbackWindowSecond * 1000L) return;
        current.engaged = true;
    }

    /**
     * 引用角色上一条消息，视为比普通接话更强的隐式反馈。
     */
    public synchronized void observeQuote(long groupID,long userID) {
        markEngaged(groupID,userID);
    }

    /**
     * 戳一戳角色，视为主动互动反馈。
     */
    public synchronized void observePoke(long groupID,long userID) {
        markEngaged(groupID,userID);
    }

    /**
     * 表情回应等平台反馈，有事件接入时调用。
     */
    public synchronized void observeReaction(long groupID,long userID) {
        markEngaged(groupID,userID);
    }

    private void markEngaged(long groupID,long userID) {
        if (!config.replyFeedbackEnable || groupID <= 0 || userID <= 0) return;
        finalizeIfExpired(groupID,System.currentTimeMillis());
        Pending current = pending.get(groupID);
        if (current == null) return;
        if (System.currentTimeMillis() - current.time > config.replyFeedbackWindowSecond * 1000L) return;
        current.engaged = true;
    }

    /**
     * 非直接消息的概率修正。直接艾特、引用和另一个机器人不受反馈惩罚。
     */
    public synchronized double adjustChance(long groupID,double chance,boolean direct,boolean otherRoleBot) {
        if (!config.replyFeedbackEnable || groupID <= 0 || direct || otherRoleBot) return clamp01(chance);
        finalizeIfExpired(groupID,System.currentTimeMillis());
        Deque<Boolean> values = recent.get(groupID);
        if (values == null || values.size() < config.replyFeedbackMinSamples) return clamp01(chance);
        int success = 0;
        for (Boolean value : values) if (Boolean.TRUE.equals(value)) success++;
        double rate = success / (double)values.size();
        double value = chance;
        if (rate < 0.35) {
            value -= config.replyFeedbackMaxPenalty * (0.35 - rate) / 0.35;
        } else if (rate > 0.70) {
            value += config.replyFeedbackBonus;
        }
        return clamp01(value);
    }

    /**
     * 给执行层看的短摘要，不写具体 QQ 和消息内容。
     */
    public synchronized String promptText(long groupID) {
        if (!config.replyFeedbackEnable || groupID <= 0) return "";
        finalizeIfExpired(groupID,System.currentTimeMillis());
        Deque<Boolean> values = recent.get(groupID);
        if (values == null || values.size() < config.replyFeedbackMinSamples) return "";
        int success = 0;
        for (Boolean value : values) if (Boolean.TRUE.equals(value)) success++;
        int total = values.size();
        int rate = (int)Math.round(success * 100.0 / total);
        return "最近主动接话反馈："+total+" 次主动发言里 "+success
                +" 次有人接话（"+rate+"%）。被接住时可以保持自然参与；连续没人接时少硬接，"
                +"不要为了刷存在感反复发言。\n";
    }

    public synchronized JSONObject stats(long groupID) {
        JSONObject result = new JSONObject(true);
        long[] total = totals.get(groupID);
        Deque<Boolean> values = recent.get(groupID);
        int success = 0;
        if (values != null) for (Boolean value : values) if (Boolean.TRUE.equals(value)) success++;
        result.put("groupID",groupID);
        result.put("successCount",total == null ? 0L : total[0]);
        result.put("ignoreCount",total == null ? 0L : total[1]);
        result.put("recentCount",values == null ? 0 : values.size());
        result.put("recentSuccess",success);
        return result;
    }

    public synchronized void reset(long groupID) {
        if (groupID <= 0) return;
        recent.remove(groupID);
        pending.remove(groupID);
        totals.remove(groupID);
        storage().update("DELETE FROM `"+TABLE+"` WHERE `groupID`=?",groupID);
    }

    private void finalizeIfExpired(long groupID,long now) {
        Pending current = pending.get(groupID);
        if (current == null) return;
        if (now - current.time <= config.replyFeedbackWindowSecond * 1000L) return;
        finalizePending(groupID,now);
    }

    private void finalizePending(long groupID,long now) {
        Pending current = pending.remove(groupID);
        if (current == null) return;
        boolean success = current.engaged;
        Deque<Boolean> values = recent.get(groupID);
        if (values == null) {
            values = new ArrayDeque<>();
            recent.put(groupID,values);
        }
        values.addLast(success);
        int limit = Math.max(5,config.replyFeedbackHistory);
        while (values.size() > limit) values.removeFirst();
        long[] total = totals.get(groupID);
        if (total == null) {
            total = new long[]{0L,0L};
            totals.put(groupID,total);
        }
        if (success) total[0]++; else total[1]++;
        saveTotals(groupID,total,now);
        plugin.getLogger().sendInfo("[反馈] 群"+groupID+" 主动发言"
                +(success ? "被接话" : "无人接话")
                +" 最近="+values.size()+" 累计成功="+total[0]+" 忽略="+total[1]);
    }

    private void loadTotals() {
        totals.clear();
        List<JSONObject> rows = storage().query("SELECT `groupID`,`successCount`,`ignoreCount` FROM `"+TABLE+"`");
        if (rows == null) return;
        for (JSONObject row : rows) {
            long groupID = row.getLongValue("groupID");
            if (groupID <= 0) continue;
            totals.put(groupID,new long[]{row.getLongValue("successCount"),row.getLongValue("ignoreCount")});
        }
    }

    private void saveTotals(long groupID,long[] total,long now) {
        storage().insert("INSERT OR REPLACE INTO `"+TABLE+"` "
                        + "(`groupID`,`successCount`,`ignoreCount`,`updateTime`) VALUES (?,?,?,?)",
                groupID,total[0],total[1],now);
    }

    private double clamp01(double value) {
        if (value < 0) return 0;
        if (value > 1) return 1;
        return value;
    }

    private StorageService storage() {
        return plugin.getServer().getStorage();
    }
}
