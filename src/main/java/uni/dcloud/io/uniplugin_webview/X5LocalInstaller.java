package uni.dcloud.io.uniplugin_webview;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tencent.smtt.sdk.QbSdk;
import com.tencent.smtt.sdk.QbSdk.PreInitCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Timer;
import java.util.TimerTask;

/**
 * X5 内核离线安装工具类
 * <p>
 * 负责将 assets 中的内核 APK 拷贝到设备存储，并调用 QbSdk.installLocalTbsCore() 完成本地离线安装。
 * <p>
 * 使用示例：
 * <pre>
 * X5LocalInstaller installer = new X5LocalInstaller(context, new X5LocalInstallListener() {
 *     public void onSuccess() { }
 *     public void onError(String message) { }
 * });
 * installer.startInstallX5();
 * </pre>
 */
public class X5LocalInstaller {

    private static final String TAG = "X5LocalInstaller";

    /**
     * 内核版本号，必须与内核 APK 文件匹配
     */
    private static final int CORE_VERSION = 46141;

    /**
     * 内核文件名称（assets 中存放的离线内核包）
     */
    private static final String CORE_NAME = "tbs_core_046141_20220915165042_nolog_fs_obfs_arm64-v8a_release.apk";

    private final Context mContext;
    private final X5LocalInstallListener mListener;
    private final String mDir;
    private final String mCorePath;
    private final Handler mMainHandler;

    /**
     * @param context  上下文环境
     * @param listener 安装结果回调
     */
    public X5LocalInstaller(Context context, X5LocalInstallListener listener) {
        this.mContext = context.getApplicationContext();
        this.mListener = listener;
        this.mMainHandler = new Handler(Looper.getMainLooper());

        File downloadDir = mContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        this.mDir = (downloadDir != null) ? downloadDir.getAbsolutePath() : mContext.getFilesDir().getAbsolutePath();
        this.mCorePath = mDir + "/" + CORE_NAME;
    }

    /**
     * 检查 X5 内核是否已初始化（版本号 > 0）
     *
     * @param context 上下文
     * @return true 表示已初始化
     */
    public static boolean isInited(Context context) {
        return QbSdk.getTbsVersion(context) > 0;
    }

    /**
     * 开始安装 X5 内核
     * <p>
     * 流程：校验 ABI 架构 → 拷贝内核文件 → 调用本地安装 → 轮询检测安装结果
     */
    public void startInstallX5() {
        String abi = Build.CPU_ABI;
        boolean is64Bit = abi != null && (abi.contains("arm64") || abi.contains("v8"));

        if (!is64Bit) {
            Log.d(TAG, "内核型号不匹配，无法进行本地离线安装内核. 当前 ABI: " + abi);
            notifyError("架构不匹配，内核包要求 arm64-v8a，您的设备是：" + abi);
            return;
        }

        if (!copyX5Core()) {
            notifyError("文件解压/拷贝失败，请检查 assets 目录是否有包");
            return;
        }

        startInstallX5LocationCore();
    }

    /**
     * 执行本地内核安装核心逻辑
     */
    private void startInstallX5LocationCore() {
        try {
            // 【极其重要】清除之前可能失败产生的内核状态锁死记录
            QbSdk.reset(mContext);

            // installLocalTbsCore 必须在主线程执行
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    QbSdk.installLocalTbsCore(mContext, CORE_VERSION, mCorePath);
                    Log.d(TAG, "installLocalTbsCore 已调用");

                    // 【致命死锁解除】很多新版 SDK 必须调用 initX5Environment 才会真正启动后台解压引擎
                    QbSdk.initX5Environment(mContext, new PreInitCallback() {
                        @Override
                        public void onCoreInitFinished() {
                            Log.d(TAG, "onCoreInitFinished (安装前置触发)");
                        }

                        @Override
                        public void onViewInitFinished(boolean p0) {
                            Log.d(TAG, "onViewInitFinished_p0=" + p0 + " (安装前置触发)");
                        }
                    });
                }
            });

            // 延迟 3 秒后开始轮询
            final Timer timer = new Timer();
            final boolean[] hasInit = {false};
            final int[] elapsed = {0};

            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    elapsed[0]++;
                    int version = QbSdk.getTbsVersion(mContext);
                    Log.d(TAG, "循环检验内核版本" + version + ", 已等待" + elapsed[0] + "s");

                    if (version > 0 && !hasInit[0]) {
                        hasInit[0] = true;
                        timer.cancel();
                        notifySuccess();
                    }

                    if (elapsed[0] >= 60 && version <= 0) {
                        timer.cancel();
                        notifyError("内核安装超时，请检查内核文件是否匹配当前设备 ABI");
                    }
                }
            }, 3000, 1000);

        } catch (Exception e) {
            Log.d(TAG, "本地离线内核安装异常,异常信息>" + e.getMessage());
            notifyError(e.getMessage() != null ? e.getMessage() : "安装异常");
        }
    }

    /**
     * 将内核文件从 assets 拷贝到指定目录
     *
     * @return true 表示拷贝成功
     */
    private boolean copyX5Core() {
        try {
            File file = new File(mCorePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            if (file.exists()) {
                file.delete();
            }

            InputStream ins = mContext.getResources().getAssets().open(CORE_NAME);
            Log.d(TAG, "开始读取内核文件");
            FileOutputStream fos = new FileOutputStream(file);
            Log.d(TAG, "开始拷贝内核文件");

            byte[] buffer = new byte[1024];
            int count;
            while ((count = ins.read(buffer)) > 0) {
                fos.write(buffer, 0, count);
            }

            fos.close();
            ins.close();

            // 设置全局可读，确保 TBS SDK（可能在独立进程）能访问该文件
            file.setReadable(true, false);
            Log.d(TAG, "拷贝内核文件完成，路径=" + file.getAbsolutePath() + ", 大小=" + file.length());
            return true;
        } catch (Exception e) {
            Log.d(TAG, "拷贝内核文件异常，异常信息>" + e.getMessage());
            notifyError(e.getMessage() != null ? e.getMessage() : "拷贝内核文件异常");
        }
        return false;
    }

    private void notifySuccess() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mListener != null) {
                    mListener.onSuccess();
                }
            }
        });
    }

    private void notifyError(final String message) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mListener != null) {
                    mListener.onError(message);
                }
            }
        });
    }
}
