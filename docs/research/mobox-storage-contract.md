# 主程序存储与生命周期契约实测

> 票：#3「主程序存储与生命周期契约实测」｜分支：research/mobox-storage-contract｜实测：2026-10-21
> 证据根：主程序源码 `C:\Users\88402\Desktop\Code\MoBoxBot`（下文路径相对此根）；`depend/MoBoxLib.jar` 用 `javap` 反编译核对；本仓库插件只作旁证，前缀 `MBB-*`。

## 结论速览

| 问题 | 结论 | 对跑团的可用性 |
| --- | --- | --- |
| 事务/原子更新 | 无。公共 API 只有 7 个同步方法，多语句无原子性 | 不可用 |
| 建表/升级 | 主程序不建插件表，插件自建；无 schema 版本机制 | 只能幂等 DDL |
| onDisable | 仅 disable/reload 调用，进程退出不调用 | 不可依赖 |
| 进行中局恢复 | 无恢复钩子、无干净退出标记，只能靠已落库的行重建 | 自建判定 |
| apiVersion 0.3 | 主版本锁定 + 构建期哈希冻结，不可向主程序要新接口 | 硬约束 |

## 1. Storage 接口：签名、异常、连接与并发

**结论**：`Server.getStorage()` 返回的是「全 JVM 串行、无事务、异常被吞」的原生 SQL 门面。

- 接口共 7 个方法，全部同步、均未声明 `throws`（`src/main/java/org/moboxlab/moboxbot/API/Storage/StorageService.java:12-26`）：`get/set/remove`（KV）+ `query/queryOne/update/insert`（SQL）。没有事务、批量、DDL 专用方法，也没有设置 KV 过期时间的方法。
- KV `set` 是「先 SELECT 再 INSERT 或 UPDATE」的读改写（`Plugin/StorageServiceImpl.java:34-43`），不是原子 upsert；唯一索引 `(pluginName,dataKey)`（`resources/struct-sqlite.sql:68`）保证不出现重复行，但并发下后到的 INSERT 静默失败。
- `set` 写入的 `expireTime` 恒为 0（`StorageServiceImpl.java:39`），而 `get` 只对 `expireTime>0` 判过期（`StorageServiceImpl.java:22-27`）→ 公共 API 根本设不了过期，过期分支是死代码。
- 异常语义：SQL 层一律捕获后吞掉——`update` 出错返回 -1（`Database/SqlExecutor.java:60-63`）、`insert` 返回 0（`:82-85`）、`query` 返回空 List（`:30-34`）、`queryOne` 返回 null（`:38-40`）。插件无法区分「0 行受影响」和「执行失败」。
- 唯一会抛给插件的异常来自 SQL 文本校验 `checkSql`（`StorageServiceImpl.java:79-84`）：空 SQL、不含 `plugin_`、含 `bot_` 三者抛 `IllegalArgumentException`。只查文本不查参数，故表名里出现 `bot_` 子串（如 `plugin_x_bot_log`）会被误杀。
- 并发模型：`SqlExecutor` 用一把静态锁（`SqlExecutor.java:19`）把所有 SQL 连读一起串行（`:23/52/68/90/105/119`）。连接是每线程缓存的池连接（`Database/DatabaseMain.java:18,39-50`），池大小 `sqlitePoolSize` 默认 2（`DatabaseMain.java:28`，`resources/config.yml:35`）。
- `SQLiteManager.getConnection()`（MoBoxLib，`javap` 反编译）随机取池中一条，若取不到或已关闭就重建；建连异常只 `sendException` 后 `sleep(200)` **死循环重试**，无上限无超时。该调用位于 `SqlExecutor` 全局锁内 → 一次建连持续失败会卡死主程序全部 SQL。
- 无 `PRAGMA journal_mode/WAL/synchronous` 设置（主程序源码与 MoBoxLib 均零命中）→ JDBC 默认 `autoCommit=true` + rollback journal：**单条语句是一个完整事务**，多条语句之间没有任何原子性。
- 事务能力确实存在但拿不到：`SqlExecutor.transaction(SqlTask)`（`SqlExecutor.java:118-143`，`setAutoCommit(false)` + commit/rollback）不在 `StorageService` 里，插件要用就得依赖主程序内部类，违反 AGENTS.md 的只能依赖 API 约束。

**对跑团的直接影响**：一个回合要写 `msg` + `state` + `memory` 多张表，中途崩溃/关机必然留下「用户消息已落库、回复未落库」的半局。回合结束必须是「一条语句写完关键状态」，或干脆设计成幂等重放，不能靠事务回滚。

## 2. 插件建表与升级表的既有做法

