package org.moboxlab.mbb.trpg.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 局面只读快照（不可变）。
 *
 * <p>供表现层与 AI 主持在群锁外读取；不暴露写入方法。场景版本取本局最新事件序号，
 * 无事件时为 0，AI 结果返回时用于判定是否过期。</p>
 */
public final class SessionSnapshot {

    private final long sessionId;
    private final long groupId;
    private final SessionState state;
    private final long kpUserId;
    private final String scriptId;
    private final String ruleSetId;
    private final int sceneIndex;
    private final String sceneTitle;
    private final long startedAt;
    private final long endedAt;
    private final String endReason;
    private final String summary;
    private final long lastActivityAt;
    private final boolean aiEnabled;
    private final long aiSuspendedUntil;
    private final int aiFailStreak;
    private final boolean recovered;
    private final long sceneVersion;
    private final List<MemberView> members;
    private final List<String> unlockedClues;

    private SessionSnapshot(Builder builder) {
        this.sessionId = builder.sessionId;
        this.groupId = builder.groupId;
        this.state = builder.state == null ? SessionState.NONE : builder.state;
        this.kpUserId = builder.kpUserId;
        this.scriptId = builder.scriptId == null ? "" : builder.scriptId;
        this.ruleSetId = builder.ruleSetId == null ? "" : builder.ruleSetId;
        this.sceneIndex = builder.sceneIndex;
        this.sceneTitle = builder.sceneTitle == null ? "" : builder.sceneTitle;
        this.startedAt = builder.startedAt;
        this.endedAt = builder.endedAt;
        this.endReason = builder.endReason == null ? "" : builder.endReason;
        this.summary = builder.summary == null ? "" : builder.summary;
        this.lastActivityAt = builder.lastActivityAt;
        this.aiEnabled = builder.aiEnabled;
        this.aiSuspendedUntil = builder.aiSuspendedUntil;
        this.aiFailStreak = builder.aiFailStreak;
        this.recovered = builder.recovered;
        this.sceneVersion = builder.sceneVersion;
        this.members = Collections.unmodifiableList(new ArrayList<MemberView>(builder.members));
        this.unlockedClues = Collections.unmodifiableList(
                new ArrayList<String>(builder.unlockedClues));
    }

    public static Builder builder() {
        return new Builder();
    }

    public long sessionId() {
        return sessionId;
    }

    public long groupId() {
        return groupId;
    }

    public SessionState state() {
        return state;
    }

    public long kpUserId() {
        return kpUserId;
    }

    public String scriptId() {
        return scriptId;
    }

    public String ruleSetId() {
        return ruleSetId;
    }

    public int sceneIndex() {
        return sceneIndex;
    }

    public String sceneTitle() {
        return sceneTitle;
    }

    public long startedAt() {
        return startedAt;
    }

    public long endedAt() {
        return endedAt;
    }

    public String endReason() {
        return endReason;
    }

    public String summary() {
        return summary;
    }

    public long lastActivityAt() {
        return lastActivityAt;
    }

    public boolean aiEnabled() {
        return aiEnabled;
    }

    public long aiSuspendedUntil() {
        return aiSuspendedUntil;
    }

    public int aiFailStreak() {
        return aiFailStreak;
    }

    /** 重启恢复后尚未被 KP 确认时为 true。 */
    public boolean recovered() {
        return recovered;
    }

    public long sceneVersion() {
        return sceneVersion;
    }

    public List<MemberView> members() {
        return members;
    }

    /** 按成员 ID 查找；不存在返回 null。 */
    public MemberView member(long userId) {
        for (MemberView member : members) {
            if (member.userId() == userId) {
                return member;
            }
        }
        return null;
    }

    public List<String> unlockedClues() {
        return unlockedClues;
    }

    /** 快照构造器。 */
    public static final class Builder {

        private long sessionId;
        private long groupId;
        private SessionState state = SessionState.NONE;
        private long kpUserId;
        private String scriptId = "";
        private String ruleSetId = "";
        private int sceneIndex;
        private String sceneTitle = "";
        private long startedAt;
        private long endedAt;
        private String endReason = "";
        private String summary = "";
        private long lastActivityAt;
        private boolean aiEnabled;
        private long aiSuspendedUntil;
        private int aiFailStreak;
        private boolean recovered;
        private long sceneVersion;
        private List<MemberView> members = new ArrayList<MemberView>();
        private List<String> unlockedClues = new ArrayList<String>();

        public Builder sessionId(long value) {
            this.sessionId = value;
            return this;
        }

        public Builder groupId(long value) {
            this.groupId = value;
            return this;
        }

        public Builder state(SessionState value) {
            this.state = value;
            return this;
        }

        public Builder kpUserId(long value) {
            this.kpUserId = value;
            return this;
        }

        public Builder scriptId(String value) {
            this.scriptId = value;
            return this;
        }

        public Builder ruleSetId(String value) {
            this.ruleSetId = value;
            return this;
        }

        public Builder sceneIndex(int value) {
            this.sceneIndex = value;
            return this;
        }

        public Builder sceneTitle(String value) {
            this.sceneTitle = value;
            return this;
        }

        public Builder startedAt(long value) {
            this.startedAt = value;
            return this;
        }

        public Builder endedAt(long value) {
            this.endedAt = value;
            return this;
        }

        public Builder endReason(String value) {
            this.endReason = value;
            return this;
        }

        public Builder summary(String value) {
            this.summary = value;
            return this;
        }

        public Builder lastActivityAt(long value) {
            this.lastActivityAt = value;
            return this;
        }

        public Builder aiEnabled(boolean value) {
            this.aiEnabled = value;
            return this;
        }

        public Builder aiSuspendedUntil(long value) {
            this.aiSuspendedUntil = value;
            return this;
        }

        public Builder aiFailStreak(int value) {
            this.aiFailStreak = value;
            return this;
        }

        public Builder recovered(boolean value) {
            this.recovered = value;
            return this;
        }

        public Builder sceneVersion(long value) {
            this.sceneVersion = value;
            return this;
        }

        public Builder members(List<MemberView> value) {
            this.members = value == null ? new ArrayList<MemberView>() : value;
            return this;
        }

        public Builder unlockedClues(List<String> value) {
            this.unlockedClues = value == null ? new ArrayList<String>() : value;
            return this;
        }

        public SessionSnapshot build() {
            return new SessionSnapshot(this);
        }
    }
}
