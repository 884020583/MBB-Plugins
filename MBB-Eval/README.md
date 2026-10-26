# MBB-Eval

MoBoxBot 的离线评测与回归基线插件：用固定题库对 MBB-AI 做可重复的自动评测，由 LLM 判分并与基线对比。评测只在命令触发或后台调度时运行，**不参与线上对话回复**。

- 规格：[docs/MBB-Eval.md](../docs/MBB-Eval.md)
- 规格票：#50；实施票：#51（A-1 插件骨架与构建接入）、#53（A-2 四表建表、索引与 run_id/CAS 骨架）、#60（A-3 命令受理、预检桩与 runner 骨架）、#61（A-4 Roleplay 网关骨架）、#54（A-5 测试载体与最小用例）、#57（G-1 命令框架与权限矩阵）；规格 #50 其余切片（F-1 存储门面、D-2 rubric 加载、D-1 确定性判分、B-1 内置题库、C-3 能力协商、C-2 Roleplay 评测生成、F-5 基线与九键可比性、E-3 报表性能节、H-2 调用重试与预算记账、H-4 终态与生命周期、I-5 Roleplay 旧键收口与墓碑，以及 A–I 区其余切片）已按 §16 波次全部落地

## 当前阶段

规格 #50 已按 §16 波次落地：插件可构建、可被主程序加载、可读取并钳制配置；启用时幂等创建四张数据表与七个索引，并对老库只补列、不删除不重建。启用期一次性完成「定位题库 → 仅在缺失时释放内置 `bank-1.0.0` 与默认 rubric → 先 rubric 后题库校验 → persona 绑定 → 建缓存」，失败按错误码记 WARN/ERROR 并保留绝对路径，只读命令照常可用；不热重载。`/eval` 9 条命令全部可用：`run` 经八项预检（接口能力、题库、rubric、persona、judge profile、MBB-AI、数据目录、预算）后受理，命令线程立即返回三行回执，实际评测在插件自有单线程 daemon 中执行（smoke 12 题 / full 40 题，逐题 `eval-generate` 采样与 judge 判分，每次物理调用先占预算再落 `_call` 行），预算、墙钟、停止点、判断熔断与孤儿回收均按 §9/§13 收口，终态只经 CAS 写入且 `-1`/`0` 只告警不重试。`report` 从库现算九节 Markdown（含 §7 性能节、L3 用量旁证、基线 delta 与显著性、隐私字段白名单），`baseline` 维护唯一 active 基线并按九键判断可比性，`baseline set` 只接受 COMPLETED full 且理由必填，`list` 零调用列出题库，`status` 只读展示进度与失败分布；群内摘要为 ≤15 行纯文本。库：`bank-1.0.0`（40 条，配比 12/8/8/6/6，smoke 12）与默认 rubric `1.1.0-c32119f4` 随 JAR 发布，只在 `evals/` 缺失时释放，已存在的文件不覆盖、不删除。

当前版本：`V0.1.0.0.2325`（apiVersion `0.3`）。

## 配置

插件在启用时读取一次配置并钳制（§12.4）：

- 缺键使用默认值；
- 数值越界收敛到区间端点，并在启动日志中逐条告警；
- `evalDefaultTier` 只接受 `smoke` / `full`，`evalAllowPersonaMismatch` 只接受 `true` / `false`（不区分大小写），非法值回退默认值并告警；
- 未知键告警后忽略；
- v1 不提供 `/eval reload`，改配置后需重载插件或重启主程序。

