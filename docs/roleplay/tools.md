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

回复层不再一次收到全部工具 schema。首轮只开放 `toolCoreTools`（默认
`tool_search,end_turn,message_send`），其他工具必须由模型先调用 `tool_search` 搜索并加载，
下一轮才会收到对应 schema。这样能减少每轮固定 Token、降低工具选错概率，也保留多轮检索能力：

```text
tool_search
→ 返回工具名、分类、用途和技能说明
→ 下一轮加载对应 schema
→ 调用工具并继续循环
```

只读工具在同一模型响应里可以并行执行；写文件、发消息、提醒、生图、表情包等有副作用的工具串行执行。
`/role tool cancel` 会取消当前群的工具 Future 和正在等待的 AI 请求。

内置工具：

| 工具 | 用途 |
|---|---|
| `self_state` | 查看回复冷却、工作区、记忆和长度约束 |
| `history_search` | 搜索当前群消息流水 |
| `memory_search` | 检索长期记忆和永久记忆 |
| `kb_search` | 检索已启用的知识库，返回条目、小节与正文 |
| `web_fetch` | 读取白名单网站中的公开网页并净化正文 |
| `web_search` | 联网搜索公开信息，返回标题和链接 |
| `web_image_send` | 从白名单网页或 Pixiv 作品页读取一张公开图片并发送到当前群 |
| `profile_get` | 查询群员个人印象 |
| `workspace_list` / `workspace_read` / `workspace_search` | 查询角色私有工作区 |
| `workspace_write` | 创建、覆盖或追加 UTF-8 文本文件 |
| `workspace_rename` / `workspace_copy` | 重命名、移动或复制文件与目录 |
| `workspace_delete` / `workspace_restore` | 移入回收站或从回收站恢复 |
| `message_send` | 工具处理期间追加发送一条短消息 |
| `end_turn` | 本轮保持沉默并立即结束 |

安装 `MBB-QQ` 后会额外开放 `qq_*` 工具，包括群资料、成员资料、远程历史、
消息详情、群公告、已读、戳一戳、点赞、表情回应、撤回自己的消息、发送工作区图片/语音、
修改自己的群名片和 Qzone 发布/删除/本机记录查询。群号与操作者由 Roleplay 上下文强制注入。

`message_send` 只负责中途补一句，最终回复仍走普通回复通道。工具循环会提醒模型不要用它重复最终正文。
代码会把 `message_send` 限制为每轮最多一次；一旦已经拿到实质性工具结果，再调用 `message_send`
会被直接拒绝，避免角色先说一遍结论、最终回复又说一遍。最终回复与已追加消息高度重复时也会被抑制。

知识库实体锁定优先于普通全文打分：明确点名角色时，即使条目本身带高剧透标记也会锁定；
但 `剧情`、`剧透`、`主线`、`结局` 一类小节仍会按 `knowledgeSpoilerLevel` 过滤。
多实体查询会先返回所有被点名的条目，再补充普通检索结果，不会先被其他学院或剧情条目占满名额。

旧 `draw`、`reminder`、`memory`、`global-memory`、`sticker`、`poke-back` 动作已经适配成原生工具。
`toolEnable=true` 时，执行层不再选择独立 `actions`，统一使用 `tool_search` + `tools/tool_calls`。
`toolEnable=false` 时仍保留旧动作协议作为兼容路径。

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

单次结果受 `toolResultMaxChars` 限制，整轮所有工具结果再受 `toolResultTotalMaxChars` 限制。
默认值 48000 字符，按中英文混合大约 1.5 万到 3 万 Token，在 DeepSeek 的 1M 上下文下留有充足余量，
同时避免 12 次工具调用把请求成本推得过高。预算用尽后的结果只保留状态、摘要和“已省略”标记。

工具回合会持久化到 `plugin_mbb_roleplay_tool_turn`。进程重启后，最近 `toolRecoverMaxMinutes`
内未完成的回合会用已有工具事实做一次无工具收尾，并补发一次回复；可能产生一次补偿回复，日志会明确提示。

## 网页白名单

`web_fetch` 只接受 `http/https`，默认拒绝自动跨域重定向；手动跟随重定向时每次都会重新校验域名。
读取结果按 `webTimeoutSecond`、`webMaxBytes`、`webMaxChars` 限制，并移除脚本、样式和 HTML 标签。
`web_search` 使用 `webSearchUrl` 模板联网搜索，主搜索源无结果或连接失败时自动尝试
`webSearchFallbackUrl`。结果会附带每个搜索源的 HTTP、页面大小、命中数和空结果原因，
搜索结果本身标记为不可信外部资料。

