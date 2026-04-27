package uni.dcloud.io.uniplugin_webview;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.alibaba.fastjson.JSONObject;
import com.tencent.smtt.sdk.CookieManager;
import com.tencent.smtt.sdk.QbSdk;
import com.tencent.smtt.sdk.WebStorage;
import com.tencent.smtt.sdk.WebView;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

/**
 * X5 内核 WebView 模块 - 提供全局 X5 WebView 管理功能
 * 包括 X5 内核初始化状态查询、Cookie 管理、缓存清理等
 *
 * 前端使用：const x5WebView = uni.requireNativePlugin('X5WebViewModule')
 */
public class X5WebViewModule extends UniModule {

    /**
     * 获取 X5 内核状态信息
     * @param callback 回调函数，返回 { isX5: true/false, version: "xxx" }
     */
    @UniJSMethod(uiThread = true)
    public void getX5Info(UniJSCallback callback) {
        try {
            JSONObject result = new JSONObject();

            // 判断是否使用 X5 内核
            boolean isX5 = QbSdk.isTbsCoreInited();
            result.put("isX5", isX5);

            // 获取 X5 内核版本 (返回 int)
            int version = QbSdk.getTbsVersion(mUniSDKInstance != null ? mUniSDKInstance.getContext() : null);
            result.put("version", String.valueOf(version));

            // 获取 TBS SDK 版本号
            result.put("sdkVersion", version);

            // 获取是否支持视频全屏
            result.put("canSupportVideo", QbSdk.canOpenWebPlus(mUniSDKInstance != null ? mUniSDKInstance.getContext() : null));

            invokeCallback(callback, true, "获取成功", result);
        } catch (Exception e) {
            invokeCallback(callback, false, e.getMessage(), null);
        }
    }

    /**
     * 清除所有 X5 WebView 缓存
     * @param callback 回调函数
     */
    @UniJSMethod(uiThread = true)
    public void clearAllCache(UniJSCallback callback) {
        if (mUniSDKInstance != null && mUniSDKInstance.getContext() instanceof Activity) {
            Activity activity = (Activity) mUniSDKInstance.getContext();
            activity.runOnUiThread(() -> {
                try {
                    // 清除 Cookie
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();

                    // 清除 Web Storage
                    WebStorage.getInstance().deleteAllData();

                    JSONObject result = new JSONObject();
                    result.put("success", true);
                    result.put("message", "X5 缓存已清除");

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

            invokeCallback(callback, true, "Cookie 设置成功", null);
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
            CookieManager cookieManager = CookieManager.getInstance();
            String cookie = name + "=; expires=Wed, 31 Dec 2000 23:59:59 GMT";
            cookieManager.setCookie(url, cookie);

            invokeCallback(callback, true, "Cookie 已删除", null);
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

            invokeCallback(callback, true, "所有 Cookie 已清除", null);
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

                invokeCallback(callback, true, "已在浏览器打开", null);
            } else {
                invokeCallback(callback, false, "Activity 为空", null);
            }
        } catch (Exception e) {
            invokeCallback(callback, false, e.getMessage(), null);
        }
    }

    /**
     * 预加载 URL（使用 X5 内核缓存页面）
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
                WebView webView = new WebView(activity.getApplicationContext());
                webView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
                webView.loadUrl(url);

                webView.setWebViewClient(new com.tencent.smtt.sdk.WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        view.destroy();
                    }
                });

                invokeCallback(callback, true, "X5 预加载开始", null);
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
