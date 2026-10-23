# MBB-Roleplay 多角色部署

## 多角色部署

同一群部署多个角色机器人时，必须通过 `otherRoleBotQQs` 明确填写其他角色机器人的 QQ。只有 QQ 命中该列表时，插件才会把对方当作角色本人；昵称或群名片改成某个角色名不会自动获得角色身份。`otherRoleBotNames` 只用于补充识别文本里提到了哪些机器人名称，默认留空，不会内置任何具体作品的角色名。

命中 `otherRoleBotQQs` 的角色机器人，关系数值与其他最佳关系一致：好感 `100/100`、信任 `100/100`、厌烦 `0/100`，不会因事件或时间下降，提示词中按同伴关系处理。

`repeatSuppressEnable` 默认 `false`，代码层重复回复兜底检测暂时关闭；重复问题先由通用提示词里的“同一件事只回应一次、不要复述最近说过的话”等规则处理。需要重新启用代码兜底时把它改为 `true`。

默认接话概率为 `0.50`，在没有真人插话时最多连续处理 2 条对方消息。

常用管理命令：

```text
/role bot status
/role bot chance 0.5
/role bot max 2
/role bot qq add 1004331369,123456789
/role bot qq list
/role bot name reset
```

名称识别支持 `list`、`add`、`remove`、`set`、`clear` 和 `reset`。QQ 识别支持 `list`、`add`、`remove`、`set` 和 `clear`。

## 跨进程协作

上面这些配置都在单个 Roleplay 进程内生效。同一个群里两个 bot 分属不同进程时，再加装
`MBB-BotHub` 可以让它们共享识图结果和回合权，避免同一张图识别两次、同一条消息回两遍。

```yaml
hubEnable: true
hubVisionShareEnable: true
hubVisionWaitMs: 15000
hubTurnShareEnable: true
```

hub 首次启动会探测本机 `18765` 端口，端口空闲的实例当服务端，其余实例自动当客户端，
不需要手工排启动顺序。详细说明见 [MBB-BotHub](../MBB-BotHub.md)。

