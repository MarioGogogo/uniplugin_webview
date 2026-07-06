# WebView 安全加固修复报告

| 项目 | 信息 |
|------|------|
| 分支 | `fix/app-store-security` |
| 修复日期 | 2026-05-28 |
| 涉及文件 | `WebViewComponent.java`、`plus-bridge-test.html`、`wechat-chat.html` |

---

## 修复问题总览

| 编号 | 问题 | 状态 |
|------|------|------|
| 6 | WebView 潜在跨站脚本攻击风险（XSS） | 已修复 |
| 7 | 允许 WebView 访问本地任意脚本 | 已修复 |
| 8 | 动态注入攻击风险 | 已修复（插件层面） |
| 9 | innerHTML 的 XSS 攻击漏洞 | 已修复 |

---

## 问题 6：WebView 跨站脚本攻击风险（XSS）

### 风险描述

`WebViewComponent.java` 中调用 `setJavaScriptEnabled(true)` 开启了 JavaScript 执行权限，若未对交互数据进行严格限制，易遭受 DOM 型 XSS 攻击。

### 修复方案

由于插件核心功能依赖 JS Bridge 通信（`postMessage`、`callHandler`、`evaluateJavascript`），JavaScript 必须保持开启。修复重点放在**输入数据校验和过滤**上。

#### 1. HTML 内容安全过滤

新增 `sanitizeHtml()` 方法，对 `html` 属性传入的内容进行三层过滤：

```java
private String sanitizeHtml(String html) {
    // 移除 <script>...</script> 标签
    // 移除 onXxx 事件属性（onclick, onerror 等）
    // 移除 javascript: 协议
}
```

#### 2. JS Bridge handlerName 校验

`callHandler()` 新增正则校验，只允许合法方法名：

```java
if (!handlerName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
    return; // 拒绝非法 handlerName
}
```

#### 3. 消息大小限制

新增 `isValidJsonMessage()` 限制单条消息不超过 64KB，防止超大 payload 攻击：

```java
if (message.length() > 1024 * 64) return false;
```

### 修改文件

- `WebViewComponent.java` — `setHtml()`、`JsBridge.postMessage()`、`JsBridge.callHandler()`

---

## 问题 7：允许 WebView 访问本地任意脚本

### 风险描述

WebView 未调用 `setAllowFileAccess(false)` 禁用本地文件系统访问，允许加载设备本地任意脚本，存在本地数据泄露隐患。

### 修复方案

线上发布后只加载远程 URL，不再需要本地文件访问能力。全面关闭文件访问权限：

#### 1. WebSettings 安全配置

```java
settings.setAllowFileAccess(false);
settings.setAllowFileAccessFromFileURLs(false);
settings.setAllowUniversalAccessFromFileURLs(false);
```

#### 2. URL 协议白名单

`setSrc()` 新增协议校验，只允许 `http://` 和 `https://`：

```java
if (!url.startsWith("http://") && !url.startsWith("https://")) {
    Log.w(TAG, "仅支持 http/https 协议，拒绝加载：" + url);
    return;
}
```

#### 3. 拦截 file:// 导航

`shouldOverrideUrlLoading` 新增 file:// 协议拦截，防止页面内跳转到本地文件：

```java
if (url.startsWith("file://")) {
    Log.w(TAG, "拦截 file:// 协议，拒绝加载：" + url);
    return true;
}
```

#### 4. 移除本地路径转换代码

删除以下不再使用的方法和常量（共约 120 行）：

- `IS_ANDROID_STUDIO` 环境开关常量
- `ASSETS_WWW_PATH` assets 路径常量
- `convertToWebViewUrl()` 路径转换方法
- `getUniAppExternalWwwPath()` 外部存储路径扫描方法

### 修改文件

- `WebViewComponent.java` — `setupWebView()`、`setSrc()`、`shouldOverrideUrlLoading()`、移除路径转换相关代码

---

## 问题 8：动态注入攻击风险

### 风险描述

攻击者可利用系统 API 将恶意 `.so` 或 `.dex` 文件写入目标进程并强行执行，实施 Hook 监控或窃取敏感信息。

### 修复方案（插件层面）

#### 1. Intent 安全检查

`shouldOverrideUrlLoading` 中新增 Intent 携带 `component` / `package` 的拦截：

```java
if (intent.getComponent() != null || intent.getPackage() != null) {
    Log.w(TAG, "拦截可疑 Intent（含 component/package）：" + url);
    return true;
}
```

阻止通过 `intent://` scheme 指定目标组件的动态注入攻击。

#### 2. file:// 拦截

与问题 7 合并处理，拦截所有 `file://` 协议的加载请求，防止加载本地恶意脚本文件。

### 修改文件

- `WebViewComponent.java` — `shouldOverrideUrlLoading()`

### 备注

> 问题 8 中"防注入内存保护"（阻止非授权 .so/.dex 加载）属于系统级加固，需要在 Application 层或 native 层实现（如完整性校验、反调试检测等）。插件层面只能做到 URL/Intent 级别的拦截。如需完整防护，建议在主 App 模块中集成专业安全 SDK。

---

## 问题 9：innerHTML 的 XSS 攻击漏洞

### 风险描述

`plus-bridge-test.html`、`wechat-chat.html` 等前端文件中存在直接对 `innerHTML` 赋值的行为，是 XSS 注入点。

### 修复方案

这些文件为调试/测试用途的 HTML 文件，不属于插件核心功能，线上发布不需要。直接删除：

| 删除文件 | innerHTML 使用次数 | 说明 |
|----------|-------------------|------|
| `app/src/main/assets/apps/__UNI__BE1144F/www/static/plus-bridge-test.html` | 14 处 | JS Bridge 调试测试页 |
| `app/src/main/assets/apps/__UNI__BE1144F/www/static/wechat-chat.html` | 5 处 | 微信聊天模拟测试页 |

> 注：审核报告中提到的 `idcard-test.html`、`nfc-simple.html` 等文件在项目中不存在，可能是其他版本的遗留描述。

### 删除文件

- `app/src/main/assets/apps/__UNI__BE1144F/www/static/plus-bridge-test.html`
- `app/src/main/assets/apps/__UNI__BE1144F/www/static/wechat-chat.html`

---

## 变更统计

```
 WebViewComponent.java  | 241 +++++----------------
 1 file changed, 59 insertions(+), 182 deletions(-)

 plus-bridge-test.html  | deleted
 wechat-chat.html       | deleted
```

---

## 验证建议

1. **功能测试**：确认 WebView 加载远程 URL（http/https）正常工作
2. **JS Bridge 测试**：确认 `postMessage` 和 `callHandler` 通信正常
3. **安全测试**：尝试传入 `file://` 路径、含 `<script>` 的 HTML、非法 handlerName，确认均被拦截
4. **回归测试**：确认 `evaluateJavascript`、`clearSessionAndReload`、`canGoBack` 等方法正常工作
