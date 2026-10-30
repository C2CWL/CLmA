---
name: auto-reply
description: 在任意聊天应用中代表用户自动回复新消息。读取可见对话上下文，拟定贴合语境的回复并发送。组合通知监听、无障碍点击/滑动/读取工具以及返回桌面全局操作。
allowed-tools: list_recent_notifications list_active_notifications launch_app read_window_tree find_node click_node set_text scroll global_action take_screenshot
---

# 自动回复 (Auto-reply)

在来源聊天应用中代为回复新消息，无需用户切出当前操作循环。

## 触发时机

用户提出类似以下要求时使用：“帮我回复一下 <人名>”、“发微信告诉 <人名> 我在路上了”、“起草一条回复上一条消息”、“有未读直接回复”；或者你在 `list_recent_notifications` 中检测到来自即时通讯软件（微信 / WhatsApp / QQ / 钉钉 / 飞书 / 邮件等）的未读消息，且用户已授权你自主处理回复。

注意：若用户仅要求向某位新联系人发起全新会话，请勿使用此技能——应根据具体应用调用对应工具或打开 App。

## 执行步骤

1. **识别来源会话**：调用 `list_recent_notifications`，找到对应聊天应用的最新未读消息。记录 `package_name`、`title`（通常为联系人或群组名）、`text`（消息预览文本）以及 `key`。
2. **打开应用**：如果通知包含主操作按钮，优先使用 `notification_action_click`；否则调用 `launch_app(package_name = "<包名>")` 打开应用。
3. **定位联系人会话**：调用 `read_window_tree`，使用 `find_node` 匹配联系人名称节点并执行 `click_node`。若当前已停留在该聊天窗口，则跳过此步。
4. **读取可见上下文**：在聊天界面执行 `read_window_tree`。若最新几条消息未完全展现，可执行一次 `scroll(direction = "up")`。从节点树中提取最近 3-5 条消息作为文本。
5. **起草回复内容**：契合用户的语言风格与习惯（参考长期记忆）。保持简练。若对方是提问则给出答案；若是状态同步则进行确认；若是事务请求，则判断当前能否处理或需要延后。
6. **发送消息**：使用 `find_node` 定位输入框，调用 `set_text` 填入回复草稿；使用 `find_node` 定位发送按钮（通常为纸飞机、箭头或“发送”字样）并调用 `click_node`。
7. **确认留存**：调用 `take_screenshot` 截屏，以便用户在聊天记录中核验。
8. **返回桌面**：调用 `global_action(action = "home")`。

## 所需工具

- `list_recent_notifications`, `list_active_notifications`
- `launch_app`, `notification_action_click`
- `read_window_tree`, `find_node`, `click_node`, `set_text`, `scroll`
- `take_screenshot`
- `global_action`

## 常见异常与恢复策略

- **找不到输入框**：部分应用发送消息使用弹窗浮层。稍作等待后重试 `read_window_tree`；若仍未定位，终止操作并告知用户：“已打开聊天窗口，但无法定位输入框，请手动回复”。
- **发送按钮置灰不可点击**：通常是文本未成功注入。重新尝试一次 `set_text`；若依然置灰，中止操作。
- **点错联系人**：若 `find_node` 命中了同名或非目标联系人，立即通过 `global_action(action = "back")` 退出并改用搜索栏精确定位。
- **通知已被清除**：`list_recent_notifications` 维护有 100 条环形缓冲区，被划掉的通知仍会留存。始终以实际打开应用看到的会话为准，切勿盲信通知为最新。

## 红线禁忌 (Don't)

- 未经用户明确确认，绝不可在群聊中代发消息——避免带来尴尬或误解。
- 绝不发送包含用户真实敏感隐私信息（身份证号、银行卡号、完整住址、密码口令等）的内容，除非是用户本人显式要求发送的原话。
- 发送前无需将整段对话原文向用户复述一遍，除非用户特别要求先过目草稿。
