package uni.dcloud.io.uniplugin_webview;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原生相册选择器 Activity
 * 不使用第三方依赖，仿系统相册网格选择界面
 */
public class PhotoAlbumActivity extends Activity {

    public static final String EXTRA_MAX_SELECTABLE = "max_selectable";
    public static final String EXTRA_SELECTED_URIS = "selected_uris";

    private static final String TAG = "PhotoAlbumActivity";
    private static final int SPAN_COUNT = 4;
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int SYSTEM_PICKER_REQUEST_CODE = 1002;

    private RecyclerView mRecyclerView;
    private PhotoAdapter mAdapter;
    private TextView mTvTitle;
    private TextView mBtnConfirm;
    private TextView mBtnPreview;
    private View mFolderListContainer;
    private RecyclerView mFolderRecyclerView;
    private FolderAdapter mFolderAdapter;
    private View mLoadingView;
    private View mTitleContainer;
    private ImageView mBtnArrow;

    private int mMaxSelectable = 9;
    private List<PhotoItem> mCurrentPhotos = new ArrayList<>();
    private List<FolderItem> mFolders = new ArrayList<>();
    private Map<String, List<PhotoItem>> mFolderMap = new LinkedHashMap<>();
    private List<PhotoItem> mSelectedPhotos = new ArrayList<>();
    // 标志位：用户从设置页面返回后是否需要重新加载
    private boolean mNeedReloadOnResume = false;

