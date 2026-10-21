# CLmA

CLmA 是一份个人维护的 Android 端 AI Agent 发行版。  
**A** 表示 Agent。

本项目基于 **[RikkaHub Agent · Pure](https://github.com/wuyhong715/rikkahub-agent-pure)**。  
首先感谢 Pure 的作者 **[wuyhong715](https://github.com/wuyhong715)**：没有 Pure，就没有这份发行版。

## 致谢与谱系

```
rikkahub/rikkahub
   └─ ExTV/rikkahub-agent
        └─ wuyhong715/rikkahub-agent-pure   ← 直接上游
             └─ C2CWL/CLmA                  ← 本仓库
```

- **[RikkaHub](https://github.com/rikkahub/rikkahub)**：原生 Android LLM 客户端。
- **[ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent)**：设备侧 Agent 能力。
- **[wuyhong715/rikkahub-agent-pure](https://github.com/wuyhong715/rikkahub-agent-pure)**：Pure 加固与长期可运行相关能力。**主体能力来自这里，特此致谢。**

CLmA 是独立仓库，由 **[C2CWL](https://github.com/C2CWL)** 维护，与上游的审查、合并节奏无关。

## 本发行版

- 应用名 **CLmA**，包名 `com.clma.app`（可与原版共存）
- 自有图标与版本号（从 0.1 起）
- 会持续开发一些**实验性功能**；是否合入、是否保留，以本仓库为准
- **可能会**不定期同步上游 Pure，**不保证**及时、完整或长期跟进

具体改动以提交记录为准。这些都是上游作品之上的增量，不改变对上游作者的致谢。

## 许可证

与上游 Pure 相同：**GNU Affero General Public License v3.0（AGPL-3.0）**。  
全文见 [LICENSE](LICENSE)。

使用、修改、再分发须遵守 AGPL-3.0（含通过网络提供服务时的源码义务）。

## 构建

Android 8+，Kotlin / Jetpack Compose。构建方式与上游一致，详见上游文档。

发行用英文 Release notes；本 README 为中文说明。

## 链接

- 本仓库：https://github.com/C2CWL/CLmA  
- 上游 Pure：https://github.com/wuyhong715/rikkahub-agent-pure  
- Agent：https://github.com/ExTV/rikkahub-agent  
- RikkaHub：https://github.com/rikkahub/rikkahub  
