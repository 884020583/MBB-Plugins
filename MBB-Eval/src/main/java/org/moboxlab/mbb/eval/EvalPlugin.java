package org.moboxlab.mbb.eval;

import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.mbb.eval.config.EvalConfig;

/**
 * MBB-Eval 插件主类（A-1 骨架）。
 *
 * <p>本切片只负责：保存默认配置、在启用时读取并钳制 14 个 eval* 配置键、把越界与非法项写入日志。
 * 建表、命令注册、executor 与孤儿清理由后续切片接入；当前不注册任何命令，也不参与线上回复。</p>
 */
public class EvalPlugin extends Plugin {

    private EvalConfig evalConfig;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        getLogger().sendInfo("[MBB-Eval] 插件已加载，A-1 骨架阶段：仅读取配置，未注册命令");
    }

    @Override
    public void onEnable() {
        evalConfig = EvalConfig.load(getConfig());
        for (String warning : evalConfig.warnings()) {
            getLogger().sendWarn("[MBB-Eval] " + warning);
        }
        getLogger().sendInfo("[MBB-Eval] 配置已读取，共 " + EvalConfig.keys().size()
                + " 项，默认档位 " + evalConfig.defaultTier());
        getLogger().sendInfo("[MBB-Eval] 已启用，版本 " + getVersion());
    }

    @Override
    public void onDisable() {
        evalConfig = null;
        getLogger().sendInfo("[MBB-Eval] 已停用");
    }

    /**
     * 当前配置快照；插件未启用时为 {@code null}。
     */
    public EvalConfig evalConfig() {
        return evalConfig;
    }
}
