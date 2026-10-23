# MBB-Trpg

MoBoxBot 的跑团主持插件：在群里开一局 TRPG，提供 CoC 风格 d100 骰点、局面状态管理、剧本与 AI 主持。

规格见 [docs/MBB-Trpg.md](../docs/MBB-Trpg.md)。

## 当前阶段

v1.1 AI 主持切片已交付，版本 `V0.1.10.2.0001`（接 issue #15；v1 的 S1–S11 已合并）。

- `/trpg ai on|off` 升级为真实开关：`on` 写 `aiEnabled=1` 并清除 `aiSuspendedUntil`，
  `off` 写 `aiEnabled=0`；AI 子状态三列（`aiEnabled` / `aiSuspendedUntil` / `aiFailStreak`）
  统一由 `SessionService` 在群锁内 CAS 落库，命令层不直接写库。
- 触发点：`begin` 开场、`scene` 推进、KP 代掷 `check` / `opposed` / `san` 立即触发；
  玩家自由文本行动与玩家自助检定结果进入 20 秒或 5 条先到的合并窗口，按事件序号拼接。
- 队列与并发：每群一条串行队列、待发上限 3，全局并发默认 2、全局等待上限 4（均可在
  `aiHost.*` 配置）；超过上限不排队，直接按 `queue_full` 降级，不挤出已在队的请求。
  忙碌、超时、网络、HTTP、输出校验、配置关闭等 12 类降级按规格表落审计与计数。
- 失败与挂起：可计类失败连续 3 次后写 `aiSuspendedUntil` 并清队列；挂起期零调用，
  每周期只发一次挂起提示；`/trpg ai on` 即清除挂起重新计数。
- 审计：`ai_trace` 每请求至多 1 行；全局关、未授权、窗口无内容不写行，挂起丢弃写 `skipped`、换局换场景写 `stale`；`traceEnabled=false` 不写。
- 叙述呈现：正文按 300 字截断、建议最多 3 条、数值变化/检定结论/推进句式越权即拒；
  落款 `—— 主持人 · <KP 名>（AI 叙述）`；降级与挂起文案见
  [docs/MBB-Trpg-presentation.md](../docs/MBB-Trpg-presentation.md) §3.6。

S10 剧本导入与生成切片继续保留：`ScriptLibrary`、`ScriptImportSessions`、`ScriptGenerationService`、
`MbbAiClient`、`ScriptRejectedStore` 与删除/替换的引用保护（`E_IN_USE`）全部接到 `/trpg script` 链路。

- `ScriptLibrary`：真正的 `ScriptCatalog` 实现，内置剧本只读、导入剧本落在
  `<dataFolder>/scripts/<id>.json`；建库时全量加载导入目录，单个文件校验失败只告警跳过。
- `ScriptImportSessions`：按（会话, 用户）内存暂存的三段式导入（`import` / `import replace`
  → 分段粘贴 → `import end` / `import cancel`）；5 分钟空闲或 30 分钟绝对超时，上限
  65536 字节 / 200 段；解析失败保留暂存，语义失败清暂存并把原文存入 `imports/rejected/`，
  同类最多保留 20 份；暂存期间该用户同会话消息一律按原文片段拼接，控制字大小写不敏感。
- `ScriptGenerationService`：`/trpg script gen <题材> [人数] [时长]`，BOT_ADMIN / OWNER，
  默认 3 人 / 60 分钟、60 秒冷却、全局单槽位，跑在插件自有单线程 daemon executor 上；
  走 MBB-AI `PluginService.call("chat")`（`timeoutSeconds=90`、`maxTokens=6000`），
  剥 Markdown 围栏后覆盖 `id=gen-<yyyyMMddHHmmss>-<4位36进制>`、`author` 缺省 `MBB-AI`，
  失败按 `E_AI_DISABLED / E_AI_BUSY / E_AI_TIMEOUT / E_AI_OUTPUT / E_AI_VALIDATION /
  E_AI_FAILED` 分类、一律不落盘并给出手工导入退路；不写 `ai_trace`、不进 AI 主持队列。
- 删除与替换的引用保护：`SessionService` 实现 `ScriptUsageIndex`，被任何未归档局
  （含重启恢复）引用的剧本禁止删除 / 替换（`E_IN_USE`），归档局不阻止；库级读写锁把
  「检查引用 + 改文件」放在同一写锁内，读锁不覆盖网络发送。
- 共享端口微调：`AiRequest` 增加 `maxTokens` / `temperature`（旧 5 参构造保留），
  `Messaging` 增加 `sendGroupAt` 供生成结果只 @ 发起人。

S9 的「空 AI」契约桩已在 v1.1 升级为真实实现：

- `TrpgAiHostService` 现在实现 `AiTriggerSink` 并持有 `AiHostEngine`；全局关时仍保持
  零调用、零审计、不注册定时器（结构性成立）。全局开时装配 `MbbAiClient`、
  `SqlTraceStore`、`PluginManagerScheduler` 与 `TrpgAiNarrationSink`。
