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

内置只读工具：

| 工具 | 用途 |
|---|---|
| `self_state` | 查看回复冷却、工作区、记忆和长度约束 |
| `history_search` | 搜索当前群消息流水 |
| `memory_search` | 检索长期记忆和永久记忆 |
| `profile_get` | 查询群员个人印象 |
| `workspace_list` / `workspace_read` / `workspace_search` | 查询角色私有工作区 |
| `workspace_write` | 创建、覆盖或追加 UTF-8 文本文件 |
| `workspace_delete` | 删除文件或目录 |
| `end_turn` | 本轮保持沉默并立即结束 |

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
user 消息加入上下文。它们不会再进入下一批造成重复回复。

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
