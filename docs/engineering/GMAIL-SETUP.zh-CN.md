# CLmA Gmail 连接与 Google Cloud 配置教程

## 先分清：谁需要配置？

- **使用官方应用的普通用户**：不需要创建 Google Cloud 项目。应用维护者先完成 OAuth 配置，你再选择账号并同意读取 Gmail。
- **应用维护者 / 自行打包者**：需要在 Google Cloud 为应用登记 Android OAuth 客户端，启用 Gmail API，并配置同意屏幕及测试用户。
- **测试账号**：应用仍处于测试模式时，即使账号已登录手机，也必须由项目维护者把该邮箱加入测试用户名单。

登录 Google 账号不等于授予应用 Gmail 权限。编译、单元测试通过也不代表真实授权已验证。

## 普通用户如何连接

1. 确保设备的 Google Play 服务能正常访问 Google。CLmA 的应用内 HTTP 代理不会配置 Play 服务流量。
2. 在 **设置 → 连接器 → Gmail** 点 **连接并授权读取**。
3. 选择需要使用的 Google 账号，并在授权页面同意 Gmail 读取。
4. Gmail 卡片出现邮箱地址才表示连接完成。如果被拒绝，联系维护者核对下方配置，不要反复登录或清空应用数据。

## 开发者分步配置

所有步骤都在**同一个 Google Cloud 项目**中操作。网页可能显示中文或英文，下面同时列出主要菜单名。

### 1. 创建或选择项目

打开 [Google Cloud 控制台](https://console.cloud.google.com/)。点击顶部项目选择器，新建项目（例如 `CLmA`），或选择现有项目。

### 2. 启用 Gmail API

打开 [Gmail API 启用页](https://console.cloud.google.com/apis/enableflow;apiid=gmail.googleapis.com)，确认选中刚才的项目，再点 **启用 / Enable**。如果已启用，保持即可。

API Key 不能代替 OAuth 授权。

### 3. 配置授权同意屏幕

进入 [Google Auth Platform → Branding / 品牌信息](https://console.developers.google.com/auth/branding)。首次使用时按提示点 **Get started**。

填写应用名称、支持邮箱及开发者联系邮箱等必填项；按 Google 要求提供真实应用主页和隐私政策。不要编造网站或把示例网址当作正式隐私政策。

### 4. 设置受众和测试用户

进入 [Audience / 受众](https://console.developers.google.com/auth/audience)：

1. 面向个人 Gmail 账号时选择 **External / 外部**。
2. 测试阶段保留 **Testing / 测试**。
3. 在 **Test users / 测试用户 → Add users / 添加用户** 中，填写实际用于授权的完整 Google 邮箱并保存。

普通用户无法替官方项目自行添加测试用户，需要项目维护者操作。

### 5. 添加读取权限范围

进入 [Data Access / 数据访问权限](https://console.developers.google.com/auth/scopes)，点 **Add or remove scopes / 添加或移除权限**，添加并保存：

```text
https://www.googleapis.com/auth/gmail.readonly
```

先使用读取权限。发送邮件申请 `gmail.send`，远程草稿申请 `gmail.compose`，在 CLmA 开启对应功能时另行授权。不要为了排错直接申请所有 Gmail 权限。

### 6. 创建 Android OAuth 客户端

进入 [Clients / 客户端](https://console.developers.google.com/auth/clients)，点 **Create client / 创建客户端**。

1. 应用类型选 **Android**，不是 Web application。
2. 名称可写 `CLmA Android`。
3. 填入当前安装包名和签名证书 SHA-1。
4. 点创建。

应用内 **Gmail 连接与配置教程** 会读取当前安装包的真实包名和签名指纹，支持分别复制。调试、不同签名和不同包名的构建需要各自匹配的登记。

GitHub 正式版的公开标识（已核对正式签名证书）：

```text
包名：com.clma.app
SHA-1：F2:B9:5E:30:7D:C8:61:58:CB:B5:07:96:5F:DE:63:10:8E:BB:5B:08
```

该指纹不是私钥。此客户端授权方式通过包名和证书标识应用，**不需要在 CLmA 中填写 Google Client ID、客户端密钥或上传 JSON 文件**。

### 7. 回到应用重新授权

保存配置后回到 CLmA，再点 **连接并授权读取**。Google 配置传播可能需要时间；本教程不保证立即生效。

- **状态码 10**：开发者配置错误，核对项目、Android 客户端、包名和 SHA-1。
- **状态码 7**：Google 服务联网错误，检查 Play 服务本身的网络，不只看 CLmA 应用内代理。
- **状态码 16**：SDK 返回取消。
- **未返回结果**：不能据此认定用户主动取消，需要检查 Play 服务与 OAuth 配置。
- **其他数字状态**：保留具体状态给维护者，不展示 SDK 异常消息或 Token。

v1.0.2 把多个非成功结果误报成 `gmail_authorization_cancelled`，旧版提示不能用于判断真实根因。修复后的回调先解析 Google 返回结果，再区分错误。

## 给公众使用前还要做什么

测试用户授权成功不等于应用已经可以向所有用户开放。Gmail 读取和草稿权限涉及受限权限，公开生产使用可能需要 Google 验证及安全评估；以项目控制台和官方要求为准。不能靠教程、添加代码或切换发布状态绕过审核。

不要向聊天、Issue、截图或教程上传 Google 密码、访问 / 刷新令牌、客户端密钥、签名密钥库或服务账号私钥。发邮件和上传草稿仍然要逐次审批，诊断修复不放宽这些边界。

## 官方参考

- [Android 授权接入](https://developer.android.com/identity/authorization)
- [Gmail Java quickstart：启用 API 和授权配置](https://developers.google.com/workspace/gmail/api/quickstart/java)
- [配置 OAuth 同意屏幕](https://developers.google.com/workspace/guides/configure-oauth-consent)
- [创建授权凭据](https://developers.google.com/workspace/guides/create-credentials)
