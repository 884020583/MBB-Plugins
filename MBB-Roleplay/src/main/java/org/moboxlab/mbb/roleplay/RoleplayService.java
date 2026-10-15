package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Event.GroupMessageEvent;
import org.moboxlab.moboxbot.API.Event.NoticeEvent;
import org.moboxlab.moboxbot.API.OneBot.MessageUtil;
import org.moboxlab.moboxbot.API.OneBot.OneBotClient;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginService;
import org.moboxlab.moboxbot.API.Storage.StorageService;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 角色扮演与记忆服务
 */
public class RoleplayService {
    static final String MSG_TABLE = "plugin_mbb_roleplay_messages";
    static final String MEMORY_TABLE = "plugin_mbb_roleplay_memory";
    static final String STATE_TABLE = "plugin_mbb_roleplay_state";
    private static final String GROUP_TABLE = "plugin_mbb_roleplay_group";
    static final String TOPIC_TABLE = "plugin_mbb_roleplay_topic";    /** 引用回复里被引用内容的截断长度 */

    /** 分句时优先保留的句末标点 */

    private final Plugin plugin;
    private final RoleplayReminderService reminderService;
    private final RoleplayGlobalMemoryService globalMemoryService;
    private final RoleplaySpeechCorpusService speechCorpusService;
    private final RoleplayKnowledgeService knowledgeService;
    private final RoleplayActionService actionService;
    private final RoleplayEmotionService emotionService;
    private final RoleplayMemberService memberService;
    private final RoleplayBlacklistService blacklistService;
    private final RoleplayQuietService quietService;
    private final RoleplayReplyFeedbackService replyFeedbackService;
    private final RoleplayExpressionService expressionService;
    private final RoleplayAttentionService attentionService;
    private final RoleplayDailyReviewService dailyReviewService;
    private final RoleplayMessageSender messageSender;
    private final RoleplayContextService contextService;
    private final RoleplayPromptAssembler promptAssembler;
    private final RoleplayMemoryBackupService memoryBackupService;
    private final RoleplayMemoryUpdateService memoryUpdateService;
    private final RoleplayBatchScheduler batchScheduler;
    private final RoleplayPokeService pokeService;
    private final RoleplayReplyExecutor replyExecutor;
    private final RoleplayTurnProcessor turnProcessor;
    private final RoleplayTextUtils textUtils;
    private final RoleplayJsonUtils jsonUtils;
    private final RoleplayImageContextService imageContextService;
    private final RoleplayStickerService stickerService;
    private final RoleplayAddressService addressService;
    private final RoleplayRateLimitService rateLimitService;
    private final RoleplayMemoryQueryService memoryQueryService;
    private final RoleplaySkillRegistry skillRegistry;
    private final RoleplayRouter router;
    private final RoleplayStyler styler;
    private volatile RoleplayConfig config;
    private volatile RoleplayPersona persona;
    private final Map<Long,RoleplayConversationState> conversationStateMap = new ConcurrentHashMap<>();


    final Map<Long,Boolean> memoryUpdatingMap = new ConcurrentHashMap<>();
    final Map<Long,String> pendingMemoryMap = new ConcurrentHashMap<>();
    final Map<Long,String> memoryErrorTypeMap = new ConcurrentHashMap<>();
    final Map<Long,Boolean> memoryMergingMap = new ConcurrentHashMap<>();



    //被并入延后回合的消息 ID，处理下一批时直接跳过，避免同一句话回两次
    final Set<Long> mergedMessageIDs = Collections.newSetFromMap(
            new ConcurrentHashMap<Long,Boolean>());
    final Object groupQueueLock = new Object();
    final Map<Long,Boolean> groupBusyMap = new HashMap<>();
    //回合执行线程池：路由与生成都是长耗时 AI 调用，不再占用共享的定时任务线程
    private volatile ExecutorService turnExecutor;
    private volatile int turnThreads = 0;
    //后台任务线程池：记忆整理、情绪分析、识图这类长耗时调用同样不占共享定时线程
    private volatile ExecutorService backgroundExecutor;
    //合批缓冲：同一群在窗口内到达的消息先攒起来，窗口结束后当成一个回合处理
    final Map<Long,List<GroupMessageEvent>> batchBufferMap = new HashMap<>();
    final Map<Long,Integer> batchGenerationMap = new HashMap<>();
    final Map<Long,Long> batchBufferStartMap = new HashMap<>();
    private static final Pattern REMEMBER_PAIR = Pattern.compile(
            "(?is)<\\s*remember\\s*>(.*?)<\\s*/\\s*remember\\s*>");
    private static final Pattern REMEMBER_OPEN = Pattern.compile(
            "(?is)<\\s*remember\\s*/?\\s*>");
    private static final Pattern REMEMBER_CLOSE = Pattern.compile(
            "(?is)<\\s*/\\s*remember\\s*>");
    private static final Pattern REMINDER_PAIR = Pattern.compile(
            "(?is)<\\s*reminder\\s*>(.*?)<\\s*/\\s*reminder\\s*>");
    private static final Pattern REMINDER_OPEN = Pattern.compile(
            "(?is)<\\s*reminder\\s*/?\\s*>");
    private static final Pattern GLOBAL_REMEMBER_PAIR = Pattern.compile(
            "(?is)<\\s*global[\\s_-]?remember\\s*>(.*?)"
                    + "<\\s*/\\s*global[\\s_-]?remember\\s*>");
    private static final Pattern GLOBAL_REMEMBER_OPEN = Pattern.compile(
            "(?is)<\\s*global[\\s_-]?remember\\s*/?\\s*>");
    private static final Pattern GLOBAL_REMEMBER_CLOSE = Pattern.compile(
            "(?is)<\\s*/\\s*global[\\s_-]?remember\\s*>");

    static class MemoryCursor {
        final long time;
        final long id;

        MemoryCursor(long time,long id) {
            this.time = time;
            this.id = id;
        }
    }

    static class RememberResult {
        final boolean requested;
        final String reply;
        final String memory;

        private RememberResult(boolean requested,String reply,String memory) {
            this.requested = requested;
            this.reply = reply;
            this.memory = memory;
        }
    }

    static class ReminderMarkerResult {
        final boolean requested;
        final String reply;
        final String time;
        final String task;
        final String target;
        final String action;
        final long id;

        private ReminderMarkerResult(boolean requested,String reply,String time,String task,
                                     String target,String action,long id) {
            this.requested = requested;
            this.reply = reply;
            this.time = time;
            this.task = task;
            this.target = target;
            this.action = action;
            this.id = id;
        }
    }

    static class GlobalRememberResult {
        final boolean requested;
        final String reply;
        final String content;

        private GlobalRememberResult(boolean requested,String reply,String content) {
            this.requested = requested;
            this.reply = reply;
            this.content = content;
        }
    }

    static class StickerMarkerResult {
        final boolean requested;
        final String reply;
        final String tags;

        StickerMarkerResult(boolean requested,String reply,String tags) {
            this.requested = requested;
            this.reply = reply;
            this.tags = tags;
        }
    }

    static class RecentImage {
        final JSONObject data;
        final long expireAt;
        volatile String summary = "";

        RecentImage(JSONObject data,long expireAt) {
            this.data = data;
            this.expireAt = expireAt;
        }

        boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    static class RecentSticker {
        final String emotion;
        final long expireAt;

        RecentSticker(String emotion,long expireAt) {
            this.emotion = emotion;
            this.expireAt = expireAt;
        }

        boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }


    /**
     * 已通过准备阶段、等待路由和回复的消息
     * 合批时多条 PreparedMessage 一起送进同一次路由与生成
     */
    static class PreparedMessage {
        GroupMessageEvent event;
        long groupID;
        long selfID;
        long userID;
        String userName = "";
        String relationship = "朋友";
        String content = "";
        boolean otherRoleBot = false;
        boolean direct = false;
        boolean hasImage = false;
        String stickerEmotion = "";
        RecentImage imageContext;
        RoleplayDecisionEngine.Signals signals;
        RoleplayEmotionService.LocalEvent emotionEvent;
        String attentionTopic = "";
    }


