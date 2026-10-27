package org.moboxlab.mbb.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;
import org.moboxlab.mbb.eval.command.EvalCommand;
import org.moboxlab.mbb.eval.command.EvalPermission;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.moboxbot.API.Command.BotCommand;
import org.moboxlab.moboxbot.API.Command.CommandPermission;
import org.moboxlab.moboxbot.API.Command.CommandSender;

/**
 * 命令框架用例（G-1：命令注册表、权限校验、冷却、help 自描述、未知/无子命令用法提示）。
 *
 * <p>命令对象以 {@code runner = null} 构造：help、权限拒绝与未知子命令都必须不触碰 runner，
 * 一旦误入业务流程就会抛 {@link NullPointerException}，从而证明「不建 run、不发起调用」。</p>
 */
public class EvalCommandTest {

    /** 命令表与顺序由 {@link EvalPermission} 派发，避免与权限矩阵用例重复维护。 */
    private static final List<String> EXPECTED_COMMANDS =
            new ArrayList<String>(EvalPermission.matrix().keySet());

    private static EvalCommand command() {
        return new EvalCommand(null, EvalConfig.load(null));
    }

    @Test
    public void helpShouldListNineCommandsWithMinPermission() {
        RecordingSender sender = new RecordingSender();
        assertTrue(command().execute(sender, new String[] { "eval", "help" }));

        List<String> messages = sender.messages;
        assertFalse("help 必须有输出", messages.isEmpty());
        for (String key : EXPECTED_COMMANDS) {
            String expected = "[eval] /eval " + key + " — " + expectedLabel(key);
            assertTrue("help 缺少命令行：" + expected, messages.contains(expected));
        }
        assertTrue("help 应说明档位省略时取 evalDefaultTier", sender.joined().contains("evalDefaultTier"));
        assertAllPrefixed(sender);
    }

    @Test
    public void helpShouldHaveNoSideEffects() {
        RecordingSender sender = new RecordingSender();
        command().execute(sender, new String[] { "eval", "help" });
        assertTrue("help 不应写 reply", sender.replies.isEmpty());
        assertAllPrefixed(sender);
    }

    @Test
    public void botAdminRunShouldBeDeniedWithOwnerHint() {
        RecordingSender sender = new RecordingSender(CommandPermission.BOT_ADMIN);
        assertTrue(command().execute(sender, new String[] { "eval", "run" }));

        assertEquals("权限不足只回一句", 1, sender.messages.size());
        String message = sender.messages.get(0);
        assertTrue("权限不足回执：" + message, message.contains("权限不足"));
        assertTrue("必须附所需权限名：" + message, message.contains("OWNER"));
        assertAllPrefixed(sender);
    }

    @Test
    public void baselineSetShouldRequireOwner() {
        RecordingSender sender = new RecordingSender(CommandPermission.BOT_ADMIN);
        command().execute(sender, new String[] { "eval", "baseline", "set", "r-20260101000000-full-abcd", "理由" });

        assertEquals(1, sender.messages.size());
        assertTrue(sender.messages.get(0).contains("OWNER"));
        assertAllPrefixed(sender);
    }

    @Test
    public void noSubcommandShouldShowUsageAndAvailableCommands() {
        RecordingSender sender = new RecordingSender();
        assertTrue(command().execute(sender, new String[] { "eval" }));

        assertEquals(1, sender.messages.size());
        String message = sender.messages.get(0);
        assertTrue("无子命令回用法提示：" + message, message.contains("用法"));
        assertTrue("回可用命令提示：" + message, message.contains("可用命令"));
        assertTrue(message.contains("help"));
        assertAllPrefixed(sender);
    }

    @Test
    public void reloadShouldBeUnknownSubcommand() {
        RecordingSender sender = new RecordingSender();
        assertTrue(command().execute(sender, new String[] { "eval", "reload" }));

        assertEquals(1, sender.messages.size());
        String message = sender.messages.get(0);
        assertTrue("reload 按未知子命令处理：" + message, message.contains("未知子命令：reload"));
        assertTrue("回可用命令提示：" + message, message.contains("可用命令"));
        assertTrue("可用命令必须与权限矩阵逐项一致：" + message,
                message.contains("可用命令：" + availableCommandList()));
        assertFalse("不提供 reload 功能", Arrays.asList(message.split("\\s*/\\s*")).contains("reload"));
        assertAllPrefixed(sender);
    }

    @Test
    public void unknownSubcommandShouldShowAvailableCommands() {
        RecordingSender sender = new RecordingSender();
        assertTrue(command().execute(sender, new String[] { "eval", "unknown" }));

        assertEquals(1, sender.messages.size());
        String message = sender.messages.get(0);
        assertTrue(message.contains("未知子命令：unknown"));
        assertTrue(message.contains("可用命令"));
        assertAllPrefixed(sender);
    }

