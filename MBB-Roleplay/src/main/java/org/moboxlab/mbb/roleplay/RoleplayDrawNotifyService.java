package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.OneBot.MessageUtil;
import org.moboxlab.moboxbot.API.OneBot.OneBotClient;
import org.moboxlab.moboxbot.API.PluginService;

/**
 * 生图完成与失败提醒
 *
 * 负责生图回调文案生成、AI 调用、兜底文案和群内艾特发送。
 */
public class RoleplayDrawNotifyService {
    private final RoleplayService service;

    public RoleplayDrawNotifyService(RoleplayService service) {
        this.service = service;
    }

    public JSONObject notifyDrawComplete(JSONObject params) {
        JSONObject prepared = prepareDrawComplete(params);
        if (!prepared.getBooleanValue("status")) return prepared;
        JSONObject sendParams = new JSONObject(true);
        if (params != null) sendParams.putAll(params);
        sendParams.put("text",prepared.getString("text"));
        sendDrawNotify(sendParams);
        return prepared;
    }

    public JSONObject prepareDrawComplete(JSONObject params) {
        long groupID = params == null ? 0L : params.getLongValue("groupID");
        long userID = params == null ? 0L : params.getLongValue("userID");
        String prompt = params == null ? "" : service.safe(params.getString("prompt"));
        String text = generateDrawNotifyText(groupID,userID,prompt,true,"");
        JSONObject result = new JSONObject(true);
        result.put("status",true);
        result.put("text",text);
        result.put("message",text);
        return result;
    }

    public JSONObject sendDrawNotify(JSONObject params) {
        long groupID = params == null ? 0L : params.getLongValue("groupID");
        long userID = params == null ? 0L : params.getLongValue("userID");
        String text = params == null ? "" : service.safe(params.getString("text"));
        sendAtNotify(groupID,userID,text);
        JSONObject result = new JSONObject(true);
        result.put("status",true);
        result.put("message",text);
        return result;
    }

    public JSONObject notifyDrawFailed(JSONObject params) {
        long groupID = params == null ? 0L : params.getLongValue("groupID");
        long userID = params == null ? 0L : params.getLongValue("userID");
        String prompt = params == null ? "" : service.safe(params.getString("prompt"));
        String reason = params == null ? "" : service.safe(params.getString("reason"));
        String text = generateDrawNotifyText(groupID,userID,prompt,false,reason);
        sendAtNotify(groupID,userID,text);
        JSONObject result = new JSONObject(true);
        result.put("status",true);
        result.put("message",text);
        return result;
    }

    String generateDrawNotifyText(long groupID,long userID,String prompt,
                                          boolean success,String reason) {
        String fallback = success ? "画好啦，快看看。" : "这次没画出来，等下再试试。";
        PluginService ai = service.plugin().getServer().getPluginManager().getService("MBB-AI");
        if (ai == null) return fallback;
        try {
            JSONArray messages = new JSONArray();
            messages.add(service.message("system","你是角色 "+service.persona().name+"。"
                    +(success ? "用户刚才要求生图，图片已经生成。"
                    : "用户刚才要求生图，但生图失败了。")
                    +"请用角色语气给发起者一句简短提醒，12 到 20 字以内，"
                    +"提醒只需要表达任务完成，可以结合请求简短提一句画面主题，但不要长篇介绍，不要重复识图。"
                    +"不要 Markdown，不要解释，不要用“图片已生成”这种机械说法，不要提及系统。"));
            String userText = "发起者QQ："+userID
                    +"\n生图需求："+service.shortText(prompt,300)
                    +"\n失败原因："+(reason == null || reason.isEmpty() ? "无" : service.shortText(reason,160));
            messages.add(service.message("user",userText));
            long startTime = System.currentTimeMillis();
            JSONObject response = callDrawNotifyAi(ai,groupID,messages);
            RoleplayAiLog.log(service.plugin().getLogger(),"生图提醒",groupID,response,
                    System.currentTimeMillis() - startTime);
            if (response == null || !response.getBooleanValue("status")) {
                service.plugin().getLogger().sendWarn("[角色] 生图提醒生成失败，使用兜底文案："
                        +service.safe(response == null ? "" : response.getString("message")));
                return fallback;
            }
            String text = service.safe(response.getString("content")).trim();
            if (text.isEmpty()) {
                service.plugin().getLogger().sendWarn("[角色] 生图提醒模型正文为空，使用兜底文案，finish="
                        +service.safe(response.getString("finishReason")));
                return fallback;
            }
            return service.shortText(text,40);
        } catch (Exception e) {
            return fallback;
        }
    }

    JSONObject callDrawNotifyAi(PluginService ai,long groupID,JSONArray messages) {
        JSONObject params = new JSONObject(true);
        params.put("profile",service.config().aiProfile);
        params.put("maxTokens",2000);
        params.put("temperature",0.7);
        params.put("reasoningEffort",service.config().replyReasoningEffort);
        params.put("timeoutSeconds",90);
        params.put("sessionId","roleplay-draw-notify-"+groupID);
        params.put("messages",messages);
        return ai.call("chat",params);
    }

    void sendAtNotify(long groupID,long userID,String text) {
        if (groupID <= 0 || userID <= 0) return;
        OneBotClient client = service.plugin().getServer().getOneBotClient();
        if (client == null) return;
        JSONArray message = MessageUtil.message(MessageUtil.at(userID),
                MessageUtil.text(" "+(text == null ? "" : text.trim())));
        JSONObject response = client.sendGroupMessage(groupID,message);
        service.plugin().getLogger().sendInfo("[角色] 生图回调提醒 群"+groupID+" 用户"+userID
                +" 内容="+service.shortText(text,80)+" 结果="
                +(response != null && response.getIntValue("retcode") == 0 ? "成功" : "失败"));
    }
}