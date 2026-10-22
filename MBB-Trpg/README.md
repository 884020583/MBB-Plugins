# MBB-Trpg

MoBoxBot 的跑团主持插件：在群里开一局 TRPG，提供 CoC 风格 d100 骰点、局面状态管理、剧本与 AI 主持。

规格见 [docs/MBB-Trpg.md](../docs/MBB-Trpg.md)。

## 当前阶段

S1 骨架切片：已落地插件骨架、注入端口、配置读取、幂等建表与补列、测试基座。

当前版本加载后只初始化数据库与配置，不注册任何用户可见指令；规则引擎、剧本、呈现、指令层与 AI 主持在后续切片按依赖顺序落地。

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
