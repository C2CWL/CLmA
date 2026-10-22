# RikkaHub Agent 纯化版 · 二期工程（PHASE2）

> 立项：2026-10-02 ｜ 仓库：`wuyhong715/rikkahub-agent-pure`（公开，AGPL-3.0）
> 一期（T-01~T-12）已全部完成并 squash 合并；唯一遗留 = **T-03 前缀缓存旧结论作废，待假代理复测**。

---

## 0. 30 秒速览

- **目标**：从"单个全能 agent"扩展到"**全能 / 混合 / 一父多子**"三种执行强度，且 token 账本要**准确到能管预算**。
- **不做**：模式枚举二选一（已否决）；父必须薄工具面（已否决）；递归卫放宽（**已决策冻结** D11，见 §6 / P2-10 —— 待真实链式需求触发）。
- **做法**：**正交旋钮 + 三套预设**；地基卡先把「工具面装配」和「助手解析」各收成**单点**，其余全在其上叠加。
- **卡数**：二期 12 项 + 1 张收口清扫（P2-14）+ 1 张候选收口（P2-15）+ 验收修复批次（P2-16~P2-22）+ 后续卡（P2-23~P2-29）；**除 P2-10 冻结外全部收口**（P2-10 见 §4 / §6）。
- **顺序**：`P2-11 → P2-01 → P2-02 → P2-04 → P2-06 → P2-05 → P2-07 → P2-08 → P2-12 → P2-13 → P2-14(收口清扫 ✅ `f518a65d`) → P2-15(§9.2 #7 信封 ✅ `e394e221`) → P2-16~P2-22(ACCEPT-FIX ✅) → P2-23~P2-25(D7/D10 ✅) → P2-26(D6 剩余 ✅) → P2-27(D3 ✅) → P2-28(D4 分组半 ✅) → P2-29(D5 残留 ✅) →(可选·已冻结) P2-10`
  （P2-11 先做额外收益：顺手收掉一期 T-03 的假代理复测尾巴。）
- ★ **现状（2026-10-04 · P2-29 后）**：**二期全部收口** —— 十二项 + ACCEPT-FIX 7 卡 + D7/D10/D6/D3 + **D4（分组半）/ D5（`mcp_update` 残留）** 均已合并，CI 全绿；**master＝`695778a3`，50 个 PR 全 merged，0 open PR / 0 open issue**。剩余＝**产品池**（D2 工作区 / D13 备份迁移 / D6 实时统计 / D4 是否再追「独立页」）+「**35 会话 vs 统计页总对话数 1**」待判；P2-10 **冻结**待触发。

---

## 1. 红线（沿用一期 + 二期新增）

1. 新功能**默认关**；关闭时行为**逐字节不变**（默认路径 = 今天的 SOLO/全能路径）。
2. **优先新增文件**，少动 `ChatService.kt` / `GenerationLoop.kt`。
3. 不编造 API：改动前 `grep` 确认真实签名。
4. 每卡一分支一 PR；**CI 全绿 + 单测通过**才算完。
5. AGPL-3.0 开源。
6. ★ 新增：**账本只存元数据**（tokens / 成本 / id），**不存消息内容**。
7. ★ 新增：**未知 ≠ 0**（provider 未报告的字段一律 `null`，不得当 0 参与统计）。
8. ★ 新增：预算超限时**回结构化信封**（模型可读），不静默失败。

---

## 2. 已定决策（DECISIONS）

| # | 决策 | 来源 |
|---|---|---|
| D1 | 不做互斥模式枚举；用**正交旋钮 + 三套预设** | 用户否决 P2-03 |
| D2 | 旋钮：父工具面 / 是否派子 / 子来源（名册·动态创建）/ 编排预算 / 保活 | 设计 |
| D3 | 三套预设 = 全能跑到底 / 部分任务派发 / 一父多子并发，**可混用** | 用户 |
| D4 | 专家**持久复用** + **独立命名空间**（workspace/记忆） | 用户 |
| D5 | 两套"专家"概念**合并**：`SubAgentProfile`(DataStore) 迁入 Room 专家库 | 本轮 |
| D6 | 专家用**新实体 `AgentDefinition`**（助手子集）+ "定义 → 合成 Assistant" 适配器 | 本轮 |
| D7 | 权限：`subagent_create/update/delete` **需批准**；**使用**已有专家**免批准** | 本轮 |
| D8 | 预算：每编排 token 上限**默认空 = 不限**；预设并发 3 / 子数 ≤8；每子 `max_trips`·`timeout` 保持现状 | 本轮 |
| D9 | 命名空间：`/workspace/agents/<slug>/`；专家冷记忆 = 该目录下 `memory/`（复用 T-06 `coldMemoryDir` 字段） | 本轮 |
| D10 | 父对专家库的写操作进 `agent_runs`（`kind=agent_def_write`）+ UI 变更历史 | 本轮 |
| D11 | 递归卫**不动**（一层，全部编排经父中转）；P2-10 留待真实链式需求 | 本轮 |
| D12 | 价格表：`设置 → 模型提供商 → 价格表`（按 model，含峰谷）+ **agent 注入入口**（ALWAYS_ASK + 审计 + 整表版本替换） | 本轮 |
| D13 | 成本在写入 `UsageRecord` 时**冻结**（存 `priceVersionId` + `costMicro`）+ "按当前价重算"按钮 | 本轮 |
| D14 | 导出：元数据 CSV 明细 + JSON 汇总，落 `/workspace/exports/`，支持 `since` 增量 | 本轮 |
| D15 | 每日上限**后置**，本期不做 | 用户 |

---

## 3. 现状实核（开工依据）

### 3.1 六个工具面装配点（P2-01 的收口清单）

| # | 位置 | 路径 |
|---|---|---|
| 1 | `WorkflowEngine.kt:224` | workflow 动作路径 |
| 2 | `CronJobWorker.kt:368` | cron 路径 |
| 3 | `ChatService.kt:1065` | fast-path router |
| 4 | `ChatService.kt:1562` | rerun |
| 5 | `ChatService.kt:1854` | 常规对话 |
| 6 | `ChatToolFactory.kt:63` | 工具工厂 |

⇒ 任何工具面语义变更都要改 6 处，否则**路径漂移**。P2-01 收成 **1 个 `resolveToolSurface(...)`**。

### 3.2 已有单源真相（可直接复用，勿重建）

- `LocalTools.getTools()` **末尾唯一收口点**：`tools.map { if (ToolApprovalDefaults.requiresApproval(it.name)) it.copy(needsApproval = { true }) else it }` —— **逐工具过滤的天然挂点，一处生效**（P2-02 落这里）。
- `ToolApprovalDefaults`：扁平工具名注册表，`ALWAYS_ASK` 132 项，**逐工具 key 直接用工具名**。
- `LocalToolOption`：52 个分组（`@SerialName` 字符串键，55 处 `options.contains` 分支），已有 `LenientLocalToolListSerializer`（备份兼容先例）。
- MCP 已按助手选择：`Assistant.mcpServers: Set<Uuid>`（`McpPicker.kt`）；助手默认 `localTools = listOf(LocalToolOption.TimeInfo)`。
- `"能否派子"已是独立开关`：`LocalTools.kt:946` 门控 `LocalToolOption.SubAgents`；UI `AssistantLocalToolPage.kt:905`。
  ⇒ **"全工具面 + 派子"（= 混合预设）今天零代码即可用。**

### 3.3 子 agent 现状

- `SubAgentEngine.kt:426`：`Conversation.ofId(id = Uuid.random(), assistantId = parentAsstUuid, …)`
  ⇒ 子会话**复用父助手**，继承父的**完整工具面** + 记忆/压缩策略。
- `SubAgentProfile`（`SubAgentRun.kt:199`）：目前**只有 model + system prompt**（prompt 被 prepend 到 task 文本）。
- 子 run 用量字段：`SubAgentRun.kt:34/35` `tokensIn` / `tokensOut`（默认 0）。
- **递归卫**：`SubAgentEngine.dispatch` 注释原文 "…**v1 does not allow nested sub-agents.**"
- 并发：全局 cap + `Assistant.maxConcurrentSubAgents`（默认 3，`:361` `coerceIn`）；`run_in_background=true` 真并行扇出可行。
- ★ **G1 专家名册是真缺口**：`SubAgentTools.kt:103` 的 `agent` 参数描述是**写死静态串**，**不列 profile** ⇒ 父只有在**派发失败**时才知道合法名字。

### 3.4 统一 run 账本（Phase 24，已存在）

- 包 `data/agentrun/`：`AgentRun.kt`（`id` / `kind` / `domainId` / `parentRunId: String?` / `status`）、`AgentRunRepository`、`AgentRunDao`、`AgentRunBootRecovery`。
- `AgentRunBootRecovery`：跨 pillar 启动清扫（覆盖五条自治路径），把超 `AgentRunDefaults.STRANDED_THRESHOLD_MS` 未更新的行翻成 `AgentRunStatus.process_lost`，**只发一条聚合通知**，best-effort 不抛。
- ⇒ **进程被杀 = 长任务丢失，只记账不续跑**（P2-08 保活的动机）。

### 3.5 ★ token 现状（P2-11 的落点，本轮新实核）

- **AI 核心在独立模块 `ai/`**（非 app 模块）。
- `ai/src/main/java/com/clma/ai/core/Usage.kt` →
  ```kotlin
  data class TokenUsage(promptTokens=0, completionTokens=0, cachedTokens=0, totalTokens=0, cost: Double? = null)
  fun TokenUsage?.merge(other: TokenUsage): TokenUsage   // ★ 语义有坑，见下
  ```
- **采集/聚合点**：
  - provider 流式解码器产 `StreamChunk.Usage`（`ChatCompletionsStreamDecoder.kt:80` 解析 `payload["usage"]`；`GoogleStreamDecoder.kt:50`）
  - **回合聚合点** = `ai/src/main/java/com/clma/ai/ui/StreamChunkHandler.kt:338` → `usage = last().usage.merge(result.usage ?: TokenUsage())`
- **已有单测**：`ai/src/test/.../ChatCompletionsUsageParsingTest.kt`、`ai/src/test/.../UsageTest.kt` ⇒ **P2-11 有现成测试落点**。
- 持久化：`ui/.../message/ChatMessageNerdLine.kt:73-78` 渲染 footer；`MessageNodeDAO.kt:59` `cachedTokens: Long = 0`。
- 统计页**已存在**：`ui/pages/stats/StatsPage.kt` / `StatsVM.kt`（含 `totalCachedTokens`、`stats_page_cache_hit_rate`）。
- 估算侧：`ContextBudgetPlanner`（优先最近一次真实 usage，缺失才估算 —— 设计不错）；`CompactionTools.kt:18` 注释明写 `compact_context` 的数字 **not provider-reported usage**。

### 3.6 ★ 已定位的四个准确性缺陷（P2-11 要修的）

| # | 缺陷 | 证据 |
|---|---|---|
| A1 | **`merge()` 语义**：`totalTokens = prompt + completion`（忽略 provider 报的 total）；`cachedTokens` 是**覆盖**不是累加；`>0` 才采纳 ⇒ "未报告"与"0"不可分 | `ai/core/Usage.kt` |
| A2 | **`cachedTokens = 0` 双义**：未报告 = 0 = 真 miss ⇒ 污染命中率均值 | `Usage.kt` + `MessageNodeDAO.kt:59` |
| A3 | **归属粒度 = 单条 assistant 消息 = 最后一次 API 调用** | `ChatMessageNerdLine.kt:78` |
| A4 | **无用途分类**：主回合 / 工具续跑 / 压缩 / 标题 / 建议 / 记忆提取 / 子 agent / cron / workflow 混在一起（且辅助调用**大概率完全没记**） | `StreamChunkHandler` 无 purpose 参数 |

---

## 4. 卡片

> 格式：**目标 / 现状 / 落点 / 红线 / 验收 / 单测**

### P2-11 账本采集层（★ 第一张）

- **目标**：一次 API 往返 → 一条 `UsageRecord`；全路径覆盖；未知 ≠ 0；成本冻结。
- **现状**：§3.5 / §3.6。
- **落点**：
  1. `ai/core/Usage.kt`：`cachedTokens: Int?`、新增 `missTokens`/`reasoningTokens`；**修正 `merge()`**（按 provider 语义，total 优先用 provider 值）。
  2. `ai/ui/StreamChunkHandler.kt`：新增可选 `purpose` 参数（**默认值保证老路径不变**），在聚合点旁**单点上报**。
  3. 新增 `app/.../data/usage/`：`UsageRecord.kt` / `UsageRecordDao.kt` / `UsageRecordRepository.kt`（Room 新表，索引 `ts` / `parentRunId` / `assistantId`）。
  4. 价格表：`设置 → 模型提供商 → 价格表`（DataStore，按 model + 峰谷），`priceVersionId` + `costMicro` 冻结；**agent 注入工具**进 `ALWAYS_ASK` + 审计 + 整表替换。
  5. 导出：`/workspace/exports/token-ledger-<date>.csv|json`。
- **红线**：只写元数据；未报告字段 `null`；默认路径行为不变（新增参数有默认值）。
- **验收**：跑一轮真实对话 → 表里出现 N 条记录（N = 实际往返数）；`compact_context` 的估算数**不被当作真实用量**；导出文件可读。
- **单测**：扩 `UsageTest.kt`（merge 语义 + null 语义）；新增 `UsageRecordDaoTest`。
- ★ **额外收益**：这套账就是 **T-03 假代理复测**的证据源（一次复测两用）。

### P2-01 两个单点（纯重构，零行为变化）

- **目标**：① 工具面装配 6 → 1；② 助手解析多 → 1。
- **现状**：§3.1（六点）；助手解析分散在 `settings.getAssistantById(cid)` 各处。
- **落点**：新增 `data/ai/ToolSurfaceResolver.kt`（唯一装配）+ `data/ai/AssistantResolver.kt`（唯一解析，支持后续"专家定义 → 合成 Assistant"）。
- **红线**：**零行为变化**（先重构、后加能力）；每改一处跑一次单测。
- **验收**：`grep` 证明 6 处调用全部改走单点；全量单测绿。
- **单测**：新增 `ToolSurfaceResolverTest`（六种调用场景等价性）。
- ★ **助手解析单点是 P2-04/P2-06 的前置**（专家定义要能被解析成"助手"给引擎用）。

### P2-02 逐工具开关

- **目标**：`Assistant.disabledLocalTools: Set<String>`（**默认空 = 回退现状**）。
- **落点**：`LocalTools.getTools()` 末尾**唯一收口点**（§3.2）→ 过滤 `disabledLocalTools`；UI 在 `AssistantLocalToolPage` 内逐项开关。
- **红线**：空集时逐字节等价。
- **验收**：关掉一个工具 → 模型看不见它（`tool_not_found`）；重开恢复。
- **单测**：`LocalToolsFilterTest`。

### P2-04 子 agent 独立工具库 + T-09 语义升级

- **目标**：`SubAgentProfile`/`AgentDefinition` 带**自己的工具面**（可含父未启用的本地工具与 MCP）⇒ 从"子 ⊆ 父"升级为"**无头安全底线**"。
- **现状**：`SubAgentEngine.kt:426` 子会话 = 父助手 ⇒ 面 = 父面（`ChatService.kt` 一处固定）。
- **落点**：`SubAgentEngine` 解析"子要用的助手/定义"而非父助手；冻结逻辑改为**逐工具底线判定**（逐次确认类 + 隐私类 + UI 绑定类 = 谁都不可无人值守跑）。
- **红线**：默认（未配独立面）时**行为 = 今天**。
- **验收**：子 agent 能用父未启用的工具；被冻结类工具一律拒（信封可读）。
- **单测**：`SubAgentSurfaceTest`。

### P2-06 专家定义（Room + 工具 + UI）

- **目标**：`AgentDefinition`（名字/描述/系统提示/模型/工具面/MCP/skills/命名空间/预算）持久化 + `subagent_create/update/list/delete` + 专家库 UI。
- **落点**：新表 + DAO；工具进 `ALWAYS_ASK`（写入留审计，D10）；命名空间 `D9`；迁移 `SubAgentProfile`（D5）。
- **红线**：不使用专家时零影响；**不写内容进账本**。
- **验收**：父建一个专家 → 列表可见 → 派发成功；重启后仍在。
- **单测**：`AgentDefinitionDaoTest` + 迁移测试。

### P2-05 工具调色板（复活 T-03 机制）

- **目标**：接上 `ToolCatalogSource.LOCAL`（现生产未用），供"配专家"时搜索/浏览工具。
- **落点**：`ToolCatalog.kt` LOCAL 源 + `ChatService.kt:1924` 旁路；配专家 UI。
- **红线**：**不改**默认对话路径的目录行为。
- **验收**：调色板能列出本地工具目录并带回 `source=LOCAL`。

### P2-07 预算设置

- **目标**：每编排 token 上限（**默认空=不限**）+ 并发/子数（3/≤8）。
- **落点**：助手级设置 + 专家级覆盖；读取口径 = `UsageRecord` 按 `parentRunId` 汇总。
- **验收**：设置项持久化；超限可被 P2-13 读取。

### P2-08 保活（编排期 FGS）

- **目标**：有活跃子 run 时起前台服务，编排结束即撤。
- **现状**：`AgentRunBootRecovery` 只能"记账不续跑"（§3.4）。
- **红线**：无活跃 run 时**不起服务**（不能变成常驻）。
- **验收**：编排期锁屏/切后台不中断；结束后通知消失。

### P2-12 账本归属与展示

- **目标**：回合视图（**本回合 N 次调用 / 输入 X / 命中 Y，可展开逐次**）+ 编排树（父→子）+ StatsPage 扩成按助手/日/用途/模型；**估算 vs 真实显式标注**。
- **落点**：`ChatMessageNerdLine.kt`（footer 升级）、`StatsPage/StatsVM`（扩展）、新编排树页。
- **验收**：一次含工具的回合能显示全部调用；`compact_context` 的估算数标注为估算。
- ★ **开工侦察发现 F1（阻断）⇒ 拆 4 子卡**（口径全 A，2026-10-03）：
  - **P2-12a 采集补齐**（✅ 已合并）：流式调用此前**根本没进账本**（装饰器只覆写 `generateText`，而主回合默认 `streamOutput=true` 走 `streamText`）⇒ 不修则回合视图对真实对话恒为空。见下方完成记录。
  - **P2-12b 回合视图**（✅ 已合并）：footer 升级 + 可展开逐次 + 估算 vs 真实标注。见下方完成记录。
  - **P2-12c StatsPage 扩展**（按助手/日/用途/模型）（✅ 已合并）。见下方完成记录。
  - **P2-12d 编排树页**（父→子；入口挂**统计页**）（✅ 已合并）。
  - **归属键口径 = 时间窗（12b 实核后修订）**：窗口 = `[本节点 currentMessage.createdAt, 下一节点 currentMessage.createdAt)`（末节点开区间），并按 purpose 排除 `TITLE` / `SUGGESTION`。★原口径「`[createdAt, finishedAt]` 天然落窗」对流式**不成立**：流式记录在 `Finish` 块盖上 `finishedAt` **之后**才落库（收在那里会漏掉整条默认流式路径），而 `ChatService` 回合结束后 `launchAuxJob` 触发的标题/建议又落在同一段。故窗口收在下一节点 + 按用途过滤。零 schema / 零 UIMessage 改动。

### P2-13 预算执行与 usage 回传

