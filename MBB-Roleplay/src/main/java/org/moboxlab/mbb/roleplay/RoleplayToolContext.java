package org.moboxlab.mbb.roleplay;

/**
 * 工具执行时的会话上下文。
 */
public class RoleplayToolContext {
    public long groupID;
    public long userID;
    public long selfID;
    public long messageID;
    public String userName = "";
    public String relationship = "";
    public String message = "";
    public String channel = "group";
    public String privacy = "group";
}
