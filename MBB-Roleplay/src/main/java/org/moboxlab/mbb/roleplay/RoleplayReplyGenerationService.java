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
                             RoleplayRouteDecision decision,long messageID,long selfID) {
        return reply(groupID,userID,userName,content,otherRoleBot,relationship,imageContext,
                decision,null,0,messageID,selfID);
    }

    JSONObject reply(long groupID,long userID,String userName,String content,
                             boolean otherRoleBot,String relationship,RoleplayService.RecentImage imageContext,
                             RoleplayRouteDecision decision,String batchText,int batchCount,
                             long messageID,long selfID) {
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
        String anchorInput = batchCount > 1
                ? service.safe(batchText)+"\n"+service.safe(content) : service.safe(content);
        messages.add(service.message("system",
                service.promptAssembler().styleAnchorPrompt(anchorInput)));
        if (service.config().toolEnable) {
            String catalogue = service.toolLoopService().registry().catalogueText(service.config());
            messages.add(service.message("system",
                    "你可以调用本轮提供的工具。当前能力目录：\n"+catalogue
                            +"需要事实、历史、记忆、知识库、网页或角色私有文件时先调用工具，"
                            +"不要在结果返回前声称已经完成。工具结果返回后可以继续调用工具，"
                            +"最多连续处理多轮检索；工具结果会作为本轮事实上下文保留，"
                            +"也可以直接输出最终回复。确实不需要回复时调用 end_turn。"
                            +"工具执行期间收到的新消息会和工具结果一起提供，需要一并考虑。"
                            +"message_send 只用于工具处理中先追加一句，不要用它重复最终回复。"
                            +"模型内部思考不会进入下一轮，也不要假装记得没有出现在工具结果或上下文里的事实。"
                            +"workspace_* 只能操作 plugins/MBB-Roleplay/data 内的文件，"
                            +"不能读取或修改外部路径。"));
        }
        messages.add(userMessage);
        JSONObject params = new JSONObject(true);
        params.put("profile",service.config().aiProfile);
        params.put("maxTokens",imageContext == null
                ? service.config().replyMaxTokens
                : Math.max(service.config().replyMaxTokens,service.config().replyImageMaxTokens));
        params.put("reasoningEffort",service.config().replyReasoningEffort);
        params.put("sessionId","roleplay-"+groupID+"-"+service.contextToken(groupID));
        RoleplayToolContext toolContext = new RoleplayToolContext();
        toolContext.groupID = groupID;
        toolContext.userID = userID;
        toolContext.selfID = selfID;
        toolContext.messageID = messageID;
        toolContext.userName = userName == null ? "" : userName;
        toolContext.relationship = relationship == null ? "" : relationship;
        toolContext.message = content == null ? "" : content;
        toolContext.channel = "group";
        toolContext.privacy = "group";
        long startTime = System.currentTimeMillis();
        JSONObject response = service.toolLoopService().run(groupID,toolContext,messages,params);
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
            JSONArray toolTrace = response.getJSONArray("toolTrace");
            if (toolTrace != null) trace.put("toolTrace",toolTrace);
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
