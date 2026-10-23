package org.moboxlab.mbb.trpg.listener;

import org.moboxlab.moboxbot.API.Event.EventHandler;
import org.moboxlab.moboxbot.API.Event.EventPriority;
import org.moboxlab.moboxbot.API.Event.GroupMessageEvent;
import org.moboxlab.moboxbot.API.Event.Listener;
import org.moboxlab.moboxbot.API.Event.PrivateMessageEvent;
import org.moboxlab.moboxbot.API.PluginLogger;
import org.moboxlab.mbb.trpg.present.TrpgMessageTemplates;
import org.moboxlab.mbb.trpg.present.TrpgPresenter;
import org.moboxlab.mbb.trpg.script.ScriptErrorCodes;
import org.moboxlab.mbb.trpg.script.ScriptException;
import org.moboxlab.mbb.trpg.script.ScriptImportResult;
import org.moboxlab.mbb.trpg.script.ScriptImportService;
import org.moboxlab.mbb.trpg.script.ScriptImportSessions;
import org.moboxlab.mbb.trpg.script.ScriptIssue;
import org.moboxlab.mbb.trpg.script.ScriptValidationException;
import org.moboxlab.mbb.trpg.script.ScriptValidationResult;
import org.moboxlab.mbb.trpg.session.SessionActor;
import org.moboxlab.mbb.trpg.session.SessionService;
import org.moboxlab.mbb.trpg.store.BlacklistService;

/**
 * 群/私聊消息入口：黑名单静默拦截 → 导入暂存吞消息（含控制字）→ 局内活动刷新。
 *
 * <p>本监听器不做任何业务判定：命中黑名单只把消息取消掉、不回复；导入暂存期间把该用户
 * 同一会话的消息原文交给 {@link ScriptImportService#append}，并就地处理
 * {@code /trpg script import end|cancel} 两个控制字（§S10：控制字优先于指令分发）。
 * 其余群消息只用于刷新局内活动，60 秒节流由 {@link SessionService#touchActivity} 内部判定。</p>
 */
public final class TrpgListener implements Listener {

    private final SessionService sessions;
    private final BlacklistService blacklist;
    private final ScriptImportService imports;
    private final TrpgPresenter presenter;
    private final PluginLogger logger;

    /**
     * @param imports 导入暂存服务；S10 之前传 null，此时不吞消息
     */
    public TrpgListener(SessionService sessions, BlacklistService blacklist,
                        ScriptImportService imports, PluginLogger logger) {
        this(sessions, blacklist, imports, null, logger);
    }

    /**
     * @param presenter 回执出口；为 null 时只吞消息、不回复（测试与降级装配用）
     */
    public TrpgListener(SessionService sessions, BlacklistService blacklist,
                        ScriptImportService imports, TrpgPresenter presenter, PluginLogger logger) {
        if (sessions == null) {
            throw new IllegalArgumentException("局面服务不能为空");
        }
        this.sessions = sessions;
        this.blacklist = blacklist;
        this.imports = imports;
        this.presenter = presenter;
        this.logger = logger;
    }

    /** 群消息入口（§5.4 的判定位次：黑名单 → 导入暂存 → 局内活动）。 */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onGroupMessage(GroupMessageEvent event) {
        if (event == null) {
            return;
        }
        long groupId = event.getGroupID();
        long userId = event.getUserID();
        if (groupId <= 0L || userId <= 0L) {
            return;
        }
        if (blacklist != null && blacklist.contains(groupId, userId)) {
            // 黑名单命中：静默丢弃，不产生任何回执。
            event.setCancelled(true);
            return;
        }
        String raw = event.getRawMessage();
        if (imports != null && imports.hasPending(groupId, userId)) {
            if (consumeImport(raw, groupId, userId)) {
                event.setCancelled(true);
                return;
            }
        }
        sessions.touchActivity(groupId, userId);
    }

