package org.moboxlab.mbb.eval.command;

import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.runner.EvalPreflight;
import org.moboxlab.mbb.eval.runner.EvalRunner;
import org.moboxlab.mbb.eval.store.EvalStore;
import org.moboxlab.moboxbot.API.Command.BotCommand;
import org.moboxlab.moboxbot.API.Command.CommandPermission;
import org.moboxlab.moboxbot.API.Command.CommandSender;

import java.util.Arrays;
import java.util.List;

/**
 * /eval 命令（规格 §9.1–§9.3）。
 *
 * <p>命令类整体声明 {@link CommandPermission#EVERYONE}，逐子命令权限由 {@link EvalPermission} 判定：
 * 一个 BotCommand 只能声明一个最低权限，而 help 必须对 EVERYONE 开放。权限不足时只回一句
 * 权限不足 + 所需权限名，不预检、不建 run、不发起调用（§9.2）。</p>
 *
 * <p>本切片（A-3）已实现 run / status / abort / help；report / list / baseline 三组只做权限判定后
 * 回「后续切片接入」，不静默假装成功。骨架期 /eval run 受理后只做占位执行（不读题库、
 * 不发起 AI 调用），用于验证受理—预检—终态落库与卸载中止路径（§16.2 A-3）。</p>
 */
public final class EvalCommand extends BotCommand {

    private static final String PREFIX = "[eval] ";
    /** 冷却秒数（§9.2）。 */
    private static final int COOLDOWN_SECONDS = 3;

    private final EvalRunner runner;
    private final EvalConfig config;

    public EvalCommand(EvalRunner runner, EvalConfig config) {
        this.runner = runner;
        this.config = config;
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
                sender.sendMessage(PREFIX + "用法：/eval <子命令>；发送 /eval help 查看全部命令");
            } else {
                sender.sendMessage(PREFIX + "未知子命令：" + args[1] + "；发送 /eval help 查看全部命令");
            }
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
            doStatus(sender, args);
            return true;
        }
        if ("abort".equals(key)) {
            doAbort(sender, args);
            return true;
        }
        sender.sendMessage(PREFIX + key + " 将在后续切片接入，当前为骨架阶段；发送 /eval help 查看已有命令");
        return true;
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
                sender.sendMessage(PREFIX + "题数 " + result.caseCount() + "（" + result.tier()
                        + "）／预计调用 " + result.planned() + "/" + result.budgetLimit()
                        + "／预计 " + EvalPreflight.estimatedMinutesLow(result.planned())
                        + "-" + EvalPreflight.estimatedMinutesHigh(result.planned()) + " 分钟");
                sender.sendMessage(PREFIX + "状态 /eval status；中止 /eval abort");
                return;
            case BUSY:
                sender.sendMessage(PREFIX + "已有进行中的评测：" + result.runId()
                        + "；" + (result.message() == null ? "" : result.message() + "；")
                        + (result.hint() == null ? "可 /eval status 查看进度" : result.hint()));
                return;
            case REJECTED:
                sender.sendMessage(PREFIX + "预检失败"
                        + (result.code() == null ? "" : "（" + result.code() + "）")
                        + "：" + result.message()
                        + (result.hint() == null ? "" : "；" + result.hint()));
                return;
            case FAILED:
            default:
                sender.sendMessage(PREFIX + "启动失败：" + result.message() + "；请查看插件日志后重试 /eval run");
        }
    }

    private void doStatus(CommandSender sender, String[] args) {
        if (args.length > 3) {
            sender.sendMessage(PREFIX + "参数过多：用法 /eval status [runId]");
            return;
        }
        EvalRunner.RunSnapshot snapshot;
        if (args.length == 3) {
            String runId = args[2];
            if (!EvalStore.isValidRunId(runId)) {
                sender.sendMessage(PREFIX + "runId 格式不正确：" + runId
                        + "；应形如 r-<yyyyMMddHHmmss>-<smoke|full>-<4 位随机>");
                return;
            }
            snapshot = runner.snapshot(runId);
            if (snapshot == null) {
                sender.sendMessage(PREFIX + "没有该 run：" + runId + "；发送 /eval status 查看最近一次 run");
                return;
            }
        } else {
            snapshot = runner.snapshot();
            if (snapshot == null) {
                sender.sendMessage(PREFIX + "还没有任何评测记录；可先发送 /eval run smoke");
                return;
            }
        }
        sender.sendMessage(PREFIX + "runId=" + snapshot.runId() + " 档位=" + snapshot.tier()
                + " 状态=" + snapshot.status());
        String startedAt = snapshot.startedAt() == null ? "未知" : snapshot.startedAt();
        sender.sendMessage(PREFIX + "调用 " + snapshot.callsUsed() + "/" + snapshot.callsBudget()
                + "，开始于 " + startedAt);
        if (snapshot.running()) {
            if (snapshot.remainingMillis() >= 0) {
                sender.sendMessage(PREFIX + "剩余墙钟约 " + (snapshot.remainingMillis() / 1000L) + " 秒");
            } else {
                sender.sendMessage(PREFIX + "该 RUNNING 行为上次运行遗留，本进程无法收尾；可发 /eval abort 中止");
            }
            sender.sendMessage(PREFIX + "进度 0/" + snapshot.caseCount()
                    + " 已完成（骨架阶段占位执行）；失败分布：无");
            sender.sendMessage(PREFIX + "骨架阶段为占位执行：不产生 case 行、不发起 AI 调用");
        }
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

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX + "命令与最低权限：");
        for (java.util.Map.Entry<String, CommandPermission> entry : EvalPermission.matrix().entrySet()) {
            sender.sendMessage(PREFIX + "/eval " + entry.getKey() + " — "
                    + EvalPermission.label(entry.getValue()));
        }
        sender.sendMessage(PREFIX + "子命令与档位大小写敏感；档位省略时取 evalDefaultTier（当前 "
                + (config == null ? "smoke" : config.defaultTier()) + "）");
        sender.sendMessage(PREFIX + "骨架阶段：run 只做占位执行（不读题库、不发起 AI 调用）；report/list/baseline 待后续切片");
    }
}
