package org.moboxlab.mbb.qq;

import org.moboxlab.moboxbot.API.Plugin;

/**
 * MBB-QQ 插件。
 */
public class QQPlugin extends Plugin {
    private QQConfig config;
    private QQService service;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        config = QQConfig.load(this);
    }

    @Override
    public void onEnable() {
        service = new QQService(this,config);
        service.init();
        getServer().getPluginManager().registerService(this,service);
        getServer().getPluginManager().registerListener(this,new QQListener(service));
        getServer().getPluginManager().registerCommand(this,new QQCommand(this,service));
        getLogger().sendInfo("MBB-QQ 已启用");
    }

    @Override
    public void onDisable() {
        getLogger().sendInfo("MBB-QQ 已停用！");
    }

    public QQConfig getQQConfig() {
        return config;
    }

    public void reloadQQ() {
        config = QQConfig.load(this);
        if (service != null) service.reload(config);
    }
}
