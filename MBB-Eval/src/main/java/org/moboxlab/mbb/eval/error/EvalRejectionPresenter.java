package org.moboxlab.mbb.eval.error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.moboxlab.mbb.eval.bank.EvalBankCache;
import org.moboxlab.mbb.eval.rubric.EvalRubricCache;
import org.moboxlab.mbb.eval.runner.EvalPreflight;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.Stage;

/**
 * 预检拒绝回执呈现（规格 #50 切片 H-1；§13.2、§13.6、§16.9，验收 H1/H2/H3）。
 *
 * <p>把 {@link EvalFailure} 渲染成固定顺序的多行用户回执：结论头（阶段｜错误码）、
 * 错误码中文说明、位置（绝对路径）、最多 5 条明细、总数、修复建议、只读命令可用性。
 * 同一输入两次渲染逐字节相同；未知错误码不回声、只标记为未登记。</p>
 *
 * <p>命令层只需逐行发送 {@link #render(EvalFailure)}；onEnable 失败时取
 * {@link #startupSummary(EvalFailure)} 记一条 WARN，保证启动期与命令期文案一致
 * （§13.2 判定规则 3）。本类不建 run、不调用评测接口。</p>
 */
public final class EvalRejectionPresenter {

    /** 用户回执统一前缀（§13.6）。 */
    public static final String PREFIX = "[eval] ";
    /** 内部日志统一前缀（§13.6）。 */
    private static final String LOG_PREFIX = "[评测] ";
    /** 单次回执最多回显的明细条数（票 #42）。 */
    public static final int MAX_DETAILS = 5;
    /** 未知错误码的占位文案；不得回显原始码。 */
    public static final String UNKNOWN_CODE_LABEL = "未登记错误码";
    /** 拒绝后仍可用的只读出口（H2：/eval report 对历史 COMPLETED run 仍可用）。 */
    public static final String REPORT_HINT = "只读命令仍可用：/eval report <runId> 可读取数据库中的历史 "
            + "COMPLETED run；/eval status、/eval list 等只读子命令不受影响";

    private static final String UNKNOWN_REASON = "该错误码不在封闭错误码集合内，请把插件日志反馈给维护者";
    private static final String GENERIC_HINT = "请查看插件日志中的完整错误信息并反馈维护者；"
            + "修复后重跑 /eval run；/eval report 仍可用";
    private static final String UNKNOWN_MESSAGE = "预检未通过（未提供具体原因，请查看插件日志）";

    private static final Map<Stage, String> STAGE_HINTS = new EnumMap<Stage, String>(Stage.class);

    static {
        STAGE_HINTS.put(Stage.CAPABILITIES,
                "请安装并启用 MBB-Roleplay，并确认其版本支持 evalApiVersion=1；升级后重启 MBB-Eval");
        STAGE_HINTS.put(Stage.BANK,
                "请按错误清单里的 JSON path 修正题库文件后重启插件（运行期不热重载）；修复后重跑 /eval run");
        STAGE_HINTS.put(Stage.RUBRIC,
                "请修正 rubric 文件（或其 version/promptVersion）后重启插件；修复后重跑 /eval run");
        STAGE_HINTS.put(Stage.PERSONA,
                "请对齐题库 personaId/personaHash 与当前 persona，或设置 evalAllowPersonaMismatch=true 后重启");
        STAGE_HINTS.put(Stage.JUDGE_PROFILE,
                "请设置 evalJudgeProfile 或 evalProfile 指向可用的 judge profile 后重启插件");
        STAGE_HINTS.put(Stage.MBB_AI,
                "请检查 aiHost 配置与 MBB-AI 服务状态（未启用或地址不可达时无法发起评测），修复后重跑 /eval run");
        STAGE_HINTS.put(Stage.DATA_DIR,
                "请确认 plugins/MBB-Roleplay/evals 目录存在且可读后重启插件；/eval report 仍可用");
        STAGE_HINTS.put(Stage.BUDGET,
                "请调大 evalCallBudget（上限 100）后重跑 /eval run，或改跑 /eval run smoke");
    }

    private EvalRejectionPresenter() {
    }

