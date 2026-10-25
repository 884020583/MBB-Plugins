package org.moboxlab.mbb.eval;

import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.Storage.StorageService;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStore;

/**
 * MBB-Eval 插件主类（A-1 骨架 + A-2 存储骨架）。
 *
 * <p>负责：保存默认配置、在启用时读取并钳制 14 个 eval* 配置键、把越界与非法项写入日志；
 * 启用时幂等创建四张数据表与索引，失败则禁用评测写路径但不重建、不删表；
 * schema 就绪后装配 {@link EvalStore}，供后续切片生成 run_id 与终态 CAS。
 * 命令注册、executor、题库与报表由后续切片接入；当前不注册任何命令，也不参与线上回复。</p>
 */
public class EvalPlugin extends Plugin {

    private EvalConfig evalConfig;
    private EvalStore evalStore;
    private boolean schemaReady;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        getLogger().sendInfo("[MBB-Eval] 插件已加载，骨架阶段：读取配置与数据表，未注册命令");
    }

    @Override
    public void onEnable() {
        evalConfig = EvalConfig.load(getConfig());
        for (String warning : evalConfig.warnings()) {
            getLogger().sendWarn("[MBB-Eval] " + warning);
        }
        getLogger().sendInfo("[MBB-Eval] 配置已读取，共 " + EvalConfig.keys().size()
                + " 项，默认档位 " + evalConfig.defaultTier());
        initStorage();
        getLogger().sendInfo("[MBB-Eval] 已启用，版本 " + getVersion());
    }

    /** 建表失败只禁用评测写路径：绝不在已有库上 DROP 或重建。 */
    private void initStorage() {
        schemaReady = false;
        if (getServer() == null) {
            getLogger().sendError("[MBB-Eval] 服务器未就绪，数据表未创建，评测能力已禁用");
            return;
        }
        StorageService storage = getServer().getStorage();
        if (storage == null) {
            getLogger().sendError("[MBB-Eval] 存储服务不可用，数据表未创建，评测能力已禁用");
            return;
        }
        EvalSchemaService schema = new EvalSchemaService(storage, getLogger());
        if (!schema.ensureSchema()) {
            getLogger().sendError("[MBB-Eval] 数据表未就绪，评测能力已禁用；"
                    + "不会删除或重建已有表，请检查数据库与上方告警后重启插件");
            return;
        }
        evalStore = new EvalStore(storage, getLogger());
        schemaReady = true;
        getLogger().sendInfo("[MBB-Eval] 数据表已就绪（4 表 / 7 索引）");
    }

    @Override
    public void onDisable() {
        evalConfig = null;
        evalStore = null;
        schemaReady = false;
        getLogger().sendInfo("[MBB-Eval] 已停用");
    }

    /**
     * 当前配置快照；插件未启用时为 {@code null}。
     */
    public EvalConfig evalConfig() {
        return evalConfig;
    }

    /**
     * run_id 与终态 CAS 入口；schema 未就绪或插件未启用时为 {@code null}。
     */
    public EvalStore evalStore() {
        return evalStore;
    }

    /**
     * 四表七索引是否就绪；未就绪时后续切片不得写入评测数据。
     */
    public boolean schemaReady() {
        return schemaReady;
    }
}
