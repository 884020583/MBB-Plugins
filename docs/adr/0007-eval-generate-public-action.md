# 评测取数走 Roleplay 的 eval-generate 公开动作，不直连 MBB-AI、不回放 trace、不复用回复执行链

状态：accepted（2026-10-26）

评测要测的是「MBB-Roleplay 在当前人设、记忆、关系与提示词装配下会怎么回」，所以输入必须由 Roleplay 自己装配，而不是评测侧重拼一份相似提示词。`RoleplayPublicService.call` 因此新增两个公开动作：`capabilities` 声明是否支持评测取数及其版本；`eval-generate` 走生成层评测分支，按题库用例的场景（历史、@对象、关系）装配与线上一致的提示词，但 `toolEnable=false`、不写任何状态、不写 trace、不投递群消息、同步执行且不内部重试。`sessionId` 由调用方给（含 run、case、sample、attempt），出参自带 `status`、`finishReason`、`content`、`usage`、`elapsedMs`、`cached`。评测侧据此逐题调用并落 `case_result` 与 `_call`；性能口径只认 `eval-generate` 返回的 `elapsedMs`（仅 `status=true` 且 `cached=false`），不自己掐表。

## 考虑过的选项

- **评测侧直接调 MBB-AI**：最省事，但评测必须复刻角色档案、记忆检索、关系与提示词装配；复刻出来测的是一份近似实现，Roleplay 改提示词后评测不会跟着变，反而给出「没退化」的假信号。旧 `/role eval` 正是这条路：单轮、固定 sessionId、关键词二值、status 恒真、不落库（#34），已定废弃。
- **回放 `plugin_mbb_roleplay_trace`**：trace 只存 route/reply 段，缺 model、profile、runId、caseId，清理只在 init 触发；既无法还原当时的完整提示词与逐次 usage，也只能测一份冻结的历史产物，换题库或换判分规约后无法重跑。
- **复用完整 replyExecutor**：会真实写状态、写 trace、注入记忆、发群消息、走工具，评测变成有副作用的生产回合，不可重复、会污染群聊与统计，100 次预算也约束不住。
- **文件或命令通道跨进程取数**：需要额外协议、鉴权与超时，拿不到同步的 usage 与耗时，错误码也无法区分生成失败与传输失败。
- **评测侧传固定 sessionId 或让接口内部重试**：缓存键是 profile + sessionId + 参数 + messages，固定值会产生假零方差（#39）；内部重试则让调用计数漏算、预算双花。
- **入参覆盖 persona、注入记忆种子、写 trace**：都会改变被测对象的真实状态，测的就不再是线上那一份，故全部否决（#40 否决表）。

## 后果

- `eval-generate` 是 MBB-Roleplay 的公开契约：入参至少含用例 id、场景、生成参数覆盖（temperature、maxTokens、reasoningEffort、retryCount、timeoutSeconds）与 `sessionId`；出参字段缺失视为版本不兼容，评测拒绝跑。
- 评测侧必须把 `cached=true` 的样本作废并换 sessionId 重采，否则重复采样没有方差。
- 生成与判分共用一个 `evalCallBudget`（默认 100 次/轮）：40 次生成 + 36 次判分 + 3 次校准，余量留给重试；重试一律由评测侧计数，接口内部不重试。
- Roleplay 未启用、未声明 `eval-generate`、API 版本不符时，评测在预检阶段失败，不进 runner。
