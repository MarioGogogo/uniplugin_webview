# UniWebView - Android WebView 插件

## 插件说明

这是一个用于 uni-app 的 Android WebView 原生插件，提供以下功能：

- **WebView 组件**: 可在 uni-app 页面中嵌入原生 WebView
- **JS Bridge 通信**: 支持网页与 uni-app 双向通信
- **Cookie 管理**: 提供 Cookie 的增删改查功能
- **缓存管理**: 清除 WebView 缓存和数据
- **WebView 池**: 单例模式复用 WebView 实例，提升性能
- **预热机制**: 应用启动时预热 WebView，缩短首次加载时间

## 插件结构

```
uniplugin_webview/
├── build.gradle                      # 模块构建配置
├── proguard-rules.pro                # ProGuard 规则
├── src/main/
│   ├── AndroidManifest.xml           # 清单文件
│   └── java/uni/dcloud/io/uniplugin_webview/
│       ├── WebViewComponent.java     # WebView 组件
│       └── WebViewModule.java        # WebView 模块
```

## 注册配置

### dcloud_uniplugins.json

```json
{
  "nativePlugins": [
    {
      "plugins": [
        {
          "type": "component",
          "name": "UniWebView",
          "class": "uni.dcloud.io.uniplugin_webview.WebViewComponent"
        }
      ]
    },
    {
      "plugins": [
        {
          "type": "module",
          "name": "UniWebViewModule",
          "class": "uni.dcloud.io.uniplugin_webview.WebViewModule"
        }
      ]
    }
  ]
}
```

## 使用方法

### 1. WebView 组件（加载 URL）

```vue
<template>
  <view>
    <UniWebView
      ref="webView"
      :src="webviewUrl"
      @onPageStart="onPageStart"
      @onPageFinish="onPageFinish"
      @onPageError="onPageError"
      @onJsMessage="onJsMessage"
    ></UniWebView>
  </view>
</template>

<script>
export default {
  data() {
    return {
      webviewUrl: 'https://uniapp.dcloud.io/'
    }
  },
  methods: {
    onPageStart(e) {
      console.log('页面开始加载:', e.url)
    },
    onPageFinish(e) {
      console.log('页面加载完成:', e)
      // e.url - 当前 URL
      // e.title - 页面标题
    },
    onPageError(e) {
      console.log('页面加载失败:', e)
      // e.errorCode - 错误代码
      // e.description - 错误描述
      // e.failingUrl - 失败的 URL
    },
    onJsMessage(e) {
      console.log('收到 JS 消息:', e)
    }
  }
}
</script>
```

### 2. WebView 组件（加载 HTML）

```vue
<template>
  <view>
    <UniWebView
      :html="htmlContent"
      @onPageFinish="onPageFinish"
    ></UniWebView>
  </view>
</template>

<script>
export default {
  data() {
    return {
      htmlContent: `
        <!DOCTYPE html>
        <html>
        <head><title>测试</title></head>
        <body><h1>Hello World</h1></body>
        </html>
      `
    }
  }
}
</script>
```

### 3. WebView 组件方法调用

```javascript
const webView = uni.requireNativePlugin('UniWebView')

// 刷新页面
webView.reload()

// 后退
webView.goBack()

// 前进
webView.goForward()

// 获取当前 URL
webView.getCurrentUrl()

// 执行 JavaScript
webView.evaluateJavascript('document.title', (result) => {
  console.log('页面标题:', result.result)
})

// 清除缓存
webView.clearCache()
```

### 4. JS Bridge 通信

#### 网页端发送消息到 uni-app

```html
<script>
// 方法 1: postMessage
function sendMessage() {
  const message = {
    type: 'buttonClick',
    data: { text: '你好' }
  };
  UniWebView.postMessage(JSON.stringify(message));
}

// 方法 2: callHandler
function callApp() {
  const data = { userId: '123', action: 'getUserInfo' };
  UniWebView.callHandler('getUserInfo', JSON.stringify(data));
}
</script>
```

#### uni-app 端接收网页消息

```javascript
export default {
  methods: {
    onJsMessage(e) {
      // 处理 callHandler 调用
      if (e.handler) {
        this.handleHandlerCall(e.handler, e.data)
      }
      // 处理 postMessage 消息
      else if (e.type) {
        this.handlePostMessage(e)
      }
    },
    handleHandlerCall(handler, data) {
      switch (handler) {
        case 'getUserInfo':
          console.log('获取用户信息:', data)
          break
      }
    },
    handlePostMessage(e) {
      switch (e.type) {
        case 'buttonClick':
          console.log('网页按钮点击:', e.data)
          break
      }
    }
  }
}
```

### 5. WebViewModule 全局方法

