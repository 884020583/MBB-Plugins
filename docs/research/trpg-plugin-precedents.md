# 跑团插件可复用先例调研（GitHub issue #2）

范围：本 worktree 完整检出，22 个插件全部检索；只做静态代码阅读，未运行插件。判定三档：可抄（仅依赖 `org.moboxlab.moboxbot.API`）、需改造（骨架可用但绑定本插件门面/配置/人设，或有边界缺陷）、不可用（缺关键能力）。

## 结论表

| # | 调研项 | 结论 | 关键位置 | 理由 |
|---|---|---|---|---|
| 1 | Poll 多步投票状态与选项解析 | 需改造 | `PollCommand.java:114-129`（时长）、`:58-75`（选项）、`PollService.java:12-45`（状态） | 跨命令状态只有一个内存 Map，无持久化、无惰性超时；选项解析可抄，状态机要重写 |
| 2 | Roleplay 会话/记忆/定时任务边界 | 需改造 | `RoleplayExecutorService.java:22-79`、`RoleplayMessageContentService.java:49-62`、`RoleplayReminderService.java:38-58` | 可复用只有 API 抽象、线程池与表生命周期；Service 全部绑 `RoleplayService` 门面，不可整体移植 |
| 3 | Remind 持久化与重启恢复 | 不可用（该插件本身无持久化），改抄 Roleplay 提醒服务 | `RemindCommand.java:67-74`；`RoleplayReminderService.java:39-57,88-92,399-421,423-445` | Remind 是纯 `runTaskLater` 内存态，重启即丢；正确先例是「先落库 pending → 注册定时器 → init 恢复」 |
| 4 | Random 骰子实现与参数校验 | 参数校验需改造，骰子能力不可用 | `RandomCommand.java:15,40-64` | 只有均匀整数；无 `2d6`/种子/记录；`max+1` 有溢出边界 |
| 5 | ImageUtil 渲图用法 | 调用约定可抄 | `HelpCommand.java:97-103`、`PluginsCommand.java:111-117`、`VersionCommand.java:52-57` | 只有 `renderText(title, lines)` 同步返回 `byte[]`，失败回退文本；实现不在本仓库，超长/换行无法确认 |

## 1. MBB-Poll

事实链（`MBB-Poll/src/main/java/org/moboxlab/mbb/poll/`）：

- 指令非交互式：`PollCommand.java:49-57` 一次解析完，`args[1]` 为时长（10 秒~86400 秒），没有再追问下一步，因此没有「交互式多步」状态。
- 选项解析：`PollCommand.java:58-75`，`args[2]` 为问题，`args[3..]` 为选项；无选项时默认 `赞成/反对`（`:60-62`），自定义选项须 2~10 个（`:67-74`）。
- 跨命令中间态：`PollService.java:12` `static Map<Long,Poll> polls`，一个群同时只能有一个进行中投票（`:15-16`）；`create` 记录 `endTime/active`（`:22-23`）；`vote` 以 `index-1` 覆盖旧票，可改票（`:28-34`）；`finish` `remove` 并置 `active=false`（`:36-41`）。
- 超时清理：只有 `PollCommand.java:83` 一次 `runTaskLater(..., finish, duration)`；`PollService.java:22` 写入的 `endTime` 全仓库无读取点（grep 仅 `:22` 赋值与 `:53` 声明），没有惰性过期兜底；`PollPlugin.java:17-19` 在 onDisable 调 `PollService.clear()`（`:43-45`），重启后投票全丢。
- 判定：时长/选项解析可抄；投票状态机需改造（落库 + 定时器 + 到期惰性校验双保险，参考第 3 项）。Poll 本身不能当作「多步指令中间态」样板。
- 更贴切的多步中间态先例：`MBB-Sticker/.../StickerReceiveService.java:30-43,61-95` 用户级 `Session{expireAt,count}` + 访问时惰性过期删除（`:71-78`、`:85-90`）；`MBB-AIGuard/.../GuardService.java:40-55,178-210` `pendingMap` 合并窗口 + `runTaskLater` 去抖。

## 2. MBB-Roleplay

可复用的边界层：

- 线程池：`RoleplayExecutorService.java:22-33` 回合池（`turnThreads` 限 1~16，daemon，线程名 `MoBoxBot-Roleplay-Turn`）、`:35-42` 后台池固定 2、`:44-70` executor 为空或拒绝时退化为当前线程执行、`:72-79` `shutdownNow`。
- 表生命周期：`RoleplayService.java:46-50` 表名常量；`RoleplayReminderService.java:39-57` 建表 + `ensureColumn` 迁移 + 联合索引。
- 定时器：`runTaskLater`（`RoleplayBatchScheduler.java:96-118`）、`runTaskTimer(...,60,3600)`（`RoleplayDailyReviewService.java:40`）。

私有实现，不可直接抄：

