---
name: mood-tracker
description: 记录每日 1-10 分心情指数与心得随笔，查看情绪走势仪表盘。历史数据持久化存储于 WebView 本地。
license: Apache-2.0
compatibility: 需要启用 JS 技能工具 (run_js)。
metadata:
  upstream: https://github.com/google-ai-edge/gallery
---

# 心情记录器 (Mood Tracker)

## 使用指南

`mood-tracker` 技能用于跟踪与记录你的日常情绪与心理健康状态。你可以为每日心情打分（1 到 10 分），并添加简要的感想留言。

### 支持的操作

#### 1. 记录心情 (Log Mood)
当用户想要记录心情时，调用 `run_js` 工具：
- **script**: `scripts/index.html`
- **data**: 包含以下字段的 JSON 字符串：
  - `action`: "log_mood"
  - `score`: 数值 (1-10)
  - `comment`: 字符串 (选填，记录心情心得)
  - `date`: 字符串。**注意**：解析用户指定的日期。
    - 若用户说“今天”，传入 "today"。
    - 若用户说“昨天”，传入 "yesterday"。
    - 若指定了具体日期（如“3月18日”），格式化为 **YYYY-MM-DD** 或传入对应日期字符串。
    - 若未提及日期，默认使用 "today"。

#### 2. 查询特定日期的心情 (Get Mood for a Specific Date)
当用户询问某一天的心情记录时，调用 `run_js` 工具：
- **script**: `scripts/index.html`
- **data**: 包含以下字段的 JSON 字符串：
  - `action`: "get_mood"
  - `date`: 字符串 (提取用户请求中的日期)

#### 3. 查看历史记录 / 展示仪表盘 (Get History / Show Dashboard)
当用户想要查看近期心情走势（如“上周”、“过去 10 天”）或打开仪表盘时，调用 `run_js` 工具：
- **script**: `scripts/index.html`
- **data**: 包含以下字段的 JSON 字符串：
  - `action`: "get_history"
  - `days`: 数值 (选填，默认 7。例如“上周”设为 7)
  - `show_dashboard`: 布尔值 (选填)

#### 4. 绘制心情走势折线图 (Plot Mood Trends)
当用户希望以图表可视化心情趋势（如“画出我过去 7 天的心情折线图”）时，调用 `run_js` 工具：
- **script**: `scripts/index.html`
- **data**: 包含以下字段的 JSON 字符串：
  - `action`: "get_history"
  - `days`: 数值 (选填，默认 7)
  - `show_dashboard`: `true`
  - **提示**：这将在仪表盘中激活走势图表视图。

#### 5. 分析心情趋势与模式 (Analyze Trends and Patterns)
当用户请求分析情绪变化趋势（如“我最近的心情有什么规律吗？”、“我的状态有好转吗？”）时，按以下步骤处理：
1. 调用 `run_js` 工具，传入 `action: "get_history"` 和合适的天数 `days`（例如月度分析设为 30）。
2. 获取返回的 JSON 历史数据后，综合分析得分与文字留言。
3. 向用户提供富有共情且具有洞察力的回复，涵盖：
   - 整体走势（好转、低落或平稳）。
   - 心情特别好或低落的集中日期特征。
   - 文字留言中反复出现的触发因素或主题。

#### 6. 删除特定日期的记录 (Delete Mood for a Specific Date)
当用户希望仅删除某一天的记录（如“删除我今天的心情记录”）时，调用 `run_js` 工具：
- **script**: `scripts/index.html`
- **data**: 包含以下字段的 JSON 字符串：
  - `action`: "delete_mood"
  - `date`: 字符串 (提取目标日期)

#### 7. 导出数据备份 (Export Data)
当用户需要备份或导出心情数据时，调用 `run_js` 工具：
- **script**: `scripts/index.html`
- **data**: 包含以下字段的 JSON 字符串：
  - `action`: "export_data"

#### 8. 清空所有数据 (Wipe All Data)
当用户希望彻底抹除所有心情记录重新开始时，调用 `run_js` 工具：
- **script**: `scripts/index.html`
- **data**: 包含以下字段的 JSON 字符串：
  - `action`: "wipe_data"

### 示例交互指令

可以通过以下示例与心情记录器交互：

- **记录心情：**
  - “记录我今天心情 8 分，感觉很棒！”
  - “把昨天的情绪记为 2 分”
  - “记录 2026年3月18日 心情 1 分”
  - “今天感觉一般，打个 5 分吧，有点累”
  - “上周五心情挺好的，算 7 分”
  - “帮我记一个 9 分的心情”

- **查看历史：**
  - “查看我的心情记录”
  - “查询上周的心情”
  - “我最近的心情怎么样？”
  - “显示过去 10 天的心情记录”
  - “打开心情仪表盘”
  - “我 3 月 18 号那天的心情是多少？”
  - “我昨天的情绪怎么样？”

- **分析趋势：**
  - “帮我分析过去 30 天的心情变化，有什么规律吗？”
  - “随着时间推移，我的整体状态是在变好还是变坏？”
  - “我的历史记录里有没有连贯的低谷期？”
  - “从我最近的留言来看，有什么影响情绪的模式吗？”

- **删除与清空：**
  - “删除今天的心情记录”
  - “移除昨天的记录”
  - “删除 3 月 18 日的那条记录”
  - “清空我的心情历史记录”（调用 `wipe_data`）
  - “抹除所有数据”（调用 `wipe_data`）

- **图表展示：**
  - “画一张过去 7 天的心情折线图”
  - “展示本月心情走势图”
  - “可视化呈现过去两周的分数”
  - “绘制我的情绪变化图表”

### 规则与限制
- **隐私性**：所有心情数据均安全存储在设备本地。
- **无匹配记录**：若所查询的日期无任何记录，明确告知用户该日期未记录心情。
- **覆盖更新**：为已有记录的同一日期再次打分时，将直接更新该日期的记录。
- **仪表盘呈现**：仅在用户显式要求查看历史或仪表盘时才弹出仪表盘。

## 归属说明

基于 Apache-2.0 许可证移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)。版权所有 Google LLC。
