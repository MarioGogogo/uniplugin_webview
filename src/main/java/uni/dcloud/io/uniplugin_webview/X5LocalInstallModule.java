package uni.dcloud.io.uniplugin_webview;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.alibaba.fastjson.JSONObject;
import com.tencent.smtt.sdk.QbSdk;
import com.tencent.smtt.sdk.TbsListener;

import java.util.HashMap;
import java.util.Map;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

/**
 * X5 离线内核安装模块
 * <p>
 * 提供 X5 内核离线安装、状态查询、TBS 设置配置等功能。
 * <p>
 * 前端使用：const x5Local = uni.requireNativePlugin('X5LocalInstallModule')
 */
public class X5LocalInstallModule extends UniModule {

    private static final String TAG = "X5LocalInstallModule";

    /**
     * 检查 X5 内核是否已安装
     *
     * @param callback 回调函数，返回 { installed: true/false, version: "xxx", abi: "xxx" }
     */
    @UniJSMethod(uiThread = true)
    public void checkX5Installed(UniJSCallback callback) {
        try {
            JSONObject result = new JSONObject();

            int version = QbSdk.getTbsVersion(mUniSDKInstance != null ? mUniSDKInstance.getContext() : null);
            boolean installed = version > 0;

            result.put("installed", installed);
            result.put("version", String.valueOf(version));
            result.put("abi", Build.CPU_ABI);

            invokeCallback(callback, true, installed ? "X5 内核已安装" : "X5 内核未安装", result);
        } catch (Exception e) {
            invokeCallback(callback, false, e.getMessage(), null);
        }
    }

    /**
     * 获取 X5 内核信息
     *
     * @param callback 回调函数，返回 { isX5, version, sdkVersion, canSupportVideo }
     */
    @UniJSMethod(uiThread = true)
    public void getX5Info(UniJSCallback callback) {
        try {
            JSONObject result = new JSONObject();

            boolean isX5 = QbSdk.isTbsCoreInited();
            int version = QbSdk.getTbsVersion(mUniSDKInstance != null ? mUniSDKInstance.getContext() : null);

            result.put("isX5", isX5);
            result.put("version", String.valueOf(version));
            result.put("sdkVersion", version);
            result.put("canSupportVideo", QbSdk.canOpenWebPlus(mUniSDKInstance != null ? mUniSDKInstance.getContext() : null));

            invokeCallback(callback, true, "获取成功", result);
        } catch (Exception e) {
            invokeCallback(callback, false, e.getMessage(), null);
        }
    }

    /**
     * 触发 X5 离线内核安装
     * <p>
     * 流程：校验 ABI → 拷贝内核文件 → 调用 installLocalTbsCore → 轮询检测 → 回调结果
     *
     * @param options 可选参数 { timeout: 60 } 超时秒数
     * @param callback 回调函数，安装成功/失败时触发
     */
    @UniJSMethod(uiThread = false)
    public void installX5Core(JSONObject options, final UniJSCallback callback) {
        Log.d(TAG, "===== installX5Core 被调用 =====");
        if (mUniSDKInstance == null || mUniSDKInstance.getContext() == null) {
            Log.e(TAG, "Context 为空，无法安装");
            invokeCallback(callback, false, "Context 为空，无法安装", null);
            return;
        }

        final Activity activity = (Activity) mUniSDKInstance.getContext();

        // 配置 TBS 设置（避免高版本系统的类加载限制）
        final Map<String, Object> map = new HashMap<>();
        map.put("use_speedy_classloader", true);
        map.put("use_dexloader_service", true);
        QbSdk.initTbsSettings(map);

        // 允许非 WiFi 下载
        QbSdk.setDownloadWithoutWifi(true);

        // 监听安装状态，获取底层错误码
        QbSdk.setTbsListener(new TbsListener() {
            @Override
            public void onDownloadFinish(int errCode) {
                Log.d(TAG, "TbsListener.onDownloadFinish, errCode=" + errCode);
            }

            @Override
            public void onInstallFinish(int errCode) {
                Log.d(TAG, "TbsListener.onInstallFinish, errCode=" + errCode);
                if (errCode != 200) {
                    // TbsListener 回调在后台线程，切到主线程 invoke
                    final int code = errCode;
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Log.e(TAG, "TBS 底层安装错误，errCode=" + code);
                            invokeCallback(callback, false, "TBS 底层安装错误，错误码：" + code, null);
                        }
                    });
                }
            }

