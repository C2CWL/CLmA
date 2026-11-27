# CLmA 原生 Android 插件（第一版）

## 目标

在 CLmA 内引入一套原生 Android 插件机制，对标 dsh-go 插件的关键体验：

- 插件以独立 APK 分发，包含清单、Kotlin/Java 实现和可选设置界面。
- 宿主读取 APK 内的 `assets/clma-plugin.json`，校验后加载到宿主进程。
- 插件可注册聊天工具，并可提供原生 Android `View` 作为设置界面。
- 设置中心提供统一的插件入口，支持安装、启用、禁用、卸载。

第一版不包含插件市场、签名链、远程分发或独立进程隔离。

## 插件结构

```text
sample-plugin/
├── src/main/assets/clma-plugin.json
└── src/main/java/.../HelloPlugin.kt
```

清单示例：

```json
{
  "id": "com.example.hello",
  "version": "1.0.0",
  "description": "示例插件",
  "minHostVersion": "1.0.0",
  "entryClass": "com.example.plugin.HelloPlugin",
  "tools": [
    {
      "name": "hello",
      "description": "返回问候语",
      "inputSchemaJson": "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}"
    }
  ],
  "hasSettings": true
}
```

实现类必须实现 `com.clma.plugin.api.ClmaPlugin`：

```kotlin
class HelloPlugin : ClmaPlugin {
    override val descriptor: PluginDescriptor = /* 从 manifest 解析 */
    override suspend fun execute(toolName: String, arguments: String): String = ...
    override fun settingsSurface(context: Context): View? = ...
}
```

## 加载流程

1. 用户在设置中选择 APK，系统复制到应用私有目录 `files/native-plugins/<id>/plugin.apk`。
2. 宿主读取 `assets/clma-plugin.json`，校验 id、版本、入口类名与工具名。
3. 使用 `DexClassLoader` 加载入口类，父加载器为宿主 `context.classLoader`，保证 `ClmaPlugin` API 类型一致。
4. 插件实例注册到 `PluginRepository`，聊天工具通过 `ChatToolFactory` 暴露给模型。
5. 状态持久化在 `files/native-plugins/registry.json`，启用/禁用不会删除 APK。

## 安全边界

- 插件代码在宿主进程中运行，拥有宿主权限；只应安装可信来源的插件。
- 清单 id、版本和工具名做白名单字符校验，避免路径穿越和工具名冲突。
- 宿主版本比较只解析数字主版本，避免预发布后缀导致误判。
- 当前未做 APK 签名校验、权限沙箱或独立进程隔离；这些属于后续版本。

## 已知限制

- 不兼容 dsh-go 的 Node/TypeScript 插件包，需按本 API 重新打包。
- 设置界面仅支持原生 `View`，不支持 Compose 直接作为插件边界。
- 插件升级采用同 id 覆盖安装，不保留旧版本回滚包。
- CI 负责编译验证；本 PR 未在本地执行构建。