- **目标**：派发前查汇总 → 超限**拒派**（结构化信封）；子跑完把 **usage 摘要回传父的 tool 结果**。
- **落点**：`SubAgentTools` / `SubAgentEngine`。
- **红线**：无预算配置时零影响。
- **验收**：设小额度 → 第二次派发被拒且模型能读懂原因。
- ✅ **已合并**（2026-10-03，PR **#34** / squash `217df6b7`）。口径（用户 ask_user 全 A）：
  ① used ＝ 会话级累计（`usage_records.parent_run_id = 父会话id`，复用 `tokensForParentRun`，**零 DAO 改动**）；
  ② 信封复用 `error` + `detail`（`budget_exceeded`，与既有限流信封同形）；
  ③ 回传字段＝`tokens_in` / `tokens_out`（回填死字段）+ `calls`；
  ④ 后台 run 的唤醒消息也带一行 usage 摘要。见文末完成记录。

### P2-14 遗留清单清扫（代码类）

- **目标**：清掉 §9 里**能在代码侧收口**的四条（其余为装机实测，另计）。
- **落点**：`SubAgentEngine`（14a 取消竞态兜底 / 14c 闸门显式化）、`SubAgentTools` + 新 `SubAgentRunEncoder`（14b）、`OrchestrationTree` + `StatsVM` / `StatsPage`（14d）。
- **红线**：无预算配置时零影响；不改 DAO（账本测试替身零改动）；`OrchestrationBudget` 本身不动。
- **验收**：取消竞态不再留非终态 registry 条目；`subagent_list` 与 `subagent_get` 的用量字段一致；有上限但无会话根的派发留 warn；统计页编排树显示「已用 / 预算 / 剩余」。
- ✅ **已合并**（2026-10-03，PR **#35** / squash `f518a65d`）。见文末完成记录。

### P2-15 §9.2 #7 后半条 —— 剩余额度进派发信封（候选收口）

- **目标**：让**父模型**在每次派发的信封里读到编排预算的**剩余额度**，从而提前自我节流（此前只能撞墙后从拒绝原因读到）。
- **落点**：`SubAgentEngine`（`checkOrchestrationBudget` 返回 `BudgetGate(refusal, remaining)`；`DispatchResult.Ok` 增 `budgetRemaining`）、`SubAgentTools`（把 headroom 交给编码器）、`SubAgentRunEncoder`（`encodeRun` 增可选参数 + 条件键）。
- **口径**：`remaining = 上限 − 派发时刻已花`，**本次 run 尚未计入**（与闸门 pre-flight 契约 D8 一致）；**无上限 ⇒ 不加键**（逐字节不变）；`0` 照常上报。
- **不收口**：拒绝信封（那里 remaining 恒 0）、`subagent_get` / `subagent_list`（registry 不带上限）。
- **验收**：无预算配置时派发信封与 P2-14 逐字节一致；配了上限后，派发返回体含 `budget_remaining` 且等于「上限 − 已花」。
- ✅ **已合并**（2026-10-03，PR **#36** / squash `e394e221`）。见文末完成记录。

### P2-10（可选，**已决策冻结**）递归卫放宽

- 深度上限 + 每层预算，仅当需要"专家链式接力"。**本期不做**（D11）。
- ★ **2026-10-03 复核结论：现阶段无需求 ⇒ 冻结为「待触发项」**（非遗留缺陷，不进收口清单）。
  - 依据：三种预设（全能 / 混合 / 一父多子）**全是一层结构**；§3.2 已实核「全工具面 + 派子」零代码可用，父**串行派发两次**即可替代嵌套。
  - 成本被低估：不是加 `depth` 参数 —— 连带 **预算归属**（P2-13 会话级累计口径）/ **取消传播**（`executionJob` 只传一层）/ **并发上限**（扁平计数）/ **账本归因**（`parentRunId` 单链→树）四处重做，且正好压在 §9.2 已知限制上。
  - **触发条件**（满足任一才开卡）：① 装机实测全过，且真实使用中反复出现「父必须转包、且父自身代劳不了」；② P2-13 预算闸与账本归属口径已装机验证稳定。

---

## 5. 依赖图

```
P2-11(账) ─┬─────────────→ P2-12(展示) ──→ P2-13(执行)
           └→ (顺带) T-03 假代理复测
P2-01(两单点) ─┬→ P2-02(逐工具开关)
               ├→ P2-04(子独立面)  ← 需 P2-01 的"助手解析单点"
               └→ P2-06(专家定义)  ← 需 P2-01 的"助手解析单点"
P2-06 → P2-07(预算) → P2-08(保活)
P2-05(调色板) 服务于 P2-06 的 UI
```

---

## 6. 待决（后置，不在本期）

> ★ 二期收口后的完整遗留清单（装机验收 / 已知限制 / 走查疑点）见 **§9**。

1. 每日上限（D15 后置）。
2. 递归卫放宽 / 专家链式接力 —— ★ **已冻结（D11，2026-10-03 复核）**，待触发；触发条件见 §4 P2-10 段。
3. "本次对话临时覆盖预设"（不做）。
4. T-02 失败路径实测。
5. skill-tester / external-automation 两条路径实测（按"同一咽喉点"记账）。

---

## 7. 关键路径速查

- 仓库：`wuyhong715/rikkahub-agent-pure`；master = `f518a65deabb0666d35412127cee1e9ef8b418c3`（P2-14 合并后）
- 容器工作副本：`/tmp/pure-t01`（`/workspace/vps.sh 'cd /tmp/pure-t01 && …'`）
- 模块：`app/`（UI·服务·数据）+ **`ai/`（AI 核心：provider / StreamingChunk / Usage）**
- 一期文档：`/workspace/rikkahub-pure/`（HANDOFF.md / PLAN.md / PROJECT.md / TASKS.md / TASK_PLAN.md）
- 本期本文档 = `PHASE2.md`

---

## 8. 进度

| 卡 | 状态 | 证据 |
|---|---|---|
| **P2-11a** TokenUsage 语义修正 + provider 补齐 | ✅ **已合并** | PR **#13**，squash `d11fb4a1`，CI 绿（assembleDebug + unit tests） |
| **P2-11b** 全 provider 来源字段补齐（8 个生产者） | ✅ **已合并** | PR **#14**，squash `2ff65d11`，CI 绿 |
| **P2-11c** 用量账本存储层（Room 新表 + 保留期清扫） | ✅ **已合并** | PR **#15**，squash `9445f178`（★ 独立库文件 `usage_ledger.db`，不并入 AppDatabase） |
| **P2-11c2** 采集单点接线（provider 解析点 + purpose 上下文） | ✅ **已合并** | PR **#16**，squash `147195f9` |
| **P2-11d-1** 成本计算 + **写入时冻结**（价目模型 + 峰谷窗口） | ✅ **已合并** | PR **#17**，squash `b1a87761` |
| **P2-11d-2** 账本导出 CSV/JSON（`usage_export` 工具组，默认关） | ✅ **已合并** | PR **#18**，squash `31aa2fd0` |
| **P2-11d-3** 价格表注入入口（`usage_get_prices` / `usage_set_prices` 整表替换 + 审计） | ✅ **已合并** | PR **#19**，squash `f8145014` |
| **P2-11d-4** 设置页价目 UI（模型表单第 4 个 tab「价格」） | ✅ **已合并** | PR **#20**，squash `51f56908` |
| **P2-01** 工具面 6→1 + 助手解析单点 | ✅ **已合并** | PR **#21**，squash `7a76c2c8`，**CI 首轮即绿**（1996 tests / 0 failures，含 17 个新单测） |
| **P2-02** 逐工具开关（`Assistant.disabledLocalTools`） | ✅ **已合并** | PR **#22**，squash `4e68d9b6`，**CI 首轮即绿**（2004 tests / 0 failures，含 8 个新单测） |
| **P2-04** 子 agent 独立工具面 + 无头底线（T-09 语义升级） | ✅ **已合并** | PR **#23**，squash `0526e975`，**CI 首轮即绿**（2018 tests / 0 failures，含 `SubAgentSurfaceTest` 26 例） |
| **P2-06a** 专家定义数据层（`AgentDefinition` 库 + 解析器 + 合成器） | ✅ **已合并** | PR **#24**，squash `b0fc4972`，**CI 首轮即绿**（2055 tests / 0 failures，含 37 个新单测） |
| **P2-06b** 切换真相源（废弃 `Settings.subAgents`）+ 专家写工具（`subagent_create/update/delete`） | ✅ **已合并** | PR **#25**，squash `81b8ad8c`，**CI 首轮双 run 全绿**（**2060 tests / 0 failures / 0 skipped**，2055 → +5；本地 harness `OK (45 tests)`） |
| **P2-06c** 专家库 UI 完整化（工具面 / MCP / skills / 命名空间选择器 + D9 UI 接线） | ✅ **已合并** | PR **#26**，squash `7b723fca`，**CI 首轮双 run 全绿**（**2088 tests / 0 failures / 0 skipped**，2060 → +28；本地 harness `OK (28 tests)`） |
| **P2-05** 工具调色板（接上 `ToolCatalogSource.LOCAL`） | ✅ **已合并** | PR **#27**，squash `c4d69b74`，**CI 首轮双 run 全绿**（**2103 tests / 0 failures / 0 skipped**，2088 → +15；本地 harness `OK (40 tests)`，含 `ToolCatalogTest` 回归） |
| **P2-07** 预算设置（助手级每编排 token 上限 + 专家覆盖 + 并发 UI） | ✅ **已合并** | PR **#28**，squash `e2aacb1e`，**CI 双 run 全绿**（**2118 tests / 0 failures / 0 skipped**，2103 → +15；本地 harness `OK (15 tests)`） |
| **P2-08** 编排期保活（子 run 全程持有 FGS） | ✅ **已合并** | PR **#29**，squash `6cb3559b`，**CI 三 run 全绿**（push `37091194979` + PR `37091205626` + master `37091872218`；**2124 tests / 0 failures / 0 skipped**，2118 → +6；本地 harness `OK (8 tests)`） |
| **P2-12a** 流式采集补齐（F1：`streamText` 进账本 + 流式点包 context） | ✅ **已合并** | PR **#30**，squash `10cd571f`，**CI 三 run 全绿**（push `37093470273` + PR `37093477515` + master `37094247629`；**2129 tests / 0 failures / 0 skipped**，2124 → +5；本地 harness `OK (5 tests)`；selfcheck 18/18） |
| **P2-12b** 回合视图（footer 升级 + 可展开逐次 + 估算 vs 真实标注） | ✅ **已合并** | PR **#31**，squash `e2a14cef`，**CI 双 run 全绿**（push `37098569754` + PR `37098572227`；**2142 tests / 0 failures / 0 skipped**，2129 → +13；本地 harness `OK (13 tests)`；selfcheck 55/55） |
| **P2-12c** 统计页扩展（按助手 / 日 / 用途 / 模型） | ✅ **已合并** | PR **#32**，squash `4c06a69a`，**CI 双 run 全绿**（push `37102306888` + PR `37102309515`；**2164 tests / 0 failures / 0 skipped**，2142 → +22；本地 harness `OK (22 tests)`；selfcheck 122/122） |
| **P2-12d** 编排树页（父→子；入口挂统计页）+ **子 run 归属写入** | ✅ **已合并** | PR **#33**，squash `395ac707`，**CI 双 run 全绿**（push `37104995022` + PR `37105001462`；**2190 tests / 0 failures / 0 skipped**，2164 → +26；本地 harness `OK (26 tests)`；selfcheck 149/149） |
| **P2-13** 预算执行与 usage 回传（派发前查汇总 → 超限拒派；子跑完回传 usage 摘要） | ✅ **已合并** | PR **#34**，squash `217df6b7`，**CI 三 run 全绿**（push `37107283538` + PR `37107291788` + master `37108064782`；**2206 tests / 0 failures / 0 skipped**，2190 → +16；本地 harness `OK (16 tests)`；selfcheck 67/67） |
| **P2-14** 遗留清单清扫（取消兜底 / 列表 usage / 闸门显式化 / 预算页脚） | ✅ **已合并** | PR **#35**，squash `f518a65d`，**CI 三 run 全绿**（push `37110355416` + PR `37110360533` + master `37111117701`；**2217 tests / 0 failures / 0 skipped**，2206 → +11；本地 harness `OK (33 tests)`；selfcheck 67/67） |
| **P2-15** §9.2 #7 后半条（剩余额度进派发信封） | ✅ **已合并** | PR **#36**，squash `e394e221`，**CI 双 run 全绿**（push `37113278363` + PR `37113286116`；**2223 tests / 0 failures / 0 skipped**，2217 → +6；本地 harness `OK (15 tests)`；selfcheck 43/43） |
| **P2-26** D6（剩余部分）统计页四维卡补 `tok/s` | ✅ **已合并** | PR **#47**，squash `e45e383c`，**CI 双 run 全绿**（push `37144065910` + PR `37144070287`；**2245 tests / 0 failures / 0 skipped**；本地 harness `OK (35 tests)`；selfcheck 12/12） |
| **P2-27** D3 音频 / 视频输入（入口 + 传输 + 子智能体） | ✅ **已合并** | PR **#48**，squash `100f226e`，**CI 双 run 全绿**（push `37145532436` + PR `37145539595`；**2260 tests / 0 failures / 0 skipped**；本地 harness `OK (10 tests)`；selfcheck 20/20） |
| **P2-28** D4（分组半）四个协议级开关拆入「高级 / 实验功能」卡 | ✅ **已合并** | PR **#49**，squash `664bba35`，**CI 双 run 全绿**（push `37147889428` + PR `37147894187`）；selfcheck 13/13（无新单测，纯 UI 移位 + 三语 2 串） |
| **P2-29** D5（残留）`mcp_update` 省略 `enabled` 时保留原状态 | ✅ **已合并** | PR **#50**，squash `695778a3`，**CI 双 run 全绿**（push `37148688416` + PR `37148690107`）；selfcheck 8/8（无新单测，工具描述 + 默认值修正） |

> ★ **2026-10-03~04 验收修复批次（ACCEPT-FIX）+ 后续卡**（正本＝`ACCEPT-FIX.md`，此处只登记编号与 sha）：P2-16 `13d518c0` · P2-17 `7a09b759` · P2-18 `b3436497` · P2-19 `bafddbe2` · P2-20 `44a5db0b` · P2-21 `1dec9d2d` · P2-22 `9ff6821c` · P2-23 `84e2a288` · P2-24 `8ef376bb` · P2-25 `5a591ed2` · P2-26 `e45e383c` · P2-27 `100f226e` —— 对应 D11 · D8/D9 · D6 · D1 · D12 · D4 · D5 · D7 · D10 · D6（剩余） · D3 **全部收口**。
> ★ **2026-10-04 收尾两卡**：P2-28 `664bba35`（D4 分组半）+ P2-29 `695778a3`（D5 `mcp_update` 残留）—— **D4 / D5 至此全收口**（i18n 由 P2-20，安装类放行由 P2-22）。
> **当前 master＝`695778a3`，50 个 PR 全 merged，0 open PR / 0 open issue。**

**P2-11a 交付**：
- `ai/core/Usage.kt` 新增 `cachedTokensReported` / `cacheMissTokens` / `reasoningTokens`（全部默认 = **未报告**）；`cachedTokens` 保留旧语义（已持久化消息 + SQL 统计投影 `$.usage.cachedTokens` 兼容）。
- `merge()` 保留 **provider 报的 total**，仅在缺失时回退求和 —— **已核验对全部 10 条录制流是 no-op**（`total == prompt + completion`，不一致数 0）。
- `ChatCompletionsAPI.parseTokenUsage` 为 OpenAI 兼容方言填来源字段（OpenAI 嵌套 / Moonshot 顶层 / DeepSeek `prompt_cache_hit_tokens` + `prompt_cache_miss_tokens`）。
- 测试：`UsageTest` +6、`ChatCompletionsUsageParsingTest` +1。
- 默认路径**零行为变化**（除 OpenAI 兼容解析器外，无生产代码填充新字段）。

**P2-11b 交付**：8 个生产者全部补齐来源字段（Google 流式+非流式 / ChatCompletions 流式 / Responses 流式+非流式 / Claude 流式+Provider）；`TokenUsage` 新增 `cacheWriteTokens`（Anthropic 缓存写入，原本被解析后丢弃）；`ClaudeProvider.sum()` 不再丢字段与 cost；Claude 流式解码器遇显式 `null` 不再抛异常；3 个新测试文件。

**下一步 = P2-11c**：① 采集层单点（`ai/ui/StreamChunkHandler.kt:338`）+ `purpose` 分类；② `UsageRecord` 表（Room + 迁移）。之后 **P2-11d**：导出与价格表。

### P2-11c 用量账本存储层 ✅ 已合并（2026-10-02，PR #15 / squash `9445f178`）

交付：`app/src/main/java/com/clma/app/data/usage/` 五个新文件 + 一个单测（+396 行，**零既有文件改动**）。

- `UsagePurpose`（12 类：MAIN/TOOL_LOOP/COMPACTION/TITLE/SUGGESTION/MEMORY_EXTRACT/SUBAGENT/CRON/WORKFLOW/SKILL_TEST/TRANSLATION/UNKNOWN，按枚举名入库）。
- `UsageRecordEntity`（`usage_records`：**一次模型往返一行**，只存元数据不存消息内容；带 P2-11a 的 provenance 列，`cached_tokens_reported=false` 才能把「没报」与「报了 0」分开；成本列留空、写入时冻结，待 P2-11d 价格表）。
- `UsageRecordDao`（insert / latest / since / count / 按编排根汇总 token＝P2-13 预算闸输入 / 保留期删除）。
- `UsageLedger`（写入 + 每天最多一次 90 天清扫；`UsageRecordMapper` 纯映射 + 保留期算术，可脱 Room 单测）。
- 单测 `UsageRecordMappingTest` 4 例（逐列映射含可空 provenance / 未上报缓存保持未上报 / purpose 往返 / 90 天边界）。

★ **架构决定：独立库文件 `usage_ledger.db`，不并入 `AppDatabase`**。原因：`ImportedDatabaseReconciler` 把 DB 版本 / Room **identity hash** / fork-only 表 DDL 三者硬绑，且 `ImportedDatabaseReconcilerTest` 从 `app/schemas/com.clma.app.data.db.AppDatabase/NN.json` 直接比对（版本号、identityHash、文件名、AppDatabase 声明四处）；而 identity hash **只能由 Room 编译器产出**，本地无 Android SDK 无法预生成 ⇒ 并入主库必然烧两轮 CI，且那条路有 issue #105 的崩溃史。独立库：遥测永不碰主库迁移链，保留期清扫碰不到用户消息；账本无外键不 join，按 id 读即可；版本停 1，形状变了整库丢弃重建。代价 = 不能跨库 SQL join，统计/导出改内存 join。**若日后要并回主库**：两轮 CI 换 hash + 把新表 DDL 手写进 reconciler 的表清单。

本地验证：给 VPS kotlinc 工具链补 `room-common-jvm-2.8.4.jar`（★ **DAO 注解 `@Dao/@Query/@Insert` 在 `-jvm` 变体；实体注解 `@Entity/@ColumnInfo/@Index/@PrimaryKey` 在 `room-common`**，两颗不是一回事）→ 真编译 + `OK (4 tests)`；顺手修了 `ktest.sh` 的缺陷（原先编译失败仍会继续跑 JUnit，导致报「class not found」而非真因，现先判「有没有产出 class」）。CI 首轮即绿。

