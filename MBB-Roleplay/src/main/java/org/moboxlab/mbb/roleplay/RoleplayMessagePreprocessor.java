package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Event.GroupMessageEvent;

/**
 * 角色消息预处理
 *
 * 负责过滤、识图、消息流水、情绪观察、提醒识别和规则信号组装。
 */
public class RoleplayMessagePreprocessor {
    private final RoleplayService service;

    public RoleplayMessagePreprocessor(RoleplayService service) {
        this.service = service;
    }

    RoleplayService.PreparedMessage prepareMessage(GroupMessageEvent event) {
        if (event == null || !service.config().enable) return null;
        long groupID = event.getGroupID();
        if (!service.isGroupEnabled(groupID)) return null;
        //已经并入延后回合的消息不再单独成一轮
        if (event.getMessageID() > 0 && service.mergedMessageIDs.remove(event.getMessageID())) {
            service.plugin().getLogger().sendInfo("[角色] 群"+groupID+" 跳过已并入上一回合的消息 "
                    +event.getMessageID());
            return null;
        }
        long selfID = event.getRaw().getLongValue("self_id");
        if (selfID > 0 && selfID == event.getUserID()) return null;
        if (service.blacklistService().contains(groupID,event.getUserID())) {
            service.plugin().getLogger().sendInfo("[角色] 群"+groupID+" 忽略黑名单用户 "
                    +event.getUserID()+" 的消息");
            return null;
        }
        if (service.containsIgnoredContent(event.getMessage())) return null;
        String content = service.extractContent(event.getMessage());
        if (content == null || content.trim().isEmpty()) return null;
        if (service.isCommand(content)) return null;
        JSONObject safety = service.getSafetyService().inspect(groupID,event.getUserID(),content);
        if (safety.getBooleanValue("block")) {
            service.plugin().getLogger().sendWarn("[安全] 群"+groupID+" 用户"+event.getUserID()
                    +" 已拦截："+safety.getString("reason"));
            return null;
        }
        boolean otherRoleBot = service.isOtherRoleBot(event,selfID);
        boolean hasImage = service.hasImageContent(event.getMessage());
        boolean hasSticker = service.hasStickerContent(event.getMessage());
        //另一个角色机器人的图片和表情不进入识图与回复链路，避免两个机器人围着同一张图互聊
        if (otherRoleBot && (hasImage || hasSticker)) {
            service.plugin().getLogger().sendInfo("[角色] 群"+groupID+" 忽略其他角色机器人的图片或表情消息");
            return null;
        }
        int otherRoleStreak = service.updateOtherRoleMessageStreak(groupID,otherRoleBot);
        boolean addressedToOtherRole = service.isAddressedToOtherRole(event,content,selfID);
        boolean addressedToOtherMember = service.config().addressedOtherMemberSkip
                && service.mentionsOtherMember(event,selfID) && !service.mentionsSelfRole(event,content,selfID);
        boolean multiRoleAddress = service.isMultiRoleAddress(event,content,selfID);
        boolean direct = service.isDirect(event,content,selfID) || multiRoleAddress;
        //免打扰名单：名单内的群员只在主动叫到角色时才得到回复，其他消息不进入回复链路
        boolean quietAddressed = direct || service.mentionsSelfRole(event,content,selfID);
        if (service.getQuietService().shouldStayQuiet(groupID,event.getUserID(),quietAddressed)) {
            service.plugin().getLogger().sendInfo("[角色] 群"+groupID+" 用户"+event.getUserID()
                    +" 在免打扰名单中且未叫到角色，本轮保持沉默");
            return null;
        }
        service.replyFeedbackService().observeHumanMessage(groupID,event.getUserID(),content,event.getMessageID());
        service.expressionService().observe(groupID,event.getUserID(),content);
        String attentionTopic = service.attentionService().topicKey(content);
        service.attentionService().observe(groupID,event.getUserID(),attentionTopic,direct,otherRoleBot);
        boolean sameUserContinuation = service.isContinuation(groupID,event.getUserID());
        boolean justRepliedToSameUser = service.justRepliedToSameUser(groupID,event.getUserID());
        boolean groupActive = service.isGroupActive(groupID);
        boolean interest = service.persona().matchesInterest(content);
        boolean hasText = service.hasMeaningfulText(event.getMessage());
        boolean pureImage = hasImage && !hasText;
        RoleplayService.RecentImage currentImage = null;
        if (hasImage) {
            if (hasSticker) {
                service.markStickerRecognitionPending(groupID,event.getUserID());
            }
            currentImage = service.rememberImageContext(event,groupID);
            //纯图片消息的识图交给独立异步任务，不占用这一组的处理线程
            boolean asyncImage = pureImage && service.config().imageAsyncEnable;
            if (!asyncImage && service.shouldUnderstandImages(event,content,direct,sameUserContinuation)) {
                content = service.enrichImageContent(event,content,groupID,currentImage);
                addressedToOtherRole = service.isAddressedToOtherRole(event,content,selfID);
                multiRoleAddress = service.isMultiRoleAddress(event,content,selfID);
                direct = service.isDirect(event,content,selfID) || multiRoleAddress;
                interest = service.persona().matchesInterest(content);
            }
        }
        RoleplayService.RecentImage replyImage = currentImage;
        if (!hasImage) replyImage = service.findRecentImageContext(groupID,event.getUserID(),content);
        boolean recentImageQuestion = !hasImage && replyImage != null;
        service.recordMessage(event,content,false);
        service.contextService().recordTopic(groupID,content,false);
        int count = service.countMessage(groupID);
        if (count >= service.config().memoryUpdateMessages) {
            service.rateLimitService().resetGroup(groupID);
            service.triggerMemory(groupID,"定时整理");
        }
        if (pureImage) {
            if (service.config().imageAsyncEnable) {
                service.dispatchImageUnderstanding(event,groupID,currentImage,hasSticker,
                        direct,sameUserContinuation);
            } else if (hasSticker) {
                String emotion = content == null || content.trim().isEmpty() ? "[表情包]" : content.trim();
                service.finishStickerRecognition(groupID,event.getUserID(),emotion);
            }
            return null;
        }
        RoleplayDecisionEngine.Signals signals = new RoleplayDecisionEngine.Signals();
        JSONObject thread = service.getThreadService().observe(groupID,event,selfID);
        signals.otherRoleBot = otherRoleBot;
        signals.otherRoleStreak = otherRoleStreak;
        signals.addressedToOtherRole = addressedToOtherRole;
        signals.addressedToOtherMember = addressedToOtherMember;
        signals.reminderNotification = service.isReminderNotification(content);
        signals.direct = direct;
        signals.quotingSelf = service.isQuotingSelf(event,selfID);
        signals.mentioningSelf = service.isMentioningSelf(event,selfID);
        if (signals.quotingSelf) {
            service.replyFeedbackService().observeQuote(groupID,event.getUserID());
        }
        signals.sameUserContinuation = sameUserContinuation;
        signals.justRepliedToSameUser = justRepliedToSameUser;
        //只在"刚回复过同一用户"时才需要查上一条，省一次数据库查询
        signals.completesPreviousMessage = justRepliedToSameUser
                && service.completesPreviousIncomplete(groupID,event.getUserID());
        signals.groupActive = groupActive;
        signals.interest = interest;
        signals.recentImageQuestion = recentImageQuestion;
        signals.contentLength = content.length();
        RoleplayContextService.TopicFatigue topicFatigue = service.contextService().topicFatigue(groupID,content);
        signals.topicFatigued = topicFatigue.fatigued();
        signals.topicHint = topicFatigue.hint();
        signals.threadHint = thread.getString("hint");
        signals.threadTurn = thread.getString("turn");
        String skipReason = RoleplayDecisionEngine.skipReason(service.config(),signals);
        if (skipReason != null) return null;
        RoleplayEmotionService.LocalEvent emotionEvent = service.emotionService().observeMessage(
                groupID,event.getUserID(),service.senderName(event),service.relationshipLabel(event,otherRoleBot),
                content,event.getMessageID(),direct,otherRoleBot);
        if (!otherRoleBot && service.getReminderService().handle(event,content)) return null;
        //引用回复：把被引用的那条消息一并带给模型，否则角色看不到对方在回哪句话
        String quoted = service.quotedText(groupID,event);
        RoleplayService.PreparedMessage prepared = new RoleplayService.PreparedMessage();
        prepared.event = event;
        prepared.groupID = groupID;
        prepared.selfID = selfID;
        prepared.userID = event.getUserID();
        prepared.userName = service.senderName(event);
        prepared.relationship = service.relationshipLabel(event,otherRoleBot);
        prepared.content = quoted.isEmpty() ? content : quoted+content;
        prepared.otherRoleBot = otherRoleBot;
        prepared.direct = direct;
        prepared.hasImage = hasImage;
        prepared.stickerEmotion = service.recentStickerEmotion(groupID,event.getUserID());
        prepared.imageContext = replyImage;
        prepared.signals = signals;
        prepared.emotionEvent = emotionEvent;
        prepared.attentionTopic = attentionTopic;
        return prepared;
    }
}
