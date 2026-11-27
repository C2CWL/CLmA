# Bot 模式第二期：架构设计

状态：草案（2026-11-28）。参考来源：OpenMausBot（Apache-2.0，commit 95f3c9f，不含 `enterprise/`），见 `third_party/openmausbot/`。

## 1. 原则

1. **内容不互通，底层共用。** Bot 模式与普通模式共用 ChatService / GenerationLoop / Room 表 / 供应商与模型调用 / 工具 / MCP，
   但所有“读”都按库分区：对话归属 = 对话绑定助手的 `library`（`BotLibraryConversations.kt` 的 `isInLibrary` /
   `libraryScope`）。会话列表、搜索、收藏、记忆、最近会话引用、定时任务列表与结果、通知深链、Web/Telegram 等都要过滤。
   不改现有表结构；不搬数据。
2. **Bot 设置独立。** 新建 Bot 模式自己的设置页与存储（独立 DataStore 文件 `bot_mode_settings`），
   普通模式设置不影响 Bot。每个 Bot 的档案（审批档位、始终允许、SOUL、对外发送策略、Chief of Staff、汇报对象）
   存在独立存储 `bot_profiles`，不往 `Assistant` 加字段。
3. **新代码独立成包，少量钩子接入。** 新代码放 `data/botmode/<feature>/` 与 `ui/pages/bot/<feature>/`，
   只在 `assistant.isBot` 且 Bot 模式下生效；普通模式路径行为不变。每个对现有文件的改动都列在 PR 说明里。
4. **对齐原版交互。** 功能、流程、版式、文案尽量对齐 OpenMausBot 前端（`src/components/bot-settings/*`、
   `routines/*`、`ApprovalModeSelector`、`SoulField`、`CheckBackLine`、`SidebarBotActivity`、`TeamMapPage`、`NowCard` 等），
   中文文案以其 `src/locales/zh.json` 为基础改写，去掉其名称/吉祥物。
5. **借用代码按 Apache-2.0：** `third_party/openmausbot/LICENSE`+`NOTICE`，改编文件头注明来源与“已修改”。

## 2. 共用 vs 独立

| 层 | 处理 |
|---|---|
| 供应商/模型调用（`:ai` 模块 ProviderManager、Provider、UIMessage） | 共用 |
| 工具（LocalTools、MCP、连接器、技能） | 共用；Bot 专属工具（check_back、soul、board 等）放 `data/botmode/*`，经 ChatService 现有的 Bot 工具注入点挂入 |
| 生成循环 GenerationLoop / ChatService | 共用；只在审批判定、系统提示词两处加 Bot 钩子 |
| 对话/消息表 | 共用，按库过滤读取 |
| 记忆 | 共用表，Bot 的“全局记忆”用独立 id，与普通模式全局记忆分开；Bot 长期记忆（MEMORY.md/主题/日志）为 Bot 专属文件存储 |
| 定时任务 | 共用 ScheduledJob 表与调度器；Bot 任务只在 Bot 页面出现，普通列表/工具过滤掉 |
| 设置 | Bot 独立设置页 + 独立存储 |
| 看板、简报、频道目标、Bot 活动状态 | Bot 专属存储（新 JSON/DataStore 文件或新库文件，不改旧表） |

第一期的 Bot 定时任务继续用共用调度，只做数据分区（用户已确认搬不搬无所谓，选最稳妥的方案）。

## 3. 锁屏后定时任务失效

单独 PR（普通模式与 Bot 都受益）。原因与修法见该 PR：闹钟到点后只排了普通 WorkManager 任务（Doze 下被推迟到维护窗口）、
Worker 没有前台服务、后台无法启动生成用的前台服务、无精确闹钟权限时退化为不精确闹钟、厂商省电。
修法：闹钟广播直接启动 `ScheduledRunService` 前台服务（精确闹钟属于后台启动 FGS 的豁免），在服务内持 wake lock 直接执行任务；
失败回退到加急 WorkManager；权限变化时重排闹钟；增加“精确闹钟 + 电池优化 + 厂商自启动”引导卡。

## 4. PR 拆分（每个独立可合并，均基于 origin/main）

| PR | 内容 |
|---|---|
| A `fix/cron-lockscreen-wake` | 锁屏定时任务可靠性 |
| B `fix/bot-data-isolation` | 两种模式内容隔离审计与修复 |
| C `feat/bot-settings-policy` | Bot 独立设置页/存储；每 Bot 档案：审批档位（询问/代我批准/完全访问）、始终允许、SOUL、对外发送 ask/allow、Chief of Staff/汇报对象；Bot 设置分区页（概览/身份/SOUL/记忆/定时任务/权限/访问…）；Bot 活动状态（工作中/等你处理/空闲） |
| D `feat/bot-routines-v2` | check_back、routine checks 预检查、overlap(skip/queue)、更多 schedule、结果去向、运行卡片 |
| E `feat/bot-memory-journal` | MEMORY.md、memory/主题、每日日志、记忆整理 |
| F `feat/bot-team` | 团队任务看板、主助手每日简报卡、频道目标、工作流触发器挂到 Bot |

## 5. 工作量估计

A 小（~1 天）；B 中（~1–2 天）；C 大（~3–4 天）；D 中大（~3 天）；E 中（~2 天）；F 大（~4–5 天）。
均以 GitHub Actions 编译/单测为准，涉及后台、通知、锁屏、权限的部分需实机验证。
