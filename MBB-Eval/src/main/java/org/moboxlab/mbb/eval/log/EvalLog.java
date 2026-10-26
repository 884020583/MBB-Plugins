package org.moboxlab.mbb.eval.log;

import org.moboxlab.moboxbot.API.PluginLogger;

/**
 * MBB-Eval 统一日志封装（规格 §13.6、§16.10 I-3）。
 *
 * <p>控制台日志行首固定前缀：命令与运行器侧 {@value #PREFIX}，judge / eval-generate
 * 接口内部 {@value #GATEWAY_PREFIX}。中文、无 emoji；不得写 Token、密钥、完整对话或完整回复。</p>
 */
public final class EvalLog {

    /** 命令与运行器侧日志行首。 */
    public static final String PREFIX = "[eval] ";
    /** judge 与 eval-generate 接口内部日志行首。 */
    public static final String GATEWAY_PREFIX = "[评测] ";

    /** 错误日志里题号的最大字符数，防止误传长文本。 */
    private static final int MAX_CASE_ID_CHARS = 64;
    /** 错误日志里错误类的最大字符数，防止误传长文本。 */
    private static final int MAX_ERROR_TYPE_CHARS = 80;

    private EvalLog() {
    }

    public static void info(PluginLogger logger, String message) {
        if (logger != null) {
            logger.sendInfo(PREFIX + (message == null ? "" : message));
        }
    }

    public static void warn(PluginLogger logger, String message) {
        if (logger != null) {
            logger.sendWarn(PREFIX + (message == null ? "" : message));
        }
    }

    public static void error(PluginLogger logger, String message) {
        if (logger != null) {
            logger.sendError(PREFIX + (message == null ? "" : message));
        }
    }

    public static void judgeInfo(PluginLogger logger, String message) {
        if (logger != null) {
            logger.sendInfo(GATEWAY_PREFIX + (message == null ? "" : message));
        }
    }

    public static void judgeWarn(PluginLogger logger, String message) {
        if (logger != null) {
            logger.sendWarn(GATEWAY_PREFIX + (message == null ? "" : message));
        }
    }

    public static void judgeError(PluginLogger logger, String message) {
        if (logger != null) {
            logger.sendError(GATEWAY_PREFIX + (message == null ? "" : message));
        }
    }

    /**
     * 错误日志：只写题号、错误类与耗时（规格 §13.6、§15.9 I3），不写回复、提示词或密钥。
     *
     * <p>题号与错误类会压掉换行并截断；错误类只保留冒号前的类名（例如
     * {@code IllegalStateException: 详情} 只记 {@code IllegalStateException}），
     * 避免把异常消息里可能夹带的回复或提示词写进日志。</p>
     */
    public static void errorOf(PluginLogger logger, String caseId, String errorType, long elapsedMs) {
        if (logger == null) {
            return;
        }
        String line = PREFIX + "case=" + oneLine(caseId, MAX_CASE_ID_CHARS)
                + " 失败 errorType=" + errorTypeOnly(errorType)
                + " 耗时=" + Math.max(0L, elapsedMs) + "ms";
        logger.sendError(line);
    }

    /**
     * 只输出回复字数，供需要记录「回复规模」的调用点使用；绝不回显回复原文。
     *
     * <p>命名约定：本类中凡以 {@code CharsOnly} 结尾的方法只返回长度类信息，
     * 不新增任何返回回复或提示词原文的 helper。</p>
     */
    public static String replyCharsOnly(String reply) {
        return "回复长度=" + charCount(reply) + " 字";
    }

    /** 只输出提示词字数，绝不回显提示词原文，命名约定同 {@link #replyCharsOnly(String)}。 */
    public static String promptCharsOnly(String prompt) {
        return "提示词长度=" + charCount(prompt) + " 字";
    }

    private static int charCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.codePointCount(0, text.length());
    }

    /** 只保留错误类名：截到第一个冒号之前，再压行、截断。 */
    private static String errorTypeOnly(String errorType) {
        if (errorType == null) {
            return "-";
        }
        int cut = errorType.length();
        int asciiColon = errorType.indexOf(':');
        int wideColon = errorType.indexOf('：');
        if (asciiColon >= 0) {
            cut = Math.min(cut, asciiColon);
        }
        if (wideColon >= 0) {
            cut = Math.min(cut, wideColon);
        }
        return oneLine(errorType.substring(0, cut), MAX_ERROR_TYPE_CHARS);
    }

    /** 压掉换行与连续空白，并按需截断，避免日志注入与误传长文本。 */
    private static String oneLine(String value, int maxChars) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), maxChars));
        boolean lastWasSpace = false;
        for (int i = 0; i < value.length() && builder.length() < maxChars; ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
                if (!lastWasSpace) {
                    builder.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            builder.appendCodePoint(codePoint);
            lastWasSpace = false;
        }
        String result = builder.toString().trim();
        return result.isEmpty() ? "-" : result;
    }
}