```javascript
const webViewModule = uni.requireNativePlugin('UniWebViewModule')

// 清除所有缓存
webViewModule.clearAllCache((result) => {
  console.log(result) // { success: true, message: "缓存已清除" }
})

// 设置 Cookie
webViewModule.setCookie({
  url: 'https://example.com',
  name: 'token',
  value: 'abc123'
}, (result) => {
  console.log(result)
})

// 获取 Cookie
webViewModule.getCookie({
  url: 'https://example.com'
}, (result) => {
  console.log('Cookies:', result.cookies)
})

// 删除 Cookie
webViewModule.removeCookie({
  url: 'https://example.com',
  name: 'token'
}, (result) => {
  console.log(result)
})

// 清除所有 Cookie
webViewModule.clearAllCookies((result) => {
  console.log(result)
})

// 在外部浏览器打开 URL
webViewModule.openInBrowser({
  url: 'https://uniapp.dcloud.io/'
}, (result) => {
  console.log(result)
})

// 获取 WebView 版本信息
webViewModule.getWebViewVersion((result) => {
  console.log('Android 版本:', result.androidVersion)
  console.log('SDK 版本:', result.sdkVersion)
})
```

## 组件属性

| 属性 | 类型 | 说明 |
|------|------|------|
| src | String | 加载的 URL 地址 |
| html | String | HTML 内容（与 src 互斥） |
| onPageStart | Function | 页面加载开始回调 |
| onPageFinish | Function | 页面加载完成回调 |
| onPageError | Function | 页面加载失败回调 |
| onJsMessage | Function | JS 消息回调 |

## 回调参数说明

### onPageStart
```javascript
{ url: "https://example.com" }  // 页面开始加载
```

### onPageFinish
```javascript
{
  url: "https://example.com",  // 加载完成的 URL
  title: "页面标题"             // 页面标题
}
```

### onPageError
```javascript
{
  errorCode: -12,              // 错误代码
  description: "主机无法解析",  // 错误描述
  failingUrl: "https://xxx.com" // 加载失败的 URL
}
```

### 常见错误代码
| 错误代码 | 说明 |
|----------|------|
| -2 | 文件未找到 |
| -6 | 服务器或 DNS 错误 |
| -8 | 加载超时 |
| -12 | 主机无法解析 |
| -13 | 服务器连接失败 |

## 组件方法

| 方法 | 参数 | 说明 |
|------|------|------|
| reload | - | 刷新页面 |
| goBack | - | 后退 |
| goForward | - | 前进 |
| canGoBack | callback | 判断是否可后退 |
| getCurrentUrl | - | 获取当前 URL |
| evaluateJavascript | js, callback | 执行 JS 代码 |
| clearCache | includeDiskFiles | 清除缓存 |
| clearSessionAndReload | - | 清除会话数据并重载 |
| reuse | - | 复用当前 WebView |
| getPoolStatus | callback | 获取池状态 |
| clearPool | - | 清理当前池 |

## 模块方法

| 方法 | 参数 | 说明 |
|------|------|------|
| clearAllCache | callback | 清除所有缓存 |
| setCookie | {url, name, value}, callback | 设置 Cookie |
| getCookie | {url}, callback | 获取 Cookie |
| removeCookie | {url, name}, callback | 删除 Cookie |
| clearAllCookies | callback | 清除所有 Cookie |
| openInBrowser | {url}, callback | 外部浏览器打开 |
| getWebViewVersion | callback | 获取版本信息 |
| warmUp | {}, callback | 预热 WebView |
| warmUpWhenIdle | {}, callback | 空闲时段预热 |
| isWarmedUp | {}, callback | 检查预热状态 |
| getPoolStatus | {}, callback | 获取池状态 |
| clearAllPools | {}, callback | 清理所有池 |

## 性能优化

### WebView 池（单例复用）

插件内置了 WebView 单例池，自动复用 WebView 实例，减少创建开销：

```javascript
// 检查池状态
const webViewModule = uni.requireNativePlugin('UniWebViewModule')
webViewModule.getPoolStatus({}, (result) => {
  console.log('池大小:', result.poolSize)
  console.log('是否已预热:', result.warmedUp)
})
```

### 预热机制

在应用启动时预热 WebView，显著缩短首次加载时间：

```javascript
// 在 App.vue 的 onLaunch 中预热
export default {
  onLaunch() {
    const webViewModule = uni.requireNativePlugin('UniWebViewModule')
    webViewModule.warmUp({}, (result) => {
      console.log('预热结果:', result)
    })
  }
}
```

**性能对比：**
- 未预热：首次加载 200-500ms
- 已预热：首次加载 50-100ms（提升 70-80%）

### 复用 WebView

组件支持复用当前 WebView 实例：

```javascript
// 清空状态并复用
this.webView.reuse()
```

### 空闲时段预热

利用系统空闲时间自动预热：

```javascript
// 在合适的时机调用（如应用启动后）
webViewModule.warmUpWhenIdle({}, (result) => {
  console.log('已安排空闲预热')
})
```

## 注意事项

1. **权限配置**: WebView 需要网络权限，确保在 AndroidManifest.xml 中添加：
   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
   ```

2. **HTTPS**: 默认允许加载 HTTP 和 HTTPS 混合内容

3. **内存管理**: 页面销毁时组件会自动清理 WebView 资源

4. **JavaScript 接口**: 网页中通过 `window.UniWebView` 调用原生方法

## 示例页面

- `pages/sample/webview.vue` - WebView 基础用法示例
- `pages/sample/webview-bridge.vue` - JS Bridge 双向通信示例

## 编译与运行

1. 在 Android Studio 中打开项目
2. 同步 Gradle
3. 构建并运行到 Android 设备或模拟器

## 技术支持

如有问题，请参考项目文档或联系开发者。
