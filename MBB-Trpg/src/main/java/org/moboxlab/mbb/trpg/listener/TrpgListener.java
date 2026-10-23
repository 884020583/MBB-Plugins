package org.moboxlab.mbb.trpg.listener;

import org.moboxlab.moboxbot.API.Event.EventHandler;
import org.moboxlab.moboxbot.API.Event.EventPriority;
import org.moboxlab.moboxbot.API.Event.GroupMessageEvent;
import org.moboxlab.moboxbot.API.Event.Listener;
import org.moboxlab.moboxbot.API.PluginLogger;
import org.moboxlab.mbb.trpg.script.ScriptException;
import org.moboxlab.mbb.trpg.script.ScriptImportService;
import org.moboxlab.mbb.trpg.session.SessionService;
import org.moboxlab.mbb.trpg.store.BlacklistService;

/**
 * 群消息入口：黑名单静默拦截 → 导入暂存吞消息 → 局内活动刷新。
 *
 * <p>本监听器不做任何业务判定：命中黑名单只把消息取消掉、不回复；导入暂存期间把该用户
 * 同一会话的消息原文交给 {@link ScriptImportService#append}；其余群消息只用于刷新局内活动，
 * 60 秒节流由 {@link SessionService#touchActivity} 内部判定。</p>
 */
public final class TrpgListener implements Listener {

    private final SessionService sessions;
    private final BlacklistService blacklist;
    private final ScriptImportService imports;
    private final PluginLogger logger;

    /**
     * @param imports 导入暂存服务；S10 之前传 null，此时不吞消息
     */
    public TrpgListener(SessionService sessions, BlacklistService blacklist,
                        ScriptImportService imports, PluginLogger logger) {
        if (sessions == null) {
            throw new IllegalArgumentException("局面服务不能为空");
        }
        this.sessions = sessions;
        this.blacklist = blacklist;
        this.imports = imports;
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
        if (imports != null && imports.hasPending(groupId, userId)) {
            if (consumeImport(event, groupId, userId)) {
                event.setCancelled(true);
                return;
            }
        }
        sessions.touchActivity(groupId, userId);
    }

    /** 把消息原文追加到导入暂存；失败时只记日志并放行后续处理。 */
    private boolean consumeImport(GroupMessageEvent event, long groupId, long userId) {
        try {
            imports.append(groupId, userId, event.getRawMessage());
            return true;
        } catch (ScriptException error) {
            warn("导入暂存追加失败：群=" + groupId + "，用户=" + userId + "，原因="
                    + error.getMessage());
            return false;
        } catch (RuntimeException error) {
            warn("导入暂存追加异常：群=" + groupId + "，用户=" + userId + "，原因="
                    + error.getMessage());
            return false;
        }
    }

    private void warn(String message) {
        if (logger != null) {
            logger.sendWarn("[MBB-Trpg] " + message);
        }
    }
}
