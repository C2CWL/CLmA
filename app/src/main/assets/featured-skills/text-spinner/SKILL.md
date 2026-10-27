---
name: text-spinner
description: 在 WebView 内渲染 3D 旋转立体文字标签。返回聊天界面可嵌入的相对 WebView 链接。
license: Apache-2.0
compatibility: 需要启用 JS 技能工具 (run_js)。
metadata:
  upstream: https://github.com/google-ai-edge/gallery
---

# 调用说明

必须使用以下参数调用 `run_js` 工具：

- skill_name: `text-spinner`
- script: `scripts/index.html`
- data: 包含以下字段的 JSON 字符串：
  - label: 要进行 3D 旋转展示的文本字符串。

## 归属说明

基于 Apache-2.0 许可证移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)。版权所有 Google LLC。
