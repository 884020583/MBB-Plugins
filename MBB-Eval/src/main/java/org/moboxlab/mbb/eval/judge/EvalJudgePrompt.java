package org.moboxlab.mbb.eval.judge;

import java.util.ArrayList;
import java.util.List;

import org.moboxlab.mbb.eval.rubric.EvalRubric;

/**
 * 单维 judge 提示词装配器（规格 §6.3.3）。
 *
 * <p>system 段只含判分职责、该维三档锚点、正反例与「先 evidence 后 label」的输出顺序；
 * user 段按固定顺序给出角色档案摘要、上下文、题目指令与 focus、被测回复、单维 rubric 重述
 * 与确定性事实观察。任何分数、生成参数、judge 自身信息（模型名 / profile / 温度）与其他题目
 * 一律不得进入 prompt。</p>
 */
public final class EvalJudgePrompt {

    /** judge prompt 结构版本；与 rubric 的 promptVersion 不一致时拒绝跑（§6.2.3）。 */
    public static final String PROMPT_VERSION = "1";

    /** 上下文总长上限，按码点计（§6.3.3）。 */
    public static final int CONTEXT_MAX_CHARS = 3000;
    /** 非记忆题保留的最近轮数（§6.3.3）。 */
    public static final int RECENT_TURNS = 8;
    /** 记忆题证据轮次前后各保留的原文轮数（§6.3.3）。 */
    public static final int EVIDENCE_WINDOW = 2;
    /** 角色档案摘要上限，按码点计（§6.3.3）。 */
    public static final int PERSONA_MAX_CHARS = 400;
    /** 记忆题其余轮次单行摘要保留的字数（§6.3.3）。 */
    public static final int SUMMARY_PREFIX_CHARS = 30;

    private final String dimensionId;
    private final String system;
    private final String user;

    private EvalJudgePrompt(String dimensionId, String system, String user) {
        this.dimensionId = dimensionId;
        this.system = system;
        this.user = user;
    }

