---
name: notification-summarise-and-act
description: 读取最近与当前活跃通知流，按应用分组归纳关键事件，并提炼具体行动建议。适用于用户询问“有什么消息”或离线数小时后重新点亮设备。
allowed-tools: list_recent_notifications list_active_notifications dismiss_notification notification_action_click launch_app read_window_tree get_time_info
---

# 通知汇总与操作建议 (Notification summarise + act)

将纷繁复杂的通知流精炼为简短摘要，并给出可直接执行的操作建议。核心目标是实现“5 秒速览、10 秒决断”。

## 触发时机

- “我错过了什么消息”、“刚才有什么动静”、“有什么重要事吗”
- 用户刚醒来，或开会数小时后首次打开手机
- 定时工作流触发（例如“息屏每两小时自动汇总通知”）

## 执行步骤

1. **时间锚定**：调用 `get_time_info` 确定时间区间（以最新通知的 `post_time` 为下界）。
2. **读取通知流**：
   - `list_active_notifications`：获取当前通知栏中驻留的通知。
   - `list_recent_notifications(limit = 50)`：读取环形历史记录，覆盖过去数小时。
3. **按包名分组**：按应用归类，统计条数，获取最新标题与摘要预览。过滤掉用户未加入白名单的干扰应用。
4. **分类整理**：
   - **需处理项 (Actionable)**：需要回复、确认或决断的事项（即时通讯消息、日程邀请、快递到达、账户预警等）。
   - **资讯类 (Informational)**：快讯推送、应用更新、社交动态、营销推广。
   - **紧急项 (Urgent)**：银行风控、未接来电、系统安全预警、验证码通知。
5. **组织简报**：
   - 紧急项置顶（≤2 行）。
   - 随后呈现需处理项（每个来源 1 行，标明数量与发件人）。
   - 默认直接忽略普通资讯类通知，除非用户有专门阅读偏好。
   - 整体总长度控制在 6 句短句以内。
6. **提出行动建议**：在末尾提供至多 3 项具体操作建议：
   - “是否在 Telegram 中回复 <张三>？”
   - “是否打开银行安全预警？”
   - “是否一键清理 14 条营销推广通知？”
   用户回复确认后即可直接代为执行。
7. **执行清理**：若用户同意清理营销类无用通知，针对对应条目调用 `dismiss_notification(key = ...)`。未经确认切勿随意清除通知。

## 所需工具

- `list_recent_notifications`, `list_active_notifications`
- `dismiss_notification`, `notification_action_click`
- `launch_app`, `read_window_tree`
- `get_time_info`

## 异常处理与边界

- **通知监听未启用**：告知用户仅可读取 `list_active_notifications`，并引导至系统“设置 → 通知使用权”开启。
- **无新通知**：一句话回复“暂无新通知，过去数小时内很清静”并结束，严禁生造内容。
- **通知包含验证码**：归类为紧急/关键，但**切勿**在回复中直接念出验证码明文，仅提示“收到银行/服务验证码，已保留在手机通知中”。

## 红线禁忌 (Don't)

- 未经用户明确许可，不得批量清除或滑动丢弃任何通知。
- 不要将消息完整正文大段念出或向外转发——那是 `smart-forward` 的职责。
- 若汇总后的字数反而超过了原始通知本身，则失去摘要意义，此时直接简要列出即可。
- 不要仅因为大写字母或多个感叹号就将某条营销推送误判为“紧急事件”。
