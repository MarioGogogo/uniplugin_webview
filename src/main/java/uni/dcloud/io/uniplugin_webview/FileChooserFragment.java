package uni.dcloud.io.uniplugin_webview;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * 无 UI 的代理 Fragment，专门用于处理 WebView 文件选择的 startActivityForResult 回调。
 * 以解耦对宿主 Activity 的直接依赖。
 *
 * 方案要点：通过系统 SAF（Storage Access Framework，Intent.ACTION_GET_CONTENT）
 * 拉起文件选择器，content:// Uri 由系统授予临时 URI 权限，
 * 因此【无需】申请 READ_MEDIA_* / READ_EXTERNAL_STORAGE 等存储权限。
 */
public class FileChooserFragment extends Fragment {

    public interface FileChooserResult {
        void onResult(Uri[] uris);
    }

    private FileChooserResult mCallback;
    private static final int REQUEST_CODE = 10001;

    // 无参构造函数必须保留
    public FileChooserFragment() {}

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true); // 保留实例，防止屏幕旋转等销毁回调
    }

    public void start(Intent intent, FileChooserResult callback) {
        mCallback = callback;
        try {
            startActivityForResult(intent, REQUEST_CODE);
        } catch (Exception e) {
            e.printStackTrace();
            if (mCallback != null) {
                mCallback.onResult(null);
                mCallback = null;
            }
            removeSelf();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE) {
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                } else if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            if (mCallback != null) {
                mCallback.onResult(results);
                mCallback = null;
            }
        }

        removeSelf();
    }

    private void removeSelf() {
        FragmentManager fm = getFragmentManager();
        if (fm != null) {
            fm.beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }
}
