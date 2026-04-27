package uni.dcloud.io.uniplugin_webview;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

import com.alibaba.fastjson.JSONObject;
import com.tencent.smtt.export.external.interfaces.GeolocationPermissionsCallback;
import com.tencent.smtt.export.external.interfaces.JsPromptResult;
import com.tencent.smtt.export.external.interfaces.JsResult;
import com.tencent.smtt.export.external.interfaces.WebResourceError;
import com.tencent.smtt.export.external.interfaces.WebResourceRequest;
import com.tencent.smtt.sdk.CookieManager;
import com.tencent.smtt.sdk.WebChromeClient;
import com.tencent.smtt.sdk.WebSettings;
import com.tencent.smtt.sdk.WebView;
import com.tencent.smtt.sdk.WebViewClient;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.ui.action.AbsComponentData;
import io.dcloud.feature.uniapp.ui.component.AbsVContainer;
import io.dcloud.feature.uniapp.ui.component.UniComponent;
import io.dcloud.feature.uniapp.ui.component.UniComponentProp;
import io.dcloud.feature.uniapp.UniSDKInstance;

/**
 * X5 内核 WebView 组件
 * 基于腾讯 TBS X5 内核，替代系统 WebView，提供更好的兼容性和性能
 *
 * 与系统 WebView 相比的优势：
 * - 更好的 H5 兼容性（尤其是复杂 CSS3、ES6+）
 * - 视频播放支持更多格式
 * - 文件下载更稳定
 * - 自带夜间模式支持
 *
 * 使用方式：在 nvue 页面中使用 <X5WebView> 标签
 */
public class X5WebViewComponent extends UniComponent<FrameLayout> {

    // ===== 调试环境开关 =====
    private static final boolean IS_ANDROID_STUDIO = false;
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

    private static final String TAG = "X5WebViewComponent";

    public X5WebViewComponent(UniSDKInstance instance, AbsVContainer parent, AbsComponentData basicComponentData) {
        super(instance, parent, basicComponentData);
        Log.d(TAG, "X5WebViewComponent 初始化");
    }

    @Override
    protected FrameLayout initComponentHostView(Context context) {
        Log.d(TAG, "===== initComponentHostView 被调用 =====");
        FrameLayout container = new FrameLayout(context);
        container.setBackgroundColor(Color.WHITE);

        mWebView = new WebView(context);
        Log.d(TAG, "X5 WebView 创建完成");
        setupWebView(mWebView);

        container.addView(mWebView,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT);

        Log.d(TAG, "X5 WebView 添加到容器");
        Log.d(TAG, "暂存的 mUrl=" + mUrl);
        Log.d(TAG, "暂存的 mHtmlContent=" + mHtmlContent);

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
     * 配置 X5 WebView 设置
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
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        // 编码设置
        settings.setDefaultTextEncodingName("UTF-8");

        // Android 5.0+ 允许混合内容
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // X5 特有优化设置
        // 开启硬件加速（提升渲染性能）
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // 设置 WebViewClient
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Log.d(TAG, "shouldOverrideUrlLoading: " + url);

                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false;
                }

