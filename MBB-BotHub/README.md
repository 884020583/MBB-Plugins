# MBB-BotHub

MoBoxBot 多机器人协作中枢。同一个群里部署多个角色机器人时，用一条本地 TCP 连接把它们串起来，
解决两个具体问题：

1. **同一张图重复识图**：两个 bot 各调一次 `MBB-Vision`，浪费一份 AI 额度。hub 让其中一个认领识图，
   另一个复用结果。
2. **抢答同一条消息**：两个 bot 都判断该回复，于是同一句话被回两遍。hub 用回合权租约只放行一个。

hub 是可选插件。没装、没连上、或者被 `hubEnable` 关掉时，Roleplay 完全退回原来的单机行为。

## 首次启动

插件第一次加载会探测本机 `18765` 端口：

| 探测结果 | 生成的配置 | 含义 |
|---|---|---|
| 端口空闲 | `mode: "server"` | 当前实例当 hub 服务端，其他 bot 连过来 |
| 端口已被占用 | `mode: "client"` | 本机已经有 hubserver，当前实例当客户端 |

也就是说，**第一个启动的实例自动成为服务端**，后面启动的实例自动成为客户端，不用手工配置。
如果启动顺序不确定，把 `mode` 设为 `"auto"`，每次启动都重新探测。

## 配置

```yaml
#模式：server / client / auto。auto 会在每次启动时重新探测本机端口。
mode: "auto"
#server 监听地址；client 连接地址
host: "127.0.0.1"
port: 18765
#共享鉴权 token，同一个 hub 下所有 bot 必须一致
token: "change-me"
#当前机器人身份，同一个 hub 内必须唯一
botID: ""
botQQ: 0
role: ""
#只参与协作的群号，英文逗号分隔；留空表示全部群
groups: ""
#心跳间隔
heartbeatSeconds: 10
#单次请求超时毫秒
requestTimeoutMs: 3000
#client 断线重连间隔
reconnectSeconds: 5
#服务端判定 peer 掉线的空闲秒数
leaseSeconds: 30
#是否共享识图结果
visionShareEnable: true
#识图进行中的认领租约秒数
visionLeaseSeconds: 30
#等待其他 bot 识图结果毫秒
visionWaitMs: 15000
#共享识图缓存保留小时
visionCacheKeepHours: 72
#共享识图缓存最大条目数
visionCacheMaxEntries: 500
#是否共享回合权
turnShareEnable: true
#回合权租约秒数
turnLeaseSeconds: 60
#打字状态广播的有效秒数
typingTtlSeconds: 30
#双方都被点名时，第二个回复的错峰延迟毫秒
multiReplyStaggerMs: 1500
#跨进程任务去重租约秒数（提醒这类任务只允许创建一次）
taskLeaseSeconds: 3600
#机器人互聊熔断：同群连续 bot-to-bot 轮数上限
botChainLimit: 4
#熔断统计窗口秒数
botChainWindowSeconds: 180
#触发熔断后的冷却秒数
botChainCooldownSeconds: 120
#上报的能力列表，留空表示全部支持
caps: "vision,turn,task,chat_guard"
#client 连不上时是否自动选举新的 hub 服务端
autoFailover: true
#连续连接失败多少次后尝试自己当 server
failoverAfterAttempts: 3
```

### 身份自动补全

`botQQ` 和 `role` 都可以留空，插件会在启动时自己补：

| 字段 | 来源 | 取不到时 |
|---|---|---|
| `botQQ` | OneBot 的 `get_login_info` | 每 5 秒重试，最多 12 次；仍失败就用手工值 |
| `role` | `MBB-Roleplay` 的 `identity` 动作（当前 persona 名） | 保持空 |
| `botID` | 补到 QQ 后取 QQ 号；否则用数据目录路径生成稳定实例 ID | - |

补全成功会写回 `config.yml` 并就地重连（client）或重新登记本地 peer（server），不用重启。
不想等自动补全时可以用 `/hub identity` 手工触发一次。

同一个 hub 内 `botID` 必须唯一，否则后连上的会顶掉先连上的连接。所以如果两个实例都拿不到 QQ，
它们会各自得到一个 `bot-xxxxxxxx` 形式的稳定 ID，而不是都叫 `bot`。

## 命令

| 命令 | 说明 |
|---|---|
| `/hub` 或 `/hub status` | 查看当前模式、地址、身份、节点数与开关 |
| `/hub peers` | 查看已连接的协作节点 |
| `/hub mode server\|client\|auto` | 切换模式并重启协作链路 |
| `/hub reload` | 重载配置 |
| `/hub reconnect` | client 模式下强制重连 |
| `/hub identity` | 立即重新探测本机 QQ 与角色名 |
| `/hub stats` | 查看协作统计（省下的识图、回合权、去重、熔断、广播） |
| `/hub task clear` | 清空跨进程任务去重记录 |
| `/hub token <共享token>` | 修改共享 token |
| `/hub vision clear` | 清空共享识图缓存与进行中的认领 |

权限：`BOT_ADMIN`。

## 协议

传输层是纯 TCP，每行一个 UTF-8 JSON（NDJSON）。消息类型：

