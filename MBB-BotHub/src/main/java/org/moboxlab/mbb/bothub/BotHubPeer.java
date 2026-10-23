package org.moboxlab.mbb.bothub;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * hub 里的一个机器人节点
 */
public class BotHubPeer {
    public final String botID;
    public final long botQQ;
    public final String role;
    public final String version;
    public final String remoteAddress;
    public final List<Long> groups = new ArrayList<>();
    public final List<String> caps = new ArrayList<>();
    public final long joinTime;
    public volatile long lastSeen;
    public volatile boolean local;
    public volatile Object transport;

    public BotHubPeer(String botID,long botQQ,String role,String version,String remoteAddress,
                      List<Long> groups,boolean local) {
        this(botID,botQQ,role,version,remoteAddress,groups,local,null);
    }

    public BotHubPeer(String botID,long botQQ,String role,String version,String remoteAddress,
                      List<Long> groups,boolean local,List<String> caps) {
        this.botID = botID == null ? "" : botID;
        this.botQQ = botQQ;
        this.role = role == null ? "" : role;
        this.version = version == null ? "" : version;
        this.remoteAddress = remoteAddress == null ? "" : remoteAddress;
        if (groups != null) this.groups.addAll(groups);
        if (caps != null) this.caps.addAll(caps);
        this.local = local;
        this.joinTime = System.currentTimeMillis();
        this.lastSeen = this.joinTime;
    }

    public boolean supports(String cap) {
        if (caps.isEmpty()) return true;
        if (cap == null || cap.isEmpty()) return true;
        for (String item : caps) {
            if (cap.equalsIgnoreCase(item)) return true;
        }
        return false;
    }

    public boolean inGroup(long groupID) {
        if (groups.isEmpty()) return true;
        for (Long item : groups) {
            if (item != null && item == groupID) return true;
        }
        return false;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject(true);
        json.put("botID",botID);
        json.put("botQQ",botQQ);
        json.put("role",role);
        json.put("version",version);
        json.put("groups",new JSONArray(new ArrayList<Object>(groups)));
        json.put("caps",new JSONArray(new ArrayList<Object>(caps)));
        json.put("joinTime",joinTime);
        json.put("lastSeen",lastSeen);
        json.put("local",local);
        return json;
    }

    public static List<Long> parseGroups(String text) {
        List<Long> result = new ArrayList<>();
        if (text == null) return result;
        String[] parts = text.split("[,，;；\\s]+");
        for (String part : parts) {
            if (part == null) continue;
            String value = part.trim();
            if (value.isEmpty()) continue;
            try {
                long groupID = Long.parseLong(value);
                if (groupID > 0 && !result.contains(groupID)) result.add(groupID);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    public static List<Long> parseGroupArray(JSONArray array) {
        List<Long> result = new ArrayList<>();
        if (array == null) return result;
        for (Object item : array) {
            if (item == null) continue;
            try {
                long groupID = Long.parseLong(String.valueOf(item).trim());
                if (groupID > 0 && !result.contains(groupID)) result.add(groupID);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    public static List<String> parseCapArray(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) return result;
        for (Object item : array) {
            if (item == null) continue;
            String value = String.valueOf(item).trim();
            if (!value.isEmpty() && !result.contains(value)) result.add(value);
        }
        return result;
    }
}
