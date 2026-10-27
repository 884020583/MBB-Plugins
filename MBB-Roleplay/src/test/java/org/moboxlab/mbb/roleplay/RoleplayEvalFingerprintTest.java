package org.moboxlab.mbb.roleplay;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * configFingerprint 的算法口径（规格 #50 §8.2 F14、§10.1）。
 *
 * <p>F14 冻结的算法是 sha256(configVersion + 竖线 + 规范化 k=v 串)，configVersion=46；
 * 键集合按 §10.1 取六个评测相关键。这里锁定前缀与键集合两件事，避免两侧指纹算法
 * 悄悄漂移后把可比性判定带偏。</p>
 */
public class RoleplayEvalFingerprintTest {

    private static final String CANONICAL = "aiProfile=default" + "\n"
            + "evalProfile=" + "\n"
            + "replyMaxTokens=4000" + "\n"
            + "replyReviewEnable=true" + "\n"
            + "toolEnable=true" + "\n"
            + "promptTotalChars=120000";

    @Test
    public void 指纹按configVersion加竖线前缀计算() {
        RoleplayConfig config = new RoleplayConfig();
        String expected = RoleplayEvalGateway.sha256Hex(
                ("46|" + CANONICAL).getBytes(StandardCharsets.UTF_8));

        assertEquals("指纹必须带 configVersion=46 与前缀竖线（§8.2 F14）",
                expected, RoleplayEvalGateway.configFingerprint(config));
    }

    @Test
    public void 缺version前缀的旧算法必须不同() {
        RoleplayConfig config = new RoleplayConfig();
        String legacy = RoleplayEvalGateway.sha256Hex(
                CANONICAL.getBytes(StandardCharsets.UTF_8));

        assertFalse("缺 46| 前缀的指纹不应再被接受",
                legacy.equals(RoleplayEvalGateway.configFingerprint(config)));
    }

    @Test
    public void 指纹随评测相关键变化() {
        RoleplayConfig config = new RoleplayConfig();
        String baseline = RoleplayEvalGateway.configFingerprint(config);

        config.aiProfile = "alt-profile";
        assertFalse("aiProfile 变化必须改变指纹",
                baseline.equals(RoleplayEvalGateway.configFingerprint(config)));

        RoleplayConfig other = new RoleplayConfig();
        other.replyMaxTokens = 2000;
        assertFalse("replyMaxTokens 变化必须改变指纹",
                baseline.equals(RoleplayEvalGateway.configFingerprint(other)));
    }
}
