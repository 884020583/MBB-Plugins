package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginLogger;
import org.moboxlab.moboxbot.API.PluginService;
import org.moboxlab.moboxbot.API.Util.PluginConfig;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MBB-Roleplay 的评测接口网关：只做动作分发与能力汇总，不含生成逻辑。
 *
 * <p>供 MBB-Eval 通过服务名 MBB-Roleplay 调用 capabilities 与 eval-generate；生成细节交给
 * {@link RoleplayEvalGenerationService}，本类只负责能力汇总、动作分发与异常收口，
 * 不依赖 MBB-Eval 的任何类型，也不读 MBB-Eval 的数据表。</p>
 *
 * <p>能力字段取值全部来自现有配置与 persona 快照，不新增配置键。开关键按规格 §12.2 / §14.5 取
 * {@code evalApiEnable && evalEnable}：新键缺失按 true，旧键作为兼容别名参与 AND，任一为 false 即关闭。
 * evalApiEnable 的正式模板键登记、RoleplayConfig 读取处兜底与一次性废弃 WARN 归 I-5（issue #94，
 * MBB-Eval 首版发布窗口）；本切片不写回配置，也不改 RoleplayConfigMigrator 的默认键表。</p>
 */
public class RoleplayEvalGateway {
    /** 能力探测动作名 */
    public static final String ACTION_CAPABILITIES = "capabilities";
    /** 生成动作名，实现见 C-2 切片 */
    public static final String ACTION_EVAL_GENERATE = "eval-generate";
    /** 评测接口版本，v1 固定为 1 */
    public static final int EVAL_API_VERSION = 1;
    /** 单次评测注入的历史消息条数上限 */
    public static final int MAX_HISTORY_ROUNDS = 40;
    /** 单次评测注入的历史字符数上限 */
    public static final int MAX_HISTORY_CHARS = 30000;
    /** personaHash 取 sha256 前 12 位小写十六进制，两侧按此口径比对（§8.2 第 3 键、§16.4 O11） */
    public static final int PERSONA_HASH_LENGTH = 12;

    /** 评测接口日志前缀，与角色侧其他模块的「[角色]」「[复盘]」标签风格一致；规格 §10 要求评测分支只写 [评测] 运行日志 */
    private static final String LOG_PREFIX = "[评测] ";
    private static final String KEY_EVAL_API_ENABLE = "evalApiEnable";

    private final RoleplayService service;
    private final RoleplayEvalGenerationService generation;

    public RoleplayEvalGateway(RoleplayService service) {
        if (service == null) {
            throw new IllegalArgumentException("RoleplayService 不能为空");
        }
        this.service = service;
        this.generation = new RoleplayEvalGenerationService(runtimeOf(service));
    }