| 配置键 | 默认值 | 取值与钳制 | 说明 |
| --- | --- | --- | --- |
| `evalCallBudget` | `100` | `1..100`，大于 100 按 100 处理并告警 | 单 run 硬顶调用次数（含重试与缓存重采） |
| `evalRunDeadlineMinutes` | `30` | `5..120` | 整 run 墙钟硬顶 |
| `evalKeepRuns` | `30` | `5..200` | 保留最近 run 数 |
| `evalDefaultTier` | `smoke` | `smoke` / `full` | `/eval run` 无参时的档位 |
| `evalTimeoutSecond` | `60` | `20..300` | 单次生成超时（秒） |
| `evalJudgeTimeoutSecond` | `60` | `20..120` | 单次 judge 调用超时（秒） |
| `evalGenerateRetries` | `1` | `0..1` | 生成错误重试次数，attempt 不超过 2 |
| `evalCachedResamples` | `3` | `1..3` | `cached=true` 时最多重采次数 |
| `evalFailStreakAbort` | `3` | `2..5` | 连续失败熔断阈值 |
| `evalFailBackoffSeconds` | `30` | `5..120` | 熔断前退避时长（秒） |
| `evalBankFile` | 空 | 相对 `evals/` 的路径 | 指定单题库文件；空表示首次启动释放内置题库并钉成当时版本 |
| `evalAllowPersonaMismatch` | `false` | `true` / `false` | 题库 persona 与 `capabilities.personaHash` 不一致时降级为告警试跑 |
| `evalProfile` | 空 | 非空时作为评测生成的默认 profile | 为空时使用 `capabilities` 返回的 `aiProfile` |
| `evalJudgeProfile` | 空 | 空则回退 `evalProfile`，再为空回退 `capabilities.aiProfile` | judge 档案；为空时报告须标注「同家族 judge，分数仅作趋势」 |

## 数据表

插件启用时经主程序 `StorageService` 幂等执行建表与建索引（规格 §4.2、§4.6）：

- `plugin_mbb_eval_run`：评测运行主记录（run_id、档位、状态、快照、调用预算等）；索引 `idx_eval_run_started`、`idx_eval_run_status`。
- `plugin_mbb_eval_case_result`：用例结果；唯一索引 `idx_eval_case_uniq(run_id, case_id)`，检索索引 `idx_eval_case_dim`。
- `plugin_mbb_eval_call`：单次生成与判分调用记录；索引 `idx_eval_call_run`、`idx_eval_call_stage`。
- `plugin_mbb_eval_baseline`：回归基线；唯一索引 `idx_eval_baseline_active(active) WHERE active = 1`。

建表、补列或建索引失败时记 ERROR 并禁用评测写路径（`/eval report` 等只读命令仍可用），绝不 DROP 或重建已有表；后续版本新增列先用 `PRAGMA table_info` 读现有列，确认缺列再 `ALTER TABLE ADD COLUMN`。

## 命令与权限

已注册 `/eval`，逐子命令权限见规格 §9（与 `/eval help` 输出一致）：`/eval run`、`/eval abort`、`/eval baseline set` 需要 OWNER；`/eval status`、`/eval report`、`/eval list`、`/eval baseline`、`/eval baseline show` 需要 BOT_ADMIN；`/eval help` 对所有人开放。子命令与档位大小写敏感，冷却 3 秒（由主程序命令框架按 `cooldownSeconds` 执行），无 `reload`；无子命令回用法提示，未知子命令与 `/eval reload` 回「未知子命令」提示，两者都列出可用命令，且不建 run、不调用 AI。

全部子命令可用：`run` 逐项预检并按 §9.3.1 回三行受理回执（题数 / 预计调用与预算 / 状态与中止入口），同一时刻只允许一个 run（内存单飞 + DB RUNNING 行双保险），重复 run 回当前 runId 与进度；`status` 支持无参与指定 run 的只读快照（进度、`calls_used`/`calls_budget`、失败分布、剩余墙钟）；`report` 只对 COMPLETED 从库重建报表并回路径与 ≤15 行摘要；`list` 零调用列出题库；`baseline` 与 `baseline show` 展示 active 基线与最近历史；`baseline set` 仅 OWNER 且理由必填；`abort` 走终态 CAS，插件停用时尽力置 ABORTED(shutdown)。拒绝类回执统一带错误码、绝对路径、最多 5 条明细与下一步动作。

## 跨插件接口

MBB-Eval 不 import MBB-Roleplay 的任何内部类，只经主程序的服务接口调用其公开动作（规格 §10）：

