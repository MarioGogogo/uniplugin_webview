package uni.dcloud.io.uniplugin_webview;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebView 单例池管理器
 *
 * 功能：
 * 1. WebView 实例复用，避免重复创建的开销
 * 2. 支持多个 WebView 池（按用途分类）
 * 3. 自动清理闲置的 WebView
 * 4. 线程安全的单例实现
 *
 * 使用示例：
 * <pre>
 * // 获取 WebView
 * WebView webView = WebViewPool.getInstance().getWebView(context, "main_pool");
 *
 * // 复用 WebView（清空状态）
 * WebViewPool.getInstance().reuseWebView("main_pool");
 *
 * // 回收 WebView 到池中
 * WebViewPool.getInstance().recycleWebView("main_pool", webView);
 *
 * // 清理指定池
 * WebViewPool.getInstance().clearPool("main_pool");
 * </pre>
 */
public class WebViewPool {

    private static final String TAG = "WebViewPool";
    private static volatile WebViewPool instance;

    // WebView 池：key 为池名称，value 为 WebView 实例
    private final ConcurrentHashMap<String, WebView> webViewPool = new ConcurrentHashMap<>();

    // 每个池的使用计数：key 为池名称，value 为引用计数
    private final ConcurrentHashMap<String, AtomicInteger> poolRefCount = new ConcurrentHashMap<>();

    // 最大池大小限制
    private static final int MAX_POOL_SIZE = 5;

    // 预热状态
    private boolean isWarmedUp = false;

    /**
     * 私有构造函数
     */
    private WebViewPool() {
        Log.d(TAG, "WebViewPool 初始化");
    }

    /**
     * 获取单例实例
     */
    public static WebViewPool getInstance() {
        if (instance == null) {
            synchronized (WebViewPool.class) {
                if (instance == null) {
                    instance = new WebViewPool();
                }
            }
        }
        return instance;
    }

    /**
     * 获取或创建 WebView
     *
     * @param context 上下文
     * @param poolName 池名称（如 "main_pool", "preload_pool"）
     * @return WebView 实例
     */
    public WebView getWebView(Context context, String poolName) {
        if (context == null) {
            Log.e(TAG, "context 不能为 null");
            return null;
        }

        if (poolName == null || poolName.isEmpty()) {
            poolName = "default_pool";
        }

        // 尝试从池中获取
        WebView webView = webViewPool.get(poolName);

        if (webView != null) {
            Log.d(TAG, "从池中获取 WebView: " + poolName);
            // 增加引用计数
            incrementRefCount(poolName);
            return webView;
        }

        // 池中不存在，创建新的
        Log.d(TAG, "创建新的 WebView: " + poolName);

        // 检查池大小限制
        if (webViewPool.size() >= MAX_POOL_SIZE) {
            Log.w(TAG, "WebView 池已满（" + MAX_POOL_SIZE + "），清理最旧的池");
            clearOldestPool();
        }

        // 使用 ApplicationContext 避免内存泄漏
        webView = new WebView(context.getApplicationContext());
        configureWebView(webView);

        // 加入池
        webViewPool.put(poolName, webView);
        poolRefCount.put(poolName, new AtomicInteger(1));

        Log.d(TAG, "WebView 已创建并加入池: " + poolName + "，当前池大小: " + webViewPool.size());

        return webView;
    }

    /**
     * 复用 WebView（清空历史和状态）
     *
     * @param poolName 池名称
     */
    public void reuseWebView(String poolName) {
        if (poolName == null || poolName.isEmpty()) {
            poolName = "default_pool";
        }

        WebView webView = webViewPool.get(poolName);
        if (webView != null) {
            Log.d(TAG, "复用 WebView: " + poolName);

            // 清空历史
            webView.clearHistory();

            // 清空缓存（可选）
            // webView.clearCache(true);

            // 加载空白页
            webView.loadUrl("about:blank");

            Log.d(TAG, "WebView 已复用: " + poolName);
        } else {
            Log.w(TAG, "池中没有可复用的 WebView: " + poolName);
        }
    }

