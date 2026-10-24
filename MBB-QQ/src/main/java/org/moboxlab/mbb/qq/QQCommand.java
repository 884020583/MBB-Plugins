package org.moboxlab.mbb.qq;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Command.BotCommand;
import org.moboxlab.moboxbot.API.Command.CommandPermission;
import org.moboxlab.moboxbot.API.Command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /qq 管理命令。
 */
public class QQCommand extends BotCommand {
    private final QQPlugin plugin;
    private final QQService service;

    public QQCommand(QQPlugin plugin,QQService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public List<String> prefix() {
        List<String> result = new ArrayList<>();
        result.add("qq");
        return result;
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
        return "查看 QQ 扩展动作与审计";
    }

    @Override
    public List<String> usage() {
        return Arrays.asList("/qq status","/qq audit [条数]","/qq qzone [条数]","/qq reload");
    }

    @Override
    public boolean execute(CommandSender sender,String[] args) {
        String action = args != null && args.length > 1 ? args[1].toLowerCase() : "status";
        if ("reload".equals(action)) {
            plugin.reloadQQ();
            sender.sendMessage("MBB-QQ 配置已重载。");
            return true;
        }
        if ("audit".equals(action)) {
            int limit = args != null && args.length > 2 ? parseLimit(args[2]) : 10;
            JSONObject params = new JSONObject(true);
            params.put("groupID",sender.getGroupID());
            params.put("limit",limit);
            sender.sendMessage(formatAudit(service.call("audit",params)));
            return true;
        }
        if ("qzone".equals(action)) {
            JSONObject params = new JSONObject(true);
            params.put("operatorID",sender.getUserID());
            params.put("limit",args != null && args.length > 2 ? parseLimit(args[2]) : 10);
            sender.sendMessage(formatQzone(service.call("qzone_posts",params)));
            return true;
        }
        QQConfig config = plugin.getQQConfig();
        sender.sendMessage("MBB-QQ：\n"
                +"只读工具："+(config.readToolsEnable ? "开" : "关")+"\n"
                +"互动工具："+(config.interactionToolsEnable ? "开" : "关")+"\n"
                +"媒体工具："+(config.mediaToolsEnable ? "开" : "关")+"\n"
                +"管理工具："+(config.adminToolsEnable ? "开" : "关")+"\n"
                +"owner 退群："+(config.groupLeaveEnable ? "开" : "关")+"\n"
                +"群事件通知 owner："+(config.notifyOwnerOnGroupEvent ? "开" : "关")+"\n"
                +"Qzone："+(config.qzoneEnable ? "开" : "关")+"\n"
                +"审计保留："+config.auditKeepDays+" 天");
        return true;
    }

    private String formatAudit(JSONObject result) {
        if (result == null || !result.getBooleanValue("status")) {
            return result == null ? "读取审计失败。" : result.getString("message");
        }
        JSONArray items = result.getJSONArray("items");
        if (items == null || items.isEmpty()) return "当前没有 QQ 操作审计。";
        StringBuilder builder = new StringBuilder("最近 QQ 操作：");
        for (Object object : items) {
            JSONObject item = (JSONObject)object;
            builder.append("\n#").append(item.getLongValue("id"))
                    .append(" ").append(item.getString("time"))
                    .append(" ").append(item.getString("action"))
                    .append(" 群=").append(item.getLongValue("groupID"))
                    .append(" 用户=").append(item.getLongValue("operatorID"))
                    .append(" 目标=").append(item.getLongValue("targetID"))
                    .append(" ").append(item.getBooleanValue("success") ? "成功" : "失败")
                    .append(" ").append(item.getString("summary"));
        }
        return builder.toString();
    }

    private String formatQzone(JSONObject result) {
        if (result == null || !result.getBooleanValue("status")) {
            return result == null ? "读取 Qzone 记录失败。" : result.getString("message");
        }
        JSONArray posts = result.getJSONArray("posts");
        if (posts == null || posts.isEmpty()) return "本机没有 Qzone 发布记录。";
        StringBuilder builder = new StringBuilder("最近 Qzone 发布：");
        for (Object object : posts) {
            JSONObject post = (JSONObject)object;
            builder.append("\n#").append(post.getLongValue("id"))
                    .append(" tid=").append(post.getString("tid"))
                    .append(" 权限=").append(post.getIntValue("ugcRight"))
                    .append(" ").append(post.getString("content"));
        }
        return builder.toString();
    }

    private int parseLimit(String value) {
        try {
            int limit = Integer.parseInt(value);
            return Math.max(1,Math.min(50,limit));
        } catch (Exception e) {
            return 10;
        }
    }
}
