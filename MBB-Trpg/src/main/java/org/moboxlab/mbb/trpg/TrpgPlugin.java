package org.moboxlab.mbb.trpg;

import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginManager;
import org.moboxlab.moboxbot.API.Storage.StorageService;
import org.moboxlab.mbb.trpg.ai.TraceStore;
import org.moboxlab.mbb.trpg.config.TrpgConfig;
import org.moboxlab.mbb.trpg.rule.D100RuleSet;
import org.moboxlab.mbb.trpg.rule.DefaultRandomSource;
import org.moboxlab.mbb.trpg.rule.RandomSource;
import org.moboxlab.mbb.trpg.rule.RuleRegistry;
import org.moboxlab.mbb.trpg.rule.RuleSet;
import org.moboxlab.mbb.trpg.script.BuiltinScriptCatalog;
import org.moboxlab.mbb.trpg.script.ScriptCatalog;
import org.moboxlab.mbb.trpg.script.ScriptValidator;
import org.moboxlab.mbb.trpg.session.Clock;
import org.moboxlab.mbb.trpg.session.PluginManagerScheduler;
import org.moboxlab.mbb.trpg.session.ScheduledTask;
import org.moboxlab.mbb.trpg.store.BlacklistService;
import org.moboxlab.mbb.trpg.store.SessionStore;
import org.moboxlab.mbb.trpg.store.SqlSessionStore;
import org.moboxlab.mbb.trpg.store.SqlTraceStore;
import org.moboxlab.mbb.trpg.store.TrpgRetentionService;
import org.moboxlab.mbb.trpg.store.TrpgSchemaService;

/**
 * MBB-Trpg 插件主类。
 *
 * <p>当前职责：加载配置、注册内置规则集并选定生效规则集、建表并保持幂等，装配存储实现、
 * 审计端口、拉黑名单与归档清理，并排入每小时清理任务；会话状态机、呈现与指令层由后续切片
 * 装配到 {@link #onEnable()} 末尾的装配位。</p>
 */
public class TrpgPlugin extends Plugin {

    private TrpgConfig trpgConfig;
    private boolean trpgEnabled;
    private String disabledReason = "";

