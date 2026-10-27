package org.moboxlab.mbb.eval.command;

import org.moboxlab.moboxbot.API.Command.CommandPermission;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /eval 子命令与最低权限判定表（规格 §9.2）。
 *
 * <p>一个 {@link org.moboxlab.moboxbot.API.Command.BotCommand} 只能声明一个最低权限，而 /eval 的 9 条
 * 子命令权限不同（OWNER / BOT_ADMIN / EVERYONE）。因此命令类整体声明 {@code EVERYONE}，
 * 由本类在分派前逐子命令判定；否则 /eval help 无法对 EVERYONE 开放。权限不足时命令层必须
 * 直接拒绝，不预检、不建 run、不发起调用（§9.2）。</p>
 */
public final class EvalPermission {

    /** 权限表的键：一次调用的规范化子命令（baseline 的二级子命令合并成 "baseline set" 与 "baseline show"）。 */
    private static final Map<String, CommandPermission> MATRIX;

    static {
        Map<String, CommandPermission> matrix = new LinkedHashMap<String, CommandPermission>();
        matrix.put("run", CommandPermission.OWNER);
        matrix.put("status", CommandPermission.BOT_ADMIN);
        matrix.put("report", CommandPermission.BOT_ADMIN);
        matrix.put("list", CommandPermission.BOT_ADMIN);
        matrix.put("baseline", CommandPermission.BOT_ADMIN);
        matrix.put("baseline show", CommandPermission.BOT_ADMIN);
        matrix.put("baseline set", CommandPermission.OWNER);
        matrix.put("abort", CommandPermission.OWNER);
        matrix.put("help", CommandPermission.EVERYONE);
        MATRIX = Collections.unmodifiableMap(matrix);
    }

    private EvalPermission() {
    }

    /**
     * 把命令参数解析成权限表的键。
     *
     * <p>{@code args[0]} 是命令名 {@code eval}，{@code args[1]} 是子命令。仅把已知的一级子命令
     * 与 baseline 的 set / show 归一到权限表键；未知子命令返回 {@code null}，由命令层回「未知子命令」，
     * 不套用任何默认权限。</p>
     */
    public static String resolveKey(String[] args) {
        if (args == null || args.length < 2) {
            return null;
        }
        String subcommand = args[1];
        if (!"baseline".equals(subcommand)) {
            return MATRIX.containsKey(subcommand) ? subcommand : null;
        }
        if (args.length >= 3 && "set".equals(args[2])) {
            return "baseline set";
        }
        if (args.length >= 3 && "show".equals(args[2])) {
            return "baseline show";
        }
        return "baseline";
    }

    /** 权限表键对应的最低权限；未知键返回 {@code null}。 */
    public static CommandPermission required(String key) {
        return key == null ? null : MATRIX.get(key);
    }

    /** 全部权限表（只读，保持 §9.1 的命令顺序，供 help 与自检使用）。 */
    public static Map<String, CommandPermission> matrix() {
        return MATRIX;
    }

    /** 权限显示名，用于「权限不足」回执与 help；只覆盖 OWNER / BOT_ADMIN / EVERYONE。 */
    public static String label(CommandPermission permission) {
        if (permission == CommandPermission.OWNER) {
            return "OWNER";
        }
        if (permission == CommandPermission.BOT_ADMIN) {
            return "BOT_ADMIN";
        }
        if (permission == CommandPermission.EVERYONE) {
            return "EVERYONE";
        }
        return String.valueOf(permission);
    }
}
