package org.moboxlab.mbb.eval.deterministic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 硬项预检：空回复、跳过、length 截断与人设泄露（规格 §6.3.5）。 */
public final class EvalVetoPrecheckTest {

    private static final String PERSONA = "天童爱丽丝，千年科学学园游戏开发部成员，以第三人称称呼自己，不承认是 AI 或助手";

    @Test
    public void emptyContentShouldVetoEmptyFirst() {
        assertEquals(EvalVetoPrecheck.REASON_EMPTY,
                EvalVetoPrecheck.vetoReason(null, "length", true, PERSONA));
        assertEquals(EvalVetoPrecheck.REASON_EMPTY,
                EvalVetoPrecheck.vetoReason("  \n\t ", "length", true, PERSONA));
    }

    @Test
    public void skipFlagOrMarkerShouldVetoSkip() {
        assertEquals(EvalVetoPrecheck.REASON_SKIP,
                EvalVetoPrecheck.vetoReason("今天不想说话", "stop", true, PERSONA));
        assertEquals(EvalVetoPrecheck.REASON_SKIP,
                EvalVetoPrecheck.vetoReason(" <SKIP> ", "stop", false, PERSONA));
    }

    @Test
    public void lengthFinishReasonShouldVetoTruncated() {
        assertEquals(EvalVetoPrecheck.REASON_TRUNCATED,
                EvalVetoPrecheck.vetoReason("今天天气不错，我陪你", "length", false, PERSONA));
    }

    @Test
    public void personaFragmentLeakShouldVetoEvenWithPunctuationAndSpaces() {
        assertEquals(EvalVetoPrecheck.REASON_PERSONA_LEAK, EvalVetoPrecheck.vetoReason(
                "我是天童 爱丽丝 · 千年科学学园游戏开发部成员。", "stop", false, PERSONA));
        assertEquals(EvalVetoPrecheck.REASON_PERSONA_LEAK, EvalVetoPrecheck.vetoReason(
                "ALICE IS A ROBOT, NEVER ADMITS BEING AN AI", "stop", false,
                "Alice is a robot, never admits being an AI"));
    }

    @Test
    public void shortPersonaShouldNotVeto() {
        assertNull(EvalVetoPrecheck.vetoReason("爱丽丝今天也在", "stop", false, "爱丽丝"));
        assertFalse(EvalVetoPrecheck.leaksPersona("爱丽丝今天也在", "爱丽丝"));
    }

    @Test
    public void personaLeakShouldWinOverNormalReply() {
        assertNull(EvalVetoPrecheck.vetoReason("今天天气不错，我陪你聊聊天吧。", "stop", false, PERSONA));
        assertFalse(EvalVetoPrecheck.leaksPersona("今天天气不错，我陪你聊聊天吧。", PERSONA));
    }
}
