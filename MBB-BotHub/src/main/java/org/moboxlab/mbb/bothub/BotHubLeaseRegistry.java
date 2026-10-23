package org.moboxlab.mbb.bothub;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * hub 租约注册表
 *
 * 同时用于识图认领和回合权：同一把 key 在租约期内只属于一个 bot。
 * 识图认领用 key = 图片标识，回合权用 key = 群号|回合标识。
 */
public class BotHubLeaseRegistry {
    private final Map<String,Lease> leases = new ConcurrentHashMap<>();

    /**
     * 认领租约。owner 已经是自己时续租成功；被他人占用时返回 false。
     * 用 ConcurrentHashMap.compute 保证同一把 key 的判定是原子的，
     * 两个 bot 同时认领时只有一个能拿到。
     */
    public boolean claim(String key,String owner,long leaseMillis) {
        if (key == null || key.trim().isEmpty() || owner == null || owner.isEmpty()) return false;
        String value = key.trim();
        long lease = Math.max(1000L,leaseMillis);
        final boolean[] granted = new boolean[1];
        leases.compute(value,(itemKey,existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || existing.expired(now)) {
                Lease created = new Lease();
                created.key = itemKey;
                created.owner = owner;
                created.createdAt = now;
                created.expireAt = now + lease;
                granted[0] = true;
                return created;
            }
            if (owner.equals(existing.owner)) {
                existing.expireAt = now + lease;
                granted[0] = true;
                return existing;
            }
            granted[0] = false;
            return existing;
        });
        return granted[0];
    }

    /**
     * 强制接管，用于直接点名这类高优先级场景。
     */
    public void takeover(String key,String owner,long leaseMillis) {
        if (key == null || key.trim().isEmpty() || owner == null || owner.isEmpty()) return;
        long now = System.currentTimeMillis();
        Lease lease = new Lease();
        lease.key = key.trim();
        lease.owner = owner;
        lease.expireAt = now + Math.max(1000L,leaseMillis);
        lease.createdAt = now;
        leases.put(lease.key,lease);
    }

    public boolean release(String key,String owner) {
        if (key == null || key.trim().isEmpty()) return false;
        String value = key.trim();
        final boolean[] released = new boolean[1];
        leases.compute(value,(itemKey,existing) -> {
            if (existing == null) return null;
            if (owner != null && !owner.isEmpty() && !owner.equals(existing.owner)) return existing;
            released[0] = true;
            return null;
        });
        return released[0];
    }

    public String owner(String key) {
        if (key == null || key.trim().isEmpty()) return "";
        Lease lease = leases.get(key.trim());
        if (lease == null) return "";
        if (lease.expired(System.currentTimeMillis())) {
            leases.remove(key.trim());
            return "";
        }
        return lease.owner;
    }

    public JSONObject describe(String key) {
        JSONObject json = new JSONObject(true);
        String value = key == null ? "" : key.trim();
        Lease lease = value.isEmpty() ? null : leases.get(value);
        if (lease != null && lease.expired(System.currentTimeMillis())) {
            leases.remove(value);
            lease = null;
        }
        json.put("key",value);
        json.put("owner",lease == null ? "" : lease.owner);
        json.put("expireAt",lease == null ? 0L : lease.expireAt);
        return json;
    }

    public int clear() {
        int size = leases.size();
        leases.clear();
        return size;
    }

    public int size() {
        purge();
        return leases.size();
    }

    public JSONArray snapshot() {
        purge();
        JSONArray array = new JSONArray();
        for (Lease lease : leases.values()) {
            JSONObject json = new JSONObject(true);
            json.put("key",lease.key);
            json.put("owner",lease.owner);
            json.put("expireAt",lease.expireAt);
            json.put("createdAt",lease.createdAt);
            array.add(json);
        }
        return array;
    }

    public void releaseOwner(String owner) {
        if (owner == null || owner.isEmpty()) return;
        List<String> keys = new ArrayList<>();
        for (Lease lease : leases.values()) {
            if (owner.equals(lease.owner)) keys.add(lease.key);
        }
        for (String key : keys) leases.remove(key);
    }

    private void purge() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String,Lease>> iterator = leases.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expired(now)) iterator.remove();
        }
    }

    private static class Lease {
        String key;
        String owner;
        long expireAt;
        long createdAt;

        boolean expired(long now) {
            return now > expireAt;
        }
    }
}
