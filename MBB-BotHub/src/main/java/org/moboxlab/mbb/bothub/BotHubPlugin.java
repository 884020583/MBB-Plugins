package org.moboxlab.mbb.bothub;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginService;

/**
 * MBB-BotHub 插件
 *
 * 首次加载会探测本机 18765 端口：已有 hubserver 就生成 client 配置，
 * 端口空闲就生成 server 配置，让第一个启动的实例自然成为中枢。
 */
public class BotHubPlugin extends Plugin {
    private static final int IDENTITY_MAX_ATTEMPTS = 12;
    private static final long IDENTITY_RETRY_SECONDS = 5L;

    private BotHubConfig config;
    private BotHubServer server;
    private BotHubClient client;
    private BotHubService service;
    private int identityAttempt = 0;
    private volatile boolean failoverScheduled = false;
    private volatile long lastBackfillAt = 0L;

    @Override
    public void onLoad() {
        config = BotHubConfig.load(this);
    }

    @Override
    public void onEnable() {
        if (config == null) config = BotHubConfig.load(this);
        startHub();
        getServer().getPluginManager().registerCommand(this,new BotHubCommand(this));
        getLogger().sendInfo("MBB-BotHub 已启用，模式="+config.mode+" botID="+config.botID
                +" 地址="+config.host+":"+config.port);
        resolveIdentity();
    }

    @Override
    public void onDisable() {
        stopHub();
        getLogger().sendInfo("MBB-BotHub 已停用！");
    }

    public BotHubConfig config() {
        return config;
    }

    public BotHubService service() {
        return service;
    }

    /**
     * 切换 server / client / auto，写回配置并重启协作链路。
     */
    public synchronized boolean applyMode(String mode) {
        String value = mode == null ? "auto" : mode.trim().toLowerCase();
        if (!"server".equals(value) && !"client".equals(value) && !"auto".equals(value)) return false;
        getConfig().set("mode",value);
        getConfig().save();
        stopHub();
        config = BotHubConfig.load(this);
        startHub();
        identityAttempt = 0;
        resolveIdentity();
        return true;
    }

    public synchronized void reloadHub() {
        stopHub();
        config = BotHubConfig.load(this);
        startHub();
        identityAttempt = 0;
        resolveIdentity();
    }

    public boolean reconnectClient() {
        if (client == null) return false;
        //只断开当前连接，让后台线程自己重连，避免 stop/start 之间出现两个 worker 线程
        client.reconnect();
        return true;
    }

    public void setToken(String token) {
        config.token = token == null ? "" : token;
        getConfig().set("token",config.token);
        getConfig().save();
    }

    /**
     * 手工触发一次身份探测，供 /hub identity 使用
     */
    public void resolveIdentityNow() {
        identityAttempt = 0;
        resolveIdentity();
    }

    /**
     * 自动补全身份：botQQ 从 OneBot 的 get_login_info 取，role 从 MBB-Roleplay 的
     * identity 动作取。OneBot 还没连上时每 5 秒重试一次，最多 12 次。
     */
    private void resolveIdentity() {
        boolean changed = false;
        if (config.botQQ <= 0) {
            long qq = fetchBotQQ();
            if (qq > 0) {
                config.botQQ = qq;
                getConfig().set("botQQ",String.valueOf(qq));
                changed = true;
            }
        }
        if (config.role == null || config.role.trim().isEmpty()) {
            String role = fetchPersonaName();
            if (role != null && !role.trim().isEmpty()) {
                config.role = role.trim();
                getConfig().set("role",config.role);
                changed = true;
            }
        }
        if (changed) {
            boolean autoID = config.botID == null || config.botID.trim().isEmpty()
                    || "bot".equals(config.botID) || config.botID.startsWith("bot-");
            if (autoID && config.botQQ > 0) {
                config.botID = String.valueOf(config.botQQ);
                getConfig().set("botID",config.botID);
            }
            getConfig().save();
            getLogger().sendInfo("[BotHub] 身份自动补全 botQQ="+config.botQQ
                    +" role="+config.role+" botID="+config.botID);
            refreshLinkIdentity();
        }
        //QQ 还没拿到就继续重试，即使这一轮只补到了角色名
        if (config.botQQ <= 0 && identityAttempt < IDENTITY_MAX_ATTEMPTS) {
            identityAttempt++;
            getServer().getPluginManager().runTaskLater(this,this::resolveIdentity,IDENTITY_RETRY_SECONDS);
            return;
        }
        if (config.botQQ <= 0) {
            getLogger().sendWarn("[BotHub] 没能从 OneBot 取到本机 QQ，当前用 "
                    +config.botID+" 作为身份；请确认 OneBot 已连接，或手工填写 botQQ。");
        }
    }

