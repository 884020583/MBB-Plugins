package org.moboxlab.mbb.bothub;

import org.moboxlab.moboxbot.API.Plugin;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * MBB-BotHub 配置
 *
 * 首次生成配置时会探测本机 port 端口：
 * 端口已被占用说明本机已经跑着 hubserver，当前实例生成 client 配置；
 * 端口空闲说明当前实例就是第一个启动的，生成 server 配置。
 */
public class BotHubConfig {
    public String mode = "auto";
    public String host = "127.0.0.1";
    public int port = 18765;
    public String token = "change-me";
    public String botID = "";
    public long botQQ = 0L;
    public String role = "";
    public String groups = "";
    public String caps = "vision,turn,task,chat_guard";
    public int heartbeatSeconds = 10;
    public int requestTimeoutMs = 3000;
    public int reconnectSeconds = 5;
    public int leaseSeconds = 30;
    public boolean autoFailover = true;
    public int failoverAfterAttempts = 3;
    public boolean visionShareEnable = true;
    public int visionLeaseSeconds = 30;
    public int visionWaitMs = 15000;
    public int visionCacheKeepHours = 72;
    public int visionCacheMaxEntries = 1000;
    public boolean turnShareEnable = true;
    public int turnLeaseSeconds = 60;
    public int typingTtlSeconds = 30;
    public int multiReplyStaggerMs = 1500;
    public int taskLeaseSeconds = 3600;
    public int botChainLimit = 4;
    public int botChainWindowSeconds = 180;
    public int botChainCooldownSeconds = 120;

