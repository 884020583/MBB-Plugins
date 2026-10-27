# MBB-Eval 独立成插件，评测与线上回复在配置、数据、生命周期上完全分开

状态：accepted（2026-10-26）

评测要回答的是「同一份人设、同一份题库，今天的回复比上周好还是差」：固定题库、固定判分规约、固定人设与调用参数，逐题生成、逐题判分、落表、出报表，跑完还能重设基线。这是离线批处理，而线上回复是群聊里的单次同步调用，两者的节奏、失败后果与预算口径都不同。仓库的前置约束又把边界钉死：插件只依赖 `org.moboxlab.moboxbot.API`，插件之间没有类加载契约，`RoleplayService` 已是历史超大类，AGENTS.md 明确要求新功能优先新增独立服务、不把新的状态、定时任务、数据库表和 AI 调用继续塞进主服务。因此 MBB-Eval 独立成插件：自己的 `plugin.json` 与版本、自己的 `/eval` 命令与权限、自己的配置项（`evalProfile`、`evalJudgeProfile`、`evalCallBudget`、`evalKeepRuns` 等）、自己的四张表（`plugin_mbb_eval_run`、`_case_result`、`_call`、`_baseline`）、自己的报表目录（`./MoBoxBot/plugins/MBB-Eval/reports/`）。它与 MBB-Roleplay 之间只有一条单向依赖：MBB-Eval 调 Roleplay 的公开动作取数（见 ADR 0007），Roleplay 不知道评测存在。题库与判分规约作为被测素材留在 `./MoBoxBot/plugins/MBB-Roleplay/evals/`，MBB-Eval 用 `getDataFolder().getParentFile()` 同父目录推导读取，不新增 roleplayDataDir；找不到就拒绝整轮，绝不静默用空题库跑出「全 0 分」。

## 考虑过的选项

- **放在 MBB-Roleplay 内新增评测服务**：省掉跨插件取数，但评测的状态、表、清理、预算与群聊生产路径落在同一个插件生命周期里，一次评测的失败、超时和表增长会直接影响线上回复的配置与日志；旧 `/role eval` 已定墓碑（#45），同一插件内留两个评测入口只会让用户继续走旧路径；也违反 AGENTS.md 对 `RoleplayService` 超大类「新功能优先新增独立服务、不把新表与新 AI 调用继续塞进主服务」的约束。
- **放进主程序或 MBB-AI**：题库、判分规约、命令、报表与保留策略是产品能力，不是网关能力；MBB-AI 只负责调用、缓存与 usage，放进主程序还会把插件版本节奏与主程序版本绑死。
- **仓库外独立脚本或工具**：拿不到 StorageService、Scheduler、MessageService 与权限矩阵，等于自造一套宿主；也不能在群里 `/eval run`，评测从可复现的群内动作退化成维护者个人脚本。
- **独立插件但直接调 MBB-AI 或回放 trace 取数**：这不是插件划分问题，而是取数口径问题，见 ADR 0007。

## 后果

- 依赖方向固定为 MBB-Eval → MBB-Roleplay；反向转发被禁止，`/role eval` 只能墓碑提示（#45）。
- MBB-Eval 预检时找不到 Roleplay 的 `capabilities` 动作、或 API 版本不符，必须拒绝跑并给出明确错误（`E_ROLEPLAY_DATA_NOT_FOUND`、版本不匹配），不允许跑出空题库的假分数。
- 评测插件可单独禁用、单独重载，不影响群聊回复；反之 Roleplay 未启用时评测不可用。
- 四张表、报表目录与 `evalKeepRuns=30` 的清理只针对自己的表，不写 `plugin_mbb_roleplay_trace`、不读线上 usage 表。
- `capabilities` 与 `eval-generate` 的入参出参是跨插件公开契约，须由 `docs/roleplay/integrations.md` 记录，改动视为破坏性变更。