- `capabilities`：版本协商与能力探测，返回 `evalApiVersion`、`actions`、`evalApiEnable` 及 persona / profile / 历史窗口等只读快照；始终可调。
- `eval-generate`：按一条用例生成一条回复，请求只带 `runId`、`caseId`、`sampleIndex`、`attempt`、`history`、`message` 与采样参数，不接受外部 `sessionId` / `selfID` / persona；`sessionId` 由本侧编码后由角色侧回显核对。

`evalApiVersion` 必须为 `1` 且 `actions` 含 `eval-generate`，否则 `/eval run` 在预检阶段被拒并给出「版本不匹配 / 未启用 / 插件缺失」三类原因之一；角色侧接口缺失或未实现该动作时返回明确错误而不是静默降级。出参中的 `errorType` 只接受规格 §10.3 的封闭集合，重试与否只看角色侧 `retryable`，本侧不按错误码猜测。评测不写 Roleplay 的状态表与 trace 表，判分调用走 MBB-AI 的 chat 能力。

## 构建

在本插件目录执行：

```powershell
.\build.ps1 -Bot "D:\CodeX\Projects\MoBoxBot\out\MoBoxBot.jar"
```

或在仓库根目录执行全量构建：

```powershell
.\build-all.ps1 -Bot "D:\CodeX\Projects\MoBoxBot\out\MoBoxBot.jar"
```

产物为 `out/MBB-Eval.jar`，全量构建后同时汇总到仓库根目录 `out/`。构建脚本只编译本插件源码、复制 `src/main/resources`，不打包 MoBoxBot API 与 fastjson；实际 JAR 依赖主程序提供。

本地自检（可选）：

```powershell
.\build.ps1 -Bot "D:\CodeX\Projects\MoBoxBot\out\MoBoxBot.jar" -Test
```

`-Test` 在打包成功后编译 `src/test/java` 并用 JUnitCore 跑 `*Test` 用例，覆盖配置键与钳制、四表七索引的 SQL 文本边界（索引名、唯一性与部分谓词）、`run_id` 格式与主键冲突重生成、终态 CAS 返回值语义、权限矩阵，以及命令框架（`help` 列出 9 条命令与最低权限、权限不足只回一句且不触碰 runner、无子命令/未知子命令/`reload`/`baseline` 多余参数的提示、子命令与档位大小写敏感、冷却 3 秒、`[eval]` 前缀），以及存储门面 ST1–ST10 契约（含 `-1` / `0` 语义与部分行识别）、rubric 加载与版本校验、确定性判分（8 步归一化 / 知识维 / 风格维）、v1 题库结构与 persona 绑定、能力协商五态，以及直接解包 `out/MBB-Eval.jar` 检查根目录清单与 `plugin.json` / `config.yml` 内容；当前共 58 个测试类 591 个测试方法，覆盖存储门面契约、判分内核、报表渲染与重建、run 主循环与中止路径、预检八项与错误码呈现、基线 delta 与可信度门槛。首次运行会把 `junit 4.13.2` 与 `hamcrest-core 1.3` 下载到 `out/test-libs` 并做 sha1 校验（离线环境可手工放入同名 jar，内置 sha1 兜底），下载失败即构建失败。全量构建 `build-all.ps1` 不传 `-Test`，因此没有测试环境时全量构建不受影响。

## 安装

1. 构建出 `MBB-Eval.jar`；
2. 放入主程序 `plugins/` 目录；
3. 重启主程序，插件会自动生成默认 `config.yml`；
4. 按需修改配置后重载插件或重启。

MBB-Eval 经 `capabilities` / `eval-generate` 调用 MBB-Roleplay 的评测接口，judge 侧调用走 MBB-AI 的 chat 能力；两者均为 `softDepend`，缺少时插件仍可加载，但评测功能不可用。

## 数据目录

插件数据目录由框架提供（`getDataFolder()`）。按规格，报表固定写入 `./MoBoxBot/plugins/MBB-Eval/reports/`，题库文件位于 `./MoBoxBot/plugins/MBB-Roleplay/evals/`；数据表落在主程序数据库，由 `StorageService` 管理。当前骨架阶段只写 run 主记录（受理时 RUNNING、停止或中止时 ABORTED），不写 case/call 行、不生成报表。
