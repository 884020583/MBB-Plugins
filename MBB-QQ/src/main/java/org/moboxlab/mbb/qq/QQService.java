package org.moboxlab.mbb.qq;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.OneBot.MessageUtil;
import org.moboxlab.moboxbot.API.OneBot.OneBotClient;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * QQ 扩展动作服务。
 *
 * 只暴露经过选择和高层封装的 OneBot 动作，不向调用方暴露凭据、rkey、原始包或进程控制。
 */
public class QQService implements PluginService {
    private static final String AUDIT_TABLE = "plugin_mbb_qq_audit";
    private static final String QZONE_TABLE = "plugin_mbb_qq_qzone_post";
    private final Plugin plugin;
    private volatile QQConfig config;

    public QQService(Plugin plugin,QQConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public String getName() {
        return "MBB-QQ";
    }

    public void reload(QQConfig config) {
        this.config = config;
    }

    public void init() {
        storage().update("CREATE TABLE IF NOT EXISTS `"+AUDIT_TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`action` TEXT NOT NULL DEFAULT '',"
                + "`groupID` INTEGER NOT NULL DEFAULT 0,"
                + "`operatorID` INTEGER NOT NULL DEFAULT 0,"
                + "`targetID` INTEGER NOT NULL DEFAULT 0,"
                + "`summary` TEXT NOT NULL DEFAULT '',"
                + "`success` INTEGER NOT NULL DEFAULT 0,"
                + "`createTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        storage().update("CREATE INDEX IF NOT EXISTS `idx_mbb_qq_audit_group` "
                + "ON `"+AUDIT_TABLE+"` (`groupID`,`createTime`)");
        storage().update("CREATE TABLE IF NOT EXISTS `"+QZONE_TABLE+"` ("
                + "`ID` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`tid` TEXT NOT NULL DEFAULT '',"
                + "`content` TEXT NOT NULL DEFAULT '',"
                + "`images` TEXT NOT NULL DEFAULT '',"
                + "`ugcRight` INTEGER NOT NULL DEFAULT 64,"
                + "`createTime` INTEGER NOT NULL DEFAULT 0"
                + ")");
        cleanupAudit();
    }

    @Override
    public JSONObject call(String action,JSONObject params) {
        if (!config.enable) return error("MBB-QQ 当前已关闭");
        String name = action == null ? "" : action.trim();
        JSONObject input = params == null ? new JSONObject(true) : params;
        try {
            if ("group_info".equalsIgnoreCase(name)) return groupInfo(input);
            if ("member_info".equalsIgnoreCase(name)) return memberInfo(input);
            if ("group_history".equalsIgnoreCase(name)) return groupHistory(input);
            if ("message_detail".equalsIgnoreCase(name)) return messageDetail(input);
            if ("group_notices".equalsIgnoreCase(name)) return groupNotices(input);
            if ("mark_read".equalsIgnoreCase(name)) return markRead(input);
            if ("poke".equalsIgnoreCase(name)) return poke(input);
            if ("like".equalsIgnoreCase(name)) return like(input);
            if ("emoji_like".equalsIgnoreCase(name)) return emojiLike(input);
            if ("recall_own".equalsIgnoreCase(name)) return recallOwn(input);
            if ("send_image".equalsIgnoreCase(name)) return sendImage(input);
            if ("send_voice".equalsIgnoreCase(name)) return sendVoice(input);
            if ("set_own_group_card".equalsIgnoreCase(name)) return setOwnGroupCard(input);
            if ("qzone_post".equalsIgnoreCase(name)) return qzonePost(input);
            if ("qzone_delete".equalsIgnoreCase(name)) return qzoneDelete(input);
            if ("qzone_posts".equalsIgnoreCase(name)) return qzonePosts(input);
            if ("audit".equalsIgnoreCase(name)) return auditList(input);
            return error("不支持的 QQ 动作："+name);
        } catch (Exception e) {
            plugin.getLogger().sendWarn("[QQ] "+name+" 执行失败："+safe(e.getMessage()));
            return error(name+" 执行失败："+safe(e.getMessage()));
        }
    }

    private JSONObject groupInfo(JSONObject input) {
        if (!config.readToolsEnable) return error("QQ 只读工具未启用");
        long groupID = positive(input,"groupID");
        if (groupID <= 0) return error("缺少 groupID");
        JSONObject params = new JSONObject(true);
        params.put("group_id",String.valueOf(groupID));
        return action("group_info","get_group_info",params,input,0,0,"读取群信息");
    }

    private JSONObject memberInfo(JSONObject input) {
        if (!config.readToolsEnable) return error("QQ 只读工具未启用");
        long groupID = positive(input,"groupID");
        long userID = positive(input,"targetID");
        if (groupID <= 0 || userID <= 0) return error("缺少群号或 QQ");
        JSONObject params = new JSONObject(true);
        params.put("group_id",String.valueOf(groupID));
        params.put("user_id",String.valueOf(userID));
        params.put("no_cache",false);
        return action("member_info","get_group_member_info",params,input,groupID,userID,"读取群成员信息");
    }

    private JSONObject groupHistory(JSONObject input) {
        if (!config.readToolsEnable) return error("QQ 只读工具未启用");
        long groupID = positive(input,"groupID");
        int count = clamp(input.getIntValue("count"),1,50);
        if (groupID <= 0) return error("缺少群号");
        JSONObject params = new JSONObject(true);
        params.put("group_id",String.valueOf(groupID));
        String seq = safe(input.getString("messageSeq"));
        if (!seq.isEmpty()) params.put("message_seq",seq);
        params.put("count",count);
        params.put("reverse_order",false);
        params.put("disable_get_url",false);
        params.put("parse_mult_msg",true);
        return action("group_history","get_group_msg_history",params,input,groupID,0,
                "读取群历史 "+count+" 条");
    }

    private JSONObject messageDetail(JSONObject input) {
        if (!config.readToolsEnable) return error("QQ 只读工具未启用");
        String messageID = safe(input.getString("messageID"));
        if (messageID.isEmpty()) return error("缺少 messageID");
        long groupID = positive(input,"groupID");
        if (groupID > 0) {
            JSONObject detail = getMessage(messageID);
            if (detail == null || !sameGroup(detail,groupID)) {
                return error("消息不属于当前群");
            }
        }
        JSONObject params = new JSONObject(true);
        params.put("message_id",messageID);
        return action("message_detail","get_msg",params,input,groupID,0,"读取消息详情");
    }

    private JSONObject groupNotices(JSONObject input) {
        if (!config.readToolsEnable) return error("QQ 只读工具未启用");
        long groupID = positive(input,"groupID");
        if (groupID <= 0) return error("缺少群号");
        JSONObject params = new JSONObject(true);
        params.put("group_id",String.valueOf(groupID));
        return action("group_notices","get_group_notice",params,input,groupID,0,"读取群公告");
    }

    private JSONObject markRead(JSONObject input) {
        if (!config.readToolsEnable) return error("QQ 只读工具未启用");
        long groupID = positive(input,"groupID");
        if (groupID <= 0) return error("缺少群号");
        JSONObject params = new JSONObject(true);
        params.put("group_id",String.valueOf(groupID));
        return action("mark_read","mark_group_msg_as_read",params,input,groupID,0,"标记群消息已读");
    }

    private JSONObject poke(JSONObject input) {
        if (!config.interactionToolsEnable) return error("QQ 互动工具未启用");
        long groupID = positive(input,"groupID");
        long operatorID = positive(input,"operatorID");
        long targetID = positive(input,"targetID");
        if (groupID <= 0 || operatorID <= 0 || targetID <= 0) return error("缺少群号或目标 QQ");
        if (targetID != operatorID && !isOwnerOrAdmin(groupID,operatorID,input)) {
            return error("只能戳自己或由 owner/管理员指定目标");
        }
        if (!cooldown("poke:"+groupID+":"+targetID,config.pokeCooldownSecond)) {
            return error("戳一戳冷却中，请稍后再说");
        }
        JSONObject params = new JSONObject(true);
        params.put("group_id",String.valueOf(groupID));
        params.put("user_id",String.valueOf(operatorID));
        params.put("target_id",String.valueOf(targetID));
        JSONObject result = action("poke","group_poke",params,input,groupID,targetID,
                "戳一戳 "+targetID);
        if (result.getBooleanValue("status")) mark("poke:"+groupID+":"+targetID);
        return result;
    }

    private JSONObject like(JSONObject input) {
        if (!config.interactionToolsEnable) return error("QQ 互动工具未启用");
        long operatorID = positive(input,"operatorID");
        long targetID = positive(input,"targetID");
        int times = clamp(input.getIntValue("times"),1,config.likeMaxTimes);
        if (operatorID <= 0 || targetID <= 0) return error("缺少 QQ");
        if (targetID != operatorID && !isOwner(operatorID)) {
            return error("只能给自己或 owner 指定目标点赞");
        }
        if (!cooldown("like:"+targetID,config.likeCooldownSecond)) {
            return error("今天已经给该用户点过赞了");
        }
        JSONObject params = new JSONObject(true);
        params.put("user_id",String.valueOf(targetID));
        params.put("times",times);
        JSONObject result = action("like","send_like",params,input,0,targetID,
                "点赞 "+targetID+" x"+times);
        if (result.getBooleanValue("status")) mark("like:"+targetID);
        return result;
    }

    private JSONObject emojiLike(JSONObject input) {
        if (!config.interactionToolsEnable) return error("QQ 互动工具未启用");
        long groupID = positive(input,"groupID");
        String messageID = safe(input.getString("messageID"));
        String emojiID = safe(input.getString("emojiID"));
        if (groupID <= 0 || messageID.isEmpty() || emojiID.isEmpty()) {
            return error("缺少群号、消息 ID 或表情 ID");
        }
        if (!cooldown("emoji:"+messageID,config.emojiLikeCooldownSecond)) {
            return error("这条消息刚刚回应过，请稍后再试");
        }
        JSONObject detail = getMessage(messageID);
        if (detail == null || !sameGroup(detail,groupID)) {
            return error("消息不属于当前群");
        }
        JSONObject params = new JSONObject(true);
        params.put("message_id",messageID);
        params.put("emoji_id",emojiID);
        params.put("set",true);
        JSONObject result = action("emoji_like","set_msg_emoji_like",params,input,groupID,0,
                "表情回应 "+messageID);
        if (result.getBooleanValue("status")) mark("emoji:"+messageID);
        return result;
    }

    private JSONObject recallOwn(JSONObject input) {
        if (!config.interactionToolsEnable) return error("QQ 互动工具未启用");
        long groupID = positive(input,"groupID");
        String messageID = safe(input.getString("messageID"));
        if (groupID <= 0 || messageID.isEmpty()) return error("缺少群号或消息 ID");
        JSONObject detail = getMessage(messageID);
        if (detail == null || !sameGroup(detail,groupID)) return error("消息不属于当前群");
        long senderID = detail.getLongValue("user_id");
        long selfID = positive(input,"selfID");
        if (senderID <= 0 || senderID != selfID) return error("只能撤回机器人自己发送的消息");
        long time = detail.getLongValue("time") * 1000L;
        if (time > 0 && System.currentTimeMillis() - time > config.recallWindowSecond * 1000L) {
            return error("消息已超过可撤回时间");
        }
        JSONObject params = new JSONObject(true);
        params.put("message_id",messageID);
        return action("recall_own","delete_msg",params,input,groupID,0,"撤回自己的消息");
    }

    private JSONObject sendImage(JSONObject input) {
        if (!config.mediaToolsEnable) return error("QQ 媒体工具未启用");
        long groupID = positive(input,"groupID");
        String file = safe(input.getString("file"));
        if (groupID <= 0 || file.isEmpty()) return error("缺少群号或图片");
        if (!file.startsWith("base64://") && !validFileExtension(file,config.imageExtensions)) {
            return error("图片扩展名不在允许范围内");
        }
        if (!withinBase64Limit(file,config.imageMaxBytes)) return error("图片超过大小限制");
        JSONObject sent = client().sendGroupMessage(groupID,
                MessageUtil.message(MessageUtil.image(file)));
        return wrapSend("send_image",input,groupID,0,"发送图片",sent);
    }

    private JSONObject sendVoice(JSONObject input) {
        if (!config.mediaToolsEnable) return error("QQ 媒体工具未启用");
        long groupID = positive(input,"groupID");
        String file = safe(input.getString("file"));
        if (groupID <= 0 || file.isEmpty()) return error("缺少群号或语音");
        if (!file.startsWith("base64://") && !validFileExtension(file,config.voiceExtensions)) {
            return error("语音扩展名不在允许范围内");
        }
        if (!withinBase64Limit(file,config.voiceMaxBytes)) return error("语音超过大小限制");
        JSONObject sent = client().sendGroupMessage(groupID,
                MessageUtil.message(MessageUtil.record(file)));
        return wrapSend("send_voice",input,groupID,0,"发送语音",sent);
    }

    private JSONObject setOwnGroupCard(JSONObject input) {
        if (!config.interactionToolsEnable) return error("QQ 互动工具未启用");
        long groupID = positive(input,"groupID");
        long selfID = positive(input,"selfID");
        long operatorID = positive(input,"operatorID");
        String card = safe(input.getString("card"));
        if (groupID <= 0 || selfID <= 0) return error("缺少群号或机器人 QQ");
        if (!isOwnerOrAdmin(groupID,operatorID,input)) {
            return error("修改机器人群名片需要 owner 或群管理员权限");
        }
        if (card.length() > 30) return error("群名片最多 30 个字符");
        if (!cooldown("card:"+groupID+":"+selfID,config.groupCardCooldownSecond)) {
            return error("群名片修改冷却中");
        }
        JSONObject params = new JSONObject(true);
        params.put("group_id",String.valueOf(groupID));
        params.put("user_id",String.valueOf(selfID));
        params.put("card",card);
        JSONObject result = action("set_own_group_card","set_group_card",params,input,
                groupID,selfID,"修改自己的群名片");
        if (result.getBooleanValue("status")) mark("card:"+groupID+":"+selfID);
        return result;
    }

    private JSONObject qzonePost(JSONObject input) {
        if (!config.qzoneEnable) return error("Qzone 工具未启用");
        long operatorID = positive(input,"operatorID");
        if (!isOwner(operatorID)) return error("管理 Qzone 仅 owner 可用");
        String content = safe(input.getString("content")).trim();
        if (content.isEmpty()) return error("说说内容不能为空");
        if (content.length() > config.qzoneContentMaxChars) {
            return error("说说内容最多 "+config.qzoneContentMaxChars+" 个字符");
        }
        JSONArray images = input.getJSONArray("images");
        if (images != null && images.size() > config.qzoneMaxImages) {
            return error("Qzone 图片最多 "+config.qzoneMaxImages+" 张");
        }
        long imageBytes = 0L;
        if (images != null) {
            for (Object object : images) {
                String image = safe(String.valueOf(object));
                if (!withinBase64Limit(image,config.imageMaxBytes)) {
                    return error("Qzone 单张图片超过大小限制");
                }
                if (image.startsWith("base64://")) {
                    imageBytes += Math.max(0,image.length() - "base64://".length()) * 3L / 4L;
                }
            }
            if (imageBytes > 16L * 1024L * 1024L) return error("Qzone 图片总大小超过 16MB");
        }
        if (!cooldown("qzone:post",config.qzoneCooldownSecond)) {
            return error("Qzone 发布冷却中");
        }
        int right = input.getIntValue("ugcRight");
        if (right == 0) right = config.qzoneDefaultUgcRight;
        if (!validUgcRight(right)) return error("Qzone 可见范围不合法");
        JSONObject params = new JSONObject(true);
        params.put("content",content);
        if (images != null) params.put("images",images);
        params.put("ugc_right",right);
        JSONArray targets = input.getJSONArray("targetUins");
        if (targets != null) params.put("target_uins",targets);
        JSONObject result = action("qzone_post","send_qzone_msg",params,input,0,operatorID,
                "发 Qzone："+shortText(content,60));
        if (result.getBooleanValue("status")) {
            mark("qzone:post");
            JSONObject data = result.getJSONObject("data");
            String tid = data == null ? "" : safe(data.getString("tid"));
            storage().insert("INSERT INTO `"+QZONE_TABLE+"` "
                            + "(`tid`,`content`,`images`,`ugcRight`,`createTime`) VALUES (?,?,?,?,?)",
                    tid,content,(images == null ? 0 : images.size())+" 张图片",right,
                    System.currentTimeMillis());
        }
        return result;
    }

    private JSONObject qzoneDelete(JSONObject input) {
        if (!config.qzoneEnable) return error("Qzone 工具未启用");
        long operatorID = positive(input,"operatorID");
        if (!isOwner(operatorID)) return error("管理 Qzone 仅 owner 可用");
        String tid = safe(input.getString("tid")).trim();
        if (tid.isEmpty()) return error("缺少说说 tid");
        JSONObject params = new JSONObject(true);
        params.put("tid",tid);
        JSONObject result = action("qzone_delete","delete_qzone_msg",params,input,0,operatorID,
                "删除 Qzone 说说 "+tid);
        if (result.getBooleanValue("status")) {
            storage().update("DELETE FROM `"+QZONE_TABLE+"` WHERE `tid`=?",tid);
        }
        return result;
    }

    private JSONObject qzonePosts(JSONObject input) {
        if (!config.qzoneEnable) return error("Qzone 工具未启用");
        long operatorID = positive(input,"operatorID");
        if (!isOwner(operatorID)) return error("管理 Qzone 仅 owner 可用");
        int limit = clamp(input.getIntValue("limit"),1,50);
        List<JSONObject> rows = storage().query("SELECT * FROM `"+QZONE_TABLE+"` "
                + "ORDER BY `ID` DESC LIMIT "+limit);
        JSONArray posts = new JSONArray();
        if (rows != null) {
            for (JSONObject row : rows) {
                JSONObject item = new JSONObject(true);
                item.put("id",row.getLongValue("ID"));
                item.put("tid",safe(row.getString("tid")));
                item.put("content",safe(row.getString("content")));
                item.put("images",safe(row.getString("images")));
                item.put("ugcRight",row.getIntValue("ugcRight"));
                item.put("createTime",row.getLongValue("createTime"));
                posts.add(item);
            }
        }
        JSONObject result = ok("读取本机 Qzone 发布记录");
        result.put("posts",posts);
        return result;
    }

    private JSONObject auditList(JSONObject input) {
        int limit = clamp(input.getIntValue("limit"),1,50);
        long groupID = positive(input,"groupID");
        List<JSONObject> rows;
        if (groupID > 0) {
            rows = storage().query("SELECT * FROM `"+AUDIT_TABLE+"` WHERE `groupID`=? "
                    + "ORDER BY `ID` DESC LIMIT "+limit,groupID);
        } else {
            rows = storage().query("SELECT * FROM `"+AUDIT_TABLE+"` ORDER BY `ID` DESC LIMIT "+limit);
        }
        JSONArray items = new JSONArray();
        if (rows != null) {
            for (JSONObject row : rows) {
                JSONObject item = new JSONObject(true);
                item.put("id",row.getLongValue("ID"));
                item.put("action",safe(row.getString("action")));
                item.put("groupID",row.getLongValue("groupID"));
                item.put("operatorID",row.getLongValue("operatorID"));
                item.put("targetID",row.getLongValue("targetID"));
                item.put("summary",safe(row.getString("summary")));
                item.put("success",row.getIntValue("success") == 1);
                item.put("time",formatTime(row.getLongValue("createTime")));
                items.add(item);
            }
        }
        JSONObject result = ok("读取 QQ 操作审计");
        result.put("items",items);
        return result;
    }

    private JSONObject action(String auditName,String action,JSONObject params,JSONObject input,
                              long groupID,long targetID,String summary) {
        OneBotClient client = client();
        JSONObject raw = client.callAction(action,params);
        boolean success = success(raw);
        audit(auditName,groupID,positive(input,"operatorID"),targetID,summary,success);
        if (raw == null) return error(action+" 没有返回结果");
        JSONObject result = new JSONObject(true);
        result.put("status",success);
        result.put("action",action);
        Object data = raw.get("data");
        if (data instanceof JSONObject) {
            result.put("data",data);
        } else if (data instanceof JSONArray) {
            JSONObject wrapped = new JSONObject(true);
            wrapped.put("items",data);
            result.put("data",wrapped);
        } else {
            result.put("data",new JSONObject(true));
        }
        if (!success) result.put("message",failureMessage(raw));
        return result;
    }

    private JSONObject wrapSend(String actionName,JSONObject input,long groupID,long targetID,
                                String summary,JSONObject raw) {
        boolean success = success(raw);
        audit(actionName,groupID,positive(input,"operatorID"),targetID,summary,success);
        if (raw == null) return error("消息发送没有返回结果");
        JSONObject result = new JSONObject(true);
        result.put("status",success);
        result.put("data",raw.getJSONObject("data") == null
                ? new JSONObject(true) : raw.getJSONObject("data"));
        if (!success) result.put("message",failureMessage(raw));
        return result;
    }

    private JSONObject getMessage(String messageID) {
        JSONObject params = new JSONObject(true);
        params.put("message_id",messageID);
        JSONObject raw = client().callAction("get_msg",params);
        return raw == null ? null : raw.getJSONObject("data");
    }

    private boolean sameGroup(JSONObject message,long groupID) {
        long value = message == null ? 0 : message.getLongValue("group_id");
        return value == groupID;
    }

    private boolean isOwner(long userID) {
        List<Long> owners = plugin.getServer().getOwnerList();
        return owners != null && owners.contains(userID);
    }

    private boolean isOwnerOrAdmin(long groupID,long userID,JSONObject input) {
        if (isOwner(userID)) return true;
        JSONObject params = new JSONObject(true);
        params.put("group_id",String.valueOf(groupID));
        params.put("user_id",String.valueOf(userID));
        params.put("no_cache",true);
        JSONObject raw = client().callAction("get_group_member_info",params);
        JSONObject data = raw == null ? null : raw.getJSONObject("data");
        String role = data == null ? "" : safe(data.getString("role"));
        return "owner".equals(role) || "admin".equals(role);
    }

    private boolean cooldown(String key,int seconds) {
        String value = plugin.getServer().getStorage().get(plugin,"qqrate:"+key);
        if (value == null || value.isEmpty()) return true;
        try {
            return System.currentTimeMillis() - Long.parseLong(value) >= seconds * 1000L;
        } catch (Exception e) {
            return true;
        }
    }

    private void mark(String key) {
        plugin.getServer().getStorage().set(plugin,"qqrate:"+key,
                String.valueOf(System.currentTimeMillis()));
    }

    private OneBotClient client() {
        OneBotClient client = plugin.getServer().getOneBotClient();
        if (client == null || !client.isConnected()) {
            throw new IllegalStateException("OneBot 未连接");
        }
        return client;
    }

    private boolean success(JSONObject raw) {
        if (raw == null) return false;
        if (raw.containsKey("retcode")) return raw.getIntValue("retcode") == 0;
        return raw.getBooleanValue("status");
    }

    private String failureMessage(JSONObject raw) {
        if (raw == null) return "没有返回结果";
        String message = safe(raw.getString("message"));
        if (message.isEmpty()) message = safe(raw.getString("wording"));
        if (message.isEmpty() && raw.get("data") instanceof JSONObject) {
            message = safe(raw.getJSONObject("data").getString("message"));
        }
        return message.isEmpty() ? "OneBot 动作失败" : shortText(message,300);
    }

    private void audit(String action,long groupID,long operatorID,long targetID,
                       String summary,boolean success) {
        try {
            storage().insert("INSERT INTO `"+AUDIT_TABLE+"` "
                            + "(`action`,`groupID`,`operatorID`,`targetID`,`summary`,`success`,`createTime`) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    action,groupID,operatorID,targetID,shortText(summary,300),
                    success ? 1 : 0,System.currentTimeMillis());
            if (System.currentTimeMillis() % 100 < 5) cleanupAudit();
        } catch (Exception e) {
            plugin.getLogger().sendWarn("[QQ] 审计写入失败："+safe(e.getMessage()));
        }
    }

    private void cleanupAudit() {
        long cutoff = System.currentTimeMillis() - config.auditKeepDays * 86400000L;
        storage().update("DELETE FROM `"+AUDIT_TABLE+"` WHERE `createTime`<?",cutoff);
        storage().update("DELETE FROM `"+AUDIT_TABLE+"` WHERE `ID` NOT IN "
                + "(SELECT `ID` FROM `"+AUDIT_TABLE+"` ORDER BY `ID` DESC LIMIT "
                +config.auditMaxRows+")");
    }

    private boolean validUgcRight(int right) {
        return right == 1 || right == 4 || right == 16 || right == 64 || right == 128;
    }

    private boolean withinBase64Limit(String file,int maxBytes) {
        if (file == null || !file.startsWith("base64://")) return true;
        long encoded = Math.max(0,file.length() - "base64://".length());
        return encoded * 3L / 4L <= maxBytes;
    }

    private boolean validFileExtension(String file,String allowed) {
        String name = safe(file);
        int slash = Math.max(name.lastIndexOf('/'),name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= name.length()) return false;
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String[] extensions = safe(allowed).split(",");
        for (String item : extensions) {
            if (extension.equals(item.trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private long positive(JSONObject input,String key) {
        if (input == null) return 0L;
        long value = input.getLongValue(key);
        return value > 0 ? value : 0L;
    }

    private int clamp(int value,int min,int max) {
        if (value < min) return min;
        return Math.min(value,max);
    }

    private String formatTime(long time) {
        if (time <= 0) return "";
        return new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date(time));
    }

    private String shortText(String value,int max) {
        String text = safe(value).replaceAll("\\s+"," ").trim();
        return text.length() <= max ? text : text.substring(0,max)+"...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private JSONObject ok(String message) {
        JSONObject result = new JSONObject(true);
        result.put("status",true);
        result.put("message",message);
        return result;
    }

    private JSONObject error(String message) {
        JSONObject result = new JSONObject(true);
        result.put("status",false);
        result.put("message",message);
        return result;
    }

    private org.moboxlab.moboxbot.API.Storage.StorageService storage() {
        return plugin.getServer().getStorage();
    }
}
