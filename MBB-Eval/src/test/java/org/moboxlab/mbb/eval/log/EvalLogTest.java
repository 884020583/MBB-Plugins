package org.moboxlab.mbb.eval.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 统一日志封装用例（I-3：规格 §13.6、§15.9 I3）。
 *
 * <p>只从公开静态方法观察日志行：前缀、行内信息密度与「不写原文」的约定。</p>
 */
public class EvalLogTest {

    private final RecordingLogger logger = new RecordingLogger();

    @Test
    public void infoShouldUseEvalPrefix() {
        EvalLog.info(logger, "配置已读取");

        assertEquals(1, logger.infos().size());
        assertEquals("[eval] 配置已读取", logger.infos().get(0));
    }

    @Test
    public void warnAndErrorShouldUseEvalPrefix() {
        EvalLog.warn(logger, "预检失败");
        EvalLog.error(logger, "终态 CAS 异常");

        assertEquals(1, logger.warns().size());
        assertEquals("[eval] 预检失败", logger.warns().get(0));
        assertEquals(1, logger.errors().size());
        assertEquals("[eval] 终态 CAS 异常", logger.errors().get(0));
    }

    @Test
    public void judgeLogsShouldUseGatewayPrefix() {
        EvalLog.judgeInfo(logger, "判分开始");
        EvalLog.judgeWarn(logger, "响应解析失败");
        EvalLog.judgeError(logger, "调用失败");

        assertEquals("[评测] 判分开始", logger.infos().get(0));
        assertEquals("[评测] 响应解析失败", logger.warns().get(0));
        assertEquals("[评测] 调用失败", logger.errors().get(0));
    }

    @Test
    public void errorOfShouldWriteOnlyCaseErrorTypeAndElapsed() {
        EvalLog.errorOf(logger, "B-01", "TimeoutException", 1234L);

        assertEquals(1, logger.errors().size());
        assertEquals("[eval] case=B-01 失败 errorType=TimeoutException 耗时=1234ms",
                logger.errors().get(0));
    }

    @Test
    public void errorOfShouldFlattenInjectedLinesAndTruncateLongType() {
        String injectedReply = "完整回复 sk-secret-0123456789";
        EvalLog.errorOf(logger, "B-02\n[fake] 伪造行", "IllegalStateException: " + injectedReply, 7L);

        String line = logger.errors().get(0);
        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
        assertFalse("错误日志不得带出回复原文", line.contains(injectedReply));
        assertFalse(line.contains("sk-secret"));
        assertFalse(line.contains("完整回复"));
        assertEquals(1, logger.errors().size());
        assertTrue(line.contains("耗时=7ms"));
        assertTrue(line.contains("errorType=IllegalStateException"));
        assertTrue(line.length() < 200);

        EvalLog.errorOf(logger, "  \n ", null, 0L);
        assertEquals("[eval] case=- 失败 errorType=- 耗时=0ms", logger.errors().get(1));
    }

    @Test
    public void leakSafeHelpersShouldExposeLengthsOnly() {
        String reply = "这是完整回复 sk-secret";
        String replyHint = EvalLog.replyCharsOnly(reply);
        String promptHint = EvalLog.promptCharsOnly("这是完整提示词原文");

        assertFalse(replyHint.contains("sk-secret"));
        assertFalse(replyHint.contains("完整回复"));
        assertTrue(replyHint.startsWith("回复长度="));
        assertTrue(replyHint.endsWith(" 字"));
        assertFalse(promptHint.contains("提示词原文"));
        assertTrue(promptHint.startsWith("提示词长度="));
        assertEquals("回复长度=0 字", EvalLog.replyCharsOnly(null));
    }

    @Test
    public void logsShouldContainNoEmoji() {
        EvalLog.info(logger, "开始运行");
        EvalLog.warn(logger, "题面指纹不一致");
        EvalLog.judgeError(logger, "接口超时");
        EvalLog.errorOf(logger, "C-03", "IOException", 20L);

        for (String line : logger.all()) {
            for (int i = 0; i < line.length(); ) {
                int codePoint = line.codePointAt(i);
                assertFalse("日志含 emoji 码点：" + Integer.toHexString(codePoint),
                        isEmoji(codePoint));
                i += Character.charCount(codePoint);
            }
        }
    }

    private static boolean isEmoji(int codePoint) {
        return (codePoint >= 0x2600 && codePoint <= 0x27BF)
                || (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
                || codePoint == 0xFE0F
                || codePoint == 0x200D;
    }
}
