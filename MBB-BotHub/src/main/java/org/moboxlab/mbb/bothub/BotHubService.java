package org.moboxlab.mbb.bothub;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginService;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MBB-BotHub 对外服务
 *
 * 其他插件只通过 getService("MBB-BotHub") 调用这里，不关心底层是 server 还是 client。
 */
public class BotHubService implements PluginService, BotHubEventListener {
    private final Plugin plugin;
    private final BotHubConfig config;
    private final BotHubServer server;
    private final BotHubClient client;
    private final Map<String,CompletableFuture<JSONObject>> visionWaiters = new ConcurrentHashMap<>();

    public BotHubService(Plugin plugin,BotHubConfig config,BotHubServer server,BotHubClient client) {
        this.plugin = plugin;
        this.config = config;
        this.server = server;
        this.client = client;
        if (server != null) server.setLocalListener(this);
        if (client != null) client.setEventListener(this);
    }

    @Override
    public String getName() {
        return "MBB-BotHub";
    }

    @Override
    public JSONObject call(String action,JSONObject params) {
        String name = action == null ? "" : action.trim().toLowerCase();
        JSONObject safeParams = params == null ? new JSONObject(true) : params;
        if ("status".equals(name)) return status();
        if ("stats".equals(name)) return dispatch(BotHubProtocol.ACTION_STATS,safeParams);
        if ("peers".equals(name)) return dispatch(BotHubProtocol.ACTION_PEERS,safeParams);
        if ("group_peers".equals(name)) return dispatch(BotHubProtocol.ACTION_GROUP_PEERS,safeParams);
        if ("vision_lookup".equals(name)) return dispatch(BotHubProtocol.ACTION_VISION_LOOKUP,safeParams);
        if ("vision_claim".equals(name)) return dispatch(BotHubProtocol.ACTION_VISION_CLAIM,safeParams);
        if ("vision_publish".equals(name)) return dispatch(BotHubProtocol.ACTION_VISION_PUBLISH,safeParams);
        if ("vision_release".equals(name)) return dispatch(BotHubProtocol.ACTION_VISION_RELEASE,safeParams);
        if ("vision_wait".equals(name)) return visionWait(safeParams);
        if ("vision_backfill".equals(name)) return dispatch(BotHubProtocol.ACTION_VISION_BACKFILL,safeParams);
        if ("turn_claim".equals(name)) return dispatch(BotHubProtocol.ACTION_TURN_CLAIM,safeParams);
        if ("turn_release".equals(name)) return dispatch(BotHubProtocol.ACTION_TURN_RELEASE,safeParams);
        if ("turn_status".equals(name)) return dispatch(BotHubProtocol.ACTION_TURN_STATUS,safeParams);
        if ("task_claim".equals(name)) return dispatch(BotHubProtocol.ACTION_TASK_CLAIM,safeParams);
        if ("task_release".equals(name)) return dispatch(BotHubProtocol.ACTION_TASK_RELEASE,safeParams);
        if ("task_clear".equals(name)) return dispatch(BotHubProtocol.ACTION_TASK_CLEAR,safeParams);
        if ("chat_guard".equals(name)) return dispatch(BotHubProtocol.ACTION_CHAT_GUARD,safeParams);
        if ("chat_guard_reset".equals(name)) return dispatch(BotHubProtocol.ACTION_CHAT_GUARD_RESET,safeParams);
        if ("typing".equals(name)) return dispatch(BotHubProtocol.ACTION_TYPING,safeParams);
        if ("typing_status".equals(name)) return dispatch(BotHubProtocol.ACTION_TYPING_STATUS,safeParams);
        if ("broadcast".equals(name)) return broadcast(safeParams);
        return BotHubProtocol.fail("不支持的动作："+action);
    }

    @Override
    public void onEvent(JSONObject message) {
        if (message == null) return;
        if (!BotHubProtocol.TYPE_EVENT.equals(message.getString("type"))) return;
        if (!BotHubProtocol.EVENT_VISION_RESULT.equals(message.getString("event"))) return;
        JSONObject data = message.getJSONObject("data");
        if (data == null) return;
        String key = safe(data.getString("key"));
        JSONObject result = data.getJSONObject("result");
        if (key.isEmpty() || result == null) return;
        CompletableFuture<JSONObject> waiter = visionWaiters.remove(key);
        if (waiter != null) waiter.complete(result);
    }

