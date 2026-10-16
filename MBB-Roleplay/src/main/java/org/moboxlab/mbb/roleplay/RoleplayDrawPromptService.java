package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.PluginService;

/**
 * 生图 prompt 编译层
 *
 * 角色层只提供画面 brief；本服务用独立上下文注入当前角色的 canonical tag、
 * 外貌资料和禁止标签，再输出最终给 ComfyUI 的正向/负向 prompt。
 */
public class RoleplayDrawPromptService {
    private final RoleplayService service;

    public RoleplayDrawPromptService(RoleplayService service) {
        this.service = service;
    }

    public RoleplayDrawPromptResult compile(long groupID,long userID,
                                             RoleplayDrawBrief brief,
                                             String requestedSize,
                                             String requestedCamera) {
        if (brief == null || brief.isEmpty()) {
            return RoleplayDrawPromptResult.failed("生图 brief 为空，已取消任务");
        }
        RoleplayConfig config = service.config();
        RoleplayPersona persona = service.persona();
        String size = safe(brief.size).isEmpty() ? safe(requestedSize) : brief.size;
        String camera = safe(brief.camera).isEmpty() ? safe(requestedCamera) : brief.camera;
        if (!config.drawPromptCompilerEnable) {
            return fallback(persona,brief,size,camera,"compiler-disabled");
        }
        PluginService ai = service.plugin().getServer().getPluginManager().getService("MBB-AI");
        if (ai == null) return fallback(persona,brief,size,camera,"ai-missing");
        try {
            JSONArray messages = new JSONArray();
            messages.add(service.message("system",compilerPrompt(persona,brief)));
            messages.add(service.message("user","请把下面的画面 brief 编译成最终生图 JSON：\n"
                    +brief.toJson().toJSONString()));
            JSONObject params = new JSONObject(true);
            String profile = safe(config.drawPromptProfile).isEmpty()
                    ? config.aiProfile : config.drawPromptProfile.trim();
            params.put("profile",profile);
            params.put("maxTokens",config.drawPromptMaxTokens);
            params.put("temperature",0.0);
            params.put("reasoningEffort",config.drawPromptReasoningEffort);
            params.put("timeoutSeconds",config.drawPromptTimeoutSecond);
            params.put("sessionId","roleplay-draw-prompt-"+groupID+"-"+userID);
            params.put("messages",messages);
            long startTime = System.currentTimeMillis();
            JSONObject response = ai.call("chat",params);
            RoleplayAiLog.log(service.plugin().getLogger(),"生图prompt",groupID,response,
                    System.currentTimeMillis() - startTime);
            JSONObject parsed = service.parseJson(response == null ? "" : response.getString("content"));
            if (parsed == null) {
                parsed = service.parseJson(response == null ? "" : response.getString("reasoningContent"));
            }
            if (parsed == null) {
                service.plugin().getLogger().sendWarn("[生图] prompt 编译没有返回合法 JSON，使用兜底编译。");
                return fallback(persona,brief,size,camera,"invalid-json");
            }
            String prompt = safe(parsed.getString("prompt"));
            String negative = safe(parsed.getString("negativePrompt"));
            String compiledSize = safe(parsed.getString("size"));
            String compiledCamera = safe(parsed.getString("camera"));
            RoleplayDrawPromptResult result = RoleplayDrawPromptValidator.validate(persona,brief,
                    prompt,negative,config.drawPromptMaxChars,config.drawPromptNegativeMaxChars);
            if (!result.status) return result;
            if (!compiledSize.isEmpty()) result.size = compiledSize;
            else result.size = size;
            if (!compiledCamera.isEmpty()) result.camera = compiledCamera;
            else result.camera = camera;
            service.plugin().getLogger().sendInfo("[生图] brief="+service.shortText(brief.promptText(),240));
            service.plugin().getLogger().sendInfo("[生图] compiler="+service.shortText(result.prompt,240)
                    +" validation="+result.validation);
            return result;
        } catch (Exception e) {
            service.plugin().getLogger().sendWarn("[生图] prompt 编译异常，使用兜底编译："
                    +service.safe(e.getMessage()));
            return fallback(persona,brief,size,camera,"exception");
        }
    }

    private String compilerPrompt(RoleplayPersona persona,RoleplayDrawBrief brief) {
        String knowledge = service.getKnowledgeService().currentCharacterAppearanceText(
                persona,service.config().drawPromptKnowledgeMaxChars);
        StringBuilder builder = new StringBuilder();
        builder.append("你是生图 prompt 编译器，只输出 JSON，不要解释，不要聊天。\n")
                .append("当前角色：").append(persona.name).append("\n")
                .append(persona.imageTagsText())
                .append(persona.imageNegativeTagsText())
                .append("角色外貌：").append(persona.appearance).append("\n");
        if (!knowledge.isEmpty()) builder.append(knowledge);
        builder.append("画面 brief：").append(brief.toJson().toJSONString()).append("\n");
        builder.append("编译规则：\n")
                .append("- 只画当前角色本人；除非 brief.characters 明确列出其他角色，否则禁止加入其他角色。\n")
                .append("- 只允许使用当前角色的发色、瞳色、光环、服装、配饰和武器；禁止使用凯伊/柯伊/Kei 或其他学生的外貌。\n")
                .append("- 必须使用角色 canonical tags；如果 brief 和 canonical tag 冲突，以 canonical tag 为准。\n")
                .append("- 必须明确发色、瞳色、光环颜色、服装、配饰和武器；不能只写角色名。\n")
                .append("- 不要写 masterpiece、best quality、highres、highly detailed、anime illustration 等质量词，插件会自动补。\n")
                .append("- 不要写 twins、clone、duplicate character、multiple views、split screen、extra person。\n")
                .append("- 单人使用 1girl，双人使用 2girls，人数标签只出现一次。\n")
                .append("- negativePrompt 只放角色专属负向词和身份冲突词；不要重复插件全局负向词。\n")
                .append("- prompt 使用逗号分隔的英文 booru 风格 tag，控制在 ")
                .append(service.config().drawPromptMaxChars).append(" 字符以内。\n")
                .append("输出格式：{\"prompt\":\"...\",\"negativePrompt\":\"...\",")
                .append("\"size\":\"square|landscape|portrait|avatar\",\"camera\":\"selfie|photo|\"}");
        return builder.toString();
    }

    private RoleplayDrawPromptResult fallback(RoleplayPersona persona,RoleplayDrawBrief brief,
                                              String size,String camera,String reason) {
        StringBuilder positive = new StringBuilder();
        for (String tag : persona.imageTags) {
            if (positive.length() > 0) positive.append(", ");
            positive.append(tag);
        }
        String briefText = brief.promptText();
        if (!briefText.isEmpty()) {
            if (positive.length() > 0) positive.append(", ");
            positive.append(briefText);
        }
        StringBuilder negative = new StringBuilder();
        for (String tag : persona.imageNegativeTags) {
            if (negative.length() > 0) negative.append(", ");
            negative.append(tag);
        }
        RoleplayDrawPromptResult result = RoleplayDrawPromptValidator.validate(persona,brief,
                positive.toString(),negative.toString(),
                service.config().drawPromptMaxChars,service.config().drawPromptNegativeMaxChars);
        if (result.status) {
            result.size = size;
            result.camera = camera;
            service.plugin().getLogger().sendWarn("[生图] 使用兜底编译 reason="+reason
                    +" prompt="+service.shortText(result.prompt,180));
        }
        return result;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