    /** 私聊消息入口：私聊只承载导入暂存，其余消息交给指令层。 */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onPrivateMessage(PrivateMessageEvent event) {
        if (event == null) {
            return;
        }
        long userId = event.getUserID();
        if (userId <= 0L || imports == null) {
            return;
        }
        long groupId = SessionActor.PRIVATE_GROUP_ID;
        if (!imports.hasPending(groupId, userId)) {
            return;
        }
        if (consumeImport(event.getRawMessage(), groupId, userId)) {
            event.setCancelled(true);
        }
    }

    /**
     * 处理一条暂存期消息：控制字就地结束/取消，其余一律当片段追加。
     *
     * @return 是否已消费（消费后调用方必须取消事件，避免再进指令分发）
     */
    private boolean consumeImport(String raw, long groupId, long userId) {
        if (ScriptImportSessions.isEndControl(raw)) {
            finishImport(groupId, userId);
            return true;
        }
        if (ScriptImportSessions.isCancelControl(raw)) {
            cancelImport(groupId, userId);
            return true;
        }
        try {
            imports.append(groupId, userId, raw);
            return true;
        } catch (ScriptException error) {
            reply(groupId, userId, errorText(error));
            return true;
        } catch (RuntimeException error) {
            warn("导入暂存追加异常：群=" + groupId + "，用户=" + userId + "，原因="
                    + error.getMessage());
            return false;
        }
    }

    /** 结束导入并保存；解析未完成时保留暂存并提示进度，校验失败则展示错误。 */
    private void finishImport(long groupId, long userId) {
        try {
            ScriptImportResult result = imports.end(groupId, userId);
            reply(groupId, userId, TrpgMessageTemplates.scriptImportSuccess(result));
        } catch (ScriptValidationException error) {
            if (imports.hasPending(groupId, userId)) {
                ScriptValidationResult validation = error.validation();
                ScriptIssue first = validation == null || validation.errors().isEmpty()
                        ? null : validation.errors().get(0);
                reply(groupId, userId, TrpgMessageTemplates.scriptImportIssue(
                        imports.pendingBytes(groupId, userId), imports.maxBytes(), first));
            } else {
                reply(groupId, userId,
                        TrpgMessageTemplates.scriptImportRejected(error.validation()));
            }
        } catch (ScriptException error) {
            reply(groupId, userId, errorText(error));
        } catch (RuntimeException error) {
            warn("导入结束异常：群=" + groupId + "，用户=" + userId + "，原因=" + error.getMessage());
            reply(groupId, userId, TrpgMessageTemplates.error("导入结束失败：" + error.getMessage(),
                    "请稍后重试，或改用手工分段重新导入。"));
        }
    }

    /** 取消导入：未写库，直接回执。 */
    private void cancelImport(long groupId, long userId) {
        try {
            imports.cancel(groupId, userId);
            reply(groupId, userId, TrpgMessageTemplates.scriptImportCancelled());
        } catch (ScriptException error) {
            reply(groupId, userId, errorText(error));
        } catch (RuntimeException error) {
            warn("导入取消失常：群=" + groupId + "，用户=" + userId + "，原因=" + error.getMessage());
        }
    }

    private static String errorText(ScriptException error) {
        String code = error == null || error.code() == null || error.code().isEmpty()
                ? "E_IMPORT_STATE" : error.code();
        String message = error == null ? "导入暂存不可用" : error.getMessage();
        return TrpgMessageTemplates.error(code + "：" + message,
                "请发送 /trpg script import 重新开始，或 /trpg help 查看用法。");
    }

    /** 按会话类型回执：群内回群、私聊回私聊。 */
    private void reply(long groupId, long userId, String message) {
        if (presenter == null || message == null) {
            return;
        }
        try {
            if (groupId > SessionActor.PRIVATE_GROUP_ID) {
                presenter.sendGroup(groupId, message);
            } else {
                presenter.sendPrivate(userId, message);
            }
        } catch (RuntimeException error) {
            warn("导入回执发送异常：" + error.getMessage());
        }
    }

    private void warn(String message) {
        if (logger != null) {
            logger.sendWarn("[MBB-Trpg] " + message);
        }
    }
}
