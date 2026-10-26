package org.moboxlab.mbb.eval.deterministic;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 知识维判分明细（规格 §6.4.4）：可直接落到 {@code case_result} 的字段。
 *
 * <p>{@link #toJson()} 的键与规格逐字一致：{@code mode}、{@code hits[{point, matched, alias}]}、
 * {@code expectedTotal}、{@code negativeHit}、{@code caseScore}。</p>
 */
public final class EvalKnowledgeResult {

    /** 单选模式。 */
    public static final String MODE_CHOICE = "choice";
    /** 要点命中模式。 */
    public static final String MODE_POINTS = "points";

    /** 单个期望点（points）或单个选项（choice）的命中明细。 */
    public static final class Hit {
        private final String point;
        private final boolean matched;
        private final String alias;

        public Hit(String point, boolean matched, String alias) {
            this.point = point;
            this.matched = matched;
            this.alias = alias;
        }

        /** 期望点标签；choice 模式下为选项文本。 */
        public String point() {
            return point;
        }

        public boolean matched() {
            return matched;
        }

        /** 命中的 alias 原文；未命中为 null。 */
        public String alias() {
            return alias;
        }
    }

    private final String mode;
    private final List<Hit> hits;
    private final int expectedTotal;
    private final boolean negativeHit;
    private final double caseScore;

    public EvalKnowledgeResult(String mode, List<Hit> hits, int expectedTotal,
                               boolean negativeHit, double caseScore) {
        this.mode = mode;
        this.hits = Collections.unmodifiableList(new ArrayList<Hit>(hits));
        this.expectedTotal = expectedTotal;
        this.negativeHit = negativeHit;
        this.caseScore = caseScore;
    }

    public String mode() {
        return mode;
    }

    public List<Hit> hits() {
        return hits;
    }

    public int expectedTotal() {
        return expectedTotal;
    }

    public boolean negativeHit() {
        return negativeHit;
    }

    public double caseScore() {
        return caseScore;
    }

    /** 落 case_result 的明细字段。 */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("mode", mode);
        JSONArray hitArray = new JSONArray();
        for (Hit hit : hits) {
            JSONObject item = new JSONObject();
            item.put("point", hit.point());
            item.put("matched", hit.matched());
            if (hit.alias() != null) {
                item.put("alias", hit.alias());
            }
            hitArray.add(item);
        }
        json.put("hits", hitArray);
        json.put("expectedTotal", expectedTotal);
        json.put("negativeHit", negativeHit);
        json.put("caseScore", caseScore);
        return json;
    }
}
