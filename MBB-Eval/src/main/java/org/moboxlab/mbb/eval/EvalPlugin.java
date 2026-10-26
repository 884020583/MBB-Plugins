package org.moboxlab.mbb.eval;

import java.io.File;
import java.lang.management.ManagementFactory;

import org.moboxlab.moboxbot.API.Plugin;
import org.moboxlab.moboxbot.API.Storage.StorageService;
import org.moboxlab.mbb.eval.bank.EvalBankCache;
import org.moboxlab.mbb.eval.bank.EvalBankResources;
import org.moboxlab.mbb.eval.baseline.EvalBaselineService;
import org.moboxlab.mbb.eval.command.EvalCommand;
import org.moboxlab.mbb.eval.config.EvalConfig;
import org.moboxlab.mbb.eval.gateway.EvalCapabilityClient;
import org.moboxlab.mbb.eval.gateway.RoleplayEvalGateway;
import org.moboxlab.mbb.eval.judge.EvalJudgePrompt;
import org.moboxlab.mbb.eval.rubric.EvalRubricCache;
import org.moboxlab.mbb.eval.runner.EvalDataBootstrap;
import org.moboxlab.mbb.eval.runner.EvalPreflight;
import org.moboxlab.mbb.eval.runner.EvalPreflightSources.CapabilitiesStatusSource;
import org.moboxlab.mbb.eval.runner.EvalRunRecovery;
import org.moboxlab.mbb.eval.runner.EvalRunner;
import org.moboxlab.mbb.eval.runner.EvalStageSources;
import org.moboxlab.mbb.eval.score.EvalDimensionWeights;
import org.moboxlab.mbb.eval.store.EvalReadOnlyBoundary;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStore;

/**
 * MBB-Eval 插件主类（A-1 骨架 + A-2 存储骨架）。
 *
 * <p>负责：保存默认配置、在启用时读取并钳制 14 个 eval* 配置键、把越界与非法项写入日志；
 * 启用时幂等创建四张数据表与索引，失败则禁用评测写路径但不重建、不删表；
 * 随后装配 {@link EvalPreflight} 与 {@link EvalRunner} 并注册 /eval 命令。
 * 停用时先让 runner 尽力把 RUNNING 行收尾为 ABORTED(shutdown)，再释放引用。
 * 题库与 rubric 在 onEnable 一次性定位、校验并缓存（B-5），运行期不热重载；缓存失败只记录
 * 状态并拒绝 /eval run，不影响插件启用与只读命令。报表与判决由后续切片接入。</p>
 */
public class EvalPlugin extends Plugin {

    private EvalConfig evalConfig;
    private EvalStore evalStore;
    private EvalRunner evalRunner;
    private EvalCapabilityClient capabilityClient;
    private CapabilitiesStatusSource capabilitiesSource;
    private EvalDataBootstrap dataBootstrap;
    private EvalBankCache bankCache;
    private EvalRubricCache rubricCache;
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
        initEvalData();
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
     * 装配题库与 rubric 缓存（§5.7.4、§5.8）。
     *
     * <p>顺序：capabilities 快照（取 personaId/personaHash）→ rubric → 题库（缺失时释放内置
     * 并钉版本）。全部失败只记日志、置缓存状态，不抛异常、不阻止插件启用；/eval report 等
     * 只读命令不受影响。</p>
     */
    private void initEvalData() {
        try {
            RoleplayEvalGateway gateway = new RoleplayEvalGateway(this, getLogger());
            capabilityClient = new EvalCapabilityClient(gateway, getLogger());
            final EvalCapabilityClient client = capabilityClient;
            capabilitiesSource = new CapabilitiesStatusSource() {
                @Override
                public EvalCapabilityClient.Snapshot snapshot() {
                    return client.snapshot();
                }
            };
            EvalCapabilityClient.Snapshot snapshot = capabilityClient.snapshot();
            String dataFolderPath = getDataFolder();
            java.io.File dataFolder = dataFolderPath == null ? null : new java.io.File(dataFolderPath);
            dataBootstrap = EvalDataBootstrap.load(dataFolder, evalConfig.bankFile(),
                    EvalJudgePrompt.PROMPT_VERSION,
                    snapshot == null ? null : snapshot.personaId(),
                    snapshot == null ? null : snapshot.personaHash(),
                    evalConfig.allowPersonaMismatch(), EvalBankResources.of(this),
                    EvalBankResources.of(this), getLogger());
            bankCache = dataBootstrap.bank();
            rubricCache = dataBootstrap.rubric();
            for (String warning : dataBootstrap.warnings()) {
                getLogger().sendWarn("[评测] " + warning);
            }
            if (!dataBootstrap.pinFileName().isEmpty()) {
                pinBankFile(dataBootstrap.pinFileName());
            }
        } catch (RuntimeException e) {
            // 缓存初始化异常只禁用 run 预检，不影响插件启用与只读命令（§13.1.1 规则 4）。
            getLogger().sendError("[MBB-Eval] 评测数据初始化异常，/eval run 将被预检拒绝；"
                    + "/eval report 仍可用：" + e.getMessage());
        }
    }

