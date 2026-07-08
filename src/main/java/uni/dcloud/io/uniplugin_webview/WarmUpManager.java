package uni.dcloud.io.uniplugin_webview;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebView 预热管理器
 *
 * 功能：
 * 1. 在应用启动时预热 WebView 实例
 * 2. 预加载常用资源
 * 3. 预初始化 JavaScript 环境
 * 4. 利用空闲时段进行预热
 *
 * 使用示例：
 * <pre>
 * // 在 Application.onCreate() 中调用
 * WarmUpManager.getInstance().warmUp(context);
 *
 * // 获取预热好的 WebView
 * WebView webView = WarmUpManager.getInstance().getWarmedWebView();
 *
 * // 检查是否已预热
 * boolean isWarmed = WarmUpManager.getInstance().isWarmedUp();
 * </pre>
 */
public class WarmUpManager {

    private static final String TAG = "WarmUpManager";
    private static volatile WarmUpManager instance;

    // 预热状态
    private final AtomicBoolean isWarmedUp = new AtomicBoolean(false);

    // 预热中的 WebView
    private volatile WebView warmingUpWebView;

    // 预热完成回调
    private volatile WarmUpCallback warmUpCallback;

    /**
     * 预热回调接口
     */
    public interface WarmUpCallback {
        /**
         * 预热完成
         * @param success 是否成功
         * @param webView 预热好的 WebView（可能为 null）
         */
        void onWarmUpComplete(boolean success, WebView webView);
    }

    /**
     * 私有构造函数
     */
    private WarmUpManager() {
        Log.d(TAG, "WarmUpManager 初始化");
    }

    /**
     * 获取单例实例
     */
    public static WarmUpManager getInstance() {
        if (instance == null) {
            synchronized (WarmUpManager.class) {
                if (instance == null) {
                    instance = new WarmUpManager();
                }
            }
        }
        return instance;
    }

    /**
     * 预热 WebView（在后台线程执行）
     *
     * @param context 上下文
     */
    public void warmUp(Context context) {
        warmUp(context, null);
    }

