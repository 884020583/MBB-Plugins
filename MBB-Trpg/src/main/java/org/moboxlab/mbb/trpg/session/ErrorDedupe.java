package org.moboxlab.mbb.trpg.session;

import java.util.HashMap;
import java.util.Map;

/**
 * 同群同类错误去重（规格 §5.6）：10 秒窗口内同类拒绝只回一次。
 *
 * <p>只对「每群同类」计数，不同用户命中同类错误时同样合并，符合规格口径。</p>
 */
public final class ErrorDedupe {

    private final Map<String, Long> lastReply = new HashMap<String, Long>();

    /**
     * 申请一次回复机会。
     *
     * @return true 表示本次应回复；false 表示窗口内已回复过、本次静默
     */
    public boolean claim(long groupId, SessionReject reject, long now, long windowMillis) {
        if (reject == null) {
            return true;
        }
        String key = groupId + ":" + reject.name();
        Long last = lastReply.get(key);
        if (last != null && windowMillis > 0L && now - last.longValue() < windowMillis) {
            return false;
        }
        lastReply.put(key, Long.valueOf(now));
        return true;
    }

    /** 清空登记（停用与测试用）。 */
    public void clear() {
        lastReply.clear();
    }
}
