package org.moboxlab.mbb.eval.error;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.moboxlab.mbb.eval.bank.EvalBankCache;
import org.moboxlab.mbb.eval.bank.EvalBankLocator;
import org.moboxlab.mbb.eval.bank.EvalBankProblem;
import org.moboxlab.mbb.eval.rubric.EvalRubricCache;
import org.moboxlab.mbb.eval.runner.EvalPreflight;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.Stage;

/**
 * 预检失败的归一化值对象（规格 #50 切片 H-1；§13.1.1、§13.2）。
 *
 * <p>把 EvalPreflight 的拒绝结果、题库/rubric 加载结果与数据目录检查结果收敛成
 * 同一形状：阶段、错误码、中文消息、修复建议、明细、错误总数、绝对路径与处置方式。
 * 呈现层 {@link EvalRejectionPresenter} 只依赖本对象，启动期日志与命令期回执因此
 * 复用同一套文案（§13.2 判定规则 3）。</p>
 *
 * <p>不可变：所有字段构造后只读，withXxx 返回新实例，保证同一输入重复渲染逐字节稳定。</p>
 */
public final class EvalFailure {

    private final Stage stage;
    private final String code;
    private final String message;
    private final String hint;
    private final List<String> details;
    private final int total;
    private final String absolutePath;
    private final EvalPreflight.Disposition disposition;

    private EvalFailure(Stage stage, String code, String message, String hint,
            List<String> details, int total, String absolutePath,
            EvalPreflight.Disposition disposition) {
        this.stage = stage;
        this.code = nullToEmpty(code);
        this.message = nullToEmpty(message);
        this.hint = nullToEmpty(hint);
        List<String> copied = new ArrayList<String>();
        if (details != null) {
            for (String detail : details) {
                if (detail != null && !detail.trim().isEmpty()) {
                    copied.add(detail);
                }
            }
        }
        this.details = Collections.unmodifiableList(copied);
        this.total = Math.max(total, this.details.size());
        this.absolutePath = nullToEmpty(absolutePath);
        this.disposition = disposition == null
                ? EvalPreflight.Disposition.REJECT : disposition;
    }

    /** 预检拒绝结果；缺少阶段时按无阶段拒绝呈现。 */
    public static EvalFailure of(EvalPreflight.Rejection rejection) {
        if (rejection == null) {
            throw new IllegalArgumentException("预检拒绝结果不能为空");
        }
        return new EvalFailure(rejection.stage(), rejection.code(), rejection.message(),
                rejection.hint(), rejection.details(), rejection.details().size(), "",
                rejection.disposition());
    }

    /** 无阶段拒绝（A-3 兼容）：错误码、消息、提示与明细直接给。 */
    public static EvalFailure of(String code, String message, String hint,
            List<String> details) {
        return ofStage(null, code, message, hint, details);
    }

    /** 某阶段拒绝（无明细，与 ofStage 等价的简写）。 */
    public static EvalFailure of(Stage stage, String code, String message, String hint) {
        return ofStage(stage, code, message, hint);
    }

    /** 某阶段拒绝（无明细）。 */
    public static EvalFailure ofStage(Stage stage, String code, String message, String hint) {
        return ofStage(stage, code, message, hint, null);
    }

    /** 某阶段拒绝。 */
    public static EvalFailure ofStage(Stage stage, String code, String message, String hint,
            List<String> details) {
        return new EvalFailure(stage, code, message, hint, details,
                details == null ? 0 : details.size(), "", EvalPreflight.Disposition.REJECT);
    }

    /** 题库加载结果；cache 为空表示 onEnable 初始化尚未完成。 */
    public static EvalFailure bank(EvalBankCache cache) {
        if (cache == null) {
            return ofStage(Stage.BANK, EvalBankProblem.READ,
                    "题库尚未加载：插件启用时的初始化未完成",
                    "请重启插件后重跑 /eval run；/eval report 仍可用");
        }
        int total = cache.check() == null
                ? cache.errors().size() : cache.check().problems().size();
        return new EvalFailure(Stage.BANK, cache.code(), cache.message(), cache.hint(),
                cache.errors(), total, cache.absolutePath(), EvalPreflight.Disposition.REJECT);
    }

    /** rubric 加载结果；cache 为空表示 onEnable 初始化尚未完成。 */
    public static EvalFailure rubric(EvalRubricCache cache) {
        if (cache == null) {
            return ofStage(Stage.RUBRIC, EvalBankProblem.READ,
                    "rubric 尚未加载：插件启用时的初始化未完成",
                    "请重启插件后重跑 /eval run；/eval report 仍可用");
        }
        return new EvalFailure(Stage.RUBRIC, cache.code(), cache.message(), cache.hint(),
                cache.errors(), cache.errors().size(), cache.absolutePath(),
                EvalPreflight.Disposition.REJECT);
    }

    /** 数据目录不可用（H2）；路径为实际查找的 evals 目录绝对路径。 */
    public static EvalFailure dataDirectory(File evalsDirectory) {
        String absolute = evalsDirectory == null ? "" : evalsDirectory.getAbsolutePath();
        String message = absolute.isEmpty()
                ? "评测数据目录不可用：无法从插件数据目录推导 evals 路径"
                : "评测数据目录不可用：" + absolute;
        return new EvalFailure(Stage.DATA_DIR, EvalBankLocator.CODE_DATA_NOT_FOUND,
                message, "", null, 0, absolute, EvalPreflight.Disposition.REJECT);
    }

    /** 追加明细并给出错误总数；返回新实例。 */
    public EvalFailure withDetails(List<String> newDetails, int newTotal) {
        return new EvalFailure(stage, code, message, hint, newDetails, newTotal,
                absolutePath, disposition);
    }

    /** 替换错误总数；返回新实例。 */
    public EvalFailure withTotal(int newTotal) {
        return new EvalFailure(stage, code, message, hint, details, newTotal,
                absolutePath, disposition);
    }

    /** 补充绝对路径（题库/rubric 文件或 evals 目录）；返回新实例。 */
    public EvalFailure withAbsolutePath(String newAbsolutePath) {
        return new EvalFailure(stage, code, message, hint, details, total,
                newAbsolutePath, disposition);
    }

    /** 命中的预检阶段；A-3 兼容构造可能为 null。 */
    public Stage stage() {
        return stage;
    }

    /** 错误码；未定义专用码时为空串。 */
    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    /** 修复建议；可为空串，由呈现层回落到错误码目录或阶段兜底。 */
    public String hint() {
        return hint;
    }

    /** 已截断或全量的明细（题库错误清单等），保持输入顺序。 */
    public List<String> details() {
        return details;
    }

    /** 错误总数（可能大于明细条数）。 */
    public int total() {
        return total;
    }

    /** 相关绝对路径；无路径时为空串。 */
    public String absolutePath() {
        return absolutePath;
    }

    public EvalPreflight.Disposition disposition() {
        return disposition;
    }

    /** 是否为拒绝（false 表示降级试跑告警）。 */
    public boolean isReject() {
        return disposition != EvalPreflight.Disposition.TRIAL;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
