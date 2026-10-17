package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.PluginService;

/**
 * 角色回复生成
 *
 * 负责回复消息装配、图片上下文合并、AI 调用和回复日志。
 */
public class RoleplayReplyGenerationService {
    private final RoleplayService service;

    public RoleplayReplyGenerationService(RoleplayService service) {
        this.service = service;
    }

    JSONObject reply(long groupID,long userID,String userName,String content,
                             boolean otherRoleBot,String relationship,RoleplayService.RecentImage imageContext,
                             RoleplayRouteDecision decision) {
        return reply(groupID,userID,userName,content,otherRoleBot,relationship,imageContext,
                decision,null,0);
    }

    JSONObject reply(long groupID,long userID,String userName,String content,
                             boolean otherRoleBot,String relationship,RoleplayService.RecentImage imageContext,
                             RoleplayRouteDecision decision,String batchText,int batchCount) {
        PluginService ai = service.plugin().getServer().getPluginManager().getService("MBB-AI");
        if (ai == null) return null;
        JSONArray messages = new JSONArray();
        String speechPrompt = service.speechPrompt(groupID,userID,content,otherRoleBot,decision);
        messages.add(service.message("system",service.promptAssembler().buildSystemPrompt(groupID,userID,otherRoleBot,relationship,
                speechPrompt,content,decision,batchCount)));
        String userText;
        if (batchCount > 1) {
            userText = "这一批消息里最需要回应的人："+(userName == null ? "" : userName)
                    +"（QQ："+userID+"）\n"
                    +"当前关系："+relationship+"\n"
                    +"本批主消息指向："+addressingText(decision)+"\n"
                    +"这批连续消息（共 "+batchCount+" 条，按时间顺序）：\n"+batchText+"\n\n"
                    +"最近群聊上下文：\n"+service.contextService().recentContext(groupID);
        } else {
            userText = "当前发言者："+(userName == null ? "" : userName)+"（QQ："+userID+"）\n"
                    +"当前关系："+relationship+"\n"
                    +"当前消息指向："+addressingText(decision)+"\n"
                    +"当前消息：\n"+content+"\n\n最近群聊上下文：\n"+service.contextService().recentContext(groupID);
        }
        if (imageContext != null && !content.contains("[图片")) {
            String summary = service.imageSummary(imageContext);
            if (!summary.isEmpty()) {
                userText += "\n\n用户最近发送的图片：\n"+summary
                        +"\n请结合这张图片回答当前问题。";
            }
        }
        JSONObject userMessage = new JSONObject(true);
        userMessage.put("role","user");
        JSONObject imageData = imageContext == null ? null : service.imageData(imageContext);
        if (imageData != null && imageData.getBooleanValue("status")
                && !service.safe(imageData.getString("dataUri")).isEmpty()) {
            JSONArray contentArray = new JSONArray();
            JSONObject textPart = new JSONObject(true);
            textPart.put("type","text");
            textPart.put("text",userText);
            contentArray.add(textPart);
            JSONObject imageUrl = new JSONObject(true);
            imageUrl.put("url",imageData.getString("dataUri"));
            JSONObject imagePart = new JSONObject(true);
            imagePart.put("type","image_url");
            imagePart.put("image_url",imageUrl);
            contentArray.add(imagePart);
            userMessage.put("content",contentArray);
        } else {
            userMessage.put("content",userText);
        }
        messages.add(userMessage);
        JSONObject params = new JSONObject(true);
        params.put("profile",service.config().aiProfile);
        params.put("maxTokens",imageContext == null
                ? service.config().replyMaxTokens
                : Math.max(service.config().replyMaxTokens,service.config().replyImageMaxTokens));
        params.put("reasoningEffort",service.config().replyReasoningEffort);
        params.put("sessionId","roleplay-"+groupID+"-"+service.contextToken(groupID));
        params.put("messages",messages);
        long startTime = System.currentTimeMillis();
        JSONObject response = ai.call("chat",params);
        long elapsed = System.currentTimeMillis() - startTime;
        RoleplayAiLog.log(service.plugin().getLogger(),"回复",groupID,response,elapsed);
        if (response != null) {
            JSONObject usage = response.getJSONObject("usage");
            JSONObject trace = new JSONObject(true);
            trace.put("message",service.shortText(content,240));
            trace.put("finishReason",service.safe(response.getString("finishReason")));
            trace.put("promptTokens",usage == null ? 0 : usage.getIntValue("promptTokens"));
            trace.put("completionTokens",usage == null ? 0 : usage.getIntValue("completionTokens"));
            trace.put("cachedPromptTokens",usage == null ? 0 : usage.getIntValue("cachedPromptTokens"));
            trace.put("elapsedMs",elapsed);
            trace.put("reply",service.shortText(service.safe(response.getString("content")),240));
            service.getTraceService().record(groupID,userID,0L,"reply",trace);
        }
        return response;
    }

    String addressingText(RoleplayRouteDecision decision) {
        String addressed = decision == null ? "none" : service.safe(decision.addressed);
        if ("direct".equals(addressed)) return "明确对角色说";
        if ("thread".equals(addressed)) return "延续角色参与的话题";
        if ("ambient".equals(addressed)) return "群聊氛围中可能接话";
        return "未明确指向角色";
    }
}
