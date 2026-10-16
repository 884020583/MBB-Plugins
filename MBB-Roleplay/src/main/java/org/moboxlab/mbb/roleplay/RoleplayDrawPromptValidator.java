package org.moboxlab.mbb.roleplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 生图 prompt 质检
 *
 * 负责把当前角色的 canonical tag 强制放进正向提示词，把其他角色和冲突外貌词移到负向提示词。
 */
public class RoleplayDrawPromptValidator {
    private static final Set<String> COLOR_WORDS = new HashSet<>(Arrays.asList(
            "black","white","grey","gray","silver","blonde","blond","pink","red",
            "orange","yellow","green","blue","cyan","purple","lavender","brown",
            "coral","teal","turquoise","mint","magenta","violet"));
    private static final String[] GLOBAL_IDENTITY_NEGATIVE = new String[]{
            "duplicate character",
            "clone",
            "multiple views",
            "split screen",
            "extra person",
            "wrong hair color",
            "wrong eye color",
            "wrong halo color",
            "merged characters",
            "identity confusion"
    };

    public static RoleplayDrawPromptResult validate(java.util.List<RoleplayDrawTarget> targets,
                                                     RoleplayDrawBrief brief,
                                                     String prompt,
                                                     String negativePrompt,
                                                     int maxPromptChars,
                                                     int maxNegativeChars) {
        if (targets == null || targets.isEmpty()) {
            return RoleplayDrawPromptResult.failed("缺少生图目标角色，无法质检 prompt");
        }
        LinkedHashSet<String> positive = tags(prompt);
        //编译器输出的负向词不直接信任：目标角色名、1girl/solo 等一旦混进去会反向压制画面。
        LinkedHashSet<String> negative = new LinkedHashSet<>();
        LinkedHashSet<String> forbidden = new LinkedHashSet<>();
        LinkedHashSet<String> canonical = new LinkedHashSet<>();
        LinkedHashSet<String> targetNames = new LinkedHashSet<>();
        for (RoleplayDrawTarget target : targets) {
            if (target == null) continue;
            addTag(canonical,target.canonicalTag);
            addAll(canonical,target.imageTags);
            addAll(forbidden,target.negativeTags);
            addTag(targetNames,target.name);
            addTag(targetNames,target.canonicalTag);
            addAll(targetNames,target.aliases);
        }
        if (brief != null) addAll(forbidden,brief.mustExclude);
        for (String item : GLOBAL_IDENTITY_NEGATIVE) forbidden.add(item);

        for (String tag : new ArrayList<>(forbidden)) {
            if (matchesAny(tag,targetNames)) forbidden.remove(tag);
        }

        int removed = 0;
        List<String> kept = new ArrayList<>();
        for (String tag : positive) {
            if (matchesAny(tag,targetNames) || isCountTag(tag)) {
                removed++;
                continue;
            }
            if (matchesAny(tag,forbidden)) {
                negative.add(tag);
                removed++;
                continue;
            }
            if (conflictsColor(tag,targets)) {
                negative.add(tag);
                removed++;
                continue;
            }
            kept.add(tag);
        }

        LinkedHashSet<String> finalPositive = new LinkedHashSet<>();
        for (String tag : canonical) addTag(finalPositive,tag);
        int characterCount = targets.size();
        addTag(finalPositive,characterCount >= 2 ? "2girls" : "1girl");
        if (brief != null) addAll(finalPositive,brief.mustInclude);
        for (String tag : kept) addTag(finalPositive,tag);

        String positiveText = joinLimited(finalPositive,maxPromptChars);
        LinkedHashSet<String> finalNegative = new LinkedHashSet<>();
        for (String tag : negative) addTag(finalNegative,tag);
        for (String tag : forbidden) addTag(finalNegative,tag);
        if (characterCount >= 2) {
            addTag(finalNegative,"1girl");
            addTag(finalNegative,"solo");
        } else {
            addTag(finalNegative,"2girls");
            addTag(finalNegative,"multiple girls");
        }
        String negativeText = joinLimited(finalNegative,maxNegativeChars);
        if (positiveText.trim().isEmpty()) {
            return RoleplayDrawPromptResult.failed("生图 prompt 为空，已取消任务");
        }
        String validation = "targets="+targets.size()
                +",canonical="+canonical.size()
                +",removedPositive="+removed
                +",negative="+finalNegative.size()
                +",count="+(characterCount >= 2 ? "2girls" : "1girl");
        return RoleplayDrawPromptResult.ok(positiveText,negativeText,
                brief == null ? "" : brief.size,
                brief == null ? "" : brief.camera,
                validation);
    }

    private static LinkedHashSet<String> tags(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (text == null || text.trim().isEmpty()) return result;
        for (String item : text.split(",")) {
            addTag(result,item);
        }
        return result;
    }

    private static void addAll(Set<String> target,List<String> values) {
        if (values == null) return;
        for (String value : values) addTag(target,value);
    }

    private static void addTag(Set<String> target,String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return;
        String normalized = normalize(text);
        for (String existing : target) {
            if (normalize(existing).equals(normalized)) return;
        }
        target.add(text);
    }

    private static boolean matchesAny(String tag,Set<String> forbidden) {
        String normalized = normalize(tag);
        for (String item : forbidden) {
            String value = normalize(item);
            if (value.isEmpty()) continue;
            if (normalized.equals(value) || normalized.contains(value)
                    || (value.length() >= 6 && value.contains(normalized))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCountTag(String tag) {
        String value = normalize(tag);
        return "1girl".equals(value) || "2girls".equals(value)
                || "solo".equals(value) || "multiple girls".equals(value)
                || "one girl".equals(value) || "two girls".equals(value);
    }

    private static boolean conflictsColor(String tag,List<RoleplayDrawTarget> targets) {
        String attribute = attribute(tag);
        if (attribute.isEmpty()) return false;
        Set<String> colors = colors(tag);
        if (colors.isEmpty()) return false;
        Set<String> allowed = new HashSet<>();
        for (RoleplayDrawTarget target : targets) {
            if (target == null) continue;
            for (String imageTag : target.imageTags) {
                if (imageTag == null || !imageTag.toLowerCase(Locale.ROOT).contains(attribute)) {
                    continue;
                }
                allowed.addAll(colors(imageTag));
            }
        }
        if (allowed.isEmpty()) return false;
        for (String color : colors) {
            if (!allowed.contains(color)) return true;
        }
        return false;
    }

    private static String attribute(String tag) {
        String value = normalize(tag);
        if (value.contains("hair")) return "hair";
        if (value.contains("eye")) return "eye";
        if (value.contains("halo")) return "halo";
        return "";
    }

    private static Set<String> colors(String tag) {
        Set<String> result = new HashSet<>();
        String value = normalize(tag).replace('_',' ');
        for (String part : value.split("\\s+")) {
            if (COLOR_WORDS.contains(part)) result.add(part);
        }
        if (result.contains("gray")) result.add("grey");
        if (result.contains("grey")) result.add("gray");
        if (result.contains("blond")) result.add("blonde");
        if (result.contains("blonde")) result.add("blond");
        return result;
    }

    private static String joinLimited(Set<String> tags,int maxChars) {
        int limit = maxChars < 80 ? 80 : maxChars;
        StringBuilder builder = new StringBuilder();
        for (String tag : tags) {
            String text = tag == null ? "" : tag.trim();
            if (text.isEmpty()) continue;
            String next = builder.length() == 0 ? text : builder.toString()+", "+text;
            if (next.length() > limit) continue;
            builder.setLength(0);
            builder.append(next);
        }
        return builder.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace('_',' ').replaceAll("\\s+"," ");
    }
}
