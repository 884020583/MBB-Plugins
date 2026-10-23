package org.moboxlab.mbb.bothub;

import com.alibaba.fastjson.JSONObject;

import java.util.concurrent.atomic.AtomicLong;

/**
 * BotHub 运行统计
 *
 * 全是单调递增的计数器，用来回答「协作到底省了多少事」：
 * 共享识图命中一次就等于省掉一次 AI 识图调用。
 */
public class BotHubStats {
    public final AtomicLong peersJoined = new AtomicLong();
    public final AtomicLong peersLeft = new AtomicLong();
    public final AtomicLong requests = new AtomicLong();
    public final AtomicLong visionLookupHit = new AtomicLong();
    public final AtomicLong visionLookupMiss = new AtomicLong();
    public final AtomicLong visionClaimGranted = new AtomicLong();
    public final AtomicLong visionClaimDenied = new AtomicLong();
    public final AtomicLong visionPublished = new AtomicLong();
    public final AtomicLong visionBackfilled = new AtomicLong();
    public final AtomicLong turnGranted = new AtomicLong();
    public final AtomicLong turnDenied = new AtomicLong();
    public final AtomicLong taskGranted = new AtomicLong();
    public final AtomicLong taskDeduped = new AtomicLong();
    public final AtomicLong chatGuardBlocked = new AtomicLong();
    public final AtomicLong chatGuardReset = new AtomicLong();
    public final AtomicLong typingStarts = new AtomicLong();
    public final AtomicLong typingStops = new AtomicLong();
    public final AtomicLong turnPreempted = new AtomicLong();
    public final AtomicLong staggeredReplies = new AtomicLong();
    public final AtomicLong broadcasts = new AtomicLong();

    /**
     * 因为共享缓存而省下来的识图次数
     */
    public long savedVisionCalls() {
        return visionLookupHit.get();
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject(true);
        json.put("peersJoined",peersJoined.get());
        json.put("peersLeft",peersLeft.get());
        json.put("requests",requests.get());
        json.put("visionLookupHit",visionLookupHit.get());
        json.put("visionLookupMiss",visionLookupMiss.get());
        json.put("visionClaimGranted",visionClaimGranted.get());
        json.put("visionClaimDenied",visionClaimDenied.get());
        json.put("visionPublished",visionPublished.get());
        json.put("visionBackfilled",visionBackfilled.get());
        json.put("visionSaved",savedVisionCalls());
        json.put("turnGranted",turnGranted.get());
        json.put("turnDenied",turnDenied.get());
        json.put("taskGranted",taskGranted.get());
        json.put("taskDeduped",taskDeduped.get());
        json.put("chatGuardBlocked",chatGuardBlocked.get());
        json.put("chatGuardReset",chatGuardReset.get());
        json.put("typingStarts",typingStarts.get());
        json.put("typingStops",typingStops.get());
        json.put("turnPreempted",turnPreempted.get());
        json.put("staggeredReplies",staggeredReplies.get());
        json.put("broadcasts",broadcasts.get());
        return json;
    }
}
