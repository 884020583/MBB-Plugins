package org.moboxlab.mbb.roleplay;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色提示词资料
 *
 * 负责提醒清单、可选知识库、学生印象、外貌参考、语料情绪和场景提示。
 */
public class RoleplayPromptMaterialService {
    private final RoleplayService service;

    public RoleplayPromptMaterialService(RoleplayService service) {
        this.service = service;
    }

    String reminderPromptText(long groupID,long userID,String content) {
        String text = service.safe(content);
        boolean asking = text.contains("提醒") || text.contains("几点") || text.contains("什么时候")
                || text.contains("安排") || text.contains("记了") || text.contains("记下");
        String body = asking ? service.getReminderService().pendingText(groupID,userID)
                : service.getReminderService().pendingBrief(groupID,userID);
        return "当前用户的待触发提醒（只是背景资料，不要在回复里逐条复述时间与内容）：\n"+body+"\n";
    }

    String materialCatalogue() {
        //角色核心、外貌、学生名录与固定规则现在都是常驻资料，不需要路由点名
        String catalogue = "";
        //学生档案库接管学生资料后，不再向路由层推荐插件自带的详细设定
        if (!service.knowledgeService().isStudentsLibraryEnabled()) {
            catalogue += "students.detail：被提到的学生的完整外貌，问起某位学生时带上\n";
        }
        String knowledge = service.knowledgeService().catalogueText();
        if (!knowledge.isEmpty()) {
            catalogue += "可选知识库（问到相关内容时点选，不需要就不要选）：\n"+knowledge;
        }
        return catalogue;
    }

    String studentBriefText() {
        if (!service.knowledgeService().isStudentsLibraryEnabled()) return service.persona().studentBriefText();
        if (service.persona().otherStudents.isEmpty()) return "";
        StringBuilder builder = new StringBuilder("了解的学生：");
        boolean first = true;
        for (String item : service.persona().otherStudents) {
            String text = service.safe(item).trim();
            if (text.isEmpty()) continue;
            String name = text;
            int colon = text.indexOf('：');
            if (colon > 0) name = text.substring(0,colon).trim();
            String summary = service.knowledgeService().studentsSummary(name);
            if (!summary.isEmpty()) text = name+"："+summary;
            if (!first) builder.append("；");
            builder.append(text);
            first = false;
        }
        builder.append("\n");
        return builder.toString();
    }

    String visionReferenceText() {
        String text = service.knowledgeService().visionReferenceText(service.persona().name,service.persona().appearance,
                service.config().visionReferenceMaxChars);
        return text.isEmpty() ? service.persona().visionReferenceText() : text;
    }

    String speechEmotionHint(long groupID,long userID,boolean otherRoleBot) {
        if (!service.config().emotionEnable || groupID <= 0) return "";
        List<String> hints = new ArrayList<>();
        double annoyance = otherRoleBot || userID <= 0 ? 0 : service.emotionService().annoyance(groupID,userID);
        int valence = service.emotionService().valence(groupID);
        int patience = service.emotionService().patience(groupID);
        if (annoyance >= 65 || patience < 30) {
            hints.add("annoyed");
            hints.add("serious");
        } else if (valence >= 70) {
            hints.add("happy");
            hints.add("excited");
            hints.add("proud");
        } else if (valence < 35) {
            hints.add("sad");
            hints.add("serious");
        }
        return String.join(",",hints);
    }

    String speechScene(String content,RoleplayRouteDecision decision) {
        String text = service.safe(content);
        if (text.contains("[戳一戳]")) return "group_chat";
        if (decision != null && "direct".equals(decision.addressed)) return "reply";
        return "daily";
    }

    String speechPrompt(long groupID,long userID,String content,boolean otherRoleBot,
                                RoleplayRouteDecision decision) {
        return service.speechCorpusService().promptText(content,service.contextService().recentContext(groupID),
                speechEmotionHint(groupID,userID,otherRoleBot),speechScene(content,decision));
    }
}