    /** 按请求装配 prompt；request 必须已通过 {@link EvalJudgeRequest.Builder#build()}。 */
    public static EvalJudgePrompt build(EvalJudgeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不得为空");
        }
        EvalRubric.Dimension dimension = request.dimension();
        return new EvalJudgePrompt(dimension.id(), buildSystem(dimension), buildUser(request));
    }

    public String dimensionId() {
        return dimensionId;
    }

    public String system() {
        return system;
    }

    public String user() {
        return user;
    }

    /** system + 空行 + user，便于注入层直接作为一次对话内容。 */
    public String text() {
        return system + "\n\n" + user;
    }

    private static String buildSystem(EvalRubric.Dimension dimension) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 MBB-Roleplay 的判分员，本次只负责一个维度：").append(dimension.id()).append("。\n");
        sb.append("只判这一条 rubric，不得评判其他维度，也不得给出总分。\n\n");
        sb.append("三档尺度（label 只能是 0、1 或 2）：\n");
        appendAnchors(sb, dimension);
        sb.append("\n正例：\n");
        for (EvalRubric.Example example : dimension.examples()) {
            sb.append("- ").append(example.positive()).append("\n");
        }
        sb.append("\n反例：\n");
        for (EvalRubric.Example example : dimension.examples()) {
            sb.append("- ").append(example.negative()).append("\n");
        }
        sb.append("\n判分步骤：先在被测回复中找证据，再按锚点定档；输出顺序必须是先 evidence 后 label。\n");
        sb.append("输出要求：只输出一个 JSON 对象，不要解释，不要 Markdown 代码块，不要多余字段：\n");
        sb.append("{\"evidence\": \"回复中「……」至少 6 字原文引用，或写「回复未涉及」\", \"label\": \"2\"}\n");
        return sb.toString();
    }

    private static String buildUser(EvalJudgeRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("【角色档案摘要】\n").append(request.personaSummary()).append("\n\n");
        sb.append("【上下文】\n").append(buildContext(request)).append("\n\n");
        sb.append("【题目指令】\n").append(placeholder(request.instruction())).append("\n\n");
        sb.append("【考察点】\n").append(placeholder(request.focus())).append("\n\n");
        sb.append("【被测回复】\n").append(request.replyContent()).append("\n\n");
        sb.append("【本维 rubric 重述】\n");
        appendAnchors(sb, request.dimension());
        sb.append("\n【确定性事实观察】\n");
        if (request.softObservations().isEmpty()) {
            sb.append("无（未提供事实观察）。\n");
        } else {
            for (String observation : request.softObservations()) {
                sb.append("- ").append(observation).append("\n");
            }
        }
        return sb.toString();
    }

    private static void appendAnchors(StringBuilder sb, EvalRubric.Dimension dimension) {
        for (int label = 0; label <= 2; label++) {
            String anchor = dimension.scaleAnchor(label);
            sb.append("- ").append(label).append("：")
                    .append(anchor == null || anchor.isEmpty() ? "（未提供锚点）" : anchor).append("\n");
        }
    }

    private static String placeholder(String value) {
        return value == null || value.trim().isEmpty() ? "无。" : value;
    }

    /**
     * 按 §6.3.3 选择上下文窗口：非记忆题取最近 8 轮原文；记忆题取证据轮次前后各 2 轮原文，
     * 其余压成「speaker：前 30 字」单行摘要。超过 3000 码点时先丢最旧的非必要行。
     */
    private static String buildContext(EvalJudgeRequest request) {
        List<EvalJudgeRequest.Turn> turns = request.history();
        if (turns.isEmpty()) {
            return "（无历史上下文）";
        }
        List<Line> lines = new ArrayList<Line>();
        boolean memory = "memory".equals(request.dimensionId());
        if (!memory) {
            int from = Math.max(0, turns.size() - RECENT_TURNS);
            for (int i = from; i < turns.size(); i++) {
                lines.add(new Line(renderFull(turns.get(i)), true));
            }
        } else {
            boolean[] fullText = new boolean[turns.size()];
            for (Integer index : request.evidenceTurns()) {
                int center = index.intValue();
                int from = Math.max(0, center - EVIDENCE_WINDOW);
                int to = Math.min(turns.size() - 1, center + EVIDENCE_WINDOW);
                for (int i = from; i <= to; i++) {
                    fullText[i] = true;
                }
            }
            for (int i = 0; i < turns.size(); i++) {
                lines.add(fullText[i]
                        ? new Line(renderFull(turns.get(i)), true)
                        : new Line(renderSummary(turns.get(i)), false));
            }
        }
        return joinWithinLimit(lines);
    }

    private static String joinWithinLimit(List<Line> lines) {
        while (lines.size() > 1 && totalChars(lines) > CONTEXT_MAX_CHARS) {
            int drop = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).essential) {
                    drop = i;
                    break;
                }
            }
            if (drop < 0) {
                drop = 0;
            }
            lines.remove(drop);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(lines.get(i).text);
        }
        return truncate(sb.toString(), CONTEXT_MAX_CHARS);
    }

    private static int totalChars(List<Line> lines) {
        int total = 0;
        for (int i = 0; i < lines.size(); i++) {
            total += lines.get(i).text.codePointCount(0, lines.get(i).text.length());
            if (i > 0) {
                total++;
            }
        }
        return total;
    }

    private static String renderFull(EvalJudgeRequest.Turn turn) {
        return nameOf(turn) + "：" + turn.text();
    }

    private static String renderSummary(EvalJudgeRequest.Turn turn) {
        String text = turn.text();
        int length = text.codePointCount(0, text.length());
        if (length <= SUMMARY_PREFIX_CHARS) {
            return nameOf(turn) + "：" + text;
        }
        return nameOf(turn) + "：" + truncate(text, SUMMARY_PREFIX_CHARS) + "…";
    }

    private static String nameOf(EvalJudgeRequest.Turn turn) {
        if ("self".equals(turn.speaker())) {
            return "我";
        }
        String name = turn.userName();
        return name == null || name.trim().isEmpty() ? "对方" : name;
    }

    /** 按码点截断，避免把代理对截成半个字符。 */
    static String truncate(String text, int maxCodePoints) {
        if (text == null) {
            return "";
        }
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
    }

    /** 上下文中的一行：necessary 行为证据窗口原文，其余为可丢弃的摘要行。 */
    private static final class Line {
        private final String text;
        private final boolean essential;

        private Line(String text, boolean essential) {
            this.text = text;
            this.essential = essential;
        }
    }
}
