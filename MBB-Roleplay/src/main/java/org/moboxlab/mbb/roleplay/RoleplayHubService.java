package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import org.moboxlab.moboxbot.API.PluginService;

/**
 * 与 MBB-BotHub 协作
 *
 * 提供两件事：
 * 1. 同群多角色共享识图结果，避免每个 bot 对同一张图各识别一次；
 * 2. 同群回合权租约，避免两个 bot 抢答同一条消息。
 *
 * hub 未安装或未连接时全部降级为本地行为，不阻塞主流程。
 */
public class RoleplayHubService {
    private final RoleplayService service;
    private volatile String localBotID;

    public RoleplayHubService(RoleplayService service) {
        this.service = service;
    }

    PluginService hub() {
        if (service.plugin() == null || service.plugin().getServer() == null) return null;
        return service.plugin().getServer().getPluginManager().getService("MBB-BotHub");
    }

    boolean available() {
        if (!service.config().hubEnable) return false;
        PluginService hub = hub();
        if (hub == null) return false;
        JSONObject status = hub.call("status",null);
        return status != null && status.getBooleanValue("available");
    }

    /**
     * 共享识图：先查 hub 缓存，未命中则认领，认领成功自己识图并广播，
     * 认领失败等待其他 bot 的结果，等待超时才回退自己识图。
     */
    JSONObject describe(PluginService vision,JSONObject params,String key) {
        if (vision == null) return null;
        PluginService hub = hub();
        if (!service.config().hubEnable || !service.config().hubVisionShareEnable
                || hub == null || key == null || key.trim().isEmpty()) {
            return vision.call("describe",params);
        }
        JSONObject status = hub.call("status",null);
        if (status == null || !status.getBooleanValue("available")) {
            return vision.call("describe",params);
        }
        String kind = service.safe(params == null ? "" : params.getString("kind"));
        JSONArray aliases = visionAliases(params);
        JSONObject hit = lookup(hub,key,aliases);
        if (hit != null) {
            service.plugin().getLogger().sendInfo("[识图] 命中 hub 共享缓存 key="+key
                    +" 来源="+service.safe(hit.getString("hubSource"))
                    +" 描述="+service.shortText(service.safe(hit.getString("summary")),60));
            return hit;
        }
        JSONObject claim = hub.call("vision_claim",keyParams(key));
        boolean granted = claim != null && claim.getBooleanValue("granted");
        if (!granted) {
            JSONObject waitParams = keyParams(key);
            waitParams.put("aliases",aliases);
            waitParams.put("waitMs",service.config().hubVisionWaitMs);
            JSONObject waited = hub.call("vision_wait",waitParams);
            if (waited != null && waited.getBooleanValue("status")
                    && (waited.containsKey("summary") || waited.containsKey("description"))) {
                service.plugin().getLogger().sendInfo("[识图] 复用其他 bot 的识图结果 key="+key
                        +" 描述="+service.shortText(service.safe(waited.getString("summary")),60));
                return waited;
            }
            service.plugin().getLogger().sendWarn("[识图] 等待 hub 识图结果超时，回退本地识图 key="+key);
        }
        JSONObject result = vision.call("describe",params);
        if (result != null && result.getBooleanValue("status")) {
            JSONObject publishParams = keyParams(key);
            publishParams.put("kind",kind);
            publishParams.put("result",result);
            publishParams.put("aliases",aliases);
            hub.call("vision_publish",publishParams);
        } else if (granted) {
            hub.call("vision_release",keyParams(key));
        }
        return result;
    }

    /**
     * 认领同群回合权。hub 不可用时直接放行，保持原行为。
     */
    TurnResult claimTurn(long groupID,String turnKey,int score,boolean multi) {
        TurnResult turn = new TurnResult();
        if (!service.config().hubEnable || !service.config().hubTurnShareEnable) return turn;
        PluginService hub = hub();
        if (hub == null || turnKey == null || turnKey.trim().isEmpty()) return turn;
        JSONObject status = hub.call("status",null);
        if (status == null || !status.getBooleanValue("available")) return turn;
        JSONObject params = new JSONObject(true);
        params.put("groupID",groupID);
        params.put("turnKey",turnKey);
        params.put("score",score);
        params.put("multi",multi);
        JSONObject result = hub.call("turn_claim",params);
        if (result == null || !result.getBooleanValue("status")) return turn;
        turn.shared = true;
        turn.granted = result.getBooleanValue("granted");
        turn.staggerMs = result.getLongValue("staggerMs");
        if (!turn.granted) {
            service.plugin().getLogger().sendInfo("[协作] 群"+groupID+" 回合权归 "
                    +service.safe(result.getString("ownerID"))+"（分数 "
                    +result.getIntValue("winnerScore")+"），本轮不接话");
        } else if (turn.staggerMs > 0) {
            service.plugin().getLogger().sendInfo("[协作] 群"+groupID
                    +" 双方都被点名，错峰 "+turn.staggerMs+"ms 后再生成");
        }
        return turn;
    }

    /**
     * 生成完成后、发送前再确认一次回合权还归自己。
     * 生成期间可能有更高优先级的角色（比如被点名的那个）把回合抢走。
     */
    boolean turnStillMine(long groupID,String turnKey) {
        if (!service.config().hubEnable || !service.config().hubTurnShareEnable) return true;
        PluginService hub = hub();
        if (hub == null || turnKey == null || turnKey.trim().isEmpty()) return true;
        JSONObject params = new JSONObject(true);
        params.put("groupID",groupID);
        params.put("turnKey",turnKey);
        JSONObject result = hub.call("turn_status",params);
        if (result == null || !result.getBooleanValue("status")) return true;
        String owner = service.safe(result.getString("ownerID"));
        if (owner.isEmpty()) return true;
        String me = localBotID(hub);
        if (me.isEmpty()) return true;
        return me.equals(owner);
    }

