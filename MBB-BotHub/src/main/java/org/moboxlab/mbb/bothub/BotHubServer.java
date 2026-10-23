package org.moboxlab.mbb.bothub;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Plugin;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BotHub 服务端
 *
 * 负责 peer 注册、心跳、共享识图缓存与回合权租约，并把事件广播给其他 bot。
 */
public class BotHubServer {
    private final Plugin plugin;
    private final BotHubConfig config;
    private final Map<String,BotHubPeer> peers = new ConcurrentHashMap<>();
    private final Map<String,BotHubConnection> connections = new ConcurrentHashMap<>();
    private final BotHubVisionCache visionCache;
    private final BotHubLeaseRegistry visionClaims = new BotHubLeaseRegistry();
    private final BotHubTurnArbiter turnArbiter = new BotHubTurnArbiter();
    private final BotHubLeaseRegistry taskLeases = new BotHubLeaseRegistry();
    private final Map<Long,ChatChain> chatChains = new ConcurrentHashMap<>();
    private final Map<Long,Map<String,TypingState>> typingMap = new ConcurrentHashMap<>();
    private final BotHubStats stats = new BotHubStats();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final long startTime = System.currentTimeMillis();
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;
    private Thread purgeThread;
    private String localBotID = "";
    private volatile BotHubEventListener localListener;

    public BotHubServer(Plugin plugin,BotHubConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.visionCache = new BotHubVisionCache(config.visionCacheMaxEntries,
                config.visionCacheKeepHours * 3600L * 1000L);
    }

