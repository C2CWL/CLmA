# 状态感知循环 (Heartbeat — Periodic Awareness Loop)

智能体运行在用户正在日常使用的 Android 手机上。对设备实时状态的准确感知至关重要——基于过时状态做出的错误操作比直接询问更加糟糕。本手册列举了在关键交互轮次中应主动采样的设备状态，以及应当触发行为调整的阈值。

<!-- 1. 低成本采样：近乎零开销，高频使用 -->
## 低成本状态采样 (Cheap, run often)

这类工具调用极快且几乎不消耗额度，在用户请求依赖时应主动调用：

- **`get_time_info`** —— 获取系统当前日期、星期、时区及时间戳。在处理定时任务、或解析“明天”、“下周”、“一小时后”等时间敏感词前务必核对。
- **最近操作历史** —— 当新会话刚开始或用户询问“刚才做了什么”时，优先检查上下文中的最近工具调用记录，避免重复执行相同命令。

<!-- 2. 中等成本采样：按需使用 -->
## 中等成本采样 (Mid-cost, run when relevant)

- **`get_battery_status`** —— 准备执行长时间后台任务、用户提到“出门了”、或某任务耗时不容忽视时调用。若电量 `< 20%` 且未在充电，在回复中主动提示。
- **`get_location`** —— 仅当用户明确请求依赖位置时调用（如“附近”、“本地天气”等）。严禁无理由预先拉取。
- **`read_window_tree`** —— 在执行点击（`click_node`、`tap`）、滑动（`scroll`）等任何手势前调用，确保屏幕控件树最新。
- **`list_recent_notifications`** —— 当用户询问“刚才有什么通知”、“错过了什么消息”时调用。读取内存中的环形缓冲区，开销极低。
- **`whisper_status`** —— 收到音频/语音条/视频附件时**第一时间调用一次**。检查本地转写环境是否就绪（`ready_to_transcribe`），若就绪则调用 `transcribe_audio_file`；若缺少依赖，先告知用户并询问确认后再安装（编译构建耗时约 5 分钟并下载 ~75MB）。

<!-- 3. 高成本采样：仅在明确需要时调用 -->
## 高成本状态采样 (Expensive, only on demand)

- **`take_screenshot`** —— 仅当 `read_window_tree` 无法获取到界面内容时使用（如 Canvas 渲染界面、游戏、验证码等）。会受到系统帧率限制并消耗大模型视觉上下文。
- **`list_jobs`** —— 仅当用户查询定时任务或创建前怀疑有冲突时检查。
- **`list_installed_apps`** —— 仅在未知晓目标应用包名时调用，调用后可在本会话内复用结果。

<!-- 4. 常见异常状态信封与处置指南 -->
## 异常信封处理指引 (State envelopes)

工具在异常时会返回结构化的 `{error, recovery, ...}` 信封，应作为明确的处置依据：

| 异常信封 (Envelope) | 含义与原因 | 应对措施 |
| --- | --- | --- |
| `error: "AccessibilityService not active"` | 无障碍服务未开启或被系统杀掉 | 简明提示用户一次，引导其前往设置开启无障碍，本轮暂停屏幕操作。 |
| `error: "no_active_window"` | 临时状态（锁屏、灭屏、转场过渡） | 先调用 `wake_screen`；若提示需要密码解锁（`keyguard_secure:true`）则提示用户解锁设备。 |
| `error: "wrong_foreground_app", current: ...` | 目标应用不在前台 | 先调用 `launch_app` 调起（会自动亮屏）；若用户正停留在 CLmA 界面，可去掉包名保护直接读取当前屏幕。 |
| `error: "launch_did_not_focus", current_foreground: ...` | 启动指令已发出但系统未切换前台焦点 | 下一步 `read_window_tree` 时切勿传入 `package_name` 限制，直接读取当前屏幕，或向用户展示 `recovery` 提示。 |
| `error: "node_not_editable"` | 输入目标不是可编辑文本框 | 若为终端/Termux 界面应切换为 `termux_run_command`；否则重新定位实际输入框。 |
| `error: "termux_not_installed"` / `"termux_permission_denied"` | Termux 未安装或未配置外部应用权限 | 直接将 `recovery` 建议原样展示给用户，引导其配置 `allow-external-apps`。 |
| `error: "screenshot_unavailable", reason: "secure_surface"` | 触发安全界面（DRM/银行安全/密码输入） | 属于系统安全机制，停止重试，向用户如实说明当前处于受保护页面。 |
| `error: "rate_limited"` | 屏幕截图触发系统频率限制（约 1 秒 1 次） | 稍作停顿后再尝试。 |
| `recovery: "Enable CLmA in Settings ..."` | 缺失系统权限 | 直接向用户展示恢复建议，明确告知需在系统设置中授予相应权限。 |
| `error: "notification_listener_not_bound"` | 通知监听服务未绑定 | 引导用户在系统设置 → 通知使用权中启用 CLmA。 |
| `error: "loop_detected"` | 相同参数连续调用 3 次以上无进展触发防死循环限制 | **立即停止重试**。调整参数、更换工具或直接向用户总结当前遇到的阻碍。 |

<!-- 5. 避免死循环与节约 Token 消耗 -->
## 交互纪律：严禁死循环与无效调用 (Loop avoidance)

- **严禁浏览器地址栏打字**：切勿使用 `set_text` 去填写 Chrome 等浏览器的 URL 栏（极易因建议列表和浮窗失焦失败）。直接调用 `open_url` 打开网页或搜索。
- **严禁向 Termux 界面打字**：切勿向终端界面输入文本，直接使用 `termux_run_command` 执行并捕获输出。
- **控件点击失败切勿盲目重复**：若某个选择器（如文本匹配）未找到，不要使用相同参数连续点，改用控件 ID 或调整匹配策略。
- **连续操作避免无意义重读**：完成点击或滑动后，等待系统响应过渡，不要在相同静止界面反复读取无障碍树。
- **语音消息严禁臆造**：切勿调用 `play_media` 试图“听”语音（它只是向手机扬声器放音）。必须调用 `transcribe_audio_file` 获得实际文本。
