package org.moboxlab.mbb.roleplay;

/**
 * 角色群心情
 *
 * 负责默认心情、自然衰减和心情标签刷新。
 */
public class RoleplayMoodService {
    private final RoleplayEmotionService service;

    public RoleplayMoodService(RoleplayEmotionService service) {
        this.service = service;
    }

    RoleplayEmotionService.MoodState defaultMood(long groupID) {
        RoleplayEmotionService.MoodState mood = new RoleplayEmotionService.MoodState();
        mood.groupID = groupID;
        mood.valence = service.baselineValence();
        mood.energy = service.baselineEnergy();
        mood.patience = service.baselinePatience();
        mood.label = service.moodLabel(mood);
        mood.lastDecayTime = System.currentTimeMillis();
        mood.updateTime = mood.lastDecayTime;
        return mood;
    }

    void decayMood(RoleplayEmotionService.MoodState mood) {
        long now = System.currentTimeMillis();
        if (mood.lastDecayTime <= 0) {
            mood.lastDecayTime = now;
            return;
        }
        long interval = Math.max(1,service.config().emotionDecayMinute) * 60000L;
        long steps = (now - mood.lastDecayTime) / interval;
        if (steps <= 0) return;
        int count = (int) Math.min(120,steps);
        mood.valence = service.moveToward(mood.valence,service.baselineValence(),count);
        mood.energy = service.moveToward(mood.energy,service.baselineEnergy(),count);
        mood.patience = service.moveToward(mood.patience,service.baselinePatience(),count);
        mood.label = service.moodLabel(mood);
        if (now - mood.lastEventTime > 7200000L) mood.reason = "";
        mood.lastDecayTime += steps * interval;
        mood.updateTime = now;
        service.emotionStateRepository().saveMood(mood);
    }
}