---
name: calculate-hash
description: 通过 WebView 的 WebCrypto API 计算指定文本的 SHA-1 哈希值。
license: Apache-2.0
compatibility: 需要启用 JS 技能工具 (run_js)。
metadata:
  upstream: https://github.com/google-ai-edge/gallery
---

# 计算哈希值 (Calculate hash)

此技能用于计算指定文本的哈希值。

## 示例触发词

* "计算...的哈希值"
* "...的哈希值是多少"
* "Calculate hash of..."

## 调用说明

使用以下参数调用 `run_js` 工具：

- skill_name: `calculate-hash`
- script: `scripts/index.html`
- data: 包含以下字段的 JSON 字符串
  - text: 需要计算哈希值的文本

## 归属说明

基于 Apache-2.0 许可证移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)。版权所有 Google LLC。
