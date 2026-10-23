# MBB-QQ

QQ 扩展动作服务。它把 NapCat/OneBot 的常用 QQ 能力封装成稳定的 `PluginService`，
供 `MBB-Roleplay` 或其他插件调用，不直接把原始 OneBot action 暴露给模型。

## 功能

| 分类 | 动作 | 说明 |
|---|---|---|
| 只读 | `group_info` | 当前群名称、人数、最大人数 |
| 只读 | `member_info` | 当前群指定成员的名片、角色和入群信息 |
| 只读 | `group_history` | 读取服务器群历史消息，补足本地数据库之外的上下文 |
| 只读 | `message_detail` | 按消息 ID 查看原文和发送者 |
| 只读 | `group_notices` | 读取当前群公告 |
| 只读 | `mark_read` | 将当前群标记为已读 |
| 互动 | `poke` | 戳当前用户，或由管理员指定目标 |
| 互动 | `like` | 给当前用户或 owner 点赞 |
| 互动 | `emoji_like` | 给当前群指定消息设置表情回应 |
| 互动 | `recall_own` | 撤回机器人自己发送的消息 |
| 媒体 | `send_image` / `send_voice` | 从 Roleplay 私有工作区读取图片或语音后发送 |
| 名片 | `set_own_group_card` | 修改机器人在当前群的群名片，需要 owner 或群管理员 |
| 申请 | `pending_requests` / `friend_request` / `group_request` | 查看和处理好友申请、群邀请；只记录，不自动响应 |
| 群管理 | `group_ban` / `group_whole_ban` | 禁言、解禁、全员禁言；需要 owner 或当前群管理员 |
| 好友 | `delete_friend` / `set_friend_remark` | 删除好友、设置好友备注；仅 owner |
| 账号 | `set_avatar` / `set_profile` / `set_signature` | 设置头像、资料卡和个性签名；仅 owner |
| Qzone | `qzone_post` / `qzone_delete` / `qzone_posts` | owner 管理机器人自己的 QQ 空间 |

好友申请和群邀请只写入本地待处理表，不会自动同意或拒绝。好友删除、账号资料和头像属于 owner 操作，
不会开放给普通群员。私聊消息和好友主动消息仍未开放。

## 权限与审计

- 群号和操作者由当前 Roleplay 回合强制注入，模型不能切换操作所在的群。
- 戳一戳默认只能操作当前用户；管理员/owner 才能指定其他目标。
- 点赞默认只能操作当前用户或 owner。
- 修改机器人群名片需要 owner 或当前群管理员。
- 好友申请、群邀请只记录在当前插件的待处理表；处理时必须由 owner 明确指定申请 ID。
- 禁言、全员禁言、删除好友、好友备注、头像、资料卡和个性签名都会写入审计。
- Qzone 发布、删除和查看本地记录仅 owner 可用。
- 每个动作写入 `plugin_mbb_qq_audit`，记录群号、操作者、目标、结果和摘要，不保存图片/语音 base64。
- Qzone 发布记录写入 `plugin_mbb_qq_qzone_post`，保存 `tid`、正文、图片描述、权限和发布时间。

## 命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/qq status` | `BOT_ADMIN` | 查看各能力开关和审计保留时间 |
| `/qq audit [条数]` | `BOT_ADMIN` | 查看当前群最近 QQ 操作审计 |
| `/qq qzone [条数]` | `OWNER` | 查看机器人本机记录的 Qzone 发布 |
| `/qq reload` | `BOT_ADMIN` | 重载 MBB-QQ 配置 |

## 配置

见 [配置文件](https://github.com/MossCG/MBB-Plugins/blob/master/MBB-QQ/src/main/resources/config.yml)。
默认关闭 Qzone 的公开发布：`qzoneDefaultUgcRight=64`，需要公开发布时显式传 `ugcRight=1`。
`adminToolsEnable=false` 会同时关闭申请记录和全部高权限账号/群管理动作。
