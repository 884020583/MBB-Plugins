package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONObject;

/**
 * 工具结果。data 给模型看，日志里可单独脱敏。
 */
public class RoleplayToolResult {
    public boolean success = true;
    public boolean endTurn = false;
    public String summary = "";
    public JSONObject data = new JSONObject(true);

    public static RoleplayToolResult ok(String summary) {
        RoleplayToolResult result = new RoleplayToolResult();
        result.summary = summary == null ? "" : summary;
        return result;
    }

    public static RoleplayToolResult failed(String summary) {
        RoleplayToolResult result = new RoleplayToolResult();
        result.success = false;
        result.summary = summary == null ? "" : summary;
        return result;
    }

    public JSONObject toJson() {
        JSONObject result = new JSONObject(true);
        result.put("status",success);
        result.put("summary",summary);
        result.put("data",data == null ? new JSONObject(true) : data);
        result.put("endTurn",endTurn);
        return result;
    }
}
