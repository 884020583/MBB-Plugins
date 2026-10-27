package org.moboxlab.mbb.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import org.junit.Test;
import org.moboxlab.mbb.eval.command.EvalPermission;
import org.moboxlab.moboxbot.API.Command.CommandPermission;

/**
 * 权限矩阵用例（A-5：权限矩阵）。
 *
 * <p>逐条断言 §9.1 的 9 行命令与最低权限，并覆盖 baseline 二级子命令归一与未知子命令不套默认权限。</p>
 */
public class EvalPermissionTest {

    private static final ArrayList<String> EXPECTED_ORDER = new ArrayList<String>(Arrays.asList(
            "run", "status", "report", "list", "baseline", "baseline show", "baseline set",
            "abort", "help"));

    @Test
    public void matrixShouldMatchCommandOrder() {
        Map<String, CommandPermission> matrix = EvalPermission.matrix();
        assertEquals(9, matrix.size());
        assertEquals("权限表顺序应与 §9.1 命令表一致", EXPECTED_ORDER, new ArrayList<String>(matrix.keySet()));
        assertEquals(CommandPermission.OWNER, matrix.get("run"));
        assertEquals(CommandPermission.OWNER, matrix.get("baseline set"));
        assertEquals(CommandPermission.OWNER, matrix.get("abort"));
        assertEquals(CommandPermission.BOT_ADMIN, matrix.get("status"));
        assertEquals(CommandPermission.BOT_ADMIN, matrix.get("report"));
        assertEquals(CommandPermission.BOT_ADMIN, matrix.get("list"));
        assertEquals(CommandPermission.BOT_ADMIN, matrix.get("baseline"));
        assertEquals(CommandPermission.BOT_ADMIN, matrix.get("baseline show"));
        assertEquals(CommandPermission.EVERYONE, matrix.get("help"));
    }

    @Test
    public void resolveKeyShouldNormalizeSubcommands() {
        assertEquals("run", EvalPermission.resolveKey(new String[] { "eval", "run" }));
        assertEquals("baseline set", EvalPermission.resolveKey(new String[] { "eval", "baseline", "set" }));
        assertEquals("baseline show", EvalPermission.resolveKey(new String[] { "eval", "baseline", "show" }));
        assertEquals("baseline", EvalPermission.resolveKey(new String[] { "eval", "baseline" }));
        assertNull("未知子命令不套默认权限", EvalPermission.resolveKey(new String[] { "eval", "reload" }));
        assertNull("无子命令", EvalPermission.resolveKey(new String[] { "eval" }));
        assertNull(EvalPermission.resolveKey(null));
    }

    @Test
    public void requiredAndLabelShouldBehave() {
        assertEquals(CommandPermission.OWNER, EvalPermission.required("abort"));
        assertNull(EvalPermission.required("unknown"));
        assertNull(EvalPermission.required(null));
        assertEquals("OWNER", EvalPermission.label(CommandPermission.OWNER));
        assertEquals("BOT_ADMIN", EvalPermission.label(CommandPermission.BOT_ADMIN));
        assertEquals("EVERYONE", EvalPermission.label(CommandPermission.EVERYONE));
        assertTrue(EvalPermission.label(CommandPermission.GROUP_ADMIN).length() > 0);
    }
}
