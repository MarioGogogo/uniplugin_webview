# uniplugin_webview 模块文档

<!-- 导航面包屑：[项目根目录](../CLAUDE.md) > 模块 > uniplugin_webview -->

## 模块概述

WebView 原生插件，包含 **WebView 组件** 和 **WebView 模块** 两部分：
- **WebViewComponent**: 在 uni-app 中嵌入原生 WebView 组件，支持加载 URL 或 HTML 内容
- **WebViewModule**: 提供全局 WebView 管理功能，包括 Cookie 管理、缓存清理、外部浏览器调用等

## 配置信息

| 属性 | 值 |
|------|-----|
| **命名空间** | `uni.dcloud.io.uniplugin_webview` |
| **编译 SDK** | 29 |
| **最低 SDK** | 16 |
| **目标 SDK** | 28 |
| **模块类型** | `com.android.library` |

## 依赖关系

### Compile Only
- `uniapp-v8-release.aar` - UniApp 核心库
- `androidx.recyclerview:recyclerview:1.0.0`
- `androidx.appcompat:appcompat:1.0.0`

### Implementation
- `com.alibaba:fastjson:1.2.83` - JSON 处理

---

## WebViewComponent 类（组件 API）

**路径**: `src/main/java/uni/dcloud/io/uniplugin_webview/WebViewComponent.java`

**继承**: `io.dcloud.feature.uniapp.ui.component.UniComponent<FrameLayout>`

> **重要**: 该组件只能在 **`.nvue` 页面** 中使用，普通 `.vue` 页面无法渲染。

### 组件属性 (Props)

| 属性 | 类型 | 说明 |
|------|------|------|
| `src` | String | 加载的 URL，支持 `http/https` 及本地路径（见下方说明） |
| `html` | String | 直接加载 HTML 字符串内容（与 `src` 互斥） |

### 组件事件 (Events)

> ⚠️ **关键说明**：nvue 模板编译器会将事件名转为**全小写**，`@eventName` 必须全部使用小写。

| 前端绑定方式 | 触发时机 | 回调数据 (`e.detail`) |
|---|---|---|
| `@onpagestart="handler"` | WebView 开始加载页面 | `{ url }` |
| `@onpagefinish="handler"` | WebView 页面加载完成 | `{ url, title }` |
| `@onpageerror="handler"` | WebView 页面加载失败 | `{ errorCode, description, failingUrl, url }` |
| `@onjsmessage="handler"` | H5 通过 JS Bridge 发送消息 | `{ type, data }` 或 `{ handler, data }` |
| `@onprogress="handler"` | 页面加载进度实时回调 | `{ progress }` （0 ~ 100 整数） |

```vue
<!-- ✅ 正确写法：事件名全小写 -->
<UniWebView
  :src="url"
  @onpagestart="onPageStart"
  @onpagefinish="onPageFinish"
  @onpageerror="onPageError"
  @onjsmessage="onJsMessage"
  @onprogress="onProgress"
/>
```

```javascript
methods: {
  onPageStart(e) {
    console.log('开始加载：', e.detail.url)
  },
  onPageFinish(e) {
    console.log('加载完成：', e.detail.url, e.detail.title)
  },
  onPageError(e) {
    console.log('加载失败：', e.detail.errorCode, e.detail.description)
  },
  onJsMessage(e) {
    console.log('H5 消息：', e.detail)
  },
  onProgress(e) {
    // 进度会高频触发，注意避免频繁更新 UI
    this.progress = e.detail.progress  // 0 ~ 100
  }
}
```

### 组件方法 (Methods)

通过 `this.$refs.webview.方法名()` 调用：

| 方法 | 参数 | 说明 |
|------|------|------|
| `reload()` | - | 刷新页面 |
| `goBack()` | - | 执行后退（WebView 历史后退） |
| `canGoBack(callback)` | `Function` | 查询是否可后退，回调返回 `{ canGoBack: Boolean }` |
| `goForward()` | - | 执行前进 |
| `getCurrentUrl()` | - | 同步返回当前页面 URL（`String`） |
| `evaluateJavascript(js, callback)` | `String, Function` | 执行 JS 代码，回调返回 `{ result }` |
| `clearCache(flag)` | `Boolean` | 清除缓存（见下方说明） |

#### `clearCache(flag)` 参数说明

