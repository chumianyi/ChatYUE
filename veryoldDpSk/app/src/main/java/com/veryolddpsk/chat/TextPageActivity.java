package com.veryolddpsk.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** 通用文本页：关于 / 更新日志 / 检查更新。 */
public class TextPageActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_BODY = "body";

    public static final int MODE_TEXT = 0;
    public static final int MODE_CHANGELOG = 1;
    public static final int MODE_UPDATE = 2;
    public static final int MODE_ABOUT = 3;

    private final Handler handler = new Handler();

    private TextView statusView;
    private TextView bodyView;
    private Button actionView;
    private int mode = MODE_TEXT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_text_page);

            statusView = (TextView) findViewById(R.id.pageStatus);
            bodyView = (TextView) findViewById(R.id.pageBody);
            actionView = (Button) findViewById(R.id.pageAction);

            findViewById(R.id.backButton).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    finish();
                }
            });

            TextView title = (TextView) findViewById(R.id.pageTitle);
            String requested = getIntent().getStringExtra(EXTRA_TITLE);
            title.setText(requested == null ? getString(R.string.about_title) : requested);

            mode = getIntent().getIntExtra(EXTRA_MODE, MODE_TEXT);
            if (savedInstanceState == null) {
                start();
            }
        } catch (Throwable t) {
            Util.toastLong(this, Util.humanize(t));
            finish();
        }
    }

    private void start() {
        if (mode == MODE_TEXT) {
            String body = getIntent().getStringExtra(EXTRA_BODY);
            bodyView.setText(body == null ? "" : body);
            return;
        }
        if (mode == MODE_ABOUT) {
            showAbout();
            return;
        }
        if (mode == MODE_CHANGELOG) {
            bodyView.setText(R.string.changelog_loading);
            loadChangelog();
            return;
        }
        statusView.setText(R.string.update_checking);
        bodyView.setText(R.string.update_checking);
        checkUpdate();
    }

    // ==================== 关于 ====================

    private void showAbout() {
        bodyView.setText(R.string.about_body);
        actionView.setVisibility(View.VISIBLE);
        actionView.setText(R.string.about_project);
        actionView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showLinks();
            }
        });
    }

    private void showLinks() {
        final String[] labels = new String[]{
                getString(R.string.about_project),
                getString(R.string.about_original),
                getString(R.string.about_license),
                getString(R.string.about_agreement),
                getString(R.string.about_privacy),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        switch (which) {
                            case 0:
                                Util.openUrl(TextPageActivity.this, App.PROJECT_URL);
                                break;
                            case 1:
                                Util.openUrl(TextPageActivity.this, App.ORIGINAL_URL);
                                break;
                            case 2:
                                Util.openUrl(TextPageActivity.this, App.LICENSE_URL);
                                break;
                            case 3:
                                Util.openUrl(TextPageActivity.this, App.AGREEMENT_URL);
                                break;
                            default:
                                Util.openUrl(TextPageActivity.this, App.PRIVACY_URL);
                                break;
                        }
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    // ==================== 更新日志 ====================

    private void loadChangelog() {
        Thread worker = new Thread(new Runnable() {
            public void run() {
                String text = null;
                String error = null;
                try {
                    Remote.Info info = Remote.fetch();
                    text = Remote.formatChangelog(info);
                } catch (Throwable t) {
                    error = Util.humanize(t);
                }
                final String success = text;
                final String failure = error;
                handler.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        statusView.setText("");
                        if (success != null && success.length() > 0) {
                            bodyView.setText(success);
                        } else if (failure != null) {
                            bodyView.setText(getString(R.string.changelog_failed, failure));
                        } else {
                            bodyView.setText(R.string.changelog_empty);
                        }
                    }
                });
            }
        }, "veryold-changelog");
        worker.setDaemon(true);
        worker.start();
    }

    // ==================== 检查更新 ====================

    private void checkUpdate() {
        Thread worker = new Thread(new Runnable() {
            public void run() {
                Remote.Info info = null;
                String error = null;
                try {
                    info = Remote.fetch();
                } catch (Throwable t) {
                    error = Util.humanize(t);
                }
                final Remote.Info fetched = info;
                final String failure = error;
                handler.post(new Runnable() {
                    public void run() {
                        if (!isFinishing()) {
                            handleUpdateResult(fetched, failure);
                        }
                    }
                });
            }
        }, "veryold-update");
        worker.setDaemon(true);
        worker.start();
    }

    private void handleUpdateResult(Remote.Info info, String error) {
        statusView.setText("");
        if (info == null) {
            bodyView.setText(getString(R.string.update_failed,
                    error == null ? getString(R.string.changelog_empty) : error));
            return;
        }
        final String downloadUrl = info.downloadUrl.length() > 0
                ? info.downloadUrl : App.PROJECT_URL;
        if (info.versionCode > versionCode()) {
            bodyView.setText(getString(R.string.update_found, info.versionName, App.VERSION_NAME));
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.update_title))
                    .setMessage(getString(R.string.update_found, info.versionName, App.VERSION_NAME))
                    .setPositiveButton(R.string.update_now, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            Util.openUrl(TextPageActivity.this, downloadUrl);
                        }
                    })
                    .setNegativeButton(R.string.update_later, null)
                    .show();
        } else {
            bodyView.setText(getString(R.string.update_latest, App.VERSION_NAME));
        }
    }

    private int versionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }
}