    public RoleplayService(Plugin plugin,RoleplayConfig config,RoleplayPersona persona) {
        this.plugin = plugin;
        this.config = config;
        this.persona = persona;
        this.reminderService = new RoleplayReminderService(plugin,config,persona,this);
        this.globalMemoryService = new RoleplayGlobalMemoryService(plugin,config,this);
        this.speechCorpusService = new RoleplaySpeechCorpusService(plugin,config);
        this.knowledgeService = new RoleplayKnowledgeService(plugin,config);
        this.actionService = new RoleplayActionService(plugin);
        this.memberService = new RoleplayMemberService(plugin,config);
        this.emotionService = new RoleplayEmotionService(plugin,config,persona,this,memberService);
        this.blacklistService = new RoleplayBlacklistService(plugin);
        this.quietService = new RoleplayQuietService(plugin,config);
        this.replyFeedbackService = new RoleplayReplyFeedbackService(plugin,config);
        this.expressionService = new RoleplayExpressionService(plugin,config);
        this.attentionService = new RoleplayAttentionService(plugin,config);
        this.dailyReviewService = new RoleplayDailyReviewService(plugin,config,this);
        this.messageSender = new RoleplayMessageSender(this);
        this.contextService = new RoleplayContextService(this);
        this.promptAssembler = new RoleplayPromptAssembler(this);
        this.memoryBackupService = new RoleplayMemoryBackupService(this);
        this.memoryUpdateService = new RoleplayMemoryUpdateService(this);
        this.batchScheduler = new RoleplayBatchScheduler(this);
        this.pokeService = new RoleplayPokeService(this);
        this.replyExecutor = new RoleplayReplyExecutor(this);
        this.turnProcessor = new RoleplayTurnProcessor(this);
        this.textUtils = new RoleplayTextUtils(this);
        this.jsonUtils = new RoleplayJsonUtils();
        this.imageContextService = new RoleplayImageContextService(this);
        this.stickerService = new RoleplayStickerService(this);
        this.addressService = new RoleplayAddressService(this);
        this.rateLimitService = new RoleplayRateLimitService(this);
        this.memoryQueryService = new RoleplayMemoryQueryService(this);
        this.skillRegistry = new RoleplaySkillRegistry(this);
        this.router = new RoleplayRouter(plugin,this);
        this.styler = new RoleplayStyler(plugin);
    }

