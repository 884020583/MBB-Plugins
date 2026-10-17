package org.moboxlab.mbb.roleplay;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 拟人节奏
 *
 * 根据回复长度、当前时段和群活跃度计算发送前延迟，让回复更像真人打字。
 */
public class RoleplayPacingService {
    private final RoleplayService service;

    public RoleplayPacingService(RoleplayService service) {
        this.service = service;
    }

    public long delayMillis(long groupID,long userID,String content,String reply) {
        if (!service.config().pacingEnable || service.safe(reply).trim().isEmpty()) return 0L;
        int length = service.safe(reply).replaceAll("\\s+","").length();
        long delay = service.config().pacingBaseMs
                + (long)length * service.config().pacingPerCharMs;
        int hour = hour();
        if (hour >= service.config().pacingNightStartHour
                && hour < service.config().pacingNightEndHour) {
            delay = (long)(delay * service.config().pacingNightMultiplier);
        }
        if (service.getAttentionService().score(groupID) >= service.config().pacingActiveThreshold) {
            delay = (long)(delay * service.config().pacingActiveMultiplier);
        }
        double jitter = 0.85 + Math.random() * 0.30;
        delay = (long)(delay * jitter);
        return Math.max(0L,Math.min(service.config().pacingMaxMs,delay));
    }

    public boolean sleep(long groupID,long userID,String content,String reply) {
        long delay = delayMillis(groupID,userID,content,reply);
        if (delay <= 0) return true;
        return service.sleepQuietly(delay);
    }

    private int hour() {
        try {
            String zoneName = service.config().timeZone == null || service.config().timeZone.trim().isEmpty()
                    ? "Asia/Shanghai" : service.config().timeZone.trim();
            TimeZone zone = TimeZone.getTimeZone(zoneName);
            return Calendar.getInstance(zone,Locale.CHINA).get(Calendar.HOUR_OF_DAY);
        } catch (Exception e) {
            return Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        }
    }
}