    /**
     * 回收 WebView 到池中
     *
     * @param poolName 池名称
     * @param webView WebView 实例
     */
    public void recycleWebView(String poolName, WebView webView) {
        if (poolName == null || poolName.isEmpty()) {
            poolName = "default_pool";
        }

        if (webView == null) {
            Log.w(TAG, "webView 不能为 null");
            return;
        }

        // 检查是否是池中的 WebView
        WebView pooledWebView = webViewPool.get(poolName);
        if (pooledWebView != webView) {
            Log.w(TAG, "尝试回收不属于池的 WebView: " + poolName);
            return;
        }

        // 减少引用计数
        int refCount = decrementRefCount(poolName);

        Log.d(TAG, "回收 WebView: " + poolName + "，引用计数: " + refCount);

        // 如果引用计数为 0，可以选择清理
        // 但为了复用，我们保留在池中
    }

    /**
     * 清理指定池
     *
     * @param poolName 池名称
     */
    public void clearPool(String poolName) {
        if (poolName == null || poolName.isEmpty()) {
            poolName = "default_pool";
        }

        WebView webView = webViewPool.remove(poolName);
        if (webView != null) {
            Log.d(TAG, "清理池: " + poolName);

            webView.removeAllViews();
            webView.destroy();

            poolRefCount.remove(poolName);
        }
    }

    /**
     * 清理所有池
     */
    public void clearAll() {
        Log.d(TAG, "清理所有 WebView 池");

        for (String poolName : webViewPool.keySet()) {
            WebView webView = webViewPool.get(poolName);
            if (webView != null) {
                webView.removeAllViews();
                webView.destroy();
            }
        }

        webViewPool.clear();
        poolRefCount.clear();

        isWarmedUp = false;
    }

    /**
     * 获取池中的 WebView（不创建新的）
     *
     * @param poolName 池名称
     * @return WebView 实例，如果不存在返回 null
     */
    public WebView getPooledWebView(String poolName) {
        if (poolName == null || poolName.isEmpty()) {
            poolName = "default_pool";
        }

        return webViewPool.get(poolName);
    }

    /**
     * 检查池中是否存在 WebView
     *
     * @param poolName 池名称
     * @return 是否存在
     */
    public boolean hasWebView(String poolName) {
        if (poolName == null || poolName.isEmpty()) {
            poolName = "default_pool";
        }

        return webViewPool.containsKey(poolName);
    }

    /**
     * 获取池大小
     *
     * @return 当前池中的 WebView 数量
     */
    public int getPoolSize() {
        return webViewPool.size();
    }

    /**
     * 设置预热状态
     *
     * @param warmedUp 是否已预热
     */
    public void setWarmedUp(boolean warmedUp) {
        this.isWarmedUp = warmedUp;
        Log.d(TAG, "预热状态: " + warmedUp);
    }

    /**
     * 获取预热状态
     *
     * @return 是否已预热
     */
    public boolean isWarmedUp() {
        return isWarmedUp;
    }

    /**
     * 配置 WebView 设置
     */
    private void configureWebView(WebView webView) {
        WebSettings settings = webView.getSettings();

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
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 安全设置
        settings.setAllowFileAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        // 编码设置
        settings.setDefaultTextEncodingName("UTF-8");

        // Android 5.0+ 允许混合内容
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        Log.d(TAG, "WebView 配置完成");
    }

    /**
     * 增加引用计数
     */
    private void incrementRefCount(String poolName) {
        poolRefCount.computeIfAbsent(poolName, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * 减少引用计数
     */
    private int decrementRefCount(String poolName) {
        AtomicInteger refCount = poolRefCount.get(poolName);
        if (refCount != null) {
            return refCount.decrementAndGet();
        }
        return 0;
    }

    /**
     * 清理最旧的池（LRU 策略）
     */
    private void clearOldestPool() {
        // 简单实现：清理第一个池
        if (!webViewPool.isEmpty()) {
            String oldestPool = webViewPool.keySet().iterator().next();
            clearPool(oldestPool);
            Log.d(TAG, "已清理最旧的池: " + oldestPool);
        }
    }
}
