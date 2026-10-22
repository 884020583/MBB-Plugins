package org.moboxlab.mbb.trpg.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;

import org.moboxlab.mbb.trpg.config.TrpgConfig;
import org.moboxlab.mbb.trpg.rule.RandomSource;
import org.moboxlab.mbb.trpg.rule.RuleRegistry;
import org.moboxlab.mbb.trpg.rule.RuleSet;
import org.moboxlab.mbb.trpg.script.Script;
import org.moboxlab.mbb.trpg.script.ScriptCatalog;
import org.moboxlab.mbb.trpg.script.ScriptInfo;
import org.moboxlab.mbb.trpg.script.ScriptSource;
import org.moboxlab.mbb.trpg.script.Scene;
import org.moboxlab.mbb.trpg.store.CardData;
import org.moboxlab.mbb.trpg.store.LogRow;
import org.moboxlab.mbb.trpg.store.MemberRow;
import org.moboxlab.mbb.trpg.store.SessionRow;
import org.moboxlab.mbb.trpg.store.SessionStore;

import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * 局面的唯一写入口（规格 §5）：四态迁移、群锁、统一判定顺序、收尾编排与启动恢复。
 *
 * <p>判定顺序固定为「是否群内 → 权限 → 状态 → 参数 → 冷却 → 执行」；黑名单在消息入口最前端，
 * 不进入本类。惰性过期（T5 / T6）在读取状态的同一临界区内先执行，且不受请求者权限影响。
 * 所有写操作在群锁内完成，事件在解锁后统一发布（临界区不含网络发送）。</p>
 *
 * <p>场景、邀请、接管、跳过与检定类迁移由 S7 / S8 在此之上落地；本切片只暴露
 * {@code sceneIndex} 与 {@link #aiState(SessionActor, boolean)} 等子状态写接口。</p>
 */
public final class SessionService {

    private static final int MILLIS_PER_SECOND = 1000;
    private static final String ACTION_START = "start";
    private static final String ACTION_JOIN = "join";
    private static final String ACTION_LEAVE = "leave";
    private static final String ACTION_BEGIN = "begin";
    private static final String ACTION_STATUS = "status";
    private static final String ACTION_AI = "ai";
    private static final String ACTION_END = "end";
    private static final String ACTION_RESUME = "resume";
    private static final String ACTION_KP_LEFT = "kp-left";
    private static final String EVENT_CREATE = "create";
    private static final String EVENT_JOIN = "join";
    private static final String EVENT_LEAVE = "leave";
    private static final String EVENT_BEGIN = "begin";
    private static final String EVENT_RESUME = "resume";

    private final SessionStore store;
    private final ScriptCatalog catalog;
    private final Clock clock;
    private final TrpgConfig config;
    private final SessionEventPublisher publisher;
    private final PluginLogger logger;
    private final GroupLockRegistry locks;
    private final CooldownRegistry cooldowns;
    private final ErrorDedupe dedupe;
    private final SessionArchiver archiver;
    private final SessionRecoveryService recovery;
    private final SessionReader reader;
    /** 角色卡指令（pc / pc reroll / pc set）。 */
    private final SessionCardService cardService;

    /** 启动后尚未确认恢复的局（内存事实，不落库）；只包含进行中的局。 */
    private final Set<Long> pendingRecovery = ConcurrentHashMap.newKeySet();
    /** 结束二次确认的时间戳，key = 群号:局号:发起者（不同局与不同发起者互不合并）。 */
    private final Map<String, Long> confirmations = new ConcurrentHashMap<String, Long>();

    public SessionService(SessionStore store, ScriptCatalog catalog, Clock clock, RandomSource random,
                          TrpgConfig config, SessionEventPublisher publisher, PluginLogger logger) {
        this(store, catalog, clock, random, config, publisher, logger,
                new GroupLockRegistry(clock), new CooldownRegistry(), new ErrorDedupe());
    }

    /**
     * 生产装配：与其它指令服务共用同一份群锁、冷却与去重登记。
     *
     * <p>共用是为了让「同群同类错误 10 秒只回一次」（规范 §3.2）与各指令的冷却窗口
     * 跨 /trpg 与 /r 一起生效；测试可注入假登记。</p>
     */
    public SessionService(SessionStore store, ScriptCatalog catalog, Clock clock, RandomSource random,
                          TrpgConfig config, SessionEventPublisher publisher, PluginLogger logger,
                          GroupLockRegistry locks, CooldownRegistry cooldowns, ErrorDedupe dedupe) {
        if (store == null) {
            throw new IllegalArgumentException("存储端口不能为空");
        }
        if (catalog == null) {
            throw new IllegalArgumentException("剧本库不能为空");
        }
        if (clock == null) {
            throw new IllegalArgumentException("时间端口不能为空");
        }
        if (random == null) {
            throw new IllegalArgumentException("随机源不能为空");
        }
        if (config == null) {
            throw new IllegalArgumentException("配置不能为空");
        }
        if (locks == null || cooldowns == null || dedupe == null) {
            throw new IllegalArgumentException("并发登记不能为空");
        }
        this.store = store;
        this.catalog = catalog;
        this.clock = clock;
        this.config = config;
        this.publisher = publisher == null ? SessionEventPublisher.NONE : publisher;
        this.logger = logger;
        this.locks = locks;
        this.cooldowns = cooldowns;
        this.dedupe = dedupe;
        this.archiver = new SessionArchiver(store, new GrowthSettler(store, random, logger),
                new SummaryResolver(catalog), logger);
        this.recovery = new SessionRecoveryService(store, archiver, locks, clock, publisher, logger,
                pendingRecovery);
        this.reader = new SessionReader(store, archiver, locks, clock, config, publisher, logger,
                this::isRecovered);
        this.cardService = new SessionCardService(this, store, cooldowns, config);
    }

    /** 请求者不能为空：与构造函数一样用 IllegalArgumentException 表达契约违例。 */
    static SessionActor requireActor(SessionActor actor) {
        if (actor == null) {
            throw new IllegalArgumentException("请求者不能为空");
        }
        return actor;
    }

    /** 查看状态：任意身份可执行；读到过期局时先惰性归档。 */
    public SessionResult status(SessionActor actor) {
        if (!requireActor(actor).inGroup()) {
            return SessionResult.rejected(SessionReject.NOT_IN_GROUP, "请在群内使用");
        }
        return withGroupLock(actor, ACTION_STATUS, new LockedAction() {

            @Override
            public SessionResult run(Turn turn) {
                SessionRow row = turn.active();
                if (row == null) {
                    return SessionResult.ok(SessionSnapshots.none(turn.groupId), "本群暂无局");
                }
                SessionResult cooling = cooling(ACTION_STATUS, turn, config.defaultCooldownSeconds());
                if (cooling != null) {
                    return cooling;
                }
                cooldowns.mark(ACTION_STATUS, actor.userId(), turn.groupId, turn.now);
                return SessionResult.ok(turn.snapshot(row), "");
            }
        });
    }

    /** 开团（T1）：建局并插入 KP 成员行。 */
    public SessionResult start(SessionActor actor, final String scriptId) {
        if (!requireActor(actor).inGroup()) {
            return SessionResult.rejected(SessionReject.NOT_IN_GROUP, "请在群内使用");
        }
        return withGroupLock(actor, ACTION_START, new LockedAction() {

            @Override
            public SessionResult run(Turn turn) {
                SessionRow active = turn.active();
                if (active != null) {
                    return reject(turn, SessionReject.STATE_UNAVAILABLE, "本群已有未归档的局");
                }
                String targetScript = scriptId == null ? "" : scriptId.trim();
                if (targetScript.isEmpty()) {
                    targetScript = defaultScriptId();
                }
                if (loadScript(targetScript) == null) {
                    return reject(turn, SessionReject.BAD_ARGUMENT, "剧本不存在：" + targetScript);
                }
                SessionResult cooling = cooling(ACTION_START, turn, config.startCooldownSeconds());
                if (cooling != null) {
                    return cooling;
                }
                long now = turn.now;
                SessionRow row = SessionRow.builder()
                        .groupId(turn.groupId)
                        .state(SessionState.RECRUITING)
                        .kpUserId(actor.userId())
                        .scriptId(targetScript)
                        .ruleSetId(resolveRuleSetId())
                        .lastActivityAt(now)
                        .createTime(now)
                        .updateTime(now)
                        .build();
                long sessionId = store.insertSession(row);
                if (sessionId == 0L) {
                    if (store.findActiveByGroup(turn.groupId) != null) {
                        return reject(turn, SessionReject.STATE_UNAVAILABLE, "本群已有未归档的局");
                    }
                    error("建局写入未生效：group=" + turn.groupId);
                    return SessionResult.storageFailure("建局写入未生效，请稍后重试");
                }
                MemberRow kp = MemberRow.builder()
                        .sessionId(sessionId)
                        .userId(actor.userId())
                        .card(CardData.empty())
                        .successSkills(new ArrayList<String>())
                        .joinedAt(now)
                        .createTime(now)
                        .updateTime(now)
                        .build();
                if (store.insertMember(kp) == 0L) {
                    int cancelled = store.casTransition(sessionId, SessionState.RECRUITING,
                            SessionState.ENDED, 0L, now, "cancelled", false, "", now);
                    error("建局成员写入未生效，已取消：session=" + sessionId + "，cas=" + cancelled);
                    if (cancelled < 0) {
                        warn("建局取消未生效，留给启动恢复按无成员归档：session=" + sessionId);
                    }
                    return SessionResult.storageFailure("建局成员写入未生效，已取消本局");
                }
                appendLog(sessionId, EVENT_CREATE, actor.userId(), targetScript, now);
                cooldowns.mark(ACTION_START, actor.userId(), turn.groupId, now);
                SessionRow created = store.findSession(sessionId);
                return SessionResult.ok(turn.snapshot(created == null ? rebuild(row, sessionId) : created),
                        "已创建招募中的局：" + targetScript);
            }
        });
    }

    /** 入局：招募期可加入；离场成员重进清离场标记，角色卡保留。 */
    public SessionResult join(SessionActor actor, final CardData card) {
        if (!requireActor(actor).inGroup()) {
            return SessionResult.rejected(SessionReject.NOT_IN_GROUP, "请在群内使用");
        }
        return withGroupLock(actor, ACTION_JOIN, new LockedAction() {

            @Override
            public SessionResult run(Turn turn) {
                SessionRow row = turn.active();
                if (row == null) {
                    return reject(turn, SessionReject.STATE_UNAVAILABLE, "本群暂无局");
                }
                if (row.state() != SessionState.RECRUITING) {
                    return reject(turn, SessionReject.STATE_UNAVAILABLE,
                            "本群已开局，进行中加入需由 KP 邀请");
                }
                MemberRow existing = turn.member(row, actor.userId());
                if (existing != null && existing.leftAt() <= 0L) {
                    return reject(turn, SessionReject.MEMBER_STATE, "你已在局内");
                }
                SessionResult cooling = cooling(ACTION_JOIN, turn, config.defaultCooldownSeconds());
                if (cooling != null) {
                    return cooling;
                }
                long now = turn.now;
                if (existing != null) {
                    int affected = store.updateMember(existing.toBuilder()
                            .leftAt(0L).updateTime(now).build());
                    if (affected < 0) {
                        return SessionResult.storageFailure("重新入局写入未生效");
                    }
                    if (affected == 0) {
                        MemberRow again = turn.member(row, actor.userId());
                        if (again == null || again.leftAt() > 0L) {
                            return SessionResult.storageFailure("重新入局写入未生效");
                        }
                    }
                    appendLog(row.id(), EVENT_JOIN, actor.userId(), "", now);
                    cooldowns.mark(ACTION_JOIN, actor.userId(), turn.groupId, now);
                    return SessionResult.ok(turn.reload(row), "已重新入局，角色卡保留");
                }
                if (actor.userId() == row.kpUserId()) {
                    return reject(turn, SessionReject.MEMBER_STATE, "你已是本局 KP");
                }
                CardData actual = card == null ? CardData.empty() : card;
                MemberRow member = MemberRow.builder()
                        .sessionId(row.id())
                        .userId(actor.userId())
                        .card(actual)
                        .successSkills(new ArrayList<String>())
                        .joinedAt(now)
                        .createTime(now)
                        .updateTime(now)
                        .build();
                if (store.insertMember(member) == 0L) {
                    return SessionResult.storageFailure("入局写入未生效，请稍后重试");
                }
                appendLog(row.id(), EVENT_JOIN, actor.userId(), "", now);
                cooldowns.mark(ACTION_JOIN, actor.userId(), turn.groupId, now);
                return SessionResult.ok(turn.reload(row), "已入局");
            }
        });
    }

    /** 离场：保留角色卡与局籍；KP 不能离场。 */
    public SessionResult leave(SessionActor actor) {
        if (!requireActor(actor).inGroup()) {
            return SessionResult.rejected(SessionReject.NOT_IN_GROUP, "请在群内使用");
        }
        return withGroupLock(actor, ACTION_LEAVE, new LockedAction() {

            @Override
            public SessionResult run(Turn turn) {
                SessionRow row = turn.active();
                if (row == null) {
                    return reject(turn, SessionReject.STATE_UNAVAILABLE, "本群暂无局");
                }
                MemberRow member = turn.member(row, actor.userId());
                if (member == null || member.leftAt() > 0L) {
                    return reject(turn, SessionReject.MEMBER_STATE, "你不在局内");
                }
                if (actor.userId() == row.kpUserId()) {
                    return reject(turn, SessionReject.MEMBER_STATE,
                            "KP 不能离场，请结束本局或交接后再离开");
                }
                SessionResult cooling = cooling(ACTION_LEAVE, turn, config.defaultCooldownSeconds());
                if (cooling != null) {
                    return cooling;
                }
                long now = turn.now;
                int affected = store.updateMember(member.toBuilder().leftAt(now).updateTime(now).build());
                if (affected < 0) {
                    return SessionResult.storageFailure("离场写入未生效");
                }
                if (affected == 0) {
                    MemberRow again = turn.member(row, actor.userId());
                    if (again != null && again.leftAt() <= 0L) {
                        return SessionResult.storageFailure("离场写入未生效");
                    }
                }
                appendLog(row.id(), EVENT_LEAVE, actor.userId(), "", now);
                cooldowns.mark(ACTION_LEAVE, actor.userId(), turn.groupId, now);
                return SessionResult.ok(turn.reload(row), "已离场，角色卡保留");
            }
        });
    }

    /** 开局（T2）：仅 KP；除 KP 外至少一名局内成员；剧本存在；切到剧本开场场景。 */
    public SessionResult begin(SessionActor actor) {
        if (!requireActor(actor).inGroup()) {
            return SessionResult.rejected(SessionReject.NOT_IN_GROUP, "请在群内使用");
        }
        return withGroupLock(actor, ACTION_BEGIN, new LockedAction() {

            @Override
            public SessionResult run(Turn turn) {
                SessionRow row = turn.active();
                if (row == null) {
                    return reject(turn, SessionReject.STATE_UNAVAILABLE, "本群暂无局");
                }
                if (actor.userId() != row.kpUserId()) {
                    return reject(turn, SessionReject.NO_PERMISSION, "只有 KP 能开局");
                }
                if (row.state() != SessionState.RECRUITING) {
                    return reject(turn, SessionReject.STATE_UNAVAILABLE, "本局不在招募阶段");
                }
                List<MemberRow> members = store.loadMembers(row.id());
                if (insideCountExcluding(members, row.kpUserId()) < 1) {
                    return reject(turn, SessionReject.STATE_UNAVAILABLE,
                            "还需要至少一名其他成员才能开局");
                }
                Script script = loadScript(row.scriptId());
                if (script == null) {
                    return reject(turn, SessionReject.STATE_UNAVAILABLE, "剧本不可用：" + row.scriptId());
                }
                SessionResult cooling = cooling(ACTION_BEGIN, turn, config.defaultCooldownSeconds());
                if (cooling != null) {
                    return cooling;
                }
                long now = turn.now;
                int cas = store.casTransition(row.id(), SessionState.RECRUITING, SessionState.RUNNING,
                        now, 0L, "", false, "", now);
                if (cas < 0) {
                    return SessionResult.storageFailure("开局写入未生效，请稍后重试");
                }
                if (cas == 0) {
                    SessionRow again = store.findSession(row.id());
                    if (again != null && again.state() != SessionState.RECRUITING) {
                        return reject(turn, SessionReject.STATE_UNAVAILABLE, "本局不在招募阶段");
                    }
                    return SessionResult.storageFailure("开局写入未生效，请稍后重试");
                }
                Scene opening = script.sceneAt(0);
                if (store.casScene(row.id(), SessionState.RUNNING, 0,
                        opening == null ? "" : opening.title(), now) < 0) {
                    warn("开局场景写入未生效：session=" + row.id());
                }
                appendLog(row.id(), EVENT_BEGIN, actor.userId(), "", now);
                cooldowns.mark(ACTION_BEGIN, actor.userId(), turn.groupId, now);
                return SessionResult.ok(turn.reload(row), "本局已开始");
            }
        });
    }

    /** 结束（T3 / T4）：KP 或管理员，同一发起者 30 秒内二次执行生效。 */
    public SessionResult end(SessionActor actor) {
        if (!requireActor(actor).inGroup()) {
            return SessionResult.rejected(SessionReject.NOT_IN_GROUP, "请在群内使用");
        }
        return withGroupLock(actor, ACTION_END, new LockedAction() {

            @Override
            public SessionResult run(Turn turn) {
                SessionRow row = turn.active();
                if (row == null) {
                    return reject(turn, SessionReject.STATE_UNAVAILABLE, "本群暂无局");
                }
                boolean kpActor = actor.userId() == row.kpUserId();
                if (!kpActor && !actor.groupAdmin()) {
                    return reject(turn, SessionReject.NO_PERMISSION, "只有 KP 或群管理员能结束本局");
                }
                SessionResult cooling = cooling(ACTION_END, turn, config.defaultCooldownSeconds());
                if (cooling != null) {
                    return cooling;
                }
                long window = config.confirmWindowSeconds() * (long) MILLIS_PER_SECOND;
                String key = turn.groupId + ":" + row.id() + ":" + actor.userId();
                Long first = confirmations.get(key);
                if (first == null || turn.now - first.longValue() > window) {
                    confirmations.put(key, Long.valueOf(turn.now));
                    // 首次确认也计一次冷却，避免 1 秒内连发两次 end 直接生效（矩阵 end 1s）。
                    cooldowns.mark(ACTION_END, actor.userId(), turn.groupId, turn.now);
                    return SessionResult.awaitingConfirmation(turn.snapshot(row),
                            "结束本局需要确认：请在 " + config.confirmWindowSeconds() + " 秒内再次执行");
                }
                confirmations.remove(key);
                String reason;
                if (!kpActor) {
                    reason = "forced";
                } else {
                    reason = row.state() == SessionState.RUNNING ? "finished" : "cancelled";
                }
                boolean settleGrowth = row.state() == SessionState.RUNNING;
                ArchiveResult archived = archiver.archive(row, reason, settleGrowth, turn.now);
                if (archived.status() == ArchiveResult.Status.FAILED) {
                    confirmations.put(key, Long.valueOf(turn.now));
                    return finish(turn, archived, "本局已结束");
                }
                cooldowns.mark(ACTION_END, actor.userId(), turn.groupId, turn.now);
                return finish(turn, archived, "本局已结束");
            }
        });
    }

    /** 恢复确认（T8）：仅 KP，且只在本机重启后未确认时生效。 */
    public SessionResult resume(SessionActor actor) {
        if (!requireActor(actor).inGroup()) {
            return SessionResult.rejected(SessionReject.NOT_IN_GROUP, "请在群内使用");
        }
        return withGroupLock(actor, ACTION_RESUME, new LockedAction() {

            @Override
            public SessionResult run(Turn turn) {
                SessionRow row = turn.active();
                if (row == null) {
                    return reject(turn, SessionReject.STATE_UNAVAILABLE, "本群暂无局");
                }
                if (actor.userId() != row.kpUserId()) {
                    return reject(turn, SessionReject.NO_PERMISSION, "只有 KP 能确认恢复");
                }
                if (!pendingRecovery.contains(Long.valueOf(row.id()))) {
                    return reject(turn, SessionReject.STATE_UNAVAILABLE, "本局无需恢复确认");
                }
                SessionResult cooling = cooling(ACTION_RESUME, turn, config.defaultCooldownSeconds());
                if (cooling != null) {
                    return cooling;
                }
                pendingRecovery.remove(Long.valueOf(row.id()));
                appendLog(row.id(), EVENT_RESUME, actor.userId(), "", turn.now);
                cooldowns.mark(ACTION_RESUME, actor.userId(), turn.groupId, turn.now);
                return SessionResult.ok(turn.reload(row), "已确认恢复，可以继续");
            }
        });
    }

    /**
     * AI 子状态写接口（供 S9）：KP 可开可关，群管理员只能关，机器人管理员可开可关。
     *
     * <p>招募期与进行中都可调（§6.1 / §6.3 / §9.2）；结束的局不在活跃查询范围。</p>
     */
    public SessionResult aiState(SessionActor actor, final boolean enabled) {
        if (!requireActor(actor).inGroup()) {
            return SessionResult.rejected(SessionReject.NOT_IN_GROUP, "请在群内使用");
        }
        return withGroupLock(actor, ACTION_AI, new LockedAction() {

            @Override
            public SessionResult run(Turn turn) {
                SessionRow row = turn.active();
                if (row == null) {
                    return reject(turn, SessionReject.STATE_UNAVAILABLE, "本群暂无局");
                }
                boolean kpActor = actor.userId() == row.kpUserId();
                boolean allowed = kpActor || actor.botAdmin() || (!enabled && actor.groupAdmin());
                if (!allowed) {
                    return reject(turn, SessionReject.NO_PERMISSION,
                            enabled ? "只有 KP 能开启 AI" : "只有 KP 或群管理员能关闭 AI");
                }
                if (row.state() != SessionState.RECRUITING && row.state() != SessionState.RUNNING) {
                    return reject(turn, SessionReject.STATE_UNAVAILABLE, "本局不在可调 AI 的状态");
                }
                SessionResult cooling = cooling(ACTION_AI, turn, config.defaultCooldownSeconds());
                if (cooling != null) {
                    return cooling;
                }
                if (row.aiEnabled() == enabled) {
                    return SessionResult.ok(turn.snapshot(row), enabled ? "AI 已是开启状态" : "AI 已是关闭状态");
                }
                long now = turn.now;
                int cas = store.casAiState(row.id(), row.state(), enabled, row.aiSuspendedUntil(),
                        row.aiFailStreak(), now);
                if (cas < 0) {
                    return SessionResult.storageFailure("AI 状态写入未生效，请稍后重试");
                }
                if (cas == 0) {
                    SessionRow again = store.findSession(row.id());
                    if (again == null || again.aiEnabled() != enabled) {
                        return SessionResult.storageFailure("AI 状态写入未生效，请稍后重试");
                    }
                }
                cooldowns.mark(ACTION_AI, actor.userId(), turn.groupId, now);
                return SessionResult.ok(turn.reload(row), enabled ? "已开启 AI" : "已关闭 AI");
            }
        });
    }

    /** KP 离群（T7）：招募期按 cancelled、进行中按 kp_left 归档。 */
    public SessionResult kpLeft(final long groupId, final long kpUserId) {
        if (groupId <= 0L || kpUserId <= 0L) {
            return SessionResult.rejected(SessionReject.BAD_ARGUMENT, "群号与用户号必须大于 0");
        }
        SessionActor actor = new SessionActor(kpUserId, groupId, false);
        return withGroupLock(actor, ACTION_KP_LEFT, new LockedAction() {

            @Override
            public SessionResult run(Turn turn) {
                SessionRow row = turn.active();
                if (row == null) {
                    return SessionResult.ok(SessionSnapshots.none(groupId), "本群暂无局");
                }
                if (row.kpUserId() != kpUserId) {
                    return SessionResult.ok(turn.snapshot(row), "KP 未变更");
                }
                boolean settleGrowth = row.state() == SessionState.RUNNING;
                return finish(turn, archiver.archive(row, "kp_left", settleGrowth, turn.now), "KP 已离群");
            }
        });
    }

    /**
     * 角色卡指令的前置放行判定：只判群内、局面存在、请求者在本局、非 KP。
     *
     * <p>供指令层在回报「参数不对」之前调用，保证 §5.5 的判定顺序（状态与权限先于参数）；
     * 放行时不执行动作，因此不写冷却。</p>
     */
    public SessionResult cardGate(SessionActor actor) {
        return cardGate(actor, false);
    }

    /**
     * 角色卡动作的前置放行判定：在 {@link #cardGate(SessionActor)} 之上再拒掉 KP。
     *
     * <p>pc reroll / pc set 对 KP 属于成员状态不满足，必须排在参数错误之前（§5.5）。</p>
     */
    public SessionResult cardActionGate(SessionActor actor) {
        return cardGate(actor, true);
    }

    private SessionResult cardGate(SessionActor actor, boolean rejectKp) {
        if (!requireActor(actor).inGroup()) {
            return SessionResult.rejected(SessionReject.NOT_IN_GROUP, "请在群内使用");
        }
        return cardService.gate(actor, rejectKp);
    }

    /** 查看角色卡（§6.2）：本局成员可看自己（含离场），KP 可看任意成员。 */
    public SessionResult viewCard(SessionActor actor, long targetUserId) {
        return cardService.viewCard(actor, targetUserId);
    }

    /**
     * 重掷角色卡（§6.1 pc reroll）：仅招募期、玩家本人；新卡由指令层生成后传入。
     *
     * <p>按 §7.5，重掷把 SAN 重置为 POW 属于生成侧职责，本方法只整卡替换并落库。</p>
     */
    public SessionResult rerollCard(SessionActor actor, CardData card) {
        return cardService.rerollCard(actor, card);
    }

    /**
     * 微调角色卡（§6.1 pc set）：仅招募期、玩家本人；只接受八属性、每项 15–90 的整数。
     *
     * <p>改 POW 不联动 SAN；SAN / HP / MP 与技能、物品都不在这里改（§7.5）。</p>
     */
    public SessionResult setAttribute(SessionActor actor, String field, String rawValue) {
        return cardService.setAttribute(actor, field, rawValue);
    }

    /** 读取本群当前局面；无活跃局返回空快照（读状态会先惰性归档过期局）。 */
    public SessionSnapshot readSnapshot(long groupId) {
        return reader.readSnapshot(groupId);
    }

    /** 刷新局内活动（§5.8），返回是否真正写了活动时间。 */
    public boolean touchActivity(long groupId, long userId) {
        return reader.touchActivity(groupId, userId);
    }

    /** 启动恢复（§5.7）：加载未归档局并重建内存局面，返回已加载快照。 */
    public List<SessionSnapshot> recover() {
        return recovery.recover();
    }

    /** 启动补做（§5.3 / §5.7）：对未结算的 ENDED 局补做成长与摘要，返回成功局数。 */
    public int settlePending() {
        return recovery.settlePending();
    }

    /** 执行一段需要群锁的动作。 */
    SessionResult withGroupLock(SessionActor actor, String action, LockedAction body) {
        long groupId = actor.groupId();
        long now = clock.nowMillis();
        if (!locks.tryLock(groupId)) {
            return rejectRaw(groupId, SessionReject.BUSY, "本群正在处理上一条", now);
        }
        Turn turn = new Turn(actor, now);
        SessionResult result;
        try {
            result = body.run(turn);
        } catch (StorageAbort abort) {
            warn("局面操作中止：" + abort.getMessage());
            result = SessionResult.storageFailure(abort.getMessage());
        } catch (RuntimeException error) {
            error("局面操作异常：" + action + "：group=" + groupId + "，原因=" + error);
            result = SessionResult.storageFailure("局面操作异常，请稍后重试");
        } finally {
            locks.unlock(groupId);
        }
        for (SessionEvent event : turn.events) {
            publish(event);
        }
        return result;
    }

    /** 收尾结果转成对外结果，并登记战报事件。 */
    private SessionResult finish(Turn turn, ArchiveResult archived, String detail) {
        if (archived.status() == ArchiveResult.Status.FAILED) {
            return SessionResult.storageFailure("本局收尾未生效，请稍后重试");
        }
        turn.events.add(SessionEvent.ended(archived.snapshot(), archived.endReason(), archived.growth()));
        if (archived.status() == ArchiveResult.Status.DEFERRED) {
            warn("本局已结束但结算未完成，待启动补做：" + archived.detail());
        }
        return SessionResult.ended(archived.snapshot(), archived.endReason(), archived.growth());
    }

    SessionResult cooling(String action, Turn turn, int cooldownSeconds) {
        long remaining = cooldowns.remainingMillis(action, turn.actor.userId(), turn.groupId,
                turn.now, cooldownSeconds * (long) MILLIS_PER_SECOND);
        if (remaining <= 0L) {
            return null;
        }
        long seconds = (remaining + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND;
        return reject(turn, SessionReject.COOLING, "还有 " + seconds + " 秒冷却");
    }

    SessionResult reject(Turn turn, SessionReject reject, String detail) {
        return rejectRaw(turn.groupId, reject, detail, turn.now);
    }

    private SessionResult rejectRaw(long groupId, SessionReject reject, String detail, long now) {
        boolean claimed = dedupe.claim(groupId, reject, now,
                config.errorDedupeSeconds() * (long) MILLIS_PER_SECOND);
        return SessionResult.rejected(reject, detail).withSuppressed(!claimed);
    }

    private void publish(SessionEvent event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException error) {
            error("局面事件发布失败：type=" + event.type() + "，原因=" + error);
        }
    }

    private String resolveRuleSetId() {
        RuleSet ruleSet = RuleRegistry.getOrDefault(config.ruleSetId());
        return ruleSet == null ? RuleRegistry.BUILTIN_RULE_SET_ID : ruleSet.id();
    }

    private Script loadScript(String scriptId) {
        if (scriptId == null || scriptId.isEmpty()) {
            return null;
        }
        Lock readLock = catalog.acquireReadLock();
        readLock.lock();
        try {
            return catalog.get(scriptId);
        } finally {
            readLock.unlock();
        }
    }

    private String defaultScriptId() {
        Lock readLock = catalog.acquireReadLock();
        readLock.lock();
        try {
            List<ScriptInfo> infos = catalog.list();
            if (infos == null || infos.isEmpty()) {
                return "";
            }
            for (ScriptInfo info : infos) {
                if (info.source() == ScriptSource.BUILTIN) {
                    return info.id();
                }
            }
            return infos.get(0).id();
        } finally {
            readLock.unlock();
        }
    }

    private boolean isRecovered(long sessionId) {
        return !pendingRecovery.contains(Long.valueOf(sessionId));
    }

    private void appendLog(long sessionId, String eventType, long actorId, String content, long now) {
        long seq = store.maxLogSeq(sessionId) + 1L;
        int affected = store.appendLog(new LogRow(0L, sessionId, seq, eventType, actorId, content, now));
        if (affected != 1) {
            warn("事件日志写入未生效：session=" + sessionId + "，seq=" + seq + "，type=" + eventType);
        }
    }

    private static MemberRow findMember(List<MemberRow> members, long userId) {
        for (MemberRow member : members) {
            if (member != null && member.userId() == userId) {
                return member;
            }
        }
        return null;
    }

    private static int insideCountExcluding(List<MemberRow> members, long excludedUserId) {
        int count = 0;
        for (MemberRow member : members) {
            if (member != null && member.userId() != excludedUserId && member.leftAt() <= 0L) {
                count++;
            }
        }
        return count;
    }

    private static SessionRow rebuild(SessionRow row, long sessionId) {
        return row.toBuilder().id(sessionId).build();
    }

    private void warn(String message) {
        if (logger != null) {
            logger.sendWarn("[MBB-Trpg] " + message);
        }
    }

    private void error(String message) {
        if (logger != null) {
            logger.sendError("[MBB-Trpg] " + message);
        }
    }

    /** 群锁内执行的动作。 */
    interface LockedAction {

        SessionResult run(Turn turn);
    }

    /** 一次请求的群锁上下文：持有当次时间、请求者与待发布事件。 */
    final class Turn {

        final SessionActor actor;
        final long now;
        final long groupId;
        final List<SessionEvent> events = new ArrayList<SessionEvent>();

        Turn(SessionActor actor, long now) {
            this.actor = actor;
            this.now = now;
            this.groupId = actor.groupId();
        }

        /** 读本群活跃局；已过期则先惰性归档，返回归档后的活跃局（无则 null）。 */
        SessionRow active() {
            SessionRow row = store.findActiveByGroup(groupId);
            int rounds = 0;
            while (row != null && archiver.isExpired(row, now, config)) {
                if (++rounds > SessionArchiver.maxExpiryRounds()) {
                    throw new StorageAbort("惰性过期反复失效：group=" + groupId);
                }
                boolean settleGrowth = row.state() == SessionState.RUNNING;
                ArchiveResult archived = archiver.archive(row, "expired", settleGrowth, now);
                if (archived.status() == ArchiveResult.Status.FAILED) {
                    throw new StorageAbort("惰性过期归档未生效：session=" + row.id());
                }
                if (archived.snapshot() != null) {
                    events.add(SessionEvent.ended(archived.snapshot(), archived.endReason(),
                            archived.growth()));
                }
                row = store.findActiveByGroup(groupId);
            }
            return row;
        }

        /** 写后回读：重读本局行再构造快照，避免拿陈旧行对外。 */
        SessionSnapshot reload(SessionRow row) {
            if (row == null) {
                return SessionSnapshots.none(groupId);
            }
            SessionRow current = store.findSession(row.id());
            return snapshot(current == null ? row : current);
        }

        /** 读取本局成员行（不存在返回 null）。 */
        MemberRow member(SessionRow row, long userId) {
            return findMember(store.loadMembers(row.id()), userId);
        }

        SessionSnapshot snapshot(SessionRow row) {
            if (row == null) {
                return SessionSnapshots.none(groupId);
            }
            return archiver.snapshot(row, isRecovered(row.id()));
        }
    }

    /** 存储前置失败：中止本步，转为对外存储异常。 */
    private static final class StorageAbort extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private StorageAbort(String message) {
            super(message);
        }
    }
}