**P2-11c2（采集接线）待做**：★已实核 `providerManager.getProviderByType(...)` 是**全 app 唯一的 provider 解析点** —— `GenerationLoop.kt:509`、`ChatService.kt:2353/2403/2896`、`TranslationHandler.kt:37` 五处文本生成调用点全走它 ⇒ 在该处套 `Provider` 装饰器（Kotlin 接口委托）即可**一次覆盖主回合 / 标题 / 建议 / 压缩 / 翻译**，无需改任何调用点；`purpose` 由环境上下文注入（主回合内的 `stepIndex>0` 判定 TOOL_LOOP）。

### P2-11c2 采集单点接线 ✅ 已合并（2026-10-02，PR #16 / squash `147195f9`）

**接在哪**：全 app 文本生成调用点只有五个，全部经 `providerManager.getProviderByType(...)`（`GenerationLoop:509` 主回合 / `ChatService:2353,2403,2896` 标题·建议·压缩 / `TranslationHandler:37` 翻译）⇒ 装饰器挂在 **provider 解析处**，零调用点改动。

- `ai/ProviderManager`：新增可选 `providerDecorator: ((Provider<*>) -> Provider<*>)? = null`，挂在**最底层 `getProvider`**（⇒ `getProvider` 与 `getProviderByType` 各覆盖一次、不重复包装），默认 null 原样返回 ⇒ 未装修饰器的路径逐字节不变。
- `app/.../data/usage/UsageRecordingProvider.kt`：Kotlin 接口委托包住 provider，只覆写 `generateText`；**透传**（delegate 结果原样返回），写库失败吞掉并打日志 —— 聊天回合绝不因遥测失败而失败。
- `UsageCallContext.kt`：走**协程上下文**（`AbstractCoroutineContextElement`）而非 Provider 接口 —— 因为「目的」只有调用点知道，且一个回合与它的工具续跑**共用同一 provider 实例**。未声明 = `UNKNOWN`，**绝不默认 MAIN**（没接线的路径必须可见）。
- `UsageCallRecorder.kt`：决策表单点可测 —— 有 usage 写一行 / **完全没报 usage 则不写**（写 0 行等于撒谎且污染均值）/ 写库抛异常 → `Failed` 不影响调用 / `CancellationException` 原样抛（遥测不许吞取消）/ 无 context → `UNKNOWN`。
- 目的标注：`GenerationLoop` 主回合 `MAIN`、工具续跑 `TOOL_LOOP`（`generateInternal` 新增 `stepIndex: Int = 0` 参数 + 唯一调用点传参）；`ChatService` → `TITLE`/`SUGGESTION`/`COMPACTION`；`TranslationHandler` → `TRANSLATION`。
- 单测 `UsageCallRecorderTest` 4 例（假 DAO）。**本地真跑测 `OK (4 tests)`**；CI 首轮即绿。

★ 手法：多行调用的包裹用**括号配对扫描**（含字符串内括号跳过）改，而不是靠字符串匹配；改完逐处 `git diff -U2` 回读（含 `withTimeout` 内嵌那处的括号平衡）。

**P2-11 剩余：11d** —— 导出 CSV/JSON + 价格表（provider 级按 model + 峰谷；agent 注入入口需 ALWAYS_ASK + 审计 + 整表版本替换），成本**写入时冻结** `priceVersionId`/`costMicros`（列已在表里，待填）。

### P2-11d-1 成本计算 + 写入时冻结 ✅ 已合并（2026-10-02，PR #17 / squash `b1a87761`）

**价格不内置任何数字**（硬编码价目表会过期，错的数字比没有更糟）⇒ 三级来源：`Model.pricing`（新增，设置页可编辑）→ `Model.pricePromptPerToken/priceCompletionPerToken`（provider 公布的逐 token 元数据，如 OpenRouter `/models`，开箱即算）→ **都没有则记 null，绝不记 0**。

**峰谷建模**（按 M09 已核实的 DeepSeek 真实口径：北京时间周一至周五 09:00-12:00 与 14:00-18:00 为高峰，其余半价 ⇒ **两个窗口**）：
- `PeakWindow(startMinute, endMinute, zoneOffsetMinutes, daysOfWeek)` —— **存 UTC 偏移不存 IANA 时区 id** ⇒ 解析器纯整数运算、不依赖日历库；支持**跨零点窗口**；ISO 周一为首日（1970-01-01 周四 ⇒ +3 移位）。
- 部分填写的 off-peak 行**继承**未覆盖的峰值数字。

**★ 计费不变量（易错）**：缓存命中与缓存写入**都计在 `promptTokens` 之内**（P2-11a/b 归一：Claude = input + cacheRead + cacheWrite；OpenAI/DeepSeek 明细内嵌）⇒ `计费输入 = prompt − cached − cacheWrite`，避免重复计费；缓存读/写价未设时**回退输入价**（不回退到免费）。

**写入时冻结**：`UsageRecordingProvider` 调用返回后立刻算 `costMicros` + `priceVersionId` 写入行内（编辑价格不能改写历史）。**版本 id 由费率内容派生**（`分支|四价` 的 SHA-1 前 12 位）—— 改价才变，无需手动 bump，自带「整表版本」语义。

**验证**：本地 `OK (18 tests)`（`UsagePriceResolverTest` 13 例：窗口内外、**边界排他**、周末过滤与周一恢复、跨零点、部分 off-peak 继承、无价目→null、元数据回退、缓存读不重复计、缓存写只计一次、未设缓存价回退、版本 id 稳定性与敏感性；`UsageCallRecorderTest` +1 例冻结成本落行）。CI 绿（本轮 ~13 分钟）。

**11d 剩余**：①导出 CSV/JSON 到 `/workspace/exports/`；②设置页 UI（模型提供商页每模型价目 + 峰谷窗口 + 校验）；③agent 注入入口（ALWAYS_ASK + 审计 + 整表版本替换）。

---

## P2-11d-2 / d-3 / d-4 完成记录（2026-10-02）

### 11d-2 导出 —— PR #18，CI 首轮绿
- 新工具 `usage_export` + 纯逻辑 `UsageExport.kt`（8 单测）：RFC 4180 转义、列序与表头同源、null→空单元格、文件名确定性（`usage-ledger-20261002T133000Z.csv`）、JSON 往返仍能区分 `cachedTokensReported=false`。
- 落点 `~/exports/`（`AgentWorkspace.expand` ⇒ `<filesDir>/workspace/exports/`）。
- 新工具组 `LocalToolOption.UsageLedger`（`@SerialName("usage_ledger")`）**默认关**，不给每个会话白塞 schema token；设置页助手本地工具加一行开关（en+zh）。
- 信封：files / rows / truncated_at_limit / window / totals（含 `costed_rows`、`unpriced_rows` —— 未定价 ≠ 0）。
- **刻意不进 ALWAYS_ASK**：只读本地账本 + 只写 agent 自己的 workspace。

### 11d-3 价格注入入口 —— PR #19
- 两个工具：`usage_get_prices`（只读免审批：整表 + 内容派生版本号 + 未定价模型清单）、`usage_set_prices`（整表替换）。
- 写路径：`replace_all_prices=true` 保险栓（模型搞错语义时回 `confirmation_required`，不静默清空）→ `UsagePriceTable.validate` → **先**开 `agent_runs` 审计（新 kind `price_table`）→ 有拒绝项则 audited `failed` 且零写入 → 应用成功 `succeeded`。
- 纯逻辑 `UsagePriceTable.kt`（11 单测）：无任何费率 / 负数 / 非有限 / 重复键 / 窗口越界各一条明确 reason；payload↔`ModelPricing` 映射；整表版本号内容派生（与 per-call 同 12 位十六进制）。
- 替换按 **provider/model UUID** 定位，同名 provider 不会串价；表中缺席的模型 `pricing=null`（未定价）而非 0。
- ★ **CI 首轮红**：`UsageTools.kt:434 Unresolved reference 'copy'` —— `ProviderSetting` 是 sealed，`copy(models=…)` 不存在于父类型。改用项目既有 `ProviderSetting.editModel(model)`（按 model.id 替换）。已修并复跑。
- ★ 教训：sealed 父类不能泛化 copy；改 provider 的 models 一律走 `addModel / editModel / delModel / copyProvider`。

### 11d-4 设置页价目 UI —— PR #20
- 模型表单第 4 个 tab「价格」：8 个费率（输入/输出/缓存读/缓存写，另有空闲时段同四项，开关控制）+ 高峰时段一行文本。
- 数字**以文本保存再解析**：半打完的 `2.` 不会覆盖已存费率，只有解析通过才写；空 = null，不写 0。
- 高峰窗口文本语法（纯逻辑 `UsagePeakWindowText.kt`，10 单测）：`1-5 09:00-12:00, 14:00-18:00 @+08:00`；跨周（`5-1`）、跨零点（`22:00-02:00`）、偏移 `±HH:MM` 解析与范围校验、整周/连号压缩、格式化↔解析往返。
- 校验提示与 `usage_set_prices` 同一句（都走 `UsagePriceTable.validate`），表单拒绝的正是账本会拒绝的。
- ★ 教训：`kotlinx.serialization.json.Json` 会被"子串包含"式检查误判为已存在（`...json.Json` ⊂ `...json.JsonObject`）⇒ 补 import 要按整行匹配。
- ★ 教训：CI 只在 `push: master|feat/**` 与**目标为 master 的 PR** 上触发 ⇒ 堆叠 PR 必须 rebase 到 master + 把 base 改成 master，否则永不触发。

### 工作法沉淀
- 本地 kotlinc harness 本轮抓到 3 个真错（两处我写错的期望值 + 一次纯逻辑依赖漏传没产出 class）；**CI 首轮**抓到 1 个真错（sealed copy）+ 1 个自伤（资源串只写了一个语言）。
- 三卡坚持"纯逻辑先本地测、Compose/Android 走 CI"：纯逻辑侧共 **29 个新单测**（8 + 11 + 10）。

### 收口（2026-10-02 16:30 北京 / 08:30 UTC）

三卡全部合并进 master：**11d-2 #18 → `31aa2fd0`**、**11d-3 #19 → `f8145014`**、**11d-4 #20 → `51f56908`**。**当前 master = `51f56908`**，P2-11 至此收完（a / b / c1 / c2 / d-1 / d-2 / d-3 / d-4 全部落地）。

三卡的 CI 都不是一次就绿：11d-2 首轮绿；11d-3 首轮红（sealed `copy`）→ 修复后绿；11d-4 首轮红（漏写一个语言的资源串，我造成的）→ 二轮红（`PeakWindow` 与 `PriceWindowSpec` 类型不匹配）→ 三轮绿。**下一卡：P2-01**（工具面装配 6→1 + 助手解析单点，纯重构零行为变化）。

---

## P2-01 完成记录（2026-10-02，PR #21 / squash `7a76c2c8`，**CI 首轮即绿**）

纯重构、零行为变化。两个单点，P2-02 / P2-04 / P2-06 都要用。

### ① 工具面装配 6 → 1

原六个 `localTools.getTools(assistant.localTools, …)` 调用点（WorkflowEngine / CronJobWorker / ChatService 快路径 / ChatService rerun / ChatService 常规回合 / ChatToolFactory）各自**手写** `ToolInvocationContext`。T-04、T-09、`show_image` 模态修复每次都要改 4~5 处字面量，漏一处就漂移。

- ✨ `data/ai/ToolSurfaceResolver.kt`：`resolve()` 是**全 app 唯一** `LocalTools.getTools` 调用者；`chatContext` / `fastPathContext` / `headlessContext` / `contextless` 是真实调用点构造上下文的唯一入口。
- ✨ `data/ai/tools/ToolInvocationContexts.kt`：上下文**字段值**的唯一来源；**只吃基本类型**（不依赖 `Assistant`/`Model`），故可在裸 JVM 上单测。

**grep 证据**：`grep -rn "localTools.getTools(" app` → 仅 1 处命中，在 resolver 内。三种上下文形状**逐字段复刻**（含 fast-path 的 `isHeadless=false`、默认 `modelCanSeeImages=true`、以及"模型看不到 schema"的路径不带模型派生标志）。

### ② 助手解析 多 → 1

✨ `data/ai/AssistantResolver.kt`：`byId` / `current` / `forConversation` / `forWorkflow`，`forWorkflow` 吸收 workflow 引擎的「持久 authoringId → 扫第一个带 Workflows 的助手」兜底，并返回 `Source`（故"兜底要大声记日志"的条件精确保留）。

- `Settings.getCurrentAssistant` / `getAssistantById` / `findAssistantById` 三个扩展**改为委托** → 光这一条就把 ~40 个 UI 调用点收进同一实现。
- 引擎侧逐个改道：ChatService ×5、ChatList、SubAgentEngine ×2、CronJobWorker、WorkflowEngine、ConversationRoutes ×2、AssistantDetailVM ×2、DoctorChecks。
- ★ 语义差异显式化：`getAssistantById` 可空，而 `getAssistantById(id) ?: getCurrentAssistant()` 会**静默换一个别的助手**；现在这条差异体现在 API 上（`byId` vs `forConversation().assistant`），不再是各调用点的偶然。

### 验证（硬证据）

- **CI run `36987557848`（push）/ `36987647048`（PR）双绿，14 步全 success**。
- 单测报告实拉核对（artifact `unit-test-report`）：全仓 **1996 tests / 0 failures / 0 skipped**；新增三个类逐类计数 —— `ToolSurfaceResolverTest` 5、`AssistantResolverTest` 6、`ToolInvocationContextsTest` 6（合计 17）。
- ★ **本地 kotlinc harness**（新工法）：用**忠实 stub**（Tool/Model/Assistant/Settings/LocalTools，签名照抄真源码）编译两个真 resolver 文件 + 三个真测试文件，另加一个 **harness-only `SmokeCalls.kt`，逐行复刻真调用点（Android 耦合、本地编不动）的调用形状**——具名实参、重载一个不落。结果：**17/17 绿，smoke 文件编译通过** ⇒ 调用点用的每个参数名/重载都确实存在。
- ★ harness 抓到 1 个真错：`AssistantResolverTest` 里我把 `current(assistants, 未知id)` 的期望写成第二个助手，实际是**第一个**（兜底语义）。

### 刻意未覆盖（写进 PR）

`ChatService` 在 local 面之上叠的**宽 chat 面**（workspace / cold-memory / skill / MCP / compaction）仍有**两处**装配点。统一它们要把四个真实行为差异（search 门控、MCP 非法名的处理、渐进目录、compaction）参数化 —— 那就不是零风险重构了，留给后续卡。

### 工法沉淀

- **stub + smoke 编译法**：Android 耦合文件（ChatService 等）本地编不动，但把**新文件**的真依赖用忠实 stub 替掉、再用一个 smoke 文件复刻调用形状，就能在本地把「import / 重载 / 具名实参」这类错误一次扫掉，不必烧 CI 轮次。
- **patch 器纪律**：改既有文件用 python 脚本 `(file, old, new, count)` + **count 断言**，改前全量读、改后才落盘 —— 一个不匹配就整批不写，杜绝半途而废的半重构。
- ★ VPS 上 `git push`：`http.extraHeader` 方式**不行**（报 `could not read Username`），必须用 URL 内嵌 `https://x-access-token:$TOKEN@github.com/...`（TOKEN 从 `/opt/mcp-gw/env` 读，不落屏）。
- ★ 经 `vps.sh` 传 heredoc 有坑：本地单引号包裹的整串里不能再出现 `<<'EOF'` 的单引号；提交信息这类多行文本**先写本地文件、base64 上传到 /tmp、再 `git commit -F`**。

---

## P2-02 完成记录（2026-10-02，PR #22 / squash `4e68d9b6`，**CI 首轮即绿**）

**目标**：`Assistant.disabledLocalTools: Set<String>`（默认空 = 回退现状）；落点 `LocalTools.getTools()` 末尾唯一收口点；UI 在 `AssistantLocalToolPage` 逐项开关。**红线**：空集时逐字节等价。

### 交付
- **数据模型**：`Assistant.disabledLocalTools: Set<String> = emptySet()`，**追加在最后**（本项目「新字段不得移动既有字段位置」约定）。
  - 标注 `@EncodeDefault(EncodeDefault.Mode.NEVER)`：`JsonInstant` 虽是 `encodeDefaults = true`，但该字段取默认值时不写入 → 既有助手持久化 JSON **逐字节不变**。**探针实测**：空默认 `{"a":1}`（字段不出现）/ 非默认出现 / 缺失取默认。
- **收口点**：`LocalTools.getTools(options, invocationContext, disabledToolNames = emptySet())`，在 `needsApproval` 映射之前 `LocalToolFilter.removeDisabled(tools, disabledToolNames)`。
  - **空集 = 完全不动**（不 mutate，直接返回原列表）⇒ 工具面逐字节等价。
  - **原地删除**（非新列表）：因为 `workflow_create` 在同一方法内以 `knownToolNamesProvider = { tools.map { it.name } }` 闭包捕获了**同一个列表**，必须让它也看到过滤后的集合（否则会把已关闭的工具名当「已知工具」）。
- **纯逻辑**：`LocalToolFilter`（无 Android 依赖，裸 JVM 可测）；`ToolSurfaceResolver.resolve()` 透传 `assistant.disabledLocalTools`。
- **UI**：`AssistantLocalToolPage` 末尾新增「Individual tools / 逐工具开关」区块，每个工具一行 Switch；工具名**从活的 `LocalTools.getTools(assistant.localTools)` 枚举**（不硬编码表）⇒ 与实际下发给模型的工具面**不可能漂移**。新增 2 条字符串（`values/` + `values-zh/`，其余语言回退英文；各语言条数本就不齐，无一致性校验）。
- **单测**：`LocalToolsFilterTest`（6，纯 JVM：空集同实例 / 删除 / 保序 / 未知名忽略 / 全删空 / 幸存者同一性）；`AssistantDisabledToolsSerializationTest`（2：默认不写 key；非默认可回环）。合计 +8。

### 验证硬证据
- CI 双 run 全绿：push `36990371044` + PR `36990406189`。
- artifact `unit-test-report`：全仓 **2004 tests / 0 failures / 0 skipped**（较 P2-01 的 1996 +8）；`LocalToolsFilterTest` 6/0/0、`AssistantDisabledToolsSerializationTest` 2/0/0。
- 本地 stub + smoke harness：真 `LocalToolFilter.kt` / `ToolSurfaceResolver.kt` + 真测试编译通过，**6/6 绿**；具名实参 `options=/invocationContext=/disabledToolNames=` 对得上签名。

### 已知取舍
- 该开关只覆盖 **local 工具**（`getTools` 出口）；MCP / workspace / cold-memory / skill / compaction 等更宽的 chat 面不在本卡范围（与 P2-01「未统一的 2 处装配点」同一理由）。
- UI 枚举用 `getTools(assistant.localTools)`（默认空 deny-list）以获得**完整**工具清单；被关闭的工具仍需展示才能被重新打开。

**下一卡 = P2-04**（子 agent 独立工具库 + T-09 语义升级；依赖 P2-01 的助手解析单点）。

---

## P2-04 完成记录（2026-10-02，PR #23 / squash `0526e975`，**CI 首轮即绿**）

**目标**：`SubAgentProfile` 带**自己的工具面**（可含父未启用的本地工具与 MCP），把子 agent 的约束语义从「子 ⊆ 父」升级为「**无头安全底线**」——只有逐次确认类 / 隐私类 / UI 绑定类工具谁都不可无人值守跑，父子关系不再是上限。

