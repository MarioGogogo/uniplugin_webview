package uni.dcloud.io.uniplugin_webview;

/**
 * X5 离线内核安装结果回调接口
 */
public interface X5LocalInstallListener {

    /**
     * 安装成功回调
     */
    void onSuccess();

    /**
     * 安装失败回调
     *
     * @param message 错误信息
     */
    void onError(String message);
}
