package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Event.GroupMessageEvent;
import org.moboxlab.moboxbot.API.PluginService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 原生工具循环与工具执行期间的消息收件箱。
 */
public class RoleplayToolLoopService {
    private final RoleplayService service;
    private final RoleplayToolRegistry registry;
    private final Map<Long,ActiveTurn> activeTurns = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public RoleplayToolLoopService(RoleplayService service,RoleplayConfig config) {
        this.service = service;
        this.registry = new RoleplayToolRegistry(service,config);
    }

    public void reload(RoleplayConfig config) {
        registry.reload(config);
    }

    public RoleplayToolRegistry registry() {
        return registry;
    }

    public JSONObject run(long groupID,RoleplayToolContext context,JSONArray messages,JSONObject params) {
        String token = begin(groupID);
        JSONArray toolTrace = new JSONArray();
        JSONObject usage = emptyUsage();
        int totalCalls = 0;
        int steps = Math.max(1,service.config().toolMaxSteps);
        try {
            for (int step = 0; step < steps; step++) {
                appendLiveMessages(groupID,token,messages,toolTrace);
                JSONObject callParams = copy(params);
                callParams.put("messages",messages);
                if (service.config().toolEnable) {
                    JSONArray schemas = registry.schemas(service.config());
                    if (!schemas.isEmpty()) {
                        callParams.put("tools",schemas);
                        callParams.put("tool_choice","auto");
                        callParams.put("parallel_tool_calls",false);
                    }
                }
                long start = System.currentTimeMillis();
                PluginService ai = service.plugin().getServer().getPluginManager().getService("MBB-AI");
                if (ai == null) return error("MBB-AI 未启用");
                JSONObject response = ai.call("chat",callParams);
                RoleplayAiLog.log(service.plugin().getLogger(),"工具循环",groupID,response,
                        System.currentTimeMillis() - start);
                if (response == null || !response.getBooleanValue("status")) {
                    if (step == 0 && callParams.getJSONArray("tools") != null && recoverWithoutTools(response)) {
                        callParams.remove("tools");
                        callParams.remove("tool_choice");
                        callParams.remove("parallel_tool_calls");
                        response = ai.call("chat",callParams);
                    }
                    if (response == null || !response.getBooleanValue("status")) {
                        return response == null ? error("AI 服务没有返回结果") : response;
                    }
                }
                addUsage(usage,response.getJSONObject("usage"));
                JSONArray calls = response.getJSONArray("toolCalls");
                if (calls == null || calls.isEmpty()) {
                    response.put("usage",usage);
                    response.put("toolTrace",toolTrace);
                    return response;
                }
                JSONObject assistant = new JSONObject(true);
                assistant.put("role","assistant");
                assistant.put("content",service.safe(response.getString("content")));
                assistant.put("tool_calls",calls);
                messages.add(assistant);
                boolean endTurn = false;
                for (Object object : calls) {
                    if (!(object instanceof JSONObject)) continue;
                    if (totalCalls >= Math.max(1,service.config().toolMaxCalls)) {
                        return error("工具调用次数超过单轮上限");
                    }
                    JSONObject call = (JSONObject)object;
                    String id = service.safe(call.getString("id"));
                    JSONObject function = call.getJSONObject("function");
                    String name = function == null ? "" : service.safe(function.getString("name"));
                    JSONObject args = parseArgs(function == null ? "" : function.getString("arguments"));
                    long startTool = System.currentTimeMillis();
                    RoleplayToolResult toolResult = registry.execute(context,name,args);
                    totalCalls++;
                    JSONObject trace = new JSONObject(true);
                    trace.put("id",id);
                    trace.put("name",name);
                    trace.put("arguments",service.shortText(redact(args.toJSONString()),800));
                    trace.put("status",toolResult.success);
                    trace.put("summary",service.shortText(toolResult.summary,500));
                    trace.put("elapsedMs",System.currentTimeMillis() - startTool);
                    toolTrace.add(trace);
                    service.plugin().getLogger().sendInfo("[工具] 群"+context.groupID
                            +" 第"+(step+1)+"轮调用 "+name
                            +" 结果="+(toolResult.success ? "成功" : "失败")
                            +" 摘要="+service.shortText(toolResult.summary,160));
                    JSONObject toolMessage = new JSONObject(true);
                    toolMessage.put("role","tool");
                    toolMessage.put("tool_call_id",id);
                    toolMessage.put("name",name);
                    toolMessage.put("content",service.shortText(toolResult.toJson().toJSONString(),
                            Math.max(500,service.config().toolResultMaxChars)));
                    messages.add(toolMessage);
                    if (toolResult.endTurn) {
                        endTurn = true;
                        break;
                    }
                }
                appendLiveMessages(groupID,token,messages,toolTrace);
                if (endTurn) {
                    service.plugin().getLogger().sendInfo("[工具] 群"+context.groupID
                            +" 模型调用 end_turn，本轮保持沉默");
                    JSONObject result = new JSONObject(true);
                    result.put("status",true);
                    result.put("content","<SKIP>");
                    result.put("finishReason","end_turn");
                    result.put("usage",usage);
                    result.put("toolTrace",toolTrace);
                    return result;
                }
            }
            JSONObject result = error("工具循环达到最大轮数，未得到最终回复");
            result.put("toolTrace",toolTrace);
            return result;
        } finally {
            end(groupID,token);
        }
    }