            @Override
            public void onDownloadProgress(int progress) {
                Log.d(TAG, "TbsListener.onDownloadProgress, progress=" + progress);
            }
        });

        // 如果已经安装了，直接返回成功
        if (X5LocalInstaller.isInited(activity)) {
            int version = QbSdk.getTbsVersion(activity);
            Log.d(TAG, "X5 已安装，version=" + version + "，无需重复安装");
            JSONObject result = new JSONObject();
            result.put("version", String.valueOf(version));
            result.put("needRestart", false);
            invokeCallback(callback, true, "X5 内核已安装，无需重复安装", result);
            return;
        }

        Log.d(TAG, "X5 未安装，启动离线安装流程...");

        // 子线程执行安装
        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "安装子线程已启动");
                X5LocalInstaller installer = new X5LocalInstaller(activity, new X5LocalInstallListener() {
                    @Override
                    public void onSuccess() {
                        Log.d(TAG, "X5LocalInstallListener.onSuccess 被触发");
                        int version = QbSdk.getTbsVersion(activity);
                        JSONObject result = new JSONObject();
                        result.put("version", String.valueOf(version));
                        result.put("needRestart", true);
                        Log.d(TAG, "准备回调前端: success=true, version=" + version + ", needRestart=true");
                        invokeCallback(callback, true, "X5 内核离线安装成功，请重启应用", result);
                    }

                    @Override
                    public void onError(String message) {
                        Log.e(TAG, "X5LocalInstallListener.onError 被触发: " + message);
                        invokeCallback(callback, false, message, null);
                    }
                });
                installer.startInstallX5();
            }
        }).start();
    }

    /**
     * 重启应用进程（安装成功后建议调用）
     * <p>
     * 首次安装成功后必须重启进程，否则可能出现类加载器污染导致 X5 特性无法使用。
     *
     * @param callback 回调函数
     */
    @UniJSMethod(uiThread = true)
    public void restartApp(UniJSCallback callback) {
        try {
            if (mUniSDKInstance == null || mUniSDKInstance.getContext() == null) {
                invokeCallback(callback, false, "Context 为空", null);
                return;
            }

            Activity activity = (Activity) mUniSDKInstance.getContext();
            Intent intent = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                activity.startActivity(intent);
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            }

            invokeCallback(callback, true, "应用重启中...", null);
        } catch (Exception e) {
            invokeCallback(callback, false, e.getMessage(), null);
        }
    }

    /**
     * 配置 TBS 设置
     *
     * @param options { useSpeedyClassloader: true, useDexloaderService: true, downloadWithoutWifi: true }
     * @param callback 回调函数
     */
    @UniJSMethod(uiThread = true)
    public void setTbsSettings(JSONObject options, UniJSCallback callback) {
        try {
            Map<String, Object> map = new HashMap<>();

            if (options != null) {
                if (options.containsKey("useSpeedyClassloader")) {
                    map.put("use_speedy_classloader", options.getBoolean("useSpeedyClassloader"));
                }
                if (options.containsKey("useDexloaderService")) {
                    map.put("use_dexloader_service", options.getBoolean("useDexloaderService"));
                }
                if (options.containsKey("downloadWithoutWifi")) {
                    QbSdk.setDownloadWithoutWifi(options.getBoolean("downloadWithoutWifi"));
                }
            }

            if (!map.isEmpty()) {
                QbSdk.initTbsSettings(map);
            }

            invokeCallback(callback, true, "TBS 设置已更新", null);
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
