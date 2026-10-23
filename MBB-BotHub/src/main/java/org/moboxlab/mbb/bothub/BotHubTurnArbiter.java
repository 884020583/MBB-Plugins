package org.moboxlab.mbb.bothub;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回合权仲裁
 *
 * 两个 bot 同时收到同一条消息时，不再用「先到先得」，而是按优先级分决定：
 *   被点名 100 / 被引用 80 / 延续自己参与的线程 60 / 泛群聊插话 40
 * 分数相同就用 botID 的稳定哈希比大小，保证两个进程各自独立算出的结果一致，
 * 不需要额外一轮通信来「商量」。
 *
 * 双方都被点名时走 multi 分支：都放行，但按加入顺序分配错峰延迟。
 */
public class BotHubTurnArbiter {
    private final Map<String,Turn> turns = new ConcurrentHashMap<>();

    public Result claim(String key,String botID,int score,long leaseMillis,boolean multi,long staggerMillis) {
        Result rejected = new Result();
        if (key == null || key.trim().isEmpty() || botID == null || botID.isEmpty()) {
            rejected.granted = false;
            return rejected;
        }
        String value = key.trim();
        long now = System.currentTimeMillis();
        long lease = Math.max(1000L,leaseMillis);
        final Result[] holder = new Result[1];
        turns.compute(value,(itemKey,existing) -> {
            Result result = new Result();
            result.score = score;
            result.ownerID = botID;
            if (existing == null || existing.expired(now)) {
                Turn created = new Turn();
                created.owner = botID;
                created.score = score;
                created.expireAt = now + lease;
                created.createdAt = now;
                created.multi = multi;
                created.multiBots.add(botID);
                result.granted = true;
                result.winnerScore = score;
                holder[0] = result;
                return created;
            }
            if (botID.equals(existing.owner)) {
                existing.expireAt = now + lease;
                if (score > existing.score) existing.score = score;
                result.granted = true;
                result.winnerScore = existing.score;
                holder[0] = result;
                return existing;
            }
            if (existing.multi || multi) {
                //双方都被点名：都放行，按加入顺序错峰，避免同时刷两条
                existing.multi = true;
                existing.expireAt = now + lease;
                int index = existing.multiBots.size();
                if (!existing.multiBots.contains(botID)) {
                    existing.multiBots.add(botID);
                } else {
                    index = Math.max(0,index - 1);
                }
                result.granted = true;
                result.winnerScore = existing.score;
                result.staggerMs = Math.max(0L,staggerMillis) * index;
                holder[0] = result;
                return existing;
            }
            if (shouldWin(score,botID,existing.score,existing.owner)) {
                result.granted = true;
                result.preempted = true;
                result.previousOwner = existing.owner;
                result.winnerScore = score;
                existing.owner = botID;
                existing.score = score;
                existing.expireAt = now + lease;
                existing.multiBots.clear();
                existing.multiBots.add(botID);
                holder[0] = result;
                return existing;
            }
            result.granted = false;
            result.winnerScore = existing.score;
            holder[0] = result;
            return existing;
        });
        Result result = holder[0];
        return result == null ? rejected : result;
    }

    public boolean release(String key,String botID) {
        if (key == null || key.trim().isEmpty()) return false;
        String value = key.trim();
        final boolean[] released = new boolean[1];
        turns.compute(value,(itemKey,existing) -> {
            if (existing == null) return null;
            if (botID != null && !botID.isEmpty() && !botID.equals(existing.owner)
                    && !existing.multiBots.contains(botID)) {
                return existing;
            }
            released[0] = true;
            return null;
        });
        return released[0];
    }

    public String owner(String key) {
        if (key == null || key.trim().isEmpty()) return "";
        Turn turn = turns.get(key.trim());
        if (turn == null) return "";
        if (turn.expired(System.currentTimeMillis())) {
            turns.remove(key.trim());
            return "";
        }
        return turn.owner;
    }

    public JSONObject describe(String key) {
        JSONObject json = new JSONObject(true);
        String value = key == null ? "" : key.trim();
        Turn turn = value.isEmpty() ? null : turns.get(value);
        if (turn != null && turn.expired(System.currentTimeMillis())) {
            turns.remove(value);
            turn = null;
        }
        json.put("key",value);
        json.put("owner",turn == null ? "" : turn.owner);
        json.put("score",turn == null ? 0 : turn.score);
        json.put("multi",turn != null && turn.multi);
        json.put("expireAt",turn == null ? 0L : turn.expireAt);
        return json;
    }

    public void releaseOwner(String botID) {
        if (botID == null || botID.isEmpty()) return;
        List<String> keys = new ArrayList<>();
        for (Turn turn : turns.values()) {
            if (botID.equals(turn.owner) || turn.multiBots.contains(botID)) keys.add(turnKeyOf(turn));
        }
        for (String key : keys) turns.remove(key);
    }

    public int clear() {
        int size = turns.size();
        turns.clear();
        return size;
    }

    public int size() {
        purge();
        return turns.size();
    }

    public JSONArray snapshot() {
        purge();
        JSONArray array = new JSONArray();
        for (Turn turn : turns.values()) {
            JSONObject json = new JSONObject(true);
            json.put("owner",turn.owner);
            json.put("score",turn.score);
            json.put("multi",turn.multi);
            json.put("expireAt",turn.expireAt);
            array.add(json);
        }
        return array;
    }

    private String turnKeyOf(Turn turn) {
        for (Map.Entry<String,Turn> item : turns.entrySet()) {
            if (item.getValue() == turn) return item.getKey();
        }
        return "";
    }

    /**
     * 分数高者胜；同分用稳定哈希比大小，保证两个进程算出的结果一致
     */
    private boolean shouldWin(int score,String botID,int winnerScore,String winnerBot) {
        if (score != winnerScore) return score > winnerScore;
        return stableRank(botID) < stableRank(winnerBot);
    }

    private int stableRank(String botID) {
        int hash = 0x811c9dc5;
        if (botID != null) {
            for (int i = 0; i < botID.length(); i++) {
                hash ^= botID.charAt(i);
                hash *= 0x01000193;
            }
        }
        return hash & 0x7fffffff;
    }

    private void purge() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String,Turn>> iterator = turns.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expired(now)) iterator.remove();
        }
    }

    private static class Turn {
        String owner = "";
        int score;
        long expireAt;
        long createdAt;
        boolean multi;
        final List<String> multiBots = new ArrayList<>();

        boolean expired(long now) {
            return now > expireAt;
        }
    }

    public static class Result {
        public boolean granted;
        public boolean preempted;
        public String previousOwner = "";
        public String ownerID = "";
        public int score;
        public int winnerScore;
        public long staggerMs;
    }
}