### 本轮拍板的三个口径（用户确认）

| 问题 | 决定 |
|---|---|
| 底线生效范围 | **守红线**：仅当「父助手开了 `enableSubAgentToolSurface`」**或**「该 profile 配了自己的工具面」时套底线；两者都不满足 = 今天不过滤（逐字节等价） |
| profile 字段范围 | 只加 `localTools` + `disabledLocalTools` + `mcpServers`（技能 / 工作区 / 记忆 / 命名空间留给 P2-06 的 `AgentDefinition`） |
| UI 归属 | 本卡**纯引擎 + 数据 + 单测，不做 UI**；配置界面由 P2-06 专家库统一做；真机实测放到最后一起深度测试 |

### 交付

- **数据模型**：`SubAgentProfile` 追加 3 个**可空**字段（末尾、「新字段不得移动既有字段位置」），`null` = 沿用父（= 今天）：
  ```kotlin
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  @Serializable(with = LenientLocalToolListSerializer::class)
  val localTools: List<LocalToolOption>? = null,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  val disabledLocalTools: Set<String>? = null,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  val mcpServers: Set<Uuid>? = null,
  ```
  `@EncodeDefault(NEVER)` ⇒ 既有 profile 持久化 JSON 逐字节不变（`JsonInstant` 是 `encodeDefaults = true`）。**探针实测**：可空属性 + 自定义 `KSerializer` 可编译并工作；取默认值时 key 不写出、非默认写出、缺失 key 解码取默认。
- **✨ `subagent/SubAgentSurface.kt`（新）**：`resolveChildAssistant(parent, profile)` —— 三字段全 null / 无 profile / 无父助手 ⇒ 返回 `null`（= 原样继承父助手）；否则合成一个子助手（**id 与所有非工具字段沿用父**，只覆写三个工具面字段）。冻结记录从 `SubAgentToolSurface` 移到这里并长出 `assistant` 槽：`FrozenSurface(assistant?, requested?)` + `freeze / release / frozenFor / assistantFor / apply / clearAll`。`apply` 逐工具跑底线。
- **`SubAgentToolSurface.kt` 重写为纯策略**：只剩 `UI_BOUND_TOOL_NAMES` / `PRIVACY_SENSITIVE_TOOL_NAMES` / `INTERNAL_TOOL_PREFIX` / `ASK_USER_TOOL_NAME` / `denialReason` / `isDenied` / `safeNames`。命中顺序未动：`subagent_` 前缀 → `ask_user` → UI 绑定类 → `NO_ALWAYS_ALLOW` → 隐私类。
- **`SubAgentEngine.executeRun`**：算一次 `parentAssistant`，再 `childAssistant = SubAgentSurface.resolveChildAssistant(parentAssistant, profile)`；冻结条件改为 `freezeToolSurface || childAssistant != null`；`requested` 仍**只由** `enableSubAgentToolSurface` 门控（那个参数的 schema 只在该开关打开时才描述给模型）；`finally` 改 `SubAgentSurface.release(conv.id)`（释放无条件、未写记录时是 no-op）。
- **`ChatService` 两处装配点**（rerun / 常规回合）：各引入 `val surfaceAssistant = SubAgentSurface.assistantFor(conversationId) ?: assistant`，本地工具 / workspace / skills / coldMemory / compaction / `toolSurfaceMode` 全改读 `surfaceAssistant`；MCP 改**条件重载**（继承子继续走全局当前助手口径，只有显式带面的子才按自己的 `mcpServers` 解析）；末尾改 `SubAgentSurface.apply`。
- **`McpManager`**：抽出 `private fun availableToolsFor(assistant)`，新增 `getAllAvailableTools(assistant: Assistant)` 重载；**无参重载行为逐字节不变**（仍用 `settings.getCurrentAssistant()`）。这是 P2-01 之后仍然存在的「MCP 按全局当前助手过滤」怪癖的定向修复，不动既有调用者。
- **单测**：`SubAgentSurfaceTest` **26 例**（子面合成 9 + 冻结注册表 14 + 持久化 3）；`SubAgentToolSurfaceTest` 从 19 例裁为**纯策略 7 例**（注册表测试整体搬进 `SubAgentSurfaceTest`）。净 +14。

### 验证硬证据

- **CI 双 run 全绿**：push `36994667922` + PR `36994695964`，均 `BUILD SUCCESSFUL`，`:app:testDebugUnitTest` 在列。
- artifact `unit-test-report` 实拉核对：全仓 **2018 tests / 0 failures / 0 skipped**（较 P2-02 的 2004 **+14**）。逐类：`SubAgentSurfaceTest` 26/0/0、`SubAgentToolSurfaceTest` 7/0/0（旧 19）。
- **本地 kotlinc harness**（`p204/harness.py`）：忠实 stub + 真 `SubAgentRun.kt` / `SubAgentToolSurface.kt` / `SubAgentSurface.kt` / `ToolApprovalDefaults.kt` / `Json.kt` + 真两个测试文件 ⇒ **`OK (33 tests)`**（26 + 7），退出码 0。

### 残留引用核对

- `grep` 全仓：`SubAgentToolSurface.apply/freeze/release/frozenFor/clearAll/FrozenSurface` **0 命中**；策略调用只剩 `SubAgentTools.kt:212/217`（`isDenied` / `denialReason`）与 `SubAgentEngine.kt`（`safeNames`）。

### 工法沉淀

- **`@EncodeDefault(NEVER)` + 可空 + 自定义序列化器**的组合先用一次性 probe 在 VPS 上跑通再接生产（省掉一轮 CI）。
- **patch 器锚点要防「子串误伤」**：`addAll(createWorkspaceToolsIfReady(...))` 在 8 空格与 20 空格缩进处各出现一次，纯子串匹配会命中 2 处 ⇒ 锚点带前导 `\n` 与精确缩进。计数断言再次救场（`expected 1, found 2` → 整批不写）。
- **harness 的三个坑**：① 一个文件不能含两个 `package`；② 编真 `ai/core/Tool.kt` 会触发 kotlinx.serialization **编译器插件内部错误**（其 `execute` 用到 `UIMessagePart`），止损办法就是把它也换成 stub；③ 漏 stub 的引用会以「unresolved reference」形式暴露，逐个补齐即可。
- ★ 堆叠 PR 陷阱（继承 P2-11d-4 教训）：本卡直连 master，无堆叠，CI 首轮即触发。

**下一卡 = P2-06**（专家定义：`AgentDefinition` Room 实体 + `subagent_*` 管理工具 + 专家库 UI；依赖 P2-01 的助手解析单点，并把 P2-04 的三个字段收编进专家定义）。

---

## P2-06 专家定义 —— 拆卡与口径（2026-10-02 拍板）

**三个口径（用户确认）**
1. **库落点**：独立 Room 库文件 `agent_definitions.db`（仿 P2-11c 的 `usage_ledger.db`）。零 `AppDatabase` 迁移风险（identity hash 只有 Room 编译器能产出 ⇒ 并入主库必烧 CI 轮次）；代价＝不进聊天备份导入/恢复链路。**注意与账本的区别：定义是用户数据**，形状变了要写真迁移，不能丢库重建。
2. **拆 3 个 PR**：
   - **P2-06a**（已合并）数据层 + 「定义 → 助手」合成器 + 名字解析器 —— **纯加法**
   - **P2-06b** 切换真相源（**废弃** DataStore `subAgents`）+ `subagent_create/update/delete` + `ALWAYS_ASK` + `agent_runs` 审计（`kind=agent_def_write`）
   - **P2-06c** 专家库 UI 完整化（工具面 / MCP / skills / 命名空间选择器）+ D9 命名空间接线
3. **旧 profile**：**直接废弃** DataStore 的 `subAgents` key，只认新库（旧的需要手工重建）。

## P2-06a 完成记录（2026-10-02，PR #24 / squash `b0fc4972`，**CI 首轮即绿**）

**纯加法**：8 个新文件 + `DataSourceModule.kt` **+11 行**（3 个 `single`）。`SubAgentProfile` / `Settings.subAgents` 本卡不碰。

### 交付
| 文件 | 行 | 作用 |
|---|---|---|
| `data/agentdef/AgentDefinition.kt` | 117 | `@Entity("agent_definitions")`；`localTools`/`disabledLocalTools`/`mcpServers` 沿用 P2-04 的 **null = 继承父**，扩到 `skills` 与 D9 `slug`；id 一律存 **字符串**（不引入实验性 uuid 类型、不钉死序列化器） |
| `data/agentdef/AgentNamespace.kt` | 96 | D9 slug / 路径纯函数 |
| `data/agentdef/AgentDefinitionConverters.kt` | 39 | `@TypeConverter`：`List<LocalToolOption>?` / `Set<String>?` ↔ TEXT |
| `data/agentdef/AgentDefinitionDao.kt` | 50 | CRUD；名字 `COLLATE NOCASE`（与解析器同一套「同名」定义）。★ DAO 方法**不能带 Kotlin 默认实参**（Room 生成的是 override，走不到接口的 `$default` 合成） |
| `data/agentdef/AgentDefinitionDatabase.kt` | 50 | 独立库 `agent_definitions.db`，version 1，`@TypeConverters` |
| `data/agentdef/AgentDefinitionRepository.kt` | 148 | 一份缓存伺候两类消费者：UI 要 `StateFlow`，`LocalTools.getTools` **不是 suspend** ⇒ `snapshotBlocking()`（先例＝`BrowserPreferences.snapshotBlocking()`）。`AppScope` 预热，启动期异常只让缓存留空、**不吞 CancellationException** |
| `data/agentdef/AgentDefinitionResolver.kt` | 63 | 从 `SubAgentProfileResolver` 移植，契约与失败文案不变 |
| `data/agentdef/AgentDefinitionSynthesis.kt` | 53 | `toAssistant(parent)` |

### 关键决策
- **序列化复用 `LenientLocalToolListSerializer`**（与 `Assistant.localTools` 同一个）⇒ 两处编码不可能漂移；未知工具子类**逐元素丢弃**而不是整行读不出；`null`（继承）绝不回环成「空」。
- **合成器故意不覆盖** `id` / `systemPrompt` / `chatModelId`：① `id` —— 派生助手从不落库，保留父 id 可让任何误查仍命中真实助手（否则静默回退全局当前助手）；② `systemPrompt` —— 引擎已把专家提示词前置到 task（#36 起），再设一次＝重复投递；③ `chatModelId` —— 优先级归 `resolveSubAgentModel`（显式 `model_id` > 专家 > 父），重写会多出第二个写错的地方。
- **slug 是路径**：不允许任何点号（`..` 拼不出来）、不允许分隔符、长度受限；**输入里的连字符是分隔符而非字面量**（否则 `Deep -- Research` 与 `Deep - Research` 会变成两个命名空间）。全中文名 slug 为空 ⇒ 返回 `""` 让调用方问用户，绝不编一个（两个专家悄悄共用命名空间会互相污染记忆文件）。
- `coldMemoryDir = agents/<slug>/memory`，**在父的工作区内**（workspaceId 继承），且在父未开冷记忆时**完全惰性**。

### 验证硬证据
- **CI 双 run 全绿**：push `36998859171` + PR `36998874929`，**首轮即绿**（KSP 接受了实体/DAO/DB，DI 装配成立）。
- artifact `unit-test-report`：全仓 **2055 tests / 0 failures / 0 skipped**（P2-04 的 2018 **+37**）。逐类：`AgentDefinitionResolverTest` 9、`AgentNamespaceTest` 10、`AgentDefinitionSynthesisTest` 11、`AgentDefinitionConvertersTest` 7。
- **本地 kotlinc harness**（`p206/harness.py`）：真 7 个 main 文件 + `utils/Json.kt` + 4 个真测试 ⇒ **`OK (37 tests)`**。`AgentDefinitionDatabase.kt` 不在 harness 内（要 `androidx.room.Room`/`RoomDatabase`/`Context`/`SQLiteConfiguration`），12 行接线交 CI。

### 顺带修掉的坑
- `LocalToolOption.TimeInfo` 的真实 `@SerialName` 是 **`time_info`**（旧探针里的 `time` 是探针自造的 stub）⇒ 编码断言一开始写错，harness 抓出。
- 反引号函数名里**不能出现 `.`**（`` `.. cannot be spelled` `` 直接编译失败）。
- `slugify` 第一版把输入里的 `-` 当字面量，`"Deep -- Research"` → `deep----research`；已改为「连字符即分隔符」，补 3 条断言。
- `room-common.jar`（/opt/kt/lib）**已含**全部所需注解（`Entity/Dao/Query/ColumnInfo/Index/PrimaryKey/Insert/OnConflictStrategy/TypeConverter/Database`）；但**不含** `RoomDatabase`/`Room`，所以 `AgentDefinitionDatabase.kt` 本地编不了。

**下一卡 = P2-06b**（切换真相源 + `subagent_create/update/delete` + `ALWAYS_ASK` + `agent_runs` 审计 `kind=agent_def_write`）。

---

## P2-06b 完成记录（2026-10-02，PR #25 / squash `81b8ad8c`，**CI 首轮双 run 全绿**）

**口径（用户 ask_user 全 A）**：① roster = **扩展现有 `subagent_list` 加 `kind`**(`runs`默认/`experts`/`all`)，**不新增工具**；② 专家的「自有面」= `localTools`/`disabledLocalTools`/`mcpServers` **+ `skills` + D9 `slug`**（命名空间在 06b 即生效）；③ 无头一律拒绝写工具。

**改动面**：23 文件 / **+1547 −630**；8 个全量替换文件 + `patch.py` 27 处外科补丁 + 2 处整块切除 + 1 删除。`p206b/{patch,harness}.py|commit.txt|pr_body.md|mkpr.py`。

### A. 真相源切换
- `SubAgentEngine` 注入 `AgentDefinitionRepository`，`executeRun` 用 `resolveByName`（**先 refresh 再解析**，不跟写竞态）；`SubAgentProfileResolver` **删除**。
- **`SubAgentModelResolver` + `resolveSubAgentModel` 从 engine 抽出成独立文件**（105 行，逻辑一字未改）。★动机＝让**纯 JVM harness 能编译解析器与优先级函数**，不必拖 `ChatService` / Koin / `android.util.Log` / 整个 engine 类。`resolveSubAgentModel` 参数由 `SubAgentProfile?` 改 `AgentDefinition?`，其 `modelId` 是 canonical Uuid **字符串** ⇒ 在此 `Uuid.parse`（解析失败 → 退化为「继承」而非让 dispatch 失败）。
- `SubAgentSurface.resolveChildAssistant/hasOwnSurface` 改吃 `AgentDefinition?`，**overlay 交给 06a 的 `toAssistant`**（表面与同一定义的 dispatch 不可能漂移）；`hasOwnSurface` 纳入 `skills` 与**可用**的 slug（`normalizeSlug != null`）。
- `SubAgentTools`：dispatch 工具列 enabled 专家；`subagent_list` 加 `kind`。★**`kind` 只在「至少一个专家存在」时才 OFFER** ⇒ 没专家的安装发送的 schema 与 pre-06b 逐字节相同；`kind=runs` 输出也逐字节不变。
- `PreferencesStore` 5 处删净（key/字段/import/读/写），`Settings.subAgents` 消失（`Settings` 定义就在此文件）。`DoctorChecks` 加**可空** `agentDefinitionRepository`（旧测试路径可编译），`subAgentProfileStatus(List<AgentDefinition>, providers)`。`SettingSubAgentsPage` 机械换源（`definitions` StateFlow + suspend 写；String↔Uuid 在 ModelSelector 处转换）。`AppModule` 三处补 `get()`。

### B. 专家写工具（新文件 `subagent/AgentDefinitionTools.kt`，568 行）
- `subagent_create/update/delete`：`needsApproval = { true }` + 进 `ALWAYS_ASK`（专家落库即可被 dispatch、删除会改变所有后续 `agent` 名的解析 ⇒ 必须人审）。
- **每次写开一条 `agent_runs`（`AgentRunKind.AgentDefWrite`）**，成功/失败都 markTerminal；**只存元数据**（op / id / 变更字段名），账本永不存 prompt 文本。
- 重名（大小写不敏感）在 create/update 直接拒；`update`/`delete` 支持 id **或** name；`update` 走 `repository.update`（行不存在 → `unknown_expert`，陈旧 id 不能复活已删行）。
- `encodeDefinition`：★**省略是语义**——留在「继承父」的字段**省略而非发 null**，否则模型把对象回读回写时会把「继承」变成「清空」。集合成员**排序**（同一专家逐字节稳定）。

### C. 策略面
- `AgentRunKind.AgentDefWrite("agent_def_write")`。
- `HeadlessToolApprovalPolicy` 第 4 组 `EXPERT_WRITE_TOOL_NAMES` + `REFUSED_TOOL_NAMES` 四组并集 + `refusalDetail` 分支（措辞独立）；写工具**入口自守**（`callerContext.isHeadless` → 同款结构化信封），绕过 gate 的直接调用者同样被拒。

### D. 测试
- `SubAgentSurfaceTest` 重写为 `AgentDefinition` 版；新增 `SubAgentModelPrecedenceTest`(6)；新增 `AgentDefinitionToolsTest`(11，`encodeDefinition` 省略/排序/空数组/与 `Assistant.localTools` 同一词汇 + `findNameClash`)；`SubAgentToolSurfaceTest` 显式列出 3 个新写工具名；`HeadlessToolApprovalPolicyTest` 三组→四组；`DoctorChecksRefreshTest` / `PreferencesStoreTest` 跟随退役；`SubAgentProfileResolverTest` **删除**。
- ★**写工具的 `execute` lambda 不做单测**（要真 `SettingsStore` + Room，本模块无 Robolectric）——与 MCP 控制工具同例（见 `McpDispatchVisibilityTest` KDoc）；只测抽出的纯函数。

### 验证硬证据
- **本地 harness `p206b/harness.py`**：真 `kotlinc` + JUnit4，**11 个 stub**（签名抄真源）+ **20 个真源文件** + 3 个真测试 ⇒ **`OK (45 tests)`**（= 28 + 6 + 11）。`AgentDefinitionDatabase.kt` / `SubAgentEngine.kt` 不在 harness 内（要 `androidx.room.Room` / ChatService/Koin/Log），交 CI。
- **CI 双 run 首轮全绿**：push `37006679989` + PR `37006691568`；合并后 master push `37008006217`。artifact `unit-test-report` 实核 **2060 tests / 0 failures / 0 skipped**（2055 → **+5**）；逐类确认 `SubAgentProfileResolverTest` 已不在报告中。
- **harness 抓出的真缺陷**：`AgentDefinitionToolsTest`「an expert keeps its own name through an update」第 2 条 `assertNull` 与第 3 条 `assertEquals` 自相矛盾（`findNameClash` 语义正确，是测试笔误）⇒ 改写成 4 条自洽断言。**省下一轮 13 分钟 CI。**

### 新坑 / 手法
- ★**`grep`/`cut` 管道里嵌 `vps.sh`（= ssh）会吞 stdin**：`find | while read` 循环里第一个 ssh 就把剩余输入吃掉，只上传了 1 个文件 ⇒ 加 ` < /dev/null`。
- Kotlin `Tool(...)` 的 `execute` 必须是**最后一个参数**（尾随 lambda 语法，测试里 `tool(name)` 依赖它）。
- 测试文件里 stub 的 `Assistant` 字段要按**测试实际用到的并集**给：`enableSubAgentToolSurface` / `workspaceId` / `coldMemoryDir` / `enabledSkills` 等。

