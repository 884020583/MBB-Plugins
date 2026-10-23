package org.moboxlab.mbb.qq;

import org.moboxlab.moboxbot.API.Event.EventHandler;
import org.moboxlab.moboxbot.API.Event.EventPriority;
import org.moboxlab.moboxbot.API.Event.Listener;
import org.moboxlab.moboxbot.API.Event.RequestEvent;

/**
 * 只记录好友申请和群邀请，不自动同意或拒绝。
 */
public class QQListener implements Listener {
    private final QQService service;

    public QQListener(QQService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR,ignoreCancelled = false)
    public void onRequest(RequestEvent event) {
        if (service != null) service.recordRequest(event);
    }
}
