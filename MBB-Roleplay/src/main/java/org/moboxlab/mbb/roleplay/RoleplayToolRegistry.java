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
    private final RoleplayHistorySearchService historySearch;
    private final Map<String,RoleplayTool> tools = new LinkedHashMap<>();

    public RoleplayToolRegistry(RoleplayService service,RoleplayConfig config) {
        this.service = service;
        this.workspace = new RoleplayWorkspaceService(service.plugin(),config);
        this.web = new RoleplayWebService(service);
        this.historySearch = new RoleplayHistorySearchService(service);
        register(new ToolSearchTool());
        register(new SelfStateTool());
        register(new HistorySearchTool());
        register(new MemorySearchTool());
        register(new KnowledgeSearchTool());
        register(new WebFetchTool());
        register(new WebSearchTool());
        register(new ProfileGetTool());
        register(new WorkspaceListTool());
        register(new WorkspaceReadTool());
        register(new WorkspaceWriteTool());
        register(new WorkspaceDeleteTool());
        register(new WorkspaceSearchTool());
        register(new WorkspaceRenameTool());
        register(new WorkspaceCopyTool());
        register(new WorkspaceRestoreTool());
        register(new MessageSendTool());
        register(new EndTurnTool());
        registerSkill("reminder");
        registerSkill("memory");
        registerSkill("global-memory");
        registerSkill("sticker");
        registerSkill("poke-back");
        registerSkill("draw");
        RoleplayQQTools.register(this,service);
    }

    public void reload(RoleplayConfig config) {
        workspace.reload(config);
    }

    public RoleplayWorkspaceService workspace() {
        return workspace;
    }

    public JSONArray schemas(RoleplayConfig config) {
        return schemas(config,null);
    }

    public JSONArray schemas(RoleplayConfig config,List<String> names) {
        JSONArray result = new JSONArray();
        if (!config.toolEnable) return result;
        for (RoleplayTool tool : tools.values()) {
            if (!enabled(tool,config)) continue;
            if (names != null && !names.contains(tool.name())) continue;
            result.add(tool.schema());
        }
        return result;
    }

    public List<String> coreToolNames(RoleplayConfig config) {
        List<String> result = new java.util.ArrayList<>();
        if (config == null) return result;
        for (String item : service.safe(config.toolCoreTools).split(",")) {
            String name = item == null ? "" : item.trim();
            RoleplayTool tool = get(name);
            if (!name.isEmpty() && tool != null && enabled(tool,config) && !result.contains(name)) {
                result.add(name);
            }
        }
        RoleplayTool search = get("tool_search");
        if (search != null && enabled(search,config) && !result.contains("tool_search")) {
            result.add(0,"tool_search");
        }
        return result;
    }

    public List<RoleplayTool> search(RoleplayConfig config,String query,int limit) {
        String text = service.safe(query).toLowerCase(Locale.ROOT).trim();
        String queryBigrams = text;
        List<ScoredTool> candidates = new java.util.ArrayList<>();
        for (RoleplayTool tool : tools.values()) {
            if (!enabled(tool,config) || "tool_search".equals(tool.name())) continue;
            String haystack = (tool.name()+" "+tool.category()+" "
                    +tool.description()).toLowerCase(Locale.ROOT);
            double score = 0;
            if (!text.isEmpty() && (haystack.contains(text) || text.contains(tool.name()))) {
                score += 8;
            }
            String normalized = SpeechCorpusEntry.normalize(haystack);
            java.util.Set<String> querySet = SpeechCorpusEntry.bigrams(
                    SpeechCorpusEntry.normalize(queryBigrams));
            if (!querySet.isEmpty()) {
                java.util.Set<String> toolSet = SpeechCorpusEntry.bigrams(normalized);
                int overlap = 0;
                for (String bigram : querySet) if (toolSet.contains(bigram)) overlap++;
                score += overlap * 2.0 / Math.max(1,querySet.size());
            }
            if (score > 0) candidates.add(new ScoredTool(tool,score));
        }
        candidates.sort((left,right) -> Double.compare(right.score,left.score));
        if (candidates.isEmpty()) {
            for (RoleplayTool tool : tools.values()) {
                if (enabled(tool,config) && !"tool_search".equals(tool.name())) {
                    candidates.add(new ScoredTool(tool,0));
                }
            }
        }
        List<RoleplayTool> result = new java.util.ArrayList<>();
        for (ScoredTool item : candidates) {
            if (result.size() >= Math.max(1,limit)) break;
            result.add(item.tool);
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

    public String categoryCatalogue(RoleplayConfig config) {
        Map<String,Integer> counts = new LinkedHashMap<>();
        for (RoleplayTool tool : tools.values()) {
            if (!enabled(tool,config)) continue;
            String category = tool.category() == null ? "general" : tool.category();
            Integer count = counts.get(category);
            counts.put(category,count == null ? 1 : count + 1);
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String,Integer> entry : counts.entrySet()) {
            builder.append("- ").append(entry.getKey())
                    .append("：").append(entry.getValue()).append(" 个\n");
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

    void register(RoleplayTool tool) {
        if (tool != null) tools.put(tool.name(),tool);
    }

    private void registerSkill(String id) {
        RoleplaySkillTool tool = RoleplaySkillTool.create(service,id);
        if (tool != null) register(tool);
    }

    private boolean enabled(RoleplayTool tool,RoleplayConfig config) {
        if (tool == null || config == null || !config.toolEnable) return false;
        if (tool.name().startsWith("workspace_") && !config.workspaceEnable) return false;
        if (("web_fetch".equals(tool.name()) || "web_search".equals(tool.name()))
                && !config.webEnable) return false;
        if ("kb_search".equals(tool.name()) && !config.knowledgeEnable) return false;
        if ("reminder".equals(tool.name()) && !config.reminderEnable) return false;
        if ("memory".equals(tool.name()) && !config.activeMemory) return false;
        if ("global-memory".equals(tool.name()) && !config.globalMemoryEnable) return false;
        if ("poke-back".equals(tool.name()) && !config.pokeBackEnable) return false;
        if ("draw".equals(tool.name()) && !hasService("MBB-ComfyUI")) {
            return false;
        }
        if ("sticker".equals(tool.name()) && !hasService("MBB-Sticker")) {
            return false;
        }
        return true;
    }

    private boolean hasService(String name) {
        return service.plugin().getServer() != null
                && service.plugin().getServer().getPluginManager() != null
                && service.plugin().getServer().getPluginManager().getService(name) != null;
    }

    private abstract class BaseTool implements RoleplayTool {
        @Override
        public String category() {
            return "general";
        }

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

    private class ToolSearchTool extends BaseTool {
        @Override
        public String name() { return "tool_search"; }

        @Override
        public String category() { return "core"; }

        @Override
        public String description() {
            return "按当前任务搜索并加载可用工具；返回工具名、分类和用途，下一轮即可调用";
        }

        @Override
        public int maxCallsPerTurn() { return 3; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("query",property("string","要查找的能力、对象或动作"));
            properties.put("limit",property("integer","最多返回几个工具，1 到 10"));
            return functionSchema(name(),description(),properties,new String[]{"query"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            String query = service.safe(args.getString("query")).trim();
            if (query.isEmpty()) return RoleplayToolResult.failed("query 不能为空");
            int limit = args.getIntValue("limit");
            if (limit <= 0) limit = service.config().toolSearchMaxResults;
            limit = Math.max(1,Math.min(10,limit));
            List<RoleplayTool> selected = search(service.config(),query,limit);
            JSONArray tools = new JSONArray();
            for (RoleplayTool tool : selected) {
                JSONObject item = new JSONObject(true);
                item.put("name",tool.name());
                item.put("category",tool.category());
                item.put("description",tool.description());
                RoleplaySkill skill = service.skillRegistry().get(tool.name());
                if (skill != null) {
                    RoleplaySkillContext skillContext = new RoleplaySkillContext();
                    skillContext.groupID = context.groupID;
                    skillContext.userID = context.userID;
                    skillContext.messageID = context.messageID;
                    skillContext.selfID = context.selfID;
                    skillContext.userName = context.userName;
                    skillContext.relationship = context.relationship;
                    skillContext.message = context.message;
                    skillContext.state = service.state(context.groupID);
                    String instruction = service.safe(skill.promptFragment(skillContext));
                    if (!instruction.isEmpty()) {
                        item.put("instruction",service.shortText(instruction,2400));
                    }
                }
                tools.add(item);
            }
            RoleplayToolResult result = RoleplayToolResult.ok(
                    "已加载 "+tools.size()+" 个工具，下一步可直接调用");
            result.data.put("query",query);
            result.data.put("tools",tools);
            for (RoleplayTool tool : selected) result.activateTools.add(tool.name());
            return result;
        }
    }

    private class SelfStateTool extends BaseTool {
        @Override
        public String name() { return "self_state"; }

        @Override
        public String category() { return "core"; }

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
        public String category() { return "retrieval"; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("query",property("string","要搜索的原文片段"));
            properties.put("limit",property("integer","最多返回条数，1 到 20"));
            properties.put("days",property("integer","向前搜索多少天，默认 30，最大 3650"));
            return functionSchema(name(),description(),properties,new String[]{"query"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            String query = service.safe(args.getString("query")).trim();
            if (query.isEmpty()) return RoleplayToolResult.failed("query 不能为空");
            int limit = args.getIntValue("limit");
            JSONObject found = historySearch.search(context.groupID,query,limit,
                    args.getIntValue("days"));
            JSONArray messages = found.getJSONArray("messages");
            RoleplayToolResult result = RoleplayToolResult.ok("找到 "+(messages == null ? 0 : messages.size())
                    +" 条历史消息");
            result.data.putAll(found);
            return result;
        }
    }

    private class MemorySearchTool extends BaseTool {
        @Override
        public String name() { return "memory_search"; }

        @Override
        public String description() { return "按当前问题检索本群长期记忆和全局永久记忆"; }

        @Override
        public String category() { return "retrieval"; }

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
        public String category() { return "retrieval"; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("query",property("string","要查询的角色、社团、学院、剧情或设定问题"));
            properties.put("library",property("string","可选，只查指定知识库 ID"));
            properties.put("limit",property("integer","最多返回几个条目，1 到 20"));
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
            int limit = args.getIntValue("limit");
            if (limit <= 0) limit = 6;
            JSONArray results = service.knowledgeService().toolResults(query,requested,limit,
                    Math.max(2000,service.config().toolResultMaxChars));
            RoleplayToolResult result = RoleplayToolResult.ok(results.isEmpty()
                    ? "知识库未命中相关条目" : "知识库命中 "+results.size()+" 个条目");
            result.trust = "external-untrusted";
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
        public String category() { return "web"; }

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
                boolean internal = web.isInternal(url);
                boolean ownerAllowed = !internal || context.ownerAuthorizedInternalNetwork
                        || service.ownerAuthorizedInternalWeb(
                        context.groupID,context.userID,url);
                JSONObject page = web.fetch(url,ownerAllowed);
                String text = service.safe(page.getString("text")).trim();
                if (text.isEmpty()) return RoleplayToolResult.failed("网页没有可读取的正文");
                RoleplayToolResult result = RoleplayToolResult.untrusted("已读取网页："+url);
                result.data = page;
                result.data.put("internal",internal);
                result.data.put("ownerAuthorized",ownerAllowed);
                return result;
            } catch (Exception e) {
                return RoleplayToolResult.failed("网页读取失败："+service.safe(e.getMessage()));
            }
        }
    }

    private class WebSearchTool extends BaseTool {
        @Override
        public String name() { return "web_search"; }

        @Override
        public String category() { return "web"; }

        @Override
        public String description() {
            return "联网搜索公开信息，返回标题和链接；优先使用官方英文名搜索，需要正文时再调用 web_fetch";
        }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("query",property("string",
                    "搜索词；作品、人物或站点优先使用官方英文名和常用英文别名"));
            return functionSchema(name(),description(),properties,new String[]{"query"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            String query = service.safe(args.getString("query")).trim();
            if (query.isEmpty()) return RoleplayToolResult.failed("query 不能为空");
            try {
                JSONObject payload = web.searchPayload(query,false);
                JSONArray results = payload.getJSONArray("results");
                boolean anyProviderResponded = providerResponded(payload.getJSONArray("attempts"));
                RoleplayToolResult result;
                if (results.isEmpty() && !anyProviderResponded) {
                    result = RoleplayToolResult.failed("搜索源连接失败或被风控页拦截");
                    result.trust = "external-untrusted";
                } else {
                    result = RoleplayToolResult.untrusted(
                            results.isEmpty() ? "联网搜索没有返回可解析结果"
                                    : "联网搜索返回 "+results.size()+" 条结果");
                }
                result.data.putAll(payload);
                if (results.isEmpty()) {
                    result.data.put("retryHint",
                            "换用作品、人物或站点的官方英文名/英文别名后重试一次，不要重复原查询");
                }
                return result;
            } catch (Exception e) {
                return RoleplayToolResult.failed("联网搜索失败："+service.safe(e.getMessage()));
            }
        }

        private boolean providerResponded(JSONArray attempts) {
            if (attempts == null) return false;
            for (Object object : attempts) {
                if (object instanceof JSONObject
                        && ((JSONObject)object).getBooleanValue("status")) return true;
            }
            return false;
        }
    }

    private class ProfileGetTool extends BaseTool {
        @Override
        public String name() { return "profile_get"; }

        @Override
        public String description() { return "查看角色对当前群员或指定 QQ 的个人印象"; }

        @Override
        public String category() { return "retrieval"; }

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
        public String category() { return "workspace"; }

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
        public String category() { return "workspace"; }

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
        public String category() { return "workspace"; }

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
        public String description() { return "把文件或目录移入工作区回收站；默认不永久删除"; }

        @Override
        public String category() { return "workspace"; }

        @Override
        public boolean sideEffect() { return true; }

        @Override
        public int maxCallsPerTurn() { return 2; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("path",property("string","相对 data/ 的文件或目录路径"));
            properties.put("recursive",property("boolean","是否递归删除非空目录"));
            properties.put("permanent",property("boolean","是否永久删除，默认 false 进入回收站"));
            return functionSchema(name(),description(),properties,new String[]{"path"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            try {
                JSONObject deleted = workspace.delete(args.getString("path"),
                        args.getBooleanValue("recursive"),args.getBooleanValue("permanent"));
                RoleplayToolResult result = RoleplayToolResult.ok(
                        deleted.getBooleanValue("permanent") ? "已永久删除" : "已移入回收站");
                result.data = deleted;
                return result;
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
        public String category() { return "workspace"; }

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

    private class WorkspaceRenameTool extends BaseTool {
        @Override
        public String name() { return "workspace_rename"; }

        @Override
        public String category() { return "workspace"; }

        @Override
        public String description() { return "重命名或移动角色私有工作区中的文件或目录"; }

        @Override
        public boolean sideEffect() { return true; }

        @Override
        public int maxCallsPerTurn() { return 3; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("source",property("string","源路径"));
            properties.put("target",property("string","目标路径"));
            properties.put("overwrite",property("boolean","目标存在时是否覆盖"));
            return functionSchema(name(),description(),properties,new String[]{"source","target"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            try {
                RoleplayToolResult result = RoleplayToolResult.ok("已重命名工作区路径");
                result.data = workspace.rename(args.getString("source"),args.getString("target"),
                        args.getBooleanValue("overwrite"));
                return result;
            } catch (Exception e) {
                return RoleplayToolResult.failed(e.getMessage());
            }
        }
    }

    private class WorkspaceCopyTool extends BaseTool {
        @Override
        public String name() { return "workspace_copy"; }

        @Override
        public String category() { return "workspace"; }

        @Override
        public String description() { return "复制角色私有工作区中的文件或目录"; }

        @Override
        public boolean sideEffect() { return true; }

        @Override
        public int maxCallsPerTurn() { return 2; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("source",property("string","源路径"));
            properties.put("target",property("string","目标路径"));
            properties.put("overwrite",property("boolean","目标存在时是否覆盖"));
            return functionSchema(name(),description(),properties,new String[]{"source","target"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            try {
                RoleplayToolResult result = RoleplayToolResult.ok("已复制工作区路径");
                result.data = workspace.copy(args.getString("source"),args.getString("target"),
                        args.getBooleanValue("overwrite"));
                return result;
            } catch (Exception e) {
                return RoleplayToolResult.failed(e.getMessage());
            }
        }
    }

    private class WorkspaceRestoreTool extends BaseTool {
        @Override
        public String name() { return "workspace_restore"; }

        @Override
        public String category() { return "workspace"; }

        @Override
        public String description() { return "从工作区回收站恢复文件或目录"; }

        @Override
        public boolean sideEffect() { return true; }

        @Override
        public int maxCallsPerTurn() { return 2; }

        @Override
        public JSONObject schema() {
            JSONObject properties = new JSONObject(true);
            properties.put("trashPath",property("string","workspace_delete 返回的 trashPath"));
            properties.put("target",property("string","恢复后的相对路径，省略时使用原文件名"));
            properties.put("overwrite",property("boolean","目标存在时是否覆盖"));
            return functionSchema(name(),description(),properties,new String[]{"trashPath"});
        }

        @Override
        public RoleplayToolResult execute(RoleplayToolContext context,JSONObject args) {
            try {
                RoleplayToolResult result = RoleplayToolResult.ok("已从回收站恢复");
                result.data = workspace.restore(args.getString("trashPath"),
                        args.getString("target"),args.getBooleanValue("overwrite"));
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
            return "在执行检索前追加一句简短回应；拿到工具结果后不要再用它说结论，最终结论必须走普通回复";
        }

        @Override
        public String category() { return "core"; }

        @Override
        public boolean sideEffect() { return true; }

        @Override
        public int maxCallsPerTurn() { return 1; }

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
        public String category() { return "core"; }

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

    private static class ScoredTool {
        private final RoleplayTool tool;
        private final double score;

        private ScoredTool(RoleplayTool tool,double score) {
            this.tool = tool;
            this.score = score;
        }
    }
}
