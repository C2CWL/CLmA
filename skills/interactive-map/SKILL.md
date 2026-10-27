---
name: interactive-map
description: 显示指定地点的交互式 Google 地图嵌入组件。返回聊天界面可直接渲染的 WebView 链接。
license: Apache-2.0
compatibility: 需要启用 JS 技能工具 (run_js) 并具备网络访问权限。
metadata:
  upstream: https://github.com/google-ai-edge/gallery
---

# 交互式地图 (Interactive map)

## 示例触发词

- "在交互式地图上显示[地点]"
- "在地图上查找[地点]"
- "Show [place] on interactive map"

## 调用说明

使用以下参数调用 `run_js` 工具：

- skill_name: `interactive-map`
- script: `scripts/index.html`
- data: 包含以下字段的 JSON 字符串
  - location: 要在地图上显示的地点名称。

## 归属说明

基于 Apache-2.0 许可证移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)。版权所有 Google LLC。
