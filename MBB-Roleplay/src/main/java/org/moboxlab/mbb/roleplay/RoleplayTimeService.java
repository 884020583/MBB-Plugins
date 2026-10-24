package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 角色时间计算与自然语言时间解析。
 */
public class RoleplayTimeService {
    private static final Pattern RELATIVE = Pattern.compile(
            "^(\\d+)\\s*(分钟|小时|天|周)(?:后|以后|之后)?$");
    private static final Pattern CLOCK = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    private volatile RoleplayConfig config;

    public RoleplayTimeService(RoleplayConfig config) {
        this.config = config;
    }

    public void reload(RoleplayConfig config) {
        this.config = config;
    }

    public JSONObject calculate(String operation,String baseText,String targetText,
                                int amount,String unit) {
        String action = safe(operation).trim().toLowerCase(Locale.ROOT);
        if (action.isEmpty()) action = "now";
        long now = System.currentTimeMillis();
        long base = parse(baseText,now);
        if (base <= 0 && !"now".equals(action)) {
            if (!safe(baseText).trim().isEmpty()) {
                throw new IllegalArgumentException("baseTime 无效");
            }
            base = now;
        }
        long result;
        String mode;
        if ("add".equals(action)) {
            long delta = unitMillis(amount,unit);
            if (delta == 0) throw new IllegalArgumentException("amount 或 unit 无效");
            result = base + delta;
            mode = "add";
        } else if ("diff".equals(action)) {
            long target = parse(targetText,now);
            if (target <= 0) throw new IllegalArgumentException("targetTime 无效");
            result = target - base;
            mode = "diff";
        } else if ("until".equals(action)) {
            long target = parse(targetText,now);
            if (target <= 0) throw new IllegalArgumentException("targetTime 无效");
            result = target - now;
            mode = "until";
        } else {
            action = "now";
            mode = "now";
            base = now;
            result = now;
        }
        JSONObject data = new JSONObject(true);
        data.put("operation",mode);
        data.put("timeZone",zoneName());
        data.put("now",now);
        data.put("nowText",format(now));
        data.put("baseTime",base);
        data.put("baseText",format(base));
        data.put("result",result);
        if ("diff".equals(mode) || "until".equals(mode)) {
            data.put("resultText",durationText(result));
            data.put("absoluteSeconds",Math.abs(result / 1000L));
        } else {
            data.put("resultText",format(result));
            data.put("weekday",weekday(result));
        }
        return data;
    }

    public long parse(String text,long now) {
        String value = safe(text).trim();
        if (value.isEmpty()) return 0L;
        long relative = parseRelative(value,now);
        if (relative > 0) return relative;
        if (value.startsWith("明天")) return parseDayOffset(value,now,1);
        if (value.startsWith("后天")) return parseDayOffset(value,now,2);
        if (value.startsWith("今天")) return parseDayOffset(value,now,0);
        Matcher clock = CLOCK.matcher(value);
        if (clock.matches()) {
            Calendar calendar = calendar(now);
            calendar.set(Calendar.HOUR_OF_DAY,Integer.parseInt(clock.group(1)));
            calendar.set(Calendar.MINUTE,Integer.parseInt(clock.group(2)));
            calendar.set(Calendar.SECOND,0);
            calendar.set(Calendar.MILLISECOND,0);
            if (calendar.getTimeInMillis() <= now) {
                calendar.add(Calendar.DAY_OF_MONTH,1);
            }
            return calendar.getTimeInMillis();
        }
        String[] patterns = new String[]{
                "yyyy-MM-dd HH:mm:ss","yyyy-MM-dd HH:mm",
                "yyyy/MM/dd HH:mm:ss","yyyy/MM/dd HH:mm",
                "yyyy-MM-dd'T'HH:mm:ss","yyyy-MM-dd'T'HH:mm"
        };
        for (String pattern : patterns) {
            SimpleDateFormat format = new SimpleDateFormat(pattern,Locale.CHINA);
            format.setLenient(false);
            format.setTimeZone(zone());
            try {
                return format.parse(value).getTime();
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    public String format(long time) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA);
        format.setTimeZone(zone());
        return format.format(new Date(time));
    }

    public String durationText(long millis) {
        long totalSeconds = Math.abs(millis) / 1000L;
        long days = totalSeconds / 86400L;
        long hours = totalSeconds % 86400L / 3600L;
        long minutes = totalSeconds % 3600L / 60L;
        long seconds = totalSeconds % 60L;
        StringBuilder builder = new StringBuilder();
        if (days > 0) builder.append(days).append("天");
        if (hours > 0) builder.append(hours).append("小时");
        if (minutes > 0) builder.append(minutes).append("分钟");
        if (seconds > 0 || builder.length() == 0) builder.append(seconds).append("秒");
        if (millis < 0) builder.append("前");
        else builder.append("后");
        return builder.toString();
    }

    private long parseRelative(String value,long now) {
        Matcher matcher = RELATIVE.matcher(value);
        if (!matcher.matches()) return 0L;
        int amount = Integer.parseInt(matcher.group(1));
        return now + unitMillis(amount,matcher.group(2));
    }

    private long parseDayOffset(String value,long now,int offset) {
        String clockText = value.replaceFirst("^(明天|后天|今天)\\s*","").trim();
        Matcher clock = CLOCK.matcher(clockText);
        if (!clock.matches()) return 0L;
        Calendar calendar = calendar(now);
        calendar.add(Calendar.DAY_OF_MONTH,offset);
        calendar.set(Calendar.HOUR_OF_DAY,Integer.parseInt(clock.group(1)));
        calendar.set(Calendar.MINUTE,Integer.parseInt(clock.group(2)));
        calendar.set(Calendar.SECOND,0);
        calendar.set(Calendar.MILLISECOND,0);
        return calendar.getTimeInMillis();
    }

    private long unitMillis(int amount,String unit) {
        if (amount <= 0) return 0L;
        String value = safe(unit).trim().toLowerCase(Locale.ROOT);
        long multiplier;
        if ("second".equals(value) || "seconds".equals(value) || "秒".equals(value)) {
            multiplier = 1000L;
        } else if ("minute".equals(value) || "minutes".equals(value) || "分钟".equals(value)) {
            multiplier = 60000L;
        } else if ("hour".equals(value) || "hours".equals(value) || "小时".equals(value)) {
            multiplier = 3600000L;
        } else if ("day".equals(value) || "days".equals(value) || "天".equals(value)) {
            multiplier = 86400000L;
        } else if ("week".equals(value) || "weeks".equals(value) || "周".equals(value)) {
            multiplier = 7L * 86400000L;
        } else {
            return 0L;
        }
        return amount * multiplier;
    }

    private String weekday(long time) {
        SimpleDateFormat format = new SimpleDateFormat("EEEE",Locale.CHINA);
        format.setTimeZone(zone());
        return format.format(new Date(time));
    }

    private Calendar calendar(long time) {
        Calendar calendar = Calendar.getInstance(zone(),Locale.CHINA);
        calendar.setTimeInMillis(time);
        return calendar;
    }

    private TimeZone zone() {
        String value = zoneName();
        TimeZone zone = TimeZone.getTimeZone(value);
        return "GMT".equals(zone.getID()) && !"GMT".equalsIgnoreCase(value)
                ? TimeZone.getTimeZone("Asia/Shanghai") : zone;
    }

    private String zoneName() {
        String value = config.timeZone == null ? "" : config.timeZone.trim();
        return value.isEmpty() ? "Asia/Shanghai" : value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