    public static BotHubConfig load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(),"config.yml");
        boolean generated = false;
        if (!file.exists()) {
            generated = true;
            writeDefault(file,detectMode("127.0.0.1",18765));
        }
        plugin.getConfig().load();
        BotHubConfig config = new BotHubConfig();
        config.mode = plugin.getConfig().getString("mode","auto");
        config.host = plugin.getConfig().getString("host","127.0.0.1");
        config.port = plugin.getConfig().getInt("port",18765);
        config.token = plugin.getConfig().getString("token","change-me");
        config.botID = plugin.getConfig().getString("botID","");
        config.botQQ = plugin.getConfig().getLong("botQQ",0L);
        config.role = plugin.getConfig().getString("role","");
        config.groups = plugin.getConfig().getString("groups","");
        config.caps = plugin.getConfig().getString("caps","vision,turn,task,chat_guard");
        config.heartbeatSeconds = plugin.getConfig().getInt("heartbeatSeconds",10);
        config.requestTimeoutMs = plugin.getConfig().getInt("requestTimeoutMs",3000);
        config.reconnectSeconds = plugin.getConfig().getInt("reconnectSeconds",5);
        config.leaseSeconds = plugin.getConfig().getInt("leaseSeconds",30);
        config.autoFailover = plugin.getConfig().getBoolean("autoFailover",true);
        config.failoverAfterAttempts = plugin.getConfig().getInt("failoverAfterAttempts",3);
        config.visionShareEnable = plugin.getConfig().getBoolean("visionShareEnable",true);
        config.visionLeaseSeconds = plugin.getConfig().getInt("visionLeaseSeconds",30);
        config.visionWaitMs = plugin.getConfig().getInt("visionWaitMs",15000);
        config.visionCacheKeepHours = plugin.getConfig().getInt("visionCacheKeepHours",72);
        config.visionCacheMaxEntries = plugin.getConfig().getInt("visionCacheMaxEntries",1000);
        config.turnShareEnable = plugin.getConfig().getBoolean("turnShareEnable",true);
        config.turnLeaseSeconds = plugin.getConfig().getInt("turnLeaseSeconds",60);
        config.typingTtlSeconds = plugin.getConfig().getInt("typingTtlSeconds",30);
        config.multiReplyStaggerMs = plugin.getConfig().getInt("multiReplyStaggerMs",1500);
        config.taskLeaseSeconds = plugin.getConfig().getInt("taskLeaseSeconds",3600);
        config.botChainLimit = plugin.getConfig().getInt("botChainLimit",4);
        config.botChainWindowSeconds = plugin.getConfig().getInt("botChainWindowSeconds",180);
        config.botChainCooldownSeconds = plugin.getConfig().getInt("botChainCooldownSeconds",120);
        if (config.mode == null || config.mode.trim().isEmpty() || "auto".equalsIgnoreCase(config.mode)) {
            config.mode = detectMode("127.0.0.1",config.port);
        }
        config.mode = config.mode.toLowerCase();
        if (!"server".equals(config.mode) && !"client".equals(config.mode)) config.mode = "server";
        if (config.host == null || config.host.trim().isEmpty()) {
            config.host = "server".equals(config.mode) ? "0.0.0.0" : "127.0.0.1";
        }
        if (config.token == null) config.token = "";
        if (config.botID == null) config.botID = "";
        if (config.role == null) config.role = "";
        if (config.groups == null) config.groups = "";
        if (config.caps == null) config.caps = "";
        if (config.port < 1 || config.port > 65535) config.port = 18765;
        config.heartbeatSeconds = clamp(config.heartbeatSeconds,3,120);
        config.requestTimeoutMs = clamp(config.requestTimeoutMs,300,15000);
        config.reconnectSeconds = clamp(config.reconnectSeconds,2,120);
        config.leaseSeconds = clamp(config.leaseSeconds,10,600);
        config.visionLeaseSeconds = clamp(config.visionLeaseSeconds,5,300);
        config.visionWaitMs = clamp(config.visionWaitMs,1000,60000);
        config.visionCacheKeepHours = clamp(config.visionCacheKeepHours,1,720);
        config.visionCacheMaxEntries = clamp(config.visionCacheMaxEntries,50,5000);
        config.turnLeaseSeconds = clamp(config.turnLeaseSeconds,10,600);
        config.typingTtlSeconds = clamp(config.typingTtlSeconds,5,300);
        config.multiReplyStaggerMs = clamp(config.multiReplyStaggerMs,0,10000);
        config.failoverAfterAttempts = clamp(config.failoverAfterAttempts,1,60);
        config.taskLeaseSeconds = clamp(config.taskLeaseSeconds,60,86400);
        config.botChainLimit = clamp(config.botChainLimit,1,50);
        config.botChainWindowSeconds = clamp(config.botChainWindowSeconds,10,3600);
        config.botChainCooldownSeconds = clamp(config.botChainCooldownSeconds,10,3600);
        if (config.botID.trim().isEmpty()) {
            config.botID = config.botQQ > 0 ? String.valueOf(config.botQQ) : stableFallbackID(plugin);
        }
        if (repairMissing(plugin,config)) {
            plugin.getLogger().sendInfo("[BotHub] 配置缺少的键已自动补全");
        }
        if (generated) {
            plugin.getLogger().sendInfo("[BotHub] 首次生成配置，探测模式="+config.mode
                    +"（"+("client".equals(config.mode)
                    ? "本机 "+config.port+" 端口已有 hubserver"
                    : "本机 "+config.port+" 端口空闲")+"）");
        }
        return config;
    }

    /**
     * 老版本配置或手工删过键时补齐缺失项，保留已有值。
     */
    private static boolean repairMissing(Plugin plugin,BotHubConfig config) {
        boolean changed = false;
        changed |= ensure(plugin,"mode",config.mode);
        changed |= ensure(plugin,"host",config.host);
        changed |= ensure(plugin,"port",String.valueOf(config.port));
        changed |= ensure(plugin,"token",config.token);
        changed |= ensure(plugin,"botID",config.botID);
        changed |= ensure(plugin,"botQQ",String.valueOf(config.botQQ));
        changed |= ensure(plugin,"role",config.role);
        changed |= ensure(plugin,"groups",config.groups);
        changed |= ensure(plugin,"caps",config.caps);
        changed |= ensure(plugin,"heartbeatSeconds",String.valueOf(config.heartbeatSeconds));
        changed |= ensure(plugin,"requestTimeoutMs",String.valueOf(config.requestTimeoutMs));
        changed |= ensure(plugin,"reconnectSeconds",String.valueOf(config.reconnectSeconds));
        changed |= ensure(plugin,"leaseSeconds",String.valueOf(config.leaseSeconds));
        changed |= ensure(plugin,"autoFailover",String.valueOf(config.autoFailover));
        changed |= ensure(plugin,"failoverAfterAttempts",String.valueOf(config.failoverAfterAttempts));
        changed |= ensure(plugin,"visionShareEnable",String.valueOf(config.visionShareEnable));
        changed |= ensure(plugin,"visionLeaseSeconds",String.valueOf(config.visionLeaseSeconds));
        changed |= ensure(plugin,"visionWaitMs",String.valueOf(config.visionWaitMs));
        changed |= ensure(plugin,"visionCacheKeepHours",String.valueOf(config.visionCacheKeepHours));
        changed |= ensure(plugin,"visionCacheMaxEntries",String.valueOf(config.visionCacheMaxEntries));
        changed |= ensure(plugin,"turnShareEnable",String.valueOf(config.turnShareEnable));
        changed |= ensure(plugin,"turnLeaseSeconds",String.valueOf(config.turnLeaseSeconds));
        changed |= ensure(plugin,"typingTtlSeconds",String.valueOf(config.typingTtlSeconds));
        changed |= ensure(plugin,"multiReplyStaggerMs",String.valueOf(config.multiReplyStaggerMs));
        changed |= ensure(plugin,"taskLeaseSeconds",String.valueOf(config.taskLeaseSeconds));
        changed |= ensure(plugin,"botChainLimit",String.valueOf(config.botChainLimit));
        changed |= ensure(plugin,"botChainWindowSeconds",String.valueOf(config.botChainWindowSeconds));
        changed |= ensure(plugin,"botChainCooldownSeconds",String.valueOf(config.botChainCooldownSeconds));
        if (changed) plugin.getConfig().save();
        return changed;
    }

    private static boolean ensure(Plugin plugin,String key,String value) {
        if (plugin.getConfig().contains(key)) return false;
        plugin.getConfig().set(key,value == null ? "" : value);
        return true;
    }

    /**
     * 探测本机端口：被占用返回 client，空闲返回 server。
     */
    public static String detectMode(String host,int port) {
        return isPortOpen(host,port) ? "client" : "server";
    }

    /**
     * 拿不到 QQ 时用数据目录路径生成稳定的实例 ID。
     * 如果直接写死 "bot"，同机多开的实例会撞成同一个 botID，在 hub 里互相顶掉。
     */
    private static String stableFallbackID(Plugin plugin) {
        String path = plugin == null ? "" : plugin.getDataFolder();
        if (path == null || path.trim().isEmpty()) return "bot";
        String normalized = path.trim().toLowerCase().replace('\\','/');
        int hash = 0;
        for (int i = 0; i < normalized.length(); i++) {
            hash = hash * 31 + normalized.charAt(i);
        }
        return "bot-"+String.format("%08x",hash & 0xffffffffL);
    }

    private static void writeDefault(File file,String mode) {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            //默认只监听本机。要跨机器部署时把 host 改成 0.0.0.0（或具体内网/公网地址），
            //并同时改掉 token，否则任何人连上都能冒充协作节点。
            String host = "127.0.0.1";
            String text = "#MBB-BotHub 配置\n"
                    +"#模式：server / client。auto 会在启动时重新探测本机端口。\n"
                    +"#首次生成时已按本机 18765 端口是否被占用自动选好：\n"
                    +"#  端口空闲 -> server（当前实例当 hub 服务端）\n"
                    +"#  端口被占用 -> client（连接已有的 hub 服务端）\n"
                    +"mode: \""+mode+"\"\n"
                    +"#server 监听地址；client 连接地址\n"
                    +"host: \""+host+"\"\n"
                    +"port: 18765\n"
                    +"#共享鉴权 token，同一个 hub 下所有 bot 必须一致\n"
                    +"token: \"change-me\"\n"
                    +"#当前机器人身份，同一个 hub 内必须唯一\n"
                    +"botID: \"\"\n"
                    +"botQQ: 0\n"
                    +"role: \"\"\n"
                    +"#只参与协作的群号，英文逗号分隔；留空表示全部群\n"
                    +"groups: \"\"\n"
                    +"#上报的能力列表，英文逗号分隔；留空表示全部支持\n"
                    +"#可选：vision / turn / task / chat_guard\n"
                    +"caps: \"vision,turn,task,chat_guard\"\n"
                    +"#心跳间隔\n"
                    +"heartbeatSeconds: 10\n"
                    +"#单次请求超时毫秒\n"
                    +"requestTimeoutMs: 3000\n"
                    +"#client 断线重连间隔\n"
                    +"reconnectSeconds: 5\n"
                    +"#服务端判定 peer 掉线的空闲秒数\n"
                    +"leaseSeconds: 30\n"
                    +"#server 绑定失败或 client 连不上时是否自动选举新的 hub 服务端\n"
                    +"autoFailover: true\n"
                    +"#连续连接失败多少次后尝试自己当 server\n"
                    +"failoverAfterAttempts: 3\n"
                    +"#是否共享识图结果，避免同群多个 bot 重复识图\n"
                    +"visionShareEnable: true\n"
                    +"#识图进行中的认领租约秒数\n"
                    +"visionLeaseSeconds: 30\n"
                    +"#等待其他 bot 识图结果毫秒\n"
                    +"visionWaitMs: 15000\n"
                    +"#共享识图缓存保留小时\n"
                    +"visionCacheKeepHours: 72\n"
                    +"#共享识图缓存最大条目数（同一结果会占多个别名槽位）\n"
                    +"visionCacheMaxEntries: 1000\n"
                    +"#是否共享回合权，避免同群多个 bot 抢答同一条消息\n"
                    +"turnShareEnable: true\n"
                    +"#回合权租约秒数\n"
                    +"turnLeaseSeconds: 60\n"
                    +"#打字状态广播的有效秒数\n"
                    +"typingTtlSeconds: 30\n"
                    +"#双方都被点名时，第二个回复的错峰延迟毫秒\n"
                    +"multiReplyStaggerMs: 1500\n"
                    +"#跨进程任务去重租约秒数（提醒这类任务只允许创建一次）\n"
                    +"taskLeaseSeconds: 3600\n"
                    +"#机器人互聊熔断：同群连续 bot-to-bot 轮数上限\n"
                    +"botChainLimit: 4\n"
                    +"#熔断统计窗口秒数\n"
                    +"botChainWindowSeconds: 180\n"
                    +"#触发熔断后的冷却秒数\n"
                    +"botChainCooldownSeconds: 120\n";
            Files.write(file.toPath(),text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    public static boolean isPortOpen(String host,int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host,port),300);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 是否只绑定本机。绑到非回环地址时插件会提醒改 token。
     */
    public static boolean isLoopbackHost(String host) {
        if (host == null) return true;
        String value = host.trim().toLowerCase();
        if (value.isEmpty()) return true;
        return "127.0.0.1".equals(value) || "localhost".equals(value) || "::1".equals(value);
    }

    /**
     * 上报给 hub 的能力列表，空表示全部支持
     */
    public List<String> capList() {
        List<String> result = new ArrayList<>();
        if (caps == null) return result;
        String[] parts = caps.split("[,，;；\\s]+");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (!value.isEmpty() && !result.contains(value)) result.add(value);
        }
        return result;
    }

    private static int clamp(int value,int min,int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