**下一卡 = P2-06c**（专家库 UI 完整化：工具面 / MCP / skills / 命名空间选择器 + D9 命名空间 UI 接线）。

---

## P2-06c 完成记录（2026-10-02，PR #26 / squash `7b723fca`，**CI 首轮双 run 全绿**）

**口径（用户 ask_user 全 A）**：① 工具面 = **组级开关**（56 个 `LocalToolOption` 组，复用助手页 title 文案）+ 逐工具禁用名单（候选名取自该专家自己的工具组；继承父时以全量目录为候选池）；② 命名空间 = **开关 + 可编辑文本框**，开启时以 `slugify(name)` 自动建议，实时预览 `agents/<slug>/memory`，非法/重名红字且禁止保存；③ **D9 只在专家库露出**（卡片徽章 + 编辑页预览），助手记忆页不动。

**改动面**：6 文件 / **+1219 −46**。2 个新文件 + 1 个全量替换 + 3 个 `strings.xml` 外科插入（en/zh/zh-rTW 各 +23 条）。

### A. 纯逻辑（新文件 `data/agentdef/AgentDefinitionDraft.kt`，349 行）
- **三态显式化**（本卡存在的理由）：四个面字段与命名空间在库里是 `null = 继承父助手`，而开关/输入框表达不了「未设置」。`ownsLocalTools` 等 + `ownX()` / `inheritX()` 是唯二转换，**自有空集绝不塌回 null**（`null` 沿用父 vs `empty` 什么都不给，是两件事）。
- `toggleLocalTool` 按 `LocalToolGroups.all` 顺序落盘（**与点按顺序无关**——`encodeDefinition` 让定义 JSON 对用户可见，不应随点按漂移）；另外三个 `Set<String>` 面用 `toSortedSet()`。
- 命名空间：`suggestedNamespace()` / `namespaceSlug()` / `namespacePreview()` / `namespaceProblem(others)`（`EMPTY` / `DUPLICATE`）/ `nameClash` / `canSave` / `withNamespace(seed)` / `clearNamespace()`。**原始输入与归一化 slug 分开存**：输入框回声用户所打（含尚不合法的文本），库里存 slug。
- `toDefinition()`（trim + slug 归一化；**不带** `createdAtMs`/`updatedAtMs`——那是 repository 的事，重复携带只会多一个错的来源）。
- `SurfaceSummary` + `AgentDefinition.surfaceSummary()`：卡片「有没有自有面 / 自有几组」的唯一口径（**自有空面也算自有面**）。
- 全纯：无 Room、无 Android、无 `R`、无 `Assistant`。

### B. 目录（`LocalToolGroups`，同文件）
- 56 个工具组，顺序＝助手本地工具页的阅读序；`order()` 去重 + 按目录序排（未知项排最后，手改数据不让编辑器崩）。
- **新护栏测试**：反射枚举 `LocalToolOption` 的嵌套类（`declaredClasses` + `isAssignableFrom`）与手写目录比对 ⇒ **新增工具组若忘记登记，测试直接红**，而不是在专家编辑器里悄悄少一行。

### C. UI（`SettingSubAgentsPage.kt` 全量替换，831 行）
- `useEditState<AgentDefinitionDraft>`；保存走 `draft.toDefinition()` → repository `create`/`update`（分支判据同前）；列表现读 `AgentDefinitionDraft.of(definition)`。
- `SurfaceGroup` = 表头「继承父助手 ⇄ 自定义」+ **仅在自定义时**渲染选择器 —— 这正是三态可见的地方（空选择器与「未配置」本来会长得一样）。`PickerRow` 是紧凑行（标签 + 开关）。
- 四节 + 命名空间节：本地工具组（56 行）/ 逐工具禁用 / MCP 服务器（按 `commonOptions.enable` 过滤）/ 技能（`SkillManager.listSkills()` 一次性载入，IO 上跑）/ 命名空间（开关 + 文本框 + 预览 + 红字 + 建议播种）。
- 工具组标签走**穷尽 `when`（无 `else`）**映射到助手页既有 `assistant_page_local_tools_*_title`：新增工具组会**在此处编译失败**而不是渲染空行；文案与助手页同源，两屏对同一组叫法一致。
- 逐工具禁用的候选名来自 `LocalTools.getTools(...)`（**活工厂** ⇒ 不可能与实际下发给模型的面对不上），`runCatching` 兜底为「无可列出的工具」。
- 卡片加自有面摘要行（`Tools n · MCP n · Skills n · agents/<slug>`），用 `listOfNotNull` 组装（避免在 inline builder 里调 composable）。

### 验证硬证据
- **CI 双 run 首轮全绿**：push `37018254760` + PR `37018297063`；合并后 master push `37020031989`。
- artifact `unit-test-report` 实核：全仓 **2088 tests / 0 failures / 0 skipped**（2060 → **+28**，正是新测试数）；逐类确认 `AgentDefinitionDraftTest` **28/0/0**。
- **本地 harness `p206c/harness.py`**：真 `kotlinc` + JUnit4 ⇒ **`OK (28 tests)`**。★ `LocalToolOption` stub **由真 `LocalTools.kt` 解析生成**（56 组，解析数不足 40 即报错退出）——因此穷尽性/序列化断言打的是**本 build 的真实工具集**，不是手抄副本。不编 `SettingSubAgentsPage.kt`（Compose），交 CI。
- 静态自查：新页引用的 **99 个 `R.string` 名全部存在**；triple `strings.xml` 键名无重复（2591 / 2284 / 2210）。

### 新坑 / 手法
- ★★**插入型补丁的幂等**：`patch.py` 跑第二遍时，锚点（`..._confirm` 行）**仍然唯一**，于是整个 23 行块被**再次插入** ⇒ 重复资源，AAPT 必挂。教训：**锚点唯一 ≠ 未打过**；插入型补丁必须额外断言「待插入内容尚不存在」并在命中时整批拒绝。已回滚重打（`git checkout -- strings.xml` 后再跑一次），并把护栏写进 `patch.py`。
  - 顺带记住：GitHub 的 `unit-test-report` artifact 是 **HTML**（`build/reports/tests/`），不是 XML —— 统计要从 `testDebugUnitTest/index.html` 的 summary 行/逐类行里抠。
- 组级工具文案不必新写：助手页的 `assistant_page_local_tools_*_title` 已覆盖全部 56 组，`when` 穷尽即可（`Shizuku` 不在正则抓取的 55 条里，要单独补——它的 title 行与 `LocalToolOption.Shizuku` 相隔 >700 字符，抓取窗口要放宽）。

**下一卡 = P2-05**（工具调色板：接上 `ToolCatalogSource.LOCAL`，供「配专家」时搜索/浏览工具；服务于 P2-06 的 UI，不改默认对话路径的目录行为）。

---

## P2-05 完成记录（2026-10-02，PR #27 / squash `c4d69b74`，**CI 首轮双 run 全绿**）

**口径（用户 ask_user 全 A）**：① **完全不动 `ChatService`** —— LOCAL 源只接在「调色板」这一侧，chat 的 DIRECT / PROGRESSIVE_CATALOG / rerun 三条装配路径逐字节不变；② 调色板 = 搜索框 + 结果行「工具名 · 所属组 · 摘要 · LOCAL 徽章」，行内开关 = 该工具的**逐工具禁用**（复用 P2-06c 的 `toggleDisabledTool`），组未开启时行内说明 + 「开启该组」快捷动作；③ 只做**专家编辑页**（`SettingSubAgentsPage`）。

**侦察结论（本卡的依据）**：`ToolCatalogSource.LOCAL` **全仓零生产者**（只有 `ChatService` 造 MCP 半边目录）；本地目录 = 56 组 / **~182 个工具槽**（SSH 8 / Telegram 14 / Files 14 / ScreenAutomation 11 / Keyboard 8 / Termux 8…），而专家编辑页此前只有**一坨 ~150 个工具名的平铺列表**（无搜索、无所属组、无摘要）。卡里写的「`ChatService.kt:1924` 旁路」是一期行号，今天那一段就是 MCP 目录块 ⇒ 按口径 A 不动它。

**改动面**：7 文件 / **+561 −5**（2 个新文件 = `LocalToolPalette.kt` 152 行 + `LocalToolPaletteTest.kt` 213 行；`ToolCatalog.kt` 1 处；`SettingSubAgentsPage.kt` 4 处 +164；3 个 `strings.xml` 各 +8 条）。

### A. 纯逻辑（新文件 `data/ai/tools/LocalToolPalette.kt`，152 行）
- **输入**：`List<LocalToolInventory>`（每组一份 `LocalToolOption → List<Tool>`，UI 用活工厂逐组取）。**分组是调用方的事**（只有调用方能构造工厂），**排序不是**。
- **排序不重写**：`search()` / `matchAll()` 直接委托 `ToolCatalog` 里 `tool_search` 用的那个打分器 ⇒ 调色板命中与「给模型的命中」在**排序、并列打破、来源**上不可能各说各话。
- **`ToolCatalogSource.LOCAL` 的唯一写入点**（本卡即为此而来）；`LocalToolPaletteHit.source` 从**目录条目**读出，UI 打的就是它（`LOCAL`），不是界面自造的标签。
- 边界：同名工具归**首个**出现的组（行要指向一个具体组，顺序＝组列表阅读序）；空名丢弃（模型也寻址不了）；组列表去重按首现排。
- 摘要 = 描述**首个非空行 + 空白折叠**（工具描述是给模型看的 `trimIndent()` 块）；**不截断** —— 180 字符的帽子属于面向模型的 `ToolSearchOutcome`，UI 靠 ellipsize。
- 全纯：无 Android、无 `Context`、无 `R`、无协程。

### B. 目录（`ToolCatalog.kt`，1 处 13 行）
- `matchAll`：`private` → `internal`，**函数体一字未改**。8 条上限是给模型的**响应预算**，不是给人滚列表的边界；调色板用它排序、自己给上限（`TOOL_PALETTE_MAX_HITS = 30`）。`search` 的 8 条上限 / 打分 / 并列打破全部原样。

### C. UI（`SettingSubAgentsPage.kt`）
- 面区标题下新增**调色板段**：标题 + 说明 + 搜索框；**空查询**时显示目录规模（`%1$d 组 · %2$d 个工具`），有查询时列命中行（上限 30）。
- 行 = `name` + `LOCAL` 徽章（`Tag(INFO)`，文案取自 `hit.source.name`）+ 第二行 `组标题 · 摘要`（2 行 ellipsize）；行内开关 = **同一个**逐工具禁用（调色板是到达既有状态的第二条路，不是第三种状态）。
- 组未开启：灰字提示 + 「开启该组」（`ownLocalTools().toggleLocalTool(group, true)` —— 用户要的就是这一组，不猜起始集合）。
- 目录由活工厂**逐组**取（56 次调用，纯对象构造，每开一次 sheet 一次），`runCatching` 失败整段隐藏（不让 sheet 陪葬）；继承态与下方禁用候选池同一约定（`localToolGroups ?: all` ⇒ 视为全开）。
- 新私有 `ToolPaletteRow`（`PickerRow` 是「开关 + 两行字」，既放不下徽章也放不下组开关提示）；既有 `localToolFactory` 声明**上提**，避免同作用域重复声明。

### 验证硬证据
- **CI 双 run 首轮全绿**：push `37023857675` + PR `37023872694`；合并后 master push `37026065794` **success**。
- artifact `unit-test-report` 实核：全仓 **2103 tests / 0 failures / 0 skipped**（2088 → **+15**，正是新测试数）；逐类确认 `LocalToolPaletteTest` **15/0/0**。
- **本地 harness `p205/harness.py`**：真 `kotlinc` + JUnit4 ⇒ **`OK (40 tests)`** = 既有 `ToolCatalogTest` 25（回归）+ 新 15。★ `LocalToolOption` stub 仍**由真 `LocalTools.kt` 解析生成**（56 组）；本卡另加三个最小 stub（`Tool` / `InputSchema` / `UIMessagePart`），已在文件头逐条注明为何是 stub（真 `Tool.kt` 的 `systemPrompt` 会把 `provider.Model` + `ui.UIMessage` 拖进来）。不编 `SettingSubAgentsPage.kt`（Compose），交 CI。
- 静态自查：triple `strings.xml` 键数 **2599 / 2292 / 2218**（P2-06c 为 2591 / 2284 / 2210，各 +8 ✓）、三文件**键名零重复**、8 个 palette 键三语**集合一致**、编辑页 **107 个 `R.string` 引用全部可解析**。
- `git diff` 实核：**`ChatService.kt` 不在改动清单里**（红线）。