    /** 逐行回执；每行都带 [eval] 前缀。 */
    public static List<String> render(EvalFailure failure) {
        if (failure == null) {
            throw new IllegalArgumentException("预检失败对象不能为空");
        }
        List<String> lines = new ArrayList<String>();
        boolean reject = failure.isReject();
        lines.add(PREFIX + (reject ? "预检失败" : "预检告警") + placeOf(failure)
                + "：" + messageOf(failure));

        String code = failure.code();
        if (!code.isEmpty()) {
            lines.add(PREFIX + "错误码说明：" + (EvalErrorCatalog.isKnown(code)
                    ? EvalErrorCatalog.reason(code) : UNKNOWN_REASON));
        }

        String path = failure.absolutePath();
        if (!path.isEmpty() && !failure.message().contains(path)
                && !failure.hint().contains(path)) {
            lines.add(PREFIX + "位置：" + path);
        }

        int shown = 0;
        for (String detail : failure.details()) {
            if (shown >= MAX_DETAILS) {
                break;
            }
            lines.add(PREFIX + (shown + 1) + ". " + detail);
            shown++;
        }

        int total = failure.total();
        if (shown > 0 && total > shown) {
            lines.add(PREFIX + "仅回显前 " + shown + " 条，共 " + total
                    + " 个错误；完整清单见插件日志");
        } else if (shown > 0) {
            lines.add(PREFIX + "以上 " + shown + " 条为全部错误，完整清单见插件日志");
        } else if (total > 0) {
            lines.add(PREFIX + "共 " + total + " 个错误；完整清单见插件日志");
        }

        lines.add(PREFIX + "修复建议：" + repairOf(failure));
        if (reject) {
            lines.add(PREFIX + REPORT_HINT);
        }
        return Collections.unmodifiableList(lines);
    }

    /** 多行回执文本（行间用换行符连接）；供命令层一次发送。 */
    public static String renderText(EvalFailure failure) {
        return String.join("\n", render(failure));
    }

    /** onEnable 摘要：单行 WARN 日志，复用加载消息与修复建议。 */
    public static String startupSummary(EvalFailure failure) {
        if (failure == null) {
            throw new IllegalArgumentException("预检失败对象不能为空");
        }
        StringBuilder summary = new StringBuilder(LOG_PREFIX);
        summary.append("预检未通过").append(placeOf(failure)).append("：")
                .append(messageOf(failure));
        int shown = Math.min(failure.details().size(), MAX_DETAILS);
        if (failure.total() > 0) {
            summary.append("；共 ").append(failure.total()).append(" 个错误");
            if (failure.total() > shown) {
                summary.append("，仅回显前 ").append(shown).append(" 条");
            }
        }
        if (!failure.absolutePath().isEmpty()) {
            summary.append("；位置：").append(failure.absolutePath());
        }
        if (!failure.details().isEmpty()) {
            summary.append("；首个问题：").append(failure.details().get(0));
        }
        summary.append("；修复建议：").append(repairOf(failure));
        return summary.toString();
    }

    /** 题库加载失败的 onEnable 摘要。 */
    public static String startupSummary(EvalBankCache cache) {
        return startupSummary(EvalFailure.bank(cache));
    }

    /** rubric 加载失败的 onEnable 摘要。 */
    public static String startupSummary(EvalRubricCache cache) {
        return startupSummary(EvalFailure.rubric(cache));
    }

    private static String messageOf(EvalFailure failure) {
        return failure.message().isEmpty() ? UNKNOWN_MESSAGE : failure.message();
    }

    /** 结论头里的定位：阶段与错误码组合，未知码只显示占位。 */
    private static String placeOf(EvalFailure failure) {
        String codeLabel = failure.code().isEmpty()
                ? "" : (EvalErrorCatalog.isKnown(failure.code())
                        ? failure.code() : UNKNOWN_CODE_LABEL);
        Stage stage = failure.stage();
        if (stage == null && codeLabel.isEmpty()) {
            return "";
        }
        if (stage == null) {
            return "（" + codeLabel + "）";
        }
        if (codeLabel.isEmpty()) {
            return "（" + stage.label() + "）";
        }
        return "（" + stage.label() + "｜" + codeLabel + "）";
    }

    /** 修复建议优先级：已有 hint → 错误码目录 → 阶段兜底 → 通用。 */
    private static String repairOf(EvalFailure failure) {
        if (!failure.hint().isEmpty()) {
            return failure.hint();
        }
        if (EvalErrorCatalog.isKnown(failure.code())) {
            return EvalErrorCatalog.nextAction(failure.code());
        }
        Stage stage = failure.stage();
        if (stage != null && STAGE_HINTS.containsKey(stage)) {
            return STAGE_HINTS.get(stage);
        }
        return GENERIC_HINT;
    }
}
