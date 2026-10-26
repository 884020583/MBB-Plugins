package org.moboxlab.mbb.eval.judge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.moboxlab.mbb.eval.gateway.EvalGenerateRequest;
import org.moboxlab.mbb.eval.rubric.EvalRubric;

/**
 * 一次单维 judge 调用的输入（规格 §6.3.1、§6.3.3）。
 *
 * <p>只承载一条维度的一条 rubric：调用方必须按「一维一调用」逐条构造，不得把多个维度
 * 或多个题目合并进来。{@code history} 是判分上下文，{@code evidenceTurns} 只对记忆维有意义；
 * {@code replyContent} 是被测回复原文，解析 evidence 时用于校验引用是否真实存在。</p>
 */
public final class EvalJudgeRequest {

    /** judge 维度封闭集合：只有这三维走 LLM judge（§6.1）。 */
    private static final List<String> JUDGE_DIMENSIONS = Collections.unmodifiableList(
            java.util.Arrays.asList("persona", "boundary", "memory"));

    private static final Pattern ID_PATTERN = Pattern.compile(EvalGenerateRequest.ID_PATTERN_TEXT);

    private final String runId;
    private final String caseId;
    private final int sampleIndex;
    private final int attempt;
    private final EvalRubric.Dimension dimension;
    private final String personaSummary;
    private final List<Turn> history;
    private final List<Integer> evidenceTurns;
    private final String instruction;
    private final String focus;
    private final String replyContent;
    private final List<String> softObservations;
    private final String promptVersion;

    private EvalJudgeRequest(Builder builder) {
        this.runId = builder.runId;
        this.caseId = builder.caseId;
        this.sampleIndex = builder.sampleIndex;
        this.attempt = builder.attempt;
        this.dimension = builder.dimension;
        this.personaSummary = builder.personaSummary;
        this.history = Collections.unmodifiableList(new ArrayList<Turn>(builder.history));
        this.evidenceTurns = Collections.unmodifiableList(new ArrayList<Integer>(builder.evidenceTurns));
        this.instruction = builder.instruction;
        this.focus = builder.focus;
        this.replyContent = builder.replyContent;
        this.softObservations = Collections.unmodifiableList(new ArrayList<String>(builder.softObservations));
        this.promptVersion = builder.promptVersion;
    }

    public static Builder builder(String runId, String caseId) {
        return new Builder(runId, caseId);
    }

    public String runId() {
        return runId;
    }

    public String caseId() {
        return caseId;
    }

    public int sampleIndex() {
        return sampleIndex;
    }

    public int attempt() {
        return attempt;
    }

    public EvalRubric.Dimension dimension() {
        return dimension;
    }

    /** 维度 id，取自 rubric 文件，不在代码里硬编码。 */
    public String dimensionId() {
        return dimension.id();
    }

    /** 角色档案摘要，1–400 码点（§6.3.3）。 */
    public String personaSummary() {
        return personaSummary;
    }

    public List<Turn> history() {
        return history;
    }

    /** 记忆维的证据轮次下标（0 基）；其他维度为空列表。 */
    public List<Integer> evidenceTurns() {
        return evidenceTurns;
    }

    public String instruction() {
        return instruction;
    }

    /** 题目考察点；只进 prompt 与报表，不参与打分（§6.3.3）。 */
    public String focus() {
        return focus;
    }

    public String replyContent() {
        return replyContent;
    }

    /** 确定性预检的软项事实观察，不含任何分数（§6.3.5）。 */
    public List<String> softObservations() {
        return softObservations;
    }

    public String promptVersion() {
        return promptVersion;
    }

    /** 一条上下文发言；{@code speaker} 只接受 self / peer / otherRole。 */
    public static final class Turn {

        private final String speaker;
        private final String userName;
        private final String text;

        public Turn(String speaker, String userName, String text) {
            this.speaker = speaker;
            this.userName = userName;
            this.text = text;
        }

        public String speaker() {
            return speaker;
        }

        public String userName() {
            return userName;
        }

        public String text() {
            return text;
        }
    }

    /** 组装与校验；非法输入一律抛 {@link IllegalArgumentException}，不做静默回退。 */
    public static final class Builder {

        private final String runId;
        private final String caseId;
        private int sampleIndex;
        private int attempt = 0;
        private EvalRubric.Dimension dimension;
        private String personaSummary;
        private final List<Turn> history = new ArrayList<Turn>();
        private final List<Integer> evidenceTurns = new ArrayList<Integer>();
        private String instruction = "";
        private String focus = "";
        private String replyContent;
        private final List<String> softObservations = new ArrayList<String>();
        private String promptVersion = EvalJudgePrompt.PROMPT_VERSION;

