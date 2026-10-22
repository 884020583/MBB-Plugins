package org.moboxlab.mbb.trpg;

import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.Storage.StorageService;
import org.moboxlab.mbb.trpg.config.TrpgConfig;
import org.moboxlab.mbb.trpg.store.TrpgSchemaService;

/**
 * MBB-Trpg 插件主类。
 *
 * <p>S1 阶段职责：加载配置、建表并保持幂等；其余能力（存储实现、规则引擎、
 * 会话状态机、呈现与指令层）由后续切片装配到 {@link #onEnable()} 末尾的装配位。</p>
 */
public class TrpgPlugin extends Plugin {

    private TrpgConfig trpgConfig;
    private boolean trpgEnabled;
    private String disabledReason = "";

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
        StorageService storage = getServer() == null ? null : getServer().getStorage();
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
        // 后续切片装配位：SessionStore、SessionService、RuleEngine、呈现服务、指令与监听器。
        getLogger().sendInfo("[MBB-Trpg] 已启用，版本 " + getVersion() + "，数据表已就绪");
    }

    @Override
    public void onDisable() {
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
}
