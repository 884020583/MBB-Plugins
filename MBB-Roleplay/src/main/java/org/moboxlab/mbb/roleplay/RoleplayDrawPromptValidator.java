package org.moboxlab.mbb.roleplay;

import java.util.ArrayList;
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
    private static final String[] GLOBAL_IDENTITY_NEGATIVE = new String[]{
            "duplicate character",
            "clone",
            "multiple views",
            "split screen",
            "extra person"
    };

    public static RoleplayDrawPromptResult validate(RoleplayPersona persona,
                                                     RoleplayDrawBrief brief,
                                                     String prompt,
                                                     String negativePrompt,
                                                     int maxPromptChars,
                                                     int maxNegativeChars) {
        if (persona == null) return RoleplayDrawPromptResult.failed("缺少角色设定，无法质检生图 prompt");
        LinkedHashSet<String> positive = tags(prompt);
        LinkedHashSet<String> negative = tags(negativePrompt);
        LinkedHashSet<String> forbidden = new LinkedHashSet<>();
        addAll(forbidden,persona.imageNegativeTags);
        if (brief != null) addAll(forbidden,brief.mustExclude);
        for (String item : GLOBAL_IDENTITY_NEGATIVE) forbidden.add(item);

        int removed = 0;
        List<String> kept = new ArrayList<>();
        for (String tag : positive) {
            if (matchesAny(tag,forbidden)) {
                negative.add(tag);
                removed++;
                continue;
            }
            kept.add(tag);
        }

        LinkedHashSet<String> finalPositive = new LinkedHashSet<>();
        for (String tag : persona.imageTags) addTag(finalPositive,tag);
        int characterCount = brief == null || brief.characters.isEmpty() ? 1 : brief.characters.size();
        addTag(finalPositive,characterCount >= 2 ? "2girls" : "1girl");
        if (brief != null) addAll(finalPositive,brief.mustInclude);
        for (String tag : kept) addTag(finalPositive,tag);

        String positiveText = joinLimited(finalPositive,maxPromptChars);
        LinkedHashSet<String> finalNegative = new LinkedHashSet<>();
        for (String tag : negative) addTag(finalNegative,tag);
        for (String tag : forbidden) addTag(finalNegative,tag);
        String negativeText = joinLimited(finalNegative,maxNegativeChars);
        if (positiveText.trim().isEmpty()) {
            return RoleplayDrawPromptResult.failed("生图 prompt 为空，已取消任务");
        }
        String validation = "canonical="+persona.imageTags.size()
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