    public BotHubConfig config() {
        return config;
    }

    public String mode() {
        return config.mode;
    }

    public boolean available() {
        if (server != null) return server.isRunning();
        if (client != null) return client.isConnected();
        return false;
    }

    private JSONObject status() {
        JSONObject result = BotHubProtocol.ok();
        result.put("mode",config.mode);
        result.put("available",available());
        result.put("host",config.host);
        result.put("port",config.port);
        result.put("botID",config.botID);
        result.put("botQQ",config.botQQ);
        result.put("role",config.role);
        result.put("visionShareEnable",config.visionShareEnable);
        result.put("turnShareEnable",config.turnShareEnable);
        if (server != null) {
            JSONObject serverStatus = server.handleLocalRequest(BotHubProtocol.ACTION_STATUS,null);
            result.put("server",serverStatus);
            result.put("peerCount",serverStatus.getIntValue("peerCount"));
            result.put("peers",serverStatus.getJSONArray("peers"));
        } else if (client != null) {
            result.put("connected",client.isConnected());
            result.put("lastConnectedAt",client.getLastConnectedAt());
            result.put("lastError",client.getLastError());
            result.put("peers",client.peersArray());
            result.put("peerCount",client.peersArray().size());
        }
        return result;
    }

    private JSONObject broadcast(JSONObject params) {
        String event = safe(params.getString("event"));
        JSONObject data = params.getJSONObject("data");
        if (event.isEmpty()) return BotHubProtocol.fail("缺少 event");
        if (server != null) return server.broadcastLocal(event,data);
        if (client != null) {
            client.publish(event,data);
            JSONObject result = BotHubProtocol.ok();
            result.put("published",client.isConnected());
            return result;
        }
        return BotHubProtocol.fail("hub 未启动");
    }

    /**
     * 先查缓存，再注册等待，注册后二次查缓存，最后阻塞等待事件。
     */
    private JSONObject visionWait(JSONObject params) {
        String key = safe(params.getString("key"));
        if (key.isEmpty()) return BotHubProtocol.fail("缺少 key");
        int waitMs = params.getIntValue("waitMs");
        if (waitMs <= 0) waitMs = config.visionWaitMs;
        JSONObject first = dispatch(BotHubProtocol.ACTION_VISION_LOOKUP,lookupParams(params,key));
        JSONObject cached = cachedResult(first);
        if (cached != null) return cached;
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        CompletableFuture<JSONObject> existing = visionWaiters.putIfAbsent(key,future);
        if (existing != null) future = existing;
        try {
            JSONObject second = dispatch(BotHubProtocol.ACTION_VISION_LOOKUP,lookupParams(params,key));
            JSONObject rechecked = cachedResult(second);
            if (rechecked != null) return rechecked;
            JSONObject result = future.get(Math.max(500L,waitMs),TimeUnit.MILLISECONDS);
            return result == null ? BotHubProtocol.fail("等待识图结果超时") : result;
        } catch (Exception e) {
            return BotHubProtocol.fail("等待识图结果超时");
        } finally {
            visionWaiters.remove(key,future);
        }
    }

    private JSONObject lookupParams(JSONObject source,String key) {
        JSONObject params = new JSONObject(true);
        params.put("key",key);
        if (source != null) {
            JSONArray aliases = source.getJSONArray("aliases");
            if (aliases != null) params.put("aliases",aliases);
        }
        return params;
    }

    private JSONObject cachedResult(JSONObject response) {
        if (response == null || !response.getBooleanValue("hit")) return null;
        JSONObject result = response.getJSONObject("result");
        if (result == null) return null;
        JSONObject copy = new JSONObject(true);
        copy.putAll(result);
        copy.put("hubShared",true);
        return copy;
    }

    private JSONObject dispatch(String action,JSONObject params) {
        if (server != null) return server.handleLocalRequest(action,params);
        if (client != null) {
            JSONObject result = client.request(action,params,config.requestTimeoutMs);
            return result == null ? BotHubProtocol.fail("hub 未连接") : result;
        }
        return BotHubProtocol.fail("hub 未启动");
    }

    public JSONArray peersArray() {
        if (client != null) return client.peersArray();
        if (server != null) {
            JSONObject status = server.handleLocalRequest(BotHubProtocol.ACTION_PEERS,null);
            return status == null ? new JSONArray() : status.getJSONArray("peers");
        }
        return new JSONArray();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