    private long fetchBotQQ() {
        try {
            if (getServer().getOneBotClient() == null) return 0L;
            JSONObject response = getServer().getOneBotClient().callAction("get_login_info",null);
            if (response == null) return 0L;
            JSONObject data = response.getJSONObject("data");
            if (data == null) return 0L;
            long userID = data.getLongValue("user_id");
            return userID > 0 ? userID : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private String fetchPersonaName() {
        try {
            PluginService roleplay = getServer().getPluginManager().getService("MBB-Roleplay");
            if (roleplay == null) return "";
            JSONObject result = roleplay.call("identity",null);
            if (result == null || !result.getBooleanValue("status")) return "";
            String persona = result.getString("persona");
            return persona == null ? "" : persona.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 身份变了要让 hub 链路用新身份重新握手：client 重连，server 重新登记本地 peer。
     */
    private void refreshLinkIdentity() {
        if ("client".equals(config.mode)) {
            if (client != null) client.reconnect();
            return;
        }
        if (server != null) {
            server.registerLocalPeer(config.botID,config.botQQ,config.role,getVersion(),
                    BotHubPeer.parseGroups(config.groups),config.capList());
        }
    }

    private void startHub() {
        if ("client".equals(config.mode)) {
            client = new BotHubClient(this,config);
        } else if (!startServer() && config.autoFailover) {
            //绑定不上说明已经有别的节点占着端口，退回 client 让它接管
            getLogger().sendWarn("[BotHub] server 绑定失败，自动切换为 client 等待其他节点接管");
            config.mode = "client";
            client = new BotHubClient(this,config);
        }
        service = new BotHubService(this,config,server,client);
        //每次重建链路都重新注册一次，否则 reload / 切换模式后插件管理器还拿着旧的服务实例
        getServer().getPluginManager().registerService(this,service);
        if (client != null) {
            client.setConnectListener(this::backfillVisionCache);
            client.start();
        }
        scheduleFailoverCheck();
    }

    private boolean startServer() {
        BotHubServer candidate = new BotHubServer(this,config);
        try {
            candidate.registerLocalPeer(config.botID,config.botQQ,config.role,getVersion(),
                    BotHubPeer.parseGroups(config.groups),config.capList());
            candidate.start();
        } catch (Exception e) {
            getLogger().sendWarn("[BotHub] server 启动失败："+e.getMessage()
                    +"，地址 "+config.host+":"+config.port);
            return false;
        }
        server = candidate;
        warnIfExposed();
        return true;
    }

    /**
     * 绑到非本机地址时提醒改 token，默认配置只监听 127.0.0.1
     */
    private void warnIfExposed() {
        if (BotHubConfig.isLoopbackHost(config.host)) return;
        String token = config.token == null ? "" : config.token.trim();
        if (token.isEmpty() || "change-me".equals(token)) {
            getLogger().sendWarn("[BotHub] 正在监听 "+config.host+":"+config.port
                    +"，但 token 还是默认值。内网/公网部署请先改 token，否则任何人都能连进来。");
            return;
        }
        getLogger().sendInfo("[BotHub] 已监听 "+config.host+":"+config.port+"（非本机地址，请确认网络边界）");
    }

    /**
     * client 连不上时定时检查，达到阈值就尝试自己绑定端口当新 hubserver。
     * 端口绑定是互斥的，所以多个 client 同时抢也只有一个能成功。
     */
    private void scheduleFailoverCheck() {
        if (failoverScheduled || !config.autoFailover) return;
        failoverScheduled = true;
        long period = Math.max(3,config.reconnectSeconds);
        getServer().getPluginManager().runTaskTimer(this,this::checkFailover,period,period);
    }

    private synchronized void checkFailover() {
        if (!config.autoFailover || !"client".equals(config.mode)) return;
        BotHubClient current = client;
        if (current == null || current.isConnected()) return;
        if (current.getFailedAttempts() < config.failoverAfterAttempts) return;
        getLogger().sendWarn("[BotHub] 连续 "+current.getFailedAttempts()
                +" 次连不上 hubserver，尝试接管为新的服务端");
        BotHubServer candidate = new BotHubServer(this,config);
        try {
            candidate.registerLocalPeer(config.botID,config.botQQ,config.role,getVersion(),
                    BotHubPeer.parseGroups(config.groups),config.capList());
            candidate.start();
        } catch (Exception e) {
            getLogger().sendInfo("[BotHub] 接管失败，端口已被其他节点占用，继续作为 client 重连");
            return;
        }
        current.stop();
        client = null;
        server = candidate;
        config.mode = "server";
        service = new BotHubService(this,config,server,client);
        getServer().getPluginManager().registerService(this,service);
        warnIfExposed();
        getLogger().sendInfo("[BotHub] 已接管为 hubserver，监听 "+config.host+":"+config.port);
    }

    /**
     * 把本地 MBB-Vision 缓存灌进 hub，让其他 bot 不必重复识图。
     * 每次重连后最多 5 分钟跑一次。
     */
    private void backfillVisionCache() {
        long now = System.currentTimeMillis();
        if (now - lastBackfillAt < 300000L) return;
        lastBackfillAt = now;
        PluginService vision = getServer().getPluginManager().getService("MBB-Vision");
        BotHubService hub = service;
        if (vision == null || hub == null) return;
        JSONObject dump;
        try {
            JSONObject params = new JSONObject(true);
            params.put("limit",500);
            dump = vision.call("dump",params);
        } catch (Exception e) {
            return;
        }
        if (dump == null || !dump.getBooleanValue("status")) return;
        JSONArray entries = dump.getJSONArray("entries");
        if (entries == null || entries.isEmpty()) return;
        JSONObject params = new JSONObject(true);
        params.put("entries",entries);
        JSONObject result = hub.call("vision_backfill",params);
        if (result != null && result.getBooleanValue("status")) {
            getLogger().sendInfo("[BotHub] 本地识图缓存回填 "+result.getIntValue("stored")+" 条到 hub");
        }
    }

    private void stopHub() {
        if (server != null) {
            server.removeLocalPeer();
            server.stop();
            server = null;
        }
        if (client != null) {
            client.stop();
            client = null;
        }
        service = null;
    }
}