```text
client -> server：hello / ping / request / publish / bye
server -> client：hello_ok / pong / response / event / error
```

服务端支持的动作：

| 动作 | 用途 |
|---|---|
| `status` | 服务端状态、peer 列表、缓存计数 |
| `peers` / `group_peers` | 节点列表 / 某个群的节点 |
| `vision_lookup` | 按 key 查共享识图结果 |
| `vision_claim` | 认领一次识图，同一 key 只有一个 bot 成功 |
| `vision_publish` | 广播并缓存识图结果 |
| `vision_release` | 释放认领租约 |
| `vision_backfill` | 批量灌入本地识图缓存，按别名建索引 |
| `vision_clear` | 清空共享识图缓存 |
| `turn_claim` / `turn_release` / `turn_status` | 回合权租约 |
| `task_claim` / `task_release` / `task_clear` | 跨进程任务去重租约（提醒等只建一次） |
| `chat_guard` / `chat_guard_reset` | 机器人互聊熔断计数与清零 |
| `typing` / `typing_status` | 打字状态广播与查询（按群过滤、带 TTL） |
| `stats` | 运行统计计数器 |

`publish` 用来让 client 主动广播事件，服务端会转发给其他 peer。

## 与其他插件协作

Roleplay 通过服务名 `MBB-BotHub` 调用本插件，不直接依赖实现类：

```java
PluginService hub = getServer().getPluginManager().getService("MBB-BotHub");
```

Roleplay 侧的开关（默认全开，关掉任意一项即退回单机）：

```yaml
hubEnable: true
hubVisionShareEnable: true
hubVisionWaitMs: 15000
hubTurnShareEnable: true
```

识图共享的 key 是 `群号 + 消息ID + 片段序号`，两个 bot 收到同一条消息时会算出同一把 key，
不依赖图片 URL 或文件名是否一致。

## 协作能力

### 能力协商

client 在 `hello` 里上报自己支持的能力（`caps`），服务端记下来并在广播时按能力过滤，
避免把 `vision_result` 这类事件推给不支持的节点。留空表示「全支持」，兼容老版本 client。

可选能力：`vision`、`turn`、`task`、`chat_guard`。

### 跨进程任务去重

提醒这类「同一条消息只应该产生一个任务」的场景走 `task_claim`：谁先认领谁负责创建，
其他 bot 认领失败就直接跳过，不再重复发确认。租约默认 3600 秒，`/hub task clear` 可以清空。

生图 CD 仍然每个进程独立，不参与跨进程去重。

### 机器人互聊熔断

`chat_guard` 按群累计连续 bot-to-bot 轮数，超过 `botChainLimit`（默认 4）就进入冷却，
期间双方都不再互相接话；真人发言会把计数和冷却一起清零。这样能压住两个角色旁若无人刷屏的情况。

### 回合权与打字状态

两个 bot 同时收到同一条消息时，不再用「先到先得」，而是按优先级分仲裁：

```text
被点名 100 / 被引用 80 / 延续自己参与的线程 60 / 泛群聊插话 40
```

分数高者胜；同分用 `botID` 的稳定哈希比大小，保证两个进程各自独立算出的结果一致，
不需要额外一轮通信来「商量」。低分先到会被高分抢占，被抢的一方收到 `turn_preempted` 事件，
生成完成、发送之前再用 `turn_status` 复核一次，发现被抢就放弃发送。

赢了回合权的一方在生成前发 `typing start`、发完发 `typing stop`，服务端按群广播
`typing_started` / `typing_stopped` 并带 TTL（`typingTtlSeconds`），进程崩了也不会一直显示正在输入。

双方都被点名（比如「小桃小绿你们觉得呢」）时走 `multi` 分支：都放行，第二个按
`multiReplyStaggerMs`（默认 1500ms）错峰，避免同时刷两条。

### 主备与选主

client 连续 `failoverAfterAttempts` 次连不上 hubserver 时会尝试自己绑定端口接管成新的服务端；
端口绑定是互斥的，多个 client 同时抢也只有一个能成功，抢失败的继续当 client。
server 绑定失败时同样会退回 client 等别人接管，所以「原 server 后启动」也能自愈。

### 共享识图别名与回填

识图结果除了主键 `群号+消息ID+片段序号`，还会按 `sha256`、`fileUnique`、`file`、`url` 建别名。
client 握手成功后会调用 `MBB-Vision` 的 `dump` 把本地缓存回填进 hub，
所以即使两个 bot 的主键不同，也能靠别名命中同一份结果。

### 统计

```text
/hub stats
```

输出识图命中/未命中、省下的识图调用次数、缓存回填条数、回合权放行/拦截、
回合抢占、错峰回复、打字广播、任务去重、互聊熔断、广播次数和 peer 上下线。

### 安全默认

默认只监听 `127.0.0.1`。要跨机器部署就把 `host` 改成 `0.0.0.0`（或具体内网/公网地址），
并且必须同时改掉 `token`；插件在绑非本机地址且 token 仍是默认值时会输出告警。

## 构建

```powershell
.\build.ps1
```

产物：`out\MBB-BotHub.jar`。

## 文档

详细设计与部署说明见 [docs/MBB-BotHub.md](../../docs/MBB-BotHub.md)。
