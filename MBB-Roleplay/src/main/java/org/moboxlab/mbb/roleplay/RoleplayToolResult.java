package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具结果。data 给模型看，日志里可单独脱敏。
 */
public class RoleplayToolResult {
    public boolean success = true;
    public boolean endTurn = false;
    public String summary = "";
    public JSONObject data = new JSONObject(true);
    public String trust = "internal";
    public final List<String> activateTools = new ArrayList<>();

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

    public static RoleplayToolResult untrusted(String summary) {
        RoleplayToolResult result = ok(summary);
        result.trust = "external-untrusted";
        return result;
    }

    public RoleplayToolResult expose(String... names) {
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.trim().isEmpty() && !activateTools.contains(name.trim())) {
                    activateTools.add(name.trim());
                }
            }
        }
        return this;
    }

    public JSONObject toJson() {
        JSONObject result = new JSONObject(true);
        result.put("status",success);
        result.put("summary",summary);
        result.put("data",data == null ? new JSONObject(true) : data);
        result.put("endTurn",endTurn);
        result.put("trust",trust);
        return result;
    }
}