    /**
     * 预热 WebView（带回调）
     *
     * @param context 上下文
     * @param callback 预热完成回调
     */
    public void warmUp(final Context context, final WarmUpCallback callback) {
        if (context == null) {
            Log.e(TAG, "context 不能为 null");
            if (callback != null) {
                callback.onWarmUpComplete(false, null);
            }
            return;
        }

        // 如果已经预热，直接返回
        if (isWarmedUp.get()) {
            Log.d(TAG, "WebView 已经预热过，直接使用");
            if (callback != null) {
                callback.onWarmUpComplete(true, null);
            }
            return;
        }

        // 如果正在预热，等待完成
        if (warmingUpWebView != null) {
            Log.d(TAG, "WebView 正在预热中，等待完成");
            this.warmUpCallback = callback;
            return;
        }

        Log.d(TAG, "开始预热 WebView");
        this.warmUpCallback = callback;

        // WebView 必须在主线程中创建
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                // 创建 WebView（必须在主线程）
                final WebView webView = new WebView(context.getApplicationContext());

                // 配置 WebView
                configureWarmUpWebView(webView);

                // 设置为不可见
                webView.setVisibility(View.GONE);

                // 保存预热中的 WebView
                warmingUpWebView = webView;

                // 预加载常用资源
                preLoadCommonResources(webView);

                // 将 WebView 加入池
                WebViewPool.getInstance().setWarmedUp(true);

                // 标记预热完成
                isWarmedUp.set(true);

                Log.d(TAG, "WebView 预热完成");

                // 回调
                if (warmUpCallback != null) {
                    warmUpCallback.onWarmUpComplete(true, webView);
                    warmUpCallback = null;
                }
                warmingUpWebView = null;

            } catch (Exception e) {
                Log.e(TAG, "WebView 预热失败: " + e.getMessage(), e);

                // 标记预热失败
                isWarmedUp.set(false);
                warmingUpWebView = null;

                // 回调失败
                if (warmUpCallback != null) {
                    warmUpCallback.onWarmUpComplete(false, null);
                    warmUpCallback = null;
                }
            }
        });
    }

    /**
     * 空闲时段预热（利用 IdleHandler）
     *
     * @param context 上下文
     */
    public void warmUpWhenIdle(final Context context) {
        if (context == null) {
            Log.e(TAG, "context 不能为 null");
            return;
        }

        if (isWarmedUp.get()) {
            Log.d(TAG, "已经预热过，无需空闲预热");
            return;
        }

        Log.d(TAG, "安排空闲时段预热");

        try {
            // 使用 IdleHandler 在空闲时预热
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Looper.myQueue().addIdleHandler(() -> {
                        Log.d(TAG, "系统空闲，开始预热");
                        try {
                            warmUp(context);
                        } catch (Exception e) {
                            Log.e(TAG, "预热过程出错: " + e.getMessage(), e);
                        }
                        // 只预热一次，返回 false 移除 IdleHandler
                        return false;
                    });
                } catch (Exception e) {
                    Log.e(TAG, "添加 IdleHandler 失败: " + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "安排空闲预热失败: " + e.getMessage(), e);
            // 降级：立即预热
            Log.d(TAG, "降级方案：立即执行预热");
            warmUp(context);
        }
    }

    /**
     * 获取预热好的 WebView
     *
     * @return 预热好的 WebView，如果未预热或已销毁返回 null
     */
    public WebView getWarmedWebView() {
        if (!isWarmedUp.get()) {
            Log.w(TAG, "WebView 未预热");
            return null;
        }

        return WebViewPool.getInstance().getPooledWebView("warmup_pool");
    }

    /**
     * 检查是否已预热
     *
     * @return 是否已预热
     */
    public boolean isWarmedUp() {
        return isWarmedUp.get();
    }

    /**
     * 重置预热状态（用于测试或强制重新预热）
     */
    public void reset() {
        Log.d(TAG, "重置预热状态");
        isWarmedUp.set(false);
        warmingUpWebView = null;
        warmUpCallback = null;
        WebViewPool.getInstance().setWarmedUp(false);
    }

    /**
     * 配置预热 WebView
     */
    private void configureWarmUpWebView(WebView webView) {
        android.webkit.WebSettings settings = webView.getSettings();

        // 启用硬件加速
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
        settings.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);

        // 安全设置
        settings.setAllowFileAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        // 编码设置
        settings.setDefaultTextEncodingName("UTF-8");

        // Android 5.0+ 允许混合内容
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        Log.d(TAG, "预热 WebView 配置完成");
    }

    /**
     * 预加载常用资源
     */
    private void preLoadCommonResources(final WebView webView) {
        // 加载一个简单的预热页面
        String warmUpHtml = createWarmUpHtml();

        // 在主线程加载
        new Handler(Looper.getMainLooper()).post(() -> {
            webView.loadDataWithBaseURL(null, warmUpHtml, "text/html", "UTF-8", null);

            // 等待页面加载完成后，执行一些 JavaScript 预热
            webView.setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public void onPageFinished(android.webkit.WebView view, String url) {
                    super.onPageFinished(view, url);

                    // 预热 JavaScript 引擎
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        view.evaluateJavascript(
                            "(function(){" +
                                "console.log('WebView JS 引擎预热完成');" +
                                "window.uni = { postMessage: function(){}, onMessage: function(){} };" +
                                "return 'ok';" +
                            "})()",
                            result -> Log.d(TAG, "JS 预热结果: " + result)
                        );
                    }
                }
            });
        });
    }

    /**
     * 创建预热 HTML 页面
     */
    private String createWarmUpHtml() {
        return "<!DOCTYPE html>\n" +
            "<html>\n" +
            "<head>\n" +
            "    <meta charset=\"UTF-8\">\n" +
            "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
            "    <title>WebView WarmUp</title>\n" +
            "    <style>\n" +
            "        body { margin: 0; padding: 0; }\n" +
            "        #warmup { display: none; }\n" +
            "    </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "    <div id=\"warmup\">WarmUp</div>\n" +
            "    <script>\n" +
            "        // 预热 JavaScript 引擎\n" +
            "        console.log('WebView 预热开始');\n" +
            "        \n" +
            "        // 预初始化常用对象\n" +
            "        window.uni = {\n" +
            "            postMessage: function(msg) { console.log('postMessage:', msg); },\n" +
            "            onMessage: function(callback) { console.log('onMessage registered'); }\n" +
            "        };\n" +
            "        \n" +
            "        // 触发一次完整的渲染流程\n" +
            "        document.addEventListener('DOMContentLoaded', function() {\n" +
            "            document.body.innerHTML = '<div>WarmUp</div>';\n" +
            "            console.log('WebView 预热完成');\n" +
            "        });\n" +
            "    </script>\n" +
            "</body>\n" +
            "</html>";
    }
}
