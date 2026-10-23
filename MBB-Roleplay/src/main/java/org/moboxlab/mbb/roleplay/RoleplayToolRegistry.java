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
    private final RoleplayWebService web;
    private final Map<String,RoleplayTool> tools = new LinkedHashMap<>();

    public RoleplayToolRegistry(RoleplayService service,RoleplayConfig config) {
        this.service = service;
        this.workspace = new RoleplayWorkspaceService(service.plugin(),config);
        this.web = new RoleplayWebService(service);
        register(new SelfStateTool());
        register(new HistorySearchTool());
        register(new MemorySearchTool());
        register(new KnowledgeSearchTool());
        register(new WebFetchTool());
        register(new ProfileGetTool());
        register(new WorkspaceListTool());
        register(new WorkspaceReadTool());
        register(new WorkspaceWriteTool());
        register(new WorkspaceDeleteTool());
        register(new WorkspaceSearchTool());
        register(new MessageSendTool());
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
            if (!enabled(tool,config)) continue;
            result.add(tool.schema());
        }
        return result;
    }

    public String catalogueText(RoleplayConfig config) {
        StringBuilder builder = new StringBuilder();
        for (RoleplayTool tool : tools.values()) {
            if (!enabled(tool,config)) continue;
            builder.append("- ").append(tool.name()).append("：")
                    .append(tool.description()).append("\n");
        }
        return builder.toString();
    }

    public JSONObject selfTest(RoleplayConfig config) {
        JSONArray issues = new JSONArray();
        int checked = 0;
        for (RoleplayTool tool : tools.values()) {
            if (!enabled(tool,config)) continue;
            checked++;
            JSONObject schema = tool.schema();
            JSONObject function = schema == null ? null : schema.getJSONObject("function");
            JSONObject parameters = function == null ? null : function.getJSONObject("parameters");
            String name = function == null ? "" : function.getString("name");
            if (!"function".equals(schema == null ? "" : schema.getString("type"))) {
                issues.add(tool.name()+" schema type 错误");
            }
            if (!tool.name().equals(name)) {
                issues.add(tool.name()+" schema 名称不一致");
            }
            if (parameters == null || !"object".equals(parameters.getString("type"))) {
                issues.add(tool.name()+" parameters 不是 object");
                continue;
            }
            JSONObject properties = parameters.getJSONObject("properties");
            JSONArray required = parameters.getJSONArray("required");
            if (required != null && !required.isEmpty()) {
                String missing = validateArguments(tool.name(),new JSONObject(true));
                if (!missing.startsWith("缺少参数：")) {
                    issues.add(tool.name()+" 未拒绝缺失必填参数");
                }
            }
            JSONObject extra = new JSONObject(true);
            if (required != null && properties != null) {
                for (Object item : required) {
                    String key = item == null ? "" : String.valueOf(item);
                    JSONObject property = key.isEmpty() ? null : properties.getJSONObject(key);
                    extra.put(key,sampleValue(property == null ? "" : property.getString("type")));
                }
            }
            extra.put("__unexpected_field__",true);
            String extraResult = validateArguments(tool.name(),extra);
            if (!extraResult.startsWith("不允许的参数：")) {
                issues.add(tool.name()+" 未拒绝未知参数");
            }
        }
        JSONObject workspaceTest = config.workspaceEnable ? workspace.selfTest() : new JSONObject(true);
        JSONObject webTest = config.webEnable ? web.selfTest() : new JSONObject(true);
        if (!workspaceTest.isEmpty() && !workspaceTest.getBooleanValue("status")) {
            issues.add("工作区路径隔离自检失败");
        }
        if (!webTest.isEmpty() && !webTest.getBooleanValue("status")) {
            issues.add("网页白名单自检失败");
        }
        JSONObject result = new JSONObject(true);
        result.put("status",issues.isEmpty());
        result.put("checked",checked);
        result.put("issues",issues);
        result.put("workspace",workspaceTest);
        result.put("web",webTest);
        return result;
    }

    private Object sampleValue(String type) {
        if ("integer".equalsIgnoreCase(type) || "number".equalsIgnoreCase(type)) return Long.valueOf(1L);
        if ("boolean".equalsIgnoreCase(type)) return Boolean.TRUE;
        if ("array".equalsIgnoreCase(type)) return new JSONArray();
        if ("object".equalsIgnoreCase(type)) return new JSONObject(true);
        return "";
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

    public String validateArguments(String name,JSONObject args) {
        RoleplayTool tool = get(name);
        if (tool == null) return "未知工具："+name;
        JSONObject schema = tool.schema();
        JSONObject function = schema == null ? null : schema.getJSONObject("function");
        JSONObject parameters = function == null ? null : function.getJSONObject("parameters");
        if (parameters == null) return "";
        JSONObject value = args == null ? new JSONObject(true) : args;
        JSONArray required = parameters.getJSONArray("required");
        if (required != null) {
            for (Object item : required) {
                String key = item == null ? "" : String.valueOf(item);
                if (key.isEmpty() || !value.containsKey(key)) return "缺少参数："+key;
            }
        }
        JSONObject properties = parameters.getJSONObject("properties");
        if (properties != null) {
            for (String key : value.keySet()) {
                JSONObject property = properties.getJSONObject(key);
                if (property == null) {
                    if (Boolean.FALSE.equals(parameters.getBoolean("additionalProperties"))) {
                        return "不允许的参数："+key;
                    }
                    continue;
                }
                String expected = property.getString("type");
                Object actual = value.get(key);
                if (actual == null || acceptType(expected,actual)) continue;
                return "参数 "+key+" 类型应为 "+expected;
            }
        }
        return "";
    }

    private boolean acceptType(String expected,Object value) {
        if (expected == null || expected.isEmpty()) return true;
        if ("string".equalsIgnoreCase(expected)) return value instanceof String;
        if ("integer".equalsIgnoreCase(expected)) {
            return value instanceof Integer || value instanceof Long
                    || value instanceof Short || value instanceof Byte;
        }
        if ("number".equalsIgnoreCase(expected)) {
            return value instanceof Number;
        }
        if ("boolean".equalsIgnoreCase(expected)) return value instanceof Boolean;
        if ("array".equalsIgnoreCase(expected)) return value instanceof JSONArray;
        if ("object".equalsIgnoreCase(expected)) return value instanceof JSONObject;
        return true;
    }

    private void register(RoleplayTool tool) {
        if (tool != null) tools.put(tool.name(),tool);
    }

    private boolean enabled(RoleplayTool tool,RoleplayConfig config) {
        if (tool == null || config == null || !config.toolEnable) return false;
        if (tool.name().startsWith("workspace_") && !config.workspaceEnable) return false;
        if ("web_fetch".equals(tool.name()) && !config.webEnable) return false;
        if ("kb_search".equals(tool.name()) && !config.knowledgeEnable) return false;
        return true;
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

    private class KnowledgeSearchTool extends BaseTool {
        @Override
        public String name() { return "kb_search"; }

        @Override
        public String description() { return "检索已安装的知识库，返回条目、小节和可引用的正文"; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("query",property("string","要查询的角色、社团、学院、剧情或设定问题"));
            properties.put("library",property("string","可选，只查指定知识库 ID"));
            return functionSchema(name(),description(),properties,new String[]{"query"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            String query = service.safe(args.getString("query")).trim();
            if (query.isEmpty()) return RoleplayToolResult.failed("query 不能为空");
            String requested = service.safe(args.getString("library")).trim();
            List<RoleplayKnowledgeLibrary> libraries = service.knowledgeService().enabledLibraries();
            if (libraries.isEmpty()) return RoleplayToolResult.failed("当前没有启用的知识库");
            if (!requested.isEmpty() && !hasLibrary(libraries,requested)) {
                return RoleplayToolResult.failed("知识库不存在或未启用："+requested);
            }
            int remaining = Math.max(1000,service.config().toolResultMaxChars);
            JSONArray results = new JSONArray();
            int hits = 0;
            for (RoleplayKnowledgeLibrary library : libraries) {
                if (!requested.isEmpty() && !requested.equals(library.id)) continue;
                String text = service.knowledgeService().injectText(library.id,query);
                if (text == null || text.trim().isEmpty()) continue;
                String limited = service.shortText(text,Math.min(remaining,library.maxInjectChars));
                if (limited.isEmpty()) break;
                JSONObject item = new JSONObject(true);
                item.put("library",library.id);
                item.put("name",library.name);
                item.put("text",limited);
                results.add(item);
                hits++;
                remaining -= limited.length();
                if (remaining <= 500) break;
            }
            RoleplayToolResult result = RoleplayToolResult.ok(hits == 0
                    ? "知识库未命中相关条目" : "知识库命中 "+hits+" 个库");
            result.data.put("query",query);
            result.data.put("results",results);
            return result;
        }

        private boolean hasLibrary(List<RoleplayKnowledgeLibrary> libraries,String id) {
            for (RoleplayKnowledgeLibrary library : libraries) {
                if (library != null && id.equals(library.id)) return true;
            }
            return false;
        }
    }

    private class WebFetchTool extends BaseTool {
        @Override
        public String name() { return "web_fetch"; }

        @Override
        public String description() { return "读取白名单网站中的公开网页并返回净化后的正文"; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("url",property("string","要读取的 http/https 网页地址"));
            return functionSchema(name(),description(),properties,new String[]{"url"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            String url = service.safe(args.getString("url")).trim();
            if (url.isEmpty()) return RoleplayToolResult.failed("url 不能为空");
            try {
                JSONObject page = web.fetch(url);
                String text = service.safe(page.getString("text")).trim();
                if (text.isEmpty()) return RoleplayToolResult.failed("网页没有可读取的正文");
                RoleplayToolResult result = RoleplayToolResult.ok("已读取网页："+url);
                result.data = page;
                return result;
            } catch (Exception e) {
                return RoleplayToolResult.failed("网页读取失败："+service.safe(e.getMessage()));
            }
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

    private class MessageSendTool extends BaseTool {
        @Override
        public String name() { return "message_send"; }

        @Override
        public String description() {
            return "在当前群追加发送一条短消息；适合在查资料或执行工具前先回应一句，不要用它重复最终回复";
        }

        @Override
        public boolean sideEffect() { return true; }

        @Override
        public int maxCallsPerTurn() { return 2; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("text",property("string","要发送的聊天正文，只发一条短消息"));
            properties.put("quote",property("boolean","是否引用当前触发消息"));
            return functionSchema(name(),description(),properties,new String[]{"text"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            if (context == null || context.groupID <= 0) {
                return RoleplayToolResult.failed("当前会话不能主动发送群消息");
            }
            String text = service.safe(args.getString("text"))
                    .replace("\r"," ").replace("\n"," ").replaceAll("\\s+"," ").trim();
            if (text.isEmpty()) return RoleplayToolResult.failed("text 不能为空");
            if (text.length() > Math.max(60,service.config().replySegmentMaxChars)) {
                return RoleplayToolResult.failed("主动消息过长，最多 "
                        +Math.max(60,service.config().replySegmentMaxChars)+" 个字符");
            }
            boolean sent = service.messageSender().sendSingleMessage(
                    service.plugin().getServer().getOneBotClient(),context.groupID,context.selfID,
                    context.userID,text,args.getBooleanValue("quote"),context.messageID);
            if (!sent) return RoleplayToolResult.failed("消息发送失败");
            RoleplayToolResult result = RoleplayToolResult.ok("已追加发送一条消息");
            result.data.put("text",text);
            return result;
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
