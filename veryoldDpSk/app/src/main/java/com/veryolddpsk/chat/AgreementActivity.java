package com.veryolddpsk.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * 首次启动的用户协议与隐私政策页。
 *
 * 只能点「同意并继续」进入应用，点「不同意」直接退出。
 * 条款优先使用网页版（可随时更新），拉取失败则退回内置文本。
 */
public class AgreementActivity extends Activity {

    private final Handler handler = new Handler();

    private TextView statusView;
    private TextView bodyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_agreement);

            statusView = (TextView) findViewById(R.id.agreementStatus);
            bodyView = (TextView) findViewById(R.id.agreementBody);

            bodyView.setText(BuiltinText.AGREEMENT + "\n\n\n" + BuiltinText.PRIVACY);
            statusView.setText(R.string.agreement_loading);

            Button accept = (Button) findViewById(R.id.acceptButton);
            accept.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Store.setAgreementAccepted(AgreementActivity.this, true);
                    try {
                        startActivity(new Intent(AgreementActivity.this, MainActivity.class));
                    } catch (Throwable ignored) {
                    }
                    finish();
                }
            });

            Button decline = (Button) findViewById(R.id.declineButton);
            decline.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Store.setAgreementAccepted(AgreementActivity.this, false);
                    // 主界面已经 finish，这里结束后整个应用即退出
                    finish();
                }
            });

            Button browser = (Button) findViewById(R.id.browserButton);
            browser.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openInBrowser();
                }
            });

            loadOnlineText();
        } catch (Throwable t) {
            // 协议页本身出错时不允许把用户卡住：直接按内置文本放行
            try {
                Store.setAgreementAccepted(this, true);
                startActivity(new Intent(this, MainActivity.class));
            } catch (Throwable ignored) {
            }
            finish();
        }
    }

    /** 返回键不能绕过协议页。 */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void openInBrowser() {
        final String[] labels = new String[]{
                getString(R.string.about_agreement),
                getString(R.string.about_privacy),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.agreement_in_browser)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        Util.openUrl(AgreementActivity.this,
                                which == 0 ? App.AGREEMENT_URL : App.PRIVACY_URL);
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void loadOnlineText() {
        Thread worker = new Thread(new Runnable() {
            public void run() {
                String agreement = null;
                String privacy = null;
                try {
                    agreement = Util.htmlToText(Net.get(App.AGREEMENT_URL));
                } catch (Throwable ignored) {
                }
                try {
                    privacy = Util.htmlToText(Net.get(App.PRIVACY_URL));
                } catch (Throwable ignored) {
                }

                final String a = usable(agreement);
                final String p = usable(privacy);
                handler.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (a == null && p == null) {
                            statusView.setText(R.string.agreement_offline);
                            return;
                        }
                        StringBuilder b = new StringBuilder();
                        b.append(a == null ? BuiltinText.AGREEMENT : a);
                        b.append("\n\n\n");
                        b.append(p == null ? BuiltinText.PRIVACY : p);
                        bodyView.setText(b.toString());
                        statusView.setText("");
                    }
                });
            }
        }, "veryold-agreement");
        worker.setDaemon(true);
        worker.start();
    }

    /** 在线内容过短时视为无效，避免被错误页面覆盖。 */
    private static String usable(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() < 200 ? null : trimmed;
    }
}
