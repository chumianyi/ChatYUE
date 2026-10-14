package com.veryolddpsk.chat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 简易 txt 选择页。
 *
 * 老系统没有系统文件选择器，这里直接扫描外部存储中的 .txt 文件供选择，
 * 读取成功后把内容回传给主界面。
 */
public class FilePickerActivity extends Activity {

    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_FILE_CONTENT = "file_content";

    private static final int MAX_DEPTH = 4;
    private static final int MAX_FILES = 300;

    private final Handler handler = new Handler();
    private final ArrayList<File> files = new ArrayList<File>();

    private ListView listView;
    private TextView emptyHint;
    private TextView statusView;
    private FileAdapter adapter;
    private volatile boolean stopped;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_file_picker);

            listView = (ListView) findViewById(R.id.fileList);
            emptyHint = (TextView) findViewById(R.id.emptyHint);
            statusView = (TextView) findViewById(R.id.fileStatus);

            findViewById(R.id.backButton).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    finish();
                }
            });

            adapter = new FileAdapter();
            listView.setAdapter(adapter);
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    choose(position);
                }
            });

            scanFiles();
        } catch (Throwable t) {
            Util.toastLong(this, Util.humanize(t));
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        super.onDestroy();
    }

    // ==================== 扫描 ====================

    private void scanFiles() {
        statusView.setText(R.string.loading);
        Thread worker = new Thread(new Runnable() {
            public void run() {
                final ArrayList<File> found = new ArrayList<File>();
                try {
                    File root = Environment.getExternalStorageDirectory();
                    if (root != null && root.isDirectory()) {
                        walk(root, 0, found);
                    }
                } catch (Throwable ignored) {
                }
                Collections.sort(found, new Comparator<File>() {
                    public int compare(File a, File b) {
                        if (a.lastModified() == b.lastModified()) {
                            return a.getName().compareToIgnoreCase(b.getName());
                        }
                        return a.lastModified() > b.lastModified() ? -1 : 1;
                    }
                });
                handler.post(new Runnable() {
                    public void run() {
                        if (stopped || isFinishing()) {
                            return;
                        }
                        statusView.setText("");
                        files.clear();
                        files.addAll(found);
                        adapter.notifyDataSetChanged();
                        emptyHint.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        }, "veryold-files");
        worker.setDaemon(true);
        worker.start();
    }

    private void walk(File dir, int depth, List<File> out) {
        if (depth > MAX_DEPTH || out.size() >= MAX_FILES || stopped) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (int k = 0; k < children.length && out.size() < MAX_FILES; k++) {
            File child = children[k];
            if (child == null) {
                continue;
            }
            String name = child.getName();
            if (name.startsWith(".")) {
                continue;
            }
            if (child.isDirectory()) {
                walk(child, depth + 1, out);
                continue;
            }
            if (name.toLowerCase().endsWith(".txt") && child.length() > 0
                    && child.length() <= Util.MAX_TEXT_BYTES) {
                out.add(child);
            }
        }
    }

    // ==================== 选择 ====================

    private void choose(int position) {
        if (position < 0 || position >= files.size()) {
            return;
        }
        final File file = files.get(position);
        statusView.setText(R.string.file_reading);
        Thread worker = new Thread(new Runnable() {
            public void run() {
                final String content = Util.readTextFile(file);
                handler.post(new Runnable() {
                    public void run() {
                        if (stopped || isFinishing()) {
                            return;
                        }
                        statusView.setText("");
                        if (content == null) {
                            Util.toast(FilePickerActivity.this, R.string.file_too_large);
                            return;
                        }
                        if (content.length() == 0) {
                            Util.toast(FilePickerActivity.this, R.string.file_read_failed);
                            return;
                        }
                        try {
                            Intent data = new Intent();
                            data.putExtra(EXTRA_FILE_NAME, file.getName());
                            data.putExtra(EXTRA_FILE_PATH, file.getAbsolutePath());
                            data.putExtra(EXTRA_FILE_CONTENT, content);
                            setResult(RESULT_OK, data);
                        } catch (Throwable ignored) {
                        }
                        finish();
                    }
                });
            }
        }, "veryold-file-read");
        worker.setDaemon(true);
        worker.start();
    }

    private static String readableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return (bytes / 1024L) + " KB";
        }
        return (bytes / (1024L * 1024L)) + " MB";
    }

    // ==================== 适配器 ====================

    private class FileAdapter extends BaseAdapter {
        public int getCount() {
            return files.size();
        }

        public Object getItem(int position) {
            return files.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.item_file, parent, false);
            }
            File file = files.get(position);
            TextView name = (TextView) view.findViewById(R.id.fileName);
            TextView meta = (TextView) view.findViewById(R.id.fileMeta);
            name.setText(file.getName());
            String parentPath = file.getParent();
            meta.setText(readableSize(file.length()) + "    " + (parentPath == null ? "" : parentPath));
            return view;
        }
    }
}