- `/trpg ai on|off`：开关词解析仍在服务里，权限、状态、冷却判定仍留在 `SessionService`；
  `on` 写库并清除挂起，`off` 幂等回执；全局关时 `ai on` 按「当前状态不可用」拒绝。
- `status` 的 AI 行按库里的 `aiEnabled` / `aiSuspendedUntil` / `aiFailStreak` 投影。
- `SessionService` 实现 `SessionView`（`readSnapshot(群号)`）与 `AiFailureRecorder`，
  引擎按群号只读局面、失败计数经同一群锁 CAS 落库（§9.11 测试缝）。
- 触发接线：`LifecycleCommandHandler.begin`、`KpCommandHandler` 的 `scene` / `check` /
  `opposed` / `san` 与 `TrpgListener` 的玩家自由文本行动都已接入 `AiTriggerSink`。

S8 已落地的指令层 B 继续保留：

- `KpCommandHandler` 注册 `scene` / `check` / `opposed` / `san` / `adjust` / `invite` /
  `take` / `skip` / `ai`，`AdminCommandHandler` 注册 `script` / `blacklist`；
  分派主干仍是 S7 的 handler 注册表，本片只追加 handler，不改分派与门禁。
- `scene`：按标题推进幕次并解锁该幕线索，群内只发线索名与公开钩子，线索全文只私聊 KP；
  场景图降级为纯文本，触发提示只私聊 KP。
- `check`：KP 可代掷或由玩家本人发起，支持 `bp` / `pp` 各 1～2 颗奖惩骰，
  回执按冻结样例给骰面、目标值与成功等级，奖惩骰抵消与十位取值的说明附在正文。
- `opposed`：对手可以是玩家技能，也可以是 0～99 的目标值；纯数字一律按目标值解析，
  只有提及形式（`<@QQ>` 或 `[CQ:at,qq=]`）才按玩家解析，避免把「50」当成用户号。
- `san`：损失写成「成功损失/失败损失」，支持常数与 `NdM+K`；SAN 归零按永久疯狂退场，
  触发 INT 检定失败按 `1D10` 小时临时疯狂，回执写明持续时间。
- `adjust` 招募期与进行中都能改技能值并封顶 0～99；`invite` 进行中补人并恢复离场成员的局籍；
  `take` 记录代管、`skip` 记录跳过原因。
- `script list` / `script show` / `script import` / `script delete` / `script gen` 仅机器人管理员可用，
  `script <剧本ID>` 只在招募期更换本局剧本；导入走三段式暂存并支持 `replace`，删除与替换受
  引用保护。
- `blacklist add|remove|list`：群管理员管理本群名单，私聊机器人管理员可按「群号 QQ」移除；
  黑名单成员在群内发言被静默取消，指令也不回任何话。
- 监听器：`TrpgListener` 在群消息入口做黑名单拦截、导入暂存吞消息与局内活动刷新；
  `TrpgNoticeListener` 在 KP 离群时按 `kp_left` 收尾，非 KP 离群只记日志不归档。
- `DiceExpressions` 统一 `NdM+K` 解析（SAN 损失与剧本检定共用口径）。

S7 已落地的指令层 A 继续保留：

- `TrpgCommand`（`/trpg`）与 `DiceCommand`（`/r`、`/roll`）：主类只做转发，框架权限一律 `EVERYONE`，
  真正的权限、状态与冷却判定全在 `SessionService`。
- `TrpgSubcommand`：主名与中文别名（`start / 开团`、`pc reroll / 重掷`、`pc set / 微调` 等）
  只在这一处定义，指令分派、帮助列表与拒绝话术共用；本片起全部子命令均已启用。
- 生命周期与只读：`start` / `join` / `leave` / `begin` / `end`（二次确认按「群 + 局 + 发起者」计）/
  `resume` / `status` / `help` / `pc` / `pc reroll` / `pc set` / `log`；命令层只解析与委托，
  不写库、不持状态，一条指令只回一条回执，拒绝话术统一走 `TrpgPresenter` 的【错误】模板。
- 指令分派用 `TrpgSubcommandHandler` 注册表 + `TrpgCommandContext`（共享冷却、错误去重、
  昵称与回执出口），S8 只往里注册新 handler，不改分派主干。
- 私聊门禁：局内指令一律回「请在群内使用」，只放行 `help`、`/r` 与 `script` 库管理
  （导入暂存在私聊同样可用，生成结果回私聊）。
- `DiceParser` 与 `/r`：支持 `NdM` 与加法最多 3 段（`1d100+5`），常数段不掷骰；
  乘除、技能名等一律回「表达式不支持」；2 秒冷却、不落库、群聊私聊都可用。
  回执单段一行 `/r 3d6 = 9`，多段每段一行 `[r] 3d6 = 9` 并在末行给合计。
