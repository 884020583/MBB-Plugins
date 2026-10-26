package org.moboxlab.mbb.eval.command;

import org.moboxlab.mbb.eval.bank.EvalBankCache;
import org.moboxlab.mbb.eval.baseline.EvalBaselineService;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.runner.EvalPreflightPlan;
import org.moboxlab.mbb.eval.runner.EvalRunService;
import org.moboxlab.mbb.eval.runner.EvalRunner;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.Command.BotCommand;
import org.moboxlab.moboxbot.API.Command.CommandPermission;
import org.moboxlab.moboxbot.API.Command.CommandSender;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * /eval 命令（规格 §9.1–§9.3）。
 *
 * <p>命令类整体声明 {@link CommandPermission#EVERYONE}，逐子命令权限由 {@link EvalPermission} 判定：
 * 一个 BotCommand 只能声明一个最低权限，而 help 必须对 EVERYONE 开放。权限不足时只回一句
 * 权限不足 + 所需权限名，不预检、不建 run、不发起调用（§9.2）。</p>
 *
 * <p>A-3 实现 run / status / abort / help；G-3 把 status / report / list / baseline /
 * baseline show 五个只读命令委派给 {@link EvalReadOnlyCommand}。只读命令不持有执行器、
 * 不写库、不建 run、不发起 AI 调用（§9.7、§16.8 G-3）。G-4 把 baseline set 与 abort
 * 委派给 {@link EvalWriteCommand}：写侧命令只写基线表、不改 run 状态；abort 只转发
 * {@link EvalRunService#abort()} 的结果（§9.3、§9.6、§16.8 G-4）。
 * 骨架期 /eval run 受理后只做占位执行（不读题库、不发起 AI 调用），用于验证
 * 受理—预检—终态落库与卸载中止路径（§16.2 A-3）。</p>
 *
 * <p>G-2 收敛受理回执：第二行直接取 {@link EvalPreflightPlan.Receipt#summary()}，重复 /eval run 只回当前
 * runId 与进度并指向 /eval status、/eval abort；命令线程全程只调用一次 {@link EvalRunService}，不轮询、
 * 不等待 run 结束、不发起任何 AI 调用（§9.3.1、§9.4、§9.7）。</p>
 */
public final class EvalCommand extends BotCommand {

    private static final String PREFIX = "[eval] ";
    /** 冷却秒数（§9.2）。 */
    private static final int COOLDOWN_SECONDS = 3;

    /** 受理面：命令线程只通过这个接口委派，不直接持有执行细节（§9.4）。 */
    private final EvalRunService runner;
    private final EvalConfig config;
    /** 只读命令实现；null 表示调用方未接线，只回明确提示，不静默假装成功。 */
    private final EvalReadOnlyCommand readOnly;
    /** 写侧命令实现；null 表示调用方未接线，baseline set / abort 回接线提示或旧路径。 */
    private final EvalWriteCommand write;

    /** 兼容 A-3 的既有构造：只读与写侧命令均未接线，相关子命令回接线提示。 */
    public EvalCommand(EvalRunService runner, EvalConfig config) {
        this(runner, config, (EvalReadOnlyCommand) null, (EvalWriteCommand) null);
    }

    /** 测试/装配用重载：直接注入只读实现，写侧命令未接线。 */
    public EvalCommand(EvalRunService runner, EvalConfig config, EvalReadOnlyCommand readOnly) {
        this(runner, config, readOnly, null);
    }

    /** 测试/装配用重载：只读与写侧实现都直接注入（§16.8 G-4）。 */
    public EvalCommand(EvalRunService runner, EvalConfig config,
            EvalReadOnlyCommand readOnly, EvalWriteCommand write) {
        this.runner = runner;
        this.config = config;
        this.readOnly = readOnly;
        this.write = write;
    }

    /**
     * 生产装配重载：用库、基线服务、题库缓存与报表目录构造只读与写侧实现。
     *
     * @param store           EvalStore 门面；不可用时读写命令都回库不可用
     * @param baselineService 基线服务；null 表示 rubric 权重不可用，baseline set 回未接线
     * @param bankCache       题库缓存；{@code /eval list} 读取，未就绪时回题库错误原因
     * @param reportsDir      报表输出目录（插件数据目录下的 reports）
     */
    public EvalCommand(EvalRunService runner, EvalConfig config, EvalStore store,
            EvalBaselineService baselineService, EvalBankCache bankCache, File reportsDir) {
        this(runner, config,
                store == null ? null : new EvalReadOnlyCommand(store, config, bankCache, reportsDir),
                new EvalWriteCommand(store, baselineService, runner));
    }

    /** 兼容 G-3 的五参构造：写侧基线服务未接线（缺 rubric 权重），baseline set 明确回未接线。 */
    public EvalCommand(EvalRunService runner, EvalConfig config,
            EvalStore store, EvalBankCache bankCache, File reportsDir) {
        this(runner, config, store, null, bankCache, reportsDir);
    }

    @Override
    public List<String> prefix() {
        return Arrays.asList("eval");
    }

    @Override
    public CommandPermission permission() {
        return CommandPermission.EVERYONE;
    }

    @Override
    public int cooldownSeconds() {
        return COOLDOWN_SECONDS;
    }

    @Override
    public String description() {
        return "离线评测与回归基线（骨架阶段）";
    }

    @Override
    public List<String> usage() {
        return Arrays.asList(
                "/eval run [full|smoke]",
                "/eval status [runId]",
                "/eval report [runId]",
                "/eval list [full|smoke]",
                "/eval baseline",
                "/eval baseline show [runId]",
                "/eval baseline set <runId> [理由]",
                "/eval abort",
                "/eval help");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        String key = EvalPermission.resolveKey(args);
        if (key == null) {
            if (args == null || args.length < 2) {
                sender.sendMessage(PREFIX + "用法：/eval <子命令>；可用命令：" + availableCommands()
                        + "；发送 /eval help 查看各命令最低权限");
            } else {
                sender.sendMessage(PREFIX + "未知子命令：" + args[1] + "；可用命令：" + availableCommands()
                        + "；发送 /eval help 查看各命令最低权限");
            }
            return true;
        }
        if ("baseline".equals(key) && args.length > 2) {
            sender.sendMessage(PREFIX + "未知子命令：baseline " + args[2] + "；可用命令：" + availableCommands()
                    + "；发送 /eval help 查看各命令最低权限");
            return true;
        }
        CommandPermission required = EvalPermission.required(key);
        if (required != CommandPermission.EVERYONE && !sender.hasPermission(required)) {
            sender.sendMessage(PREFIX + "权限不足：" + key + " 需要 " + EvalPermission.label(required) + " 权限");
            return true;
        }
        if ("help".equals(key)) {
            sendHelp(sender);
            return true;
        }
        if ("run".equals(key)) {
            doRun(sender, args);
            return true;
        }
        if ("status".equals(key)) {
            dispatchReadOnly(sender, key, readOnly == null ? null : readOnly.status(tail(args)));
            return true;
        }
        if ("report".equals(key)) {
            dispatchReadOnly(sender, key, readOnly == null ? null : readOnly.report(tail(args)));
            return true;
        }
        if ("list".equals(key)) {
            dispatchReadOnly(sender, key, readOnly == null ? null : readOnly.list(tail(args)));
            return true;
        }
        if ("baseline set".equals(key)) {
            // baseline set 的尾部参数从第 4 段开始（eval baseline set <runId> <理由…>）。
            if (write == null) {
                sender.sendMessage(PREFIX + "baseline set 将在后续切片接入，当前为骨架阶段；发送 /eval help 查看已有命令");
                return true;
            }
            dispatchWrite(sender, write.baselineSet(tail(args, 3)));
            return true;
        }
        if ("baseline".equals(key)) {
            dispatchReadOnly(sender, key, readOnly == null ? null : readOnly.baseline(tail(args)));
            return true;
        }
        if ("baseline show".equals(key)) {
            // baseline show 的尾部参数从第 4 段开始（eval baseline show [runId]）。
            dispatchReadOnly(sender, key,
                    readOnly == null ? null : readOnly.baselineShow(tail(args, 3)));
            return true;
        }
        if ("abort".equals(key)) {
            if (write != null) {
                dispatchWrite(sender, write.abort(tail(args)));
                return true;
            }
            doAbort(sender, args);
            return true;
        }
        sender.sendMessage(PREFIX + key + " 将在后续切片接入，当前为骨架阶段；发送 /eval help 查看已有命令");
        return true;
    }

    /** 子命令之后的参数；args 依次为 eval、子命令（baseline show 为三段）。 */
    private static String[] tail(String[] args) {
        return tail(args, 2);
    }

    /** 从第 offset 段（0 起）截取尾部参数。 */
    private static String[] tail(String[] args, int offset) {
        return args.length <= offset ? new String[0] : Arrays.copyOfRange(args, offset, args.length);
    }

    /** 写侧命令逐行加 [eval] 前缀；回执本身不带前缀，命令层统一发送（§16.8 G-4）。 */
    private void dispatchWrite(CommandSender sender, List<String> lines) {
        if (lines == null) {
            sender.sendMessage(PREFIX + "写侧命令未返回结果；请查看插件日志后重试");
            return;
        }
        for (String line : lines) {
            sender.sendMessage(PREFIX + line);
        }
    }

    /** 只读命令逐行加 [eval] 前缀；实现缺失时给明确的接线提示，不假装成功。 */
    private void dispatchReadOnly(CommandSender sender, String key, List<String> lines) {
        if (lines == null) {
            sender.sendMessage(PREFIX + "只读命令依赖未接线：" + key
                    + " 需要 EvalStore、题库缓存与报表目录；请检查插件启动日志");
            return;
        }
        for (String line : lines) {
            sender.sendMessage(PREFIX + line);
        }
    }

    private void doRun(CommandSender sender, String[] args) {
        if (args.length > 3) {
            sender.sendMessage(PREFIX + "参数过多：用法 /eval run [full|smoke]");
            return;
        }
        String tier = args.length == 3 ? args[2] : null;
        if (tier != null && !EvalStore.TIER_SMOKE.equals(tier) && !EvalStore.TIER_FULL.equals(tier)) {
            sender.sendMessage(PREFIX + "参数错误：档位只能是 smoke 或 full（大小写敏感）；用法 /eval run [full|smoke]");
            return;
        }
        EvalRunner.AcceptResult result = runner.accept(tier);
        switch (result.kind()) {
            case ACCEPTED:
                sender.sendMessage(PREFIX + "已受理：" + result.runId());
                // 第二行整体取自预检计划的回执对象，命令层不重复计算题数、调用数与预计时长（§9.3.1）。
                sender.sendMessage(PREFIX + EvalPreflightPlan.of(result.tier())
                        .receipt(result.budgetLimit()).summary());
                sender.sendMessage(PREFIX + "状态 /eval status；中止 /eval abort");
                return;
            case BUSY:
                // §9.7：不排队、不新建行、不覆盖，只回当前 runId 与当前进度。
                sender.sendMessage(PREFIX + "已在进行中：" + result.runId()
                        + "，当前进度 " + (result.message() == null ? "未知" : result.message())
                        + (result.hint() == null ? "；可发 /eval status 查看进度" : "；" + result.hint()));
                return;
            case REJECTED:
                renderRejection(sender, result);
                return;
            case FAILED:
            default:
                sender.sendMessage(PREFIX + "启动失败：" + result.message() + "；请查看插件日志后重试 /eval run");
        }
    }

    /**
     * 预检拒绝回执（§9.3.1、§13.1、§15.3 B29）：原因、前 5 条明细与下一步动作。
     *
     * <p>明细来自 onEnable 缓存，顺序由题库校验的稳定排序决定，同一状态两次回显完全一致；
     * 纯文本、逐行 [eval] 前缀，总行数不超过 15。</p>
     */
    private void renderRejection(CommandSender sender, EvalRunner.AcceptResult result) {
        String code = result.code();
        sender.sendMessage(PREFIX + "预检失败" + (code == null || code.isEmpty() ? "" : "（" + code + "）")
                + "：" + (result.message() == null ? "未给出原因" : result.message()));
        java.util.List<String> details = result.details();
        int shown = 0;
        for (String detail : details) {
            if (shown >= 5) {
                break;
            }
            shown++;
            sender.sendMessage(PREFIX + shown + ". " + detail);
        }
        if (!details.isEmpty()) {
            sender.sendMessage(PREFIX + (details.size() > shown
                    ? "仅回显前 " + shown + " 条，完整清单见插件日志"
                    : "以上 " + shown + " 条为全部错误，完整清单见插件日志"));
        }
        sender.sendMessage(PREFIX + "下一步：" + (result.hint() == null || result.hint().isEmpty()
                ? "修复后重跑 /eval run；/eval report 仍可用" : result.hint()));
    }

    private void doAbort(CommandSender sender, String[] args) {
        if (args.length > 2) {
            sender.sendMessage(PREFIX + "参数过多：/eval abort 不接受参数");
            return;
        }
        EvalRunner.AbortResult result = runner.abort();
        switch (result.kind()) {
            case ABORTED:
                sender.sendMessage(PREFIX + "已中止：" + result.runId() + "（状态见 /eval status）");
                return;
            case NONE:
                sender.sendMessage(PREFIX + "当前没有进行中的评测");
                return;
            case FAILED:
            default:
                sender.sendMessage(PREFIX + "中止失败：" + result.message());
        }
    }

    /** 按 §9.1 顺序拼出可用子命令，供未知/无子命令时的用法提示（G13）。 */
    private static String availableCommands() {
        StringBuilder text = new StringBuilder();
        for (String key : EvalPermission.matrix().keySet()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(key);
        }
        return text.toString();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX + "命令与最低权限：");
        for (java.util.Map.Entry<String, CommandPermission> entry : EvalPermission.matrix().entrySet()) {
            sender.sendMessage(PREFIX + "/eval " + entry.getKey() + " — "
                    + EvalPermission.label(entry.getValue()));
        }
        sender.sendMessage(PREFIX + "子命令与档位大小写敏感；档位省略时取 evalDefaultTier（当前 "
                + (config == null ? "smoke" : config.defaultTier()) + "）");
        sender.sendMessage(PREFIX + "只读命令 status/report/list/baseline/baseline show 不写库、不建 run、不调 AI；"
                + "run 当前仍为占位执行；"
                + (write == null ? "baseline set 待后续切片接入"
                        : "写侧命令 baseline set / abort 由 OWNER 执行：baseline set 只接受 COMPLETED 的 full run"));
    }
}
