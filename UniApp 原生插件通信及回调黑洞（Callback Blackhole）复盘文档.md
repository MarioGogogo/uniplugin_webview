# UniApp 原生插件通信及回调黑洞（Callback Blackhole）复盘文档

在 UniApp 原生插件（特别是 WebView 增强插件）开发中，跨端通信的链路非常脆弱。本次技术攻坚解决了一个极具代表性的难题：**H5 调用原生相机后，无法收到任何回调结果**。

本文档将对这其中的两大“深坑”进行深度技术复盘，并提供最终的“无敌”解决方案。

---

## 坑位一：WebView Host Object 属性注入失败（静默死机）

### 1. 现象描述
H5 端使用闭包回调模式 `window.UniWebView.openCamera(function(res) {...})` 调用原生插件时，原生日志没有任何响应。

### 2. 根本原因
通过 `addJavascriptInterface` 注入的 Java 对象（在 JS 层被映射为 `window.UniWebView`），在 WebKit / TBS 内核底层是一个被强保护的 **Host Object（宿主对象）**。
前端试图直接向这个对象上挂载新属性或方法（如临时附加回调函数）时，浏览器内核会直接阻断并静默失败，导致原生的 `openCamera` 根本没被触达。

### 3. 破局方案：退回全局监听模式（Observer Pattern）
抛弃复杂且容易出错的动态闭包注入，将通信解耦为最原始、最稳健的全局事件机制：

**原生层极简注入**：
```java
// 仅保留最纯净的方法暴露，不接受任何 JS Function 回调
webView.addJavascriptInterface(new JsBridge(), "UniWebView");

public class JsBridge {
    @JavascriptInterface
    public void openCamera() {
        // 直接触发相机逻辑
    }
}
```

**原生回调方式**：通过硬编码的字符串，直接拉起 H5 全局挂载好的函数。
```java
String js = "javascript:if(window.onCameraResult) { window.onCameraResult(" + resultJSON + "); }";
mWebView.evaluateJavascript(js, null);
```

**H5 端配合（全局挂载）**：
```javascript
window.onCameraResult = function(result) {
    console.log('全局收到照片数据', result);
};
// 呼叫原生
window.UniWebView.openCamera();
```

---

## 坑位二：UniApp 容器 `onActivityResult` 回调黑洞（史诗级巨坑）

### 1. 现象描述
相机成功拉起、拍照也成功完成，且 App 顺利恢复到了前台。但在代理层（`CameraProxyFragment`）中，死活收不到相机的图片返回。

### 2. 根本原因（Callback Blackhole）
在使用 `startActivityForResult` 甚至 AndroidX 最新的 `ActivityResultLauncher` 机制时，底层的事件分发路径为：
`系统相机` 👉 `宿主 Activity (PandoraEntryActivity)` 👉 `下发给 Fragment (或 Registry)`。

**致命缺陷**：UniApp 的原生宿主 `PandoraEntryActivity` 在重写 `onActivityResult` 时，疑似**直接吞噬了该事件**（未调用 `super.onActivityResult(...)`），彻底阻断了原生 Fragment 接收结果的通道！

### 3. 破局方案：基于生命周期侧信道的“异步轮询大法”
既然原生系统回调这条路被彻底堵死，我们采用绕过拦截的**生命周期侧信道（Side-Channel）**技术：

虽然宿主 Activity 敢拦截数据，但它绝不敢拦截 `onResume`（Activity 恢复到前台的生命周期）。我们提前设定好图片的保存路径（URI），然后在 `onResume` 时主动出击，读取磁盘文件状态！

**核心实现代码 (`CameraProxyFragment.java`)**：
```java
// 1. 启动普通 Intent，不依赖 forResult，只依赖传入固定的 URI 路径
mPendingLaunch = true; // 标记正在拍照
intent.putExtra(MediaStore.EXTRA_OUTPUT, mCameraImageUri);
startActivity(intent);

// 2. 利用不可被拦截的 onResume 主动检查
@Override
public void onResume() {
    super.onResume();
    if (mPendingLaunch) {
        mPendingLaunch = false;
        
        // 延迟 500ms 检查，防止相机退回时系统 IO 还没彻底 flush 到磁盘
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                InputStream is = requireActivity().getContentResolver().openInputStream(mCameraImageUri);
                if (is != null && is.available() > 0) {
                    // 🎉 检查成功：照片存在且有体积！
                    mCallback.onResult(mCameraImageUri);
                } else {
                    // 取消拍照或异常
                    mCallback.onResult(null);
                }
            } catch (Exception e) {
                // 处理异常
            }
        }, 500);
    }
}
```

## 总结
在混合开发框架（如 UniApp、Flutter 等）中编写原生插件时，永远不要过于相信系统回调链（如 `startActivityForResult`）。
当遇到事件断层时：
1. **对于 JS <-> 原生**：退守 `window` 全局事件。
2. **对于 Android IPC (进程间通信)**：退守 `onResume` + 强 I/O 文件检测。

大道至简，这两招在任何复杂的内核与容器中都能所向披靡。
