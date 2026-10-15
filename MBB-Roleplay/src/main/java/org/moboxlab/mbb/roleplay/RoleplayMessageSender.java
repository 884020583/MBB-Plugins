package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.OneBot.MessageUtil;
import org.moboxlab.moboxbot.API.OneBot.OneBotClient;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 消息发送与分段
 *
 * 负责回复分段、引用、链接过滤和发送后的发言状态登记，避免这些逻辑继续堆在 RoleplayService。
 */
public class RoleplayMessageSender {
    private static final String STRONG_SPLIT_PUNCTUATION = "。！？!?…";
    private static final Pattern REPLY_URL_PATTERN = Pattern.compile(
            "(?i)\\bhttps?://\\S+|\\bwww\\.\\S+|\\b[a-z0-9-]+\\.(com|cn|net|org|io|me|xyz|top)(/\\S*)?\\b");

    private final RoleplayService service;

    public RoleplayMessageSender(RoleplayService service) {
        this.service = service;
    }

    public void sendReply(OneBotClient client,long groupID,long selfID,long userID,String reply,
                          boolean quoteRequired,boolean quoteSuggested,long quoteMessageID) {
        List<String> segments = splitReply(reply);
        if (segments.isEmpty()) return;
        RoleplayConfig config = service.config();
        boolean quote = config.quoteReplyEnable
                && (quoteRequired || quoteSuggested) && quoteMessageID > 0;
        int count = Math.min(segments.size(),config.replyMaxSegments);
        for (int i = 0; i < count; i++) {
            sendSingleMessage(client,groupID,selfID,userID,segments.get(i),quote && i == 0,
                    quoteMessageID);
            if (i + 1 < count && !service.sleepQuietly(250L)) return;
        }
    }

    public boolean sendSingleMessage(OneBotClient client,long groupID,long selfID,long userID,
                                     String text,boolean quote,long quoteMessageID) {
        if (client == null || text == null || text.trim().isEmpty()) return false;
        text = filterReplyLinks(groupID,text);
        if (text.isEmpty()) return false;
        JSONArray message = quote && quoteMessageID > 0
                ? MessageUtil.message(MessageUtil.reply(quoteMessageID),MessageUtil.text(text))
                : MessageUtil.message(MessageUtil.text(text));
        JSONObject response = client.sendGroupMessage(groupID,message);
        if (response == null || response.getIntValue("retcode") != 0) return false;
        long messageID = response.getJSONObject("data") == null
                ? 0L : response.getJSONObject("data").getLongValue("message_id");
        RoleplayConversationState state = service.state(groupID);
        state.lastBotMessageID = messageID;
        state.lastReplyUser = userID;
        state.botStreak++;
        service.recordBotMessage(groupID,selfID,text,messageID);
        service.replyFeedbackService().onBotMessage(groupID,messageID,text);
        return true;
    }

    private String filterReplyLinks(long groupID,String text) {
        String value = service.safe(text);
        if (!service.config().replyLinkFilterEnable || value.isEmpty()) return value;
        String filtered = REPLY_URL_PATTERN.matcher(value).replaceAll(" ")
                .replaceAll("\\s+"," ").trim();
        if (!filtered.equals(value)) {
            service.plugin().getLogger().sendInfo("[角色] 群"+groupID+" 已过滤模型输出链接："
                    +service.shortText(value,120));
        }
        return filtered;
    }

    public List<String> splitReply(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;
        int maxChars = service.config().replySegmentMaxChars;
        String normalized = text.replace("\\n","\n").replace("\r\n","\n").replace("\r","\n");
        String[] paragraphs = normalized.split("\n");
        for (String paragraph : paragraphs) {
            String value = paragraph.replaceAll("\\s+"," ").trim();
            if (value.isEmpty()) continue;
            if (value.length() <= maxChars) result.add(value);
            else addParagraph(result,value,maxChars);
        }
        return result;
    }

    private void addParagraph(List<String> result,String text,int maxChars) {
        while (text.length() > maxChars) {
            int cut = findSplitCut(text,maxChars,true);
            if (cut <= 0) cut = findSplitCut(text,maxChars,false);
            if (cut <= 0) cut = maxChars;
            if (cut > 0 && cut < text.length() && text.charAt(cut) == '…') {
                int runStart = cut;
                while (runStart > 0 && text.charAt(runStart - 1) == '…') runStart--;
                if (runStart > 0) cut = runStart;
                else while (cut < text.length() && text.charAt(cut) == '…') cut++;
            }
            String segment = trimWeakSplitPunctuation(text.substring(0,cut).trim());
            if (!segment.isEmpty()) result.add(segment);
            text = text.substring(cut).trim();
        }
        if (!text.isEmpty()) result.add(text);
    }

    public int findSplitCut(String text,int maxChars,boolean strong) {
        int start = Math.max(0,maxChars - 20);
        for (int i = Math.min(maxChars - 1,text.length() - 1); i >= start; i--) {
            char value = text.charAt(i);
            if (strong ? isStrongSplitPunctuation(value) : isWeakSplitPunctuation(value)) return i + 1;
        }
        return -1;
    }

    private boolean isSplitPunctuation(char value) {
        String punctuation = service.config().replySplitPunctuation;
        return punctuation != null && punctuation.indexOf(value) >= 0;
    }

    private boolean isStrongSplitPunctuation(char value) {
        return isSplitPunctuation(value) && STRONG_SPLIT_PUNCTUATION.indexOf(value) >= 0;
    }

    boolean isWeakSplitPunctuation(char value) {
        return isSplitPunctuation(value) && !isStrongSplitPunctuation(value);
    }

    String trimWeakSplitPunctuation(String text) {
        String value = text == null ? "" : text.trim();
        while (!value.isEmpty() && isWeakSplitPunctuation(value.charAt(value.length() - 1))) {
            value = value.substring(0,value.length() - 1).trim();
        }
        return value;
    }
}