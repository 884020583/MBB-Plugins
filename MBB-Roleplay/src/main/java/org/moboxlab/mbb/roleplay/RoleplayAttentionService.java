package org.moboxlab.mbb.roleplay;

import org.moboxlab.moboxbot.API.Plugin;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 注意力漂移
 *
 * 记录角色当前对群和话题的注意力。直接点名、连续参与会提高注意力；长时间没人互动会自然衰减。
 * 注意力只影响非直接消息的主动接话概率，不会阻挡被直接艾特时的回复。
 */
public class RoleplayAttentionService {
    private static class State {
        private double score = 0.5;
        private long updateTime = System.currentTimeMillis();
        private final Map<String,Double> topics = new HashMap<>();
    }

    private final Plugin plugin;
    private volatile RoleplayConfig config;
    private final Map<Long,State> states = new ConcurrentHashMap<>();

    public RoleplayAttentionService(Plugin plugin,RoleplayConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void reload(RoleplayConfig config) {
        this.config = config;
    }

    public synchronized void observe(long groupID,long userID,String topicKey,boolean direct,boolean otherRoleBot) {
        if (!config.attentionDriftEnable || groupID <= 0 || userID <= 0) return;
        State state = state(groupID);
        decay(state,System.currentTimeMillis());
        double delta = direct ? 0.28 : (otherRoleBot ? 0.04 : 0.08);
        state.score = clamp01(state.score + delta);
        if (topicKey != null && !topicKey.isEmpty()) {
            Double value = state.topics.get(topicKey);
            state.topics.put(topicKey,clamp01((value == null ? 0.0 : value) + (direct ? 0.35 : 0.08)));
            trimTopics(state);
        }
    }

    public synchronized double adjustChance(long groupID,String topicKey,double chance,
                                             boolean direct,boolean otherRoleBot) {
        if (!config.attentionDriftEnable || groupID <= 0 || direct || otherRoleBot) return clamp01(chance);
        State state = state(groupID);
        decay(state,System.currentTimeMillis());
        double value = chance;
        if (state.score < config.attentionLowThreshold) {
            value -= config.attentionMaxPenalty * (config.attentionLowThreshold - state.score)
                    / Math.max(0.01,config.attentionLowThreshold);
        } else if (state.score > config.attentionHighThreshold) {
            value += config.attentionBonus;
        }
        Double topic = topicKey == null ? null : state.topics.get(topicKey);
        if (topic != null && topic < 0.15) value -= config.attentionMaxPenalty * 0.25;
        if (topic != null && topic > 0.65) value += config.attentionBonus * 0.5;
        return clamp01(value);
    }

    public synchronized String promptText(long groupID) {
        if (!config.attentionDriftEnable || groupID <= 0) return "";
        State state = state(groupID);
        decay(state,System.currentTimeMillis());
        if (state.score < config.attentionLowThreshold) {
            return "当前注意力偏低：角色最近对这个群的主动参与意愿不高，非直接消息不要硬接，少刷存在感。\n";
        }
        if (state.score > config.attentionHighThreshold) {
            return "当前注意力较高：角色最近一直在跟这个群的交流，接话可以自然一些，但仍要避免重复。\n";
        }
        return "";
    }

    public String topicKey(String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length() && builder.length() < 12; i++) {
            char value = text.charAt(i);
            if (Character.isLetterOrDigit(value)) builder.append(Character.toLowerCase(value));
        }
        return builder.toString();
    }

    public synchronized double score(long groupID) {
        State state = state(groupID);
        decay(state,System.currentTimeMillis());
        return state.score;
    }

    public synchronized void reset(long groupID) {
        if (groupID > 0) states.remove(groupID);
    }

    private State state(long groupID) {
        State state = states.get(groupID);
        if (state != null) return state;
        State created = new State();
        State previous = states.putIfAbsent(groupID,created);
        return previous == null ? created : previous;
    }

    private void decay(State state,long now) {
        long elapsed = now - state.updateTime;
        if (elapsed <= 0) return;
        double halfLife = Math.max(1,config.attentionDecayMinute) * 60000.0;
        double factor = Math.pow(0.5,elapsed / halfLife);
        state.score = clamp01(0.5 + (state.score - 0.5) * factor);
        Iterator<Map.Entry<String,Double>> iterator = state.topics.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String,Double> entry = iterator.next();
            double value = entry.getValue() * factor;
            if (value < 0.03) iterator.remove();
            else entry.setValue(value);
        }
        state.updateTime = now;
    }

    private void trimTopics(State state) {
        int limit = Math.max(10,config.attentionTopicLimit);
        while (state.topics.size() > limit) {
            String minKey = null;
            double minValue = Double.MAX_VALUE;
            for (Map.Entry<String,Double> entry : state.topics.entrySet()) {
                if (entry.getValue() < minValue) {
                    minValue = entry.getValue();
                    minKey = entry.getKey();
                }
            }
            if (minKey == null) break;
            state.topics.remove(minKey);
        }
    }

    private double clamp01(double value) {
        if (value < 0) return 0;
        if (value > 1) return 1;
        return value;
    }
}