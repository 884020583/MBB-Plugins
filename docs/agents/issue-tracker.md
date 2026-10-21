# Issue tracker: GitHub

本仓库的 issue 与规格文档都放在 GitHub Issues 里，统一用 `gh` CLI 操作。

## 仓库约定

- issue 写在 fork 仓库 `884020583/MBB-Plugins`（本仓库，当前账号有写权限）。
- 上游 `MossCG/MBB-Plugins` 是维护者的仓库：不要往上创建 issue；向上游贡献走 Pull Request。
- GitHub 的 issue 与 PR 共用一个编号空间：`#42` 可能是 issue 也可能是 PR。先用 `gh pr view 42` 查，查不到再 `gh issue view 42`。

## 常用操作

- **建 issue**：`gh issue create --title "..." --body "..."`；多行正文用 heredoc 或 `--body-file`。
- **读 issue**：`gh issue view <number> --comments`；需要结构化数据时用 `--json` 配 `jq` 过滤评论与 labels。
- **列 issue**：`gh issue list --state open --json number,title,body,labels,comments --jq '[...]'`，配合 `--label`、`--state` 过滤。
- **评论**：`gh issue comment <number> --body "..."`。
- **打 / 摘标签**：`gh issue edit <number> --add-label "..."` / `--remove-label "..."`。
- **关闭**：`gh issue close <number> --comment "..."`。

在克隆目录里直接执行即可，`gh` 会自动推断仓库。

## PR 是否作为分诊入口

**否。** 外部 PR 不进 triage 队列；代码贡献走正常的 PR 评审流程（见根目录 `AGENTS.md` 的「PR 处理流程」）。

如果以后要把 PR 当需求入口，把这里改成「是」：`/triage` 会读这个开关，并用对应的 `gh pr` 命令处理 PR。

## 技能说「发布到 issue tracker」时

创建一条 GitHub issue。

## 技能说「取回相关票」时

执行 `gh issue view <number> --comments`。

## Wayfinding 操作（`/wayfinder` 使用）

地图是一条 issue，子票是它的子 issue。

- **地图**：一条打了 `wayfinder:map` 标签的 issue，正文承载 Notes / Decisions so far / Fog。`gh issue create --label wayfinder:map`。
- **子票**：地图的 GitHub sub-issue。若仓库未启用 sub-issue，就把子票加进地图正文的任务清单，并在子票正文顶部写 `Part of #<地图号>`。标签用 `wayfinder:<type>`（`research` / `prototype` / `grilling` / `task`）。认领后指派给执行者。
- **阻塞**：优先用 GitHub 原生 issue 依赖：`gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`，其中 `<blocker-db-id>` 是阻塞票的数字 database id（`gh api repos/<owner>/<repo>/issues/<n> --jq .id`，不是 `#number` 或 `node_id`）。是否被阻塞看 `issue_dependencies_summary.blocked_by`（只算未关闭的阻塞票）。依赖不可用时，退回「子票正文顶部写 `Blocked by: #<n>, #<n>`」。
- **frontier**：列地图的 open 子票，剔除有未关闭阻塞的、或已有 assignee 的，取地图顺序里的第一条。
- **认领**：`gh issue edit <n> --add-assignee @me`，这是本次推进的第一次写操作。
- **解决**：`gh issue comment <n> --body "<答案>"` → `gh issue close <n>` → 把结论指针（要点 + 链接）追加到地图的 Decisions so far。
