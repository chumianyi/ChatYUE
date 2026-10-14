package com.veryolddpsk.chat;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 自建相册页：列出设备中的全部图片供选择。
 *
 * Android 6.0 起需要运行时权限，这里用反射申请，避免直接调用 API 23 才出现的接口，
 * 从而保证在 Android 1.5~5.1 上也不会因为缺方法而崩溃。
 */
public class ImagePickerActivity extends Activity {

    public static final String EXTRA_IMAGE_NAME = "image_name";

    private static final int REQUEST_PERMISSION = 1001;
    private static final int THUMB_TARGET_PX = 180;
    private static final int MAX_THUMB_CACHE = 150;

    private final Handler handler = new Handler();
    private final ArrayList<Entry> entries = new ArrayList<Entry>();
    private final HashMap<String, Bitmap> thumbs = new HashMap<String, Bitmap>();

    private GridView grid;
    private View emptyPanel;
    private TextView emptyText;
    private Button grantButton;
    private volatile boolean stopped;

    private GridAdapter adapter;

    private static final class Entry {
        Uri uri;
        String path;
        String name;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_image_picker);

            grid = (GridView) findViewById(R.id.imageGrid);
            emptyPanel = findViewById(R.id.emptyPanel);
            emptyText = (TextView) findViewById(R.id.emptyText);
            grantButton = (Button) findViewById(R.id.grantButton);

