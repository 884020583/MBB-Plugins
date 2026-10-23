package org.moboxlab.mbb.bothub;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Command.BotCommand;
import org.moboxlab.moboxbot.API.Command.CommandPermission;
import org.moboxlab.moboxbot.API.Command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /hub 多机器人协作管理命令
 */
public class BotHubCommand extends BotCommand {
    private final BotHubPlugin plugin;

    public BotHubCommand(BotHubPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> prefix() {
        List<String> list = new ArrayList<>();
        list.add("hub");
        list.add("协作");
        return list;
    }

    @Override
    public CommandPermission permission() {
        return CommandPermission.BOT_ADMIN;
    }

    @Override
    public int cooldownSeconds() {
        return 3;
    }

    @Override
    public String description() {
        return "多机器人协作中枢管理";
    }

    @Override
    public List<String> usage() {
        return Arrays.asList("/hub status","/hub peers","/hub mode server|client|auto",
                "/hub reload","/hub reconnect","/hub identity","/hub token <共享token>",
                "/hub stats","/hub task clear","/hub vision clear");
    }

    @Override
    public boolean execute(CommandSender sender,String[] args) {
        String action = args != null && args.length > 1 ? args[1].toLowerCase() : "status";
        if ("peers".equals(action)) {
            sender.sendMessage(peerText());
            return true;
        }
        if ("stats".equals(action)) {
            sender.sendMessage(statsText());
            return true;
        }
        if ("task".equals(action)) {
            String sub = args.length > 2 ? args[2].toLowerCase() : "status";
            if ("clear".equals(sub)) {
                JSONObject result = plugin.service().call("task_clear",null);
                sender.sendMessage(result.getBooleanValue("status")
                        ? "已清空任务去重记录 "+result.getIntValue("count")+" 条。"
                        : "清空失败："+result.getString("message"));
                return true;
            }
            JSONObject stats = plugin.service().call("stats",null);
            sender.sendMessage("任务去重记录："+stats.getIntValue("taskCount")
                    +"\n放行 "+stats.getJSONObject("counters").getLongValue("taskGranted")
                    +" 次，去重 "+stats.getJSONObject("counters").getLongValue("taskDeduped")+" 次。");
            return true;
        }
        if ("mode".equals(action)) {
            if (args.length < 3) {
                sender.sendMessage("当前模式："+plugin.config().mode+"\n用法：/hub mode server|client|auto");
                return true;
            }
            String mode = args[2].toLowerCase();
            if (!"server".equals(mode) && !"client".equals(mode) && !"auto".equals(mode)) {
                sender.sendMessage("模式只能是 server、client 或 auto。");
                return true;
            }
            boolean ok = plugin.applyMode(mode);
            sender.sendMessage(ok ? "模式已切换为 "+plugin.config().mode+"，协作链路已重启。"
                    : "模式切换失败，请查看控制台日志。");
            return true;
        }
        if ("reload".equals(action)) {
            plugin.reloadHub();
            sender.sendMessage("MBB-BotHub 配置已重载，当前模式："+plugin.config().mode);
            return true;
        }
        if ("reconnect".equals(action)) {
            boolean ok = plugin.reconnectClient();
            sender.sendMessage(ok ? "已触发 hub 重连。" : "当前不是 client 模式，无需重连。");
            return true;
        }
        if ("identity".equals(action)) {
            plugin.resolveIdentityNow();
            sender.sendMessage("已尝试自动补全身份：botQQ="+plugin.config().botQQ
                    +" role="+plugin.config().role+" botID="+plugin.config().botID);
            return true;
        }
        if ("token".equals(action)) {
            if (args.length < 3) {
                sender.sendMessage("用法：/hub token <共享token>");
                return true;
            }
            plugin.setToken(args[2]);
            sender.sendMessage("共享 token 已更新，需要重启或 /hub reload 后生效。");
            return true;
        }
        if ("vision".equals(action)) {
            String sub = args.length > 2 ? args[2].toLowerCase() : "status";
            if ("clear".equals(sub)) {
                JSONObject result = plugin.service().call("vision_clear",null);
                sender.sendMessage(result.getBooleanValue("status")
                        ? "共享识图缓存已清空，共 "+result.getIntValue("count")+" 条。"
                        : "清空失败："+result.getString("message"));
                return true;
            }
            JSONObject status = plugin.service().call("status",null);
            JSONObject server = status.getJSONObject("server");
            sender.sendMessage("共享识图缓存："+(server == null ? 0 : server.getIntValue("visionCacheSize"))
                    +"\n识图认领中："+(server == null ? 0 : server.getIntValue("visionClaimCount")));
            return true;
        }
        sender.sendMessage(statusText());
        return true;
    }

    private String statusText() {
        JSONObject status = plugin.service().call("status",null);
        StringBuilder builder = new StringBuilder();
        builder.append("MBB-BotHub 状态\n");
        builder.append("模式：").append(plugin.config().mode)
                .append(status.getBooleanValue("available") ? "（在线）" : "（未就绪）").append("\n");
        builder.append("地址：").append(plugin.config().host).append(":").append(plugin.config().port).append("\n");
        builder.append("身份：").append(plugin.config().botID)
                .append(" / ").append(plugin.config().botQQ)
                .append(plugin.config().role.isEmpty() ? "" : " / "+plugin.config().role).append("\n");
        builder.append("节点数：").append(status.getIntValue("peerCount")).append("\n");
        builder.append("识图共享：").append(plugin.config().visionShareEnable ? "开" : "关")
                .append("，回合权共享：").append(plugin.config().turnShareEnable ? "开" : "关").append("\n");
        if ("client".equals(plugin.config().mode)) {
            String error = status.getString("lastError");
            if (error != null && !error.isEmpty()) builder.append("最近错误：").append(error).append("\n");
        }
        builder.append("使用 /hub peers 查看节点列表。");
        return builder.toString();
    }

    private String peerText() {
        JSONArray peers = plugin.service().peersArray();
        if (peers == null || peers.isEmpty()) return "当前没有其他协作节点。";
        StringBuilder builder = new StringBuilder("协作节点（"+peers.size()+"）：\n");
        for (int i = 0; i < peers.size(); i++) {
            JSONObject peer = peers.getJSONObject(i);
            if (peer == null) continue;
            builder.append("- ").append(peer.getString("botID"))
                    .append(" / ").append(peer.getLongValue("botQQ"));
            String role = peer.getString("role");
            if (role != null && !role.isEmpty()) builder.append(" / ").append(role);
            if (peer.getBooleanValue("local")) builder.append("（本机）");
            builder.append("\n");
        }
        return builder.toString().trim();
    }

    private String statsText() {
        JSONObject stats = plugin.service().call("stats",null);
        if (stats == null || !stats.getBooleanValue("status")) return "统计不可用：hub 未就绪。";
        JSONObject c = stats.getJSONObject("counters");
        if (c == null) c = new JSONObject(true);
        StringBuilder builder = new StringBuilder("MBB-BotHub 统计\n");
        builder.append("运行：").append(stats.getLongValue("uptimeMs") / 1000L).append(" 秒，节点 ")
                .append(stats.getIntValue("peerCount")).append(" 个\n");
        builder.append("识图共享：命中 ").append(c.getLongValue("visionLookupHit"))
                .append(" / 未命中 ").append(c.getLongValue("visionLookupMiss"))
                .append("，认领 ").append(c.getLongValue("visionClaimGranted"))
                .append("，被抢 ").append(c.getLongValue("visionClaimDenied")).append("\n");
        builder.append("省下的识图调用：").append(c.getLongValue("visionSaved")).append(" 次\n");
        builder.append("本地缓存回填：").append(c.getLongValue("visionBackfilled")).append(" 条\n");
        builder.append("回合权：放行 ").append(c.getLongValue("turnGranted"))
                .append(" / 拦截 ").append(c.getLongValue("turnDenied")).append("\n");
        builder.append("任务去重：放行 ").append(c.getLongValue("taskGranted"))
                .append(" / 去重 ").append(c.getLongValue("taskDeduped")).append("\n");
        builder.append("互聊熔断：拦截 ").append(c.getLongValue("chatGuardBlocked"))
                .append(" / 重置 ").append(c.getLongValue("chatGuardReset")).append("\n");
        builder.append("打字广播：开始 ").append(c.getLongValue("typingStarts"))
                .append(" / 结束 ").append(c.getLongValue("typingStops")).append("\n");
        builder.append("回合抢占：").append(c.getLongValue("turnPreempted"))
                .append("，错峰回复：").append(c.getLongValue("staggeredReplies")).append("\n");
        builder.append("共享缓存：").append(stats.getIntValue("visionCacheSize"))
                .append(" 条，回合租约：").append(stats.getIntValue("turnCount"))
                .append("，任务租约：").append(stats.getIntValue("taskCount"))
                .append("，正在输入：").append(stats.getIntValue("typingCount")).append("\n");
        builder.append("广播 ").append(c.getLongValue("broadcasts"))
                .append(" 次，peer 上下线 ").append(c.getLongValue("peersJoined"))
                .append(" / ").append(c.getLongValue("peersLeft"));
        return builder.toString();
    }
}