| `flag` 值 | 效果 |
|---|---|
| `false` | 仅清除内存缓存（速度快，不影响磁盘资源） |
| `true` | 同时清除内存缓存 + 磁盘缓存 + 历史记录（彻底清理） |

```javascript
// 仅清内存缓存
this.$refs.webview.clearCache(false)

// 完整清理
this.$refs.webview.clearCache(true)

// 查询是否可后退
this.$refs.webview.canGoBack(result => {
  console.log(result.canGoBack) // true / false
  if (result.canGoBack) {
    this.$refs.webview.goBack()
  }
})

// 刷新页面
this.$refs.webview.reload()

// 执行 JS
this.$refs.webview.evaluateJavascript('document.title', res => {
  console.log('页面标题：', res.result)
})
```

---

## URL 支持类型与环境切换

`src` 属性支持两种格式：

| 类型 | 示例 | 说明 |
|------|------|------|
| 网络地址 | `https://www.example.com` | `http://` 或 `https://` 开头，直接使用 |
| 本地文件路径 | `/static/demo.html` | 原生根据 `IS_ANDROID_STUDIO` 开关自动拼接完整路径 |

### 调试环境开关（`IS_ANDROID_STUDIO`）

代码顶部有一个环境开关，**切换环境只改这一行**，前端代码不需要改：

```java
// ===== 调试环境开关 =====
// true  = Android Studio 真机调试：本地路径拼接为 assets 完整路径
// false = uni-app 自定义基座：动态扫描外部存储路径自动拼接
// → 前端 2 种环境都传 /static/xxx，切换开关即可，不需改前端代码
private static final boolean IS_ANDROID_STUDIO = true;

// Android Studio 环境下 www 目录的 assets 路径（IS_ANDROID_STUDIO=true 时生效）
private static final String ASSETS_WWW_PATH = "file:///android_asset/apps/__UNI__BE1144F/www/";
```

### 两种环境的路径转换

| 开关值 | 环境 | 前端传入 | WebView 实际加载 |
|---|---|---|---|
| `true` | Android Studio 真机 | `/static/demo.html` | `file:///android_asset/apps/__UNI__BE1144F/www/static/demo.html` |
| `false` | uni-app 自定义基座 | `/static/demo.html` | `file:///storage/emulated/0/Android/data/{pkg}/apps/__UNI__xxx/www/static/demo.html` |

> **uni-app 路径原理**：`IS_ANDROID_STUDIO = false` 时，原生通过 `Context.getExternalFilesDir()` 动态获取外部存储根目录，再扫描 `apps/__UNI__xxx/www/` 拼接出完整路径，无需硬编码。

### 实际路径对照

| 环境 | www 实际路径 |
|---|---|
| Android Studio 真机调试 | `file:///android_asset/apps/__UNI__BE1144F/www/` |
| uni-app 自定义基座 | `file:///storage/emulated/0/Android/data/uni.app.UNIBE1144F/apps/__UNI__BE1144F/www/` |

---

## JS Bridge 通信

### 方向一：H5 → uni-app

WebView 加载的 H5 页面可通过 `window.UniWebView` 调用原生：

```javascript
// 发送通用消息
window.UniWebView.postMessage(JSON.stringify({
  type: 'nfc',
  data: 'abc123'
}))

// 调用指定 handler
window.UniWebView.callHandler('doLogin', JSON.stringify({
  userId: '001'
}))
```

uni-app 端接收（`@onjsmessage`）：

```javascript
onJsMessage(e) {
  const msg = e.detail
  console.log(msg.type)    // postMessage: msg.type = 'nfc'
  console.log(msg.handler) // callHandler: msg.handler = 'doLogin'
  console.log(msg.data)    // callHandler: msg.data = { userId: '001' }
}
```

### 方向二：uni-app → H5

```javascript
// 向 H5 注入并执行 JS
this.$refs.webview.evaluateJavascript(
  `window.receiveFromApp(${JSON.stringify({ cmd: 'refresh' })})`,
  res => console.log('执行结果：', res.result)
)
```

H5 页面对应：

```javascript
window.receiveFromApp = function(data) {
  console.log('收到 App 数据：', data)
  return 'ok' // 此返回值会传给 uni-app 回调
}
```

### 通信注意事项

