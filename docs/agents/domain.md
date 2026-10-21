# Domain Docs

本仓库的域文档布局已在初始化时与用户确认为 single-context（一个仓库共用一份根目录的 `GLOSSARY.md`，架构决定放 `docs/adr/`）。

工程技能在探索代码库前，按下面的规则阅读域文档。

## 探索前先读这些

- 根目录的 **`GLOSSARY.md`**（single-context：不使用 `GLOSSARY-MAP.md`，也不给各子项目单独建 GLOSSARY）。
- **`docs/adr/`**：读与当前工作区域相关的 ADR。

如果这些文件不存在，**静默继续**：不要提示缺失，也不要主动提议创建。`GLOSSARY.md` 与 `docs/adr/` 由 `/domain-modeling` 技能在真正敲定术语或决策时按需创建——本次初始化不建 `GLOSSARY.md`、`GLOSSARY-MAP.md` 或子项目 GLOSSARY，留到第一次真正写下词条时再建。

## 文件结构

single-context（本仓库）：

```text
C:\Users\88402\Desktop\Code\MBB-Plugins\
├── GLOSSARY.md              # 尚未创建，首次敲定术语时由 domain-modeling 建
├── docs\
│   ├── adr\                 # 尚未创建，首个架构决定落笔时建
│   └── agents\              # 技能配置：domain.md / issue-tracker.md / triage-labels.md
├── MBB-AntiSpam\
├── MBB-Roleplay\
└── ...（其余 MBB-* 插件目录）
```

## 使用词表里的词汇

输出里出现领域概念时（issue 标题、重构方案、假设、测试名），用 `GLOSSARY.md` 定义的术语，不要漂移到它明确避免的同义词。

若需要的概念还不在词表里，这是信号：要么你在发明项目不用的语言（重新考虑），要么确有缺口（记下来留给 `/domain-modeling`）。

## 标出 ADR 冲突

若输出与既有 ADR 矛盾，明确提出来，不要静默覆盖：

> _与 ADR-0007 冲突，但值得重开，因为……_
