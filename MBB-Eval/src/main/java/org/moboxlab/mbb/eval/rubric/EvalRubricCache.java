package org.moboxlab.mbb.eval.rubric;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.moboxlab.mbb.eval.bank.EvalBankLocator;
import org.moboxlab.mbb.eval.bank.EvalBankProblem;

/**
 * onEnable 期一次性加载的 rubric 缓存（B-5）。
 *
 * <p>按 docs/MBB-Eval.md §5.7.4，插件启用时先加载校验 rubric，再加载题库；rubric 失败时
 * 题库不加载。rubric 文件放在 {@code plugins/MBB-Roleplay/evals}，命名为
 * {@code rubric-<version>.json}（§6.2.1），当前没有配置键指定版本，因此由本类扫描目录：
 * 只有一个候选时直接使用；多个时取文件名最大者并记告警；一个都没有时报缺失。</p>
 *
 * <p>rubric 专属错误码尚未冻结（§17.1 O20），对外统一映射到 §13.1.1 的封闭集合：
 * 读取/缺失 → E_BANK_READ，JSON 语法 → E_BANK_JSON，版本或 promptVersion → E_BANK_VERSION，
 * 内容哈希 → E_BANK_HASH，维度 → E_BANK_DIMENSION，字段与权重 → E_BANK_FIELD。</p>
 */
public final class EvalRubricCache {

    private final File file;
    private final String absolutePath;
    private final EvalRubricLoadResult result;
    private final String code;
    private final String message;
    private final String hint;
    private final List<String> errors;
    private final List<String> warnings;
    private final String summary;

    private EvalRubricCache(File file, EvalRubricLoadResult result, String code, String message,
            String hint, List<String> errors, List<String> warnings, String summary) {
        this.file = file;
        this.absolutePath = file == null ? "" : file.getAbsolutePath();
        this.result = result;
        this.code = code;
        this.message = message;
        this.hint = hint;
        this.errors = Collections.unmodifiableList(new ArrayList<String>(errors));
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
        this.summary = summary;
    }

    /** 在 evals 目录中定位并加载 rubric；失败不抛异常。 */
    public static EvalRubricCache load(File dataFolder, String expectedPromptVersion) {
        File evals = EvalBankLocator.evalsDirectory(dataFolder);
        // 目录级失败按 §13.1.1 统一用 E_ROLEPLAY_DATA_NOT_FOUND，便于预检定位到目录问题。
        if (evals == null) {
            return failure(null, EvalBankLocator.CODE_DATA_NOT_FOUND,
                    "rubric 目录不可用：无法从插件数据目录推导 evals 路径",
                    "请检查插件数据目录结构后重启插件；/eval report 仍可用",
                    Collections.<String>emptyList());
        }
        if (!evals.isDirectory()) {
            return failure(new File(evals, "rubric-<version>.json"), EvalBankLocator.CODE_DATA_NOT_FOUND,
                    "评测数据目录不可用：" + evals.getAbsolutePath(),
                    "请确认 plugins/MBB-Roleplay/evals 目录存在且可读，修复后重启插件；/eval report 仍可用",
                    Collections.<String>emptyList());
        }
        File[] candidates = evals.listFiles();
        if (candidates == null) {
            return failure(new File(evals, "rubric-<version>.json"), EvalBankLocator.CODE_DATA_NOT_FOUND,
                    "无法读取评测数据目录：" + evals.getAbsolutePath(),
                    "请检查目录读权限后重启插件；/eval report 仍可用",
                    Collections.<String>emptyList());
        }
        List<File> rubrics = new ArrayList<File>();
        for (File candidate : candidates) {
            if (candidate.isFile() && candidate.getName().startsWith("rubric-")
                    && candidate.getName().endsWith(".json")) {
                rubrics.add(candidate);
            }
        }
        if (rubrics.isEmpty()) {
            return failure(new File(evals, "rubric-<version>.json"), EvalBankProblem.READ,
                    "rubric 文件不存在：" + evals.getAbsolutePath() + " 下没有 rubric-<version>.json",
                    "请把 rubric-<version>.json 放入该目录后重启插件；rubric 缺失时 /eval run 拒绝执行，"
                            + "/eval report 仍可用", Collections.<String>emptyList());
        }
        File chosen = rubrics.get(0);
        List<String> loadWarnings = new ArrayList<String>();
        if (rubrics.size() > 1) {
            for (File rubric : rubrics) {
                if (rubric.getName().compareTo(chosen.getName()) > 0) {
                    chosen = rubric;
                }
            }
            loadWarnings.add("检测到 " + rubrics.size() + " 个 rubric 文件，已使用 "
                    + chosen.getName() + "；请清理多余版本避免歧义");
        }

        EvalRubricLoadResult result = EvalRubricLoader.load(chosen, expectedPromptVersion);
        if (result.ok()) {
            EvalRubric rubric = result.rubric();
            String summary = "rubric 已加载：" + chosen.getName() + "，version=" + rubric.version()
                    + "，promptVersion=" + rubric.promptVersion() + "，文件："
                    + chosen.getAbsolutePath();
            return new EvalRubricCache(chosen, result, "", "", "", Collections.<String>emptyList(),
                    mergeWarnings(result.warnings(), loadWarnings), summary);
        }

        List<EvalRubricProblem> problems = result.problems();
        List<String> errors = new ArrayList<String>();
        for (int i = 0; i < problems.size() && i < 5; i++) {
            errors.add(display(problems.get(i)));
        }
        EvalRubricProblem first = problems.isEmpty() ? null : problems.get(0);
        String code = first == null ? EvalBankProblem.READ : mapCode(first.code());
        String detail = first == null ? "rubric 校验失败" : display(first);
        boolean promptMismatch = first != null
                && EvalRubricProblem.CODE_PROMPT_VERSION.equals(first.code());
        String message = "rubric 校验失败：共 " + problems.size() + " 个错误（" + code + "），"
                + (promptMismatch ? "promptVersion 与代码支持版本不匹配" : detail)
                + "，文件：" + chosen.getAbsolutePath();
        String hint = promptMismatch
                ? "请使用 promptVersion=" + expectedPromptVersion + " 的 rubric 文件；文件："
                        + chosen.getAbsolutePath() + "；修复后重启插件，/eval report 仍可用"
                : "请按上面列出的 JSON path 修正 rubric 后重启插件（运行期不热重载）；文件："
                        + chosen.getAbsolutePath() + "；修复后重跑 /eval run；/eval report 仍可用";
        return new EvalRubricCache(chosen, result, code, message, hint, errors,
                mergeWarnings(result.warnings(), loadWarnings), code + "：" + message + "；" + hint);
    }