- `CardGenerator` / `Profession`：`join` 时随机生成八属性角色卡（3D6×5、SAN=POW、
  HP=(CON+SIZ)/10、MP=POW/5、物品取剧本起始物品）；`pc reroll` 只重掷自己的卡，
  `pc set` 只改八属性 15～90 且改 POW 不联动 SAN。
- 插件启用时注册 `/trpg`、`/r`、`/roll` 与两个群监听器，注册失败只记错误日志、其余能力照常。

S6 已落地的呈现层继续保留：

- `TrpgPresenter`：模板 → 清洗与截断 → 拆分 → 图片 → 发送；任何发送失败只发告警不重试，
  开团/开局/结束这类关键消息发送失败时补一行【提示】。群消息与私聊走同一个出口，
  结束与恢复事件在群锁外由它消费。
- `TrpgMessageTemplates`：纯函数模板，标题取自冻结词表 `TrpgTitles`；
  样例文案与 `docs/MBB-Trpg-presentation.md` 的冻结样例逐字对齐。
- `TrpgMessageFormatter` / `TrpgMessageSplitter`：清洗控制字符、计字数、超长截断；
  群内按 段落空行 → 换行 → 句读 回扫拆分最多 3 条，第 2/3 条标题加「· 续 n/m」，
  第 3 条仍超长时在末尾折叠并提示 `/trpg log`；私聊只折叠不拆。
- `TrpgImageTemplates`：情景卡 ≤ 14 行（元信息、在场、线索、叙述折行、下一步），
  战报固定九节且 ≤ 22 行，每行 ≤ 26 全角字符；`TrpgImageRenderer` 端口由 `ImageUtilRenderer` 实现。
  渲染不可用或图片发送失败时回退纯文本，回退后不重试。
- 失败回退：情景卡回【场景 · 标题】纯文本并提示 `/trpg status`，战报回【战报】，
  脚本页回私聊纯文本；角色卡私聊不可达转为群内【提示】，连续 2 次私聊失败后
  角色卡改在群内折叠、只列职业与关键数值；秘密信息绝不回退到群内。
- `TrpgLogReportSource` 把结束事件装配成战报（KP、玩家、时长、线索、检定次数、成长），
  `TrpgPresentConfig` 收敛 `presentation.*` 的取值区间。

S5 已落地的局面状态机继续保留：

- `SessionService`：局面唯一写入口，四态 `NONE` / `RECRUITING` / `RUNNING` / `ENDED` 与 T1–T6 迁移；
  每群一把锁（容量上限加空闲淘汰），临界区内不做网络发送，结束事件在解锁后统一发布；
  判定顺序为群内 → 权限 → 状态 → 参数 → 冷却 → 执行；`-1` 中止本步不推进结算，`0` 回读后按新状态处理。
- 收尾顺序冻结：CAS 置 `ENDED` → 逐成员成长（一条 UPDATE 同时写新角色卡并清空待成长技能）→
  写摘要并把 `growthSettled` 置 1 → 发布结束事件。摘要按结局所需线索是否全解锁选取，
  截断 2000 字符；战报消费与真实发送归 S6。
- 惰性过期：招募超 24 小时、进行中超 7 天无局内活动时，在下一次读状态或任一指令时归档；
  局内活动由消息入口按 60 秒节流抬升 `lastActivityAt`，旁观者不计。
- 启动序列：建表 → 未结算局补做成长与摘要 → 过期清理 → 扫活跃局并加载成员 →
  进行中的局标记为待确认恢复并发布【恢复】事件（文案归 S6）→ KP 用 `/trpg resume` 确认。
- 未知 `ruleSetID` 在运行时回退 `d100` 并告警，库内原值保持不变。
- 职责拆分：`SessionService` 只做状态迁移与门面转发，启动恢复与补做在 `SessionRecoveryService`，
  快照读取与局内活动刷新在 `SessionReader`；群锁注册表用并发容器加监视器登记，持锁期间锁对象不会被淘汰。

S4 已落地的剧本契约继续保留：

- `ScriptValidator`：结构与跨字段语义一起校验，全有或全无。先看字节数（≤ 65536），再解析 JSON、
  按字段白名单读值与范围、查全局 id 唯一与引用存在；未知字段只记 `W_UNKNOWN_FIELD` 告警，
  不阻断导入；文本字段先去首尾空白再判非空与长度，落盘保留原文（只把 CRLF 统一为 LF）。
- `BuiltinScriptCatalog`：启动时读 `scripts/index.txt`，逐个加载 JAR 内 `scripts/<id>.json`
  并校验缓存；运行时以 `ScriptLibrary` 组合「内置只读 + 导入目录」，内置剧本删除回 `E_BUILTIN`。
- `ScriptImportService` 端口由 S10 的 `ScriptImportSessions` 实现（分段粘贴、超时、字节/段数上限、失败留档）。
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

`/trpg`、`/r`、`/roll`、`/trpg script` 与 AI 主持全链路均已注册；AI 主持在配置
`aiHost.enabled=true` 时装配引擎与每秒一次的合并窗口结算，否则保持零调用桩；模型与密钥由 MBB-AI 侧配置。

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
