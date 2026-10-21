# AGENTS.md

## 前置约束

- 插件只依赖 `org.moboxlab.moboxbot.API`。
- 插件不要直接访问主程序内部类、`BasicInfo`、数据库或 OneBot 实现。
- 技术栈：Java 8 + fastjson + MoBoxBot API。
- 中文注释、中文日志，不用 emoji。

## 仓库边界

本仓库是独立插件仓库，路径：

```text
D:\CodeX\Projects\MBB-Plugins
```

主程序仓库：

```text
D:\CodeX\Projects\MoBoxBot
```

插件仓库改动不影响主程序时，不更新主程序版本号。

## 版本与提交

- 插件版本格式：`V大版本.小版本.小更新.小修正.四位时间戳`。
- `小更新` 位没有 `99` 上限，可以继续写 `100`、`101`；只有真实的大功能更新或破坏性变更才进位小版本。
- 插件仓库发布版本使用根目录 `version.txt`。
- 纯文档调整：`docs: 中文摘要`。
- 插件功能更新维护 `update.md`。
- 不提交 `out/`、日志、数据库、真实 Token 和个人信息。

## 构建

```powershell
.\build-all.ps1 -Bot "D:\CodeX\Projects\MoBoxBot\out\MoBoxBot.jar"
```

全部构建成功后，插件 JAR 会汇总到仓库根目录 `out/`。

## 自检

1. 插件 JAR 根目录有 `plugin.json`。
2. 主类继承 `API.Plugin`。
3. 插件 JAR 不打包公共依赖。
4. 权限、冷却、说明完整。
5. 插件配置和 README 同步。

## 类体积与拆分

- 单类超过 800 行时开始关注；超过 1500 行时，新功能应先评估拆出服务或工具类，不允许继续把逻辑堆进该类。
- 单类超过 2500 行时，默认停止扩展，先做一轮纯搬移拆分；拆分不得改变行为，每拆一步都要重新构建。
- `RoleplayService` 属于历史超大类，当前按多轮拆分处理：已拆出回复反馈、表达候选、注意力漂移、每日复盘；后续继续拆回合主流程、提示词装配、记忆管理、上下文与话题疲劳、消息执行和文本工具。
- 新功能优先新增独立 `RoleplayXxxService`，由 `RoleplayService` 只做协调和门面转发；不要把新的状态、定时任务、数据库表和 AI 调用继续塞进主服务。
- 拆分顺序：先拆边界清晰、依赖少、行为不变的服务；再拆提示词装配和回合主流程。每轮都同步 `update.md` 与相关文档。
## PR 处理流程

1. 收到 PR 后先获取 PR head，定位变更文件和基线，不得直接合并。
2. 按代码审查方式检查 Java 8 风格、API 边界、权限、并发、数据库、异常隔离和长跑稳定性。
3. 至少完成一次真实插件构建；同时检查 `plugin.json`、配置迁移、README、`docs/` 和 `update.md`。
4. 发现问题时，基于 PR head 建本地修复分支，保留原作者提交；修复后重新构建和自检。
5. 只有审查无阻断问题，或阻断问题已修复并验证后，才能合并到 `master`。
6. 合并后运行全量构建、密钥扫描，推送远端并确认远程分支状态。

## Agent skills

### Issue tracker

GitHub Issues（本 fork `884020583/MBB-Plugins`，用 `gh` CLI 操作）。详见 `docs/agents/issue-tracker.md`。

### Triage labels

沿用默认五角色 `needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`，另有 `wayfinder:*` 标签。详见 `docs/agents/triage-labels.md`。

### Domain docs

single-context（根目录一份 `GLOSSARY.md`，架构决定放 `docs/adr/`）。详见 `docs/agents/domain.md`。
