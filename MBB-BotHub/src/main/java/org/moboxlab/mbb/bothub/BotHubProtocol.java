package org.moboxlab.mbb.bothub;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;

/**
 * BotHub 通信协议
 *
 * 传输层是纯 TCP，每行一个 UTF-8 JSON（NDJSON），行内不出现裸换行。
 * 消息类型：
 *   client -> server：hello / ping / request / publish / bye
 *   server -> client：hello_ok / pong / response / event / error
 */
public class BotHubProtocol {
    public static final String TYPE_HELLO = "hello";
    public static final String TYPE_HELLO_OK = "hello_ok";
    public static final String TYPE_PING = "ping";
    public static final String TYPE_PONG = "pong";
    public static final String TYPE_REQUEST = "request";
    public static final String TYPE_RESPONSE = "response";
    public static final String TYPE_PUBLISH = "publish";
    public static final String TYPE_EVENT = "event";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_BYE = "bye";

    public static final String ACTION_STATUS = "status";
    public static final String ACTION_PEERS = "peers";
    public static final String ACTION_GROUP_PEERS = "group_peers";
    public static final String ACTION_VISION_LOOKUP = "vision_lookup";
    public static final String ACTION_VISION_CLAIM = "vision_claim";
    public static final String ACTION_VISION_PUBLISH = "vision_publish";
    public static final String ACTION_VISION_RELEASE = "vision_release";
    public static final String ACTION_VISION_CLEAR = "vision_clear";
    public static final String ACTION_TURN_CLAIM = "turn_claim";
    public static final String ACTION_TURN_RELEASE = "turn_release";
    public static final String ACTION_TURN_STATUS = "turn_status";
    public static final String ACTION_STATS = "stats";
    public static final String ACTION_TASK_CLAIM = "task_claim";
    public static final String ACTION_TASK_RELEASE = "task_release";
    public static final String ACTION_TASK_CLEAR = "task_clear";
    public static final String ACTION_CHAT_GUARD = "chat_guard";
    public static final String ACTION_CHAT_GUARD_RESET = "chat_guard_reset";
    public static final String ACTION_VISION_BACKFILL = "vision_backfill";
    public static final String ACTION_TYPING = "typing";
    public static final String ACTION_TYPING_STATUS = "typing_status";

    public static final String EVENT_PEER_JOIN = "peer_join";
    public static final String EVENT_PEER_LEAVE = "peer_leave";
    public static final String EVENT_VISION_RESULT = "vision_result";
    public static final String EVENT_TURN_GRANTED = "turn_granted";
    public static final String EVENT_TURN_RELEASED = "turn_released";
    public static final String EVENT_TASK_CLAIMED = "task_claimed";
    public static final String EVENT_CHAT_GUARD_BLOCKED = "chat_guard_blocked";
    public static final String EVENT_TYPING_STARTED = "typing_started";
    public static final String EVENT_TYPING_STOPPED = "typing_stopped";
    public static final String EVENT_TURN_PREEMPTED = "turn_preempted";

    /** 能力协商：client 在 hello 里上报自己支持哪些协作能力 */
    public static final String CAP_VISION = "vision";
    public static final String CAP_TURN = "turn";
    public static final String CAP_TASK = "task";
    public static final String CAP_CHAT_GUARD = "chat_guard";
    public static final String CAP_TYPING = "typing";

    public static JSONArray supportedCaps() {
        JSONArray caps = new JSONArray();
        caps.add(CAP_VISION);
        caps.add(CAP_TURN);
        caps.add(CAP_TASK);
        caps.add(CAP_CHAT_GUARD);
        caps.add(CAP_TYPING);
        return caps;
    }

    /**
     * 判断能力列表里是否包含某项。列表为空时按“全支持”处理，兼容老版本 client。
     */
    public static boolean supports(JSONArray caps,String cap) {
        if (caps == null || caps.isEmpty()) return true;
        if (cap == null || cap.isEmpty()) return true;
        for (Object item : caps) {
            if (item != null && cap.equalsIgnoreCase(String.valueOf(item).trim())) return true;
        }
        return false;
    }

    /**
     * 读取一行并解析为 JSON，遇到空行跳过；流结束返回 null。
     */
    public static JSONObject read(BufferedReader reader) throws IOException {
        while (true) {
            String line = reader.readLine();
            if (line == null) return null;
            if (line.trim().isEmpty()) continue;
            try {
                return JSONObject.parseObject(line);
            } catch (Exception e) {
                return error("bad_json",line);
            }
        }
    }

    /**
     * 写出一行 JSON 并立即 flush，行内换行被替换成空格。
     */
    public static void write(BufferedWriter writer,JSONObject message) throws IOException {
        String line = message == null ? "{}" : message.toJSONString();
        line = line.replace("\r"," ").replace("\n"," ");
        writer.write(line);
        writer.write("\n");
        writer.flush();
    }

    public static JSONObject message(String type) {
        JSONObject message = new JSONObject(true);
        message.put("type",type);
        message.put("ts",System.currentTimeMillis());
        return message;
    }

    public static JSONObject response(String id,boolean ok,JSONObject result,String error) {
        JSONObject message = message(TYPE_RESPONSE);
        message.put("id",id == null ? "" : id);
        message.put("ok",ok);
        if (result != null) message.put("result",result);
        if (error != null && !error.isEmpty()) message.put("error",error);
        return message;
    }

    public static JSONObject event(String event,String from,JSONObject data) {
        JSONObject message = message(TYPE_EVENT);
        message.put("event",event == null ? "" : event);
        message.put("from",from == null ? "" : from);
        if (data != null) message.put("data",data);
        return message;
    }

    public static JSONObject error(String error,String detail) {
        JSONObject message = message(TYPE_ERROR);
        message.put("error",error == null ? "" : error);
        if (detail != null && !detail.isEmpty()) message.put("detail",detail);
        return message;
    }

    public static JSONObject ok() {
        JSONObject result = new JSONObject(true);
        result.put("status",true);
        return result;
    }

    public static JSONObject fail(String message) {
        JSONObject result = new JSONObject(true);
        result.put("status",false);
        result.put("message",message == null ? "" : message);
        return result;
    }
}
