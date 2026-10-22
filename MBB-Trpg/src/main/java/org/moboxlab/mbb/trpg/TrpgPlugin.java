package org.moboxlab.mbb.trpg;

import org.moboxlab.moboxbot.API.Plugin;

/**
 * MBB-Trpg 插件主类。
 *
 * <p>当前仅为骨架版本（S1 第一阶段）：只负责插件生命周期与版本日志，
 * 存储、规则引擎、会话状态机、指令层等能力由后续切片逐步接入。</p>
 */
public class TrpgPlugin extends Plugin {

    @Override
    public void onEnable() {
        getLogger().sendInfo("MBB-Trpg 已启用，版本 " + getVersion() + "，当前为骨架版本");
    }

    @Override
    public void onDisable() {
        getLogger().sendInfo("MBB-Trpg 已停用");
    }
}
