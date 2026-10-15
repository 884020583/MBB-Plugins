package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Command.CommandPermission;
import org.moboxlab.moboxbot.API.Command.CommandSender;
import org.moboxlab.moboxbot.API.Util.ImageUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Roleplay 命令处理器：RoleplayContentCommandHandler
 */
public class RoleplayContentCommandHandler {
    private final RoleplayCommand command;

    public RoleplayContentCommandHandler(RoleplayCommand command) {
        this.command = command;
    }

    void handleSpeech(CommandSender sender,String[] args) {
        RoleplaySpeechCorpusService speech = command.service().getSpeechCorpusService();
        String action = args.length > 2 ? args[2].toLowerCase() : "stats";
        if ("stats".equals(action)) {
            JSONObject stats = speech.stats();
            sender.sendMessage("台词语料："
                    +"\n启用："+(stats.getBooleanValue("enabled") ? "是" : "否")
                    +"\n条数："+stats.getIntValue("count")
                    +"\n标签数："+stats.getIntValue("tagCount")
                    +"\n文件："+stats.getString("path"));
            return;
        }
        if ("reload".equals(action)) {
            command.plugin().reloadRoleplay();
            sender.sendMessage("台词语料已重载，当前条数："+speech.count());
            return;
        }
        if ("on".equals(action) || "off".equals(action)) {
            boolean changed = command.plugin().setSpeechCorpusEnable("on".equals(action));
            sender.sendMessage(changed
                    ? "台词语料检索已"+("on".equals(action) ? "开启。" : "关闭。")
                    : "保存配置失败，请检查 config.yml 权限。");
            return;
        }
        if ("search".equals(action)) {
            if (args.length < 4) {
                sender.sendMessage("用法：/role speech search <文本>");
                return;
            }
            JSONArray results = speech.search(command.joinArgs(args,3),10);
            if (results.isEmpty()) {
                sender.sendMessage("没有检索到合适的台词示例。");
                return;
            }
            StringBuilder builder = new StringBuilder("台词检索结果：");
            for (int i = 0; i < results.size(); i++) {
                JSONObject item = results.getJSONObject(i);
                builder.append("\n#").append(i + 1)
                        .append(" ").append(item.getString("text"))
                        .append(" [").append(item.getString("emotion"))
                        .append("/").append(item.getString("scene")).append("]");
            }
            sender.sendMessage(builder.toString());
            return;
        }
        sender.sendMessage("用法：/role speech stats | reload | search <文本> | on | off");
    }

    void handleKnowledge(CommandSender sender,String[] args) {
        RoleplayKnowledgeService knowledge = command.service().getKnowledgeService();
        String action = args.length > 2 ? args[2].toLowerCase() : "list";
        if ("list".equals(action) || "stats".equals(action)) {
            JSONObject stats = knowledge.stats();
            StringBuilder builder = new StringBuilder("知识库："
                    +"\n启用："+(stats.getBooleanValue("enabled") ? "是" : "否")
                    +"\n库数："+stats.getIntValue("libraryCount")
                    +" 条目："+stats.getIntValue("entryCount")
                    +" 小节："+stats.getIntValue("sectionCount")
                    +"\n目录："+stats.getString("path"));
            JSONArray libraries = stats.getJSONArray("libraries");
            for (int i = 0; i < libraries.size(); i++) {
                JSONObject item = libraries.getJSONObject(i);
                builder.append("\n- ").append(item.getString("id"))
                        .append("（").append(item.getString("name")).append("）")
                        .append(item.getBooleanValue("enabled") ? "" : " [未启用]")
                        .append(" 条目=").append(item.getIntValue("entries"))
                        .append(" 小节=").append(item.getIntValue("sections"));
                String scope = item.getString("scope");
                if (scope != null && !scope.isEmpty()) builder.append("\n  ").append(scope);
            }
            if (libraries.isEmpty()) {
                builder.append("\n还没有知识库，按 KNOWLEDGE.md 把内容放进该目录。");
            }
            sender.sendMessage(builder.toString());
            return;
        }
        if ("reload".equals(action)) {
            command.plugin().reloadRoleplay();
            sender.sendMessage("知识库已重载，当前 "+knowledge.entryCount()+" 条目，"
                    +knowledge.sectionCount()+" 小节。");
            return;
        }
        if ("on".equals(action) || "off".equals(action)) {
            boolean changed = command.plugin().setKnowledgeEnable("on".equals(action));
            sender.sendMessage(changed
                    ? "知识库已"+("on".equals(action) ? "开启。" : "关闭。")
                    : "保存配置失败，请检查 config.yml 权限。");
            return;
        }
        if ("search".equals(action)) {
            if (args.length < 4) {
                sender.sendMessage("用法：/role kb search <文本>");
                return;
            }
            JSONArray results = knowledge.search(command.joinArgs(args,3),6);
            if (results.isEmpty()) {
                sender.sendMessage("没有检索到知识库内容，检查库名、别名和最低分设置。");
                return;
            }
            StringBuilder builder = new StringBuilder("知识库检索结果：");
            for (int i = 0; i < results.size(); i++) {
                JSONObject item = results.getJSONObject(i);
                builder.append("\n#").append(i + 1)
                        .append(" [").append(item.getString("library")).append("] ")
                        .append(item.getString("entry")).append(" · ")
                        .append(item.getString("section"))
                        .append(" 分数=").append(item.getDoubleValue("score"))
                        .append(item.getBooleanValue("locked") ? " 锁定" : "")
                        .append("\n  ").append(item.getString("text"));
            }
            sender.sendMessage(builder.toString());
            return;
        }
        sender.sendMessage("用法：/role kb list | reload | search <文本> | on | off");
    }
}