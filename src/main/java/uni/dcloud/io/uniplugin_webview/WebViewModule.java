package uni.dcloud.io.uniplugin_webview;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.alibaba.fastjson.JSONObject;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;
import io.dcloud.feature.uniapp.UniSDKInstance;

/**
 * WebView 模块 - 提供全局 WebView 管理功能
 * 包括 Cookie 管理、缓存清理、打开外部浏览器等
 */
public class WebViewModule extends UniModule {

    /**
     * 清除所有 WebView 缓存
     * @param callback 回调函数
     */
    @UniJSMethod(uiThread = true)
    public void clearAllCache(UniJSCallback callback) {
        if (mUniSDKInstance != null && mUniSDKInstance.getContext() instanceof Activity) {
            Activity activity = (Activity) mUniSDKInstance.getContext();
            activity.runOnUiThread(() -> {
                try {
                    // 清除 WebView 缓存
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();

                    // 清除 Web Storage
                    WebStorage.getInstance().deleteAllData();

                    JSONObject result = new JSONObject();
                    result.put("success", true);
                    result.put("message", "缓存已清除");

                    if (callback != null) {
                        callback.invoke(result);
                    }
                } catch (Exception e) {
                    JSONObject result = new JSONObject();
                    result.put("success", false);
                    result.put("message", e.getMessage());
                    if (callback != null) {
                        callback.invoke(result);
                    }
                }
            });
        }
    }

    /**
     * 设置 Cookie
     * @param options {url, name, value}
     * @param callback 回调函数
     */
    @UniJSMethod(uiThread = true)
    public void setCookie(JSONObject options, UniJSCallback callback) {
        if (options == null) {
            invokeCallback(callback, false, "参数不能为空", null);
            return;
        }

        String url = options.getString("url");
        String name = options.getString("name");
        String value = options.getString("value");

        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(name)) {
            invokeCallback(callback, false, "url 和 name 不能为空", null);
            return;
        }

        try {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setCookie(url, name + "=" + value);

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Cookie 设置成功");

            invokeCallback(callback, true, "Cookie 设置成功", result);
        } catch (Exception e) {
            invokeCallback(callback, false, e.getMessage(), null);
        }
    }

    /**
     * 获取 Cookie
     * @param options {url}
     * @param callback 回调函数
     */
    @UniJSMethod(uiThread = true)
    public void getCookie(JSONObject options, UniJSCallback callback) {
        if (options == null) {
            invokeCallback(callback, false, "参数不能为空", null);
            return;
        }

        String url = options.getString("url");
        if (TextUtils.isEmpty(url)) {
            invokeCallback(callback, false, "url 不能为空", null);
            return;
        }

        try {
            CookieManager cookieManager = CookieManager.getInstance();
            String cookies = cookieManager.getCookie(url);

            JSONObject result = new JSONObject();
            result.put("cookies", cookies != null ? cookies : "");
            result.put("success", true);

            invokeCallback(callback, true, "获取成功", result);
        } catch (Exception e) {
            invokeCallback(callback, false, e.getMessage(), null);
        }
    }

    /**
     * 删除 Cookie
     * @param options {url, name}
     * @param callback 回调函数
     */
    @UniJSMethod(uiThread = true)
    public void removeCookie(JSONObject options, UniJSCallback callback) {
        if (options == null) {
            invokeCallback(callback, false, "参数不能为空", null);
            return;
        }

        String url = options.getString("url");
        String name = options.getString("name");

        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(name)) {
            invokeCallback(callback, false, "url 和 name 不能为空", null);
            return;
        }

        try {
            // 删除 Cookie 需要设置过期时间
            CookieManager cookieManager = CookieManager.getInstance();
            String cookie = name + "=; expires=Wed, 31 Dec 2000 23:59:59 GMT";
            cookieManager.setCookie(url, cookie);

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Cookie 已删除");

            invokeCallback(callback, true, "Cookie 已删除", result);
        } catch (Exception e) {
            invokeCallback(callback, false, e.getMessage(), null);
        }
    }

    /**
     * 清除所有 Cookie
     * @param callback 回调函数
     */
    @UniJSMethod(uiThread = true)
    public void clearAllCookies(UniJSCallback callback) {
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.removeAllCookies(null);
            cookieManager.flush();

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "所有 Cookie 已清除");

            invokeCallback(callback, true, "所有 Cookie 已清除", result);
        } catch (Exception e) {
            invokeCallback(callback, false, e.getMessage(), null);
        }
    }

    /**
     * 在外部浏览器打开 URL
     * @param options {url}
     * @param callback 回调函数
     */
    @UniJSMethod(uiThread = true)
    public void openInBrowser(JSONObject options, UniJSCallback callback) {
        if (options == null) {
            invokeCallback(callback, false, "参数不能为空", null);
            return;
        }

        String url = options.getString("url");
        if (TextUtils.isEmpty(url)) {
            invokeCallback(callback, false, "url 不能为空", null);
            return;
        }

        try {
            if (mUniSDKInstance != null && mUniSDKInstance.getContext() instanceof Activity) {
                Activity activity = (Activity) mUniSDKInstance.getContext();
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                activity.startActivity(intent);

                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("message", "已在浏览器打开");

                invokeCallback(callback, true, "已在浏览器打开", result);
            } else {
                invokeCallback(callback, false, "Activity 为空", null);
            }
        } catch (Exception e) {
            invokeCallback(callback, false, e.getMessage(), null);
        }
    }

    /**
     * 获取 WebView 版本信息
     * @param callback 回调函数
     */
    @UniJSMethod(uiThread = true)
    public void getWebViewVersion(UniJSCallback callback) {
        try {
            JSONObject result = new JSONObject();
            result.put("success", true);

            // 获取 Android 版本
            result.put("androidVersion", android.os.Build.VERSION.RELEASE);
            result.put("sdkVersion", android.os.Build.VERSION.SDK_INT);

            invokeCallback(callback, true, "获取成功", result);
        } catch (Exception e) {
            invokeCallback(callback, false, e.getMessage(), null);
        }
    }

    /**
     * 预加载 URL（缓存页面）
     * @param options {url}
     * @param callback 回调函数
     */
    @UniJSMethod(uiThread = true)
    public void preloadUrl(JSONObject options, UniJSCallback callback) {
        if (options == null) {
            invokeCallback(callback, false, "参数不能为空", null);
            return;
        }

        String url = options.getString("url");
        if (TextUtils.isEmpty(url)) {
            invokeCallback(callback, false, "url 不能为空", null);
            return;
        }

        try {
            if (mUniSDKInstance != null && mUniSDKInstance.getContext() instanceof Activity) {
                Activity activity = (Activity) mUniSDKInstance.getContext();
                // 创建一个隐藏的 WebView 进行预加载
                WebView webView = new WebView(activity.getApplicationContext());
                webView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
                webView.loadUrl(url);

                // 预加载完成后销毁
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(android.webkit.WebView view, String url) {
                        view.destroy();
                    }
                });

                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("message", "预加载开始");

                invokeCallback(callback, true, "预加载开始", result);
            }
        } catch (Exception e) {
            invokeCallback(callback, false, e.getMessage(), null);
        }
    }

    /**
     * 工具方法：调用回调
     */
    private void invokeCallback(UniJSCallback callback, boolean success, String message, JSONObject data) {
        if (callback != null) {
            JSONObject result = new JSONObject();
            result.put("success", success);
            result.put("message", message);
            if (data != null) {
                result.putAll(data);
            }
            callback.invoke(result);
        }
    }
}
