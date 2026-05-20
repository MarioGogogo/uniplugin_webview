package uni.dcloud.io.uniplugin_webview;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.alibaba.fastjson.JSONObject;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.ui.action.AbsComponentData;
import io.dcloud.feature.uniapp.ui.component.AbsVContainer;
import io.dcloud.feature.uniapp.ui.component.UniComponent;
import io.dcloud.feature.uniapp.ui.component.UniComponentProp;
import io.dcloud.feature.uniapp.UniSDKInstance;

/**
 * WebView 组件
 * 可在 uni-app 中嵌入原生 WebView，支持加载 URL 或 HTML 内容
 *
 * 路径说明：
 * - 标准基座：file:///android_asset/apps/__UNI__{APPID}/www/static/
 * - 自定义基座：file:///android_asset/www/static/ 或 file:///android_asset/apps/__UNI__{APPID}/www/static/
 * - 代码会自动检测正确的路径
 * /storage/emulated/0/Android/data/uni.app.UNIBE1144F/apps/__UNI__BE1144F/www/static/plus-bridge-test.html
 */
public class WebViewComponent extends UniComponent<FrameLayout> {

    // ===== 调试环境开关 =====
    // true  = Android Studio 真机调试：本地路径拼接为 assets 完整路径
    // false = uni-app 自定义基座：动态扫描外部存储路径自动拼接
    // → 前端 2 种环境都传 /static/xxx，切换开关即可，不需改前端代码
    private static final boolean IS_ANDROID_STUDIO = false;
    // Android Studio 环境下 www 目录的 assets 路径（IS_ANDROID_STUDIO=true 时生效）
    private static final String ASSETS_WWW_PATH = "file:///android_asset/apps/__UNI__BE1144F/www/";
    // ====================

    private WebView mWebView;
    private UniJSCallback onPageStartCallback;
    private UniJSCallback onPageFinishCallback;
    private UniJSCallback onPageErrorCallback;
    private UniJSCallback onJsMessageCallback;
    private UniJSCallback onProgressCallback;
    private String mUrl = "";
    private String mHtmlContent = "";

    private static final String TAG = "WebViewComponent";

    public WebViewComponent(UniSDKInstance instance, AbsVContainer parent, AbsComponentData basicComponentData) {
        super(instance, parent, basicComponentData);
        Log.d(TAG, "WebViewComponent 初始化");
    }


    @Override
    protected FrameLayout initComponentHostView(Context context) {
        Log.d(TAG, "===== initComponentHostView 被调用 =====");
        FrameLayout container = new FrameLayout(context);
        container.setBackgroundColor(Color.WHITE);

        mWebView = new WebView(context);
        Log.d(TAG, "WebView 创建完成");
        setupWebView(mWebView);

        container.addView(mWebView,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT);

        Log.d(TAG, "WebView 添加到容器");
        Log.d(TAG, "暂存的 mUrl=" + mUrl);
        Log.d(TAG, "暂存的 mHtmlContent=" + mHtmlContent);

        // 如果有暂存的 URL 或 HTML，在下一帧加载
        // 确保所有 prop（包括 onPageStart 等回调）都已设置完毕
        if (!TextUtils.isEmpty(mHtmlContent)) {
            Log.d(TAG, "延迟加载暂存的 HTML 内容");
            mWebView.post(() -> {
                Log.d(TAG, "开始加载暂存的 HTML 内容");
                mWebView.loadDataWithBaseURL(null, mHtmlContent, "text/html", "UTF-8", null);
            });
        } else if (!TextUtils.isEmpty(mUrl)) {
            String loadUrl = convertToWebViewUrl(mUrl);
            Log.d(TAG, "延迟加载 URL: " + loadUrl);
            mWebView.post(() -> {
                Log.d(TAG, "开始加载 URL: " + loadUrl);
                mWebView.loadUrl(loadUrl);
            });
        } else {
            Log.w(TAG, "警告：没有 URL 或 HTML 内容可加载");
        }

        return container;
    }