    private RuleSet ruleSet;
    private RandomSource randomSource;
    private SessionStore sessionStore;
    private TraceStore traceStore;
    private BlacklistService blacklistService;
    private TrpgRetentionService retentionService;
    private ScheduledTask retentionTask;
    private ScriptCatalog scriptCatalog;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        trpgConfig = TrpgConfig.load(getConfig());
        for (String warning : trpgConfig.warnings()) {
            getLogger().sendWarn("[MBB-Trpg] " + warning);
        }
        getLogger().sendInfo("[MBB-Trpg] 配置已加载，共 " + TrpgConfig.keys().size() + " 项，AI 主持"
                + (trpgConfig.aiHostEnabled() ? "已启用" : "未启用"));
    }

    @Override
    public void onEnable() {
        if (trpgConfig == null) {
            trpgConfig = TrpgConfig.load(getConfig());
        }
        // 规则集是编译期 SPI：先注册内置实现，再按配置里的 id 取用。
        // 注册与取用不依赖数据库，因此放在建表之前，建表失败时规则集依然可用。
        registerBuiltinRuleSets();
        if (getServer() == null) {
            trpgEnabled = false;
            disabledReason = "服务器未就绪";
            getLogger().sendError("[MBB-Trpg] 取不到服务器实例，TRPG 能力已禁用");
            return;
        }
        StorageService storage = getServer().getStorage();
        if (storage == null) {
            trpgEnabled = false;
            disabledReason = "存储服务不可用";
            getLogger().sendError("[MBB-Trpg] 取不到存储服务，TRPG 能力已禁用");
            return;
        }
        TrpgSchemaService schemaService = new TrpgSchemaService(storage, getLogger());
        boolean ready = schemaService.ensureSchema();
        if (!ready) {
            trpgEnabled = false;
            disabledReason = "数据表未就绪";
            getLogger().sendError("[MBB-Trpg] 建表失败，TRPG 能力已禁用；不会删除或重建已有表，"
                    + "请检查数据库与上方告警后重启插件");
            return;
        }
        if (!assembleScriptCatalog()) {
            return;
        }
        trpgEnabled = true;
        disabledReason = "";
        assembleServices(storage);
        // 后续切片装配位：SessionService、RuleEngine、呈现服务、指令与监听器。
        getLogger().sendInfo("[MBB-Trpg] 已启用，版本 " + getVersion() + "，数据表已就绪");
    }

    private void registerBuiltinRuleSets() {
        RuleRegistry.register(new D100RuleSet());
        randomSource = DefaultRandomSource.INSTANCE;
        ruleSet = RuleRegistry.getOrDefault(trpgConfig.ruleSetId());
        if (ruleSet == null) {
            getLogger().sendError("[MBB-Trpg] 规则集 " + trpgConfig.ruleSetId()
                    + " 未注册且内置 d100 缺失，检定不可用");
        } else if (ruleSet.id().equals(trpgConfig.ruleSetId())) {
            getLogger().sendInfo("[MBB-Trpg] 规则集 " + ruleSet.id() + "（" + ruleSet.displayName()
                    + "）已就绪");
        }
        // 回退告警由 RuleRegistry 统一记录，这里只负责打成插件日志，避免同一件事报两遍。
        for (String warning : RuleRegistry.warnings()) {
            getLogger().sendWarn("[MBB-Trpg] " + warning);
        }
    }

    /**
     * 加载并校验内置剧本库。
     *
     * <p>内置剧本随 JAR 分发，缺失或校验失败属于分发问题：此时把插件置为不可用，
     * 避免指令层对外暴露不可用剧本。导入剧本属于 S10，届时由剧本库组合视图负责。</p>
     *
     * @return 加载成功返回 true；失败时已设置禁用原因与错误日志
     */
    private boolean assembleScriptCatalog() {
        BuiltinScriptCatalog catalog;
        try {
            catalog = new BuiltinScriptCatalog(TrpgPlugin.class.getClassLoader(),
                    new ScriptValidator());
        } catch (RuntimeException e) {
            trpgEnabled = false;
            disabledReason = "内置剧本未就绪";
            scriptCatalog = null;
            getLogger().sendError("[MBB-Trpg] 内置剧本加载失败，TRPG 能力已禁用：" + e.getMessage());
            return false;
        }
        scriptCatalog = catalog;
        for (String warning : catalog.warnings()) {
            getLogger().sendWarn("[MBB-Trpg] 剧本校验告警：" + warning);
        }
        getLogger().sendInfo("[MBB-Trpg] 内置剧本已加载，共 " + catalog.size() + " 份");
        return true;
    }

    private void assembleServices(StorageService storage) {
        SqlSessionStore store = new SqlSessionStore(storage, getLogger());
        Clock clock = new Clock() {
            @Override
            public long nowMillis() {
                return System.currentTimeMillis();
            }
        };
        sessionStore = store;
        traceStore = new SqlTraceStore(store);
        blacklistService = new BlacklistService(store);
        retentionService = new TrpgRetentionService(store, getLogger(), clock,
                trpgConfig.retentionSessionDays(), trpgConfig.aiTraceKeepHours(),
                trpgConfig.aiTraceMaxRows());

        // 启动顺序按规格 §6.5：建表 → 成长与摘要补做 → 清理。补做属于后续切片，
        // 落地后要插在下面这一行之前，保证先结算再清理。
        try {
            TrpgRetentionService.Result result = retentionService.runOnce(clock.nowMillis());
            getLogger().sendInfo("[MBB-Trpg] 启动清理完成：" + result);
        } catch (RuntimeException e) {
            getLogger().sendWarn("[MBB-Trpg] 启动清理异常，已跳过：" + e.getMessage());
        }
        PluginManager pluginManager = getServer() == null ? null : getServer().getPluginManager();
        if (pluginManager == null) {
            retentionTask = null;
            getLogger().sendWarn("[MBB-Trpg] 取不到插件管理器，归档清理不会按小时执行");
            return;
        }
        try {
            retentionTask = retentionService.scheduleHourly(
                    new PluginManagerScheduler(this, pluginManager, getLogger()));
        } catch (RuntimeException e) {
            retentionTask = null;
            getLogger().sendWarn("[MBB-Trpg] 归档清理定时任务注册失败：" + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (retentionTask != null) {
            retentionTask.cancel();
            retentionTask = null;
        }
        ruleSet = null;
        randomSource = null;
        sessionStore = null;
        traceStore = null;
        blacklistService = null;
        retentionService = null;
        scriptCatalog = null;
        getLogger().sendInfo("[MBB-Trpg] 已停用");
    }

    /** 配置快照；未加载时为 null。 */
    public TrpgConfig trpgConfig() {
        return trpgConfig;
    }

    /** TRPG 能力是否可用（建表失败时为 false）。 */
    public boolean trpgEnabled() {
        return trpgEnabled;
    }

    /** TRPG 被禁用的原因；可用时为空串。 */
    public String disabledReason() {
        return disabledReason;
    }

    /** 当前生效的规则集；注册完成后即有值，插件停用后为 null。 */
    public RuleSet ruleSet() {
        return ruleSet;
    }

    /** 生产随机源；注册完成后即有值，插件停用后为 null。 */
    public RandomSource randomSource() {
        return randomSource;
    }

    /** 会话存储；未启用时为 null。 */
    public SessionStore sessionStore() {
        return sessionStore;
    }

    /** AI 审计端口；未启用时为 null。 */
    public TraceStore traceStore() {
        return traceStore;
    }

    /** 拉黑名单服务；未启用时为 null。 */
    public BlacklistService blacklistService() {
        return blacklistService;
    }

    /** 归档清理服务；未启用时为 null。 */
    public TrpgRetentionService retentionService() {
        return retentionService;
    }

    /** 剧本库（内置只读）；未启用时为 null。 */
    public ScriptCatalog scriptCatalog() {
        return scriptCatalog;
    }
}