### 新坑 / 手法
- ★ **`artifact.py` 的下载目标要按 artifact 取名**：第一版把所有 artifact 都写到同一个 `/tmp/p205/art.zip`，循环最后一轮是 337 MB 的 `apk-debug` ⇒ 统计脚本打开的是 APK，**静默输出空**（不是失败，是「查错了文件」）。教训：抓 artifact 统计时先 `--name` 过滤，别复用同一个落盘路径。
- ★ **`vps.sh` + heredoc 仍会吃掉 stdin**：`sh vps.sh "python3 - <<'EOF' …"` 里的 `\` 转义会被外层再吃一层（本次报 `unexpected character after line continuation character`）。所有多行 python 一律**先落盘再 base64 上传执行**（`selfcheck.py` / `count.py` 即此）。
- 调色板要「搜索整个本地目录」就绕不开 `ToolCatalog.search` 的 8 条上限；解法是**把 `matchAll` 从 `private` 提到 `internal`**（一行 + 注释），而不是在 UI 里再写一遍打分——**排序只有一个家**这条纪律比「少动一个文件」值钱。

## P2-07 完成记录（2026-10-02，PR #28 / squash `e2aacb1e`，**CI 双 run 全绿**）

**口径（用户 ask_user 全 A）**：① 助手级新增 `Assistant.orchestrationTokenBudget: Long? = null`（默认不限），并发沿用已有 `maxConcurrentSubAgents`（默认 3 / 上限 8，本卡补 UI），**不**另加子数字段；② 专家 `AgentDefinition.tokenBudget` **覆盖**父助手预算，null = 继承父；③ UI 落点 = 助手「基本设置」页；④ 专家编辑页补 `tokenBudget` 输入框。

**改动面**：8 文件 / **+339 −1**（2 新文件 = `OrchestrationBudget.kt` + `OrchestrationBudgetTest.kt`；`Assistant.kt` +9；`AssistantBasicPage.kt` +122；`SettingSubAgentsPage.kt` +23；3 个 `strings.xml` 各 +10）。

### A. 数据（`Assistant.kt`）
- 末尾 `orchestrationTokenBudget: Long? = null`，标 `@EncodeDefault(NEVER)` ⇒ 未触碰该设置的助手在库中**逐字节不变**，默认即 P2-07 之前的行为。

### B. 纯逻辑（新文件 `data/usage/OrchestrationBudget.kt`）
- `effectiveBudget(assistant, expert)`：专家有值用专家，否则用助手；皆 null = 不限（D8）。
- `exceeded(used, budget)`：`used >= budget`（**达顶即拒派**，P2-13 用）；null 预算永不超。
- `remaining(used, budget)`：剩余量钳 0；**null ≠ 0**（「不限」与「用完」不能同形）。
- 全纯：无 Room / 协程 / Android。**读取仍归** `UsageLedger.tokensForOrchestration` + `UsageRecordDao.tokensForParentRun`（P2-11 已存在，本卡未改）。

### C. UI
- `AssistantBasicPage` 新卡：`每编排 token 上限`（留空 = 不限）+ `并发子 agent 数`（失焦提交、钳 1..8）。★并发字段是 Phase 11 起就持久化、但**从未露出**的旋钮，本卡补上。
- `SettingSubAgentsPage`：`tokenBudget` 输入框（空 = null = 继承父助手）。★该列 P2-06a 已预留、draft / 写工具 / 仓库已全链路，**只差控件**。

### 验证硬证据
- **CI 双 run 全绿**：push `37031657556` + PR `37031663179`；合并后 master push `37033442195` **success**。
- artifact `unit-test-report` 实核：全仓 **2118 tests / 0 failures / 0 skipped**（2103 → **+15**，正是新测试数）；逐类确认 `OrchestrationBudgetTest` **15/0/0**。
- **本地 harness `p207/harness.py`**：真 kotlinc + JUnit4 ⇒ **`OK (15 tests)`**。★本卡纯文件，**零 stub**（对比 P2-05 的 3 个）。
- 静态自查：triple `strings.xml` 键数 **2609 / 2302 / 2228**（P2-05 为 2599 / 2292 / 2218，各 +10 ✓）、三文件键名零重复、10 个新键三语**集合一致**、两页 `R.string` 引用全解析（AssistantBasicPage 57 / SettingSubAgentsPage 109，0 missing）。
- `git diff` 实核：**`ChatService.kt` / `GenerationLoop.kt` 不在改动清单**（红线）。

### 新坑 / 手法
- ★★**同文件多编辑必须「累积」**：补丁器第一版 `pending[path] = body.replace(old, new, 1)` 每轮都基于**原始** body 重算 ⇒ 同文件后一个编辑**覆盖**前一个（BasicPage 的 `SubAgentDefaults` import、Sheet 的两个 Compose import 全丢），而锚点 / 守卫检查**全部通过**（每个编辑单独看都合法）。改为 `originals`（守卫用原始）+ `pending`（串行叠加）即修复。**教训：锚点唯一 ≠ 状态正确。**
- ★**en strings 里撇号必须转义 `\'`**：`expert's` / `assistant's` 两处未转义 ⇒ `mergeDebugResources` 直接失败（AAPT2 报 "Invalid unicode escape sequence in string"，且报的行号是**错位**的 2153，非真实行）——白烧一轮 CI。同文件既有串都用 `\'`。**改 strings 前先扫 `'`。**
- `assistant_page_orchestration_budget_limit` 用 `%1$s` + `budget.toString()`（不用 `%d` 配 Long，绕开 lint 的格式类型检查）。

**下一卡 = P2-08**（保活：编排期 FGS —— 有活跃子 run 时起前台服务、结束即撤；红线＝**无活跃 run 不起服务**，不能变常驻）。

## P2-08 完成记录（2026-10-03，PR #29 / squash `6cb3559b`，**CI 三 run 全绿**）

**口径（用户 ask_user 全 A）**：① 保活**只覆盖子 agent run**（`SubAgentEngine.executeRun` 全程持有），不动 cron / workflow / Telegram 路径；② 通知**不新增文案**，复用既有 live-update 通知（零 strings 面）。

**改动面**：4 文件 / **+235 −4**（★ **无新文件** —— 上游已有 `ForegroundWorkTrackerTest.kt`，本卡**扩充**而非覆盖：2 既有用例原文保留 + 6 新用例）。

### A. 缺口（侦察实证）

保活机制**早已存在**（`ForegroundWorkTracker` 引用计数 + `ChatGenerationForegroundService`），但**只有 ChatService 的生成任务持有**它（4 处 acquire，全是用户会话的生成）。子 run 跑在 `appScope` 上、全程不持有任何 FGS 引用 ⇒ `run_in_background=true` 的子 run 在父回合结束后，只在**自己调 LLM 的那几秒**被 ChatService 的生成副作用顺带保活；工具执行 / 等并发槽 / 通知父会话前最长 5 分钟的等待，这些**空档期无保活** —— 正是平台冻结或回收进程的窗口（`AgentRunBootRecovery` 只能事后记一笔 `process_lost`）。

### B. 接线

- `SubAgentEngine.executeRun` 拆成「取 claim → `try { runSubAgentBody(...) } finally { release() }`」，**原 body 原文未动** ⇒ 成功 / 失败 / 取消 / 超时 / 三个 early return **全部**覆盖。
- `ChatService.retainForegroundForActiveRun(): () -> Unit` = **同一个 tracker** 的一次 acquire。**不新增服务、不改 Manifest、不动 `ChatGenerationForegroundService`**。
- `ForegroundWorkTracker`：私有计数 `activeCount` → `holders`，新增只读 `val activeCount`，补 KDoc。**计数语义未变**。

### C. 红线由引用计数本身保证

服务起点/终点由**同一个计数**决定：第一个 claim 启、最后一个 claim 停 ⇒ ① 编排结束**不会**停掉仍在生成的服务；② 计数归零**不会**让服务常驻（无活跃 run 时服务必撤）。

### 验证硬证据

- **CI 三 run 全绿**：push `37091194979` + PR `37091205626`；合并后 master push `37091872218` **success**。
- artifact `unit-test-report` 实核：全仓 **2124 tests / 0 failures / 0 skipped**（2118 → **+6**，正是新用例数）；逐类确认 `ForegroundWorkTrackerTest` **8/0/0**（2 既有 + 6 新）。
- **本地 harness `p208/harness.py`**：真 kotlinc + JUnit4 ⇒ **`OK (8 tests)`**（`ForegroundWorkTracker` 只依赖 `java.util.concurrent.atomic`：零 stub、零 Android、零协程）。
- **`p208/selfcheck.py` 14/14 PASS**：claim 唯一取用点、`finally` 唯一释放点、body 拆分未动 run 逻辑（early return / `SubAgentSurface.release` 仍在）、tracker 计数改名无残留、`ChatService` 无第三个 FGS 启动点。

### 新坑 / 手法

- ★★ **"新文件"落笔前先 `ls` 目标路径**：本卡原计划新建 `ForegroundWorkTrackerTest.kt`，patch 直接 ABORT（`refusing to overwrite existing …`）—— 上游其实**已有**该文件（2 个用例）。改为**扩充**既有文件（原文保留 + 追加 6 例）：diff 更小、也不吃掉别人的测试。守卫脚本的"拒绝覆盖"这次真的挡住了事故。
- ★ **artifact 的 `archive_download_url` 重定向后不能带 `Authorization`**（Azure blob 直接 401）：`urllib` 会把 header 一路带到 302 目标；改用 `curl -sSL`（curl 跨主机自动剥 Authorization），或两段式先取 `.../artifacts/{id}/zip`。
- ★ `jq` 解析 `actions/runs` 会因某个 run 的字段含控制字符直接 `parse error`（watch 脚本因此哑火）⇒ 状态查询改用 stdlib `json`（`p208/check_ci.py`）。
- 本卡**零 UI、零 strings、零 Manifest**：验收（编排期锁屏/切后台不中断、结束通知消失）只能装机实测，文档口径以"计数语义 + CI 全绿"为硬证据。

**下一卡 = P2-12a**（账本归属与展示 · 第 1 子卡：流式采集补齐）。

## P2-12a 完成记录（2026-10-03，PR #30 / squash `10cd571f`，**CI 三 run 全绿**）

**口径（用户 ask_user 全 A）**：① F1 本卡先修；② 归属键＝时间窗；③ 拆 4 子卡；④ 编排树入口＝统计页。

### 缺口（侦察实证，语言级证明）

P2-11 的采集装饰器 `UsageRecordingProvider` 是 `Provider<T> by delegate`，**只覆写 `generateText`**；`streamText` 直接透传。而主回合**默认走流式**（`Assistant.streamOutput = true` → `GenerationLoop` 用 `providerImpl.streamText`），且 `UsageCallContext` 只包了非流式分支 ⇒ **普通聊天（含工具回合）在 `usage_records` 里一行都没有**；只有 TITLE/SUGGESTION/COMPACTION/TRANSLATION 与 `streamOutput=false` 的助手有记录。P2-11 文档/冷库注记的前提（"所有路径最终都调 generateText"）**对流式不成立**。
★ 补充事实：`ChatCompletionsAPI` 已请求 `stream_options.include_usage=true`，解码器产 `StreamChunk.Usage` ⇒ 修了就有数据。

### 改动面（4 文件，+229 −11；2 新文件）

- `data/usage/UsageRecordingProvider.kt`：新增 `streamText` 覆写 → `recordUsageOnce` 包流；**急切**读 `UsageCallContext`（流可能稍后/重试才被收集）；`generateText`/`streamText` 收敛到同一 `recordCall`；非流式路径 `streaming=false`、行为逐字节不变。
- `data/usage/UsageStreamRecorder.kt` ✨（纯）：`Flow<T>.recordUsageOnce(select, record)` —— 元素原样透传；按 `TokenUsage.merge` 同规则合并；正常完成后只写一行；无 usage / 失败 / 取消 ⇒ 不写；重复收集不重复写；`record` 抛异常吞掉、`CancellationException` 原样抛。
- `data/ai/GenerationLoop.kt`：流式调用点包上与非流式同形的 `UsageCallContext`（`stepIndex>0` ⇒ TOOL_LOOP 否则 MAIN；带 conversationId/assistantId）。
- `test/.../data/usage/UsageStreamRecorderTest.kt` ✨：5 例。

### 验证硬证据

- **CI 三 run 全绿**：push `37093470273` + PR `37093477515`；合并后 master push `37094247629` **success**。
- artifact `unit-test-report` 实核：全仓 **2129 tests / 0 failures / 0 skipped**（2124 → **+5**）；逐类确认 `UsageStreamRecorderTest` **5/0/0**。
- **本地 harness `p212/harness.py`**：真 kotlinc + JUnit4 ⇒ **`OK (5 tests)`**（零 stub；`Usage.kt` 只依赖 kotlinx.serialization）。
- **`p212/selfcheck.py` 18/18 PASS**。

### 新坑 / 手法

- ★ **`merge` 是 `ai.core` 的顶层扩展函数，跨包必须显式 `import com.clma.ai.core.merge`**（`TokenUsage?.merge`）—— 首轮 harness 编译因此失败（`unresolved reference 'merge'`），其余逻辑全对。教训：挪用别处的扩展函数，别忘连 import 一起搬。
- ★ 附带的**行为外溢**（可接受）：连接测试 `ProviderConnectionTester` 也经 `providerManager.getProviderByType` ⇒ 其流式那半现在也会记一行（purpose=UNKNOWN）。因**非流式那半本就已记**，属一致化、非回归；UNKNOWN 正是"未接线路径必须可见"的设计。

**下一卡 = P2-12b**（回合视图：footer 升级 + 可展开逐次 + 估算 vs 真实标注；归属键＝时间窗）。

## P2-12b 完成记录（2026-10-03，PR #31 / squash `e2a14cef`，**CI 双 run 全绿**）

**口径（用户 ask_user 全 A + 一条实核修订）**：① 优先账本，窗口无记录则回退现有 `message.usage`（行为不变）；② 内联展开（`AnimatedVisibility`）；③ 显式参数下钻；④ provider 自报 cost=真实、价格表算出=估算（`~` 前缀）；⑤ 窗口内全部记录计入、按用途标签区分；★⑥ **窗口 = `[本节点 createdAt, 下一节点 createdAt)`（末节点开区间）+ 按 purpose 排除 `TITLE`/`SUGGESTION`**。

### 缺口（侦察实证）
footer 只读 `message.usage`，而它由 `StreamChunkHandler` 的 `TokenUsage.merge` **覆盖式**合并而来 ⇒ 含工具调用的回合显示的是**最后一次**调用的数字（P2-11 缺陷 A3）。账本每往返一行，回合窗口求和才读得到整回合。

### A. 窗口口径为何必须修订（两条硬事实）
- ★**流式记录晚于 `finishedAt`**：provider 先发 `Finish` 块 → `StreamChunkHandler` 立刻把 `message.finishedAt` 盖成此刻；流 flow 结束后 `recordUsageOnce` 才落库 ⇒ 严格 `[createdAt, finishedAt]` 会**漏掉全部流式记录**（默认路径）。故窗口收在**下一节点**的 `createdAt`（天然收下尾部延迟、又不漏进下一回合）。
- ★**标题/建议混入**：回合结束后 `ChatService` 立刻 `launchAuxJob(SUGGESTION)`（每回合）/ `TITLE`（首回合），二者都带 `conversationId`、落在同一段 ⇒ 时间窗无法区分，必须按 purpose 排除，否则每回合多算一次。
- ★**刷新时机**：账本行落库晚于 `finishedAt` 持久化 ⇒ `turnUsages` 除按节点时间戳重查外，还需在 `conversationJob` 转空闲时**再查一次**（用 `combine(conversation, conversationJob)`），否则刚结束的回合会卡在回退态直到下一条消息。

### B. 改动面（15 文件，+676 −2；3 新文件）
- `data/usage/UsageTurnView.kt` ✨（纯）：`UsageTurnWindow` / `UsageCallView` / `TurnUsageView`（含 `estimatedCostOnly`）+ `UsageTurnViewFactory`（`windowsFor` 窗口切分 / `isTurnCall` 过滤 / `aggregate` 求和 / `map`）。缓存命中**只在上报过缓存字段的行**上求和（`cachedTokensReported`）；缺价一律 `null`，绝不当 0。
- `UsageRecordDao.forConversation` + `UsageLedger.recordsForConversation`：按会话的有界读取（`QUERY_LIMIT`）。
- `ChatVM.turnUsages` ✨：`消息id -> TurnUsageView`；`combine(节点时间戳, conversationJob 是否繁忙)` → `distinctUntilChanged` → `flatMapLatest` 查库 → `stateIn(WhileSubscribed(5_000L))`；读取失败 ⇒ 空 map（回退）。
- `ui/.../message/ChatMessageTurnUsageLine.kt` ✨：折叠态 `N calls` + 输入(含命中率)/输出/成本 + 展开箭头；展开列逐次（用途 · 输入→输出 · 缓存 · 成本 · 耗时）。成本 provider 自报原样、价格表算出加 `~`。
- `ChatMessageNerdLine` 加可选 `turnUsage`：有账本行 → 回合视图，无 → **原单次 footer 逐字节不变**（`turnUsage == null && usage != null` 才走旧分支）。
- 下钻：`ChatPage`（`vm.turnUsages.collectAsStateWithLifecycle()`）→ `ChatList`（两处签名 + 转发）→ `ChatMessage` → `ChatMessageNerdLine`（各加可选参数，其他调用点零影响）。
- `ViewModelModule` 注入 `usageLedger`；三语 strings 各 +13。
- ★**既有测试连带**：`UsageRecordDao` 增方法 ⇒ 替身 `UsageCallRecorderTest.FakeDao` 必须补 `forConversation`（CI `compileDebugUnitTestKotlin` 才会过）。

### C. 验证硬证据
- **CI 双 run 全绿**：push `37098569754` + PR `37098572227`；合并后 master push `37099344046` **success**。
- artifact `unit-test-report` 实核：全仓 **2142 tests / 0 failures / 0 skipped**（2129 → **+13**）；逐类确认 `UsageTurnViewTest` **13/0/0**。
- **本地 harness `p212b/harness.py`**：真 kotlinc + JUnit4 ⇒ **`OK (13 tests)`**（零 stub；`UsageTurnView` 只依赖 `UsageRecordEntity`(room-common) + `UsagePurpose`）。
- **`p212b/selfcheck.py` 55/55 PASS**：红线文件未动、锚点唯一、三语键数与集合、`R.string` 引用全解析、**`data.usage` 的 import 必须能解析到真实声明**。

### 新坑 / 手法
- ★★**文件名 ≠ 类名**：新文件叫 `UsageTurnView.kt`，但类是 `TurnUsageView` ⇒ ChatVM 里写成 `import ...UsageTurnView` 直接编译失败（`ChatVM.kt:39 Unresolved reference`）。本地 harness 只编纯文件，**覆盖不到** ⇒ 新增自查「`data.usage` 的 import 必须解析到该包真实声明」（本次立即抓到）。教训：**局部 harness 的覆盖盲区要用结构化自查补**。
- ★★**改接口要连带改测试替身**：给 `UsageRecordDao` 加方法后，`UsageCallRecorderTest.FakeDao` 未实现新方法 ⇒ `:app:compileDebugUnitTestKotlin FAILED`（第一轮 CI 只编到主源码，第二轮才编到测试源，于是**同一次 CI 分两次暴露问题**）。改 DAO/接口前先 `grep ": XxxDao"` 找替身。
- ★ **amend + force-push 修同一张卡的提交**：`git add -A && git commit --amend --no-edit && git push --force <token-url> <branch>`（单卡单 commit，squash 合并前一贯做法）。
- ★ artifact 取法与统计口径沿用 12a（`curl -sSL` 剥 Authorization、stdlib json、按 name 过滤）。

## P2-12c 完成记录（2026-10-03，PR #32 / squash `4c06a69a`，**CI 双 run 全绿**）

**口径（用户 ask_user 全 A）**：① 新增「用量账本」板块，**既有卡片不动**（两源窗口不同、永不相加）；② 助手维度：行自带 `assistantId` → 否，用 `conversationId` 反查会话所属助手 → 仍无则「未知」；③ 用途维度**全部列出**（含 `TITLE`/`SUGGESTION` —— 12b 的排除只针对「回合归属」）；④ 每维一张卡：Top-N 行 + 占比条 + tokens + 成本，可展开全部；⑤ 窗口固定 **90 天**（= 账本保留窗）；⑥ 成本沿用 12b（provider 自报裸值、价格表算出加 `~`）。

### 缺口（侦察实证）
统计页此前**只读 `message.usage`**（`MessageNodeDAO.getTokenStats` 从 `message_node.messages` JSON 里 `json_extract`），**零账本接入** ⇒ 能数 token，但答不出「谁 / 哪天 / 什么用途 / 哪个模型花的」。

### A. 三条决定口径的硬事实
- ★**`assistantId` 只有主回合填**：`grep UsageCallContext(` 全仓只有 `GenerationLoop.kt:1468`（MAIN / TOOL_LOOP）带 `assistantId`；`ChatService` 的 TITLE / SUGGESTION / COMPACTION 只带 `conversationId`，`TranslationHandler` 两者皆无 ⇒ 辅助调用行的 `assistant_id` 恒 null。账本与 `conversationentity` 分属两个 DB 文件、不能 SQL join ⇒ 助手回退在 Kotlin 侧解析。
- ★**账本 ≠ 消息表**：账本仅 90 天、且 P2-12a 前流式零记录 ⇒ 替换既有卡片会「数字倒退」。故只**新增**。
- ★**必须有界读**：`QUERY_LIMIT=1000` 太小 ⇒ 新 `STATS_QUERY_LIMIT=20000`，且复用既有 `UsageRecordDao.since`（**不改 DAO ⇒ `UsageCallRecorderTest.FakeDao` 替身零改动**）。

### B. 改动面（8 文件，+899；2 新文件）
- `data/usage/UsageStatsView.kt` ✨（纯）：`UsageStatBucket`（含 `totalTokens` / `cacheHitRate` / `estimatedCostOnly`）+ `LedgerStatsView`（四维 + `total` + `shareOfTokens`）+ `UsageStatsFactory.build(records, zone, assistantOfConversation)`（四维分组；未知键 `"unknown"`；成本 null 不当 0；缓存只累加 `cachedTokensReported` 的行）。
- 新测试 `data/usage/UsageStatsViewTest.kt` **22 例**。
- `UsageLedgerDefaults.STATS_QUERY_LIMIT` + `UsageLedger.recordsSince(sinceMs, limit)`（复用 `dao.since`）。
- `StatsVM`：注入 `usageLedger`；新 `ledgerStats`（null = 未加载 / 读失败 ⇒ 板块不渲染）+ `assistantNames`（id → 名）；`loadLedger()` 读 90 天 + `conversationDAO.getAll().first()` 建 conversation → assistant 映射（best effort）。
- `StatsPage`：新 `LedgerStatsSection` + `LedgerBucketCard` + `LedgerBucketRow`（占比条）+ `ledgerCostText` + `purposeLabelRes`；`formatCost` 跨包复用（同 module `internal`）。
- 三语 strings 各 +13（含补 12b 缺的 `chat_message_usage_purpose_title` / `_suggestion`）。

### C. 验证硬证据
- **CI 双 run 全绿**：push `37102306888` + PR `37102309515`（sha `736c6072`）；合并后 master `4c06a69a`。
- artifact `unit-test-report` 实核：全仓 **2164 tests / 0 failures / 0 skipped**（2142 → **+22**）；逐类确认 `UsageStatsViewTest` **22/0/0**。
- **本地 harness `p212c/harness.py`**：真 kotlinc + JUnit4 ⇒ **`OK (22 tests)`**（零 stub）。
- **`p212c/selfcheck.py` 122/122 PASS**：红线未动、锚点唯一、`R.string` 全解析、**单文件键不重复**、**`data.usage` 的 import 解析到真实声明**。

### 新坑 / 手法
- ★★**字符串锚点里别重复既有行**：把新 `title` / `suggestion` 插在 `purpose_unknown` 前时，new 文本里又写了一遍**已存在**的 `purpose_translation` ⇒ 资源合并 `Found item String/... more than one time`，`:app:packageDebugResources` 首轮即挂。**自查已补**「单文件内键不重复」。
- ★★**改 stage 文件后必须重新 put.sh**：只把修正版单独 base64 推到 REPO 跑通 harness，却忘了同步 `/tmp/p212c/files` ⇒ 之后「重置 + 重放 patch」又用旧 stage 覆盖回去，**第二轮 CI** 才在 `:app:compileDebugUnitTestKotlin` 暴露。**stage 是唯一真相源，改了就得重传**。
- ★ `assertEquals(Float, Float, Double)` 在 JUnit 无此重载：delta 必须写 `1e-6f`。本地 kotlinc 与 CI 同报此错（harness 本可抓到，同上因 stage 未同步才漏到 CI）。
- ★ 沿用：`curl -sSL` 剥 Authorization、stdlib json（jq 会 parse error）、artifact 按 name 过滤。

## P2-12d 完成记录（2026-10-03，PR #33 / squash `395ac707`，**CI 双 run 全绿**）

**口径（用户 ask_user 全 A）**：① 本卡补子 run 归属写入（顺带修好 P2-13 输入）；② 树骨架＝`agent_runs`（`kind=subagent`），token/成本按 `run_id` 汇总；③ 入口＝统计页内嵌「编排树」卡片（父会话分组、可展开）；④ 范围＝最近 50 条 run。

### 缺口（侦察实证）
`usage_records.run_id` / `parent_run_id` 从 P2-11 就有、编排树与 P2-13 都读它们，**但全仓 6 处 `UsageCallContext(...)` 无一处填过**（GenerationLoop 只带 conversationId/assistantId；ChatService 三处只带 conversationId；TranslationHandler 皆无）⇒ 子 agent 的调用落库时两字段恒 null：「父→子」树零数据源，`tokensForParentRun()` 恒 0（P2-07 设的上限形同虚设）。

### A. 注入点为什么是注册表
子 agent 经 `chatService.sendMessage(子会话)` 进聊天管线，而**生成 job 跑在 `appScope.launch`** ⇒ 派发方的 `withContext` **传不进那个 job**。故仿 `HeadlessConversations`：新增 `UsageRunContexts`（进程级 `conversationId → Attribution(runId, parentRunId, purpose)`），`GenerationLoop` 构造 ambient context 时读取。
★ 边界：`ChatService` 的 TITLE/SUGGESTION/COMPACTION 与 `TranslationHandler` **不动**（红线）⇒ 这些辅助行 `run_id` 仍 null、不计入编排（预算只统计编排内子 run 消耗，口径可接受）。

### B. 改动面（11 文件，+968 −2；4 新文件）
- `data/usage/UsageRunContexts.kt` ✨（纯）：`Attribution` + `mark/get/unmarkRun/unmarkConversation/clear`；默认 `purpose = SUBAGENT`；**覆盖时双向清理**映射。
- `data/usage/OrchestrationTree.kt` ✨（纯）：`OrchestrationNode` / `OrchestrationTree`（`childCount`/`totalTokens`/`providerCostUsd`/`costMicros`/`latestAtMs`/`hasRunningChild`）+ `OrchestrationTreeFactory.build(runs, records, maxTrees=20)`；只把 `subagent` 行当节点、按 `parentRunId` 分组；**无账本行的 run 仍出现**（0 调用）；**缺失价格不当 0**；label 从 `metadata_json` 解析、失败回退 `domainId.take(8)`。
- 新测试 `UsageRunContextsTest.kt` **8 例** + `OrchestrationTreeTest.kt` **18 例**。
- `SubAgentEngine.kt`：`HeadlessConversations.mark` 旁 `UsageRunContexts.mark(conv.id, runId, parentChatId)`；`executeRun` 的 `finally` 里 `unmarkRun(runId)`。
- `GenerationLoop.kt`（**有意改**）：流式 + 非流式两处都 `val usageAttribution = conversationId?.let { UsageRunContexts.get(...) }`，写进 `UsageCallContext`（purpose 认 `usageAttribution?.purpose`，runId/parentRunId 一并带上）。交互对话解析 null ⇒ context 逐字节不变。
- `StatsVM.kt`：注入 `AgentRunRepository`；`orchestrationTrees`（null=未加载）+ `conversationTitles`；`loadOrchestration()` 读最近 50 条 run + `recordsSince(min(createdAtMs))`（复用 12c，**不改 DAO ⇒ 替身零改动**）。
- `StatsPage.kt`：`OrchestrationSection` / `OrchestrationTreeCard` / `OrchestrationChildRow`（状态点 + label + 状态 + 调用数 + tokens + 成本，可展开）。
- 三语 strings 各 +9（`stats_page_orchestration_*`）。

### C. 验证硬证据
- **CI 双 run 全绿**：push `37104995022` + PR `37105001462`（sha `708c99e4`）；合并后 master `395ac707`。
- artifact `unit-test-report` 实核：全仓 **2190 tests / 0 failures / 0 skipped**（2164 → **+26**）；逐类确认 `OrchestrationTreeTest` **18/0/0**、`UsageRunContextsTest` **8/0/0**。
- **本地 harness `p212d/harness.py`**：真 kotlinc + JUnit4（零 stub；编 `UsageRunContexts` + `OrchestrationTree` + `UsagePurpose` + `UsageRecordEntity` + `AgentRun`）⇒ **`OK (26 tests)`**。
- **`p212d/selfcheck.py` 149/149 PASS**：红线未动、**有意改动断言**（GenerationLoop/SubAgentEngine 必须在 touched 里）、锚点唯一、三语键集与不重复、`R.string` 全解析、**`data.usage` 的 import 解析到真实声明**。

### 新坑 / 手法
- ★★**双向映射要两边都清**：`UsageRunContexts.mark` 最初只清「新 run 的旧会话」一侧，漏了「本会话旧 run 的反向映射」⇒ 覆盖后再 `unmarkRun(旧run)` 会**误删新条目**。本地 harness 第一轮即抓到（26 例 1 败）。教训：**一对多映射的替换要同时维护正反两张表**。
- ★ 沿用：**stage 是唯一真相源**（改了 `UsageRunContexts.kt` 后重新 `put.sh` 并覆盖 REPO 再跑 harness）；`curl -sSL` 剥 Authorization；stdlib json（jq 会 parse error）；artifact 按 name 过滤。
- ★ 本卡**有意打破**「少动 GenerationLoop」红线：ambient `UsageCallContext` 只在模型调用点构造，那里就在 GenerationLoop —— 不改则无 run 归属。

## P2-13 完成记录（2026-10-03，PR #34 / squash `217df6b7`，**CI 三 run 全绿**）

**口径（用户 ask_user 全 A）**：① used ＝ **会话级累计**（`usage_records.parent_run_id = 父会话id` 的 in+out 之和，复用 `tokensForParentRun` ⇒ **零 DAO 改动**）；② 拒派信封**复用 `error` + `detail`**（`budget_exceeded`，与既有 `global_cap_reached` / `assistant_cap_reached` 同形，detail 带 used / budget / 超额三个数）；③ 回传字段＝`tokens_in` / `tokens_out`（回填 Phase 11 起就没人写过的死字段）+ `calls`（模型往返次数，区别于 `trip_count` 的工具轮）；④ **后台 run 的唤醒消息也带一行 usage 摘要**。

**改动面**：8 文件 / **+390 −0**（4 新文件 = `OrchestrationGate.kt` 68 + `RunUsageSummary.kt` 59 + 两测试 68/92；`SubAgentEngine.kt` +97；`SubAgentRun.kt` / `SubAgentTools.kt` / `AppModule.kt` 各 +2）。

### A. 缺口（侦察实证）
- `OrchestrationBudget`（P2-07，自带 15 例单测）自落地起**生产零调用点** —— `dispatch` 只做递归卫 / 请求校验 / 并发上限，从不读账本 ⇒ 上限是装饰品。
- `SubAgentRun.tokensIn/tokensOut` 自 Phase 11 就有、`encodeRun` 一直在编码，**但全仓无一处写入** ⇒ 派发工具回给父的恒为 `0 / 0`。
- P2-12d 把 `usage_records.run_id / parent_run_id` 真正填上之后，这两处才第一次**可修**。

### B. 闸门落在哪、为什么
`SubAgentEngine.dispatch` 是唯一咽喉（工具 / 工作流 / 外部自动化 / 未来调用者都经它），且 `DispatchResult.Reject → errEnv` 的信封通道现成。位置＝**请求校验之后、并发上限之前**（超预算按政策拒，而不是按容量拒）。
- 天花板：`OrchestrationBudget.effectiveBudget(父助手.orchestrationTokenBudget, 专家.tokenBudget)`（专家覆盖父，D8）。
- **短路**：助手无上限且未指名专家 ⇒ **直接 return，连设置与专家的读都不发生** ⇒ 未配置预算的装机逐字节回到今天。
- `parentChatId == null`（cron / workflow / 外部自动化）⇒ 无编排根，**不设闸**。
- 所有读都 `runCatching`：账本 / 设置抖动 ⇒ **降级为「不拒」**（遥测不得制造新失败模式）。
- 拒绝只针对**已花费**，不预测本次成本 —— 所以「把树推过线的那一次」永远是**下一次**被拒（D8 原文的合同）。达顶即拒＝`used >= budget`。

### C. 回传：为什么必须写在终态之前
`SubAgentRun` 一旦终态即冻结，而唤醒消息读的就是同一条 entry ⇒ `attachRunUsage` 在**写终态之前**调用（timed-out / succeeded / 抛异常三条终态路径各一处）。
- 读法＝`usageLedger.recordsForConversation(子conv.id)` **再按 `runId` 过滤**（而非走 `run_id` 的新 DAO 查询）⇒ **零 DAO 改动 ⇒ `UsageCallRecorderTest.FakeDao` 替身零改动**。
- 必须按 `runId` 过滤：子会话里还混着它没付钱的行（标题生成无 run id —— 12d 有意留的边界），不过滤会把每次 run 都算胖。
- entity 的 `input_tokens` / `output_tokens` 是 **Int**，汇总显式 `.toLong()` 加宽（两测试各有一条守这个）。

### D. 验证硬证据
- **CI 三 run 全绿**：push `37107283538` + PR `37107291788`（sha `e036b2da`）；合并后 master push `37108064782` → `217df6b7`。
- artifact `unit-test-report` 实核：全仓 **2206 tests / 0 failures / 0 skipped**（2190 → **+16**）；逐类确认 `OrchestrationGateTest` **9/0/0**、`RunUsageSummaryTest` **7/0/0**。
- **本地 harness `p213/harness.py`**：真 kotlinc + JUnit4（零 stub；编 `OrchestrationGate` + `RunUsageSummary` + `OrchestrationBudget` + `UsageRecordEntity` + `UsagePurpose`）⇒ **`OK (16 tests)`**。
- **`p213/selfcheck.py` 67/67 PASS**：红线未动（`ChatService.kt` / `GenerationLoop.kt` / `UsageRecordDao.kt` / `UsageRecordingProvider.kt` / `UsageCallRecorder.kt` / `OrchestrationBudget.kt` / `strings.xml` 全不在 touched）、有意改动断言（引擎 / 派发工具 / run / Koin）、锚点唯一、`data.usage` import 解析真实声明、无 DAO 改动 ⇒ 替身零改动。
- 本卡**无 UI、无 strings**（`strings.xml` 零改动）。

### E. 新坑 / 手法
- ★★ **补丁器的「已打过」守卫会命中自己刚插入的文本**：TIMED_OUT 那处插入 `attachRunUsage(runId, conv.id.toString())` 之后，SUCCEEDED 那处的 `already_marker="attachRunUsage"` **立刻变真** ⇒ 该条 edit 直接 ABORT。教训：**守卫标记要取「只有重放才会出现」的整段**，或干脆只靠「锚点唯一 + 该锚点只在未打补丁状态出现」。本次＝删掉那个 marker。
- ★ **半打状态必须清干净再重放**：重放前 `git checkout -- . && git clean -fdq` 回干净 master（`git clean -fd` 不含 `-x`，不会碰 build 目录）。
- ★ 沿用：**stage 是唯一真相源**（改 `patch.py` 后必须重 `put.sh` 再重放）；`curl -sSL` 剥 Authorization；stdlib json。
- 标准重放六步：① 本地改 stage → ② `put.sh` → ③ VPS `git checkout -- . && git clean -fdq` → ④ `patch.py` → ⑤ harness + selfcheck → ⑥ push / PR / CI / squash。

**PHASE2 十张卡（P2-11 拆 4 子卡 + P2-01/02/04/05/06 拆 3 子卡 + P2-07/08/12 拆 4 子卡 + P2-13）全部收口。** 遗留仅 P2-10（可选，递归卫放宽）与一期 T-03 假代理复测尾巴（待真实链式需求）。

---

## P2-14 完成记录（2026-10-03，PR #35 / squash `f518a65d`，**CI 三 run 全绿**）

清扫 §9 的**四个代码侧条目**；装机实测类（§9.1）与一期 T-03 假代理复测不在本卡内。

### A. 四子卡

| 子卡 | 条目 | 改动 |
|---|---|---|
| **14a** | §9.3 #10 | `SubAgentEngine.dispatch` 装 `executionJob.invokeOnCompletion` 兜底；`markTerminal` 拆出非挂起 `markTerminalInRegistry` + `SubAgentStatus.toLedgerStatus()` |
| **14b** | §9.4 #8 | 两个编码器移到新纯文件 `subagent/SubAgentRunEncoder.kt`；`encodeRuns(List<SubAgentRun>)` 补 `tokens_in` / `tokens_out` / `calls`；`SubAgentTools` 删掉编码器与已无用的 `addJsonObject` import |
| **14c** | §9.2 #9 | `checkOrchestrationBudget` 把 `parentChatId == null` 的早返回**移到预算解析之后**，命中即 `Log.w` 后放行 |
| **14d** | §9.4 #11 | `OrchestrationTree` 增 `budget`（助手级上限，工厂入参 `budgetOfConversation`）+ `hasBudget` / `remaining` / `budgetExceeded`；`StatsVM` 供上限；`StatsPage` 编排树卡片渲染页脚；新增 3 语言字符串 `stats_page_orchestration_budget` |

### B. 改动面（11 文件，+426 −55）

- 新：`subagent/SubAgentRunEncoder.kt`（71）、`test/.../SubAgentRunEncoderListTest.kt`（87，5 例）、`test/.../OrchestrationTreeBudgetTest.kt`（121，6 例）
- 改：`SubAgentEngine.kt`（+89）、`SubAgentTools.kt`（−39）、`OrchestrationTree.kt`（+31）、`StatsPage.kt`（+20）、`StatsVM.kt`（+20）、三份 `strings.xml`（各 +1）
- **不碰**：`OrchestrationBudget.kt`（只调用、不改）、`SubAgentRegistry.kt`、`SubAgentRun.kt`、`AppModule.kt`、所有账本 / DAO 红线文件。

### C. 三个口径决定

1. **14a 落在 `invokeOnCompletion` 而不是 `executeRun` 的 finally**：§9.3 原建议是给 `executeRun` 加兜底，但「协程尚未启动即被取消」时 `executeRun` 的函数体根本不执行、finally 也不跑 —— 只有完成回调能覆盖这一格，且它同时覆盖 try 之前的挂起点。
2. **14a 只在非终态时写**：`markTerminal` 是无条件覆盖，成功 / 失败 / 超时早已写好的终态不能被兜底降级 ⇒ 先读 `registry.get(runId)?.status` 再决定。registry 翻转同步（`StateFlow.update`），只有账本镜像需要 `appScope.launch`。
3. **14c 选「记日志」而非「拒绝」**：无会话根 ⇒ 没有可求和的编排根，拒绝会把「缺锚点」变成该路径全新的失败模式；放行 + warn 既保持零影响又留下痕迹。今天 `dispatch` 唯一调用方是 `subagent_dispatch`（总带 chat id），实际影响仍为零。

### D. 验证硬证据

- push `37110355416` / PR `37110360533` / master `37111117701`，**三 run 全绿**。
- artifact 实核：**2217 tests / 0 failures / 0 skipped**（2206 → +11）。
- 本地 harness：`OK (33 tests)`（含既有 `OrchestrationTreeTest` 18 例 + `SubAgentToolsEncodeRunTest` 4 例回归）。
- `selfcheck.py` 67/67。

### E. 新坑 / 手法

1. ★ **补丁器的 `already_marker` 遇到「删除类」编辑必炸**：把要删掉的原文当 already_marker，首跑就命中自己（P2-13 那条坑的镜像面）。删除类编辑改用 **anchor 计数**兜底（改完 anchor 归零 ⇒ 重放自然 ABORT）；只有「新增文本」才配 already_marker。
2. ★ **「插在既有行之后」的编辑必须带 already_marker**：字符串这类锚点插入后锚点仍在的编辑，不带守卫会重放成两行。
3. **harness 要编 `SubAgentRun.kt` 就得有 T-04 的 `SubAgentContextDigest`**：该文件拖入 `com.clma.ai.ui`（Compose），与卡无关 ⇒ 用 5 行哨兵对象（同 `MAX_TURNS = 10`）保住纯逻辑可测，是全卡唯一 stub。

---

## P2-15 完成记录（2026-10-03，PR #36 / squash `e394e221`，**CI 双 run 全绿**）

**性质**＝§9.2 #7（第二条）收口，非新功能卡。**改动面 4 文件 / +121 −15**（1 新文件）。CI：push `37113278363` + PR `37113286116` 双 run 全绿；**2223 tests / 0 failures / 0 skipped**（2217 → +6）；本地 harness **OK (15 tests)**；selfcheck **43/43**。**当前 master＝`e394e221`**。

### A. 缺口与口径
父能看到**已花**（P2-13 的 `tokens_in` / `tokens_out` / `calls`）与**上限**（P2-14d 的统计页），但**看不到剩余** ⇒ 只能撞墙后从拒绝原因读到，无法提前自我节流。

口径：`remaining = 上限 − 派发时刻已花`（`OrchestrationBudget.remaining`，钳 0）；**本次 run 尚未计入**（它还没跑、成本未知）——与闸门 pre-flight 契约（D8）一致，故「模型读到的数」＝「下次判定用的数」。

### B. 为什么把闸门的读带出来，而不新读一次
`checkOrchestrationBudget` 为判定**本就**读过「上限 + 已花」。改返回值 `Reject?` → `BudgetGate(refusal, remaining)`，**判定一字未改**（同一 `OrchestrationGate.decide` / 同一拒绝信封 / 同一 no-root warn / 同一短路由）。`DispatchResult.Ok` 增 `budgetRemaining: Long? = null`（默认 null ⇒ 既有构造点语义不变）；`encodeRun` 增同名可选参数，**仅当有上限时**才 `put("budget_remaining", …)`。

### C. 刻意不做
① 拒绝信封不加（remaining 恒 0，detail 已说明超了多少）；② `subagent_get` / `subagent_list` 不加（registry 不带上限，无剩余可报）。

### D. 验证硬证据
- 本地 harness：新 `SubAgentRunEncoderBudgetTest` 6 例（「无上限 ⇒ 键不存在」红线断言 / 「0 照常上报」/「不打乱 run 自身计数」/「encodeRuns 永不带」）。
- selfcheck 43/43：判定语句未变 + 两处 Ok 都带 remaining + 三处 allow-and-forget 返回空 gate + `subagent_get` 仍调无参编码器 + 十个红线文件未动。
- CI artifact：`testDebugUnitTest` **2223 / 0 / 0**，新类 6/0/0。

### E. 新坑 / 手法
1. **锚点唯一 ≠ 未打过**（P2-06c 老坑的复现面）：`BudgetGate` 的注释块插在 `sealed class DispatchResult {` 之前，该 anchor 重放仍存在 ⇒ 必须带 `already_marker='private data class BudgetGate('`；而「替换型」编辑（签名、`return null` → `return BudgetGate(null, null)`）天然由「锚点消失」守卫。
2. **`Ok(...)` 两处构造点同形**（后台 / 前台各一）⇒ 锚点必须带上下文（前者带注释行，后者带 `}\n    }`）才能各自唯一。

---

## P2-26 完成记录（2026-10-04，PR #47 / squash `e45e383c`，**CI 双 run 全绿**）

**性质**＝D6 剩余部分收口（不改 SQL / DAO）。**改动面 8 文件 / +121 −8**。CI：push `37144065910` + PR `37144070287` 双 run 全绿；**2245 tests / 0 failures / 0 skipped**；本地 harness **OK (35 tests)**；selfcheck **12/12**。

### A. 缺口
`ACCEPT-FIX.md` 明载：P2-19 只给**消息页 footer** 补了 tok/s，统计页四个维度卡（按日 / 按用途 / 按模型 / 按助手）**没做**，因为 `UsageStatBucket` 不聚合延迟。账本 `usage_records.latency_ms` 早已入库 ⇒ 只缺聚合。

### B. 交付
`UsageStatBucket` 增 `measuredOutputTokens` / `generationMs`（**无默认值**，逼编译器点名所有构造点）+ 计算属性 `tokensPerSecond: Double?`；`UsageStatsFactory.Acc` 只对**上报了延迟**的行同时累加两项；`UsagePurposeGroups.sum` 合并分组时两项各自求和（组速率＝组总输出 / 组总时长，不是各行速率的平均）；`LedgerStatsView.EMPTY` 补 0；`StatsPage.LedgerBucketRow` 展示 + `formatRate`（一位小数）；三语 `stats_page_ledger_tok_per_sec`。

### C. 口径（与 P2-19 一致）
分子＝上报了延迟的行的 `output_tokens` 之和，分母＝同批行 `latency_ms` 之和 ⇒ 未上报的调用**分子分母都不参与**（不虚高也不稀释）；无人上报 ⇒ `null`（不显示），上报但总和为 0 ⇒ 同样 `null`（防除零）。**绝不编造 0**。

### D. 新坑 / 手法
1. **无默认值字段当「编译器 checklist」**：给 data class 新增**无默认值**的 `val`，Kotlin 会报出所有构造点（本例 4 处），比 grep 可靠。
2. ★ **`put.sh` 上传的脚本里 grep 的是 VPS 侧 `/opt/mcp-gw/env`** —— 在**本地**工作区直接跑 `python3 p226/check_ci.py` 会因 token 为空而 `KeyError: workflow_runs`；CI 脚本一律经 `/workspace/vps.sh` 在 VPS 上执行。

---

## P2-27 完成记录（2026-10-04，PR #48 / squash `100f226e`，**CI 双 run 全绿**）

**性质**＝D3 收口（用户拍板：范围 **B**＝入口+传输+子智能体；门控 **A**＝扩展 `Modality`，不加布尔字段）。**改动面 18 文件 / +514 −8**（2 新文件）。CI：push `37145532436` + PR `37145539595` 双 run 全绿；**2260 tests / 0 failures / 0 skipped**；本地 harness **OK (10 tests)**；selfcheck **20/20**。

### A. 根因（两层，缺一不可）
1. `FilesPicker` 把音视频按钮硬门控在 `provider is ProviderSetting.Google` ⇒ DeepSeek/DashScope 用户**看不到按钮**。
2. OpenAI 兼容通道 `ChatCompletionsAPI.addNonAssistantMessage` 的 `when(part)` 只有 Text/Image，其余 `else -> {}` **静默丢弃**；`isOnlyTextPart()` 又只把 Text+Image 计入「要发的 part」⇒ `[Audio, Text]` 塌缩成纯文本。**只有 `GoogleProvider` 会序列化 Video/Audio。**

### B. 交付
- **枚举**：`Modality` 增 `AUDIO` / `VIDEO`（穷尽 `when` 仅 4 处，两文件，全部同步）。
- **入口**：picker 门控＝`provider is Google || Modality.X in currentModel.inputModalities`；`FilesManager.getFileSize` + picker 超限 toast（视频 100MB / 音频 20MB，**工程经验值非服务端上限**，注释已写明）。
- **传输**：新纯文件 `ai/.../openai/OpenAIMultimodalParts.kt`（`input_audio{data,format}` / `video_url{url}` + 占位常量）；字节编码仍留在 `FileEncoder`，以 `encode: () -> String?` 注入 ⇒ **模型不支持时一次盘都不读**，且块形状可无设备单测。
- **子智能体**：`SubAgentRequest.attachParentMedia` ← `subagent_dispatch` 的 `attach_media`；`SubAgentContextDigest.mediaPartsFrom()` 取**最新一条 user 消息**的 Audio/Video；`SubAgentEngine.withParentMedia` best-effort 解析（失败降级为空，绝不失败派发）；首条消息＝`mediaParts + Text`；`encodeRun` 仅在 `>0` 时出 `media_parts`。
- **测试**：`OpenAIMultimodalPartsTest` 10 例（CI 跑）+ `SubAgentContextDigestTest` +5 例。

### C. 刻意不做
- **图片不进子智能体**（`turnsFrom` 的 only-text-by-construction 不变）。
- **OpenAI Responses API**（`ResponseAPI.kt`）不加音视频 —— 用户 dashscope 走 ChatCompletions（已知缺口：ResponseAPI 的 `addUserItems` 只 filter Text|Image，整条只有音视频的 user 消息会被丢，已在 PR 说明里记录）。
- **Claude / Google 不动**；`ModelRegistry` 的 `input()/output()` 仍只产 TEXT/IMAGE（新模态由编辑器手工勾选）。

### D. 新坑 / 手法
1. ★ **逐字相同的 `when` 分支要靠「函数尾部」区分**：`addAssistantMessageJson` 与 `addNonAssistantMessage` 各有一份**一字不差**的 Image 分支（含缩进）。锚点必须一直带到 `})\n    }`（前者后面是 `// tool_calls`）才唯一 —— 只带 `else -> {}` 会撞两处。
2. ★ **「读文件动作」要有断言证明它没发生**：占位路径必须**先判模态再调 `encode`**，测试用 `var read = false` 的 lambda 断言「不支持的模型一次都没读盘」。
3. **枚举加值的穷尽 `when` 清单要开工前 grep**：`grep -rn "Modality" --include=*.kt . | grep -v "/build/"` 一次列全（本例恰好只有 2 文件 4 处，且都在 app 模块）。
4. **`--check` 干跑模式**：把 patch.py 改成「先验全部锚点唯一、再落刀」，33 条锚点一次过，避免半打状态来回 `git checkout`。