                try {
                    android.content.Intent intent = android.content.Intent.parseUri(
                            url, android.content.Intent.URI_INTENT_SCHEME);
                    android.content.pm.PackageManager pm = view.getContext().getPackageManager();
                    if (pm.resolveActivity(intent, 0) != null) {
                        view.getContext().startActivity(intent);
                        Log.d(TAG, "Deep link 跳转成功：" + url);
                    } else {
                        Log.w(TAG, "没有 App 能处理该 scheme，忽略：" + url);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "解析 deep link 失败，忽略：" + url + " | 错误：" + e.getMessage());
                }

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
                    onPageStartCallback.invokeAndKeepAlive(data);
                }
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
                fireEvent("onpagefinish", createEventParams(data));
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
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
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                Log.e(TAG, "加载失败 (new API): errorCode=" + error.getErrorCode() + ", description=" + error.getDescription() + ", url=" + request.getUrl().toString());
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
                fireEvent("onpageerror", createEventParams(data));
            }
        });

        // 设置 WebChromeClient
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                return super.onJsAlert(view, url, message, result);
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                return super.onJsConfirm(view, url, message, result);
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                return super.onJsPrompt(view, url, message, defaultValue, result);
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissionsCallback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                Log.d(TAG, "加载进度：" + newProgress + "%");
                JSONObject data = new JSONObject();
                data.put("progress", newProgress);
                if (onProgressCallback != null) {
                    onProgressCallback.invokeAndKeepAlive(data);
                }
                fireEvent("onprogress", createEventParams(data));
            }
        });

        // 添加 JavaScript 接口
        webView.addJavascriptInterface(new JsBridge(), "UniWebView");
    }

    /**
     * 设置加载的 URL
     */
    @UniComponentProp(name = "src")
    public void setSrc(String url) {
        Log.d(TAG, "===== setSrc 被调用，url=" + url);
        Log.d(TAG, "===== 当前 mWebView=" + (mWebView != null ? "已初始化" : "未初始化"));
        Log.d(TAG, "===== 当前 onPageStartCallback=" + (onPageStartCallback != null ? "已绑定" : "未绑定"));
        if (!TextUtils.isEmpty(url)) {
            mUrl = url;
            if (mWebView != null) {
                String loadUrl = convertToWebViewUrl(mUrl);
                Log.d(TAG, "WebView 已就绪，延迟加载 URL: " + loadUrl);
                mWebView.post(() -> {
                    Log.d(TAG, "开始加载 URL: " + loadUrl);
                    mWebView.loadUrl(loadUrl);
                });
            }
        } else {
            Log.e(TAG, "url 为空");
        }
    }

    /**
     * 转换输入路径为 WebView 可识别的 URL
     */
    private String convertToWebViewUrl(String url) {
        Log.d(TAG, "convertToWebViewUrl: " + url + "  IS_ANDROID_STUDIO=" + IS_ANDROID_STUDIO);

        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }

        String relativePath = null;
        if (url.startsWith("file:///static/")) {
            relativePath = url.substring("file:///static/".length());
        } else if (url.startsWith("/static/")) {
            relativePath = url.substring("/static/".length());
        } else if (url.startsWith("static/")) {
            relativePath = url.substring("static/".length());
        }

        if (relativePath == null) {
            Log.w(TAG, "未识别的路径格式，直接使用：" + url);
            return url;
        }

        if (IS_ANDROID_STUDIO) {
            String fullPath = ASSETS_WWW_PATH + "static/" + relativePath;
            Log.d(TAG, "[AS环境] " + url + " -> " + fullPath);
            return fullPath;
        } else {
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
     */
    private String getUniAppExternalWwwPath(Context context) {
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

        try {
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
        }
    }

    @UniComponentProp(name = "onPageStart")
    public void setOnPageStart(UniJSCallback callback) {
        Log.d(TAG, "===== setOnPageStart 被调用，callback=" + (callback != null ? "已绑定" : "null"));
        Log.d(TAG, "===== 当前 mWebView=" + (mWebView != null ? "已初始化" : "未初始化"));
        Log.d(TAG, "===== 当前 mUrl=" + mUrl);
        this.onPageStartCallback = callback;
    }

    @UniComponentProp(name = "onPageFinish")
    public void setOnPageFinish(UniJSCallback callback) {
        Log.d(TAG, "===== setOnPageFinish 被调用，callback=" + (callback != null ? "已绑定" : "null"));
        this.onPageFinishCallback = callback;
    }

    @UniComponentProp(name = "onPageError")
    public void setOnPageError(UniJSCallback callback) {
        Log.d(TAG, "===== setOnPageError 被调用，callback=" + (callback != null ? "已绑定" : "null"));
        this.onPageErrorCallback = callback;
    }

    @UniComponentProp(name = "onJsMessage")
    public void setOnJsMessage(UniJSCallback callback) {
        Log.d(TAG, "===== setOnJsMessage 被调用，callback=" + (callback != null ? "已绑定" : "null"));
        this.onJsMessageCallback = callback;
    }

    @UniComponentProp(name = "onProgress")
    public void setOnProgress(UniJSCallback callback) {
        Log.d(TAG, "===== setOnProgress 被调用，callback=" + (callback != null ? "已绑定" : "null"));
        this.onProgressCallback = callback;
    }

    @UniJSMethod
    public void reload() {
        if (mWebView != null) {
            mWebView.reload();
        }
    }

    @UniJSMethod
    public void goBack() {
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        }
    }

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

    @UniJSMethod
    public void goForward() {
        if (mWebView != null && mWebView.canGoForward()) {
            mWebView.goForward();
        }
    }

    @UniJSMethod
    public String getCurrentUrl() {
        return mWebView != null ? mWebView.getUrl() : "";
    }

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

    @UniJSMethod
    public void clearCache(boolean includeDiskFiles) {
        if (mWebView != null) {
            mWebView.clearCache(includeDiskFiles);
            if (includeDiskFiles) {
                mWebView.clearHistory();
                Log.d(TAG, "clearCache: 已清除内存缓存 + 磁盘缓存 + 历史记录");
            } else {
                Log.d(TAG, "clearCache: 已清除内存缓存（磁盘缓存保留）");
            }
        }
    }

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
     */
    public class JsBridge {

        @android.webkit.JavascriptInterface
        public void postMessage(String message) {
            if (!TextUtils.isEmpty(message)) {
                try {
                    JSONObject data = JSONObject.parseObject(message);
                    if (onJsMessageCallback != null) {
                        onJsMessageCallback.invokeAndKeepAlive(data);
                    }
                    if (mWebView != null) {
                        mWebView.post(() -> fireEvent("onjsmessage", createEventParams(data)));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        @android.webkit.JavascriptInterface
        public void callHandler(String handlerName, String data) {
            try {
                JSONObject params = new JSONObject();
                params.put("handler", handlerName);
                if (!TextUtils.isEmpty(data)) {
                    params.put("data", JSONObject.parseObject(data));
                }
                if (onJsMessageCallback != null) {
                    onJsMessageCallback.invokeAndKeepAlive(params);
                }
                if (mWebView != null) {
                    mWebView.post(() -> fireEvent("onjsmessage", createEventParams(params)));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
