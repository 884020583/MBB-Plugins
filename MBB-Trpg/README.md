# MBB-Trpg

MoBoxBot 的跑团主持插件：在群里开一局 TRPG，提供 CoC 风格 d100 骰点、局面状态管理、剧本与 AI 主持。

规格见 [docs/MBB-Trpg.md](../docs/MBB-Trpg.md)。

## 当前阶段

S2 存储切片：在 S1 骨架上落地 SQLite 存储实现、审计端口、拉黑名单与归档清理。

- `SqlSessionStore`：插件全部存储 SQL 的唯一落点，覆盖五表读写与 CAS 迁移；
  主程序返回码原样透传（`-1` 语句未执行、`0` 无匹配行），不做业务校验、不加锁。
- `BlacklistService`：群级拉黑名单，重复拉黑靠唯一约束收敛为一行，名单永久保留。
- `SqlTraceStore`：AI 审计端口实现，写失败只返回 false，不阻断叙述流程。
- `TrpgRetentionService`：启动清一轮，之后每小时清一次过期已结算局
  （先日志、再成员、最后主行，失败留待下一轮）与过期/超量审计行。

当前版本仍不注册任何用户可见指令；规则引擎、剧本、呈现、指令层与 AI 主持在后续切片按依赖顺序落地。

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
