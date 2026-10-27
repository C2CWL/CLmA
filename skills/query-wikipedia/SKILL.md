---
name: query-wikipedia
description: 模糊搜索 Wikipedia 词条，获取引言概述与信息框摘要。需要联网。
license: Apache-2.0
compatibility: 需要启用 JS 技能工具 (run_js) 并具备网络访问权限。
metadata:
  upstream: https://github.com/google-ai-edge/gallery
---

# 维基百科查询 (Query Wiki)

## 调用说明

使用 `script: scripts/index.html` 调用 `run_js` 工具，并将 `data` 设为包含以下字段的 JSON 字符串：
- **topic**: 必填。仅提取核心实体、人物或事件名称（例如："2026 Oscars"、"阿尔伯特·爱因斯坦"）。必须去除所有具体问题细节、动作词或口语化文本（例如：切勿包含“获奖者”、“最佳影片”、“谁赢了”、“...的历史”等字眼）。只搜索宽泛主题，以便工具检索对应的主条目。
- **lang**: 必填。两位语言代码。该代码必须与你在 `topic` 字段中提供的关键词语言相匹配。使用标准代码，例如："zh" (中文)、"en" (英语)、"ja" (日语)、"ko" (韩语)、"fr" (法语)、"de" (德语)、"es" (西班牙语)、"ru" (俄语) 等。

**约束限制：**
- 提供简明扼要的摘要（1-3 句完整句子）以节省上下文。始终确保回复以完整的句子结尾。你的回复必须与用户原始提问的语言保持一致。
- 对于周期性事件或具备时效性的事实，查询具体的年份/届数（例如："2026 Oscars"）。如果用户省略了年份，默认使用当前年份。
- 如果提取的内容中未找到用户提问的精确答案，请简要说明，并主动提供提取文本中包含的与该主题相关的其他有效信息。

## 归属说明

基于 Apache-2.0 许可证移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)。版权所有 Google LLC。
