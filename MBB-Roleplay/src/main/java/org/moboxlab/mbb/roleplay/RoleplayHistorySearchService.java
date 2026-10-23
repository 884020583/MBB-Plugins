package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 群历史混合检索。
 *
 * 不依赖数据库分词扩展：先取时间窗口内的候选，再用原文命中、字符 bigram 和新鲜度排序。
 */
public class RoleplayHistorySearchService {
    private final RoleplayService service;

    public RoleplayHistorySearchService(RoleplayService service) {
        this.service = service;
    }

    public JSONObject search(long groupID,String query,int limit,int days) {
        String text = service.safe(query).trim();
        int max = Math.max(1,Math.min(50,limit <= 0 ? 8 : limit));
        int windowDays = Math.max(1,Math.min(3650,days <= 0 ? 30 : days));
        long cutoff = System.currentTimeMillis() - windowDays * 86400000L;
        List<JSONObject> rows = service.storage().query(
                "SELECT `userID`,`userName`,`content`,`messageTime`,`isBot` FROM `"
                        +RoleplayService.MSG_TABLE+"` WHERE `groupID`=? AND `messageTime`>=? "
                        +"AND `userID` NOT IN (SELECT `userID` FROM `plugin_mbb_roleplay_blacklist` "
                        +"WHERE `groupID`=?) ORDER BY `messageTime` DESC,`ID` DESC LIMIT 2000",
                groupID,cutoff,groupID);
        JSONArray messages = new JSONArray();
        if (rows == null || rows.isEmpty() || text.isEmpty()) {
            JSONObject empty = new JSONObject(true);
            empty.put("messages",messages);
            empty.put("windowDays",windowDays);
            return empty;
        }
        String normalizedQuery = SpeechCorpusEntry.normalize(text);
        Set<String> queryBigrams = SpeechCorpusEntry.bigrams(normalizedQuery);
        long now = System.currentTimeMillis();
        List<Scored> scored = new ArrayList<>();
        for (JSONObject row : rows) {
            String content = service.safe(row.getString("content"));
            String normalized = SpeechCorpusEntry.normalize(content);
            double score = 0;
            if (!normalizedQuery.isEmpty() && normalized.contains(normalizedQuery)) score += 8.0;
            if (!queryBigrams.isEmpty()) {
                Set<String> bigrams = SpeechCorpusEntry.bigrams(normalized);
                int overlap = 0;
                for (String bigram : queryBigrams) if (bigrams.contains(bigram)) overlap++;
                if (overlap > 0) {
                    score += overlap * 5.0 / Math.max(1,queryBigrams.size());
                }
            }
            String name = service.safe(row.getString("userName"));
            if (name.contains(text) || String.valueOf(row.getLongValue("userID")).equals(text)) {
                score += 5.0;
            }
            if (score <= 0) continue;
            long ageHours = Math.max(0,(now - row.getLongValue("messageTime")) / 3600000L);
            score += 1.0 / (1.0 + ageHours / 72.0);
            scored.add(new Scored(row,score));
        }
        Collections.sort(scored,new Comparator<Scored>() {
            @Override
            public int compare(Scored left,Scored right) {
                return Double.compare(right.score,left.score);
            }
        });
        for (int i = 0; i < scored.size() && i < max; i++) {
            JSONObject row = scored.get(i).row;
            JSONObject item = new JSONObject(true);
            item.put("userID",row.getLongValue("userID"));
            item.put("userName",service.safe(row.getString("userName")));
            item.put("isBot",row.getIntValue("isBot") == 1);
            item.put("content",service.safe(row.getString("content")));
            item.put("time",formatTime(row.getLongValue("messageTime")));
            item.put("score",Math.round(scored.get(i).score * 100.0) / 100.0);
            messages.add(item);
        }
        JSONObject result = new JSONObject(true);
        result.put("messages",messages);
        result.put("windowDays",windowDays);
        result.put("candidateCount",rows.size());
        return result;
    }

    private String formatTime(long time) {
        if (time <= 0) return "";
        return new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date(time));
    }

    private static class Scored {
        private final JSONObject row;
        private final double score;

        private Scored(JSONObject row,double score) {
            this.row = row;
            this.score = score;
        }
    }
}
