package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONObject;

/**
 * 可由模型调用的角色工具。
 */
public interface RoleplayTool {
    String name();

    String category();

    String description();

    JSONObject schema();

    boolean sideEffect();

    int maxCallsPerTurn();

    RoleplayToolResult execute(RoleplayToolContext context,JSONObject args);
}