**结论**：主程序完全不碰插件表；本仓库的范式是「CREATE TABLE IF NOT EXISTS + PRAGMA table_info 补列」，没有任何 schema 版本号。

- `TableInitializer` 只读取并执行 `struct-sqlite.sql`（`Database/TableInitializer.java:18-31`），不含版本比较分支；该文件末尾无条件 `PRAGMA user_version = 1;`（`struct-sqlite.sql:96`），文件头注释却声称「结构版本用 PRAGMA user_version 管理」（`:2`）→ 版本号是摆设，每次启动都原样重跑。
- 旁证：建表全部是 `storage().update("CREATE TABLE IF NOT EXISTS \`"+TABLE+"\` (...)")`，如 `MBB-ChatStat/.../ChatStatService.java:28`、`MBB-Vision/.../VisionCache.java:22`、`MBB-Roleplay/.../RoleplaySchemaService.java:19`。
- 旁证：升级全部是 `PRAGMA table_info` 查列 + `ALTER TABLE ADD COLUMN`，如 `MBB-Roleplay/.../RoleplaySchemaService.java:50-54,80,86`、`MBB-Roleplay/.../RoleplayReminderService.java:604,610`。
- 表名约束来自 `checkSql`（见 1 节）：必须含 `plugin_`、不得含 `bot_`。`docs/PLUGIN.md:317-328` 给出的命名规范是 `plugin_<插件名>_`。

**对跑团的直接影响**：新增局/回合字段只能走 `ensureColumn` 式的幂等补列，且必须能承受任意次重复执行（主程序启动、插件 reload、玩家手动 reload 都会重跑一遍）。

## 3. 数据目录、配置重载、onDisable 与定时任务

**结论**：`getDataFolder()` 可靠；配置热更新、退出落盘、任务清理三项都不能按插件直觉假设。

- 数据目录：`loadOne` 里拼出 `pluginDir/name` 并建目录后注入（`Plugin/PluginManagerImpl.java:131-133,147`），`Plugin.getDataFolder()` 原样返回（`API/Plugin.java:60-62`）→ `onLoad` 即可写文件。目录名就是插件名，改名等于换目录、丢数据。
- 配置：`getConfig()` 首次 `new PluginConfig(dataFolder/config.yml)` 后永久缓存（`API/Plugin.java:64-67`），只有显式 `load()` 才重读文件（`API/Util/PluginConfig.java:31-48`）。
- `/reload`：控制台 `reload` 与聊天 `/reload` 都只调 `Server.reloadConfig()` → `Main.reloadConfig()`（`Main.java:95-101`），仅重读主程序 `config.yml` 和 debug 开关，**不通知插件、不重读插件 config.yml**（`Command/CommandReload.java:20`、`MBB-Reload/.../ReloadCommand.java:39`）。插件配置热更新必须自己实现。
- onDisable：只出现在 `disablePlugin`（`PluginManagerImpl.java:222`）和 `reloadPlugin`（`:244-262`）路径；进程退出 `Main.cleanup()`（`Main.java:116-135`）只做 OneBot 关闭 → 调度器 stop → 数据库 close，**从不调用任何插件 onDisable**（全仓 grep 仅命中 `Plugin.java:137` 与 `PluginManagerImpl.java:222`）。与 `docs/PLUGIN.md:118` 的硬要求（线程/连接/定时任务必须在 onDisable 释放）直接冲突。
- 框架登记的任务会被回收：`recycle`（`PluginManagerImpl.java:363-385`）对 `record.tasks` 里每个 Future 调 `future.cancel(false)`（`:375-382`）；不中断正在执行的那一次，也不覆盖插件自建的 Thread / ExecutorService。
- 调度器 4 线程、daemon（`Task/SchedulerService.java:18-22`），`shutdownNow` 收尾（`:41-45`）；它在插件加载之前就已启动（`Main.java:67` < `:70`），而所有插件的 `onLoad` 跑完之后才统一 `onEnable`（`PluginManagerImpl.java:117-122`）→ `onEnable` 期间，先前启用插件的周期任务已经在跑。
- `pluginAutoEnable/pluginHotReload/pluginDevMode` 三个开关在 `config.yml:39-41`，但全仓 grep 零读取 → 纯文档项，不构成任何行为前置条件。

**对跑团的直接影响**：不能把「关闭时 flush」当作数据安全网；回合数据必须在回合结束时同步落库。周期任务要能容忍「被 cancel 后才开始跑」和「跑一半被 shutdownNow 打断」。

## 4. 重载/升级后「进行中局」如何判定可恢复

**结论**：主程序不提供任何局/会话概念，也没有「干净退出」信号；可恢复性只能由插件用已提交的行自证。

