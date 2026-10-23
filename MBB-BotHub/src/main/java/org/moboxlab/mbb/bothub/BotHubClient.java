package org.moboxlab.mbb.bothub;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Plugin;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BotHub 客户端
 *
 * 连接 hubserver，负责握手、心跳、请求响应关联、事件回调与断线重连。
 */
public class BotHubClient {
    private final Plugin plugin;
    private final BotHubConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong requestSeq = new AtomicLong(0);
    private final Map<String,CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    private final Map<String,CompletableFuture<JSONObject>> visionWaiters = new ConcurrentHashMap<>();
    private final Map<String,BotHubPeer> peers = new ConcurrentHashMap<>();
    private volatile BotHubConnection connection;
    private volatile boolean connected = false;
    private volatile long lastConnectedAt = 0L;
    private volatile String lastError = "";
    private volatile BotHubEventListener eventListener;
    private volatile Runnable connectListener;
    private final java.util.concurrent.atomic.AtomicInteger failedAttempts =
            new java.util.concurrent.atomic.AtomicInteger();
    private Thread worker;
    private Thread heartbeat;

    public BotHubClient(Plugin plugin,BotHubConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        if (!running.compareAndSet(false,true)) return;
        worker = new Thread(this::runLoop,"MBB-BotHub-Client");
        worker.setDaemon(true);
        worker.start();
    }

    public void setEventListener(BotHubEventListener listener) {
        this.eventListener = listener;
    }

    /**
     * 每次握手成功后回调一次，用来做本地缓存回填这类一次性动作
     */
    public void setConnectListener(Runnable listener) {
        this.connectListener = listener;
    }

    public int getFailedAttempts() {
        return failedAttempts.get();
    }

    /**
     * 强制断开当前连接，让后台线程带着最新身份重新握手。
     * 身份（botQQ / botID / role）自动补全后调用。
     */
    public void reconnect() {
        BotHubConnection current = connection;
        if (current != null) current.close();
    }

    public void stop() {
        if (!running.compareAndSet(true,false)) return;
        BotHubConnection current = connection;
        if (current != null) {
            JSONObject bye = BotHubProtocol.message(BotHubProtocol.TYPE_BYE);
            current.send(bye);
            current.close();
        }
        connected = false;
        failPending("hub 客户端已停止");
        plugin.getLogger().sendInfo("[BotHub] client 已停止");
    }

    public boolean isConnected() {
        return connected;
    }

    public String getLastError() {
        return lastError;
    }

    public long getLastConnectedAt() {
        return lastConnectedAt;
    }

    public JSONArray peersArray() {
        JSONArray array = new JSONArray();
        for (BotHubPeer peer : peers.values()) array.add(peer.toJson());
        return array;
    }

