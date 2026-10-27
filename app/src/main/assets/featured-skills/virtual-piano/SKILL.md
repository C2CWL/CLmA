---
name: virtual-piano
description: 在 WebView 内展示可弹奏的 88 键虚拟钢琴，带有合成音符音频。
license: Apache-2.0
compatibility: 需要启用 JS 技能工具 (run_js)。
metadata:
  upstream: https://github.com/google-ai-edge/gallery
---

# 虚拟钢琴 (Virtual Piano)

一个可交互弹奏、横向滚动的 88 键虚拟钢琴键盘，基于 Web Audio 合成音效。

## 结构说明
- `scripts/index.html`: 本地入口文件，负责加载 `scripts/index.js`。
- `scripts/index.js`: 返回指向 `assets/` 下本地界面的 WebView 链接 `ui.html?v=<timestamp>`。
- `assets/ui.html`: 钢琴键盘界面（3D 视觉、88 键、横向滑动浏览）；音符通过 WebAudio 实时合成。

## 示例触发词
- "打开虚拟钢琴"
- "弹钢琴"
- "我想弹琴"
- "显示钢琴键盘"
- "Open virtual piano"

## 调用说明

使用以下参数调用 `run_js` 工具：
- skill_name: `virtual-piano`
- script: `scripts/index.html`
- data: JSON 字符串（任意有效载荷，该技能会忽略具体参数）。

该技能返回一个带有相对路径 (`ui.html?v=...`) 的 `webview` 结果。聊天界面会自动处理嵌入式 WebView 的渲染；如果无法内嵌 iframe 则会降级显示为可点击卡片。

主页：<https://github.com/google-ai-edge/gallery/tree/main/skills/featured/virtual-piano>

## 归属说明

基于 Apache-2.0 许可证移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)。版权所有 Google LLC。
