package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 角色原生工具注册表。
 */
public class RoleplayToolRegistry {
    private final RoleplayService service;
    private final RoleplayWorkspaceService workspace;
    private final Map<String,RoleplayTool> tools = new LinkedHashMap<>();

    public RoleplayToolRegistry(RoleplayService service,RoleplayConfig config) {
        this.service = service;
        this.workspace = new RoleplayWorkspaceService(service.plugin(),config);
        register(new SelfStateTool());
        register(new HistorySearchTool());
        register(new MemorySearchTool());
        register(new ProfileGetTool());
        register(new WorkspaceListTool());
        register(new WorkspaceReadTool());
        register(new WorkspaceWriteTool());
        register(new WorkspaceDeleteTool());
        register(new WorkspaceSearchTool());
        register(new EndTurnTool());
    }

    public void reload(RoleplayConfig config) {
        workspace.reload(config);
    }

    public RoleplayWorkspaceService workspace() {
        return workspace;
    }

    public JSONArray schemas(RoleplayConfig config) {
        JSONArray result = new JSONArray();
        if (!config.toolEnable) return result;
        for (RoleplayTool tool : tools.values()) {
            if (tool.name().startsWith("workspace_") && !config.workspaceEnable) continue;
            result.add(tool.schema());
        }
        return result;
    }

    public RoleplayTool get(String name) {
        return name == null ? null : tools.get(name.trim());
    }

    public RoleplayToolResult execute(RoleplayToolContext context,String name,JSONObject args) {
        RoleplayTool tool = get(name);
        if (tool == null) return RoleplayToolResult.failed("未知工具："+name);
        try {
            return tool.execute(context,args == null ? new JSONObject(true) : args);
        } catch (Exception e) {
            return RoleplayToolResult.failed(tool.name()+" 执行失败："+service.safe(e.getMessage()));
        }
    }

    private void register(RoleplayTool tool) {
        if (tool != null) tools.put(tool.name(),tool);
    }

    private abstract class BaseTool implements RoleplayTool {
        @Override
        public int maxCallsPerTurn() {
            return 4;
        }

        @Override
        public boolean sideEffect() {
            return false;
        }

        protected JSONObject functionSchema(String name,String description,JSONObject properties,
                                            String[] required) {
            JSONObject function = new JSONObject(true);
            function.put("name",name);
            function.put("description",description);
            JSONObject parameters = new JSONObject(true);
            parameters.put("type","object");
            parameters.put("properties",properties == null ? new JSONObject(true) : properties);
            JSONArray requiredArray = new JSONArray();
            if (required != null) {
                for (String item : required) {
                    if (item != null && !item.isEmpty()) requiredArray.add(item);
                }
            }
            parameters.put("required",requiredArray);
            parameters.put("additionalProperties",false);
            function.put("parameters",parameters);
            JSONObject schema = new JSONObject(true);
            schema.put("type","function");
            schema.put("function",function);
            return schema;
        }

        protected JSONObject property(String type,String description) {
            JSONObject property = new JSONObject(true);
            property.put("type",type);
            property.put("description",description);
            return property;
        }
    }

    private class SelfStateTool extends BaseTool {
        @Override
        public String name() { return "self_state"; }

        @Override
        public String description() { return "查看当前发送冷却、工作区、记忆和回复约束等真实运行状态"; }

        @Override
        public JSONObject schema() {
            return functionSchema(name(),description(),new JSONObject(true),new String[0]);
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            JSONObject data = new JSONObject(true);
            data.put("groupID",context.groupID);
            data.put("canReply",!service.rateLimited(context.groupID));
            data.put("workspaceEnabled",service.config().workspaceEnable);
            data.put("workspacePath","plugins/MBB-Roleplay/data");
            data.put("memoryCount",service.status(context.groupID).getIntValue("memoryCount"));
            data.put("shortSummary",service.shortText(service.shortSummary(context.groupID),600));
            data.put("replySegmentMaxChars",service.config().replySegmentMaxChars);
            data.put("replyMaxSegments",service.config().replyMaxSegments);
            RoleplayToolResult result = RoleplayToolResult.ok("已读取当前运行状态");
            result.data = data;
            return result;
        }
    }