    /**
     * 工具执行期间的新消息直接进入当前轮上下文，并跳过后续批次重复处理。
     */
    public boolean captureLiveMessage(GroupMessageEvent event) {
        if (event == null || event.getGroupID() <= 0) return false;
        ActiveTurn active = activeTurns.get(event.getGroupID());
        if (active == null) return false;
        long selfID = event.getRaw().getLongValue("self_id");
        if (selfID > 0 && event.getUserID() == selfID) return false;
        if (service.blacklistService().contains(event.getGroupID(),event.getUserID())) return false;
        if (service.containsIgnoredContent(event.getMessage())) return false;
        String content = service.extractContent(event.getMessage());
        if (content == null || content.trim().isEmpty() || service.isCommand(content)) return false;
        JSONObject safety = service.getSafetyService().inspect(event.getGroupID(),event.getUserID(),content);
        if (safety.getBooleanValue("block")) return false;
        if ((service.hasImageContent(event.getMessage()) || service.hasStickerContent(event.getMessage()))
                && !service.hasMeaningfulText(event.getMessage())) return false;
        if (active.messages.size() >= service.config().toolLiveMessageMax) {
            service.plugin().getLogger().sendWarn("[工具] 群"+event.getGroupID()
                    +" 工具执行期间新消息超过上限，本条转入下一回合");
            return false;
        }
        LiveMessage live = new LiveMessage();
        live.userID = event.getUserID();
        live.userName = service.senderName(event);
        live.content = content;
        live.messageID = event.getMessageID();
        live.time = System.currentTimeMillis();
        active.messages.add(live);
        if (event.getMessageID() > 0) {
            if (service.mergedMessageIDs.size() > 500) service.mergedMessageIDs.clear();
            service.mergedMessageIDs.add(event.getMessageID());
        }
        service.recordMessage(event,content,false);
        service.contextService().recordTopic(event.getGroupID(),content,false);
        service.plugin().getLogger().sendInfo("[工具] 群"+event.getGroupID()
                +" 工具执行期间并入新消息 "+live.userName+"（QQ："+live.userID+"）："
                +service.shortText(content,60));
        return true;
    }

    private void appendLiveMessages(long groupID,String token,JSONArray messages,JSONArray toolTrace) {
        ActiveTurn active = activeTurns.get(groupID);
        if (active == null || !active.token.equals(token)) return;
        List<LiveMessage> drained = new ArrayList<>();
        LiveMessage live;
        while ((live = active.messages.poll()) != null) drained.add(live);
        if (drained.isEmpty()) return;
        StringBuilder builder = new StringBuilder("工具执行期间又收到了新消息。把它们和工具结果一起考虑：\n");
        for (LiveMessage item : drained) {
            builder.append(item.userName).append("（QQ：").append(item.userID).append("）：")
                    .append(service.shortText(item.content,500)).append("\n");
        }
        messages.add(service.message("user",builder.toString()));
        JSONObject trace = new JSONObject(true);
        trace.put("stage","live_messages");
        trace.put("count",drained.size());
        toolTrace.add(trace);
    }

    private String begin(long groupID) {
        String token = groupID+"-"+sequence.incrementAndGet();
        activeTurns.put(groupID,new ActiveTurn(token));
        return token;
    }

    private void end(long groupID,String token) {
        ActiveTurn active = activeTurns.get(groupID);
        if (active != null && active.token.equals(token)) activeTurns.remove(groupID,active);
    }

    private JSONObject parseArgs(String arguments) {
        try {
            JSONObject value = JSONObject.parseObject(arguments);
            return value == null ? new JSONObject(true) : value;
        } catch (Exception e) {
            return new JSONObject(true);
        }
    }

    private JSONObject copy(JSONObject value) {
        JSONObject result = new JSONObject(true);
        if (value != null) result.putAll(value);
        return result;
    }

    private boolean recoverWithoutTools(JSONObject response) {
        String type = response == null ? "" : service.safe(response.getString("errorType"));
        return "http_400".equals(type) || "http_422".equals(type);
    }

    private JSONObject emptyUsage() {
        JSONObject usage = new JSONObject(true);
        usage.put("promptTokens",0);
        usage.put("completionTokens",0);
        usage.put("totalTokens",0);
        usage.put("cachedPromptTokens",0);
        return usage;
    }

    private void addUsage(JSONObject total,JSONObject current) {
        if (total == null || current == null) return;
        total.put("promptTokens",total.getIntValue("promptTokens")+current.getIntValue("promptTokens"));
        total.put("completionTokens",total.getIntValue("completionTokens")+current.getIntValue("completionTokens"));
        total.put("totalTokens",total.getIntValue("totalTokens")+current.getIntValue("totalTokens"));
        total.put("cachedPromptTokens",total.getIntValue("cachedPromptTokens")
                +current.getIntValue("cachedPromptTokens"));
    }

    private String redact(String text) {
        return service.safe(text).replaceAll("(?i)(api[_-]?key|token|authorization)\\s*[:=]\\s*[^,}\\s]+","$1=***");
    }

    private JSONObject error(String message) {
        JSONObject result = new JSONObject(true);
        result.put("status",false);
        result.put("message",message);
        return result;
    }

    private static class ActiveTurn {
        private final String token;
        private final ConcurrentLinkedQueue<LiveMessage> messages = new ConcurrentLinkedQueue<>();

        private ActiveTurn(String token) {
            this.token = token;
        }
    }

    private static class LiveMessage {
        private long userID;
        private String userName = "";
        private String content = "";
        private long messageID;
        private long time;
    }
}