---

## P2-28 完成记录（2026-10-04，PR #49 / squash `664bba35`，**CI 双 run 全绿**）

**性质**＝D4 **分组半**收口（i18n 半由 P2-20 完成）。**改动面 4 文件 / +97 −69**（无新文件、无新单测）。CI：push `37147889428` + PR `37147894187` 双 run 全绿；selfcheck **13/13**。

### A. 缺口
`ACCEPT-FIX.md` 明载：P2-20 只补了四组能力开关的 zh / zh-rTW 串，**「归入高级 / 实验功能分组」未做** —— `AssistantBasicPage.kt` 是**无分组头的扁平 `FormItem` 列表**，四个协议级开关（模型自压缩 / 渐进 MCP 目录 / 子智能体上下文引用 / 冻结子智能体工具面）和温度、工具结果预算等普通设置挤在同一张大卡里。

### B. 交付（用户拍板 A：页内拆独立卡）
四个开关（及其 3 个内部 `HorizontalDivider`）**逐字**从大卡移出，放进一张新卡：卡首为标题 **「高级 / 实验功能」** + 副标题 **「协议级 / 实验性开关，默认全部关闭…」**（风险提示 + off-by-default 标注），紧跟在大卡之后（背景卡之前）。开关本体零改动。新增 2 串 × 三语：`assistant_page_advanced_section` / `_desc`。