| 问题 | 原因 | 解决方案 |
|---|---|---|
| H5 调用 `UniWebView` 报 `undefined` | WebView 还未完成 JS 接口注入 | 在 `DOMContentLoaded` 后调用 |
| `evaluateJavascript` 无返回值 | Android 4.4 以下不支持 | 代码已做 API 版本分支处理 |
| `fireEvent` 在非 UI 线程崩溃 | 必须在主线程调用 | JsBridge 已用 `mWebView.post()` 包裹 |
| 回调只触发一次 | `invoke()` 销毁 callbackId | 生命周期事件已改用 `invokeAndKeepAlive()` |

---

## Deep Link 处理

`shouldOverrideUrlLoading` 按 URL scheme 分类处理，防止 `baiduboxapp://` 等自定义协议导致加载失败：

| URL 类型 | 处理方式 |
|---|---|
| `http://` / `https://` | `return false`，由 WebView 自行处理（支持 302 重定向，不会死循环） |
| 其他 scheme（`baiduboxapp://`, `tel:`, `mailto:`, `intent://` 等） | 用 `Intent.parseUri()` 尝试跳转对应 App；未安装则静默忽略 |

---

## UniJSCallback 使用规范

| 方法 | 适用场景 | 说明 |
|---|---|---|
| `callback.invoke(data)` | **一次性回调**（如 `evaluateJavascript`、`canGoBack`） | JS 端收到后 callbackId 立即销毁 |
| `callback.invokeAndKeepAlive(data)` | **多次回调**（生命周期事件、进度、JsBridge 消息） | callbackId 保留，可重复触发 |

> 当前所有生命周期事件（`onpagestart/finish/error`）、进度事件（`onprogress`）和 JsBridge 消息（`onjsmessage`）均已改用 `invokeAndKeepAlive`。

---

## WebViewModule 类（模块 API）

**路径**: `src/main/java/uni/dcloud/io/uniplugin_webview/WebViewModule.java`

**继承**: `io.dcloud.feature.uniapp.common.UniModule`

**前端使用**：`const webView = uni.requireNativePlugin('UniWebViewModule')`

| 方法 | 参数 | 回调返回 | 说明 |
|------|------|----------|---|
| `clearAllCache(callback)` | - | `{success, message}` | 清除所有 WebView 缓存 |
| `setCookie(options, callback)` | `{url, name, value}` | `{success, message}` | 设置 Cookie |
| `getCookie(options, callback)` | `{url}` | `{success, cookies}` | 获取 Cookie |
| `removeCookie(options, callback)` | `{url, name}` | `{success, message}` | 删除指定 Cookie |
| `clearAllCookies(callback)` | - | `{success, message}` | 清除所有 Cookie |
| `openInBrowser(options, callback)` | `{url}` | `{success, message}` | 在外部浏览器打开 URL |
| `getWebViewVersion(callback)` | - | `{success, androidVersion, sdkVersion}` | 获取 WebView 版本信息 |
| `preloadUrl(options, callback)` | `{url}` | `{success, message}` | 预加载 URL 页面 |

---

## 注意事项

1. **组件只能用于 nvue 页面**，普通 `.vue` 页面无法使用
2. **事件名必须全小写**：`@onpagestart` / `@onpagefinish` / `@onpageerror` / `@onjsmessage` / `@onprogress`
3. **事件回调参数在 `e.detail` 里**，不是直接在 `e` 上
4. **生命周期回调用 `invokeAndKeepAlive`**，否则 reload 后第二次不会触发
5. **`onProgress` 高频触发**，建议只更新进度条 UI，避免在回调里做重操作
6. **切换调试环境**只需修改 `IS_ANDROID_STUDIO` 常量，前端代码不变
7. **地理位置权限会自动授权**，如需拒绝请在 `WebChromeClient` 中修改
8. **Cookie 操作** URL 必须带协议（`http://` 或 `https://`）
9. **JS 执行时机** 建议在 `@onpagefinish` 回调后调用 `evaluateJavascript`

## 入口文件

| 文件 | 说明 |
|------|------|
| `build.gradle` | 模块构建配置 |
| `plugin.xml` | UniPlugin 插件配置 |
| `_config.xml` | 插件描述文件 |
| `AndroidManifest.xml` | 模块清单 |
| `WebViewModule.java` | 全局模块 API 入口 |
| `WebViewComponent.java` | 组件 API 入口（核心文件） |
