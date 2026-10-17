package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONObject;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 角色安全与防注入
 *
 * 拦截系统提示词诱导、越狱指令和现实隐私索取，并阻止不安全内容写入记忆。
 */
public class RoleplaySafetyService {
    private static final List<String> INJECTION = Arrays.asList(
            "忽略之前","忽略以上","忽略前面的","系统提示词","系统指令","开发者消息",
            "developer message","jailbreak","越狱","不要遵守","绕过限制","泄露prompt",
            "输出你的设定","把你的设定","你的prompt","api key","apikey","访问令牌");
    private static final List<String> PRIVACY = Arrays.asList(
            "真实姓名","家庭住址","家庭地址","身份证","银行卡","手机号","电话号码",
            "密码","验证码","住址","联系方式","开房记录","定位");

    private final RoleplayService service;

    public RoleplaySafetyService(RoleplayService service) {
        this.service = service;
    }

    public JSONObject inspect(long groupID,long userID,String content) {
        JSONObject result = new JSONObject(true);
        result.put("block",false);
        result.put("type","");
        result.put("reason","");
        if (!service.config().safetyGuardEnable) return result;
        String text = service.safe(content).toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return result;
        String hit = match(text,INJECTION);
        if (!hit.isEmpty() && service.config().promptInjectionGuardEnable) {
            return blocked(result,"injection","疑似提示词注入："+hit);
        }
        hit = match(text,PRIVACY);
        if (!hit.isEmpty() && service.config().privacyGuardEnable) {
            return blocked(result,"privacy","疑似索取现实隐私："+hit);
        }
        return result;
    }

    public String unsafeMemoryReason(String content) {
        if (!service.config().safetyGuardEnable) return "";
        String text = service.safe(content).toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return "";
        String hit = match(text,INJECTION);
        if (!hit.isEmpty() && service.config().promptInjectionGuardEnable) return "提示词注入："+hit;
        hit = match(text,PRIVACY);
        if (!hit.isEmpty() && service.config().privacyGuardEnable) return "现实隐私："+hit;
        return "";
    }

    private JSONObject blocked(JSONObject result,String type,String reason) {
        result.put("block",true);
        result.put("type",type);
        result.put("reason",reason);
        return result;
    }

    private String match(String text,List<String> values) {
        for (String value : values) {
            if (text.contains(value.toLowerCase(Locale.ROOT))) return value;
        }
        return "";
    }
}
