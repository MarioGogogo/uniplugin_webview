package uni.dcloud.io.uniplugin_webview;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

/**
 * 无 UI 的代理 Fragment，用于处理摄像头拍照的 startActivityForResult 回调
 * 内置 CAMERA 运行时权限申请
 */
public class CameraProxyFragment extends Fragment {

    public interface CameraResultCallback {
        void onResult(Uri uri);
    }

    private CameraResultCallback mCallback;
    private Uri mCameraImageUri;
    private static final int REQUEST_CODE_CAMERA = 10002;

    private ActivityResultLauncher<String> mPermissionLauncher;
    private ActivityResultLauncher<Uri> mTakePictureLauncher;
    private boolean mPendingLaunch = false;

    public CameraProxyFragment() {}

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);

        mPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        launchCamera();
                    } else {
                        Log.w("CameraProxyFragment", "Camera permission denied");
                        if (mCallback != null) {
                            mCallback.onResult(null);
                            mCallback = null;
                        }
                        removeSelf();
                    }
                }
        );
    }

    public void start(CameraResultCallback callback) {
        mCallback = callback;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            mPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchCamera() {
        Log.d("CameraProxyFragment", "launchCamera 启动 (使用纯净 startActivity 方案)");
        try {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (intent.resolveActivity(requireActivity().getPackageManager()) != null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.TITLE, "Camera_" + System.currentTimeMillis());
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "Camera_" + System.currentTimeMillis() + ".jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                mCameraImageUri = requireActivity().getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, mCameraImageUri);
                Log.d("CameraProxyFragment", "启动普通 Intent, URI=" + mCameraImageUri);
                mPendingLaunch = true;
                startActivity(intent);
            } else {
                Log.e("CameraProxyFragment", "No camera app available");
                if (mCallback != null) {
                    mCallback.onResult(null);
                    mCallback = null;
                }
                removeSelf();
            }
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
    public void onResume() {
        super.onResume();
        if (mPendingLaunch) {
            mPendingLaunch = false;
            Log.d("CameraProxyFragment", "onResume 触发，开始检查照片写入情况...");
            // 延迟 500ms 检查，防止相机退回时系统 IO 还没写入完成
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                Uri resultUri = null;
                if (mCameraImageUri != null) {
                    try {
                        java.io.InputStream is = requireActivity().getContentResolver().openInputStream(mCameraImageUri);
                        if (is != null && is.available() > 0) {
                            Log.d("CameraProxyFragment", "检查成功，照片存在，大小=" + is.available());
                            resultUri = mCameraImageUri;
                            is.close();
                        } else {
                            Log.d("CameraProxyFragment", "检查失败，文件为空或未拍照片");
                            if (is != null) is.close();
                            requireActivity().getContentResolver().delete(mCameraImageUri, null, null);
                        }
                    } catch (Exception e) {
                        Log.e("CameraProxyFragment", "检查照片异常: " + e.getMessage());
                        try {
                            requireActivity().getContentResolver().delete(mCameraImageUri, null, null);
                        } catch (Exception ignored) {}
                    }
                }
                
                if (mCallback != null) {
                    mCallback.onResult(resultUri);
                    mCallback = null;
                }
                removeSelf();
            }, 500);
        }
    }

    private void removeSelf() {
        if (isAdded() && getParentFragmentManager() != null) {
            getParentFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }
}
