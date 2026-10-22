package org.moboxlab.mbb.trpg;

import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.PluginManager;
import org.moboxlab.moboxbot.API.Storage.StorageService;
import org.moboxlab.mbb.trpg.ai.TraceStore;
import org.moboxlab.mbb.trpg.config.TrpgConfig;
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
 * <p>当前职责：加载配置、建表并保持幂等，装配存储实现、审计端口、拉黑名单与归档清理，
 * 并排入每小时清理任务；规则引擎、会话状态机、呈现与指令层由后续切片装配到
 * {@link #onEnable()} 末尾的装配位。</p>
 */
public class TrpgPlugin extends Plugin {

    private TrpgConfig trpgConfig;
    private boolean trpgEnabled;
    private String disabledReason = "";

    private SessionStore sessionStore;
    private TraceStore traceStore;
    private BlacklistService blacklistService;
    private TrpgRetentionService retentionService;
    private ScheduledTask retentionTask;

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
        trpgEnabled = true;
        disabledReason = "";
        assembleServices(storage);
        // 后续切片装配位：SessionService、RuleEngine、呈现服务、指令与监听器。
        getLogger().sendInfo("[MBB-Trpg] 已启用，版本 " + getVersion() + "，数据表已就绪");
    }

    private void assembleServices(StorageService storage) {
        SqlSessionStore store = new SqlSessionStore(storage, getLogger());
        sessionStore = store;
        traceStore = new SqlTraceStore(store);
        blacklistService = new BlacklistService(store);
        retentionService = new TrpgRetentionService(store, getLogger(),
                trpgConfig.retentionSessionDays(), trpgConfig.aiTraceKeepHours(),
                trpgConfig.aiTraceMaxRows());

        // 启动先清一轮，再交给定时任务按小时执行
        try {
            TrpgRetentionService.Result result = retentionService.runOnce(System.currentTimeMillis());
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
        sessionStore = null;
        traceStore = null;
        blacklistService = null;
        retentionService = null;
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
}