        private Builder(String runId, String caseId) {
            this.runId = runId;
            this.caseId = caseId;
        }

        public Builder sampleIndex(int value) {
            this.sampleIndex = value;
            return this;
        }

        public Builder attempt(int value) {
            this.attempt = value;
            return this;
        }

        /** 必须是 persona / boundary / memory 之一，且来自已加载的 rubric。 */
        public Builder dimension(EvalRubric.Dimension value) {
            this.dimension = value;
            return this;
        }

        public Builder personaSummary(String value) {
            this.personaSummary = value;
            return this;
        }

        public Builder history(List<Turn> value) {
            this.history.clear();
            if (value != null) {
                this.history.addAll(value);
            }
            return this;
        }

        public Builder evidenceTurns(List<Integer> value) {
            this.evidenceTurns.clear();
            if (value != null) {
                this.evidenceTurns.addAll(value);
            }
            return this;
        }

        public Builder instruction(String value) {
            this.instruction = value == null ? "" : value;
            return this;
        }

        public Builder focus(String value) {
            this.focus = value == null ? "" : value;
            return this;
        }

        public Builder replyContent(String value) {
            this.replyContent = value;
            return this;
        }

        public Builder softObservations(List<String> value) {
            this.softObservations.clear();
            if (value != null) {
                this.softObservations.addAll(value);
            }
            return this;
        }

        /** 上游 rubric 的 promptVersion；与代码支持版本不一致时 judge 拒绝调用。 */
        public Builder promptVersion(String value) {
            this.promptVersion = value;
            return this;
        }

        public EvalJudgeRequest build() {
            requireId("runId", runId);
            requireId("caseId", caseId);
            if (sampleIndex < 0 || sampleIndex > EvalGenerateRequest.MAX_SAMPLE_INDEX) {
                throw new IllegalArgumentException("sampleIndex 越界：" + sampleIndex + "，取值 0.."
                        + EvalGenerateRequest.MAX_SAMPLE_INDEX);
            }
            if (attempt < 0 || attempt > EvalGenerateRequest.MAX_ATTEMPT) {
                throw new IllegalArgumentException("attempt 越界：" + attempt + "，取值 0.."
                        + EvalGenerateRequest.MAX_ATTEMPT);
            }
            if (dimension == null) {
                throw new IllegalArgumentException("缺少维度：judge 一次只判一条 rubric");
            }
            if (!JUDGE_DIMENSIONS.contains(dimension.id())) {
                throw new IllegalArgumentException("维度 " + dimension.id()
                        + " 不走 LLM judge，只接受 persona / boundary / memory（§6.1）");
            }
            if (personaSummary == null || personaSummary.trim().isEmpty()) {
                throw new IllegalArgumentException("缺少角色档案摘要 personaSummary");
            }
            String summary = personaSummary.trim();
            int summaryLength = summary.codePointCount(0, summary.length());
            if (summaryLength > EvalJudgePrompt.PERSONA_MAX_CHARS) {
                throw new IllegalArgumentException("角色档案摘要超过 " + EvalJudgePrompt.PERSONA_MAX_CHARS
                        + " 码点，实际 " + summaryLength);
            }
            for (int i = 0; i < history.size(); i++) {
                Turn turn = history.get(i);
                if (turn == null) {
                    throw new IllegalArgumentException("history[" + i + "] 为空");
                }
                if (!"self".equals(turn.speaker()) && !"peer".equals(turn.speaker())
                        && !"otherRole".equals(turn.speaker())) {
                    throw new IllegalArgumentException("history[" + i + "].speaker 非法：" + turn.speaker()
                            + "，只接受 self / peer / otherRole");
                }
                if (turn.text() == null) {
                    throw new IllegalArgumentException("history[" + i + "].text 为 null");
                }
            }
            for (int i = 0; i < evidenceTurns.size(); i++) {
                Integer index = evidenceTurns.get(i);
                if (index == null || index.intValue() < 0 || index.intValue() >= history.size()) {
                    throw new IllegalArgumentException("evidenceTurns[" + i + "] 越界：" + index
                            + "，history 共 " + history.size() + " 条");
                }
            }
            if (replyContent == null) {
                throw new IllegalArgumentException("缺少被测回复 replyContent");
            }
            if (promptVersion == null) {
                throw new IllegalArgumentException("缺少 promptVersion");
            }
            Builder validated = this;
            validated.personaSummary = summary;
            return new EvalJudgeRequest(validated);
        }

        private static void requireId(String field, String value) {
            if (value == null || !ID_PATTERN.matcher(value).matches()) {
                throw new IllegalArgumentException(field + " 非法：" + value + "，必须匹配 "
                        + EvalGenerateRequest.ID_PATTERN_TEXT);
            }
        }
    }
}
