package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 生图画面意图
 *
 * 角色层只描述“画什么”，不直接写最终 booru prompt。
 */
public class RoleplayDrawBrief {
    public String subject = "";
    public List<String> characters = new ArrayList<>();
    public String action = "";
    public String expression = "";
    public String outfit = "";
    public String scene = "";
    public String composition = "";
    public String camera = "";
    public String size = "";
    public List<String> mustInclude = new ArrayList<>();
    public List<String> mustExclude = new ArrayList<>();
    public String raw = "";

    public static RoleplayDrawBrief fromJson(JSONObject json) {
        RoleplayDrawBrief brief = new RoleplayDrawBrief();
        if (json == null) return brief;
        brief.subject = safe(json.getString("subject"));
        brief.characters = readList(json.getJSONArray("characters"));
        brief.action = safe(json.getString("action"));
        brief.expression = safe(json.getString("expression"));
        brief.outfit = safe(json.getString("outfit"));
        brief.scene = safe(json.getString("scene"));
        brief.composition = safe(json.getString("composition"));
        brief.camera = safe(json.getString("camera"));
        brief.size = safe(json.getString("size"));
        brief.mustInclude = readList(json.getJSONArray("mustInclude"));
        brief.mustExclude = readList(json.getJSONArray("mustExclude"));
        brief.raw = safe(json.getString("raw"));
        return brief;
    }

    public static RoleplayDrawBrief fromLegacyPrompt(String prompt) {
        RoleplayDrawBrief brief = new RoleplayDrawBrief();
        brief.raw = safe(prompt);
        brief.subject = "当前角色";
        return brief;
    }

    public boolean isEmpty() {
        return subject.isEmpty() && characters.isEmpty() && action.isEmpty()
                && expression.isEmpty() && outfit.isEmpty() && scene.isEmpty()
                && composition.isEmpty() && camera.isEmpty() && size.isEmpty()
                && mustInclude.isEmpty() && mustExclude.isEmpty() && raw.isEmpty();
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject(true);
        json.put("subject",subject);
        json.put("characters",characters);
        json.put("action",action);
        json.put("expression",expression);
        json.put("outfit",outfit);
        json.put("scene",scene);
        json.put("composition",composition);
        json.put("camera",camera);
        json.put("size",size);
        json.put("mustInclude",mustInclude);
        json.put("mustExclude",mustExclude);
        json.put("raw",raw);
        return json;
    }

    public String promptText() {
        List<String> parts = new ArrayList<>();
        if (!subject.isEmpty()) parts.add(subject);
        if (!characters.isEmpty()) parts.add("characters: "+String.join(", ",characters));
        if (!action.isEmpty()) parts.add(action);
        if (!expression.isEmpty()) parts.add(expression);
        if (!outfit.isEmpty()) parts.add(outfit);
        if (!scene.isEmpty()) parts.add(scene);
        if (!composition.isEmpty()) parts.add(composition);
        if (!camera.isEmpty()) parts.add("camera: "+camera);
        if (!mustInclude.isEmpty()) parts.add("must include: "+String.join(", ",mustInclude));
        if (!mustExclude.isEmpty()) parts.add("must exclude: "+String.join(", ",mustExclude));
        if (!raw.isEmpty()) parts.add(raw);
        return String.join("; ",parts);
    }

    private static List<String> readList(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) return result;
        for (Object value : array) {
            String text = safe(value == null ? "" : String.valueOf(value));
            if (!text.isEmpty()) result.add(text);
        }
        return result;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
