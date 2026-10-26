package org.moboxlab.mbb.eval;

import java.lang.management.ManagementFactory;

import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.Storage.StorageService;
import org.moboxlab.mbb.eval.command.EvalCommand;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.runner.EvalPreflight;
import org.moboxlab.mbb.eval.runner.EvalRunRecovery;
import org.moboxlab.mbb.eval.runner.EvalRunner;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStore;

/**
 * MBB-Eval 插件主类（A-1 骨架 + A-2 存储骨架）。
 *
 * <p>负责：保存默认配置、在启用时读取并钳制 14 个 eval* 配置键、把越界与非法项写入日志；
 * 启用时幂等创建四张数据表与索引，失败则禁用评测写路径但不重建、不删表；
 * 随后装配 {@link EvalPreflight} 与 {@link EvalRunner} 并注册 /eval 命令。
 * 停用时先让 runner 尽力把 RUNNING 行收尾为 ABORTED(shutdown)，再释放引用。
 * 题库、rubric、报表与判决由后续切片接入；当前 run 为占位执行，不读题库、不发起 AI 调用。</p>
 */
public class EvalPlugin extends Plugin {

    private EvalConfig evalConfig;
    private EvalStore evalStore;
    private EvalRunner evalRunner;
    private boolean schemaReady;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        getLogger().sendInfo("[MBB-Eval] 插件已加载，骨架阶段：读取配置、数据表与命令");
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
        recoverOrphans();
        initRunner();
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

    /**
     * 启动期回收上次进程遗留的 RUNNING 行（§4.5、§13.5、ST10）。
     *
     * <p>判定依据是 started_at 早于本次进程启动时刻，而不是运行时长；只 CAS 成
     * ABORTED(orphan)，不删 run 行、不删 case/call 行、不删报表。数据表未就绪或回收失败
     * 只记 WARN，不影响插件启用。</p>
     */
    private void recoverOrphans() {
        if (evalStore == null) {
            return;
        }
        long processStartedAt;
        try {
            processStartedAt = ManagementFactory.getRuntimeMXBean().getStartTime();
        } catch (RuntimeException e) {
            processStartedAt = System.currentTimeMillis();
            getLogger().sendWarn("[MBB-Eval] 读取进程启动时刻失败，孤儿回收改按当前时刻判定："
                    + e.getMessage());
        }
        try {
            new EvalRunRecovery(evalStore, getLogger(), processStartedAt).recoverOrphans();
        } catch (RuntimeException e) {
            getLogger().sendWarn("[MBB-Eval] 孤儿 RUNNING 行回收失败，留待下次启动重试："
                    + e.getMessage());
        }
    }

    /**
     * 装配预检与 runner，并注册 /eval 命令。
     *
     * <p>即使数据表未就绪也要注册命令：/eval status、/eval help 仍应可用，run 由预检回
     * 「数据表未就绪」而不是静默消失（§4.6、§13.4）。</p>
     */
    private void initRunner() {
        if (getServer() == null) {
            getLogger().sendError("[MBB-Eval] 服务器未就绪，未注册 /eval 命令");
            return;
        }
        EvalPreflight preflight = EvalPreflight.forPlugin(this, evalConfig);
        evalRunner = new EvalRunner(evalStore, evalConfig, preflight, getLogger(), getVersion());
        getServer().getPluginManager().registerCommand(this, new EvalCommand(evalRunner, evalConfig));
        getLogger().sendInfo("[MBB-Eval] 已注册 /eval 命令（骨架阶段，run 为占位执行，不发起 AI 调用）");
    }

    @Override
    public void onDisable() {
        if (evalRunner != null) {
            evalRunner.shutdown();
            evalRunner = null;
        }
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