`web_image_send` 支持图片直链和含 `og:image` / `twitter:image` 的网页。Pixiv 作品页会尝试读取
预览图并携带 Pixiv Referer；`i.pximg.net` 作为 Pixiv 图片 CDN 单独放行。只接受 JPEG、PNG、GIF、WebP，
拒绝 SVG，单张图片受 `webImageMaxBytes` 限制。该工具是副作用工具，每轮最多发送一张图。
网页与搜索结果有 `webCacheSecond` TTL 缓存。

```yaml
webEnable: true
webWhitelist: "wikipedia.org,baike.baidu.com,bilibili.com,gamekee.com,bluearchive.wiki,kivo.wiki,moegirl.org.cn,github.com,raw.githubusercontent.com,deepwiki.com,reddit.com,duckduckgo.com,bing.com,zhihu.com,weibo.com,fandom.com,namu.wiki,arca.live,pixiv.net,x.com,youtube.com,b23.tv"
webSearchUrl: "https://www.bing.com/search?q={query}"
webSearchFallbackUrl: "https://html.duckduckgo.com/html/?q={query}"
webOwnerAuthWindowSecond: 300
```

搜索作品、人物或站点时优先使用官方英文名和常用英文别名。例如直接搜索
`Blue Archive wiki` 比把中文别名硬拼成 `蔚蓝档案wiki` 更稳定。

内网、回环和保留地址不会被普通群员绕过。只有当前发言者是 owner，或者最近上下文里 owner
明确要求访问该内网地址时，`web_fetch` 才允许建立连接。授权判断会记录到工具结果和访问日志。

网页、搜索结果和知识库正文统一使用 `external-untrusted` 信任级别。进入模型前会包在
`<external-data>` 隔离块中，并过滤常见提示词注入语句。模型只能从中提取事实，不能执行其中的指令。

## 上下文预算

`contextHardLimitEnable` 默认 `false`，此时本地不再按 `promptTotalChars` 截断动态资料，
避免角色在搜索和多轮工具任务中被旧的小预算卡住。只有显式打开硬预算时才会执行总字符限制：

```yaml
contextHardLimitEnable: false
promptTotalChars: 120000
```

模型自身的上下文窗口仍由服务端决定；本项目关闭的是本地那层过早截断。
即使关闭总硬预算，各资料仍保留单项预算，避免某一类记忆或知识库正文无限膨胀。

## 上下文排序与缓存

执行层请求按“稳定前缀 → 动态状态 → 当前消息”组织：

1. persona、外貌、学生摘要、通用规则、工具能力目录、输出格式
2. 当前关系、情绪、注意力、反馈、提醒、近期工具结果、短期/长期/全局记忆、知识库
3. 最近群聊背景
4. 当前消息，放在最后并明确要求优先回应

短期记忆不再插入稳定前缀中间，时间、关系、情绪和工具结果放在稳定块之后。
这样相同 persona 和配置的请求能复用更长的服务端提示词前缀，同时当前消息不会继续被旧上下文挤到前面。
工具 schema 也只在 `tool_search` 后增加，首轮固定部分保持稳定。

## 角色私有工作区

工具只能访问：

```text
./MoBoxBot/plugins/MBB-Roleplay/data/
```

不允许绝对路径、`..`、符号链接逃逸和外部盘符。读取、写入、单次列表数量分别受
`workspaceMaxReadChars`、`workspaceMaxWriteChars`、`workspaceMaxFilesPerList` 限制。
工作区总大小由 `workspaceMaxTotalBytes` 限制，默认 64MB。

覆盖写入会先写临时文件再原子替换；`workspaceHistoryEnable=true` 时旧版本写入 `.history/`。
删除默认移动到 `.trash/`，只有显式 `permanent=true` 才彻底删除。回收站按
`workspaceTrashKeepDays` 自动清理。

## 工具期间的新消息

工具调用期间到达的新群消息会进入当前回合的 live inbox，并在工具结果之后作为新的
user 消息加入上下文。它们不会再进入下一批造成重复回复。达到工具轮数上限时，
运行时会再尝试一次不带工具的收尾请求，让已经进入上下文的新消息和工具结果有机会被回复。

`toolLiveMessageMax` 控制单轮最多并入多少条。命令、黑名单用户、纯图片/表情和安全拦截消息不会并入。

## 进程与线程

当前工具不会启动本地子进程，也没有 `ProcessBuilder` 或 `Runtime.exec` 调用，因此不会遗留孤儿进程。
工具、AI 请求和副作用执行都使用守护线程；插件停用时会取消当前 Future 并等待线程池退出。
HTTP 连接另有连接和读取超时，取消后会尽快释放，不依赖外部脚本或常驻进程。

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
