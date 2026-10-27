package org.moboxlab.mbb.eval.deterministic;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 风格维判分明细（规格 §6.5.4）。
 *
 * <p>字段可直接落到 case_result：hardFails、soft、emojiCount 与 caseScore。
 * emoji 只观测不计分，硬项违规时软项不再计算。</p>
 */
public final class EvalStyleResult {

    private final List<String> hardFails;
    private final List<Soft> soft;
    private final int emojiCount;
    private final double caseScore;

    EvalStyleResult(List<String> hardFails, List<Soft> soft, int emojiCount, double caseScore) {
        this.hardFails = Collections.unmodifiableList(new ArrayList<String>(hardFails));
        this.soft = Collections.unmodifiableList(new ArrayList<Soft>(soft));
        this.emojiCount = emojiCount;
        this.caseScore = caseScore;
    }

    /** 违规的硬项 id，保持挂载顺序。 */
    public List<String> hardFails() {
        return hardFails;
    }

    /** 软项判定明细；硬项违规时为空。 */
    public List<Soft> soft() {
        return soft;
    }

    public int emojiCount() {
        return emojiCount;
    }

    /** 题分：硬项违规 0，否则软项通过数 / 挂载软项数（0 条软项记 1）。 */
    public double caseScore() {
        return caseScore;
    }

    /** 转成可落 case_result 的 JSON。 */
    public JSONObject toJson() {
        JSONObject json = new JSONObject(true);
        JSONArray fails = new JSONArray();
        fails.addAll(hardFails);
        json.put("hardFails", fails);
        JSONArray softArray = new JSONArray();
        for (Soft item : soft) {
            JSONObject one = new JSONObject(true);
            one.put("id", item.id());
            one.put("passed", item.passed());
            one.put("detail", item.detail());
            softArray.add(one);
        }
        json.put("soft", softArray);
        json.put("emojiCount", emojiCount);
        json.put("caseScore", caseScore);
        return json;
    }

    /** 单个软项的判定结果。 */
    public static final class Soft {

        private final String id;
        private final boolean passed;
        private final String detail;

        Soft(String id, boolean passed, String detail) {
            this.id = id;
            this.passed = passed;
            this.detail = detail;
        }

        public String id() {
            return id;
        }

        public boolean passed() {
            return passed;
        }

        /** 可复核的说明，例如「长度 9 码点，要求 [10, 120]」。 */
        public String detail() {
            return detail;
        }
    }
}
