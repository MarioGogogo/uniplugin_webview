package uni.dcloud.io.uniplugin_webview;

import android.app.Activity;
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
 */
public class WebViewComponent extends UniComponent<FrameLayout> {

    private WebView mWebView;
    private UniJSCallback onPageStartCallback;
    private UniJSCallback onPageFinishCallback;
    private UniJSCallback onPageErrorCallback;
    private UniJSCallback onJsMessageCallback;
    private UniJSCallback onProgressCallback;
    private String mUrl = "";
    private String mHtmlContent = "";
    private String mPoolName = ""; // WebView 池名称

    private static final String TAG = "WebViewComponent";
    private static final boolean USE_WEBVIEW_POOL = true; // 是否启用 WebView 池

    public WebViewComponent(UniSDKInstance instance, AbsVContainer parent, AbsComponentData basicComponentData) {
        super(instance, parent, basicComponentData);
        Log.d(TAG, "WebViewComponent 初始化");

        // 生成唯一的池名称（基于实例 ID）
        mPoolName = "webview_pool_" + System.currentTimeMillis();
        Log.d(TAG, "WebView 池名称: " + mPoolName);
    }


    @Override
    protected FrameLayout initComponentHostView(Context context) {
        Log.d(TAG, "===== initComponentHostView 被调用 =====");
        FrameLayout container = new FrameLayout(context);
        container.setBackgroundColor(Color.WHITE);

        // 从池中获取或创建 WebView
        if (USE_WEBVIEW_POOL) {
            // 优先尝试从预热池获取
            mWebView = WebViewPool.getInstance().getPooledWebView("warmup_pool");

            if (mWebView != null) {
                Log.d(TAG, "使用预热池中的 WebView");
                // 复制配置（确保安全设置）
                setupWebView(mWebView);
                // 从预热池移除，避免多个组件共享同一个 WebView
                WebViewPool.getInstance().clearPool("warmup_pool");
            } else {
                // 创建新的 WebView 并加入池
                mWebView = WebViewPool.getInstance().getWebView(context, mPoolName);
                Log.d(TAG, "从池中获取或创建新的 WebView: " + mPoolName);
                setupWebView(mWebView);
            }
        } else {
            // 传统方式：每次创建新的 WebView
            mWebView = new WebView(context);
            Log.d(TAG, "创建新的 WebView（未使用池）");
            setupWebView(mWebView);
        }

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
            Log.d(TAG, "延迟加载 URL: " + mUrl);
            mWebView.post(() -> {
                Log.d(TAG, "开始加载 URL: " + mUrl);
                mWebView.loadUrl(mUrl);
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

        // 启用硬件加速，显著提升渲染性能
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

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

        // 文件访问安全配置：平衡安全性与文件上传功能
        // setAllowFileAccess 必须为 true 才能支持 <input type="file">
        // 但我们可以通过拦截 file:// 协议来防止恶意加载
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);  // 禁止 file:// URL 的 JS 访问
        settings.setAllowUniversalAccessFromFileURLs(false); // 禁止跨域访问

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

                // 拦截 file:// 协议，防止加载本地文件
                if (url.startsWith("file://")) {
                    Log.w(TAG, "拦截 file:// 协议，拒绝加载：" + url);
                    return true;
                }

                // http / https：让 WebView 正常加载，支持 302 重定向
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false;
                }

                // 其他 scheme（baiduboxapp://, intent://, tel:, mailto: 等 deep link）
                // 用系统 Intent 处理，跳转到对应 App；未安装则静默忽略
                try {
                    android.content.Intent intent = android.content.Intent.parseUri(
                            url, android.content.Intent.URI_INTENT_SCHEME);
                    // 安全检查：禁止 Intent 携带 component 或 package 跳转，防止动态注入
                    if (intent.getComponent() != null || intent.getPackage() != null) {
                        Log.w(TAG, "拦截可疑 Intent（含 component/package）：" + url);
                        return true;
                    }
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Log.e(TAG, "加载失败 (new API): errorCode=" + error.getErrorCode() + ", description=" + error.getDescription() + ", url=" + request.getUrl().toString());
                }
                if (!request.isForMainFrame()) {
                    Log.d(TAG, "子资源加载失败，忽略：" + request.getUrl().toString());
                    return;
                }
                JSONObject data = new JSONObject();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    data.put("errorCode", error.getErrorCode());
                    data.put("description", error.getDescription() != null ? error.getDescription() : "");
                } else {
                    data.put("errorCode", -1);
                    data.put("description", "");
                }
                data.put("failingUrl", request.getUrl().toString());
                data.put("url", request.getUrl().toString());
                if (onPageErrorCallback != null) {
                    onPageErrorCallback.invokeAndKeepAlive(data);
                }
                fireEvent("onpageerror", createEventParams(data));
            }
        });

        // 设置 WebChromeClient 处理 JS 对话框、地理位置和文件上传
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

            /**
             * 递归从 Context 中解析出宿主 Activity（WebView 宿主可能被 ContextWrapper 包裹）
             */
            private android.app.Activity getActivityFromContext(Context context) {
                if (context instanceof android.app.Activity) {
                    return (android.app.Activity) context;
                }
                while (context instanceof android.content.ContextWrapper) {
                    if (context instanceof android.app.Activity) {
                        return (android.app.Activity) context;
                    }
                    context = ((android.content.ContextWrapper) context).getBaseContext();
                }
                return null;
            }

            /**
             * 处理文件上传请求（Android 5.0+）
             * 当 H5 页面点击 <input type="file"> 时触发。
             * 直接通过系统 SAF 拉起文件选择器，无需申请存储/相册权限。
             */
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                Log.d(TAG, "WebView: onShowFileChooser triggered");
                Context context = getInstance() != null ? getInstance().getContext() : webView.getContext();
                android.app.Activity activity = getActivityFromContext(context);

                if (activity != null) {
                    Log.d(TAG, "WebView: Found Activity, starting FileChooserFragment");
                    FileChooserFragment fragment = new FileChooserFragment();
                    activity.getFragmentManager().beginTransaction().add(fragment, "fileChooser").commitAllowingStateLoss();
                    activity.getFragmentManager().executePendingTransactions();

                    android.content.Intent intent = null;
                    if (fileChooserParams != null) {
                        try {
                            intent = fileChooserParams.createIntent();
                        } catch (Exception e) {
                            Log.w(TAG, "WebView: createIntent failed", e);
                        }
                    }
                    if (intent == null) {
                        intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                    }
                    // 核心修复：防止 H5 <input type="file"> 未指定 accept 导致 intent type 为空从而崩溃
                    if (android.text.TextUtils.isEmpty(intent.getType())) {
                        intent.setType("*/*");
                    }

                    fragment.start(intent, uris -> filePathCallback.onReceiveValue(uris));
                    return true;
                } else {
                    Log.e(TAG, "WebView: Cannot find Activity context! context=" + context);
                    filePathCallback.onReceiveValue(null);
                }
                return false;
            }

            // For Android 4.1+（旧版兼容入口）
            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
                Log.d(TAG, "WebView: openFileChooser triggered");
                Context context = getInstance() != null ? getInstance().getContext() : mWebView.getContext();
                android.app.Activity activity = getActivityFromContext(context);
                if (activity != null) {
                    Log.d(TAG, "WebView: Found Activity for openFileChooser");
                    FileChooserFragment fragment = new FileChooserFragment();
                    activity.getFragmentManager().beginTransaction().add(fragment, "fileChooser").commitAllowingStateLoss();
                    activity.getFragmentManager().executePendingTransactions();

                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                    intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                    intent.setType(TextUtils.isEmpty(acceptType) ? "*/*" : acceptType);

                    fragment.start(intent, uris -> uploadMsg.onReceiveValue(uris != null && uris.length > 0 ? uris[0] : null));
                } else {
                    Log.e(TAG, "WebView: Cannot find Activity context for openFileChooser!");
                    uploadMsg.onReceiveValue(null);
                }
            }
        });

        // 添加 JavaScript 接口
        webView.addJavascriptInterface(new JsBridge(), "UniWebView");
    }

    /**
     * 设置加载的 URL
     * 仅支持 http:// 和 https:// 协议
     */
    @UniComponentProp(name = "src")
    public void setSrc(String url) {
        Log.d(TAG, "===== setSrc 被调用，url=" + url);
        if (!TextUtils.isEmpty(url)) {
            // 只允许 http/https 协议，拒绝 file:// 等本地路径
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Log.w(TAG, "仅支持 http/https 协议，拒绝加载：" + url);
                return;
            }
            mUrl = url;
            if (mWebView != null) {
                Log.d(TAG, "WebView 已就绪，延迟加载 URL: " + mUrl);
                mWebView.post(() -> {
                    Log.d(TAG, "开始加载 URL: " + mUrl);
                    mWebView.loadUrl(mUrl);
                });
            }
        } else {
            Log.e(TAG, "url 为空");
        }
    }

    /**
     * 设置 HTML 内容
     */
    @UniComponentProp(name = "html")
    public void setHtml(String html) {
        if (!TextUtils.isEmpty(html)) {
            mHtmlContent = sanitizeHtml(html);
            if (mWebView != null) {
                mWebView.loadDataWithBaseURL(null, mHtmlContent, "text/html", "UTF-8", null);
            }
        }
    }

    /**
     * 对 HTML 内容进行安全过滤，移除危险的 script 标签和事件处理器属性
     */
    private String sanitizeHtml(String html) {
        if (html == null) return "";
        // 移除 <script>...</script> 标签
        String sanitized = html.replaceAll("(?i)<script[^>]*>[\\s\\S]*?</script>", "");
        // 移除 onXxx 事件属性（如 onclick, onerror 等）
        sanitized = sanitized.replaceAll("(?i)\\s+on\\w+\\s*=\\s*([\"'][^\"']*[\"']|\\S+)", "");
        // 移除 javascript: 协议
        sanitized = sanitized.replaceAll("(?i)javascript\\s*:", "");
        return sanitized;
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
            if (USE_WEBVIEW_POOL) {
                // 回收到池中，而不是销毁
                Log.d(TAG, "回收 WebView 到池: " + mPoolName);
                WebViewPool.getInstance().recycleWebView(mPoolName, mWebView);
            } else {
                // 传统方式：销毁 WebView
                mWebView.removeAllViews();
                mWebView.destroy();
            }
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
     * 复用当前 WebView（清空历史和状态）
     * 调用示例：this.$refs.webview.reuse()
     */
    @UniJSMethod
    public void reuse() {
        if (mWebView != null && USE_WEBVIEW_POOL) {
            Log.d(TAG, "复用 WebView: " + mPoolName);

            // 清空历史
            mWebView.clearHistory();

            // 清空缓存（可选）
            // mWebView.clearCache(true);

            // 加载空白页
            mWebView.loadUrl("about:blank");

            // 通知池更新
            WebViewPool.getInstance().reuseWebView(mPoolName);
        }
    }

    /**
     * 获取 WebView 池状态信息
     * 调用示例：this.$refs.webview.getPoolStatus(result => { console.log(result) })
     */
    @UniJSMethod
    public void getPoolStatus(UniJSCallback callback) {
        if (callback != null) {
            JSONObject data = new JSONObject();
            data.put("usePool", USE_WEBVIEW_POOL);
            data.put("poolName", mPoolName);
            data.put("poolSize", WebViewPool.getInstance().getPoolSize());
            data.put("isWarmedUp", WebViewPool.getInstance().isWarmedUp());
            data.put("hasWebView", WebViewPool.getInstance().hasWebView(mPoolName));
            callback.invoke(data);
        }
    }

    /**
     * 清理当前 WebView 池
     * 调用示例：this.$refs.webview.clearPool()
     */
    @UniJSMethod
    public void clearPool() {
        if (USE_WEBVIEW_POOL) {
            Log.d(TAG, "清理 WebView 池: " + mPoolName);
            WebViewPool.getInstance().clearPool(mPoolName);
        }
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
            if (!TextUtils.isEmpty(message) && isValidJsonMessage(message)) {
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

        /**
         * 调用 uni-app 方法
         * @param handlerName 方法名
         * @param data JSON 字符串格式的参数
         */
        @JavascriptInterface
        public void callHandler(String handlerName, String data) {
            try {
                // 校验 handlerName，防止注入
                if (TextUtils.isEmpty(handlerName) || !handlerName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                    Log.w(TAG, "callHandler: handlerName 不合法：" + handlerName);
                    return;
                }
                JSONObject params = new JSONObject();
                params.put("handler", handlerName);
                if (!TextUtils.isEmpty(data) && isValidJsonMessage(data)) {
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

        /**
         * 校验 JSON 消息字符串，防止 XSS 注入
         */
        private boolean isValidJsonMessage(String message) {
            if (message.length() > 1024 * 64) return false; // 限制 64KB
            return true;
        }
    }
}