    /**
     * 回合权分数：被点名 100 / 被引用 80 / 延续自己参与的线程 60 / 泛群聊插话 40
     */
    int turnScore(RoleplayRouteDecision decision,boolean direct) {
        if (direct) return 100;
        if (decision != null && decision.quoteRequired) return 80;
        String addressed = decision == null ? "" : service.safe(decision.addressed);
        if ("thread".equals(addressed)) return 60;
        return 40;
    }

    void typingStart(long groupID,String turnKey) {
        typing(groupID,turnKey,"start");
    }

    void typingStop(long groupID,String turnKey) {
        typing(groupID,turnKey,"stop");
    }

    private void typing(long groupID,String turnKey,String state) {
        if (!service.config().hubEnable || !service.config().hubTypingBroadcastEnable) return;
        PluginService hub = hub();
        if (hub == null) return;
        JSONObject params = new JSONObject(true);
        params.put("groupID",groupID);
        params.put("turnKey",turnKey == null ? "" : turnKey);
        params.put("state",state);
        hub.call("typing",params);
    }

    private String localBotID(PluginService hub) {
        if (localBotID != null) return localBotID;
        JSONObject status = hub.call("status",null);
        String id = status == null ? "" : service.safe(status.getString("botID"));
        localBotID = id;
        return id;
    }

    void turnRelease(long groupID,String turnKey) {
        if (!service.config().hubEnable || !service.config().hubTurnShareEnable) return;
        PluginService hub = hub();
        if (hub == null || turnKey == null || turnKey.trim().isEmpty()) return;
        JSONObject params = new JSONObject(true);
        params.put("groupID",groupID);
        params.put("turnKey",turnKey);
        hub.call("turn_release",params);
    }

    static class TurnResult {
        boolean granted = true;
        long staggerMs = 0L;
        boolean shared = false;
    }

    /**
     * 跨进程任务去重：返回 true 表示可以创建，false 表示别的 bot 已经建过同一个任务
     */
    boolean claimTask(String key) {
        if (!service.config().hubEnable || !service.config().hubTaskDedupEnable) return true;
        PluginService hub = hub();
        if (hub == null || key == null || key.trim().isEmpty()) return true;
        JSONObject status = hub.call("status",null);
        if (status == null || !status.getBooleanValue("available")) return true;
        JSONObject params = new JSONObject(true);
        params.put("key",key.trim());
        JSONObject result = hub.call("task_claim",params);
        if (result == null || !result.getBooleanValue("status")) return true;
        boolean granted = result.getBooleanValue("granted");
        if (!granted) {
            service.plugin().getLogger().sendInfo("[协作] 任务已由 "
                    +service.safe(result.getString("ownerID"))+" 创建，跳过重复创建");
        }
        return granted;
    }

    /**
     * 机器人互聊熔断：返回 true 表示可以继续接话
     */
    boolean botChainAllow(long groupID) {
        if (!service.config().hubEnable || !service.config().hubChatGuardEnable) return true;
        PluginService hub = hub();
        if (hub == null) return true;
        JSONObject status = hub.call("status",null);
        if (status == null || !status.getBooleanValue("available")) return true;
        JSONObject params = new JSONObject(true);
        params.put("groupID",groupID);
        params.put("botToBot",true);
        JSONObject result = hub.call("chat_guard",params);
        if (result == null || !result.getBooleanValue("status")) return true;
        boolean allow = result.getBooleanValue("allow");
        if (!allow) {
            service.plugin().getLogger().sendInfo("[协作] 群"+groupID
                    +" 机器人互聊触发熔断，本轮不接话 原因="+service.safe(result.getString("reason")));
        }
        return allow;
    }

    /**
     * 真人发言后把同群的互聊计数清零
     */
    void botChainReset(long groupID) {
        if (!service.config().hubEnable || !service.config().hubChatGuardEnable) return;
        PluginService hub = hub();
        if (hub == null) return;
        JSONObject params = new JSONObject(true);
        params.put("groupID",groupID);
        params.put("botToBot",false);
        hub.call("chat_guard",params);
    }

    private JSONArray visionAliases(JSONObject params) {
        JSONArray aliases = new JSONArray();
        if (params == null) return aliases;
        addAlias(aliases,"unique",params.getString("fileUnique"));
        addAlias(aliases,"file",params.getString("file"));
        addAlias(aliases,"url",params.getString("url"));
        return aliases;
    }

    private void addAlias(JSONArray aliases,String prefix,String value) {
        String text = service.safe(value);
        if (text.isEmpty()) return;
        String alias = prefix+":"+text;
        if (!aliases.contains(alias)) aliases.add(alias);
    }

    private JSONObject lookup(PluginService hub,String key,JSONArray aliases) {
        JSONObject params = keyParams(key);
        if (aliases != null && !aliases.isEmpty()) params.put("aliases",aliases);
        JSONObject lookup = hub.call("vision_lookup",params);
        if (lookup == null || !lookup.getBooleanValue("hit")) return null;
        return lookup.getJSONObject("result");
    }

    private JSONObject keyParams(String key) {
        JSONObject params = new JSONObject(true);
        params.put("key",key);
        return params;
    }
}
