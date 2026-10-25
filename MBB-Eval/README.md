# MBB-Eval

MoBoxBot 的离线评测与回归基线插件：用固定题库对 MBB-AI 做可重复的自动评测，由 LLM 判分并与基线对比。评测只在命令触发或后台调度时运行，**不参与线上对话回复**。

- 规格：[docs/MBB-Eval.md](../docs/MBB-Eval.md)
- 规格票：#50；实施票：#51（A-1 插件骨架与构建接入）、#53（A-2 四表建表、索引与 run_id/CAS 骨架）

## 当前阶段

A-2 存储骨架阶段：插件可构建、可被主程序加载、可读取并钳制配置；启用时幂等创建四张数据表与七个索引，并对老库只补列、不删除不重建。尚未注册 `/eval` 命令，也不含题库加载、判分、报表与基线逻辑，这些由后续切片按规格 §16.2 接入。

当前版本：`V0.1.0.0.0405`（apiVersion `0.3`）。

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

## 命令与权限（规划）

命令在后续切片注册，当前不可用。权限口径见规格 §9：`/eval run`、`/eval abort`、`/eval baseline set` 需要 OWNER；`/eval status`、`/eval report`、`/eval list`、`/eval baseline`、`/eval baseline show` 需要 BOT_ADMIN；`/eval help` 对所有人开放。

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

## 安装

1. 构建出 `MBB-Eval.jar`；
2. 放入主程序 `plugins/` 目录；
3. 重启主程序，插件会自动生成默认 `config.yml`；
4. 按需修改配置后重载插件或重启。

MBB-Eval 经 `capabilities` / `eval-generate` 调用 MBB-Roleplay 的评测接口，judge 侧调用走 MBB-AI 的 chat 能力；两者均为 `softDepend`，缺少时插件仍可加载，但评测功能不可用。

## 数据目录

插件数据目录由框架提供（`getDataFolder()`）。按规格，报表固定写入 `./MoBoxBot/plugins/MBB-Eval/reports/`，题库文件位于 `./MoBoxBot/plugins/MBB-Roleplay/evals/`；数据表落在主程序数据库，由 `StorageService` 管理。当前存储骨架阶段只建表与索引，不写评测数据、不生成报表。