    private static EvalRubricCache failure(File file, String code, String message, String hint,
            List<String> warnings) {
        return new EvalRubricCache(file, null, code, message, hint, Collections.<String>emptyList(),
                warnings, code + "：" + message + "；" + hint);
    }

    private static List<String> mergeWarnings(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<String>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return merged;
    }

    /** 单条 rubric 问题按 §17.1 O20 映射到 §13.1.1 封闭错误码。 */
    private static String mapCode(String rubricCode) {
        if (EvalRubricProblem.CODE_READ.equals(rubricCode)) {
            return EvalBankProblem.READ;
        }
        if (EvalRubricProblem.CODE_JSON.equals(rubricCode)) {
            return EvalBankProblem.JSON;
        }
        if (EvalRubricProblem.CODE_VERSION.equals(rubricCode)
                || EvalRubricProblem.CODE_PROMPT_VERSION.equals(rubricCode)) {
            return EvalBankProblem.VERSION;
        }
        if (EvalRubricProblem.CODE_HASH.equals(rubricCode)) {
            return EvalBankProblem.HASH;
        }
        if (EvalRubricProblem.CODE_DIMENSION.equals(rubricCode)) {
            return EvalBankProblem.DIMENSION;
        }
        return EvalBankProblem.FIELD;
    }

    private static String display(EvalRubricProblem problem) {
        return mapCode(problem.code()) + "（" + problem.jsonPath() + "）：" + problem.message();
    }

    public boolean ok() {
        return result != null && result.ok();
    }

    /** 选中的 rubric 文件；目录不可用时为预期路径，可能不存在。 */
    public File file() {
        return file;
    }

    public String absolutePath() {
        return absolutePath;
    }

    public EvalRubric rubric() {
        return result == null ? null : result.rubric();
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public String hint() {
        return hint;
    }

    /** 可见错误明细，最多 5 条。 */
    public List<String> errors() {
        return errors;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** 成功时为摘要行；失败时为「错误码：消息」和提示。 */
    public String summary() {
        return summary;
    }
}