    // 缩略图内存缓存 + 后台加载线程池
    private android.util.LruCache<String, Bitmap> mThumbnailCache;
    private java.util.concurrent.ExecutorService mImageLoadExecutor;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_album);

        mMaxSelectable = getIntent().getIntExtra(EXTRA_MAX_SELECTABLE, 9);
        if (mMaxSelectable <= 0) mMaxSelectable = 1;

        // 初始化缩略图缓存（最大内存的 1/8）
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        mThumbnailCache = new android.util.LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        mImageLoadExecutor = java.util.concurrent.Executors.newFixedThreadPool(4);

        initViews();
        checkAndRequestPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户从设置页面返回后，重新加载照片
        if (mNeedReloadOnResume) {
            mNeedReloadOnResume = false;
            Log.d(TAG, "从设置返回，重新加载照片");
            mSelectedPhotos.clear();
            loadPhotos();
        }
    }

    private void initViews() {
        // 设置状态栏为透明，让内容延伸到状态栏下方
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        }

        // 状态栏占位高度
        View statusBarPlaceholder = findViewById(R.id.status_bar_placeholder);
        statusBarPlaceholder.getLayoutParams().height = getStatusBarHeight();

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        mTvTitle = findViewById(R.id.tv_title);
        mTvTitle.setText("全部照片");

        mBtnArrow = findViewById(R.id.btn_arrow);
        mTitleContainer = findViewById(R.id.title_container);
        mTitleContainer.setOnClickListener(v -> toggleFolderList());

        mRecyclerView = findViewById(R.id.recycler_view);
        mRecyclerView.setLayoutManager(new GridLayoutManager(this, SPAN_COUNT));
        mAdapter = new PhotoAdapter();
        mRecyclerView.setAdapter(mAdapter);

        mBtnPreview = findViewById(R.id.btn_preview);
        mBtnConfirm = findViewById(R.id.btn_confirm);

        mBtnPreview.setOnClickListener(v -> {
            if (mSelectedPhotos.isEmpty()) return;
            Toast.makeText(this, "预览 " + mSelectedPhotos.size() + " 张", Toast.LENGTH_SHORT).show();
        });

        mBtnConfirm.setOnClickListener(v -> confirmSelection());

        mFolderListContainer = findViewById(R.id.folder_list_container);
        mFolderListContainer.setOnClickListener(v -> toggleFolderList());

        mFolderRecyclerView = findViewById(R.id.folder_recycler_view);
        mFolderRecyclerView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        mFolderAdapter = new FolderAdapter();
        mFolderRecyclerView.setAdapter(mFolderAdapter);

        mLoadingView = findViewById(R.id.loading_view);
    }

    /**
     * 获取需要请求的权限列表
     * 国产 ROM 兼容策略：同时请求 READ_EXTERNAL_STORAGE 和 READ_MEDIA_IMAGES
     * 部分厂商 ROM（如华为 HarmonyOS、小米 HyperOS）需要两个权限都被授予
     */
    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+：同时请求两个权限
            // targetSdkVersion < 33 的应用需要 READ_EXTERNAL_STORAGE 建立映射链
            // READ_MEDIA_IMAGES 是新的细粒度权限
            return new String[]{
                    "android.permission.READ_MEDIA_IMAGES",
                    "android.permission.READ_EXTERNAL_STORAGE"
            };
        }
        return new String[]{
                "android.permission.READ_EXTERNAL_STORAGE"
        };
    }

    private void checkAndRequestPermission() {
        String[] permissions = getRequiredPermissions();
        boolean allGranted = true;
        for (String perm : permissions) {
            int result = ContextCompat.checkSelfPermission(this, perm);
            Log.d(TAG, "权限检查: " + perm + " = " + (result == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
            }
        }

        if (allGranted) {
            Log.d(TAG, "所有权限已授予，开始加载照片");
            loadPhotos();
        } else {
            Log.d(TAG, "请求权限: " + java.util.Arrays.toString(permissions));
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean anyGranted = false;
            for (int i = 0; i < grantResults.length; i++) {
                String perm = i < permissions.length ? permissions[i] : "unknown";
                boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                Log.d(TAG, "权限结果: " + perm + " = " + (granted ? "GRANTED" : "DENIED"));
                if (granted) anyGranted = true;
            }

            if (anyGranted) {
                Log.d(TAG, "至少一个权限被授予，开始加载照片");
                loadPhotos();
            } else {
                // 即使权限被拒，仍然尝试加载
                // 部分国产 ROM（如 OPPO ColorOS）权限检查返回 DENIED 但实际可查询 MediaStore
                Log.w(TAG, "权限被拒绝，仍尝试加载（国产 ROM 兼容模式）");
                loadPhotos();
            }
        }
    }

    /**
     * 获取状态栏高度（像素）
     */
    private int getStatusBarHeight() {
        int result = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    private void toggleFolderList() {
        if (mFolderListContainer.getVisibility() == View.VISIBLE) {
            mFolderListContainer.setVisibility(View.GONE);
            mBtnArrow.setRotation(0);
        } else {
            mFolderListContainer.setVisibility(View.VISIBLE);
            mBtnArrow.setRotation(180);
        }
    }

    private void loadPhotos() {
        mLoadingView.setVisibility(View.VISIBLE);

        // === 诊断日志：输出设备和权限状态 ===
        Log.d(TAG, "========== 相册诊断信息 ==========");
        Log.d(TAG, "设备: " + Build.MANUFACTURER + " " + Build.MODEL);
        Log.d(TAG, "Android 版本: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        Log.d(TAG, "ROM: " + Build.DISPLAY);
        Log.d(TAG, "READ_EXTERNAL_STORAGE: " +
                (ContextCompat.checkSelfPermission(this, "android.permission.READ_EXTERNAL_STORAGE")
                        == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));
        if (Build.VERSION.SDK_INT >= 33) {
            Log.d(TAG, "READ_MEDIA_IMAGES: " +
                    (ContextCompat.checkSelfPermission(this, "android.permission.READ_MEDIA_IMAGES")
                            == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));
        }
        Log.d(TAG, "===================================");

        new Thread(() -> {
            queryPhotos();
            runOnUiThread(() -> {
                mLoadingView.setVisibility(View.GONE);
                List<PhotoItem> allPhotos = mFolderMap.get("全部");
                if (allPhotos == null) allPhotos = new ArrayList<>();
                mCurrentPhotos = allPhotos;
                mAdapter.notifyDataSetChanged();
                mFolderAdapter.notifyDataSetChanged();
                updateBottomBar();
                if (allPhotos.isEmpty()) {
                    // MediaStore 和文件系统都未找到照片
                    // Android 14+：可能是用户在权限弹窗中选择了"选择照片和视频"（部分访问模式）
                    // 而非"允许全部"，导致 MediaStore 返回空结果
                    Log.w(TAG, "MediaStore 和文件扫描均未找到照片");

                    if (Build.VERSION.SDK_INT >= 33) {
                        // 弹出对话框让用户选择
                        new android.app.AlertDialog.Builder(PhotoAlbumActivity.this)
                                .setTitle("无法读取照片")
                                .setMessage("请在系统设置中将照片权限改为「允许全部」，即可使用自定义相册。\n\n或者直接使用系统相册选择照片。")
                                .setPositiveButton("去设置", (dialog, which) -> {
                                    // 跳转到应用权限设置页面
                                    mNeedReloadOnResume = true;
                                    try {
                                        Intent settingsIntent = new Intent(
                                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:" + getPackageName()));
                                        startActivity(settingsIntent);
                                    } catch (Exception e) {
                                        Log.e(TAG, "跳转设置失败", e);
                                    }
                                    // 不 finish，用户从设置返回后可以重试
                                })
                                .setNegativeButton("使用系统相册", (dialog, which) -> {
                                    launchSystemPhotoPicker();
                                })
                                .setNeutralButton("取消", (dialog, which) -> {
                                    setResult(RESULT_CANCELED);
                                    finish();
                                })
                                .setCancelable(false)
                                .show();
                    } else {
                        // Android 12 及以下，直接回退到系统相册
                        launchSystemPhotoPicker();
                    }
                }
            });
        }).start();
    }

    /**
     * 系统 Photo Picker 回退方案
     * 当 MediaStore 和文件系统扫描都找不到照片时调用
     * 适用于 Android 14+ 的"部分照片访问"模式
     *
     * 回退策略（按优先级）：
     * 1. Android 13+ (API 33)：ACTION_PICK_IMAGES（系统 Photo Picker，无需权限）
     * 2. Android 4.4+ (API 19)：ACTION_OPEN_DOCUMENT（SAF 文件选择器）
     * 3. 传统方式：ACTION_PICK（旧版相册选择器）
     */
    private void launchSystemPhotoPicker() {
        Intent intent = null;

        // 策略 1：Android 13+ 系统 Photo Picker
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                intent = new Intent("android.provider.action.PICK_IMAGES");
                if (mMaxSelectable > 1) {
                    intent.putExtra("android.provider.extra.PICK_IMAGES_MAX", mMaxSelectable);
                }
                // 验证 intent 是否可用
                if (intent.resolveActivity(getPackageManager()) != null) {
                    Log.d(TAG, "使用系统 Photo Picker (ACTION_PICK_IMAGES)，maxSelectable=" + mMaxSelectable);
                    startActivityForResult(intent, SYSTEM_PICKER_REQUEST_CODE);
                    return;
                } else {
                    Log.w(TAG, "ACTION_PICK_IMAGES 不可用，尝试下一个策略");
                    intent = null;
                }
            } catch (Exception e) {
                Log.w(TAG, "ACTION_PICK_IMAGES 失败: " + e.getMessage());
                intent = null;
            }
        }

        // 策略 2：SAF 文件选择器（ACTION_OPEN_DOCUMENT）
        try {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            if (mMaxSelectable > 1) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            Log.d(TAG, "使用 SAF 文件选择器 (ACTION_OPEN_DOCUMENT)");
            startActivityForResult(intent, SYSTEM_PICKER_REQUEST_CODE);
        } catch (Exception e) {
            Log.w(TAG, "ACTION_OPEN_DOCUMENT 失败: " + e.getMessage());

            // 策略 3：传统 ACTION_PICK
            try {
                intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                intent.setType("image/*");
                Log.d(TAG, "使用传统选择器 (ACTION_PICK)");
                startActivityForResult(intent, SYSTEM_PICKER_REQUEST_CODE);
            } catch (Exception e2) {
                Log.e(TAG, "所有 Photo Picker 策略均失败", e2);
                Toast.makeText(this, "无法打开相册选择器", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SYSTEM_PICKER_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                List<String> uriStrings = new ArrayList<>();

                // 处理多选结果
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    for (int i = 0; i < count; i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        if (uri != null) {
                            uriStrings.add(uri.toString());
                            Log.d(TAG, "系统 Picker 选中: " + uri);
                        }
                    }
                }
                // 处理单选结果
                else if (data.getData() != null) {
                    uriStrings.add(data.getData().toString());
                    Log.d(TAG, "系统 Picker 选中: " + data.getData());
                }

                if (!uriStrings.isEmpty()) {
                    Log.d(TAG, "系统 Photo Picker 返回 " + uriStrings.size() + " 张照片");
                    Intent result = new Intent();
                    result.putExtra(EXTRA_SELECTED_URIS, uriStrings.toArray(new String[0]));
                    setResult(RESULT_OK, result);
                } else {
                    Log.w(TAG, "系统 Photo Picker 未返回任何照片");
                    setResult(RESULT_CANCELED);
                }
            } else {
                Log.d(TAG, "用户取消了系统 Photo Picker");
                setResult(RESULT_CANCELED);
            }
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mImageLoadExecutor != null) {
            mImageLoadExecutor.shutdown();
        }
    }

    private void queryPhotos() {
        List<PhotoItem> allList = new ArrayList<>();
        mFolderMap.clear();

        // 1. 标准 MediaStore 查询
        queryMediaStore(allList);

        // 2. MediaStore 为空时扫描文件系统
        if (allList.isEmpty()) {
            Log.d(TAG, "MediaStore empty, scanning file system...");
            scanDirectories(allList);
        }

        // 按修改时间倒序
        Collections.sort(allList, new Comparator<PhotoItem>() {
            @Override
            public int compare(PhotoItem a, PhotoItem b) {
                return Long.compare(b.lastModified, a.lastModified);
            }
        });

        Log.d(TAG, "Total photos = " + allList.size());

        mFolderMap.put("全部", allList);

        mFolders.clear();
        mFolders.add(new FolderItem("全部", allList.size(),
                allList.isEmpty() ? null : allList.get(0).uri));

        for (Map.Entry<String, List<PhotoItem>> entry : mFolderMap.entrySet()) {
            if (!"全部".equals(entry.getKey())) {
                List<PhotoItem> list = entry.getValue();
                mFolders.add(new FolderItem(entry.getKey(), list.size(),
                        list.isEmpty() ? null : list.get(0).uri));
            }
        }
    }

    private void queryMediaStore(List<PhotoItem> allList) {
        // 同时查询外部存储和内部存储，覆盖国产 ROM 可能将图片存储在不同 volume 的情况
        Uri[] contentUris = {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media.INTERNAL_CONTENT_URI
        };

        for (Uri contentUri : contentUris) {
            queryMediaStoreUri(contentUri, allList);
        }
    }

    /**
     * 对单个 MediaStore Content URI 执行查询
     * 使用 DATE_MODIFIED 列获取时间戳（避免通过 DATA 路径访问文件系统，Android 10+ 不可靠）
     * 增加 SIZE 和 MIME_TYPE 做合法性过滤
     */
    private void queryMediaStoreUri(Uri contentUri, List<PhotoItem> allList) {
        ContentResolver resolver = getContentResolver();
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATA
        };
        // 优先使用 DATE_MODIFIED 排序，部分国产 ROM 的 DATE_ADDED 不准确
        String sortOrder = MediaStore.Images.Media.DATE_MODIFIED + " DESC";
        // 过滤条件：文件大小 > 0 且 MIME 类型为图片
        String selection = MediaStore.Images.Media.SIZE + " > 0";

        Cursor cursor = null;
        try {
            cursor = resolver.query(contentUri, projection, selection, null, sortOrder);
            if (cursor != null) {
                Log.d(TAG, "MediaStore [" + contentUri + "] cursor count = " + cursor.getCount());
                int idColumn = cursor.getColumnIndex(MediaStore.Images.Media._ID);
                int bucketColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                int dateModifiedColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED);
                int dateAddedColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);
                int sizeColumn = cursor.getColumnIndex(MediaStore.Images.Media.SIZE);
                int mimeColumn = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE);
                int dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);

                    // MIME 类型校验：过滤非图片记录
                    String mimeType = mimeColumn != -1 ? cursor.getString(mimeColumn) : null;
                    if (mimeType != null && !mimeType.startsWith("image/")) {
                        continue;
                    }

                    // 文件大小校验：过滤损坏或过小的记录（< 1KB）
                    long size = sizeColumn != -1 ? cursor.getLong(sizeColumn) : 0;
                    if (size < 1024) {
                        continue;
                    }

                    // 文件夹名称：优先使用 BUCKET_DISPLAY_NAME，回退到 DATA 路径解析
                    String bucketName = bucketColumn != -1 ? cursor.getString(bucketColumn) : null;
                    if (bucketName == null || bucketName.trim().isEmpty()) {
                        // 尝试从 DATA 路径提取父目录名
                        if (dataColumn != -1) {
                            String dataPath = cursor.getString(dataColumn);
                            if (dataPath != null) {
                                File parentDir = new File(dataPath).getParentFile();
                                if (parentDir != null) {
                                    bucketName = parentDir.getName();
                                }
                            }
                        }
                        if (bucketName == null || bucketName.trim().isEmpty()) {
                            bucketName = "其他";
                        }
                    }

                    Uri uri = ContentUris.withAppendedId(contentUri, id);

                    // 时间戳：优先 DATE_MODIFIED（秒级），回退 DATE_ADDED，都转为毫秒
                    long lastModified = 0;
                    if (dateModifiedColumn != -1) {
                        lastModified = cursor.getLong(dateModifiedColumn) * 1000;
                    }
                    if (lastModified <= 0 && dateAddedColumn != -1) {
                        lastModified = cursor.getLong(dateAddedColumn) * 1000;
                    }

                    PhotoItem item = new PhotoItem(id, uri, lastModified);
                    allList.add(item);

                    List<PhotoItem> folderList = mFolderMap.get(bucketName);
                    if (folderList == null) {
                        folderList = new ArrayList<>();
                        mFolderMap.put(bucketName, folderList);
                    }
                    folderList.add(item);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore query error for " + contentUri, e);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * 文件系统扫描回退方案
     * 覆盖国产 ROM 常见的相册存储路径：
     * - 标准 DCIM/Camera、Pictures
     * - 华为/荣耀：Screenshots、AI摄影、超级微距
     * - 小米 MIUI：Gallery/cloud、ScreenRecorder
     * - OPPO/一加 ColorOS：相册截图等
     * - vivo OriginOS：vivoCamera
     * - 微信、QQ、微博、抖音等社交 App 图片目录
     */
    private void scanDirectories(List<PhotoItem> allList) {
        File externalStorage = Environment.getExternalStorageDirectory();
        if (externalStorage == null || !externalStorage.exists()) {
            Log.d(TAG, "External storage not available");
            return;
        }

        // 使用 Set 去重（标准 API 和手动拼接可能指向同一目录）
        java.util.Set<String> pathSet = new java.util.LinkedHashSet<>();
        String root = externalStorage.getAbsolutePath();

        // === 标准 Android 目录 ===
        try {
            pathSet.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath());
        } catch (Exception ignored) {}
        try {
            pathSet.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath());
        } catch (Exception ignored) {}
        try {
            pathSet.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
        } catch (Exception ignored) {}

        pathSet.add(root + "/DCIM");
        pathSet.add(root + "/DCIM/Camera");
        pathSet.add(root + "/DCIM/100ANDRO");
        pathSet.add(root + "/Pictures");
        pathSet.add(root + "/Camera");
        pathSet.add(root + "/Download");
        pathSet.add(root + "/Photo");
        pathSet.add(root + "/Photos");

        // === 华为/荣耀 HarmonyOS / EMUI ===
        pathSet.add(root + "/DCIM/Screenshots");
        pathSet.add(root + "/DCIM/ScreenRecorder");
        pathSet.add(root + "/Pictures/Screenshots");
        pathSet.add(root + "/Pictures/AI摄影");
        pathSet.add(root + "/Pictures/超级微距");
        pathSet.add(root + "/Pictures/超级夜景");
        pathSet.add(root + "/Pictures/Petal Maps");
        pathSet.add(root + "/Huawei/MMS");

        // === 小米 MIUI / HyperOS ===
        pathSet.add(root + "/DCIM/Camera/MI_Camera");
        pathSet.add(root + "/MIUI/Gallery/cloud");
        pathSet.add(root + "/DCIM/Screenshots");
        pathSet.add(root + "/MIUI/wallpaper");

        // === OPPO / 一加 ColorOS ===
        pathSet.add(root + "/DCIM/.thumbnails");  // 跳过，不扫描缩略图目录
        pathSet.add(root + "/Pictures/Saved Pictures");

        // === vivo / iQOO OriginOS ===
        pathSet.add(root + "/DCIM/vivoCamera");

        // === 三星 One UI ===
        pathSet.add(root + "/DCIM/Camera");
        pathSet.add(root + "/Pictures/Samsung");

        // === 社交 App 图片目录 ===
        pathSet.add(root + "/tencent/MicroMsg/WeiXin");
        pathSet.add(root + "/tencent/QQ_Images");
        pathSet.add(root + "/Pictures/WeiXin");
        pathSet.add(root + "/Pictures/QQ");
        pathSet.add(root + "/Pictures/知乎");
        pathSet.add(root + "/Sina/weibo");
        pathSet.add(root + "/Pictures/weibo");
        pathSet.add(root + "/Pictures/Douyin");
        pathSet.add(root + "/DCIM/Douyin");
        pathSet.add(root + "/Pictures/Bilibili");
        pathSet.add(root + "/Pictures/XiaoHongShu");
        pathSet.add(root + "/Pictures/com.ss.android.ugc.aweme");

        // === 扫描所有有效目录 ===
        // 排除缩略图目录
        pathSet.remove(root + "/DCIM/.thumbnails");

        for (String path : pathSet) {
            File dir = new File(path);
            if (dir.exists() && dir.isDirectory()) {
                Log.d(TAG, "Scanning: " + path);
                scanDir(dir, allList, 0);
            }
        }
    }

    /**
     * 递归扫描目录中的图片文件
     * 限制最大递归深度 = 5（部分国产 ROM 目录层级较深）
     * 跳过隐藏目录和缩略图缓存
     */
    private void scanDir(File dir, List<PhotoItem> allList, int depth) {
        if (depth > 5) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                // 跳过隐藏目录和缩略图缓存目录
                String dirName = file.getName();
                if (dirName.startsWith(".") || "thumbnails".equalsIgnoreCase(dirName)
                        || ".thumbnails".equals(dirName)) {
                    continue;
                }
                scanDir(file, allList, depth + 1);
            } else {
                if (!file.exists() || !file.canRead() || file.length() < 1024) continue;
                String name = file.getName().toLowerCase();
                // 跳过缩略图和临时文件
                if (name.startsWith(".")) continue;
                if (name.endsWith(".jpg") || name.endsWith(".jpeg")
                        || name.endsWith(".png") || name.endsWith(".webp")
                        || name.endsWith(".gif") || name.endsWith(".bmp")
                        || name.endsWith(".heic") || name.endsWith(".heif")) {

                    Uri uri = Uri.fromFile(file);
                    PhotoItem item = new PhotoItem(0, uri, file.lastModified());
                    allList.add(item);

                    String folderName = file.getParentFile() != null
                            ? file.getParentFile().getName() : "其他";
                    List<PhotoItem> folderList = mFolderMap.get(folderName);
                    if (folderList == null) {
                        folderList = new ArrayList<>();
                        mFolderMap.put(folderName, folderList);
                    }
                    folderList.add(item);
                }
            }
        }
    }

    private void updateBottomBar() {
        int count = mSelectedPhotos.size();
        mBtnConfirm.setText("完成(" + count + "/" + mMaxSelectable + ")");
        mBtnConfirm.setEnabled(count > 0);
        if (count > 0) {
            mBtnConfirm.setBackgroundColor(0xFF1AAD19); // 微信绿色
        } else {
            mBtnConfirm.setBackgroundColor(0xFF555555); // 灰色
        }
        mBtnPreview.setEnabled(count > 0);
        mBtnPreview.setTextColor(count > 0 ? 0xFFFFFFFF : 0xFF666666);
    }

    private void confirmSelection() {
        String[] uris = new String[mSelectedPhotos.size()];
        for (int i = 0; i < mSelectedPhotos.size(); i++) {
            uris[i] = mSelectedPhotos.get(i).uri.toString();
        }
        Intent result = new Intent();
        result.putExtra(EXTRA_SELECTED_URIS, uris);
        setResult(RESULT_OK, result);
        finish();
    }

    private void onPhotoClick(int position) {
        PhotoItem item = mCurrentPhotos.get(position);
        if (item.isSelected) {
            item.isSelected = false;
            mSelectedPhotos.remove(item);
            for (int i = 0; i < mSelectedPhotos.size(); i++) {
                mSelectedPhotos.get(i).selectIndex = i + 1;
            }
        } else {
            if (mSelectedPhotos.size() >= mMaxSelectable) {
                Toast.makeText(this, "最多选择" + mMaxSelectable + "张", Toast.LENGTH_SHORT).show();
                return;
            }
            item.isSelected = true;
            item.selectIndex = mSelectedPhotos.size() + 1;
            mSelectedPhotos.add(item);
        }
        mAdapter.notifyDataSetChanged();
        updateBottomBar();
    }

    private void onFolderClick(int position) {
        FolderItem folder = mFolders.get(position);
        mTvTitle.setText(folder.name);
        toggleFolderList();

        List<PhotoItem> list = mFolderMap.get(folder.name);
        if (list == null) list = new ArrayList<>();
        mCurrentPhotos = list;
        mAdapter.notifyDataSetChanged();
        mRecyclerView.scrollToPosition(0);
    }

    private static class PhotoItem {
        long id;
        Uri uri;
        boolean isSelected;
        int selectIndex;
        long lastModified;

        PhotoItem(long id, Uri uri, long lastModified) {
            this.id = id;
            this.uri = uri;
            this.lastModified = lastModified;
        }
    }

    private static class FolderItem {
        String name;
        int count;
        Uri coverUri;

        FolderItem(String name, int count, Uri coverUri) {
            this.name = name;
            this.count = count;
            this.coverUri = coverUri;
        }
    }

    private class PhotoAdapter extends RecyclerView.Adapter<PhotoViewHolder> {

        @NonNull
        @Override
        public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_photo_grid, parent, false);
            return new PhotoViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
            PhotoItem item = mCurrentPhotos.get(position);
            holder.bind(item, position);
        }

        @Override
        public int getItemCount() {
            return mCurrentPhotos.size();
        }
    }

    private class PhotoViewHolder extends RecyclerView.ViewHolder {
        ImageView ivPhoto;
        View vSelectBg;
        TextView tvSelectIndex;

        PhotoViewHolder(View itemView) {
            super(itemView);
            ivPhoto = itemView.findViewById(R.id.iv_photo);
            vSelectBg = itemView.findViewById(R.id.v_select_bg);
            tvSelectIndex = itemView.findViewById(R.id.tv_select_index);
        }

        void bind(PhotoItem item, int position) {
            String cacheKey = item.uri != null ? item.uri.toString() : String.valueOf(item.id);
            ivPhoto.setTag(cacheKey);
            loadThumbnailAsync(item, ivPhoto, cacheKey);

            if (item.isSelected) {
                vSelectBg.setBackgroundResource(R.drawable.bg_circle_selected);
                tvSelectIndex.setVisibility(View.VISIBLE);
                tvSelectIndex.setText(String.valueOf(item.selectIndex));
                ivPhoto.setAlpha(0.7f);
            } else {
                vSelectBg.setBackgroundResource(R.drawable.bg_circle_unselected);
                tvSelectIndex.setVisibility(View.GONE);
                ivPhoto.setAlpha(1.0f);
            }

            itemView.setOnClickListener(v -> onPhotoClick(position));
        }
    }

    /**
     * 异步加载缩略图，避免主线程 IO 导致滑动掉帧
     * 策略：LruCache 内存缓存 → 后台线程解码 → 主线程刷新
     */
    private void loadThumbnailAsync(PhotoItem item, ImageView imageView, String cacheKey) {
        // 1. 内存缓存命中，直接显示
        Bitmap cached = mThumbnailCache.get(cacheKey);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        // 2. 设置占位图，防止复用时显示旧图
        imageView.setImageResource(android.R.color.darker_gray);

        // 3. 提交到后台线程解码
        mImageLoadExecutor.execute(() -> {
            Bitmap bmp = decodeThumbnailBitmap(item);
            if (bmp != null) {
                mThumbnailCache.put(cacheKey, bmp);
            }

            final Bitmap result = bmp;
            imageView.post(() -> {
                // Tag 校验：防止 RecyclerView 复用导致图片错位
                Object currentTag = imageView.getTag();
                if (currentTag == null || !currentTag.equals(cacheKey)) {
                    return;
                }
                if (result != null) {
                    imageView.setImageBitmap(result);
                } else {
                    imageView.setImageResource(android.R.color.darker_gray);
                }
            });
        });
    }

    /**
     * 在后台线程中执行实际的 Bitmap 解码
     */
    private Bitmap decodeThumbnailBitmap(PhotoItem item) {
        // 1. MediaStore 系统缩略图
        if (item.id > 0) {
            try {
                Bitmap thumbnail = MediaStore.Images.Thumbnails.getThumbnail(
                        getContentResolver(), item.id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                if (thumbnail != null) return thumbnail;
            } catch (Exception e) {
                Log.w(TAG, "getThumbnail failed: " + e.getMessage());
            }
        }

        // 2. Content URI 采样解码
        if (item.uri != null && "content".equals(item.uri.getScheme())) {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                java.io.InputStream is = getContentResolver().openInputStream(item.uri);
                if (is != null) {
                    BitmapFactory.decodeStream(is, null, opts);
                    is.close();
                    int sample = 1;
                    while (opts.outWidth / sample > 300 || opts.outHeight / sample > 300) {
                        sample *= 2;
                    }
                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = sample;
                    is = getContentResolver().openInputStream(item.uri);
                    if (is != null) {
                        Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                        is.close();
                        if (bmp != null) return bmp;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "decodeStream failed: " + item.uri);
            }
        }

        // 3. 文件路径采样解码
        String path = item.uri != null ? item.uri.getPath() : null;
        if (path != null) {
            File file = new File(path);
            if (file.exists() && file.canRead()) {
                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, opts);
                    int sample = 1;
                    while (opts.outWidth / sample > 300 || opts.outHeight / sample > 300) {
                        sample *= 2;
                    }
                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = sample;
                    Bitmap bmp = BitmapFactory.decodeFile(path, opts);
                    if (bmp != null) return bmp;
                } catch (Exception e) {
                    Log.w(TAG, "decodeFile failed: " + path);
                }
            }
        }

        return null;
    }

    private class FolderAdapter extends RecyclerView.Adapter<FolderViewHolder> {

        @NonNull
        @Override
        public FolderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_album_folder, parent, false);
            return new FolderViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull FolderViewHolder holder, int position) {
            FolderItem item = mFolders.get(position);
            holder.tvName.setText(item.name);
            holder.tvCount.setText(String.valueOf(item.count));

            if (item.coverUri != null) {
                try {
                    holder.ivCover.setImageURI(item.coverUri);
                } catch (Exception e) {
                    holder.ivCover.setImageResource(android.R.color.darker_gray);
                }
            } else {
                holder.ivCover.setImageResource(android.R.color.darker_gray);
            }

            holder.itemView.setOnClickListener(v -> onFolderClick(position));
        }

        @Override
        public int getItemCount() {
            return mFolders.size();
        }
    }

    private static class FolderViewHolder extends RecyclerView.ViewHolder {
        ImageView ivCover;
        TextView tvName;
        TextView tvCount;

        FolderViewHolder(View itemView) {
            super(itemView);
            ivCover = itemView.findViewById(R.id.iv_folder_cover);
            tvName = itemView.findViewById(R.id.tv_folder_name);
            tvCount = itemView.findViewById(R.id.tv_folder_count);
        }
    }
}
