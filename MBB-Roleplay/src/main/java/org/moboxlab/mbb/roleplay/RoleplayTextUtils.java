package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Event.GroupMessageEvent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * 角色文本与时间工具
 *
 * 负责空值处理、截断、时区时间、昵称读取、地址别名和重复度计算。
 */
public class RoleplayTextUtils {
    private final RoleplayService service;

    public RoleplayTextUtils(RoleplayService service) {
        this.service = service;
    }

    String safe(String value) {
        return value == null ? "" : value;
    }

    String shortText(String text,int maxChars) {
        String value = safe(text).replace("\n"," ").trim();
        if (value.length() <= maxChars) return value;
        return value.substring(0,maxChars)+"...";
    }

    String currentTimeText() {
        try {
            String zoneName = service.config().timeZone == null || service.config().timeZone.trim().isEmpty()
                    ? "Asia/Shanghai" : service.config().timeZone.trim();
            TimeZone zone = TimeZone.getTimeZone(zoneName);
            if ("GMT".equals(zone.getID()) && !"GMT".equalsIgnoreCase(zoneName)) {
                zoneName = "Asia/Shanghai";
                zone = TimeZone.getTimeZone(zoneName);
            }
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE",Locale.CHINA);
            format.setTimeZone(zone);
            return format.format(new Date())+"（"+zoneName+"）";
        } catch (Exception e) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE",Locale.CHINA).format(new Date());
        }
    }

    String formatMemoryTime(long time) {
        if (time <= 0) return "未知时间";
        try {
            String zoneName = service.config().timeZone == null || service.config().timeZone.trim().isEmpty()
                    ? "Asia/Shanghai" : service.config().timeZone.trim();
            TimeZone zone = TimeZone.getTimeZone(zoneName);
            if ("GMT".equals(zone.getID()) && !"GMT".equalsIgnoreCase(zoneName)) {
                zone = TimeZone.getTimeZone("Asia/Shanghai");
            }
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA);
            format.setTimeZone(zone);
            return format.format(new Date(time));
        } catch (Exception e) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(new Date(time));
        }
    }

    String normalizeAddressText(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("@")) {
            text = text.replaceFirst("^@[0-9]+\\s*","").trim();
        }
        return text;
    }

    boolean containsRoleAlias(String text,String alias) {
        if (text == null || alias == null || alias.trim().isEmpty()) return false;
        String value = text.trim();
        String name = alias.trim();
        //单字别名容易误伤普通词，只在句首或显式艾特时生效
        if (name.length() == 1) return value.startsWith(name) || value.contains("@"+name);
        return value.contains(name) || value.contains("@"+name);
    }

    boolean startsWithAlias(String text,String alias) {
        if (text == null || alias == null || alias.trim().isEmpty()) return false;
        String value = text.trim();
        String name = alias.trim();
        return value.startsWith(name) || value.startsWith("@"+name);
    }

    boolean isOwnRoleName(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        if (name.equalsIgnoreCase(service.persona().name)) return true;
        for (String alias : service.persona().aliases) {
            if (name.equalsIgnoreCase(alias)) return true;
        }
        return false;
    }

    String joinArray(JSONArray array) {
        if (array == null || array.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < array.size(); i++) {
            String value = safe(array.getString(i));
            if (value.isEmpty()) continue;
            if (builder.length() > 0) builder.append(", ");
            builder.append(value);
        }
        return builder.toString();
    }

    boolean containsCSVLong(String csv,long value) {
        if (csv == null || csv.trim().isEmpty()) return false;
        for (String item : csv.split(",")) {
            try {
                if (Long.parseLong(item.trim()) == value) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    long eventTimeMillis(GroupMessageEvent event) {
        if (event == null || event.getRaw() == null) return 0L;
        long seconds = event.getRaw().getLongValue("time");
        return seconds <= 0 ? 0L : seconds * 1000L;
    }

    String senderName(GroupMessageEvent event) {
        JSONObject sender = event.getSender();
        String name = sender == null ? "" : sender.getString("card");
        if (name == null || name.trim().isEmpty()) name = sender == null ? "" : sender.getString("nickname");
        return name == null || name.trim().isEmpty() ? String.valueOf(event.getUserID()) : name;
    }

    String openingOf(String text) {
        if (text == null) return "";
        int length = Math.min(2,text.length());
        return length <= 0 ? "" : text.substring(0,length);
    }

    int longestCommonRun(String left,String right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) return 0;
        int best = 0;
        for (int i = 0; i < left.length(); i++) {
            for (int j = 0; j < right.length(); j++) {
                int run = 0;
                while (i + run < left.length() && j + run < right.length()
                        && left.charAt(i + run) == right.charAt(j + run)) {
                    run++;
                }
                if (run > best) best = run;
            }
        }
        return best;
    }

    double similarity(String left,String right) {
        if (left == null || right == null) return 0;
        if (left.equals(right)) return 1.0;
        Set<String> leftSet = bigrams(left);
        Set<String> rightSet = bigrams(right);
        if (leftSet.isEmpty() || rightSet.isEmpty()) return 0;
        int intersection = 0;
        for (String item : leftSet) {
            if (rightSet.contains(item)) intersection++;
        }
        int union = leftSet.size() + rightSet.size() - intersection;
        return union <= 0 ? 0 : intersection / (double)union;
    }

    Set<String> bigrams(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.length() < 2) return result;
        for (int i = 0; i < text.length() - 1; i++) {
            result.add(text.substring(i,i + 2));
        }
        return result;
    }

    String normalizeForSimilarity(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.CHINA)
                .replaceAll("[^\\u4e00-\\u9fa5a-z0-9]","")
                .trim();
    }

    boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}