    public void start() throws Exception {
        if (!running.compareAndSet(false,true)) return;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(config.host,config.port));
        } catch (Exception e) {
            //绑定失败必须把状态回滚，否则选主失败后这个实例会一直以为自己是 server
            running.set(false);
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (Exception ignored) {
            }
            serverSocket = null;
            throw e;
        }
        acceptThread = new Thread(this::acceptLoop,"MBB-BotHub-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        purgeThread = new Thread(this::purgeLoop,"MBB-BotHub-Purge");
        purgeThread.setDaemon(true);
        purgeThread.start();
        plugin.getLogger().sendInfo("[BotHub] server 已启动，监听 "+config.host+":"+config.port);
    }

    public void stop() {
        if (!running.compareAndSet(true,false)) return;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        for (BotHubConnection connection : connections.values()) connection.close();
        connections.clear();
        peers.clear();
        visionClaims.clear();
        turnArbiter.clear();
        taskLeases.clear();
        chatChains.clear();
        typingMap.clear();
        plugin.getLogger().sendInfo("[BotHub] server 已停止");
    }

    public BotHubStats stats() {
        return stats;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void setLocalListener(BotHubEventListener listener) {
        this.localListener = listener;
    }

    /**
     * server 模式下当前 bot 自己也是参与者，注册成本地 peer。
     */
    public void registerLocalPeer(String botID,long botQQ,String role,String version,List<Long> groups) {
        registerLocalPeer(botID,botQQ,role,version,groups,
                BotHubPeer.parseCapArray(BotHubProtocol.supportedCaps()));
    }

    public void registerLocalPeer(String botID,long botQQ,String role,String version,
                                  List<Long> groups,List<String> caps) {
        //身份自动补全后会重新登记，先把旧的本地 peer 摘掉，避免同一个实例留下两条记录
        removeLocalPeer();
        this.localBotID = botID == null ? "" : botID;
        BotHubPeer peer = new BotHubPeer(botID,botQQ,role,version,"local",groups,true,caps);
        peers.put(peer.botID,peer);
    }

    public void removeLocalPeer() {
        if (localBotID == null || localBotID.isEmpty()) return;
        peers.remove(localBotID);
        visionClaims.releaseOwner(localBotID);
        turnArbiter.releaseOwner(localBotID);
    }

    /**
     * server 模式下本地 bot 直接走内存派发，不经过 TCP。
     */
    public JSONObject handleLocalRequest(String action,JSONObject params) {
        return handleRequest(localBotID,action,params);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                Thread thread = new Thread(() -> handleClient(socket),"MBB-BotHub-Client");
                thread.setDaemon(true);
                thread.start();
            } catch (Exception e) {
                if (running.get()) plugin.getLogger().sendWarn("[BotHub] accept 失败："+e.getMessage());
            }
        }
    }

    private void handleClient(Socket socket) {
        BotHubConnection connection = null;
        BotHubPeer registered = null;
        String botID = "";
        try {
            connection = new BotHubConnection(socket);
            JSONObject hello = connection.read();
            if (hello == null) return;
            if (!BotHubProtocol.TYPE_HELLO.equals(hello.getString("type"))) {
                connection.send(BotHubProtocol.error("hello_required",""));
                return;
            }
            if (!authOk(hello.getString("token"))) {
                connection.send(BotHubProtocol.error("auth_failed","token 不匹配"));
                plugin.getLogger().sendWarn("[BotHub] 拒绝一个 token 不匹配的连接："+connection.remoteAddress());
                return;
            }
            botID = safe(hello.getString("botID"));
            if (botID.isEmpty()) botID = String.valueOf(hello.getLongValue("botQQ"));
            if (botID.isEmpty()) {
                connection.send(BotHubProtocol.error("bot_id_required",""));
                return;
            }
            List<Long> groups = parseGroups(hello.getJSONArray("groups"));
            List<String> caps = BotHubPeer.parseCapArray(hello.getJSONArray("caps"));
            BotHubPeer peer = new BotHubPeer(botID,hello.getLongValue("botQQ"),hello.getString("role"),
                    hello.getString("version"),connection.remoteAddress(),groups,false,caps);
            peer.transport = connection;
            BotHubPeer previous = peers.put(botID,peer);
            connections.put(botID,connection);
            registered = peer;
            if (previous != null && previous.transport != connection && previous.transport instanceof BotHubConnection) {
                ((BotHubConnection) previous.transport).close();
            }
            connection.send(helloOk(peer));
            broadcast(BotHubProtocol.EVENT_PEER_JOIN,botID,peerJson(peer),botID);
            stats.peersJoined.incrementAndGet();
            plugin.getLogger().sendInfo("[BotHub] peer 上线 "+botID+" qq="+peer.botQQ
                    +" role="+peer.role+" caps="+peer.caps+" 来自 "+peer.remoteAddress);
            loop(connection,botID);
        } catch (Exception e) {
            if (running.get() && connection != null && !connection.isClosed()) {
                plugin.getLogger().sendWarn("[BotHub] 连接异常："+e.getMessage());
            }
        } finally {
            if (connection != null) connection.close();
            if (!botID.isEmpty()) {
                //同一个 botID 重连时旧连接会走到这里，只有自己仍然是登记项才清理，避免误删新连接
                BotHubPeer removed = registered == null ? null : (peers.remove(botID,registered) ? registered : null);
                connections.remove(botID,connection);
                if (removed != null) {
                    visionClaims.releaseOwner(botID);
                    turnArbiter.releaseOwner(botID);
                    broadcast(BotHubProtocol.EVENT_PEER_LEAVE,botID,peerJson(removed),botID);
                    stats.peersLeft.incrementAndGet();
                    plugin.getLogger().sendInfo("[BotHub] peer 下线 "+botID);
                }
            }
        }
    }

    private void loop(BotHubConnection connection,String botID) throws Exception {
        while (running.get() && !connection.isClosed()) {
            JSONObject message = connection.read();
            if (message == null) return;
            String type = safe(message.getString("type"));
            BotHubPeer peer = peers.get(botID);
            if (peer != null) peer.lastSeen = System.currentTimeMillis();
            if (BotHubProtocol.TYPE_PING.equals(type)) {
                JSONObject pong = BotHubProtocol.message(BotHubProtocol.TYPE_PONG);
                pong.put("serverTime",System.currentTimeMillis());
                connection.send(pong);
                continue;
            }
            if (BotHubProtocol.TYPE_BYE.equals(type)) return;
            if (BotHubProtocol.TYPE_REQUEST.equals(type)) {
                String id = safe(message.getString("id"));
                String action = safe(message.getString("action"));
                JSONObject params = message.getJSONObject("params");
                JSONObject result;
                try {
                    result = handleRequest(botID,action,params);
                } catch (Exception e) {
                    result = BotHubProtocol.fail("处理失败："+e.getMessage());
                }
                connection.send(BotHubProtocol.response(id,true,result,""));
                continue;
            }
            if (BotHubProtocol.TYPE_PUBLISH.equals(type)) {
                String event = safe(message.getString("event"));
                JSONObject data = message.getJSONObject("data");
                if (!event.isEmpty()) broadcast(event,botID,data,botID);
                continue;
            }
            connection.send(BotHubProtocol.error("unsupported_type",type));
        }
    }

    /**
     * 统一请求派发，server 模式和 client 模式复用同一套动作。
     */
    public JSONObject handleRequest(String botID,String action,JSONObject params) {
        String name = action == null ? "" : action.trim().toLowerCase();
        JSONObject safeParams = params == null ? new JSONObject(true) : params;
        stats.requests.incrementAndGet();
        if (BotHubProtocol.ACTION_STATUS.equals(name)) return status();
        if (BotHubProtocol.ACTION_STATS.equals(name)) return statsResult();
        if (BotHubProtocol.ACTION_PEERS.equals(name)) return peers();
        if (BotHubProtocol.ACTION_GROUP_PEERS.equals(name)) return groupPeers(safeParams);
        if (BotHubProtocol.ACTION_VISION_LOOKUP.equals(name)) return visionLookup(safeParams);
        if (BotHubProtocol.ACTION_VISION_CLAIM.equals(name)) return visionClaim(botID,safeParams);
        if (BotHubProtocol.ACTION_VISION_PUBLISH.equals(name)) return visionPublish(botID,safeParams);
        if (BotHubProtocol.ACTION_VISION_RELEASE.equals(name)) return visionRelease(botID,safeParams);
        if (BotHubProtocol.ACTION_VISION_BACKFILL.equals(name)) return visionBackfill(safeParams);
        if (BotHubProtocol.ACTION_VISION_CLEAR.equals(name)) return visionClear();
        if (BotHubProtocol.ACTION_TURN_CLAIM.equals(name)) return turnClaim(botID,safeParams);
        if (BotHubProtocol.ACTION_TURN_RELEASE.equals(name)) return turnRelease(botID,safeParams);
        if (BotHubProtocol.ACTION_TURN_STATUS.equals(name)) return turnStatus(safeParams);
        if (BotHubProtocol.ACTION_TASK_CLAIM.equals(name)) return taskClaim(botID,safeParams);
        if (BotHubProtocol.ACTION_TASK_RELEASE.equals(name)) return taskRelease(botID,safeParams);
        if (BotHubProtocol.ACTION_TASK_CLEAR.equals(name)) return taskClear();
        if (BotHubProtocol.ACTION_CHAT_GUARD.equals(name)) return chatGuard(botID,safeParams);
        if (BotHubProtocol.ACTION_CHAT_GUARD_RESET.equals(name)) return chatGuardReset(safeParams);
        if (BotHubProtocol.ACTION_TYPING.equals(name)) return typing(botID,safeParams);
        if (BotHubProtocol.ACTION_TYPING_STATUS.equals(name)) return typingStatus(safeParams);
        return BotHubProtocol.fail("不支持的动作："+action);
    }

    private JSONObject statsResult() {
        JSONObject result = BotHubProtocol.ok();
        result.put("uptimeMs",System.currentTimeMillis() - startTime);
        result.put("peerCount",peers.size());
        result.put("visionCacheSize",visionCache.size());
        result.put("visionClaimCount",visionClaims.size());
        result.put("turnCount",turnArbiter.size());
        result.put("taskCount",taskLeases.size());
        result.put("typingCount",typingCount());
        result.put("counters",stats.toJson());
        return result;
    }

    private JSONObject status() {
        JSONObject result = BotHubProtocol.ok();
        result.put("mode","server");
        result.put("host",config.host);
        result.put("port",config.port);
        result.put("uptimeMs",System.currentTimeMillis() - startTime);
        result.put("peerCount",peers.size());
        result.put("peers",peersArray(0));
        result.put("visionCacheSize",visionCache.size());
        result.put("visionCacheMax",config.visionCacheMaxEntries);
        result.put("visionClaimCount",visionClaims.size());
        result.put("turnCount",turnArbiter.size());
        result.put("typingCount",typingCount());
        return result;
    }

    private JSONObject peers() {
        JSONObject result = BotHubProtocol.ok();
        result.put("peers",peersArray(0));
        return result;
    }

    private JSONObject groupPeers(JSONObject params) {
        long groupID = params.getLongValue("groupID");
        JSONObject result = BotHubProtocol.ok();
        result.put("groupID",groupID);
        result.put("peers",peersArray(groupID));
        return result;
    }

    private JSONObject visionLookup(JSONObject params) {
        List<String> keys = visionKeys(params);
        JSONObject cached = visionCache.findFirst(keys);
        JSONObject result = BotHubProtocol.ok();
        result.put("key",keys.isEmpty() ? "" : keys.get(0));
        result.put("hit",cached != null);
        if (cached != null) {
            stats.visionLookupHit.incrementAndGet();
            result.put("result",cached);
        } else {
            stats.visionLookupMiss.incrementAndGet();
        }
        return result;
    }

    private JSONObject visionClaim(String botID,JSONObject params) {
        String key = safe(params.getString("key"));
        boolean granted = visionClaims.claim(key,botID,config.visionLeaseSeconds * 1000L);
        if (granted) {
            stats.visionClaimGranted.incrementAndGet();
        } else {
            stats.visionClaimDenied.incrementAndGet();
        }
        JSONObject result = BotHubProtocol.ok();
        result.put("key",key);
        result.put("granted",granted);
        result.put("ownerID",visionClaims.owner(key));
        result.put("leaseSeconds",config.visionLeaseSeconds);
        return result;
    }

    private JSONObject visionPublish(String botID,JSONObject params) {
        String key = safe(params.getString("key"));
        JSONObject data = params.getJSONObject("result");
        if (data == null) return BotHubProtocol.fail("缺少 result");
        List<String> aliases = visionAliases(data);
        appendAliases(aliases,params.getJSONArray("aliases"));
        if (key.isEmpty() && aliases.isEmpty()) return BotHubProtocol.fail("缺少 key 或 aliases");
        JSONObject stored = new JSONObject(true);
        stored.putAll(data);
        stored.put("hubSource",botID);
        stored.put("hubShared",true);
        visionCache.save(key,aliases,stored);
        if (!key.isEmpty()) visionClaims.release(key,botID);
        stats.visionPublished.incrementAndGet();
        if (!key.isEmpty()) {
            JSONObject event = new JSONObject(true);
            event.put("key",key);
            event.put("kind",safe(params.getString("kind")));
            event.put("result",stored);
            broadcast(BotHubProtocol.EVENT_VISION_RESULT,botID,event,botID,BotHubProtocol.CAP_VISION);
        }
        JSONObject result = BotHubProtocol.ok();
        result.put("key",key);
        result.put("stored",true);
        result.put("aliasCount",aliases.size());
        return result;
    }

    /**
     * client 启动时把本地 MBB-Vision 缓存灌进 hub，让其他 bot 不必重复识图
     */
    private JSONObject visionBackfill(JSONObject params) {
        JSONArray entries = params.getJSONArray("entries");
        if (entries == null || entries.isEmpty()) return BotHubProtocol.fail("缺少 entries");
        int stored = 0;
        for (Object item : entries) {
            if (!(item instanceof JSONObject)) continue;
            JSONObject entry = (JSONObject) item;
            JSONObject data = entry.getJSONObject("result");
            if (data == null) continue;
            List<String> aliases = visionAliases(data);
            appendAliases(aliases,entry.getJSONArray("aliases"));
            if (aliases.isEmpty()) continue;
            JSONObject copy = new JSONObject(true);
            copy.putAll(data);
            copy.put("hubShared",true);
            copy.put("hubBackfilled",true);
            if (!copy.containsKey("hubSource")) copy.put("hubSource","local-cache");
            visionCache.save("",aliases,copy);
            stored++;
        }
        stats.visionBackfilled.addAndGet(stored);
        JSONObject result = BotHubProtocol.ok();
        result.put("stored",stored);
        return result;
    }

    private List<String> visionKeys(JSONObject params) {
        List<String> keys = new ArrayList<>();
        String key = safe(params.getString("key"));
        if (!key.isEmpty()) keys.add(key);
        appendAliases(keys,params.getJSONArray("aliases"));
        return keys;
    }

    private void appendAliases(List<String> keys,JSONArray aliases) {
        if (aliases == null) return;
        for (Object item : aliases) {
            if (item == null) continue;
            String value = String.valueOf(item).trim();
            if (!value.isEmpty() && !keys.contains(value)) keys.add(value);
        }
    }

    /**
     * 从识图结果里抽出可复用的别名，让不同主键的 bot 也能命中同一份结果
     */
    private List<String> visionAliases(JSONObject result) {
        List<String> aliases = new ArrayList<>();
        if (result == null) return aliases;
        addAlias(aliases,"sha256",result.getString("sha256"));
        addAlias(aliases,"unique",result.getString("fileUnique"));
        addAlias(aliases,"file",result.getString("file"));
        addAlias(aliases,"url",result.getString("url"));
        return aliases;
    }

    private void addAlias(List<String> aliases,String prefix,String value) {
        String text = safe(value);
        if (text.isEmpty()) return;
        String alias = prefix+":"+text;
        if (!aliases.contains(alias)) aliases.add(alias);
    }

    private JSONObject visionRelease(String botID,JSONObject params) {
        String key = safe(params.getString("key"));
        JSONObject result = BotHubProtocol.ok();
        result.put("key",key);
        result.put("released",visionClaims.release(key,botID));
        return result;
    }

    private JSONObject visionClear() {
        JSONObject result = BotHubProtocol.ok();
        result.put("count",visionCache.clear());
        result.put("claimCount",visionClaims.clear());
        return result;
    }

    private JSONObject turnClaim(String botID,JSONObject params) {
        long groupID = params.getLongValue("groupID");
        String turnKey = safe(params.getString("turnKey"));
        String key = groupID+"|"+turnKey;
        int score = params.getIntValue("score");
        if (score <= 0) score = params.getBooleanValue("priority") ? 100 : 50;
        boolean multi = params.getBooleanValue("multi");
        BotHubTurnArbiter.Result claimed = turnArbiter.claim(key,botID,score,
                config.turnLeaseSeconds * 1000L,multi,config.multiReplyStaggerMs);
        if (claimed.granted) {
            stats.turnGranted.incrementAndGet();
        } else {
            stats.turnDenied.incrementAndGet();
        }
        if (claimed.staggerMs > 0) stats.staggeredReplies.incrementAndGet();
        if (claimed.preempted && !claimed.previousOwner.isEmpty()) {
            stats.turnPreempted.incrementAndGet();
            JSONObject event = new JSONObject(true);
            event.put("groupID",groupID);
            event.put("turnKey",turnKey);
            event.put("by",botID);
            sendTo(claimed.previousOwner,BotHubProtocol.event(
                    BotHubProtocol.EVENT_TURN_PREEMPTED,botID,event));
        }
        JSONObject result = BotHubProtocol.ok();
        result.put("groupID",groupID);
        result.put("turnKey",turnKey);
        result.put("granted",claimed.granted);
        result.put("ownerID",turnArbiter.owner(key));
        result.put("score",claimed.score);
        result.put("winnerScore",claimed.winnerScore);
        result.put("staggerMs",claimed.staggerMs);
        result.put("multi",multi);
        result.put("leaseSeconds",config.turnLeaseSeconds);
        return result;
    }

    private JSONObject turnRelease(String botID,JSONObject params) {
        long groupID = params.getLongValue("groupID");
        String turnKey = safe(params.getString("turnKey"));
        String key = groupID+"|"+turnKey;
        boolean released = turnArbiter.release(key,botID);
        JSONObject result = BotHubProtocol.ok();
        result.put("groupID",groupID);
        result.put("turnKey",turnKey);
        result.put("released",released);
        if (released) {
            JSONObject event = new JSONObject(true);
            event.put("groupID",groupID);
            event.put("turnKey",turnKey);
            broadcast(BotHubProtocol.EVENT_TURN_RELEASED,botID,event,botID);
        }
        return result;
    }

    private JSONObject turnStatus(JSONObject params) {
        long groupID = params.getLongValue("groupID");
        String turnKey = safe(params.getString("turnKey"));
        JSONObject described = turnArbiter.describe(groupID+"|"+turnKey);
        JSONObject result = BotHubProtocol.ok();
        result.put("groupID",groupID);
        result.put("turnKey",turnKey);
        result.put("ownerID",described.getString("owner"));
        result.put("score",described.getIntValue("score"));
        result.put("multi",described.getBooleanValue("multi"));
        result.put("expireAt",described.getLongValue("expireAt"));
        return result;
    }

    /**
     * 打字状态广播：赢了回合权的一方在生成前发 start，发完发 stop。
     * 带 TTL，进程崩了也不会永远显示「正在输入」。
     */
    private JSONObject typing(String botID,JSONObject params) {
        long groupID = params.getLongValue("groupID");
        String turnKey = safe(params.getString("turnKey"));
        String state = safe(params.getString("state"));
        Map<String,TypingState> group = typingMap.computeIfAbsent(groupID,k -> new ConcurrentHashMap<>());
        JSONObject result = BotHubProtocol.ok();
        result.put("groupID",groupID);
        result.put("turnKey",turnKey);
        if ("stop".equalsIgnoreCase(state)) {
            group.remove(botID);
            stats.typingStops.incrementAndGet();
            result.put("state","stop");
            broadcastToGroup(BotHubProtocol.EVENT_TYPING_STOPPED,botID,groupID,
                    typingPayload(groupID,turnKey,botID),BotHubProtocol.CAP_TYPING);
            return result;
        }
        TypingState entry = new TypingState();
        entry.botID = botID;
        entry.turnKey = turnKey;
        entry.expireAt = System.currentTimeMillis() + Math.max(5,config.typingTtlSeconds) * 1000L;
        group.put(botID,entry);
        stats.typingStarts.incrementAndGet();
        result.put("state","start");
        result.put("ttlSeconds",config.typingTtlSeconds);
        broadcastToGroup(BotHubProtocol.EVENT_TYPING_STARTED,botID,groupID,
                typingPayload(groupID,turnKey,botID),BotHubProtocol.CAP_TYPING);
        return result;
    }

    private JSONObject typingStatus(JSONObject params) {
        long groupID = params.getLongValue("groupID");
        JSONArray array = new JSONArray();
        Map<String,TypingState> group = typingMap.get(groupID);
        long now = System.currentTimeMillis();
        if (group != null) {
            for (Iterator<Map.Entry<String,TypingState>> iterator = group.entrySet().iterator();
                 iterator.hasNext(); ) {
                Map.Entry<String,TypingState> item = iterator.next();
                if (item.getValue().expired(now)) {
                    iterator.remove();
                    continue;
                }
                JSONObject json = new JSONObject(true);
                json.put("botID",item.getValue().botID);
                json.put("turnKey",item.getValue().turnKey);
                json.put("expireAt",item.getValue().expireAt);
                array.add(json);
            }
        }
        JSONObject result = BotHubProtocol.ok();
        result.put("groupID",groupID);
        result.put("typing",array);
        return result;
    }

    private JSONObject typingPayload(long groupID,String turnKey,String botID) {
        JSONObject payload = new JSONObject(true);
        payload.put("groupID",groupID);
        payload.put("turnKey",turnKey);
        payload.put("botID",botID);
        return payload;
    }

    private int typingCount() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (Map<String,TypingState> group : typingMap.values()) {
            for (Iterator<Map.Entry<String,TypingState>> iterator = group.entrySet().iterator();
                 iterator.hasNext(); ) {
                Map.Entry<String,TypingState> item = iterator.next();
                if (item.getValue().expired(now)) {
                    iterator.remove();
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    private static class TypingState {
        String botID = "";
        String turnKey = "";
        long expireAt;

        boolean expired(long now) {
            return now > expireAt;
        }
    }

    /**
     * 跨进程任务去重：提醒创建这类「同一条消息只能产生一个任务」的场景，
     * 谁先认领谁负责创建，其他 bot 直接跳过。
     */
    private JSONObject taskClaim(String botID,JSONObject params) {
        String key = safe(params.getString("key"));
        if (key.isEmpty()) return BotHubProtocol.fail("缺少 key");
        boolean granted = taskLeases.claim(key,botID,config.taskLeaseSeconds * 1000L);
        JSONObject result = BotHubProtocol.ok();
        result.put("key",key);
        result.put("granted",granted);
        result.put("ownerID",taskLeases.owner(key));
        if (granted) {
            stats.taskGranted.incrementAndGet();
            JSONObject event = new JSONObject(true);
            event.put("key",key);
            broadcast(BotHubProtocol.EVENT_TASK_CLAIMED,botID,event,botID,BotHubProtocol.CAP_TASK);
        } else {
            stats.taskDeduped.incrementAndGet();
        }
        return result;
    }

    private JSONObject taskRelease(String botID,JSONObject params) {
        String key = safe(params.getString("key"));
        JSONObject result = BotHubProtocol.ok();
        result.put("key",key);
        result.put("released",taskLeases.release(key,botID));
        return result;
    }

    private JSONObject taskClear() {
        JSONObject result = BotHubProtocol.ok();
        result.put("count",taskLeases.clear());
        return result;
    }

    /**
     * 机器人互聊熔断：按群累计连续 bot-to-bot 轮数，超限后冷却一段时间。
     * 真人发言（botToBot=false）会把计数清零。
     */
    private JSONObject chatGuard(String botID,JSONObject params) {
        long groupID = params.getLongValue("groupID");
        boolean botToBot = params.getBooleanValue("botToBot");
        long now = System.currentTimeMillis();
        ChatChain chain = chatChains.computeIfAbsent(groupID,k -> new ChatChain());
        JSONObject result = BotHubProtocol.ok();
        result.put("groupID",groupID);
        synchronized (chain) {
            if (!botToBot) {
                //真人发言说明群里有人看着，计数和冷却一起清零，熔断可以立刻恢复
                chain.chain = 0;
                chain.blockedUntil = 0L;
                chain.lastAt = now;
                stats.chatGuardReset.incrementAndGet();
                result.put("allow",true);
                result.put("chain",0);
                return result;
            }
            if (now < chain.blockedUntil) {
                stats.chatGuardBlocked.incrementAndGet();
                result.put("allow",false);
                result.put("reason","cooldown");
                result.put("chain",chain.chain);
                result.put("blockedUntil",chain.blockedUntil);
                return result;
            }
            long window = Math.max(10,config.botChainWindowSeconds) * 1000L;
            if (now - chain.lastAt > window) chain.chain = 0;
            chain.chain++;
            chain.lastAt = now;
            int limit = Math.max(1,config.botChainLimit);
            if (chain.chain > limit) {
                chain.chain = 0;
                chain.blockedUntil = now + Math.max(10,config.botChainCooldownSeconds) * 1000L;
                stats.chatGuardBlocked.incrementAndGet();
                result.put("allow",false);
                result.put("reason","limit");
                result.put("blockedUntil",chain.blockedUntil);
                return result;
            }
            result.put("allow",true);
            result.put("chain",chain.chain);
            result.put("limit",limit);
            return result;
        }
    }

    private JSONObject chatGuardReset(JSONObject params) {
        long groupID = params.getLongValue("groupID");
        ChatChain chain = chatChains.get(groupID);
        if (chain != null) {
            synchronized (chain) {
                chain.chain = 0;
                chain.blockedUntil = 0L;
                chain.lastAt = System.currentTimeMillis();
            }
        }
        stats.chatGuardReset.incrementAndGet();
        JSONObject result = BotHubProtocol.ok();
        result.put("groupID",groupID);
        return result;
    }

    /**
     * server 模式的本地 bot 也要能广播，之前只有 client 能发
     */
    public JSONObject broadcastLocal(String event,JSONObject data) {
        String name = safe(event);
        if (name.isEmpty()) return BotHubProtocol.fail("缺少 event");
        broadcast(name,localBotID,data,localBotID);
        JSONObject result = BotHubProtocol.ok();
        result.put("published",true);
        return result;
    }

    private static class ChatChain {
        int chain;
        long lastAt;
        long blockedUntil;
    }

    private JSONObject helloOk(BotHubPeer peer) {
        JSONObject message = BotHubProtocol.message(BotHubProtocol.TYPE_HELLO_OK);
        message.put("serverTime",System.currentTimeMillis());
        message.put("botID",peer.botID);
        message.put("peers",peersArray(0));
        message.put("visionShareEnable",config.visionShareEnable);
        message.put("turnShareEnable",config.turnShareEnable);
        message.put("caps",BotHubProtocol.supportedCaps());
        return message;
    }

    private JSONArray peersArray(long groupID) {
        JSONArray array = new JSONArray();
        for (BotHubPeer peer : peers.values()) {
            if (groupID > 0 && !peer.inGroup(groupID)) continue;
            array.add(peerJson(peer));
        }
        return array;
    }

    private JSONObject peerJson(BotHubPeer peer) {
        if (peer == null) return new JSONObject(true);
        return peer.toJson();
    }

    private void broadcast(String event,String from,JSONObject data,String exceptBotID) {
        broadcast(event,from,data,exceptBotID,null);
    }

    /**
     * requiredCap 不为空时只发给声明支持该能力的 peer；老版本 peer 上报空能力列表按全支持处理。
     */
    private void broadcast(String event,String from,JSONObject data,String exceptBotID,String requiredCap) {
        JSONObject message = BotHubProtocol.event(event,from,data);
        for (Map.Entry<String,BotHubConnection> item : connections.entrySet()) {
            if (exceptBotID != null && exceptBotID.equals(item.getKey())) continue;
            BotHubPeer target = peers.get(item.getKey());
            if (requiredCap != null && target != null && !target.supports(requiredCap)) continue;
            item.getValue().send(message);
        }
        BotHubEventListener listener = localListener;
        if (listener != null && (exceptBotID == null || !exceptBotID.equals(localBotID))) {
            try {
                listener.onEvent(message);
            } catch (Exception ignored) {
            }
        }
        stats.broadcasts.incrementAndGet();
    }

    /**
     * 只发给某一个 peer（本地 bot 走内存回调）
     */
    private void sendTo(String botID,JSONObject message) {
        if (botID == null || botID.isEmpty() || message == null) return;
        BotHubConnection connection = connections.get(botID);
        if (connection != null) {
            connection.send(message);
            return;
        }
        BotHubEventListener listener = localListener;
        if (listener != null && botID.equals(localBotID)) {
            try {
                listener.onEvent(message);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 按群过滤广播：只发给声明参与该群的 peer
     */
    private void broadcastToGroup(String event,String from,long groupID,JSONObject data,String requiredCap) {
        JSONObject message = BotHubProtocol.event(event,from,data);
        for (Map.Entry<String,BotHubConnection> item : connections.entrySet()) {
            if (from != null && from.equals(item.getKey())) continue;
            BotHubPeer target = peers.get(item.getKey());
            if (target != null && !target.inGroup(groupID)) continue;
            if (requiredCap != null && target != null && !target.supports(requiredCap)) continue;
            item.getValue().send(message);
        }
        BotHubEventListener listener = localListener;
        BotHubPeer local = localBotID == null || localBotID.isEmpty() ? null : peers.get(localBotID);
        if (listener != null && local != null && local.inGroup(groupID)
                && (from == null || !from.equals(localBotID))) {
            try {
                listener.onEvent(message);
            } catch (Exception ignored) {
            }
        }
        stats.broadcasts.incrementAndGet();
    }

    private boolean authOk(String token) {
        if (config.token == null || config.token.trim().isEmpty()) return true;
        return config.token.trim().equals(token == null ? "" : token.trim());
    }

    private List<Long> parseGroups(JSONArray array) {
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

    private void purgeLoop() {
        long interval = Math.max(3000L,config.heartbeatSeconds * 1000L);
        while (running.get()) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            long timeout = Math.max(config.leaseSeconds,config.heartbeatSeconds * 3) * 1000L;
            for (BotHubPeer peer : peers.values()) {
                if (peer.local) continue;
                if (now - peer.lastSeen <= timeout) continue;
                Object transport = peer.transport;
                if (!(transport instanceof BotHubConnection)) continue;
                //只关掉这条 peer 自己登记的那条连接，避免误伤同 botID 的新连接
                connections.remove(peer.botID,transport);
                ((BotHubConnection) transport).close();
            }
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
