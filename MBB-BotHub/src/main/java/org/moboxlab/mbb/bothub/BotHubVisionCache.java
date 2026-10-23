package org.moboxlab.mbb.bothub;

import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * hub 共享识图结果缓存
 *
 * 按调用方给出的 key（通常是 群号|消息ID|片段序号）保存识图结果，
 * 同群其他 bot 命中后直接复用，不再各识别一次。
 */
public class BotHubVisionCache {
    private final int maxEntries;
    private final long keepMillis;
    private final LinkedHashMap<String,Entry> entries = new LinkedHashMap<>();

    public BotHubVisionCache(int maxEntries,long keepMillis) {
        this.maxEntries = maxEntries < 1 ? 1 : maxEntries;
        this.keepMillis = keepMillis < 1000L ? 1000L : keepMillis;
    }

    public synchronized JSONObject find(String key) {
        if (key == null || key.trim().isEmpty()) return null;
        purge();
        Entry entry = entries.get(key.trim());
        if (entry == null) return null;
        entry.lastHit = System.currentTimeMillis();
        entry.hitCount++;
        return entry.result;
    }

    public synchronized void save(String key,JSONObject result) {
        save(key,null,result);
    }

    /**
     * 同一份结果按主键和若干别名一起写入。
     * 别名用来让「同一条消息」之外的维度也能命中：sha256、fileUnique、文件名、URL。
     */
    public synchronized void save(String key,List<String> aliases,JSONObject result) {
        if (result == null) return;
        List<String> keys = new ArrayList<>();
        if (key != null && !key.trim().isEmpty()) keys.add(key.trim());
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias == null || alias.trim().isEmpty()) continue;
                String value = alias.trim();
                if (!keys.contains(value)) keys.add(value);
            }
        }
        if (keys.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (String value : keys) {
            Entry entry = new Entry();
            entry.key = value;
            entry.result = result;
            entry.createdAt = now;
            entry.lastHit = now;
            // 先移除再放入，保证 LinkedHashMap 的访问顺序把新写入放到末尾
            entries.remove(value);
            entries.put(value,entry);
        }
        purge();
        while (entries.size() > maxEntries) {
            Iterator<String> iterator = entries.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * 依次尝试多个 key，返回第一个命中
     */
    public synchronized JSONObject findFirst(List<String> keys) {
        if (keys == null) return null;
        for (String key : keys) {
            JSONObject hit = find(key);
            if (hit != null) return hit;
        }
        return null;
    }

    public synchronized int remove(String key) {
        if (key == null || key.trim().isEmpty()) return 0;
        return entries.remove(key.trim()) == null ? 0 : 1;
    }

    public synchronized int clear() {
        int size = entries.size();
        entries.clear();
        return size;
    }

    public synchronized int size() {
        purge();
        return entries.size();
    }

    public synchronized List<JSONObject> snapshot() {
        purge();
        List<JSONObject> list = new ArrayList<>();
        for (Map.Entry<String,Entry> item : entries.entrySet()) {
            JSONObject json = new JSONObject(true);
            json.put("key",item.getKey());
            json.put("createdAt",item.getValue().createdAt);
            json.put("lastHit",item.getValue().lastHit);
            json.put("hitCount",item.getValue().hitCount);
            json.put("kind",item.getValue().result == null ? "" : item.getValue().result.getString("kind"));
            list.add(json);
        }
        return list;
    }

    private void purge() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String,Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String,Entry> item = iterator.next();
            if (now - item.getValue().createdAt > keepMillis) iterator.remove();
        }
    }

    private static class Entry {
        String key;
        JSONObject result;
        long createdAt;
        long lastHit;
        int hitCount;
    }
}
