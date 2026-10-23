package org.moboxlab.mbb.trpg.script;

import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import org.moboxlab.mbb.trpg.ai.AiClient;
import org.moboxlab.mbb.trpg.ai.AiErrorTypes;
import org.moboxlab.mbb.trpg.ai.AiRequest;
import org.moboxlab.mbb.trpg.ai.AiResult;

/**
 * 剧本生成服务：一次性的 MBB-AI chat 调用 + 与导入完全相同的校验与落盘链路（§8.3）。
 *
 * <p>插件内只有一个生成槽位，已有任务在跑时立刻回 {@code E_AI_BUSY}，不排队；
 * 生成线程由装配层提供的单线程 daemon executor 承载，本类自身不创建线程。</p>
 *
 * <p>任何失败都不落盘：原始返回（空内容写占位记录）存入 imports/rejected/，
 * 回执带上错误码与手动导入退路。</p>
 */
public final class ScriptGenerationService {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String DEFAULT_AUTHOR = "MBB-AI";
    private static final int MAX_REPORTED = 5;

    private final ScriptLibrary library;
    private final ScriptValidator validator;
    private final AiClient aiClient;
    private final ScriptRejectedStore rejected;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ScriptGenerationService(ScriptLibrary library, ScriptValidator validator,
                                   AiClient aiClient, ScriptRejectedStore rejected) {
        if (library == null) {
            throw new IllegalArgumentException("剧本库不能为空");
        }
        if (validator == null) {
            throw new IllegalArgumentException("校验器不能为空");
        }
        if (aiClient == null) {
            throw new IllegalArgumentException("AI 客户端不能为空");
        }
        if (rejected == null) {
            throw new IllegalArgumentException("拒绝原文存档不能为空");
        }
        this.library = library;
        this.validator = validator;
        this.aiClient = aiClient;
        this.rejected = rejected;
    }

    /** 当前是否有生成任务在跑。 */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 同步生成一份剧本并落盘；调用方负责放到自有 executor 上执行。
     *
     * @param theme      题材（不含空白）
     * @param players    玩家人数，同时作为 minPlayers 与 maxPlayers
     * @param minutes    预计时长
     * @param operatorId 发起人（只用于拒绝原文文件名与日志）
     */
    public ScriptGenerationOutcome generate(String theme, int players, int minutes,
                                            long operatorId) {
        long startedAt = System.currentTimeMillis();
        if (!running.compareAndSet(false, true)) {
            return failure(ScriptErrorCodes.E_AI_BUSY, null, null, 0,
                    System.currentTimeMillis() - startedAt);
        }
        try {
            AiRequest request = ScriptGenerationPrompts.build(theme, players, minutes);
            AiResult result = aiClient.chat(request);
            long elapsed = System.currentTimeMillis() - startedAt;
            if (!result.success()) {
                String code = mapError(result.errorType());
                String saved = saveRaw(result.content(), operatorId, code);
                return failure(code, saved, null, 0, elapsed);
            }
            String raw = stripFence(result.content());
            JSONObject json;
            try {
                json = JSON.parseObject(raw);
            } catch (RuntimeException error) {
                String saved = saveRaw(result.content(), operatorId, ScriptErrorCodes.E_AI_OUTPUT);
                return failure(ScriptErrorCodes.E_AI_OUTPUT, saved, null, 0, elapsed);
            }
            if (json == null) {
                String saved = saveRaw(result.content(), operatorId, ScriptErrorCodes.E_AI_OUTPUT);
                return failure(ScriptErrorCodes.E_AI_OUTPUT, saved, null, 0, elapsed);
            }
            json.put("id", nextId());
            if (isBlank(json.getString("author"))) {
                json.put("author", DEFAULT_AUTHOR);
            }
            byte[] content = json.toJSONString().getBytes(UTF8);
            ScriptValidationResult validation = validator.validateGenerated(content);
            if (!validation.ok()) {
                String saved = saveRaw(result.content(), operatorId,
                        ScriptErrorCodes.E_AI_VALIDATION);
                return failure(ScriptErrorCodes.E_AI_VALIDATION, saved,
                        validation.reportedErrors(), validation.errorCount(), elapsed);
            }
            try {
                ScriptImportResult imported = library.saveGenerated(content);
                int nodeCount = imported.script().sceneCount();
                return ScriptGenerationOutcome.success(imported.script(), nodeCount,
                        System.currentTimeMillis() - startedAt);
            } catch (ScriptValidationException error) {
                String saved = saveRaw(result.content(), operatorId,
                        ScriptErrorCodes.E_AI_VALIDATION);
                ScriptValidationResult failedResult = error.validation();
                return failure(ScriptErrorCodes.E_AI_VALIDATION, saved,
                        failedResult == null ? null : failedResult.reportedErrors(),
                        failedResult == null ? 0 : failedResult.errorCount(),
                        System.currentTimeMillis() - startedAt);
            } catch (ScriptException error) {
                String saved = saveRaw(result.content(), operatorId, ScriptErrorCodes.E_AI_FAILED);
                return failure(ScriptErrorCodes.E_AI_FAILED, saved, null, 0,
                        System.currentTimeMillis() - startedAt);
            }
        } finally {
            running.set(false);
        }
    }

    /** 生成剧本 id：gen-<yyyyMMddHHmmss>-<4 位 36 进制随机>。 */
    static String nextId() {
        String time = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(new Date());
        int random = ThreadLocalRandom.current().nextInt(36 * 36 * 36 * 36);
        String suffix = Integer.toString(random, 36);
        StringBuilder padded = new StringBuilder();
        for (int i = suffix.length(); i < 4; i++) {
            padded.append('0');
        }
        padded.append(suffix);
        return "gen-" + time + "-" + padded;
    }

    /**
     * 剥掉整条内容外面的一对 Markdown 代码围栏；只有围栏包住整体时才剥。
     */
    static String stripFence(String content) {
        String text = content == null ? "" : content.trim();
        if (!text.startsWith("```") || !text.endsWith("```") || text.length() < 6) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String inner = text.substring(firstNewline + 1, text.length() - 3);
        return inner.trim();
    }

    private String saveRaw(String raw, long operatorId, String code) {
        java.io.File file = rejected.save(raw, operatorId, code);
        return file == null ? "" : file.getName();
    }

    private static String mapError(String errorType) {
        if (AiErrorTypes.DISABLED.equals(errorType) || AiErrorTypes.PARAMS.equals(errorType)) {
            return ScriptErrorCodes.E_AI_DISABLED;
        }
        if (AiErrorTypes.BUSY.equals(errorType)) {
            return ScriptErrorCodes.E_AI_BUSY;
        }
        if (AiErrorTypes.TIMEOUT.equals(errorType)) {
            return ScriptErrorCodes.E_AI_TIMEOUT;
        }
        return ScriptErrorCodes.E_AI_FAILED;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private static ScriptGenerationOutcome failure(String code, String savedFile,
                                                   List<ScriptIssue> errors, int errorCount,
                                                   long elapsedMs) {
        return ScriptGenerationOutcome.failure(code, savedFile,
                errors == null ? java.util.Collections.<ScriptIssue>emptyList()
                        : new java.util.ArrayList<ScriptIssue>(
                                errors.subList(0, Math.min(MAX_REPORTED, errors.size()))),
                errorCount, elapsedMs);
    }
}
