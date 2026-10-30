---
name: openclaw-converter
description: 将来自 ClawHub（或 Markdown 原文）的 OpenClaw 技能转换为 CLmA 兼容技能。在用户提供 OpenClaw 技能链接、GitHub 仓库或原始技能 Markdown 时使用。
---

# OpenClaw 转 CLmA 技能转换器 (OpenClaw to CLmA Skill Converter)

将来自 ClawHub（或 Markdown 原文）的 OpenClaw 技能转换为与 CLmA 规范兼容的技能格式。当用户提供 OpenClaw 技能链接、GitHub 链接或技能 Markdown 文本时自动执行。

## 如何拉取技能源文档

1. ClawHub 页面 (`https://clawhub.ai/<owner>/<slug>`)：页面为 JS 单页应用，普通的网页抓取可能返回空。请使用内置浏览器工具：通过 `browser_open` 打开该链接，等待页面渲染完成后提取渲染出的 SKILL.md 文本（截取前 16000 字符以内），随后关闭浏览器。
2. GitHub 原始链接：若已知仓库地址，优先尝试读取 `https://raw.githubusercontent.com/<owner>/<repo>/main/SKILL.md`；部分技能位于 `https://github.com/<owner>/<repo>/tree/main/skills/<name>` 路径下。
3. 用户直接粘贴的 Markdown：若用户直接提供了 SKILL.md 文本，则直接以此为输入。

## 转换适配规则

### 路径映射 (Paths)

| OpenClaw 路径 | CLmA 对应路径 |
|---|---|
| `~/.openclaw/workspace/` | `~/` |
| `~/.openclaw/workspace/.learnings/` | `~/learnings/` |
| `~/.openclaw/skills/<name>/` | 通过 `skill_install_from_text` 安装；文件存放在 `~/` |
| 任意工程根目录的 `.learnings/` | `~/learnings/` |

### 工具调用映射 (Tool references)

| OpenClaw 工具 / 概念 | CLmA 等价能力 | 适配说明 |
|---|---|---|
| `sessions_list` | 暂不支持 | 移除该章节或注明环境限制 |
| `sessions_history` | 暂不支持 | 移除该章节或注明环境限制 |
| `sessions_send` | 暂不支持 | 移除该调用并说明跨会话通信不可用，不映射为消息通知 |
| `sessions_spawn` | `subagent_dispatch` | 仅在用户启用了子智能体分发时可用 |
| `clawdhub install <name>` | `skill_install_from_url` 或 `skill_install_from_text` | 替换安装说明指令 |
| `openclaw hooks enable` | `schedule_job` 或 `workflow_create` | Hook 机制转换为定时任务或自动化工作流 |
| `memory` (OpenClaw) | `memory_tool` (create/edit/delete) | 概念相同，接口参数调整 |
| Shell 终端命令 | `termux_run_command` | Root 权限命令添加 `su -c` 前缀 |
| 文件读写操作 | `write_text_file`, `read_file`, `list_files` | 保持路径一致，适配为对应工具 |

### 存储目标映射 (Promotion targets)

| OpenClaw 目标 | CLmA 目标 |
|---|---|
| `CLAUDE.md` | 工程根目录同名文件（若存在对应项目）或 `~/learnings/` |
| `AGENTS.md` | CLmA 技能文件或 `memory_tool` |
| `SOUL.md` | `memory_tool`（用于持久化行为准则） |
| `TOOLS.md` | 更新对应技能的工具使用说明 |
| `MEMORY.md` | `memory_tool` |

### 需要剔除或替换的章节

- “OpenClaw Setup” / “OpenClaw Workspace Structure”：替换为 CLmA 的工作区路径说明。
- “Inter-Session Communication”：移除 `sessions_*` 工具；若该设计至关重要，明确说明当前不支持跨会话通信，不虚构替代工具。
- “Hook Integration” / “Enable Hook”：替换为 CLmA 的工作流 (`workflow_create`) 或定时任务 (`schedule_job`)。
- “Claude Code / Codex Setup”：直接删除（针对其他外部平台）。
- 通过 `clawdhub` 或 `git clone` 安装：替换为“通过 `skill_install_from_url` 或 `skill_install_from_text` 安装”以及 CLmA 兼容路径。

### 保持原样的部分

日志记录格式（如 LEARNINGS.md / ERRORS.md 的文件结构）、触发时机、优先级策略、具体核心业务逻辑。

### 格式头部规范 (Frontmatter)

CLmA 技能使用标准的 YAML Frontmatter 块：包含 `name`、`description` 和 `auto_load`。清除 OpenClaw 特有的 frontmatter 字段，并将一级标题 `# Heading` 作为技能展示标题。

### 命名规范

短横线命名法（kebab-case），小写字母，最长 40 个字符（例如：`github-pr-review`、`obsidian-sync`）。

## 执行转换的标准工作流

```
用户：“帮我拉取并转换这个技能 https://clawhub.ai/owner/skill-name”
1. browser_open(url="https://clawhub.ai/owner/skill-name")
2. 等待内容渲染
3. 提取 SKILL.md 文本
4. browser_close()
5. 应用上述转换规则生成适配后的 Markdown
6. skill_install_from_text(content=adapted, name="skill-name",
     source_label="Converted from <url> for CLmA")
7. 初始化所需的配套目录结构
8. 向用户汇报转换与安装结果
```

## 已知边界情况

- 极简技能（仅有寥寥数段）：通常仅需调整工作路径与工具名称。
- 带有 Hook 脚本的技能：OpenClaw 特有的 `scripts/` 目录与 Hook 设置需转换为 CLmA 工作流/定时任务，或予以精简移除。
- 引用 `~/.openclaw/` 路径的技能：全部重定向至 `~/` 等价路径。
- 带有 YAML frontmatter 的技能：统一规范化顶部声明。
- 已存在的技能：同名调用 `skill_install_from_text` 会直接就地覆盖更新。优先更新而非重命名强行新建。