    /**
     * 配置 WebView 设置
     */
    private void setupWebView(WebView webView) {
        WebSettings settings = webView.getSettings();

        // 启用 JavaScript
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        // 支持缩放
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        // 自适应屏幕
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // 缓存设置
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 允许 WebView 通过 file:// 协议加载本地文件
        // Android 11+ 默认值改为 false，必须显式开启，否则外部存储路径无法加载
        settings.setAllowFileAccess(true);
        // 允许 file:// 页面中的 JS 访问其他 file:// 资源（如相对路径的 css/js）
        settings.setAllowFileAccessFromFileURLs(true);
        // 允许 file:// 页面跨域访问任意 file:// 资源（兼容 UniApp 沙盒路径下的跨目录引用）
        // 注意：此 API 在新版 SDK 中标记为 Deprecated，但对 UniApp 本地资源加载仍是必要的
        settings.setAllowUniversalAccessFromFileURLs(true);

        // 编码设置
        settings.setDefaultTextEncodingName("UTF-8");

        // Android 5.0+ 允许混合内容
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // 设置 WebViewClient
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Log.d(TAG, "shouldOverrideUrlLoading: " + url);

                // http / https：让 WebView 正常加载，支持 302 重定向
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false; // 返回 false = 由 WebView 自己处理，不会死循环
                }

                // 其他 scheme（baiduboxapp://, intent://, tel:, mailto: 等 deep link）
                // 用系统 Intent 处理，跳转到对应 App；未安装则静默忽略
                try {
                    android.content.Intent intent = android.content.Intent.parseUri(
                            url, android.content.Intent.URI_INTENT_SCHEME);
                    android.content.pm.PackageManager pm = view.getContext().getPackageManager();
                    if (pm.resolveActivity(intent, 0) != null) {
                        // 有 App 能处理，直接跳转
                        view.getContext().startActivity(intent);
                        Log.d(TAG, "Deep link 跳转成功：" + url);
                    } else {
                        Log.w(TAG, "没有 App 能处理该 scheme，忽略：" + url);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "解析 deep link 失败，忽略：" + url + " | 错误：" + e.getMessage());
                }

