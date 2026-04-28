package uni.dcloud.io.uniplugin_webview;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tencent.smtt.sdk.QbSdk;
import com.tencent.smtt.sdk.QbSdk.PreInitCallback;
import com.tencent.smtt.sdk.TbsListener;

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

    // TbsListener 和 Timer 的共享状态标志
    private volatile boolean mInstallFinished = false;
    private volatile Timer mTimer = null;

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
        Log.d(TAG, "===== startInstallX5 开始 =====");
        String abi = Build.CPU_ABI;
        Log.d(TAG, "设备 ABI=" + abi);
        boolean is64Bit = abi != null && (abi.contains("arm64") || abi.contains("v8"));

        if (!is64Bit) {
            Log.e(TAG, "架构不匹配，当前 ABI=" + abi + "，需要 arm64-v8a");
            notifyError("架构不匹配，内核包要求 arm64-v8a，您的设备是：" + abi);
            return;
        }

        Log.d(TAG, "开始拷贝内核文件...");
        boolean copied = copyX5Core();
        Log.d(TAG, "copyX5Core 结果=" + copied);
        if (!copied) {
            notifyError("文件拷贝失败，请检查 assets 目录是否有内核包: " + CORE_NAME);
            return;
        }

        Log.d(TAG, "拷贝完成，进入安装核心逻辑...");
        startInstallX5LocationCore();
    }

    /**
     * 执行本地内核安装核心逻辑
     */
    private void startInstallX5LocationCore() {
        try {
            Log.d(TAG, "startInstallX5LocationCore 开始执行");
            // 【极其重要】清除之前可能失败产生的内核状态锁死记录
            QbSdk.reset(mContext);
            Log.d(TAG, "QbSdk.reset 完成");

            // 【核心修复】设置 TbsListener 监听底层安装状态
            // onInstallFinish(200) 表示安装流程已完成，但当前进程需重启才能读取版本号
            QbSdk.setTbsListener(new TbsListener() {
                @Override
                public void onDownloadFinish(int errCode) {
                    Log.d(TAG, "TbsListener.onDownloadFinish, errCode=" + errCode);
                }

                @Override
                public void onInstallFinish(int errCode) {
                    Log.d(TAG, "TbsListener.onInstallFinish, errCode=" + errCode);
                    if (errCode == 200 && !mInstallFinished) {
                        Log.i(TAG, "TBS 底层安装完成(errCode=200)，触发成功回调");
                        mInstallFinished = true;
                        if (mTimer != null) {
                            mTimer.cancel();
                            mTimer = null;
                        }
                        notifySuccess();
                    } else if (errCode != 200 && !mInstallFinished) {
                        Log.e(TAG, "TBS 底层安装失败，errCode=" + errCode);
                        mInstallFinished = true;
                        if (mTimer != null) {
                            mTimer.cancel();
                            mTimer = null;
                        }
                        notifyError("TBS 底层安装错误，错误码：" + errCode);
                    }
                }

                @Override
                public void onDownloadProgress(int progress) {
                    Log.d(TAG, "TbsListener.onDownloadProgress, progress=" + progress);
                }
            });

            // installLocalTbsCore 必须在主线程执行
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "主线程: 开始调用 installLocalTbsCore");
                    QbSdk.installLocalTbsCore(mContext, CORE_VERSION, mCorePath);
                    Log.d(TAG, "主线程: installLocalTbsCore 已调用, version=" + CORE_VERSION + ", path=" + mCorePath);

                    // 【致命死锁解除】很多新版 SDK 必须调用 initX5Environment 才会真正启动后台解压引擎
                    QbSdk.initX5Environment(mContext, new PreInitCallback() {
                        @Override
                        public void onCoreInitFinished() {
                            Log.d(TAG, "主线程: onCoreInitFinished");
                        }

                        @Override
                        public void onViewInitFinished(boolean isX5) {
                            Log.d(TAG, "主线程: onViewInitFinished isX5=" + isX5);
                        }
                    });
                    Log.d(TAG, "主线程: initX5Environment 已调用");
                }
            });

            // 延迟 3 秒后开始轮询（兜底，防止 TbsListener 未被触发）
            mTimer = new Timer();
            final boolean[] hasInit = {false};
            final int[] elapsed = {0};

            Log.d(TAG, "启动轮询检测 Timer，延迟 3 秒开始...");
            mTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (mInstallFinished) {
                        Log.d(TAG, "轮询检测: mInstallFinished=true，Timer 提前结束");
                        mTimer.cancel();
                        return;
                    }

                    elapsed[0]++;
                    int version = QbSdk.getTbsVersion(mContext);
                    Log.d(TAG, "轮询检测: version=" + version + ", 已等待=" + elapsed[0] + "s");

                    if (version > 0 && !hasInit[0]) {
                        Log.d(TAG, "轮询检测: 内核安装成功! version=" + version);
                        hasInit[0] = true;
                        mInstallFinished = true;
                        mTimer.cancel();
                        notifySuccess();
                        return;
                    }

                    if (elapsed[0] >= 60 && version <= 0 && !mInstallFinished) {
                        Log.e(TAG, "轮询检测: 安装超时(60s)，内核仍未就绪");
                        mInstallFinished = true;
                        mTimer.cancel();
                        notifyError("内核安装超时，请检查内核文件是否匹配当前设备 ABI");
                    }
                }
            }, 3000, 1000);

        } catch (Exception e) {
            Log.e(TAG, "本地离线内核安装异常: " + e.getMessage(), e);
            if (!mInstallFinished) {
                mInstallFinished = true;
                notifyError(e.getMessage() != null ? e.getMessage() : "安装异常");
            }
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
                boolean mkdirs = parent.mkdirs();
                Log.d(TAG, "创建目录结果=" + mkdirs + ", 路径=" + parent.getAbsolutePath());
            }

            if (file.exists()) {
                boolean deleted = file.delete();
                Log.d(TAG, "删除旧内核文件结果=" + deleted);
            }

            Log.d(TAG, "开始从 assets 读取内核文件: " + CORE_NAME);
            InputStream ins = mContext.getResources().getAssets().open(CORE_NAME);
            FileOutputStream fos = new FileOutputStream(file);
            Log.d(TAG, "开始拷贝内核文件到: " + file.getAbsolutePath());

            byte[] buffer = new byte[8192];
            int count;
            long total = 0;
            while ((count = ins.read(buffer)) > 0) {
                fos.write(buffer, 0, count);
                total += count;
            }

            fos.close();
            ins.close();

            // 设置全局可读，确保 TBS SDK（可能在独立进程）能访问该文件
            file.setReadable(true, false);
            Log.d(TAG, "拷贝内核文件完成，路径=" + file.getAbsolutePath() + ", 大小=" + file.length() + " 字节");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "拷贝内核文件异常: " + e.getMessage(), e);
            // 不在这里调用 notifyError，由外部 startInstallX5 统一处理，避免双重回调
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