            findViewById(R.id.backButton).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    finish();
                }
            });

            grantButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    requestStoragePermission();
                }
            });

            adapter = new GridAdapter();
            grid.setAdapter(adapter);
            grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    pick(position);
                }
            });

            if (hasStoragePermission()) {
                loadImages();
            } else {
                showEmpty(getString(R.string.permission_denied), true);
            }
        } catch (Throwable t) {
            Util.toastLong(this, Util.humanize(t));
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        thumbs.clear();
        super.onDestroy();
    }

    // ==================== 权限 ====================

    private static int sdkInt() {
        try {
            return Integer.parseInt(android.os.Build.VERSION.SDK);
        } catch (Throwable t) {
            return 1;
        }
    }

    private boolean hasStoragePermission() {
        if (sdkInt() < 16) {
            // Android 4.1 之前外部存储完全开放，不需要申请
            return true;
        }
        try {
            return checkCallingOrSelfPermission("android.permission.READ_EXTERNAL_STORAGE")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 通过反射申请权限，避免直接引用 API 23 的方法。
     *
     * 授权结果也不通过 onRequestPermissionsResult 回调（那个方法同样只在 API 23 存在），
     * 而是主动轮询权限状态，这样代码里不会出现任何高于 API 3 的符号。
     */
    private void requestStoragePermission() {
        if (sdkInt() < 23) {
            loadImages();
            return;
        }
        boolean requested;
        try {
            java.lang.reflect.Method method = Activity.class.getMethod(
                    "requestPermissions", new Class[]{String[].class, int.class});
            method.invoke(this, new Object[]{
                    new String[]{"android.permission.READ_EXTERNAL_STORAGE"},
                    Integer.valueOf(REQUEST_PERMISSION)});
            requested = true;
        } catch (Throwable t) {
            requested = false;
        }
        if (!requested) {
            Util.toast(this, R.string.permission_denied);
            return;
        }
        watchPermission(0);
    }

    /** 每 500ms 检查一次授权结果，最多等 30 秒。 */
    private void watchPermission(final int attempt) {
        if (stopped || isFinishing()) {
            return;
        }
        if (hasStoragePermission()) {
            loadImages();
            return;
        }
        if (attempt >= 60) {
            showEmpty(getString(R.string.permission_denied), true);
            return;
        }
        handler.postDelayed(new Runnable() {
            public void run() {
                watchPermission(attempt + 1);
            }
        }, 500L);
    }

    // ==================== 读取相册 ====================

    private void loadImages() {
        showEmpty(getString(R.string.loading), false);
        Thread worker = new Thread(new Runnable() {
            public void run() {
                final ArrayList<Entry> found = new ArrayList<Entry>();
                try {
                    scan(found, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                } catch (Throwable ignored) {
                }
                try {
                    scan(found, MediaStore.Images.Media.INTERNAL_CONTENT_URI);
                } catch (Throwable ignored) {
                }
                handler.post(new Runnable() {
                    public void run() {
                        if (stopped || isFinishing()) {
                            return;
                        }
                        entries.clear();
                        entries.addAll(found);
                        adapter.notifyDataSetChanged();
                        if (entries.isEmpty()) {
                            showEmpty(getString(R.string.pick_image_empty), false);
                        } else {
                            hideEmpty();
                            startDecoder();
                        }
                    }
                });
            }
        }, "veryold-gallery");
        worker.setDaemon(true);
        worker.start();
    }

    private void scan(List<Entry> out, Uri base) {
        ContentResolver resolver = getContentResolver();
        Cursor cursor = null;
        try {
            cursor = resolver.query(base, new String[]{"_id", "_data"}, null, null, "_id DESC");
            if (cursor == null) {
                return;
            }
            int idIndex = cursor.getColumnIndex("_id");
            int dataIndex = cursor.getColumnIndex("_data");
            while (cursor.moveToNext()) {
                String path = dataIndex >= 0 ? cursor.getString(dataIndex) : null;
                if (path == null || path.length() == 0) {
                    continue;
                }
                long rowId = idIndex >= 0 ? cursor.getLong(idIndex) : 0L;
                Entry entry = new Entry();
                entry.uri = ContentUris.withAppendedId(base, rowId);
                entry.path = path;
                entry.name = new File(path).getName();
                out.add(entry);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void startDecoder() {
        Thread worker = new Thread(new Runnable() {
            public void run() {
                int sinceRefresh = 0;
                int cached = 0;
                for (int k = 0; k < entries.size() && !stopped && cached < MAX_THUMB_CACHE; k++) {
                    Entry entry = entries.get(k);
                    if (thumbs.containsKey(entry.path)) {
                        continue;
                    }
                    Bitmap bitmap = decodeThumb(entry.path);
                    if (bitmap == null) {
                        continue;
                    }
                    cached++;
                    thumbs.put(entry.path, bitmap);
                    if (++sinceRefresh >= 4) {
                        sinceRefresh = 0;
                        refreshGrid();
                    }
                }
                refreshGrid();
            }
        }, "veryold-thumb");
        worker.setDaemon(true);
        worker.start();
    }

    private Bitmap decodeThumb(String path) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int width = bounds.outWidth;
            int height = bounds.outHeight;
            if (width <= 0 || height <= 0) {
                return null;
            }
            int sample = 1;
            while (width / (sample * 2) >= THUMB_TARGET_PX
                    && height / (sample * 2) >= THUMB_TARGET_PX) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, options);
        } catch (Throwable t) {
            return null;
        }
    }

    private void refreshGrid() {
        handler.post(new Runnable() {
            public void run() {
                if (!stopped && !isFinishing()) {
                    adapter.notifyDataSetChanged();
                }
            }
        });
    }

    private void pick(int position) {
        if (position < 0 || position >= entries.size()) {
            return;
        }
        Entry entry = entries.get(position);
        try {
            Intent data = new Intent();
            data.setData(entry.uri);
            data.putExtra(EXTRA_IMAGE_NAME, entry.name);
            setResult(RESULT_OK, data);
        } catch (Throwable t) {
            Util.toast(this, R.string.file_read_failed);
        }
        finish();
    }

    private void showEmpty(String message, boolean withButton) {
        grid.setVisibility(View.GONE);
        emptyPanel.setVisibility(View.VISIBLE);
        emptyText.setText(message);
        grantButton.setVisibility(withButton ? View.VISIBLE : View.GONE);
    }

    private void hideEmpty() {
        emptyPanel.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);
    }

    // ==================== 适配器 ====================

    private class GridAdapter extends BaseAdapter {
        public int getCount() {
            return entries.size();
        }

        public Object getItem(int position) {
            return entries.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.item_image, parent, false);
            }
            ImageView thumb = (ImageView) view.findViewById(R.id.thumbnail);
            Entry entry = entries.get(position);
            Bitmap bitmap = thumbs.get(entry.path);
            if (bitmap != null) {
                thumb.setImageBitmap(bitmap);
            } else {
                thumb.setImageBitmap(null);
            }
            return view;
        }
    }
}