                // 阻止 WebView 尝试加载非 http(s) URL
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.d(TAG, "===== onPageStarted 被触发，url=" + url);
                Log.d(TAG, "===== 当前 onPageStartCallback=" + (onPageStartCallback != null ? "已绑定 ✓" : "未绑定 ✗"));
                JSONObject data = new JSONObject();
                data.put("url", url);
                if (onPageStartCallback != null) {
                    // 生命周期事件可能多次触发，必须用 invokeAndKeepAlive
                    onPageStartCallback.invokeAndKeepAlive(data);
                }
                // 注意：nvue 模板编译器会把事件名转为全小写
                // @onPageStart 实际监听的是 "onpagestart"，必须保持一致
                fireEvent("onpagestart", createEventParams(data));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "页面加载完成：" + url + ", 标题：" + view.getTitle());
                JSONObject data = new JSONObject();
                data.put("url", url);
                data.put("title", view.getTitle());
                if (onPageFinishCallback != null) {
                    onPageFinishCallback.invokeAndKeepAlive(data);
                }
                // 注意：事件名必须全小写，对应前端 @onpagefinish
                fireEvent("onpagefinish", createEventParams(data));
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                // Android 6.0+ 已由新 API 处理，旧 API 不再重复回调，避免触发两次
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    return;
                }
                Log.e(TAG, "加载失败 (old API): errorCode=" + errorCode + ", description=" + description + ", url=" + failingUrl);
                JSONObject data = new JSONObject();
                data.put("errorCode", errorCode);
                data.put("description", description != null ? description : "");
                data.put("failingUrl", failingUrl);
                data.put("url", failingUrl);
                if (onPageErrorCallback != null) {
                    onPageErrorCallback.invokeAndKeepAlive(data);
                }
                fireEvent("onpageerror", createEventParams(data));
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                Log.e(TAG, "加载失败 (new API): errorCode=" + error.getErrorCode() + ", description=" + error.getDescription() + ", url=" + request.getUrl().toString());
                // 只在主框架加载失败时回调（避免 iframe 等子资源错误干扰）
                if (!request.isForMainFrame()) {
                    Log.d(TAG, "子资源加载失败，忽略：" + request.getUrl().toString());
                    return;
                }
                JSONObject data = new JSONObject();
                data.put("errorCode", error.getErrorCode());
                data.put("description", error.getDescription() != null ? error.getDescription() : "");
                data.put("failingUrl", request.getUrl().toString());
                data.put("url", request.getUrl().toString());
                if (onPageErrorCallback != null) {
                    onPageErrorCallback.invokeAndKeepAlive(data);
                }
                // 注意：事件名必须全小写，对应前端 @onpageerror
                fireEvent("onpageerror", createEventParams(data));
            }
        });

        // 设置 WebChromeClient 处理 JS 对话框和地理位置
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, android.webkit.JsResult result) {
                // 处理 JS alert
                return super.onJsAlert(view, url, message, result);
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, android.webkit.JsResult result) {
                // 处理 JS confirm
                return super.onJsConfirm(view, url, message, result);
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, android.webkit.JsPromptResult result) {
                // 处理 JS prompt
                return super.onJsPrompt(view, url, message, defaultValue, result);
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                // 处理地理位置权限请求
                callback.invoke(origin, true, false);
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                Log.d(TAG, "加载进度：" + newProgress + "%");
                JSONObject data = new JSONObject();
                data.put("progress", newProgress);
                if (onProgressCallback != null) {
                    // 进度会多次触发，必须用 invokeAndKeepAlive
                    onProgressCallback.invokeAndKeepAlive(data);
                }
                // 同时通过 fireEvent 支持 @onprogress 事件更新界面
                fireEvent("onprogress", createEventParams(data));
            }
        });

        // 添加 JavaScript 接口
        webView.addJavascriptInterface(new JsBridge(), "UniWebView");
    }

    /**
     * 设置加载的 URL
     * 支持：http://, https://, file://, assets://, 本地文件路径
     *
     * 注意：只暂存 URL，等待 initComponentHostView 完成后统一加载
     * 这样可以确保所有回调（onPageStart 等）都已设置完毕
     */
    @UniComponentProp(name = "src")
    public void setSrc(String url) {
        Log.d(TAG, "===== setSrc 被调用，url=" + url);
        Log.d(TAG, "===== 当前 mWebView=" + (mWebView != null ? "已初始化" : "未初始化"));
        Log.d(TAG, "===== 当前 onPageStartCallback=" + (onPageStartCallback != null ? "已绑定" : "未绑定"));
        if (!TextUtils.isEmpty(url)) {
            mUrl = url;
            // 如果 WebView 已经初始化完成，延迟加载 URL（确保所有 prop 设置完毕）
            if (mWebView != null) {
                String loadUrl = convertToWebViewUrl(mUrl);
                Log.d(TAG, "WebView 已就绪，延迟加载 URL: " + loadUrl);
                mWebView.post(() -> {
                    Log.d(TAG, "开始加载 URL: " + loadUrl);
                    mWebView.loadUrl(loadUrl);
                });
            }
            // 否则等待 initComponentHostView 完成后加载
        } else {
            Log.e(TAG, "url 为空");
        }
    }

    /**
     * 转换输入路径为 WebView 可识别的 URL
     *
     * 环境切换：修改顶部 IS_ANDROID_STUDIO 开关即可，前端 2 种环境都传 /static/xxx
     *  IS_ANDROID_STUDIO = true（Android Studio 真机调试）：
     *    /static/demo.html  →  file:///android_asset/apps/__UNI__BE1144F/www/static/demo.html
     *  IS_ANDROID_STUDIO = false（uni-app 自定义基座）：
     *    /static/demo.html  →  file:///storage/emulated/0/Android/data/{pkg}/apps/__UNI__xxx/www/static/demo.html
     */
    private String convertToWebViewUrl(String url) {
        Log.d(TAG, "convertToWebViewUrl: " + url + "  IS_ANDROID_STUDIO=" + IS_ANDROID_STUDIO);

        // 网络地址：两种环境均直接使用
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }

        // 提取相对路径（去掉前缀即 static/xxx 部分）
        String relativePath = null;
        if (url.startsWith("file:///static/")) {
            relativePath = url.substring("file:///static/".length());
        } else if (url.startsWith("/static/")) {
            relativePath = url.substring("/static/".length());
        } else if (url.startsWith("static/")) {
            relativePath = url.substring("static/".length());
        }

        if (relativePath == null) {
            // 其他格式（file://、http 等）直接返回
            Log.w(TAG, "未识别的路径格式，直接使用：" + url);
            return url;
        }

        if (IS_ANDROID_STUDIO) {
            // Android Studio 环境：拼接 assets 完整路径
            String fullPath = ASSETS_WWW_PATH + "static/" + relativePath;
            Log.d(TAG, "[AS环境] " + url + " -> " + fullPath);
            return fullPath;
        } else {
            // uni-app 环境：动态扫描外部存储，自动得到真实 www 路径
            Context ctx = getInstance() != null ? getInstance().getContext() : null;
            String wwwPath = ctx != null ? getUniAppExternalWwwPath(ctx) : null;
            if (wwwPath != null) {
                String fullPath = "file://" + wwwPath + "static/" + relativePath;
                Log.d(TAG, "[uni-app环境] " + url + " -> " + fullPath);
                return fullPath;
            }
            Log.w(TAG, "[uni-app环境] 外部存储路径获取失败，直接使用原始路径：" + url);
            return url;
        }
    }

    /**
     * 动态获取 uni-app 在设备上的 www 根路径
     * 同时扫描内部存储和外部存储，优先返回内部存储路径
     *
     * 内部存储（自定义基座 wgt 解压路径）:
     *   /data/data/{pkg}/files/apps/__UNI__xxx/www/
     * 外部存储（部分机型/版本）:
     *   /storage/emulated/0/Android/data/{pkg}/apps/__UNI__xxx/www/
     */
    private String getUniAppExternalWwwPath(Context context) {
        // ── 优先扫描内部存储 ─────────────────────────────────────
        try {
            java.io.File filesDir = context.getFilesDir();
            if (filesDir != null) {
                java.io.File appsDir = new java.io.File(filesDir, "apps");
                if (appsDir.exists() && appsDir.isDirectory()) {
                    java.io.File[] appSubDirs = appsDir.listFiles();
                    if (appSubDirs != null) {
                        for (java.io.File appDir : appSubDirs) {
                            if (appDir.isDirectory() && appDir.getName().startsWith("__UNI__")) {
                                java.io.File wwwDir = new java.io.File(appDir, "www");
                                java.io.File staticDir = new java.io.File(wwwDir, "static");
                                if (staticDir.exists()) {
                                    String path = wwwDir.getAbsolutePath();
                                    if (!path.endsWith("/")) path += "/";
                                    Log.i(TAG, "[uni-app] 内部存储 www 路径：" + path
                                            + "（APPID: " + appDir.getName() + "）");
                                    return path;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "内部存储扫描失败：" + e.getMessage());
        }

        // ── 回退扫描外部存储 ─────────────────────────────────────
        try {
            // getExternalFilesDir() 返回：/storage/emulated/0/Android/data/{pkg}/files/
            // 父目录就是应用的外部存储根目录
            java.io.File externalFilesDir = context.getExternalFilesDir(null);
            if (externalFilesDir == null) {
                Log.w(TAG, "外部存储不可用");
                return null;
            }
            java.io.File pkgDir = externalFilesDir.getParentFile();
            if (pkgDir == null) return null;

            java.io.File appsDir = new java.io.File(pkgDir, "apps");
            if (!appsDir.exists() || !appsDir.isDirectory()) {
                Log.w(TAG, "外部存储 apps 目录不存在：" + appsDir.getAbsolutePath());
                return null;
            }

            // 扫描找到 __UNI__ 开头的应用目录
            java.io.File[] appSubDirs = appsDir.listFiles();
            if (appSubDirs != null) {
                for (java.io.File appDir : appSubDirs) {
                    if (appDir.isDirectory() && appDir.getName().startsWith("__UNI__")) {
                        java.io.File wwwDir = new java.io.File(appDir, "www");
                        java.io.File staticDir = new java.io.File(wwwDir, "static");
                        if (staticDir.exists()) {
                            String path = wwwDir.getAbsolutePath();
                            if (!path.endsWith("/")) path += "/";
                            Log.i(TAG, "[uni-app] 外部存储 www 路径：" + path
                                    + "（APPID: " + appDir.getName() + "）");
                            return path;
                        }
                    }
                }
            }
            Log.w(TAG, "外部存储 apps 目录下未找到 __UNI__ 目录：" + appsDir.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "外部存储扫描异常：" + e.getMessage());
        }
        return null;
    }

    /**
     * 设置 HTML 内容
     */
    @UniComponentProp(name = "html")
    public void setHtml(String html) {
        if (!TextUtils.isEmpty(html)) {
            mHtmlContent = html;
            if (mWebView != null) {
                mWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
            }
            // 如果 WebView 尚未初始化，暂存 html，等待初始化后加载
        }
    }

    /**
     * 页面加载开始回调
     */
    @UniComponentProp(name = "onPageStart")
    public void setOnPageStart(UniJSCallback callback) {
        Log.d(TAG, "===== setOnPageStart 被调用，callback=" + (callback != null ? "已绑定" : "null"));
        Log.d(TAG, "===== 当前 mWebView=" + (mWebView != null ? "已初始化" : "未初始化"));
        Log.d(TAG, "===== 当前 mUrl=" + mUrl);
        this.onPageStartCallback = callback;
    }

    /**
     * 页面加载完成回调
     */
    @UniComponentProp(name = "onPageFinish")
    public void setOnPageFinish(UniJSCallback callback) {
        Log.d(TAG, "===== setOnPageFinish 被调用，callback=" + (callback != null ? "已绑定" : "null"));
        this.onPageFinishCallback = callback;
    }

    /**
     * 页面加载失败回调
     */
    @UniComponentProp(name = "onPageError")
    public void setOnPageError(UniJSCallback callback) {
        Log.d(TAG, "===== setOnPageError 被调用，callback=" + (callback != null ? "已绑定" : "null"));
        this.onPageErrorCallback = callback;
    }

    /**
     * JS 消息回调
     */
    @UniComponentProp(name = "onJsMessage")
    public void setOnJsMessage(UniJSCallback callback) {
        Log.d(TAG, "===== setOnJsMessage 被调用，callback=" + (callback != null ? "已绑定" : "null"));
        this.onJsMessageCallback = callback;
    }

    /**
     * 页面加载进度实时回调
     * 前端用法：
     *   :onProgress="onProgress"
     *   onProgress(res) { console.log(res.progress) }  // 0~100
     */
    @UniComponentProp(name = "onProgress")
    public void setOnProgress(UniJSCallback callback) {
        Log.d(TAG, "===== setOnProgress 被调用，callback=" + (callback != null ? "已绑定" : "null"));
        this.onProgressCallback = callback;
    }

    /**
     * 重新加载页面
     * 调用示例：this.$refs.webview.reload()
     */
    @UniJSMethod
    public void reload() {
        if (mWebView != null) {
            mWebView.reload();
        }
    }

    /**
     * 后退（执行后退操作）
     */
    @UniJSMethod
    public void goBack() {
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        }
    }

    /**
     * 判断 WebView 是否可以后退，结果通过回调返回
     * 调用示例：this.$refs.webview.canGoBack(result => { console.log(result.canGoBack) })
     * @param callback 回调函数，返回 { canGoBack: true/false }
     */
    @UniJSMethod
    public void canGoBack(UniJSCallback callback) {
        boolean result = mWebView != null && mWebView.canGoBack();
        Log.d(TAG, "canGoBack: " + result);
        if (callback != null) {
            JSONObject data = new JSONObject();
            data.put("canGoBack", result);
            callback.invoke(data);
        }
    }

    /**
     * 前进
     */
    @UniJSMethod
    public void goForward() {
        if (mWebView != null && mWebView.canGoForward()) {
            mWebView.goForward();
        }
    }

    /**
     * 获取当前 URL
     */
    @UniJSMethod
    public String getCurrentUrl() {
        return mWebView != null ? mWebView.getUrl() : "";
    }

    /**
     * 执行 JavaScript 代码
     */
    @UniJSMethod
    public void evaluateJavascript(String js, UniJSCallback callback) {
        if (mWebView != null && !TextUtils.isEmpty(js)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                mWebView.evaluateJavascript(js, result -> {
                    if (callback != null) {
                        JSONObject data = new JSONObject();
                        data.put("result", result);
                        callback.invoke(data);
                    }
                });
            } else {
                mWebView.loadUrl("javascript:" + js);
                if (callback != null) {
                    callback.invoke(new JSONObject());
                }
            }
        }
    }

    /**
     * 清除缓存
     * 调用示例：this.$refs.webview.clearCache(true)
     * @param includeDiskFiles false=仅清内存缓存；true=同时清内存+磁盘缓存
     */
    @UniJSMethod
    public void clearCache(boolean includeDiskFiles) {
        if (mWebView != null) {
            // includeDiskFiles=true 时同时清除磁盘缓存
            mWebView.clearCache(includeDiskFiles);
            if (includeDiskFiles) {
                // 同时清除历史记录（完整清理）
                mWebView.clearHistory();
                Log.d(TAG, "clearCache: 已清除内存缓存 + 磁盘缓存 + 历史记录");
            } else {
                Log.d(TAG, "clearCache: 已清除内存缓存（磁盘缓存保留）");
            }
        }
    }

    /**
     * 清除会话数据并重新加载页面
     * 清除 Cookie + localStorage + sessionStorage，然后 reload
     * 调用示例：this.$refs.webview.clearSessionAndReload()
     */
    @UniJSMethod
    public void clearSessionAndReload() {
        if (mWebView == null) return;

        // 清除 Cookie
        android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();

        // 通过 JS 清除 localStorage 和 sessionStorage
        String js = "(function(){" +
            "try { localStorage.clear(); } catch(e) {}" +
            "try { sessionStorage.clear(); } catch(e) {}" +
            "return 'ok';" +
            "})()";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            mWebView.evaluateJavascript(js, result -> {
                Log.d(TAG, "clearSessionAndReload: 会话数据已清除，开始 reload");
                mWebView.reload();
            });
        } else {
            mWebView.loadUrl("javascript:" + js);
            mWebView.postDelayed(() -> {
                Log.d(TAG, "clearSessionAndReload: 会话数据已清除，开始 reload");
                mWebView.reload();
            }, 300);
        }
    }

    /**
     * 销毁 WebView
     */
    @Override
    public void destroy() {
        if (mWebView != null) {
            mWebView.removeAllViews();
            mWebView.destroy();
            mWebView = null;
        }
        if (onPageStartCallback != null) {
            onPageStartCallback = null;
        }
        if (onPageFinishCallback != null) {
            onPageFinishCallback = null;
        }
        if (onPageErrorCallback != null) {
            onPageErrorCallback = null;
        }
        if (onJsMessageCallback != null) {
            onJsMessageCallback = null;
        }
        super.destroy();
    }

    /**
     * 创建事件参数（uni-app 要求参数必须放在 detail 键下）
     * @param data 原始数据
     * @return 符合 uni-app 格式的参数 Map
     */
    private java.util.Map<String, Object> createEventParams(JSONObject data) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        java.util.Map<String, Object> detail = new java.util.HashMap<>();

        if (data != null) {
            for (String key : data.keySet()) {
                detail.put(key, data.get(key));
            }
        }

        params.put("detail", detail);
        return params;
    }

    /**
     * JavaScript 桥接类
     * 提供 JS 调用原生代码的能力
     */
    public class JsBridge {

        /**
         * 发送消息到 uni-app
         * @param message JSON 字符串格式的消息
         */
        @JavascriptInterface
        public void postMessage(String message) {
            if (!TextUtils.isEmpty(message)) {
                try {
                    JSONObject data = JSONObject.parseObject(message);
                    if (onJsMessageCallback != null) {
                        // H5 可能多次调用，必须用 invokeAndKeepAlive
                        onJsMessageCallback.invokeAndKeepAlive(data);
                    }
                    // fireEvent 必须在 UI 线程调用，事件名全小写对应前端 @onjsmessage
                    if (mWebView != null) {
                        mWebView.post(() -> fireEvent("onjsmessage", createEventParams(data)));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * 调用 uni-app 方法
         * @param handlerName 方法名
         * @param data JSON 字符串格式的参数
         */
        @JavascriptInterface
        public void callHandler(String handlerName, String data) {
            try {
                JSONObject params = new JSONObject();
                params.put("handler", handlerName);
                if (!TextUtils.isEmpty(data)) {
                    params.put("data", JSONObject.parseObject(data));
                }
                if (onJsMessageCallback != null) {
                    // H5 可能多次调用，必须用 invokeAndKeepAlive
                    onJsMessageCallback.invokeAndKeepAlive(params);
                }
                // fireEvent 必须在 UI 线程调用，事件名全小写对应前端 @onjsmessage
                if (mWebView != null) {
                    mWebView.post(() -> fireEvent("onjsmessage", createEventParams(params)));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
