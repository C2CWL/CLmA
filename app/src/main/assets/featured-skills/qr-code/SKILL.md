---
name: qr-code
description: 为指定链接生成 512x512 PNG 格式二维码。返回 Base64 编码图片，完全支持离线运行。
license: Apache-2.0
compatibility: 需要启用 JS 技能工具 (run_js)。
metadata:
  upstream: https://github.com/google-ai-edge/gallery
---

# 调用说明

必须使用以下参数调用 `run_js` 工具：

- skill_name: `qr-code`
- script: `scripts/index.html`
- data: 包含以下字段的 JSON 字符串：
  - url: 字符串 - 需要生成二维码的链接地址

## 归属说明

基于 Apache-2.0 许可证移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)。版权所有 Google LLC。