- 全部服务以 `RoleplayService` 为门面取 config/persona/storage/submitBackground（`RoleplayReminderService.java:26,30-36`；`RoleplayService.java:1327-1337`），移植任何一个服务都要连带门面。
- 消息逐条 `INSERT` 到 `plugin_mbb_roleplay_messages`（`RoleplayMessageContentService.java:49-62`）；运行态 `conversationStateMap`（`RoleplayService.java:105`）只在内存，字段是角色扮演语义（`RoleplayConversationState.java:8-26`）。
- 记忆层：`RoleplayMemoryUpdateService.java:36-50` 用 `synchronized(memoryUpdatingMap)` 防重入、`pendingMemoryMap` 合并、`submitBackground` 执行；`:211-287` 依赖 `MBB-AI` 的 `PluginService`，超上下文时丢弃旧批次，最后 `saveMemoryState` 写 `STATE_TABLE`。
- 对外接口极窄：`RoleplayPublicService.java:22-42` 只暴露绘图/直播通知与 `status`，未知 action 返回「不支持的动作」。跑团插件不应依赖它，应自带 `PluginService`。

判定：线程池、表迁移、先落库后调度的骨架可抄；任何 `Roleplay*Service` 原样复制都需改造；会话状态、记忆、人设相关实现不可用。

## 3. MBB-Remind

- 现状（`MBB-Remind/src/main/java/org/moboxlab/mbb/remind/`）：`RemindPlugin.java:9,18` 上限 604800 秒；`RemindCommand.java:49-57` 校验参数，`:67-74` `runTaskLater` 到点直接发群/私聊；没有表、没有恢复入口，插件重载或进程重启后提醒消失；`parseDelay`（`:79-94`）与 Poll 同款。
- 可抄实现（`MBB-Roleplay/.../RoleplayReminderService.java`）：
  - 写库时机 `:88-92`：先 `INSERT ... status='pending'`，再 `schedule`（`:92`）注册定时器，然后发确认文本；顺序是「先持久化后调度」。
  - 恢复入口 `restorePending` `:399-412`：`SELECT ... WHERE status='pending' ORDER BY remindTime ASC`，逐条 `schedule`，由 `RoleplayService.java:321-323` 在 onEnable 调用。
  - 调度 `:414-421`：`max(1,(runAtTime-now+999)/1000)` 秒，`runTaskLater` 后转 `submitBackground`，避免在定时线程里做 AI。
  - 触发幂等 `:423-445`：先查 `status='pending'` 且 `remindTime` 与预期一致才发；`retcode==0` 置 `done`，否则 60 秒后重试。
- 判定：Remind 不可用；`RoleplayReminderService` 可抄但需改造（表名与字段、去掉 relation/persona、AI 文本改纯文本、目标支持群与私聊）。

## 4. MBB-Random

- `RandomCommand.java:15` `MAX_RANGE=1000000`；`:40-54` 三种形态（无参 1~100、一参 `1..max`、两参 min/max，解析失败给示例）；`:55-63` `min > max` 校验与 `long range` 差值上限；`:64` `ThreadLocalRandom.nextInt(min, max+1)`；`:25-27` `EVERYONE`，`:30-32` 冷却 2 秒；无配置项（`RandomPlugin.java:10-12` 只注册命令）。
- 边界缺陷：`max == Integer.MAX_VALUE` 时 `max+1` 溢出为负数，`nextInt` 抛 `IllegalArgumentException` 且不在 try 内；`args.length > 3` 时多余参数被静默忽略。
- 判定：解析骨架与范围校验可抄；骰子语义（NdM、优势/劣势、重掷、种子、单次结果审计）全部缺失，不可用作跑团骰子引擎，建议自建 `DiceCommand`。

## 5. ImageUtil 渲图

- API 面：`ImageUtil.renderText(String title, List<String> lines)` 返回 `byte[]`，`ImageUtil.toBase64Uri(byte[])` 转 data URI（`org.moboxlab.moboxbot.API.Util`，实现在主程序仓库，本 worktree 无源码）。
- 用法三处完全同构：`HelpCommand.java:97-103`、`PluginsCommand.java:111-117`、`VersionCommand.java:52-57`——拼标题与行列表 → 渲染 → 返回 null 时逐行发文本。`toBase64Uri` 共 15 处，均在自绘 renderer 之后（AI/Guard/ChatStat/Emotion/Memory/Status）。
- 组织方式：标题单独传参，正文由调用方按行拼（`HelpCommand.java:63-94` 就是 command/usages 逐行组装）。主程序内部如何换行、截断、分页，本仓库无源码，无法确认。
- 渲染线程：`renderText` 同步返回，仓库内无异步渲图 API；耗时场景把「AI + 自绘」放进插件调度任务，如 `ChatStatCommand.java:119-120` `runTask(plugin, () -> sendAiSummary(...))`，`:170-175` 自绘并发送，失败回退文本。
- 复杂排版先例：`RoleplayMemoryImageRenderer.java:24-26`（宽 1080、边距 38、间距 16）、`:40-81`（先算行高与总高再建图，每页 12 条）、`:166-196`（按 54/56 字符换行，向前 12 字符找标点）、`:212-222`（省略号截断）、`:228-236`（`ImageIO` 写 PNG）；`RoleplayMemoryCommandHandler.java:96-101` 自绘失败回退文本。
- 判定：`renderText` 可抄（帮助、列表、结算页）；需要分页、长文本、强调色时自绘更可控；异步渲染无法确认。

## 不确定项

- `ImageUtil` 内部实现（换行、字号自适应、最大高度、是否分页）在主程序仓库，本 worktree 无源码，无法确认。
- 插件未实际运行，定时器在 reload 时是否被取消、AI 服务缺失时的降级路径仅由代码推断。
- 未读取 issue #2 正文，调研范围以任务描述的五项为准。