### C. 手法 / 坑
1. ★ **「删除 + 就地重插」用同一个 Python 变量复用整段**：四开关文本 `SWITCH_BLOCK` 只写一遍，Edit1（从大卡删）= `old=SWITCH_BLOCK, new=""`，Edit2（插进新卡）= 新卡 = 标题 + `SWITCH_BLOCK`——缩进恰好一致（都是卡内 12 空格），逐字不差。
2. ★ **`already_marker` 在「挪位」场景是必需的**：删掉再插回后，`SWITCH_BLOCK` 计数仍是 1，**重跑会误删新卡里的那份** —— 两处 Edit 都挂 `P2-28 (D4)` 标记，重跑即中止。
3. ★ **「吃掉闭合 `}`」教训**：Edit2 的 `old` 从大卡的闭合 `}` 起（`GRADIENT_HEAD`），`new` 若不以 `}` 开头，新卡会**嵌进大卡** —— 括号仍能配平但语义错（嵌套卡）。`--check` 抓不出来（锚点唯一），**必须人眼看 diff**。修法：`ADVANCED_CARD` 首行补回 `        }`。

---

## P2-29 完成记录（2026-10-04，PR #50 / squash `695778a3`，**CI 双 run 全绿**）

**性质**＝D5 **残留**收口。**改动面 1 文件 / +21 −4**（无新文件、无新单测）。CI：push `37148688416` + PR `37148690107` 双 run 全绿；selfcheck **8/8**。

### A. 缺口
P2-22（#43）把安装类工具改为「放行 + 产物默认 disabled」（`mcp_add` 默认 `enabled=false`、`skill_install_*` 不再自动启用），PR 里明载残留：**`mcp_update` 省略 `enabled` 仍默认 `true`** —— 编辑一个已禁用的服务器会把它**静默重新启用**，正是 D5 要消除的「未获用户同意就让产物 live」。

### B. 交付（用户拍板 A：保留原状态）
「更新 ≠ 安装」：`mcp_update` 的 `enabled` **改到读到 `old` 之后再解析**，省略时默认 **`old.commonOptions.enable`**（保留当前状态），显式值仍优先。顺带修正 P2-22 漏改的文档：`mcp_add` 的 prose 与 schema 仍写 *default true* → 改 *default false*；`mcp_update` 的 `enabled` 参数原本**连描述都没有** → 补上「省略即保留当前状态」。

### C. 手法 / 坑
1. ★ **默认值要读「已存在实体」时，求值顺序必须下移**：原 `enabled` 在 `old` 之前算，改成 `?: old.commonOptions.enable` 前必须先 `val old = all.firstOrNull{…} ?: return errEnv(...)`。锚点把整段（`rawName…rawUrl…enabled…headers…timeout…all…old`）一起替换，保证唯一。
2. **字段名是 `enable` 不是 `enabled`**：`McpCommonOptions.enable`（`buildConfig(enabled=…)` 落成 `enable=enabled`）—— 别按参数名想字段名。
3. **文档债也是债**：P2-22 改了行为没改「Defaults to true」文案，模型读到的工具说明与实际相反 ⇒ 同批修掉（prose + schema 两处）。

---

## 9. 遗留清单（二期收口复核，2026-10-03）

> 复核方式：GitHub API 实查 **36 个 PR 全部 `merged`**（0 open PR / 0 open issue）+ master **`e394e221`**（P2-15 后）的 CI 双 run 全绿 + §8 表与各卡完成记录逐条对齐。以下为核对出的缺口。
>
> ★ **2026-10-04 增补**：**D4（分组半，P2-28 `664bba35`）与 D5（`mcp_update` 残留，P2-29 `695778a3`）已收口** ⇒ 至此二期验收 13 条问题里，**二期内 8 条（D1/D5/D6/D7/D8/D9/D10/D11）全清**；二期外**仅剩 D2（工作区）/ D13（备份迁移）留在产品池**（D3 已由 P2-27、D12 已由 P2-17、D4 本次收口）。当前 master＝`695778a3`，**50 PR 全 merged**。
>
> ★ **2026-10-03 P2-14 清扫**：代码侧四条（#8 / #9 / #10 / #11）已收口（PR #35 / `f518a65d`）。§9.3 / §9.4 已整节清空（存档）。
>
> ★ **2026-10-03 P2-15 收口**：§9.2 **#7 后半条**已收口（PR #36 / `e394e221`）—— 派发信封现含 `budget_remaining`。**剩余 = 装机实测三类**（§9.1 的 1 / 2 / 3，**已补入 P2-14 自身的 4 条验收 + P2-15 的 1 条**）**与 §9.2 现存三条口径声明（#4~#6）**。

### 9.1 必做（有明确验收条件，但尚未装机验证）

> ★ **逐条勾选清单**：`/workspace/rikkahub-pure/QA-PHASE2.md`（A＝二期 15 条 / B＝一期 T-03 假代理复测 3 条 / C＝§6 待决 4/5；含前置准备、取证命令与结果汇总表）。
1. ✅ **二期整体装机实测＝2026-10-03 已跑完（通过）** —— 用户结论：「**二期功能基本实现，没有明显恶性 bug**」（A 组 15 条视为通过）。过程发现 **13 条问题**全部记录在 `QA-PHASE2.md` **§D**：**二期内 8 条均非恶性**（唯一确诊代码缺陷＝**D11** `OrchestrationTree.build` 用 `run.id` 查 `usageByRun`、键应为 `run.domainId`，1 行可修、影响面限编排树展示）；**二期外 5 条**留产品改进池。★ **未跑**：B 组（一期 T-03 假代理复测）、C 组（§6 待决 4/5）。以下为原逐条验收清单（存档）：
   - **P2-02**：关掉一个工具 → 模型收到 `tool_not_found`；重开恢复。
   - **P2-04**：子 agent 能用父**未启用**的本地工具；被冻结类工具（设备 UI / 逐次确认 / `subagent_*`）一律拒且信封可读。
   - **P2-05**：专家编辑器里调色板能列本地工具目录并带 `source=LOCAL` 徽章。
   - **P2-06**：父建一个专家 → 列表可见 → 派发成功；**杀进程重启后仍在**（含 `SubAgentProfile` 迁移）。
   - **P2-07**：上限 / 并发设置持久化；并发钳 1..8。
   - **P2-08**：编排期锁屏 / 切后台不中断；结束通知消失（★ 且无活跃 run 时**不起**服务）。
   - **P2-12a**：跑一轮含工具的**流式**对话 → `usage_records` 出现 N 条（N＝实际往返数）。
   - **P2-12b/c**：footer 的「N 次调用」与账本一致；统计页「用量账本」四维卡有数。
   - **P2-12d**：统计页「编排树」出现父→子节点，token 按 run 汇总（不再是 0）。
   - **P2-13**：**设小额度 → 第一次派发放行，第二次被拒，且模型能读懂 `budget_exceeded` 的原因**。
   - **P2-14a**（★ 新增补录）：`/stop` 或 `subagent_cancel` 打断一个刚派发、尚在 PENDING 的子 run → `subagent_get` **不卡 RUNNING**，并发槽释放（registry 无残留非终态条目）。
   - **P2-14b**（★ 新增补录）：`subagent_list` 每条含 `tokens_in` / `tokens_out` / `calls`，与 `subagent_get` 同名同值。
   - **P2-14c**（★ 新增补录）：设了助手级预算上限但派发无会话根时 → logcat 可见 warn，行为不变（仍放行）。
   - **P2-14d**（★ 新增补录）：统计页编排树页脚显示「已用 / 预算 / 剩余」，与账本一致（三语言均正常）。
   - **P2-15**（★ 新增补录）：**不配预算**时派发返回体与 P2-14 逐字节一致（无 `budget_remaining` 键）；**配小额度**后派发成功返回体含 `budget_remaining`，且值 = 上限 − 该编排已花。
2. **一期 T-03 假代理复测**（前缀缓存：把 provider base URL 指向自建代理以抓全量请求体，定位 2% 命中的机制）。真机结论（严重亏损）已在 2026-10-02 得出，缺的是机制定位。★ P2-11 的账本现已可用作同一次复测的证据源（一次复测两用）。
3. §6 待决 4：**T-02 失败路径实测**；待决 5：**skill-tester / external-automation 两条路径实测**（按「同一咽喉点」记账）。

### 9.2 已知口径声明（设计决定，非缺陷，需明示；#4~#6 现存，#7 已由 P2-15 收口）
4. **预算只统计「派出去的子 agent」**，不含父自身回合的消耗（Q1=A）—— 一个 10 万 token 的父回合对预算毫无影响。
5. **used 是会话级累计、永不重置**（受账本 90 天保留窗约束）⇒ 长寿会话会自然触顶，只能手动调高上限。若日后要「每回合重置」，需要一个新的时间锚点。
6. **并发扇出存在 race**：N 个派发几乎同时读同一 `used` ⇒ 可以一起过闸、集体超额一截（最坏 ≈ 同时派发数 × 单次花费）。这是「拒绝只看已花费、不预测本次成本」的必然，上限＝助手并发 cap（3..8）。要收口得改成「预留额度」。
7. **父看不到剩余额度**（口径 A 只回 `tokens_in` / `tokens_out` / `calls`，不回 `remaining`）⇒ 模型无法提前自我节流，只能撞墙后读拒绝原因。
   → ⚠ **P2-14d 只解了一半**：统计页**给人看**已解，「父模型看不到剩余额度」仍在（那是 tool 结果信封口径，非本卡范围）。
   → ✅ **P2-15 已收口**（2026-10-03，PR #36 / `e394e221`）：派发信封现含 `budget_remaining`（= 上限 − 派发时刻已花；无上限则不加键）。只动 `SubAgentEngine` 闸门返回值 + `SubAgentTools` 信封 + `SubAgentRunEncoder`，**未改 DAO / 闸门判定 / `OrchestrationBudget` / `OrchestrationGate`**。
8. `subagent_list` 的 `encodeRuns` 仍**不带** token / calls（只有 `subagent_get` 的 `encodeRun` 带）。
   → ✅ **P2-14b 已修**：列表条目补齐 `tokens_in` / `tokens_out` / `calls`，与 `subagent_get` 同名同语义（编码器移到纯文件，harness 可测）。
9. 闸门对 `parentChatId == null` 的派发**不生效**。今天 `engine.dispatch` 只有 `subagent_dispatch` 工具一个调用方（已 grep 实证），实际影响为零；但这是**未来**调用者（工作流 / 外部自动化直接构造 `SubAgentRequest`）的坑。
   → ✅ **P2-14c 已收口**：有上限但无会话根时记 warn 后放行（行为不变），不再静默绕过。

### 9.3 代码走查疑点 → ✅ **已全部收口**（P2-14 / `f518a65d`，存档）
10. ★ **取消早于启动的子 run 会卡在非终态**：`executeRun` 只在 `runSubAgentBody` 的 `try/catch` 内 `markTerminal`，而 `runSubAgentBody` 在进入 try **之前**有一个挂起点（`agentRunRepo.setStatus(running)`）。若 `requestCancel`（`subagent_cancel` 工具 / 用户 `/stop` 的 `cancelAllForParent`）在协程真正开始前或在该挂起点取消，**全链路没有 `invokeOnCompletion` 兜底**（已 grep：subagent 路径无一处）⇒ registry 条目停在 PENDING/RUNNING：占住并发槽（`globalActiveCount` 把 PENDING 也算活跃）、`subagent_get` 永远显示 RUNNING；`AgentRunBootRecovery` 只救**账本行**（还得等重启），registry 那条永远不会自愈。
    - 影响面：Phase 11 起既有，**非 P2-13 引入**；触发窗口窄（竞态），但 `/stop` 与模型主动 `subagent_cancel` 都能踩到。
    - 建议修法：`executeRun` 加 `catch (CancellationException)` / `finally` 兜底，**仅在 run 尚未终态时**写 `CANCELLED`（`markTerminal` 是无条件覆盖，必须先读当前状态再决定写不写）。
    - → ✅ **P2-14a 已修**（同旨，但落在 `dispatch` 的 `executionJob.invokeOnCompletion`——`executeRun` 的 `finally` 对「协程尚未启动即被取消」无效，`invokeOnCompletion` 才能覆盖该格）。

### 9.4 卫生项（不阻塞）→ ✅ **已全部收口**（P2-14 / `f518a65d`，存档）
11. `OrchestrationBudget.remaining()`（P2-07 为「账本 UI 显示剩余额度」预留）**至今无生产调用方**（已 grep：只有它自己的单测）—— P2-12c/12d 都没用它，P2-13 用的是 `overByTokens`/`refusalDetail`。要么接进统计页（顺便解掉 §9.2 第 7 条「父看不到剩余额度」），要么删掉。
    → ✅ **P2-14d 已收口**：接进统计页编排树（`OrchestrationTree.remaining`），现已有生产调用方。
    → ⚠ §9.2 第 7 条**当时只解了一半**：统计页给人看，「父模型看不到剩余额度」仍在（那是 tool 结果信封口径，非本卡范围）。→ ✅ **P2-15 已补上另一半**（派发信封含 `budget_remaining`）。