    /**
     * 测试入口：直接注入评测运行时，绕开需要真实插件服务器的 RoleplayService。
     *
     * <p>只在同包测试与自检里使用，生产路径始终走 {@link #RoleplayEvalGateway(RoleplayService)}。</p>
     */
    RoleplayEvalGateway(RoleplayEvalGenerationService.Runtime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("评测运行时不能为空");
        }
        this.service = null;
        this.generation = new RoleplayEvalGenerationService(runtime);
    }

    /**
     * 处理 eval-generate 动作（规格 §10.2 / §10.3）。
     *
     * <p>入参校验、配置回读、钳制、隔离与出参形状全部交给 {@link RoleplayEvalGenerationService}；
     * 这里只做服务边界的最后一道收口：任何运行时异常都转成 status=false + errorType=internal，
     * 不把异常抛给 MBB-Eval（§13.6 / AGENTS 异常隔离）。</p>
     */
    public JSONObject evalGenerate(JSONObject params) {
        try {
            return generation.generate(params);
        } catch (RuntimeException e) {
            String detail = describe(e);
            logError(pluginOf(), "生成接口内部异常：" + detail);
            return RoleplayEvalGenerationService.failure("internal", false, "评测生成内部异常：" + detail);
        }
    }

    /**
     * 把现有 RoleplayService 适配成评测运行时：只暴露只读配置、persona 快照、一次 MBB-AI chat
     * 与一行 [评测] 普通日志。刻意不暴露 trace、消息流水、发送器、工具循环、记忆与情绪服务，
     * 这样评测分支在类型层面就写不到任何群状态（§10.4.2）。
     */
    static RoleplayEvalGenerationService.Runtime runtimeOf(RoleplayService service) {
        return new ServiceRuntime(service);
    }

    private Plugin pluginOf() {
        return service == null ? null : service.plugin();
    }

    private static final class ServiceRuntime implements RoleplayEvalGenerationService.Runtime {
        private final RoleplayService service;

        private ServiceRuntime(RoleplayService service) {
            this.service = service;
        }

        @Override
        public boolean evalApiEnabled() {
            return RoleplayEvalGateway.evalApiEnabled(service.plugin(), service.config());
        }

        @Override
        public String aiProfile() {
            return aiProfileOf(service.config());
        }

        @Override
        public String evalProfile() {
            return evalProfileOf(service.config());
        }

        @Override
        public int evalMaxTokens() {
            RoleplayConfig config = service.config();
            return config == null ? 1200 : config.evalMaxTokens;
        }

        @Override
        public int evalTimeoutSecond() {
            RoleplayConfig config = service.config();
            return config == null ? 60 : config.evalTimeoutSecond;
        }

        @Override
        public String personaId() {
            RoleplayPersona persona = service.persona();
            return persona == null ? "" : textOrEmpty(persona.name);
        }

        @Override
        public String personaHash() {
            return RoleplayEvalGateway.personaHash(service.plugin(), service.config());
        }

        @Override
        public String systemPrompt() {
            RoleplayPersona persona = service.persona();
            return persona == null ? "" : persona.coreText() + persona.appearanceText();
        }

        @Override
        public JSONObject chat(JSONObject params) {
            Plugin plugin = service.plugin();
            if (plugin == null) {
                return null;
            }
            try {
                PluginService ai = plugin.getServer().getPluginManager().getService("MBB-AI");
                return ai == null ? null : ai.call("chat", params);
            } catch (RuntimeException e) {
                // 取不到 MBB-AI 或调用入口异常都按 ai-missing 收敛，不影响服务边界
                logError(plugin, "调用 MBB-AI 失败：" + describe(e));
                return null;
            }
        }

        @Override
        public void log(String line) {
            Plugin plugin = service.plugin();
            if (plugin != null) {
                plugin.getLogger().sendInfo(line);
            }
        }
    }

    /**
     * 返回能力清单。只读、无副作用，evalApiEnable=false 时 actions 只保留 capabilities。
     *
     * <p>本方法就是要穿过服务边界的入口，任何运行时异常都在这里收口：记 ERROR 并返回 status=false，
     * 不把异常抛给调用方（§13.6 实现缺陷记 ERROR；AGENTS 异常隔离）。</p>
     */
    public JSONObject capabilities() {
        try {
            return buildCapabilities(pluginOf(), service == null ? null : service.config(),
                    service == null ? null : service.persona());
        } catch (RuntimeException e) {
            String detail = describe(e);
            logError(pluginOf(), "计算 capabilities 失败：" + detail);
            JSONObject failed = new JSONObject(true);
            failed.put("status", false);
            failed.put("action", ACTION_CAPABILITIES);
            failed.put("message", "计算能力字段失败：" + detail);
            return failed;
        }
    }

    /**
     * 由当前插件、配置与 persona 快照汇总能力清单。包内可见，便于自检直接注入快照。
     */
    static JSONObject buildCapabilities(Plugin plugin, RoleplayConfig config, RoleplayPersona persona) {
        boolean evalApiEnable = evalApiEnabled(plugin, config);

        JSONObject result = new JSONObject(true);
        result.put("status", true);
        result.put("action", ACTION_CAPABILITIES);
        result.put("evalApiVersion", EVAL_API_VERSION);

        JSONArray actions = new JSONArray();
        actions.add(ACTION_CAPABILITIES);
        if (evalApiEnable) {
            actions.add(ACTION_EVAL_GENERATE);
        }
        result.put("actions", actions);

        result.put("personaId", persona == null ? "" : textOrEmpty(persona.name));
        result.put("personaHash", personaHash(plugin, config));
        result.put("configFingerprint", configFingerprint(config));

        result.put("aiProfile", aiProfileOf(config));
        result.put("evalProfile", evalProfileOf(config));
        result.put("replyMaxTokens", replyMaxTokensOf(config));
        result.put("promptTotalChars", promptTotalCharsOf(config));
        result.put("toolEnable", toolEnableOf(config));
        result.put("replyReviewEnable", replyReviewEnableOf(config));
        result.put("maxHistoryRounds", MAX_HISTORY_ROUNDS);
        result.put("maxHistoryChars", MAX_HISTORY_CHARS);
        result.put("evalApiEnable", evalApiEnable);
        return result;
    }

    /**
     * 评测接口总开关：按规格 §12.2 / §14.5 取 {@code evalApiEnable && evalEnable}，
     * 旧键 evalEnable 作为兼容别名参与 AND 兜底，任一为 false 即关闭。
     * evalApiEnable 正式入模板、RoleplayConfig 读取处兜底与一次性废弃 WARN 归 I-5（issue #94）。
     */
    static boolean evalApiEnabled(Plugin plugin, RoleplayConfig config) {
        PluginConfig pluginConfig = plugin == null ? null : plugin.getConfig();
        boolean apiEnable = pluginConfig == null || pluginConfig.getBoolean(KEY_EVAL_API_ENABLE, true);
        boolean legacyEnable = config == null || config.evalEnable;
        return apiEnable && legacyEnable;
    }

    /**
     * persona 文件原文的 sha256 前 12 位小写十六进制（§8.2 第 3 键、§16.4 O11 冻结两侧同口径）。
     * 读不到文件时记 WARN 并返回空串，让 MBB-Eval 侧按「不可比」处理，而不是静默伪造一个指纹。
     */
    static String personaHash(Plugin plugin, RoleplayConfig config) {
        if (plugin == null || config == null) {
            warn(plugin, "无法计算 personaHash：插件或配置未就绪，capabilities 的 personaHash 置空");
            return "";
        }
        String folder = plugin.getDataFolder();
        String fileName = config.personaFile == null ? "" : config.personaFile.trim();
        if (folder == null || folder.isEmpty() || fileName.isEmpty()) {
            warn(plugin, "无法计算 personaHash：persona 目录或文件名为空，capabilities 的 personaHash 置空");
            return "";
        }
        File file = new File(folder, fileName);
        if (!file.isFile()) {
            warn(plugin, "persona 文件不存在，capabilities 的 personaHash 置空：" + file.getAbsolutePath());
            return "";
        }
        try {
            String full = sha256Hex(Files.readAllBytes(file.toPath()));
            return full.length() <= PERSONA_HASH_LENGTH ? full : full.substring(0, PERSONA_HASH_LENGTH);
        } catch (IOException e) {
            warn(plugin, "读取 persona 文件失败，capabilities 的 personaHash 置空：" + e.getMessage());
            return "";
        }
    }

    /**
     * 评测相关配置指纹。字段集合与顺序取自 MBB-Eval 规格 §10.1：
     * aiProfile / evalProfile / replyMaxTokens / replyReviewEnable / toolEnable / promptTotalChars，
     * 固定顺序以换行分隔后取 sha256，键序或注释变化不影响结果。
     */
    static String configFingerprint(RoleplayConfig config) {
        StringBuilder text = new StringBuilder();
        text.append("aiProfile=").append(aiProfileOf(config));
        text.append('\n').append("evalProfile=").append(evalProfileOf(config));
        text.append('\n').append("replyMaxTokens=").append(replyMaxTokensOf(config));
        text.append('\n').append("replyReviewEnable=").append(replyReviewEnableOf(config));
        text.append('\n').append("toolEnable=").append(toolEnableOf(config));
        text.append('\n').append("promptTotalChars=").append(promptTotalCharsOf(config));
        return sha256Hex(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                int value = b & 0xFF;
                if (value < 0x10) {
                    hex.append('0');
                }
                hex.append(Integer.toHexString(value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", e);
        }
    }

    /** 配置取值统一收口，避免能力出参与指纹两处各写一份默认值而漂移 */
    private static String aiProfileOf(RoleplayConfig config) {
        return config == null ? "default" : textOrFallback(config.aiProfile, "default");
    }

    private static String evalProfileOf(RoleplayConfig config) {
        return config == null ? "" : textOrEmpty(config.evalProfile);
    }

    private static int replyMaxTokensOf(RoleplayConfig config) {
        return config == null ? 4000 : config.replyMaxTokens;
    }

    private static int promptTotalCharsOf(RoleplayConfig config) {
        return config == null ? 120000 : config.promptTotalChars;
    }

    private static boolean toolEnableOf(RoleplayConfig config) {
        return config == null || config.toolEnable;
    }

    private static boolean replyReviewEnableOf(RoleplayConfig config) {
        return config == null || config.replyReviewEnable;
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String textOrFallback(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static void warn(Plugin plugin, String message) {
        PluginLogger logger = plugin == null ? null : plugin.getLogger();
        if (logger != null) {
            logger.sendWarn(LOG_PREFIX + message);
        }
    }

    private static void logError(Plugin plugin, String message) {
        PluginLogger logger = plugin == null ? null : plugin.getLogger();
        if (logger != null) {
            logger.sendError(LOG_PREFIX + message);
        }
    }
}