    private class HistorySearchTool extends BaseTool {
        @Override
        public String name() { return "history_search"; }

        @Override
        public String description() { return "搜索当前群最近的聊天流水，返回匹配的消息、发言人和时间"; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("query",property("string","要搜索的原文片段"));
            properties.put("limit",property("integer","最多返回条数，1 到 20"));
            return functionSchema(name(),description(),properties,new String[]{"query"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            String query = service.safe(args.getString("query")).trim();
            if (query.isEmpty()) return RoleplayToolResult.failed("query 不能为空");
            int limit = args.getIntValue("limit");
            if (limit < 1) limit = 8;
            if (limit > 20) limit = 20;
            List<JSONObject> rows = service.storage().query(
                    "SELECT `userID`,`userName`,`content`,`messageTime` FROM `"+RoleplayService.MSG_TABLE+"` "
                            +"WHERE `groupID`=? AND `content` LIKE ? ORDER BY `messageTime` DESC,`ID` DESC LIMIT ?",
                    context.groupID,"%"+query+"%",limit);
            JSONArray messages = new JSONArray();
            if (rows != null) {
                for (JSONObject row : rows) {
                    JSONObject item = new JSONObject(true);
                    item.put("userID",row.getLongValue("userID"));
                    item.put("userName",service.safe(row.getString("userName")));
                    item.put("content",service.shortText(service.safe(row.getString("content")),500));
                    item.put("time",formatTime(row.getLongValue("messageTime")));
                    messages.add(item);
                }
            }
            RoleplayToolResult result = RoleplayToolResult.ok("找到 "+messages.size()+" 条历史消息");
            result.data.put("messages",messages);
            return result;
        }
    }

    private class MemorySearchTool extends BaseTool {
        @Override
        public String name() { return "memory_search"; }

        @Override
        public String description() { return "按当前问题检索本群长期记忆和全局永久记忆"; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("query",property("string","要查询的主题或关键词"));
            return functionSchema(name(),description(),properties,new String[]{"query"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            String query = service.safe(args.getString("query")).trim();
            if (query.isEmpty()) return RoleplayToolResult.failed("query 不能为空");
            RoleplayToolResult result = RoleplayToolResult.ok("已检索相关记忆");
            result.data.put("longTerm",service.shortText(
                    service.longMemoryText(context.groupID,context.userID,query),
                    service.config().toolResultMaxChars / 2));
            result.data.put("global",service.shortText(
                    service.globalMemoryService().promptText(query,context.userID,
                            Math.max(1000,service.config().toolResultMaxChars / 2)),
                    service.config().toolResultMaxChars / 2));
            return result;
        }
    }

    private class ProfileGetTool extends BaseTool {
        @Override
        public String name() { return "profile_get"; }

        @Override
        public String description() { return "查看角色对当前群员或指定 QQ 的个人印象"; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("userID",property("integer","目标 QQ；省略时查看当前发言者"));
            return functionSchema(name(),description(),properties,new String[0]);
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            long userID = args.getLongValue("userID");
            if (userID <= 0) userID = context.userID;
            JSONObject profile = service.getMemberService().profile(context.groupID,userID);
            RoleplayToolResult result = RoleplayToolResult.ok(profile == null ? "暂无该群员档案" : "已读取群员档案");
            result.data.put("profile",profile == null ? new JSONObject(true) : profile);
            return result;
        }
    }

    private class WorkspaceListTool extends BaseTool {
        @Override
        public String name() { return "workspace_list"; }

        @Override
        public String description() { return "列出角色私有工作区中的文件和文件夹"; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("path",property("string","相对 data/ 的目录，省略为根目录"));
            return functionSchema(name(),description(),properties,new String[0]);
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            try {
                RoleplayToolResult result = RoleplayToolResult.ok("已列出工作区");
                result.data = workspace.list(args.getString("path"));
                return result;
            } catch (Exception e) {
                return RoleplayToolResult.failed(e.getMessage());
            }
        }
    }

    private class WorkspaceReadTool extends BaseTool {
        @Override
        public String name() { return "workspace_read"; }

        @Override
        public String description() { return "读取角色私有工作区中的一个 UTF-8 文本文件"; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("path",property("string","相对 data/ 的文件路径"));
            return functionSchema(name(),description(),properties,new String[]{"path"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            try {
                RoleplayToolResult result = RoleplayToolResult.ok("已读取文件");
                result.data = workspace.read(args.getString("path"));
                return result;
            } catch (Exception e) {
                return RoleplayToolResult.failed(e.getMessage());
            }
        }
    }

    private class WorkspaceWriteTool extends BaseTool {
        @Override
        public String name() { return "workspace_write"; }

        @Override
        public String description() { return "创建或修改角色私有工作区中的 UTF-8 文本文件"; }

        @Override
        public boolean sideEffect() { return true; }

        @Override
        public int maxCallsPerTurn() { return 4; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("path",property("string","相对 data/ 的文件路径"));
            properties.put("content",property("string","UTF-8 文本内容"));
            properties.put("append",property("boolean","是否追加到文件末尾"));
            return functionSchema(name(),description(),properties,new String[]{"path","content"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            try {
                RoleplayToolResult result = RoleplayToolResult.ok("文件已写入");
                result.data = workspace.write(args.getString("path"),args.getString("content"),
                        args.getBooleanValue("append"));
                return result;
            } catch (Exception e) {
                return RoleplayToolResult.failed(e.getMessage());
            }
        }
    }

    private class WorkspaceDeleteTool extends BaseTool {
        @Override
        public String name() { return "workspace_delete"; }

        @Override
        public String description() { return "删除角色私有工作区中的文件；删除非空目录必须显式 recursive=true"; }

        @Override
        public boolean sideEffect() { return true; }

        @Override
        public int maxCallsPerTurn() { return 2; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("path",property("string","相对 data/ 的文件或目录路径"));
            properties.put("recursive",property("boolean","是否递归删除非空目录"));
            return functionSchema(name(),description(),properties,new String[]{"path"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            try {
                workspace.delete(args.getString("path"),args.getBooleanValue("recursive"));
                return RoleplayToolResult.ok("已删除 "+args.getString("path"));
            } catch (Exception e) {
                return RoleplayToolResult.failed(e.getMessage());
            }
        }
    }

    private class WorkspaceSearchTool extends BaseTool {
        @Override
        public String name() { return "workspace_search"; }

        @Override
        public String description() { return "在角色私有工作区中按普通文本搜索，返回文件路径和行号"; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("query",property("string","要搜索的文本"));
            properties.put("path",property("string","相对 data/ 的搜索目录，省略为根目录"));
            properties.put("maxResults",property("integer","最多返回结果数，1 到 100"));
            return functionSchema(name(),description(),properties,new String[]{"query"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            try {
                RoleplayToolResult result = RoleplayToolResult.ok("已完成工作区搜索");
                result.data = workspace.search(args.getString("path"),args.getString("query"),
                        args.getIntValue("maxResults"));
                return result;
            } catch (Exception e) {
                return RoleplayToolResult.failed(e.getMessage());
            }
        }
    }

    private class EndTurnTool extends BaseTool {
        @Override
        public String name() { return "end_turn"; }

        @Override
        public String description() { return "本轮不发送任何文字并立即结束；只适合确实无需回复或完成动作后保持沉默"; }

        @Override
        public int maxCallsPerTurn() { return 1; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("reason",property("string","结束本轮的简短原因"));
            return functionSchema(name(),description(),properties,new String[]{"reason"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            RoleplayToolResult result = RoleplayToolResult.ok("本轮已结束");
            result.endTurn = true;
            result.data.put("reason",service.shortText(service.safe(args.getString("reason")),200));
            return result;
        }
    }

    private String formatTime(long time) {
        if (time <= 0) return "";
        return new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date(time));
    }
}
