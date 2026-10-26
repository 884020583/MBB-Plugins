package org.moboxlab.mbb.eval.deterministic;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识维确定性判分器（规格 §6.4）：choice 与 points 两种模式，零 judge 调用。
 *
 * <p>输入是题库题目的 {@code knowledge} 节点与 rubric 的
 * {@code deterministic.knowledge} 节点（均由 fastjson 传入），本类不读文件、不写库。</p>
 *
 * <p>choice：正确项命中且干扰项一个都不命中记 1，否则 0（§6.4.1）。
 * points：题分 = 命中点数 / 期望点数；命中 {@code negative} 任一即 0（§6.4.2）。</p>
 */
public final class EvalKnowledgeScorer {

    private final EvalDeterministicNormalizer normalizer;

    /**
     * @param deterministicKnowledge rubric 的 {@code deterministic.knowledge} 节点
     */
    public EvalKnowledgeScorer(JSONObject deterministicKnowledge) {
        this.normalizer = EvalDeterministicNormalizer.fromRubric(deterministicKnowledge);
    }

    /**
     * 判一道知识题。
     *
     * @param knowledge 题库题目的 {@code knowledge} 节点
     * @param content   模型回复原文
     * @return 判分明细与题分
     */
    public EvalKnowledgeResult score(JSONObject knowledge, String content) {
        if (knowledge == null) {
            throw new IllegalArgumentException("题目缺少 knowledge 节点");
        }
        String mode = knowledge.getString("mode");
        if (EvalKnowledgeResult.MODE_CHOICE.equals(mode)) {
            return scoreChoice(knowledge, content);
        }
        if (EvalKnowledgeResult.MODE_POINTS.equals(mode)) {
            return scorePoints(knowledge, content);
        }
        throw new IllegalArgumentException("未知的 knowledge.mode：" + mode);
    }

    private EvalKnowledgeResult scoreChoice(JSONObject knowledge, String content) {
        JSONArray choices = knowledge.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalArgumentException("choice 题缺少 knowledge.choices");
        }
        JSONArray unitAliases = knowledge.getJSONArray("unitAliases");
        int correctCount = 0;
        boolean correctHit = false;
        boolean distractorHit = false;
        List<EvalKnowledgeResult.Hit> hits = new ArrayList<EvalKnowledgeResult.Hit>();
        for (int i = 0; i < choices.size(); i++) {
            JSONObject choice = choices.getJSONObject(i);
            String text = choice.getString("text");
            boolean matched = normalizer.matches(content, text, unitAliases);
            if (choice.getBooleanValue("correct")) {
                correctCount++;
                correctHit = matched;
            } else if (matched) {
                distractorHit = true;
            }
            hits.add(new EvalKnowledgeResult.Hit(text, matched, matched ? text : null));
        }
        if (correctCount != 1) {
            throw new IllegalArgumentException("choice 题必须恰好 1 个正确项，实际 " + correctCount);
        }
        double caseScore = correctHit && !distractorHit ? 1.0 : 0.0;
        return new EvalKnowledgeResult(EvalKnowledgeResult.MODE_CHOICE, hits, 1, false, caseScore);
    }

    private EvalKnowledgeResult scorePoints(JSONObject knowledge, String content) {
        JSONArray expected = knowledge.getJSONArray("expected");
        if (expected == null || expected.isEmpty()) {
            throw new IllegalArgumentException("points 题缺少 knowledge.expected");
        }
        JSONArray unitAliases = knowledge.getJSONArray("unitAliases");
        List<EvalKnowledgeResult.Hit> hits = new ArrayList<EvalKnowledgeResult.Hit>();
        int hitCount = 0;
        for (int i = 0; i < expected.size(); i++) {
            JSONObject point = expected.getJSONObject(i);
            String label = point.getString("label");
            JSONArray aliases = point.getJSONArray("aliases");
            if (aliases == null || aliases.isEmpty()) {
                throw new IllegalArgumentException("期望点缺少 aliases：" + label);
            }
            String matchedAlias = firstMatchedAlias(content, aliases, unitAliases);
            if (matchedAlias != null) {
                hitCount++;
            }
            hits.add(new EvalKnowledgeResult.Hit(label, matchedAlias != null, matchedAlias));
        }
        boolean negativeHit = matchesAny(content, knowledge.getJSONArray("negative"), unitAliases);
        double caseScore = negativeHit ? 0.0 : (double) hitCount / expected.size();
        return new EvalKnowledgeResult(EvalKnowledgeResult.MODE_POINTS, hits, expected.size(),
                negativeHit, caseScore);
    }

    private String firstMatchedAlias(String content, JSONArray aliases, JSONArray unitAliases) {
        for (int i = 0; i < aliases.size(); i++) {
            String alias = aliases.getString(i);
            if (normalizer.matches(content, alias, unitAliases)) {
                return alias;
            }
        }
        return null;
    }

    private boolean matchesAny(String content, JSONArray points, JSONArray unitAliases) {
        if (points == null) {
            return false;
        }
        for (int i = 0; i < points.size(); i++) {
            JSONObject point = points.getJSONObject(i);
            JSONArray aliases = point.getJSONArray("aliases");
            if (aliases != null && firstMatchedAlias(content, aliases, unitAliases) != null) {
                return true;
            }
        }
        return false;
    }
}
