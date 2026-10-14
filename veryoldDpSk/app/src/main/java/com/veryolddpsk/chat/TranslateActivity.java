package com.veryolddpsk.chat;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能翻译：5 组互译，只展示译文，思考过程一律不显示。
 */
public class TranslateActivity extends Activity {

    /** 界面刷新节流间隔，避免老设备被高频 setText 拖卡。 */
    private static final long REFRESH_INTERVAL_MS = 90L;

    private final Handler handler = new Handler();
    private final StringBuilder answer = new StringBuilder();

    private Net.Cancel cancel;
    private boolean leftToRight = true;
    private int pairIndex = 0;
    private boolean streaming;
    private long lastRefresh;

    private Spinner pairSpinner;
    private TextView directionLabel;
    private TextView statusView;
    private TextView resultText;
    private EditText sourceInput;
    private Button translateButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_translate);

            pairSpinner = (Spinner) findViewById(R.id.pairSpinner);
            directionLabel = (TextView) findViewById(R.id.directionLabel);
            statusView = (TextView) findViewById(R.id.translateStatus);
            resultText = (TextView) findViewById(R.id.resultText);
            sourceInput = (EditText) findViewById(R.id.sourceInput);
            translateButton = (Button) findViewById(R.id.translateButton);

            findViewById(R.id.backButton).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    finish();
                }
            });

            String[] labels = new String[Prompts.PAIRS.length];
            for (int k = 0; k < Prompts.PAIRS.length; k++) {
                labels[k] = Prompts.PAIRS[k].label;
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_item, labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            pairSpinner.setAdapter(adapter);
            pairSpinner.setSelection(0);

            findViewById(R.id.swapButton).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    leftToRight = !leftToRight;
                    refreshDirection();
                }
            });

            translateButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    startTranslate();
                }
            });

            refreshDirection();
        } catch (Throwable t) {
            Util.toastLong(this, Util.humanize(t));
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (cancel != null) {
            cancel.cancel();
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void refreshDirection() {
        int index = pairSpinner.getSelectedItemPosition();
        if (index < 0 || index >= Prompts.PAIRS.length) {
            index = 0;
        }
        pairIndex = index;
        Prompts.Pair pair = Prompts.PAIRS[index];
        String from = leftToRight ? pair.left : pair.right;
        String to = leftToRight ? pair.right : pair.left;
        directionLabel.setText(from + "  →  " + to);
    }

    private void setStreaming(boolean running) {
        streaming = running;
        translateButton.setEnabled(!running);
        statusView.setText(running ? getString(R.string.generating) : "");
    }

    private void startTranslate() {
        if (streaming) {
            if (cancel != null) {
                cancel.cancel();
            }
            setStreaming(false);
            return;
        }
        String source = sourceInput.getText().toString().trim();
        if (source.length() == 0) {
            Util.toast(this, R.string.empty_input);
            return;
        }
        refreshDirection();
        Prompts.Pair pair = Prompts.PAIRS[pairIndex];
        String from = leftToRight ? pair.left : pair.right;
        String to = leftToRight ? pair.right : pair.left;

        answer.setLength(0);
        answer.append("");
        resultText.setText(R.string.generating);
        lastRefresh = 0L;
        setStreaming(true);

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(message("system", Prompts.translationSystem(from, to)));
        messages.add(message("user", source));

        cancel = Net.stream(messages, new Net.Sink() {
            public void onThinking(String delta) {
                // 需求：翻译不展示思考内容，只在界面上出现译文
            }

            public void onContent(final String delta) {
                answer.append(delta);
                long now = System.currentTimeMillis();
                if (now - lastRefresh < REFRESH_INTERVAL_MS) {
                    return;
                }
                lastRefresh = now;
                handler.post(new Runnable() {
                    public void run() {
                        if (!isFinishing()) {
                            resultText.setText(answer.toString());
                        }
                    }
                });
            }

            public void onFinish(String fullThinking, final String fullContent) {
                handler.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        setStreaming(false);
                        resultText.setText(fullContent == null ? "" : fullContent.trim());
                    }
                });
            }

            public void onError(final String message) {
                handler.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        setStreaming(false);
                        resultText.setText(message);
                    }
                });
            }
        });
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