    public void init() {
        storage().update("CREATE TABLE IF NOT EXISTS `"+MSG_TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`messageID` INTEGER NOT NULL DEFAULT 0,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0,"
                + "`userID` INTEGER NOT NULL DEFAULT 0,"
                + "`userName` TEXT NOT NULL DEFAULT '',"
                + "`messageTime` INTEGER NOT NULL DEFAULT 0,"
                + "`content` TEXT NOT NULL DEFAULT '',"
                + "`isBot` INTEGER NOT NULL DEFAULT 0"
                + ")");
        storage().update("CREATE TABLE IF NOT EXISTS `"+MEMORY_TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0,"
                + "`memoryType` TEXT NOT NULL DEFAULT '',"
                + "`subjectID` INTEGER NOT NULL DEFAULT 0,"
                + "`content` TEXT NOT NULL DEFAULT '',"
                + "`importance` INTEGER NOT NULL DEFAULT 1,"
                + "`updateTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        storage().update("CREATE TABLE IF NOT EXISTS `"+STATE_TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0 UNIQUE,"
                + "`shortSummary` TEXT NOT NULL DEFAULT '',"
                + "`lastMemoryTime` INTEGER NOT NULL DEFAULT 0,"
                + "`lastMemoryID` INTEGER NOT NULL DEFAULT 0,"
                + "`lastMergeTime` INTEGER NOT NULL DEFAULT 0,"
                + "`updateTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        ensureColumn(STATE_TABLE,"lastMemoryID","INTEGER NOT NULL DEFAULT 0");
        ensureColumn(STATE_TABLE,"lastMergeTime","INTEGER NOT NULL DEFAULT 0");
        storage().update("CREATE TABLE IF NOT EXISTS `"+GROUP_TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0 UNIQUE,"
                + "`enabled` INTEGER NOT NULL DEFAULT 0,"
                + "`contextToken` TEXT NOT NULL DEFAULT '',"
                + "`updateTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        storage().update("CREATE TABLE IF NOT EXISTS `"+TOPIC_TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`groupID` INTEGER NOT NULL DEFAULT 0,"
                + "`topicKey` TEXT NOT NULL DEFAULT '',"
                + "`lastTime` INTEGER NOT NULL DEFAULT 0,"
                + "`count` INTEGER NOT NULL DEFAULT 0,"
                + "`selfCount` INTEGER NOT NULL DEFAULT 0,"
                + "`lastSelfTime` INTEGER NOT NULL DEFAULT 0,"
                + "`updateTime` INTEGER NOT NULL DEFAULT 0,"
                + "UNIQUE(`groupID`,`topicKey`)"
                + ")");
        storage().update("DELETE FROM `"+TOPIC_TABLE+"` WHERE `updateTime`<?",
                System.currentTimeMillis() - Math.max(1,config.topicFatigueKeepHours) * 3600000L);
        ensureColumn(GROUP_TABLE,"contextToken","TEXT NOT NULL DEFAULT ''");
        storage().update("CREATE INDEX IF NOT EXISTS `idx_plugin_mbb_roleplay_msg_group` ON `"+MSG_TABLE+"` (`groupID`,`messageTime`)");
        reminderService.init();
        globalMemoryService.init();
        speechCorpusService.init();
        knowledgeService.init();
        emotionService.init();
        memberService.init();
        blacklistService.init();
        quietService.init();
        replyFeedbackService.init();
        expressionService.init();
        attentionService.reload(config);
        ensureTurnExecutor();
        ensureBackgroundExecutor();
        dailyReviewService.ensureTask();
    }

    public void reload(RoleplayConfig config,RoleplayPersona persona) {
        this.config = config;
        this.persona = persona;
        reminderService.reload(config,persona);
        globalMemoryService.reload(config);
        speechCorpusService.reload(config);
        knowledgeService.reload(config);
        emotionService.reload(config,persona);
        memberService.reload(config);
        quietService.reload(config);
        replyFeedbackService.reload(config);
        expressionService.reload(config);
        attentionService.reload(config);
        dailyReviewService.reload(config);
        ensureTurnExecutor();
        ensureBackgroundExecutor();
        dailyReviewService.ensureTask();
    }

    /**
     * 每日复盘：按本地时区在配置的小时执行一次，把当天消息压成短期记忆。
     */
    public void triggerDailyReview(long groupID) {
        dailyReviewService.trigger(groupID);
    }

    private void ensureTurnExecutor() {
        int threads = config.turnThreads < 1 ? 1 : Math.min(config.turnThreads,16);
        if (turnExecutor != null && turnThreads == threads) return;
        ExecutorService previous = turnExecutor;
        turnExecutor = Executors.newFixedThreadPool(threads,runnable -> {
            Thread thread = new Thread(runnable,"MoBoxBot-Roleplay-Turn");
            thread.setDaemon(true);
            return thread;
        });
        turnThreads = threads;
        if (previous != null) previous.shutdown();
    }

    private void ensureBackgroundExecutor() {
        if (backgroundExecutor != null) return;
        backgroundExecutor = Executors.newFixedThreadPool(2,runnable -> {
            Thread thread = new Thread(runnable,"MoBoxBot-Roleplay-Background");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 把一整个回合丢到独立线程池执行；线程池不可用时退回当前线程，保证功能不丢
     */
    void submitTurn(Runnable task) {
        if (task == null) return;
        ExecutorService executor = turnExecutor;
        if (executor == null) {
            task.run();
            return;
        }
        try {
            executor.submit(task);
        } catch (RejectedExecutionException e) {
            task.run();
        }
    }

    /**
     * 记忆整理、情绪分析、识图等后台 AI 调用统一走这个线程池
     */
    void submitBackground(Runnable task) {
        if (task == null) return;
        ExecutorService executor = backgroundExecutor;
        if (executor == null) {
            task.run();
            return;
        }
        try {
            executor.submit(task);
        } catch (RejectedExecutionException e) {
            task.run();
        }
    }

    public void shutdown() {
        ExecutorService executor = turnExecutor;
        turnExecutor = null;
        if (executor != null) executor.shutdownNow();
        ExecutorService background = backgroundExecutor;
        backgroundExecutor = null;
        if (background != null) background.shutdownNow();
    }

    /**
     * 群消息入口
     * 同一个群先按窗口攒批，窗口结束后整批处理一次；处理期间到达的消息进入下一批，
     * 这样连续快速发言不会被逐条排队，旧消息也不会挤掉新消息。
     */
    public void handle(GroupMessageEvent event) {
        batchScheduler.handle(event);
    }

    boolean processGroupMessage(GroupMessageEvent event) {
        return turnProcessor.processGroupMessage(event);
    }

    /**
     * 合批回合：先把批内每条消息各自过一遍准备阶段，再只做一次路由和一次生成
     * 返回 true 表示这一轮交给了延后回合，调用方不能当成已结束
     */
    boolean processMessageBatch(long groupID,List<GroupMessageEvent> batch) {
        return turnProcessor.processMessageBatch(groupID,batch);
    }

    void wakePendingTurnForUser(long groupID,GroupMessageEvent event) {
        turnProcessor.wakePendingTurnForUser(groupID,event);
    }

    private void markStickerRecognitionPending(long groupID,long userID) {
        turnProcessor.markStickerRecognitionPending(groupID,userID);
    }

    void finishStickerRecognition(long groupID,long userID,String emotion) {
        turnProcessor.finishStickerRecognition(groupID,userID,emotion);
    }

    /**
     * 消息准备：过滤、识图、记流水、规则预判、情绪观察和提醒识别
     * 返回 null 表示这条消息本轮不需要角色出声
     */
    PreparedMessage prepareMessage(GroupMessageEvent event) {
        if (event == null || !config.enable) return null;
        long groupID = event.getGroupID();
        if (!isGroupEnabled(groupID)) return null;
        //已经并入延后回合的消息不再单独成一轮
        if (event.getMessageID() > 0 && mergedMessageIDs.remove(event.getMessageID())) {
            plugin.getLogger().sendInfo("[角色] 群"+groupID+" 跳过已并入上一回合的消息 "
                    +event.getMessageID());
            return null;
        }
        long selfID = event.getRaw().getLongValue("self_id");
        if (selfID > 0 && selfID == event.getUserID()) return null;
        if (blacklistService.contains(groupID,event.getUserID())) {
            plugin.getLogger().sendInfo("[角色] 群"+groupID+" 忽略黑名单用户 "
                    +event.getUserID()+" 的消息");
            return null;
        }
        if (containsIgnoredContent(event.getMessage())) return null;
        String content = extractContent(event.getMessage());
        if (content == null || content.trim().isEmpty()) return null;
        if (isCommand(content)) return null;
        boolean otherRoleBot = isOtherRoleBot(event,selfID);
        boolean hasImage = hasImageContent(event.getMessage());
        boolean hasSticker = hasStickerContent(event.getMessage());
        //另一个角色机器人的图片和表情不进入识图与回复链路，避免两个机器人围着同一张图互聊
        if (otherRoleBot && (hasImage || hasSticker)) {
            plugin.getLogger().sendInfo("[角色] 群"+groupID+" 忽略其他角色机器人的图片或表情消息");
            return null;
        }
        int otherRoleStreak = updateOtherRoleMessageStreak(groupID,otherRoleBot);
        boolean addressedToOtherRole = isAddressedToOtherRole(event,content,selfID);
        boolean addressedToOtherMember = config.addressedOtherMemberSkip
                && mentionsOtherMember(event,selfID) && !mentionsSelfRole(event,content,selfID);
        boolean multiRoleAddress = isMultiRoleAddress(event,content,selfID);
        boolean direct = isDirect(event,content,selfID) || multiRoleAddress;
        //免打扰名单：名单内的群员只在主动叫到角色时才得到回复，其他消息不进入回复链路
        boolean quietAddressed = direct || mentionsSelfRole(event,content,selfID);
        if (quietService.shouldStayQuiet(groupID,event.getUserID(),quietAddressed)) {
            plugin.getLogger().sendInfo("[角色] 群"+groupID+" 用户"+event.getUserID()
                    +" 在免打扰名单中且未叫到角色，本轮保持沉默");
            return null;
        }
        replyFeedbackService.observeHumanMessage(groupID,event.getUserID(),content,event.getMessageID());
        expressionService.observe(groupID,event.getUserID(),content);
        String attentionTopic = attentionService.topicKey(content);
        attentionService.observe(groupID,event.getUserID(),attentionTopic,direct,otherRoleBot);
        boolean sameUserContinuation = isContinuation(groupID,event.getUserID());
        boolean justRepliedToSameUser = justRepliedToSameUser(groupID,event.getUserID());
        boolean groupActive = isGroupActive(groupID);
        boolean interest = persona.matchesInterest(content);
        boolean hasText = hasMeaningfulText(event.getMessage());
        boolean pureImage = hasImage && !hasText;
        RecentImage currentImage = null;
        if (hasImage) {
            if (hasSticker) {
                markStickerRecognitionPending(groupID,event.getUserID());
            }
            currentImage = rememberImageContext(event,groupID);
            //纯图片消息的识图交给独立异步任务，不占用这一组的处理线程
            boolean asyncImage = pureImage && config.imageAsyncEnable;
            if (!asyncImage && shouldUnderstandImages(event,content,direct,sameUserContinuation)) {
                content = enrichImageContent(event,content,groupID,currentImage);
                addressedToOtherRole = isAddressedToOtherRole(event,content,selfID);
                multiRoleAddress = isMultiRoleAddress(event,content,selfID);
                direct = isDirect(event,content,selfID) || multiRoleAddress;
                interest = persona.matchesInterest(content);
            }
        }
        RecentImage replyImage = currentImage;
        if (!hasImage) replyImage = findRecentImageContext(groupID,event.getUserID(),content);
        boolean recentImageQuestion = !hasImage && replyImage != null;
        recordMessage(event,content,false);
        contextService.recordTopic(groupID,content,false);
        int count = countMessage(groupID);
        if (count >= config.memoryUpdateMessages) {
            rateLimitService.resetGroup(groupID);
            triggerMemory(groupID,"定时整理");
        }
        if (pureImage) {
            if (config.imageAsyncEnable) {
                dispatchImageUnderstanding(event,groupID,currentImage,hasSticker,
                        direct,sameUserContinuation);
            } else if (hasSticker) {
                String emotion = content == null || content.trim().isEmpty() ? "[表情包]" : content.trim();
                finishStickerRecognition(groupID,event.getUserID(),emotion);
            }
            return null;
        }
        RoleplayDecisionEngine.Signals signals = new RoleplayDecisionEngine.Signals();
        signals.otherRoleBot = otherRoleBot;
        signals.otherRoleStreak = otherRoleStreak;
        signals.addressedToOtherRole = addressedToOtherRole;
        signals.addressedToOtherMember = addressedToOtherMember;
        signals.reminderNotification = isReminderNotification(content);
        signals.direct = direct;
        signals.quotingSelf = isQuotingSelf(event,selfID);
        signals.mentioningSelf = isMentioningSelf(event,selfID);
        signals.sameUserContinuation = sameUserContinuation;
        signals.justRepliedToSameUser = justRepliedToSameUser;
        //只在"刚回复过同一用户"时才需要查上一条，省一次数据库查询
        signals.completesPreviousMessage = justRepliedToSameUser
                && completesPreviousIncomplete(groupID,event.getUserID());
        signals.groupActive = groupActive;
        signals.interest = interest;
        signals.recentImageQuestion = recentImageQuestion;
        signals.contentLength = content.length();
        RoleplayContextService.TopicFatigue topicFatigue = contextService.topicFatigue(groupID,content);
        signals.topicFatigued = topicFatigue.fatigued();
        signals.topicHint = topicFatigue.hint();
        String skipReason = RoleplayDecisionEngine.skipReason(config,signals);
        if (skipReason != null) return null;
        RoleplayEmotionService.LocalEvent emotionEvent = emotionService.observeMessage(
                groupID,event.getUserID(),senderName(event),relationshipLabel(event,otherRoleBot),
                content,event.getMessageID(),direct,otherRoleBot);
        if (!otherRoleBot && reminderService.handle(event,content)) return null;
        //引用回复：把被引用的那条消息一并带给模型，否则角色看不到对方在回哪句话
        String quoted = quotedText(groupID,event);
        PreparedMessage prepared = new PreparedMessage();
        prepared.event = event;
        prepared.groupID = groupID;
        prepared.selfID = selfID;
        prepared.userID = event.getUserID();
        prepared.userName = senderName(event);
        prepared.relationship = relationshipLabel(event,otherRoleBot);
        prepared.content = quoted.isEmpty() ? content : quoted+content;
        prepared.otherRoleBot = otherRoleBot;
        prepared.direct = direct;
        prepared.hasImage = hasImage;
        prepared.stickerEmotion = recentStickerEmotion(groupID,event.getUserID());
        prepared.imageContext = replyImage;
        prepared.signals = signals;
        prepared.emotionEvent = emotionEvent;
        prepared.attentionTopic = attentionTopic;
        return prepared;
    }


    /**
     * 合批回合：先把批内每条消息各自过一遍准备阶段，再只做一次路由和一次生成
     * 返回 true 表示这一轮交给了延后回合，调用方不能当成已结束
     */


    /**
     * 批内主消息：优先取最后一条直接点名角色的消息，没有就取最后一条
     */






    /**
     * 戳一戳事件
     * 走和群消息同一条链路，只是把当前消息换成"有人戳了你"，戳回去由执行层决定
     */
    public void handlePoke(NoticeEvent event) {
        pokeService.handlePoke(event);
    }


    void executeSkillCalls(RoleplayRouteDecision decision,RoleplayReplyDraft draft,
                           long groupID,long userID,long messageID,long selfID,
                           String userName,String relationship,String content) {
        replyExecutor.executeSkillCalls(decision,draft,groupID,userID,messageID,selfID,
                userName,relationship,content);
    }

    void ensurePokeBackAction(RoleplayReplyDraft draft,RoleplayRouteDecision decision,String reply) {
        replyExecutor.ensurePokeBackAction(draft,decision,reply);
    }
    public JSONObject status(long groupID) {
        JSONObject result = new JSONObject(true);
        result.put("status",true);
        result.put("groupID",groupID);
        result.put("role",persona.name);
        result.put("groupEnabled",groupID <= 0 || isGroupEnabled(groupID));
        result.put("shortSummary",shortSummary(groupID));
        result.put("memoryCount",memoryCount(groupID));
        result.put("mood",emotionService.moodStats(groupID,1).getJSONObject("mood"));
        return result;
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
        String prompt = params == null ? "" : safe(params.getString("prompt"));
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
        String text = params == null ? "" : safe(params.getString("text"));
        sendAtNotify(groupID,userID,text);
        JSONObject result = new JSONObject(true);
        result.put("status",true);
        result.put("message",text);
        return result;
    }

    public JSONObject notifyDrawFailed(JSONObject params) {
        long groupID = params == null ? 0L : params.getLongValue("groupID");
        long userID = params == null ? 0L : params.getLongValue("userID");
        String prompt = params == null ? "" : safe(params.getString("prompt"));
        String reason = params == null ? "" : safe(params.getString("reason"));
        String text = generateDrawNotifyText(groupID,userID,prompt,false,reason);
        sendAtNotify(groupID,userID,text);
        JSONObject result = new JSONObject(true);
        result.put("status",true);
        result.put("message",text);
        return result;
    }

    private String generateDrawNotifyText(long groupID,long userID,String prompt,
                                          boolean success,String reason) {
        String fallback = success ? "画好啦，快看看。" : "这次没画出来，等下再试试。";
        PluginService ai = plugin.getServer().getPluginManager().getService("MBB-AI");
        if (ai == null) return fallback;
        try {
            JSONArray messages = new JSONArray();
            messages.add(message("system","你是角色 "+persona.name+"。"
                    +(success ? "用户刚才要求生图，图片已经生成。"
                    : "用户刚才要求生图，但生图失败了。")
                    +"请用角色语气给发起者一句简短提醒，12 到 20 字以内，"
                    +"提醒只需要表达任务完成，可以结合请求简短提一句画面主题，但不要长篇介绍，不要重复识图。"
                    +"不要 Markdown，不要解释，不要用“图片已生成”这种机械说法，不要提及系统。"));
            String userText = "发起者QQ："+userID
                    +"\n生图需求："+shortText(prompt,300)
                    +"\n失败原因："+(reason == null || reason.isEmpty() ? "无" : shortText(reason,160));
            messages.add(message("user",userText));
            long startTime = System.currentTimeMillis();
            JSONObject response = callDrawNotifyAi(ai,groupID,messages);
            RoleplayAiLog.log(plugin.getLogger(),"生图提醒",groupID,response,
                    System.currentTimeMillis() - startTime);
            if (response == null || !response.getBooleanValue("status")) {
                plugin.getLogger().sendWarn("[角色] 生图提醒生成失败，使用兜底文案："
                        +safe(response == null ? "" : response.getString("message")));
                return fallback;
            }
            String text = safe(response.getString("content")).trim();
            if (text.isEmpty()) {
                plugin.getLogger().sendWarn("[角色] 生图提醒模型正文为空，使用兜底文案，finish="
                        +safe(response.getString("finishReason")));
                return fallback;
            }
            return shortText(text,40);
        } catch (Exception e) {
            return fallback;
        }
    }

    private JSONObject callDrawNotifyAi(PluginService ai,long groupID,JSONArray messages) {
        JSONObject params = new JSONObject(true);
        params.put("profile",config.aiProfile);
        params.put("maxTokens",2000);
        params.put("temperature",0.7);
        params.put("reasoningEffort",config.replyReasoningEffort);
        params.put("timeoutSeconds",90);
        params.put("sessionId","roleplay-draw-notify-"+groupID);
        params.put("messages",messages);
        return ai.call("chat",params);
    }

    private void sendAtNotify(long groupID,long userID,String text) {
        if (groupID <= 0 || userID <= 0) return;
        OneBotClient client = plugin.getServer().getOneBotClient();
        if (client == null) return;
        JSONArray message = MessageUtil.message(MessageUtil.at(userID),
                MessageUtil.text(" "+(text == null ? "" : text.trim())));
        JSONObject response = client.sendGroupMessage(groupID,message);
        plugin.getLogger().sendInfo("[角色] 生图回调提醒 群"+groupID+" 用户"+userID
                +" 内容="+shortText(text,80)+" 结果="
                +(response != null && response.getIntValue("retcode") == 0 ? "成功" : "失败"));
    }

    public boolean isGroupEnabled(long groupID) {
        JSONObject row = storage().queryOne("SELECT `enabled` FROM `"+GROUP_TABLE+"` WHERE `groupID`=?",groupID);
        return row != null && row.getIntValue("enabled") == 1;
    }

    String contextToken(long groupID) {
        JSONObject row = storage().queryOne(
                "SELECT `contextToken` FROM `"+GROUP_TABLE+"` WHERE `groupID`=?",groupID);
        if (row != null && row.getString("contextToken") != null
                && !row.getString("contextToken").trim().isEmpty()) {
            return row.getString("contextToken").trim();
        }
        String token = newContextToken();
        if (row == null) {
            storage().insert("INSERT INTO `"+GROUP_TABLE+"` "
                            + "(`groupID`,`enabled`,`contextToken`,`updateTime`) VALUES (?,?,?,?)",
                    groupID,0,token,System.currentTimeMillis());
        } else {
            storage().update("UPDATE `"+GROUP_TABLE+"` SET `contextToken`=?,`updateTime`=? WHERE `groupID`=?",
                    token,System.currentTimeMillis(),groupID);
        }
        return token;
    }

    private void rotateContextToken(long groupID) {
        String token = newContextToken();
        int rows = storage().update("UPDATE `"+GROUP_TABLE+"` SET `contextToken`=?,`updateTime`=? WHERE `groupID`=?",
                token,System.currentTimeMillis(),groupID);
        if (rows <= 0) {
            storage().insert("INSERT INTO `"+GROUP_TABLE+"` "
                            + "(`groupID`,`enabled`,`contextToken`,`updateTime`) VALUES (?,?,?,?)",
                    groupID,0,token,System.currentTimeMillis());
        }
    }

    private String newContextToken() {
        return UUID.randomUUID().toString().replace("-","");
    }

    public void setGroupEnabled(long groupID,boolean enabled) {
        JSONObject row = storage().queryOne("SELECT `ID` FROM `"+GROUP_TABLE+"` WHERE `groupID`=?",groupID);
        long now = System.currentTimeMillis();
        if (row == null) {
            storage().insert("INSERT INTO `"+GROUP_TABLE+"` (`groupID`,`enabled`,`updateTime`) VALUES (?,?,?)",
                    groupID,enabled ? 1 : 0,now);
        } else {
            storage().update("UPDATE `"+GROUP_TABLE+"` SET `enabled`=?,`updateTime`=? WHERE `groupID`=?",
                    enabled ? 1 : 0,now,groupID);
        }
    }

    public JSONArray listGroups() {
        JSONArray result = new JSONArray();
        List<JSONObject> rows = storage().query("SELECT `groupID` FROM `"+GROUP_TABLE+"` WHERE `enabled`=1 ORDER BY `groupID` ASC");
        if (rows == null) return result;
        for (JSONObject row : rows) result.add(row.getLongValue("groupID"));
        return result;
    }

    void saveMemoryState(long groupID,String shortTerm,long lastTime,long lastID) {
        memoryUpdateService.saveMemoryState(groupID,shortTerm,lastTime,lastID);
    }

    void triggerMemory(long groupID,String reason) {
        memoryUpdateService.triggerMemory(groupID,reason);
    }

    void triggerMemory(long groupID,String reason,String directMemory) {
        memoryUpdateService.triggerMemory(groupID,reason,directMemory);
    }

    void mergeLongMemoryIfNeeded(long groupID) {
        memoryUpdateService.mergeLongMemoryIfNeeded(groupID);
    }

    public boolean mergeLongMemoryNow(long groupID) {
        return memoryUpdateService.mergeLongMemoryNow(groupID);
    }

    public boolean mergeLongMemoryNow(long groupID,boolean force) {
        return memoryUpdateService.mergeLongMemoryNow(groupID,force);
    }

    public boolean mergeLongMemoryNow(long groupID,boolean force,Consumer<String> callback) {
        return memoryUpdateService.mergeLongMemoryNow(groupID,force,callback);
    }
    public JSONObject memoryStats(long groupID) {
        JSONObject result = status(groupID);
        result.put("shortSummary",shortSummary(groupID));
        result.put("memories",longMemories(groupID,false));
        return result;
    }

    public void clearMemory(long groupID) {
        storage().update("DELETE FROM `"+MEMORY_TABLE+"` WHERE `groupID`=?",groupID);
        storage().update("DELETE FROM `"+STATE_TABLE+"` WHERE `groupID`=?",groupID);
        storage().update("DELETE FROM `"+MSG_TABLE+"` WHERE `groupID`=?",groupID);
        rotateContextToken(groupID);
        memoryUpdatingMap.remove(groupID);
        pendingMemoryMap.remove(groupID);
        rateLimitService.resetGroup(groupID);
        conversationStateMap.remove(groupID);

    }

    public String getRoleName() {
        return persona.name;
    }

    public RoleplayReminderService getReminderService() {
        return reminderService;
    }

    public RoleplayGlobalMemoryService getGlobalMemoryService() {
        return globalMemoryService;
    }

    public RoleplaySpeechCorpusService getSpeechCorpusService() {
        return speechCorpusService;
    }

    public RoleplayKnowledgeService getKnowledgeService() {
        return knowledgeService;
    }

    public RoleplayActionService getActionService() {
        return actionService;
    }

    public RoleplayEmotionService getEmotionService() {
        return emotionService;
    }

    public RoleplayBlacklistService getBlacklistService() {
        return blacklistService;
    }

    public RoleplayQuietService getQuietService() {
        return quietService;
    }

    public RoleplayReplyFeedbackService getReplyFeedbackService() {
        return replyFeedbackService;
    }

    RoleplayReplyFeedbackService replyFeedbackService() {
        return replyFeedbackService;
    }

    public RoleplayExpressionService getExpressionService() {
        return expressionService;
    }

    public RoleplayAttentionService getAttentionService() {
        return attentionService;
    }

    RoleplayContextService contextService() {
        return contextService;
    }

    RoleplayReplyExecutor replyExecutor() {
        return replyExecutor;
    }

    RoleplayBatchScheduler batchScheduler() {
        return batchScheduler;
    }

    RoleplayMessageSender messageSender() {
        return messageSender;
    }

    RoleplaySpeechCorpusService speechCorpusService() {
        return speechCorpusService;
    }

    RoleplayStyler styler() {
        return styler;
    }

    RoleplaySkillRegistry skillRegistry() {
        return skillRegistry;
    }

    RoleplayEmotionService emotionService() {
        return emotionService;
    }

    RoleplayPersona persona() {
        return persona;
    }

    RoleplayAttentionService attentionService() {
        return attentionService;
    }

    RoleplayKnowledgeService knowledgeService() {
        return knowledgeService;
    }

    RoleplayMemberService memberService() {
        return memberService;
    }

    RoleplayGlobalMemoryService globalMemoryService() {
        return globalMemoryService;
    }

    RoleplayBlacklistService blacklistService() {
        return blacklistService;
    }

    RoleplayExpressionService expressionService() {
        return expressionService;
    }

    public void blacklistAdd(long groupID,long userID,String reason,long operatorID) {
        blacklistService.add(groupID,userID,reason,operatorID);
    }

    /** 技能注册表使用的内部入口，只在本包内可见 */
    Plugin plugin() {
        return plugin;
    }

    RoleplayConfig config() {
        return config;
    }

    void rememberNow(long groupID,String content) {
        triggerMemory(groupID,"主动记忆",content);
    }

    void sendStickerMessage(long groupID,long userID,String tags) {
        stickerService.sendSticker(groupID,userID,tags);
    }

    boolean isOwnerUser(long userID) {
        return isOwner(userID);
    }

    boolean isInducedMemory(String content) {
        return isInducedMemoryRequest(content);
    }

    public int ruleLikeCount() {
        return persona.interests.size();
    }

    public RoleplayMemberService getMemberService() {
        return memberService;
    }

    JSONObject reply(long groupID,long userID,String userName,String content,
                             boolean otherRoleBot,String relationship,RecentImage imageContext,
                             RoleplayRouteDecision decision) {
        return reply(groupID,userID,userName,content,otherRoleBot,relationship,imageContext,
                decision,null,0);
    }

    JSONObject reply(long groupID,long userID,String userName,String content,
                             boolean otherRoleBot,String relationship,RecentImage imageContext,
                             RoleplayRouteDecision decision,String batchText,int batchCount) {
        PluginService ai = plugin.getServer().getPluginManager().getService("MBB-AI");
        if (ai == null) return null;
        JSONArray messages = new JSONArray();
        String speechPrompt = speechPrompt(groupID,userID,content,otherRoleBot,decision);
        messages.add(message("system",promptAssembler.buildSystemPrompt(groupID,userID,otherRoleBot,relationship,
                speechPrompt,content,decision,batchCount)));
        String userText;
        if (batchCount > 1) {
            userText = "这一批消息里最需要回应的人："+(userName == null ? "" : userName)
                    +"（QQ："+userID+"）\n"
                    +"当前关系："+relationship+"\n"
                    +"本批主消息指向："+addressingText(decision)+"\n"
                    +"这批连续消息（共 "+batchCount+" 条，按时间顺序）：\n"+batchText+"\n\n"
                    +"最近群聊上下文：\n"+contextService.recentContext(groupID);
        } else {
            userText = "当前发言者："+(userName == null ? "" : userName)+"（QQ："+userID+"）\n"
                    +"当前关系："+relationship+"\n"
                    +"当前消息指向："+addressingText(decision)+"\n"
                    +"当前消息：\n"+content+"\n\n最近群聊上下文：\n"+contextService.recentContext(groupID);
        }
        if (imageContext != null && !content.contains("[图片")) {
            String summary = imageSummary(imageContext);
            if (!summary.isEmpty()) {
                userText += "\n\n用户最近发送的图片：\n"+summary
                        +"\n请结合这张图片回答当前问题。";
            }
        }
        JSONObject userMessage = new JSONObject(true);
        userMessage.put("role","user");
        JSONObject imageData = imageContext == null ? null : imageData(imageContext);
        if (imageData != null && imageData.getBooleanValue("status")
                && !safe(imageData.getString("dataUri")).isEmpty()) {
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
        params.put("profile",config.aiProfile);
        params.put("maxTokens",imageContext == null
                ? config.replyMaxTokens
                : Math.max(config.replyMaxTokens,config.replyImageMaxTokens));
        params.put("reasoningEffort",config.replyReasoningEffort);
        params.put("sessionId","roleplay-"+groupID+"-"+contextToken(groupID));
        params.put("messages",messages);
        long startTime = System.currentTimeMillis();
        JSONObject response = ai.call("chat",params);
        RoleplayAiLog.log(plugin.getLogger(),"回复",groupID,response,System.currentTimeMillis() - startTime);
        return response;
    }

    private String addressingText(RoleplayRouteDecision decision) {
        String addressed = decision == null ? "none" : safe(decision.addressed);
        if ("direct".equals(addressed)) return "明确对角色说";
        if ("thread".equals(addressed)) return "延续角色参与的话题";
        if ("ambient".equals(addressed)) return "群聊氛围中可能接话";
        return "未明确指向角色";
    }


    /**
     * 提醒资料。
     * 只有对方确实在问提醒安排时才给完整清单，其他情况只给条数与最近一条，
     * 否则模型会把待办当成聊天素材逐条念出来。
     */
    String reminderPromptText(long groupID,long userID,String content) {
        String text = safe(content);
        boolean asking = text.contains("提醒") || text.contains("几点") || text.contains("什么时候")
                || text.contains("安排") || text.contains("记了") || text.contains("记下");
        String body = asking ? reminderService.pendingText(groupID,userID)
                : reminderService.pendingBrief(groupID,userID);
        return "当前用户的待触发提醒（只是背景资料，不要在回复里逐条复述时间与内容）：\n"+body+"\n";
    }

    /**
     * 可选资料清单，给路由层点名用
     */
    String materialCatalogue() {
        //角色核心、外貌、学生名录与固定规则现在都是常驻资料，不需要路由点名
        String catalogue = "";
        //学生档案库接管学生资料后，不再向路由层推荐插件自带的详细设定
        if (!knowledgeService.isStudentsLibraryEnabled()) {
            catalogue += "students.detail：被提到的学生的完整外貌，问起某位学生时带上\n";
        }
        String knowledge = knowledgeService.catalogueText();
        if (!knowledge.isEmpty()) {
            catalogue += "可选知识库（问到相关内容时点选，不需要就不要选）：\n"+knowledge;
        }
        return catalogue;
    }

    /**
     * 角色了解的学生一句话印象。
     * 学生档案库启用时用条目 summary 覆盖人设里写死的印象，避免两处说法不一致。
     */
    String studentBriefText() {
        if (!knowledgeService.isStudentsLibraryEnabled()) return persona.studentBriefText();
        if (persona.otherStudents.isEmpty()) return "";
        StringBuilder builder = new StringBuilder("了解的学生：");
        boolean first = true;
        for (String item : persona.otherStudents) {
            String text = safe(item).trim();
            if (text.isEmpty()) continue;
            String name = text;
            int colon = text.indexOf('：');
            if (colon > 0) name = text.substring(0,colon).trim();
            String summary = knowledgeService.studentsSummary(name);
            if (!summary.isEmpty()) text = name+"："+summary;
            if (!first) builder.append("；");
            builder.append(text);
            first = false;
        }
        builder.append("\n");
        return builder.toString();
    }

    /**
     * 识图用的学生外貌参考。
     * 知识库学生库启用时从条目取外貌并按总预算均分，否则退回插件自带的学生图鉴。
     */
    String visionReferenceText() {
        String text = knowledgeService.visionReferenceText(persona.name,persona.appearance,
                config.visionReferenceMaxChars);
        return text.isEmpty() ? persona.visionReferenceText() : text;
    }

    /**
     * 语料检索用的情绪提示。
     * 只在情绪明显偏离基线时才给：语料里 neutral 占四分之三，给成 neutral 等于没给。
     */
    private String speechEmotionHint(long groupID,long userID,boolean otherRoleBot) {
        if (!config.emotionEnable || groupID <= 0) return "";
        List<String> hints = new ArrayList<>();
        double annoyance = otherRoleBot || userID <= 0 ? 0 : emotionService.annoyance(groupID,userID);
        int valence = emotionService.valence(groupID);
        int patience = emotionService.patience(groupID);
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

    /**
     * 语料检索用的场景提示，取值必须是语料里出现过的 scene
     */
    private String speechScene(String content,RoleplayRouteDecision decision) {
        String text = safe(content);
        if (text.contains("[戳一戳]")) return "group_chat";
        if (decision != null && "direct".equals(decision.addressed)) return "reply";
        return "daily";
    }

    String speechPrompt(long groupID,long userID,String content,boolean otherRoleBot,
                                RoleplayRouteDecision decision) {
        return speechCorpusService.promptText(content,contextService.recentContext(groupID),
                speechEmotionHint(groupID,userID,otherRoleBot),speechScene(content,decision));
    }

    String emotionRouterText(long groupID,long userID,boolean otherRoleBot) {
        return emotionService.routerText(groupID,userID,otherRoleBot);
    }

    /**
     * 给路由层看的发言指向说明，避免把别人之间的对话当成对角色说的
     */
    private String addressingHint(GroupMessageEvent event,long groupID,
                                  RoleplayDecisionEngine.Signals signals) {
        if (signals == null) return "";
        List<String> parts = new ArrayList<>();
        parts.add("艾特目标："+atTargetInfo(event));
        parts.add("引用目标："+quotedTargetInfo(groupID,event));
        if (signals.mentioningSelf) parts.add("指向角色：艾特了角色本人");
        if (signals.quotingSelf) parts.add("指向角色：回复的是角色本人上一条消息");
        if (signals.direct && !signals.mentioningSelf) parts.add("指向角色：开头或句首叫了角色的名字");
        if (signals.addressedToOtherMember) parts.add("其他指向：艾特的是其他群成员，不是角色");
        if (signals.addressedToOtherRole) parts.add("其他指向：艾特的是另一个角色机器人");
        if (parts.size() <= 2) parts.add("指向角色：未明确指向角色，需要结合上下文判断");
        return String.join("；",parts);
    }

    String atTargetInfo(GroupMessageEvent event) {
        if (event == null || event.getMessage() == null) return "无";
        List<String> targets = new ArrayList<>();
        JSONArray message = event.getMessage();
        for (int i = 0; i < message.size(); i++) {
            JSONObject segment = message.getJSONObject(i);
            if (segment == null || !"at".equals(segment.getString("type"))) continue;
            JSONObject data = segment.getJSONObject("data");
            long atID = data == null ? 0L : data.getLongValue("qq");
            if (atID > 0) targets.add("QQ "+atID);
        }
        return targets.isEmpty() ? "无" : String.join("、",targets);
    }

    String quotedTargetInfo(long groupID,GroupMessageEvent event) {
        long quotedID = quotedMessageID(event);
        if (quotedID <= 0) return "无";
        JSONObject row = storage().queryOne(
                "SELECT `userID`,`userName` FROM `"+MSG_TABLE+"` WHERE `groupID`=? AND `messageID`=? LIMIT 1",
                groupID,quotedID);
        if (row == null) return "未知（消息ID："+quotedID+"）";
        String name = safe(row.getString("userName"));
        long userID = row.getLongValue("userID");
        return (name.isEmpty() ? "未知" : name)+"（QQ："+userID+"）";
    }

    /**
     * 产出本轮决策
     * 规则先算一遍作为兜底，路由可用时以路由结果为准，但规则保留否决权
     */
    RoleplayRouteDecision routeDecision(RoleplayDecisionEngine.Signals signals,
                                                GroupMessageEvent event,long groupID,long selfID,
                                                String content) {
        RoleplayRouteDecision ruleDecision = RoleplayDecisionEngine.decide(config,signals);
        if (!config.routerEnable) return ruleDecision;
        RoleplayRouteDecision routed = router.route(config,persona,skillRegistry,state(groupID),
                content,senderName(event),relationshipLabel(event,signals.otherRoleBot),
                contextService.recentContext(groupID),emotionRouterText(groupID,event.getUserID(),
                        signals.otherRoleBot),addressingHint(event,groupID,signals),
                signals.topicHint);
        if (routed == null) {
            plugin.getLogger().sendWarn("[角色] 群"+groupID+" 路由失败，回退规则决策");
            return ruleDecision;
        }
        if (signals.direct) {
            routed.reply = true;
            routed.addressed = "direct";
        }
        routed.quoteRequired = config.quoteReplyEnable
                && (signals.quotingSelf || signals.mentioningSelf);
        routed.otherRoleBot = signals.otherRoleBot;
        if (signals.otherRoleBot) {
            //机器人互聊必须服从规则层概率，AI 路由只能决定“想不想接”，不能把概率抬到 1.0
            routed.addressed = "ambient";
            routed.chance = Math.min(routed.chance,config.otherRoleBotReplyChance);
            routed.confidence = Math.min(routed.confidence,config.otherRoleBotReplyChance);
        }
        routed.actions = skillRegistry.actionIds(config);
        return routed;
    }


    public synchronized String backupAllMemories(String reason) {
        return memoryBackupService.backupAllMemories(reason);
    }

    public List<String> listMemoryBackups() {
        return memoryBackupService.listMemoryBackups();
    }

    public synchronized JSONObject restoreAllMemories(String fileName) {
        return memoryBackupService.restoreAllMemories(fileName);
    }


    void clearRuntimeMemoryState() {        conversationStateMap.clear();
        rateLimitService.clearRuntimeState();
        pendingMemoryMap.clear();
        memoryErrorTypeMap.clear();
        memoryMergingMap.clear();
        imageContextService.clearRuntimeState();
        stickerService.clearRuntimeState();
        turnProcessor.clearRuntimeState();
        emotionService.clearCache();
    }










    void recordMessage(GroupMessageEvent event,String content,boolean isBot) {
        JSONObject sender = event.getSender();
        String userName = sender == null ? "" : sender.getString("card");
        if (userName == null || userName.isEmpty()) userName = sender == null ? "" : sender.getString("nickname");
        storage().insert("INSERT INTO `"+MSG_TABLE+"` (`messageID`,`groupID`,`userID`,`userName`,`messageTime`,`content`,`isBot`) VALUES (?,?,?,?,?,?,?)",
                event.getMessageID(),event.getGroupID(),event.getUserID(),userName == null ? "" : userName,
                System.currentTimeMillis(),content,isBot ? 1 : 0);
    }

    void recordBotMessage(long groupID,long botID,String content,long messageID) {
        storage().insert("INSERT INTO `"+MSG_TABLE+"` (`messageID`,`groupID`,`userID`,`userName`,`messageTime`,`content`,`isBot`) VALUES (?,?,?,?,?,?,?)",
                messageID,groupID,botID,"角色",System.currentTimeMillis(),content,1);
        contextService.recordTopic(groupID,content,true);
    }



    /**
     * 执行技能调用
     * 技能必须已注册且在本轮开放清单内，校验失败只丢弃该动作，不影响正文
     */


    boolean isCommand(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("@")) {
            text = text.replaceFirst("^@[0-9]+\\s*","").trim();
        }
        for (String prefix : config.commandPrefixes.split(",")) {
            if (!prefix.trim().isEmpty() && text.startsWith(prefix.trim())) return true;
        }
        return false;
    }






    private boolean isInducedMemoryRequest(String content) {
        String text = safe(content);
        return text.contains("调用全局记忆") || text.contains("全局记忆功能")
                || text.contains("永久记忆") || text.contains("记一下")
                || text.contains("记住这个") || text.contains("记忆一下")
                || text.contains("记下来");
    }

    boolean isOwner(long userID) {
        List<Long> owners = plugin.getServer().getOwnerList();
        return owners != null && owners.contains(userID);
    }





    /**
     * 这条消息是否艾特了普通群成员：既不是角色自己，也不是另一个角色机器人
     */




    boolean isReminderNotification(String content) {
        String text = content == null ? "" : content;
        return text.contains(RoleplayReminderService.REMINDER_MARKER);
    }


    /**
     * 只读的限流判断，用于在调用路由之前提前跳过，避免冷却期还去请求模型
     */


    RoleplayConversationState state(long groupID) {
        RoleplayConversationState existing = conversationStateMap.get(groupID);
        if (existing != null) return existing;
        RoleplayConversationState created = new RoleplayConversationState();
        created.groupID = groupID;
        RoleplayConversationState previous = conversationStateMap.putIfAbsent(groupID,created);
        return previous == null ? created : previous;
    }


    /**
     * 这条消息是否引用了机器人自己的上一条消息
     */

    /**
     * 被引用消息的 id，没有引用时返回 0
     */

    /**
     * 被引用消息的文本，形如「[引用 某人：内容] 」。
     * 先从本地消息流水里找，找不到再退回 OneBot 的 get_msg，两者都拿不到就返回空串。
     */

    /**
     * 本地流水里没有这条消息时，向 OneBot 要一次原文
     */


    /**
     * 这条消息是否艾特了机器人自己
     */



    /**
     * 同一用户刚被回复过：用于抑制"一句话拆成两句"时的第二次回复
     */

    /**
     * 半句识别：结尾正好是角色名字，或者停在连接词上，说明这句话还没说完
     * 命中后延后回合会等同一用户补充，并把补充内容并进同一个回合
     */
    boolean looksIncomplete(String content) {
        if (config.splitMessageSuppressSecond <= 0) return false;
        String text = content == null ? "" : content.trim();
        if (text.isEmpty() || text.length() > config.splitMessageSuppressMaxChars) return false;
        for (String name : addressNames()) {
            if (name.length() >= 2 && text.endsWith(name)) return true;
        }
        String[] tails = new String[]{"，","、","和","与","跟","的","是",
                "然后","但是","因为","所以","而且","还是"};
        for (String tail : tails) {
            if (text.endsWith(tail)) return true;
        }
        return false;
    }

    private List<String> addressNames() {
        List<String> names = new ArrayList<>();
        if (persona.name != null && !persona.name.trim().isEmpty()) {
            names.add(persona.name.trim());
        }
        for (String alias : persona.aliases) {
            if (alias != null && !alias.trim().isEmpty()) names.add(alias.trim());
        }
        String botName = plugin.getServer().getBotName();
        if (botName != null && !botName.trim().isEmpty()) names.add(botName.trim());
        return names;
    }

    /**
     * 上一条用户消息是不是没说完的半句
     * 当前消息已经记过流水，所以取倒数第二条
     */
    boolean completesPreviousIncomplete(long groupID,long userID) {
        if (config.splitMessageSuppressSecond <= 0) return false;
        List<JSONObject> rows = storage().query(
                "SELECT `content` FROM `"+MSG_TABLE+"` WHERE `groupID`=? AND `userID`=? "
                        + "AND `isBot`=0 ORDER BY `ID` DESC LIMIT 1 OFFSET 1",
                groupID,userID);
        if (rows == null || rows.isEmpty()) return false;
        return looksIncomplete(safe(rows.get(0).getString("content")));
    }








    /**
     * 两条回复的最长公共子串长度。
     * “布丁明早验，咖喱七点，别糊锅”和“布丁明早验，咖喱七点，再不睡就凉了”
     * 的 bigram 相似度只有 0.5 左右，但公共片段有 10 个字，说明信息是重复的。
     */




    RememberResult extractRemember(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new RememberResult(false,"","");
        }
        Matcher pair = REMEMBER_PAIR.matcher(text);
        if (pair.find()) {
            String reply = (text.substring(0,pair.start())+text.substring(pair.end())).trim();
            String memory = cleanRememberText(pair.group(1));
            return new RememberResult(true,reply,memory);
        }
        Matcher open = REMEMBER_OPEN.matcher(text);
        if (open.find()) {
            String reply = text.substring(0,open.start()).trim();
            String memory = cleanRememberText(text.substring(open.end()));
            return new RememberResult(true,reply,memory);
        }
        Matcher close = REMEMBER_CLOSE.matcher(text);
        if (close.find()) {
            String reply = (text.substring(0,close.start())+text.substring(close.end())).trim();
            return new RememberResult(true,reply,"");
        }
        return new RememberResult(false,text.trim(),"");
    }

    ReminderMarkerResult extractReminderMarker(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new ReminderMarkerResult(false,"","","","","create",0L);
        }
        Matcher pair = REMINDER_PAIR.matcher(text);
        if (pair.find()) {
            String reply = (text.substring(0,pair.start())+text.substring(pair.end())).trim();
            return parseReminderMarker(pair.group(1),reply);
        }
        Matcher open = REMINDER_OPEN.matcher(text);
        if (open.find()) {
            String reply = text.substring(0,open.start()).trim();
            return parseReminderMarker(text.substring(open.end()),reply);
        }
        return new ReminderMarkerResult(false,text.trim(),"","","","create",0L);
    }

    GlobalRememberResult extractGlobalRemember(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new GlobalRememberResult(false,"","");
        }
        Matcher pair = GLOBAL_REMEMBER_PAIR.matcher(text);
        if (pair.find()) {
            String reply = (text.substring(0,pair.start())+text.substring(pair.end())).trim();
            return new GlobalRememberResult(true,reply,cleanGlobalRememberText(pair.group(1)));
        }
        Matcher open = GLOBAL_REMEMBER_OPEN.matcher(text);
        if (open.find()) {
            String reply = text.substring(0,open.start()).trim();
            String content = cleanGlobalRememberText(text.substring(open.end()));
            return new GlobalRememberResult(true,reply,content);
        }
        return new GlobalRememberResult(false,text.trim(),"");
    }




    private String cleanGlobalRememberText(String text) {
        if (text == null) return "";
        return GLOBAL_REMEMBER_CLOSE.matcher(text).replaceAll("").trim();
    }

    private ReminderMarkerResult parseReminderMarker(String json,String reply) {
        JSONObject parsed = parseJson(json);
        if (parsed == null) {
            return new ReminderMarkerResult(true,reply,"","","","create",0L);
        }
        return new ReminderMarkerResult(true,reply,
                safe(parsed.getString("time")),
                safe(parsed.getString("task")),
                safe(parsed.getString("target")),
                safe(parsed.getString("action")).trim().isEmpty()
                        ? "create" : safe(parsed.getString("action")).trim(),
                parsed.getLongValue("id"));
    }

    private String cleanRememberText(String text) {
        if (text == null) return "";
        return REMEMBER_CLOSE.matcher(text).replaceAll("").trim();
    }




    String extractContent(JSONArray message) {
        if (message == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < message.size(); i++) {
            JSONObject segment = message.getJSONObject(i);
            if (segment == null) continue;
            String type = segment.getString("type");
            JSONObject data = segment.getJSONObject("data");
            if ("text".equals(type)) builder.append(data == null ? "" : safe(data.getString("text")));
            else if ("image".equals(type) || "mface".equals(type)) builder.append("[图片]");
            else if ("at".equals(type)) builder.append("@").append(data == null ? "" : safe(data.getString("qq")));
            else if ("face".equals(type)) builder.append("[表情]");
        }
        return builder.toString().trim();
    }













    /**
     * 同一用户又发言时立刻结束他的表情包等待，不再让回合干等到窗口结束
     */












    /**
     * 纯图片与表情消息的识图
     * 放到独立异步任务里跑，识图完成后合并表情语气并放行等待中的回合，
     * 这样一张图不会卡住同一群后面的消息。
     */





    boolean containsIgnoredContent(JSONArray message) {
        if (message == null) return false;
        for (int i = 0; i < message.size(); i++) {
            JSONObject segment = message.getJSONObject(i);
            if (segment == null) continue;
            String type = segment.getString("type");
            if ("face".equals(type)) return true;
        }
        return false;
    }









    private void ensureColumn(String table,String column,String definition) {
        List<JSONObject> columns = storage().query("PRAGMA table_info(`"+table+"`)");
        if (columns != null) {
            for (JSONObject item : columns) {
                if (column.equalsIgnoreCase(safe(item.getString("name")))) return;
            }
        }
        storage().update("ALTER TABLE `"+table+"` ADD COLUMN `"+column+"` "+definition);
    }


    String safe(String value) {
        return textUtils.safe(value);
    }

    String shortText(String text,int maxChars) {
        return textUtils.shortText(text,maxChars);
    }

    String currentTimeText() {
        return textUtils.currentTimeText();
    }

    String formatMemoryTime(long time) {
        return textUtils.formatMemoryTime(time);
    }

    String normalizeAddressText(String content) {
        return textUtils.normalizeAddressText(content);
    }

    boolean containsRoleAlias(String text,String alias) {
        return textUtils.containsRoleAlias(text,alias);
    }

    boolean startsWithAlias(String text,String alias) {
        return textUtils.startsWithAlias(text,alias);
    }

    boolean isOwnRoleName(String name) {
        return textUtils.isOwnRoleName(name);
    }

    String joinArray(JSONArray array) {
        return textUtils.joinArray(array);
    }

    boolean containsCSVLong(String csv,long value) {
        return textUtils.containsCSVLong(csv,value);
    }

    long eventTimeMillis(GroupMessageEvent event) {
        return textUtils.eventTimeMillis(event);
    }

    String senderName(GroupMessageEvent event) {
        return textUtils.senderName(event);
    }

    String openingOf(String text) {
        return textUtils.openingOf(text);
    }

    int longestCommonRun(String left,String right) {
        return textUtils.longestCommonRun(left,right);
    }

    double similarity(String left,String right) {
        return textUtils.similarity(left,right);
    }

    Set<String> bigrams(String text) {
        return textUtils.bigrams(text);
    }

    String normalizeForSimilarity(String text) {
        return textUtils.normalizeForSimilarity(text);
    }

    boolean sleepQuietly(long millis) {
        return textUtils.sleepQuietly(millis);
    }


    JSONObject parseJson(String content) {
        return jsonUtils.parseJson(content);
    }

    JSONObject message(String role,String content) {
        return jsonUtils.message(role,content);
    }


    boolean hasImageContent(JSONArray message) {
        return imageContextService.hasImageContent(message);
    }

    boolean hasMeaningfulText(JSONArray message) {
        return imageContextService.hasMeaningfulText(message);
    }

    RecentImage rememberImageContext(GroupMessageEvent event,long groupID) {
        return imageContextService.rememberImageContext(event,groupID);
    }

    RecentImage findRecentImageContext(long groupID,long userID,String content) {
        return imageContextService.findRecentImageContext(groupID,userID,content);
    }

    JSONObject imageParams(RecentImage recent) {
        return imageContextService.imageParams(recent);
    }

    JSONObject imageData(RecentImage recent) {
        return imageContextService.imageData(recent);
    }

    String imageSummary(RecentImage recent) {
        return imageContextService.imageSummary(recent);
    }

    String recentImageKey(long groupID,long userID) {
        return imageContextService.recentImageKey(groupID,userID);
    }

    boolean shouldUnderstandImages(GroupMessageEvent event,String content,
                                   boolean direct,boolean sameUserContinuation) {
        return imageContextService.shouldUnderstandImages(event,content,direct,sameUserContinuation);
    }

    boolean hasStickerContent(JSONArray message) {
        return imageContextService.hasStickerContent(message);
    }

    void dispatchImageUnderstanding(GroupMessageEvent event,long groupID,
                                    RecentImage imageContext,boolean hasSticker,
                                    boolean direct,boolean sameUserContinuation) {
        imageContextService.dispatchImageUnderstanding(event,groupID,imageContext,hasSticker,
                direct,sameUserContinuation);
    }

    String enrichImageContent(GroupMessageEvent event,String context,long groupID,
                              RecentImage currentImage) {
        return imageContextService.enrichImageContent(event,context,groupID,currentImage);
    }


    StickerMarkerResult extractStickerMarker(String text) {
        return stickerService.extractStickerMarker(text);
    }

    String stickerPrompt() {
        return stickerService.stickerPrompt();
    }

    void rememberStickerEmotion(long groupID,long userID,String emotion) {
        stickerService.rememberStickerEmotion(groupID,userID,emotion);
    }

    String recentStickerEmotion(long groupID,long userID) {
        return stickerService.recentStickerEmotion(groupID,userID);
    }

    String mergeStickerEmotion(String content,String emotion) {
        return stickerService.mergeStickerEmotion(content,emotion);
    }


    boolean isAddressedToOtherRole(GroupMessageEvent event,String content,long selfID) {
        return addressService.isAddressedToOtherRole(event,content,selfID);
    }

    boolean isMultiRoleAddress(GroupMessageEvent event,String content,long selfID) {
        return addressService.isMultiRoleAddress(event,content,selfID);
    }

    boolean mentionsSelfRole(GroupMessageEvent event,String content,long selfID) {
        return addressService.mentionsSelfRole(event,content,selfID);
    }

    boolean mentionsOtherMember(GroupMessageEvent event,long selfID) {
        return addressService.mentionsOtherMember(event,selfID);
    }

    boolean isDirect(GroupMessageEvent event,String content,long selfID) {
        return addressService.isDirect(event,content,selfID);
    }

    boolean isQuotingSelf(GroupMessageEvent event,long selfID) {
        return addressService.isQuotingSelf(event,selfID);
    }

    long quotedMessageID(GroupMessageEvent event) {
        return addressService.quotedMessageID(event);
    }

    String quotedText(long groupID,GroupMessageEvent event) {
        return addressService.quotedText(groupID,event);
    }

    boolean isMentioningSelf(GroupMessageEvent event,long selfID) {
        return addressService.isMentioningSelf(event,selfID);
    }

    String relationshipLabel(GroupMessageEvent event,boolean otherRoleBot) {
        return addressService.relationshipLabel(event,otherRoleBot);
    }

    boolean isOtherRoleBot(GroupMessageEvent event,long selfID) {
        return addressService.isOtherRoleBot(event,selfID);
    }

    boolean isRoleParticipantUser(long userID) {
        return addressService.isRoleParticipantUser(userID);
    }


    int countMessage(long groupID) {
        return rateLimitService.countMessage(groupID);
    }

    boolean rateLimited(long groupID) {
        return rateLimitService.rateLimited(groupID);
    }

    boolean canReply(long groupID) {
        return rateLimitService.canReply(groupID);
    }

    boolean shouldSuppressRepeat(long groupID,String reply) {
        return rateLimitService.shouldSuppressRepeat(groupID,reply);
    }

    boolean isGroupActive(long groupID) {
        return rateLimitService.isGroupActive(groupID);
    }

    int updateOtherRoleMessageStreak(long groupID,boolean otherRoleBot) {
        return rateLimitService.updateOtherRoleMessageStreak(groupID,otherRoleBot);
    }

    boolean justRepliedToSameUser(long groupID,long userID) {
        return rateLimitService.justRepliedToSameUser(groupID,userID);
    }

    boolean isContinuation(long groupID,long userID) {
        return rateLimitService.isContinuation(groupID,userID);
    }


    String longMemoryText(long groupID,long userID,String query) {
        return memoryQueryService.longMemoryText(groupID,userID,query);
    }

    JSONArray longMemories(long groupID) {
        return memoryQueryService.longMemories(groupID);
    }

    JSONArray longMemories(long groupID,boolean excludeBlacklisted) {
        return memoryQueryService.longMemories(groupID,excludeBlacklisted);
    }

    String shortSummary(long groupID) {
        return memoryQueryService.shortSummary(groupID);
    }

    MemoryCursor memoryCursor(long groupID) {
        return memoryQueryService.memoryCursor(groupID);
    }

    int memoryCount(long groupID) {
        return memoryQueryService.memoryCount(groupID);
    }

    List<JSONObject> limitMemoryRows(List<JSONObject> rows,int maxChars) {
        return memoryQueryService.limitMemoryRows(rows,maxChars);
    }

    StorageService storage() {
        return plugin.getServer().getStorage();
    }

}