- 无恢复钩子：`PluginManager` 只有 enable/disable/reload 与注册类方法（`API/PluginManager.java:13-39`），没有 onSave、没有停机事件、没有「上次是否异常退出」字段。
- 时序上唯一可用的钩子是 `onEnable`：它在所有插件 `onLoad` 之后、OneBot 启动之前被调用（`Main.java:70` < `:76`），此时收不到群消息，适合做一次恢复扫描；但其他插件的定时任务可能已在跑。
- 并发上不可假设同群串行：群消息经 `SchedulerService.runTaskAsync` 投到 4 线程池（`OneBot/OneBotEvent.java:87-90,98-101`），主程序没有任何按群加锁。
- 真正持久的部分：单条 INSERT/UPDATE 在 autocommit 下已落盘，进程被 kill 不会丢已返回成功的语句；会丢的是 onDisable 里的补写、内存队列，以及「一个回合多语句」的中间态。
- 因此判定规则必须只依赖行：建议 `state` 表里存 `round`/`status`+`updateTime`，把 `status` 为「进行中」的行一律视为「可能被中断」，启动时幂等重建或回滚，而不是信内存标志。
- 旁证（现状）：`RoleplaySchemaService.initSchema`（`MBB-Roleplay/.../RoleplaySchemaService.java:18-77`）建了 msg/memory/state/group/topic 五表，没有任何「回合计进行中」字段 → 目前只能靠 msg 与 state 的 `updateTime` 反推。

**对跑团的直接影响**：需要显式持久化「局」的状态机（含中断态），并在 `onEnable` 里跑一次收敛；不能假设「进程会正常调用 onDisable 收尾」。

## 5. plugin.json 的 apiVersion 0.3 硬约束

**结论**：0.3 是「主版本锁 + 构建期形状哈希」的双重冻结，插件不能在施工期指望主程序补接口。

- 检查点在读取描述时：`parse` 中 `if (!checkApiVersion(description)) return null;`（`Plugin/PluginDescriptionReader.java:87`），返回 null 即整个插件不被加载，只有一条 warn。
- 规则（`PluginDescriptionReader.java:91-108`）：缺 `apiVersion` → 跳过；主版本（第一个 `.` 之前，`:110-113`）不相等 → 跳过；声明版本**高于**主程序 → 只 warn「可能缺少接口」仍继续加载。
- 当前主程序 `API_VERSION = "0.3"`（`API/MoBoxBotAPI.java:8`）；构建期 `ApiFreezeTest` 对 31 个 API 类（含 `StorageService`）的 public 形状算 SHA-256，与 `EXPECTED_HASH` 比对（`src/test/java/org/moboxlab/moboxbot/ApiFreezeTest.java:17,46,67-105`），不一致直接让 `build.ps1` 失败（`build.ps1:64-68`）。
- 含义：0.3 的方法集合被逐字节钉死，连「非破坏性追加」也会改哈希，需要主程序主动升版本 → 在 0.4 发布前，插件拿到的存储语义就是本文第 1 节描述的那一套，不会变好。
- `depend/softDepend` 有排序逻辑（`PluginManagerImpl.java:80-117`），但软依赖缺失时的具体行为未验证 → 无法确认。

**对跑团的直接影响**：所有「等主程序支持事务/原子更新/关机钩子」的方案都应判为不可行，按现有 0.3 面设计；`plugin.json` 必须写 `apiVersion: "0.3"`（写成 0.2 同样能过主版本校验，见上条规则）。

## 无法保证的 API 假设

1. **不能假设多语句原子**：没有暴露事务，`SqlExecutor.transaction` 属内部类，插件不得依赖。
2. **不能假设出错会抛异常**：`update/insert/query` 失败被吞成 -1/0/空/null，与「没有数据」不可区分，必须用「读回来校验」代替「看返回值」。
3. **不能假设同群串行**：消息回调跑在 4 线程池上，主程序不按群加锁，同一群的两次发言可以并发进入同一插件。
4. **不能假设退出时会调 onDisable**：`Main.cleanup()` 不遍历插件，正常停机和 kill 都不触发。
5. **不能假设配置会自动重载**：`/reload` 只重载主程序配置；`getConfig()` 缓存对象直到进程结束。
6. **不能假设存在 schema 版本/迁移钩子**：主程序对插件表不建、不迁、不校验，只能幂等 DDL 自重入。
7. **不能假设 `storage` 调用一定会返回**：MoBoxLib `getConnection()` 建连失败 200ms 死循环重试且持全局锁（反编译所得，未在真实故障下复现），保守起见所有存储调用都应放在可容忍阻塞的位置。
8. **不能假设 apiVersion 是「最小版本」语义**：它只是主版本相等 + 高版本仅警告，低版本 0.x 会被静默接受，声明更高版本也不会被拒绝。
