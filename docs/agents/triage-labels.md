# Triage Labels

技能用五个标准分诊角色说话。这张表把角色映射到本仓库 issue tracker 里实际使用的标签字符串。

| 技能里的标签 | 本仓库的标签 | 含义 |
| --- | --- | --- |
| `needs-triage` | `needs-triage` | 需要维护者评估（尚未诊断） |
| `needs-info` | `needs-info` | 在等报告者补充信息 |
| `ready-for-agent` | `ready-for-agent` | 已完全写清，可交给 AFK 代理 |
| `ready-for-human` | `ready-for-human` | 需要人来实现 |
| `wontfix` | `wontfix` | 不做处理 |

技能提到某个角色（例如「打上 AFK-ready 分诊标签」）时，使用上表右列的标签名。

本仓库沿用默认五角色，没有重命名。仓库里已有的 `bug`、`documentation`、`enhancement`、`good first issue`、`help wanted`、`invalid`、`question`、`accessibility` 等通用标签照常使用，不需要转成分诊角色。

## Wayfinder 标签

`/wayfinder` 另有一套标签，与分诊角色并行使用：

| 标签 | 用途 |
| --- | --- |
| `wayfinder:map` | 地图票，承载 Notes / Decisions so far / Fog |
| `wayfinder:research` | 调研类子票 |
| `wayfinder:prototype` | 原型类子票 |
| `wayfinder:grilling` | 决策 / 对齐讨论类子票 |
| `wayfinder:task` | 执行类子票 |
