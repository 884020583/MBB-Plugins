# MBB-Trpg

MoBoxBot 的跑团主持插件：在群里开一局 TRPG，提供 CoC 风格 d100 骰点、局面状态管理、剧本与 AI 主持。

规格见 [docs/MBB-Trpg.md](../docs/MBB-Trpg.md)。

## 当前阶段

S4 剧本契约切片：在 S1–S3 之上冻结 v1 剧本格式、随包示例与校验器。

- `ScriptValidator`：结构与跨字段语义一起校验，全有或全无。先看字节数（≤ 65536），再解析 JSON、
  按字段白名单读值与范围、查全局 id 唯一与引用存在；未知字段只记 `W_UNKNOWN_FIELD` 告警，
  不阻断导入；文本字段先去首尾空白再判非空与长度，落盘保留原文（只把 CRLF 统一为 LF）。
- `BuiltinScriptCatalog`：启动时读 `scripts/index.txt`，逐个加载 JAR 内 `scripts/<id>.json`
  并校验缓存；内置剧本只读（删除回 `E_BUILTIN`），导入与替换归 S10（现在回 `E_IMPORT_STATE`）。
  库级读写锁先就位，供「先群锁后库读锁」的调用顺序使用。
- `ScriptImportService`：只冻结「开始 → 分段粘贴 → 结束 / 取消」端口，实现归 S10。
- 随包示例 `coc-example`（雨夜古宅）：4 个线性场景、4 条线索、2 个 NPC、3 个建议检定、
  3 个触发、3 个结局，覆盖三种 `when` 与 `clue.lockedSummary` / `npc.secret` 素材结构。

S3 已落地的规则引擎继续保留：

- `RuleSet` / `RuleRegistry`：编译期 SPI，插件启用时显式注册内置 `D100RuleSet`；
  配置只保存规则集 id（`ruleset: d100`），未知 id 回退 d100 并告警，不暴露阈值、骰型与失败口径。
- `D100RuleSet`：三档难度、六档成功等级、大成功 01 与大失败分界（技能值 < 50 时 96–100，
  否则 100）、奖励/惩罚骰一比一抵消后取最小/最大十位、一次调用完成对抗判定（成功等级 → 技能值 →
  骰面低者，平局重掷一次后仍平为 `NONE`）、SAN 损失与大失败取上限及 `sanTriggerInt`、
  成长严格大于才 +1D10 并封顶 99。
- `RandomSource`：检定只经注入的随机源掷骰，生产实现 `DefaultRandomSource` 用 `ThreadLocalRandom`，
  测试用固定骰面桩复现 `RollTrace`。

S2 已落地的存储层继续保留：

- `SqlSessionStore`：插件全部存储 SQL 的唯一落点，覆盖五表读写与 CAS 迁移；
  主程序返回码原样透传（`-1` 语句未执行、`0` 无匹配行），不做业务校验、不加锁。
- `BlacklistService`：群级拉黑名单，重复拉黑靠唯一约束收敛为一行，名单永久保留。
- `SqlTraceStore`：AI 审计端口实现，写失败只返回 false，不阻断叙述流程。
- `TrpgRetentionService`：启动清一轮，之后每小时清一次过期已结算局
  （先日志、再成员、最后主行，失败留待下一轮）与过期/超量审计行。

当前版本仍不注册任何用户可见指令；呈现、指令层、局面状态机与 AI 主持在后续切片按依赖顺序落地。

## 剧本文件

片段格式的权威定义是 [docs/scripts/README.md](../docs/scripts/README.md) 与
[docs/scripts/script-v1.schema.json](../docs/scripts/script-v1.schema.json)；JAR 里的
`script-v1.schema.json` 是后者的逐字节副本。示例见 `src/main/resources/scripts/coc-example.json`。

运行时**不**加载 JSON Schema 引擎：`ScriptValidator` 是与 schema 同构的手写结构检查加跨字段语义检查，
语义口径以 docs 为准（schema 只作机器可读摘要，个别约束故意放宽，例如 `check.loss` 的形态由
DiceExpression 再解析一次）。

- 内置剧本随 JAR 分发，只读；导入剧本放数据目录 `scripts/<id>.json`，同 id 以内置为准。
- 单份内容上限 65536 字节（BOM 计入），允许 UTF-8 BOM，但非法 UTF-8 字节回 `E_PARSE`；`schemaVersion` 固定 1，`ruleSet` 只接受 `d100`。
- 校验失败不落盘，回执最多列 5 条错误并追加「另有 N 条」；未知字段只告警。
- 内置剧本缺失或校验失败视为分发问题：插件直接置为不可用并记错误日志。

## 配置

配置写在 `config.yml`，缺失键回默认值，未知键在控制台告警。

| 分节 | 内容 |
|---|---|
| `ruleset` | 默认规则集，v1 为 `d100` |
| `session` | 招募过期、进行中过期、结束二次确认窗口 |
| `cooldown` | 开局、骰点、其余动作的冷却秒数 |
| `errorDedupeSeconds` / `activityThrottleSeconds` | 同类错误去重与局内活动节流 |
| `log` | 日志内容截断长度与分页大小 |
| `retention` | 已结束局的归档保留天数 |
| `presentation` | 输出风格、群聊/私聊长度上限、图片开关 |
| `script` | 剧本大小、导入超时、拒绝目录上限、生成冷却 |
| `aiHost` | AI 主持开关、超时、队列、挂起与轨迹保留 |

`aiHost.enabled` 只在插件加载时读一次，改配置后需要重载插件。

## 数据目录

插件数据放在 `getDataFolder()` 下：

- SQLite 数据库，表名统一 `plugin_mbb_trpg_*`。
- `scripts/`：剧本文件，同 ID 内置剧本优先。
- `imports/rejected/`：导入失败的剧本副本，最多保留 20 份。

## 归档与清理

启动时立即执行一轮，之后每小时执行一次，配置取自 `retention.sessionDays` 与
`aiHost.traceKeepHours` / `aiHost.traceMaxRows`：

- 已结束且成长结算完成的局，超过保留天数后按 日志 → 成员 → 主行 顺序删除；
  子表 DELETE 返回 -1（语句未执行、状态未知），或按库里内容预期有行却删除 0 行时，中止本局、
  不删主行，下一轮重试；库里本就没有该子表的行时，0 行属于正常结果，继续清理。
- 成长结算未完成的局永不清理；拉黑名单不随局清理。
- 审计先按小时数删过期行，再只保留最新 `traceMaxRows` 行，行数不足时不动。

## 构建

```powershell
.\build.ps1
```

可用 `-Bot` 指定主程序 JAR：

```powershell
.\build.ps1 -Bot "D:\CodeX\Projects\MoBoxBot\out\MoBoxBot.jar"
```

跑单元测试（首次会下载并校验 JUnit）：

```powershell
.\build.ps1 -Bot "D:\CodeX\Projects\MoBoxBot\out\MoBoxBot.jar" -Test
```

产物：

```text
out\MBB-Trpg.jar
```
