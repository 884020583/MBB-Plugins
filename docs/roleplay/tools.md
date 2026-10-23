# MBB-Roleplay 工具调用、工作区与交流节拍

## 原生工具循环

`MBB-Roleplay` 通过 `MBB-AI` 的 OpenAI 兼容 `tools/tool_calls` 执行有限工具循环：

```text
模型选择工具
→ Roleplay 校验工具名、参数、次数和路径
→ 执行工具
→ 把 role=tool 结果返回模型
→ 模型继续调用、输出最终回复或调用 end_turn
```

单轮最多执行 `toolMaxSteps` 次模型循环和 `toolMaxCalls` 次工具调用。工具失败会作为结构化结果返回模型，不会伪装成成功。
单轮总耗时和单个工具耗时分别受 `toolLoopMaxMillis`、`toolCallMaxMillis` 限制；超时结果会作为失败事实返回，不会让回合线程无限阻塞。
`/role tool test` 会离线检查所有启用工具的 schema、必填与未知参数、工作区路径隔离和网页白名单边界。

回复层会同时收到完整的 `tools` schema 和一份简短能力目录，因此模型可以按当前问题继续请求检索，
而不是只在路由层决定一次技能。工具结果会立即进入当前 Agent Loop，并在后续轮次保留。

内置工具：

| 工具 | 用途 |
|---|---|
| `self_state` | 查看回复冷却、工作区、记忆和长度约束 |
| `history_search` | 搜索当前群消息流水 |
| `memory_search` | 检索长期记忆和永久记忆 |
| `kb_search` | 检索已启用的知识库，返回条目、小节与正文 |
| `web_fetch` | 读取白名单网站中的公开网页并净化正文 |
| `profile_get` | 查询群员个人印象 |
| `workspace_list` / `workspace_read` / `workspace_search` | 查询角色私有工作区 |
| `workspace_write` | 创建、覆盖或追加 UTF-8 文本文件 |
| `workspace_delete` | 删除文件或目录 |
| `message_send` | 工具处理期间追加发送一条短消息 |
| `end_turn` | 本轮保持沉默并立即结束 |

`message_send` 只负责中途补一句，最终回复仍走普通回复通道。工具循环会提醒模型不要用它重复最终正文。

## 工具结果上下文

每次工具执行都会把工具名、参数、结构化结果和摘要写入当前群的工具结果上下文，默认保留 72 小时。
下一轮会按当前用户、当前消息相关性和时间衰减挑选最近结果注入提示词，受以下配置限制：

```yaml
toolMemoryInjectItems: 8
toolMemoryInjectMaxChars: 6000
toolMemoryKeepHours: 72
toolMemoryMaxRows: 500
```

这里只保存工具可见结果，不保存模型 `reasoning` 或思维链。工具期间的新消息、工具失败和超时也按事实记录，
下一轮不会因为进程内临时状态消失而完全忘记刚才查过什么。

## 网页白名单

`web_fetch` 只接受 `http/https`，默认拒绝自动跨域重定向；手动跟随重定向时每次都会重新校验域名。
读取结果按 `webTimeoutSecond`、`webMaxBytes`、`webMaxChars` 限制，并移除脚本、样式和 HTML 标签。

```yaml
webEnable: true
webWhitelist: "wikipedia.org,baike.baidu.com,bilibili.com,gamekee.com,bluearchive.wiki,kivo.wiki,moegirl.org.cn,github.com,raw.githubusercontent.com,deepwiki.com,reddit.com"
```

## 上下文预算

`contextHardLimitEnable` 默认 `false`，此时本地不再按 `promptTotalChars` 截断动态资料，
避免角色在搜索和多轮工具任务中被旧的小预算卡住。只有显式打开硬预算时才会执行总字符限制：

```yaml
contextHardLimitEnable: false
promptTotalChars: 120000
```

模型自身的上下文窗口仍由服务端决定；本项目关闭的是本地那层过早截断。

## 角色私有工作区

工具只能访问：

```text
./MoBoxBot/plugins/MBB-Roleplay/data/
```

不允许绝对路径、`..`、符号链接逃逸和外部盘符。读取、写入、单次列表数量分别受
`workspaceMaxReadChars`、`workspaceMaxWriteChars`、`workspaceMaxFilesPerList` 限制。
工作区总大小由 `workspaceMaxTotalBytes` 限制，默认 64MB。

## 工具期间的新消息

工具调用期间到达的新群消息会进入当前回合的 live inbox，并在工具结果之后作为新的
user 消息加入上下文。它们不会再进入下一批造成重复回复。达到工具轮数上限时，
运行时会再尝试一次不带工具的收尾请求，让已经进入上下文的新消息和工具结果有机会被回复。

`toolLiveMessageMax` 控制单轮最多并入多少条。命令、黑名单用户、纯图片/表情和安全拦截消息不会并入。

## 交流节拍

角色的 `text` 可以用换行表达 1 到 `replyBeatMaxSegments` 个连续节拍，每行作为独立消息发送。
运行时根据下一段长度、群活跃度和时段计算延迟：

```yaml
replyBeatMaxSegments: 3
replyBeatBaseDelayMs: 450
replyBeatPerCharMs: 35
replyBeatMaxDelayMs: 2200
```

节拍由角色决定是否拆分，发送间隔由运行时控制。一次能说完时不强制拆句。