    /** 首次释放内置题库后把 evalBankFile 钉成该版本文件名，避免下次启动回退到默认名。 */
    private void pinBankFile(String fileName) {
        try {
            getConfig().set("evalBankFile", fileName);
            getConfig().save();
            getLogger().sendInfo("[评测] 已将 evalBankFile 固定为 " + fileName);
        } catch (RuntimeException e) {
            getLogger().sendWarn("[评测] 写回 evalBankFile 失败，请在 config.yml 手动填写 "
                    + fileName + "：" + e.getMessage());
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
        EvalPreflight.Builder builder = EvalPreflight.builder()
                .config(evalConfig)
                .logger(getLogger())
                .bank(EvalStageSources.bank(bankCache))
                .rubric(EvalStageSources.rubric(rubricCache))
                .persona(EvalStageSources.persona(bankCache, capabilitiesSource,
                        evalConfig.allowPersonaMismatch()));
        if (capabilitiesSource != null) {
            builder.capabilities(capabilitiesSource);
        }
        EvalPreflight preflight = builder.build();
        evalRunner = new EvalRunner(evalStore, evalConfig, preflight, getLogger(), getVersion());
        // 只读命令只注入库、题库缓存与报表目录，不注入 runner（§9.7、§16.8 G-3）。
        EvalReadOnlyBoundary boundary = new EvalReadOnlyBoundary(new File(getDataFolder()));
        EvalBaselineService baselineService = buildBaselineService();
        getServer().getPluginManager().registerCommand(this, new EvalCommand(
                evalRunner, evalConfig, evalStore, baselineService, bankCache, boundary.reportsDirectory()));
        getLogger().sendInfo("[MBB-Eval] 已注册 /eval 命令（run 为占位执行；status/report/list/baseline "
                + "为只读命令，不写库、不建 run、不发起 AI 调用；baseline set / abort 为写侧命令"
                + (baselineService == null ? "，基线服务因 rubric 权重不可用未接线" : "") + "）");
    }

    /**
     * 按 rubric 五维权重构造基线服务（§8.1、§16.8 G-4）。
     *
     * <p>rubric 未就绪或权重非法时返回 null：baseline set 只回「写侧基线服务未接线」，
     * 不影响只读命令与 abort，也不阻止插件启用；原因写入日志便于排查。</p>
     */
    private EvalBaselineService buildBaselineService() {
        if (rubricCache == null || !rubricCache.ok()) {
            getLogger().sendWarn("[MBB-Eval] rubric 未就绪，baseline set 不可用（不影响只读命令与 abort）");
            return null;
        }
        try {
            EvalDimensionWeights weights = EvalDimensionWeights.from(rubricCache.rubric());
            return new EvalBaselineService(weights, getLogger());
        } catch (RuntimeException e) {
            getLogger().sendWarn("[MBB-Eval] 构造基线服务失败，baseline set 不可用：" + e.getMessage());
            return null;
        }
    }

    @Override
    public void onDisable() {
        if (evalRunner != null) {
            evalRunner.shutdown();
            evalRunner = null;
        }
        evalConfig = null;
        evalStore = null;
        capabilityClient = null;
        capabilitiesSource = null;
        dataBootstrap = null;
        bankCache = null;
        rubricCache = null;
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

    /** onEnable 的题库/rubric 装配结果；未启用时为 {@code null}。 */
    public EvalDataBootstrap dataBootstrap() {
        return dataBootstrap;
    }

    /** 只读题库缓存；加载失败时仍非 null（带错误码与明细），未启用时为 {@code null}。 */
    public EvalBankCache bankCache() {
        return bankCache;
    }

    /** 只读 rubric 缓存；未启用时为 {@code null}。 */
    public EvalRubricCache rubricCache() {
        return rubricCache;
    }
}
