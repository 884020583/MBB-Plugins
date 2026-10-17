package org.moboxlab.mbb.roleplay;

import java.util.ArrayList;
import java.util.List;

/**
 * 生图目标角色
 */
public class RoleplayDrawTarget {
    public String id = "";
    public String name = "";
    public String canonicalTag = "";
    public String appearance = "";
    public boolean currentRole;
    public List<String> imageTags = new ArrayList<>();
    public List<String> negativeTags = new ArrayList<>();
    public List<String> aliases = new ArrayList<>();

    public String tagText() {
        return String.join(", ",imageTags);
    }

    public String negativeText() {
        return String.join(", ",negativeTags);
    }

    public String cardText() {
        StringBuilder builder = new StringBuilder();
        builder.append("canonical=").append(canonicalTag);
        if (!imageTags.isEmpty()) builder.append(" 标准标签=").append(tagText());
        if (!appearance.isEmpty()) builder.append(" 外貌=").append(appearance);
        if (!negativeTags.isEmpty()) builder.append(" 禁止=").append(negativeText());
        return builder.toString();
    }
}