    /**
     * 发一次请求并等待响应，超时或未连接返回 null。
     */
    public JSONObject request(String action,JSONObject params,long timeoutMs) {
        if (!connected) return null;
        BotHubConnection current = connection;
        if (current == null || current.isClosed()) return null;
        String id = "r" + requestSeq.incrementAndGet();
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        pending.put(id,future);
        JSONObject message = BotHubProtocol.message(BotHubProtocol.TYPE_REQUEST);
        message.put("id",id);
        message.put("action",action);
        message.put("params",params == null ? new JSONObject(true) : params);
        current.send(message);
        try {
            return future.get(Math.max(300L,timeoutMs),TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        } finally {
            pending.remove(id);
        }
    }

    public void publish(String event,JSONObject data) {
        if (!connected) return;
        BotHubConnection current = connection;
        if (current == null || current.isClosed()) return;
        JSONObject message = BotHubProtocol.message(BotHubProtocol.TYPE_PUBLISH);
        message.put("event",event);
        message.put("data",data == null ? new JSONObject(true) : data);
        current.send(message);
    }

    /**
     * 等待其他 bot 广播的识图结果，命中后返回 result，超时返回 null。
     */
    public JSONObject awaitVision(String key,long timeoutMs) {
        if (!connected || key == null || key.trim().isEmpty()) return null;
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        CompletableFuture<JSONObject> existing = visionWaiters.putIfAbsent(key.trim(),future);
        if (existing != null) future = existing;
        try {
            return future.get(Math.max(500L,timeoutMs),TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        } finally {
            visionWaiters.remove(key.trim(),future);
        }
    }

    private void runLoop() {
        while (running.get()) {
            boolean connectedOnce = false;
            try {
                connectedOnce = connectOnce();
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (connectedOnce) {
                failedAttempts.set(0);
            } else {
                failedAttempts.incrementAndGet();
            }
            connected = false;
            BotHubConnection current = connection;
            if (current != null) current.close();
            connection = null;
            failPending("hub 连接已断开");
            peers.clear();
            if (!running.get()) return;
            sleepQuiet(config.reconnectSeconds * 1000L);
        }
    }

    private boolean connectOnce() throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(config.host,config.port),config.requestTimeoutMs);
        BotHubConnection current = new BotHubConnection(socket);
        connection = current;
        JSONObject hello = BotHubProtocol.message(BotHubProtocol.TYPE_HELLO);
        hello.put("token",config.token);
        hello.put("botID",config.botID);
        hello.put("botQQ",config.botQQ);
        hello.put("role",config.role);
        hello.put("version",plugin.getVersion());
        hello.put("groups",BotHubPeer.parseGroups(config.groups));
        hello.put("caps",config.capList());
        current.send(hello);
        JSONObject reply = current.read();
        if (reply == null) throw new Exception("hubserver 无响应");
        if (BotHubProtocol.TYPE_ERROR.equals(reply.getString("type"))) {
            throw new Exception("握手被拒绝："+safe(reply.getString("error")));
        }
        if (!BotHubProtocol.TYPE_HELLO_OK.equals(reply.getString("type"))) {
            throw new Exception("握手响应异常："+safe(reply.getString("type")));
        }
        connected = true;
        failedAttempts.set(0);
        lastConnectedAt = System.currentTimeMillis();
        lastError = "";
        loadPeers(reply.getJSONArray("peers"));
        plugin.getLogger().sendInfo("[BotHub] 已连接 hubserver "+config.host+":"+config.port
                +"，peer 数="+peers.size());
        startHeartbeat();
        Runnable listener = connectListener;
        if (listener != null) {
            try {
                listener.run();
            } catch (Exception ignored) {
            }
        }
        try {
            readLoop(current);
        } catch (Exception ignored) {
        }
        return true;
    }

    private void startHeartbeat() {
        if (heartbeat != null && heartbeat.isAlive()) return;
        heartbeat = new Thread(() -> {
            while (running.get() && connected) {
                sleepQuiet(config.heartbeatSeconds * 1000L);
                if (!connected) return;
                BotHubConnection current = connection;
                if (current == null) return;
                JSONObject ping = BotHubProtocol.message(BotHubProtocol.TYPE_PING);
                ping.put("ts",System.currentTimeMillis());
                current.send(ping);
            }
        },"MBB-BotHub-Heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    private void readLoop(BotHubConnection current) throws Exception {
        while (running.get() && connected && !current.isClosed()) {
            JSONObject message = current.read();
            if (message == null) return;
            String type = safe(message.getString("type"));
            if (BotHubProtocol.TYPE_PONG.equals(type)) continue;
            if (BotHubProtocol.TYPE_RESPONSE.equals(type)) {
                String id = safe(message.getString("id"));
                CompletableFuture<JSONObject> future = pending.remove(id);
                if (future != null) future.complete(message.getJSONObject("result"));
                continue;
            }
            if (BotHubProtocol.TYPE_EVENT.equals(type)) {
                handleEvent(message);
                continue;
            }
            if (BotHubProtocol.TYPE_ERROR.equals(type)) {
                plugin.getLogger().sendWarn("[BotHub] 服务端返回错误："+safe(message.getString("error")));
            }
        }
    }

    private void handleEvent(JSONObject message) {
        String event = safe(message.getString("event"));
        String from = safe(message.getString("from"));
        JSONObject data = message.getJSONObject("data");
        if (BotHubProtocol.EVENT_PEER_JOIN.equals(event) && data != null) {
            BotHubPeer peer = toPeer(data);
            if (peer != null) peers.put(peer.botID,peer);
        }
        if (BotHubProtocol.EVENT_PEER_LEAVE.equals(event) && data != null) {
            peers.remove(safe(data.getString("botID")));
        }
        if (BotHubProtocol.EVENT_VISION_RESULT.equals(event) && data != null) {
            String key = safe(data.getString("key"));
            JSONObject result = data.getJSONObject("result");
            CompletableFuture<JSONObject> waiter = key.isEmpty() ? null : visionWaiters.remove(key);
            if (waiter != null && result != null) waiter.complete(result);
        }
        BotHubEventListener listener = eventListener;
        if (listener != null) {
            try {
                listener.onEvent(message);
            } catch (Exception ignored) {
            }
        }
    }

    private void loadPeers(JSONArray array) {
        peers.clear();
        if (array == null) return;
        for (Object item : array) {
            if (!(item instanceof JSONObject)) continue;
            BotHubPeer peer = toPeer((JSONObject) item);
            if (peer != null) peers.put(peer.botID,peer);
        }
    }

    private BotHubPeer toPeer(JSONObject json) {
        if (json == null) return null;
        String botID = safe(json.getString("botID"));
        if (botID.isEmpty()) return null;
        List<Long> groups = BotHubPeer.parseGroupArray(json.getJSONArray("groups"));
        List<String> caps = BotHubPeer.parseCapArray(json.getJSONArray("caps"));
        return new BotHubPeer(botID,json.getLongValue("botQQ"),json.getString("role"),
                json.getString("version"),"",groups,json.getBooleanValue("local"),caps);
    }

    private void failPending(String reason) {
        for (CompletableFuture<JSONObject> future : pending.values()) {
            future.complete(BotHubProtocol.fail(reason));
        }
        pending.clear();
        for (CompletableFuture<JSONObject> future : visionWaiters.values()) {
            future.complete(null);
        }
        visionWaiters.clear();
    }

    private void sleepQuiet(long millis) {
        try {
            Thread.sleep(Math.max(100L,millis));
        } catch (InterruptedException ignored) {
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