    @Test
    public void pendingSubcommandsShouldBeExplicit() {
        RecordingSender sender = new RecordingSender(CommandPermission.BOT_ADMIN);
        assertTrue(command().execute(sender, new String[] { "eval", "report", "r-20260101000000-smoke-abcd" }));
        String message = sender.messages.get(0);
        assertTrue("只读依赖未接线时必须明确说明：" + message, message.contains("只读命令依赖未接线"));

        sender = new RecordingSender(CommandPermission.OWNER);
        assertTrue(command().execute(sender, new String[] { "eval", "baseline", "set",
                "r-20260101000000-full-abcd", "理由" }));
        message = sender.messages.get(0);
        assertTrue("基线服务不可用时必须说明：" + message, message.contains("基线服务不可用"));
        assertAllPrefixed(sender);
    }

    @Test
    public void usageShouldListNineCommandForms() {
        BotCommand command = command();
        List<String> usage = command.usage();
        assertEquals("usage 应覆盖 §9.1 的 9 条命令", 9, usage.size());
        assertEquals("/eval run [full|smoke]", usage.get(0));
        assertEquals("/eval help", usage.get(8));
        String joined = usage.toString();
        for (String key : EXPECTED_COMMANDS) {
            assertTrue("usage 缺少命令形式：/eval " + key, joined.contains("/eval " + key));
        }
    }

    @Test
    public void cooldownShouldBeThreeSeconds() {
        assertEquals("§9.2 冷却 3 秒", 3, command().cooldownSeconds());
    }

    @Test
    public void commandShouldBeRegisteredUnderEvalWithEveryoneLevel() {
        BotCommand command = command();
        assertEquals(Arrays.asList("eval"), command.prefix());
        assertEquals("命令级最低权限为 EVERYONE，逐子命令由 EvalPermission 判定",
                CommandPermission.EVERYONE, command.permission());
        assertFalse("描述不能为空", command.description().trim().isEmpty());
    }

    @Test
    public void baselineUnknownThirdTokenShouldBeUnknownSubcommand() {
        RecordingSender sender = new RecordingSender(CommandPermission.BOT_ADMIN);
        assertTrue(command().execute(sender, new String[] { "eval", "baseline", "foo" }));

        assertEquals(1, sender.messages.size());
        String message = sender.messages.get(0);
        assertTrue("baseline 多余参数按未知子命令处理：" + message, message.contains("未知子命令：baseline foo"));
        assertTrue("回可用命令提示：" + message, message.contains("可用命令"));
        assertFalse("不得落到占位回执", message.contains("后续切片"));
        assertAllPrefixed(sender);
    }

    @Test
    public void subcommandShouldBeCaseSensitive() {
        RecordingSender sender = new RecordingSender(CommandPermission.OWNER);
        assertTrue(command().execute(sender, new String[] { "eval", "RUN" }));

        assertEquals(1, sender.messages.size());
        assertTrue("子命令大小写敏感：" + sender.messages.get(0),
                sender.messages.get(0).contains("未知子命令：RUN"));
        assertAllPrefixed(sender);
    }

    @Test
    public void runTierShouldBeCaseSensitive() {
        RecordingSender sender = new RecordingSender(CommandPermission.OWNER);
        assertTrue(command().execute(sender, new String[] { "eval", "run", "FULL" }));

        assertEquals(1, sender.messages.size());
        String message = sender.messages.get(0);
        assertTrue("档位大小写敏感：" + message, message.contains("参数错误"));
        assertTrue("必须说明大小写敏感：" + message, message.contains("大小写敏感"));
        assertAllPrefixed(sender);
    }

    /** 期望的最低权限文案：直接取权限矩阵，矩阵本身由 EvalPermissionTest 冻结。 */
    private static String expectedLabel(String key) {
        return EvalPermission.label(EvalPermission.required(key));
    }

    private static String availableCommandList() {
        StringBuilder text = new StringBuilder();
        for (String key : EXPECTED_COMMANDS) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(key);
        }
        return text.toString();
    }

    private static void assertAllPrefixed(RecordingSender sender) {
        for (String message : sender.messages) {
            assertTrue("所有回执必须带 [eval] 前缀：" + message, message.startsWith("[eval] "));
        }
    }

    private static final class RecordingSender implements CommandSender {
        private final Set<CommandPermission> granted = new LinkedHashSet<CommandPermission>();
        private final List<String> messages = new ArrayList<String>();
        private final List<String> replies = new ArrayList<String>();

        private RecordingSender(CommandPermission... permissions) {
            granted.addAll(Arrays.asList(permissions));
        }

        @Override
        public boolean isGroup() {
            return false;
        }

        @Override
        public long getGroupID() {
            return 0L;
        }

        @Override
        public long getUserID() {
            return 10001L;
        }

        @Override
        public String getName() {
            return "tester";
        }

        @Override
        public String getCommandPrefix() {
            return "/";
        }

        @Override
        public boolean hasPermission(CommandPermission permission) {
            return permission == CommandPermission.EVERYONE || granted.contains(permission);
        }

        @Override
        public void sendMessage(String message) {
            messages.add(message);
        }

        @Override
        public void reply(String message) {
            replies.add(message);
        }

        private String joined() {
            StringBuilder text = new StringBuilder();
            for (String message : messages) {
                text.append(message).append('\n');
            }
            return text.toString();
        }
    }
}
