package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色 JSON 工具
 *
 * 负责模型输出清洗、宽松 JSON 提取、常见损坏修复和控制字符转义。
 */
public class RoleplayJsonUtils {
    JSONObject parseJson(String content) {
        if (content == null || content.trim().isEmpty()) return null;
        String text = content.replace("\uFEFF","").trim();
        JSONObject direct = tryParseObject(text);
        if (direct != null) return direct;
        for (String candidate : extractJsonObjects(text)) {
            JSONObject parsed = tryParseObject(candidate);
            if (parsed != null) return parsed;
        }
        return null;
    }

    JSONObject tryParseObject(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String value = text.trim();
        JSONObject parsed = parseObjectValue(value);
        if (parsed != null) return parsed;
        String repaired = repairJson(value);
        if (!repaired.equals(value)) return parseObjectValue(repaired);
        return null;
    }

    JSONObject parseObjectValue(String text) {
        try {
            Object value = JSON.parse(text);
            if (value instanceof JSONObject) return (JSONObject)value;
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray)value;
                for (Object item : array) {
                    if (item instanceof JSONObject) return (JSONObject)item;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    List<String> extractJsonObjects(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) return result;
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (character == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    result.add(text.substring(start,i + 1));
                    start = -1;
                }
            }
        }
        return result;
    }

    String repairJson(String text) {
        String value = text.replace("\uFEFF","").trim();
        value = value.replaceAll("(?s)```[a-zA-Z0-9_-]*\\s*","");
        value = value.replace("```","");
        value = stripJsonComments(value);
        value = value.replaceAll(",\\s*([}\\]])","$1");
        value = value.replaceAll(",\\s*([}\\]])","$1");
        return escapeJsonControls(value);
    }

    String stripJsonComments(String text) {
        StringBuilder builder = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (inString) {
                builder.append(character);
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
                builder.append(character);
                continue;
            }
            if (character == '/' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '/') {
                    i += 2;
                    while (i < text.length() && text.charAt(i) != '\n') i++;
                    builder.append('\n');
                    continue;
                }
                if (next == '*') {
                    i += 2;
                    while (i + 1 < text.length()
                            && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) i++;
                    i++;
                    continue;
                }
            }
            builder.append(character);
        }
        return builder.toString();
    }

    String escapeJsonControls(String text) {
        StringBuilder builder = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (!inString) {
                builder.append(character);
                if (character == '"') inString = true;
                continue;
            }
            if (escaped) {
                builder.append(character);
                escaped = false;
                continue;
            }
            if (character == '\\') {
                builder.append(character);
                escaped = true;
                continue;
            }
            if (character == '"') {
                builder.append(character);
                inString = false;
                continue;
            }
            if (character == '\n') {
                builder.append("\\n");
            } else if (character == '\r') {
                builder.append("\\r");
            } else if (character == '\t') {
                builder.append("\\t");
            } else if (character < 0x20) {
                builder.append(String.format("\\u%04x",(int)character));
            } else {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    JSONObject message(String role,String content) {
        JSONObject message = new JSONObject(true);
        message.put("role",role);
        message.put("content",content == null ? "" : content);
        return message;
    }
}