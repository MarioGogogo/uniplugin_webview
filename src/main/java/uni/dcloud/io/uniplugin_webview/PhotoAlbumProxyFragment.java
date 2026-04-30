package uni.dcloud.io.uniplugin_webview;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * 无 UI 的代理 Fragment，用于启动 PhotoAlbumActivity 并处理返回结果。
 * 使用 android.app.Fragment（与 FileChooserFragment 保持一致），
 * 确保在 uni-app 宿主 Activity 中能正确收到 onActivityResult。
 */
public class PhotoAlbumProxyFragment extends Fragment {

    public interface PhotoAlbumResultCallback {
        void onResult(Uri[] uris);
    }

    private static final int REQUEST_CODE = 10002;
    private static final String TAG = "PhotoAlbumProxyFragment";

    private PhotoAlbumResultCallback mCallback;

    public PhotoAlbumProxyFragment() {}

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
    }

    public void openPhotoAlbum(int maxSelectable, PhotoAlbumResultCallback callback) {
        this.mCallback = callback;
        Intent intent = new Intent(getActivity(), PhotoAlbumActivity.class);
        intent.putExtra(PhotoAlbumActivity.EXTRA_MAX_SELECTABLE, maxSelectable);
        try {
            startActivityForResult(intent, REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "startActivityForResult failed", e);
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
                String[] uriStrings = data.getStringArrayExtra(PhotoAlbumActivity.EXTRA_SELECTED_URIS);
                if (uriStrings != null && uriStrings.length > 0) {
                    results = new Uri[uriStrings.length];
                    for (int i = 0; i < uriStrings.length; i++) {
                        results[i] = Uri.parse(uriStrings[i]);
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
