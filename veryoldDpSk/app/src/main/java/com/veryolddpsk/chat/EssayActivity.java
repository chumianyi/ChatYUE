package com.veryolddpsk.chat;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一起作文：可选风格、语言与字数，只展示正文。
 */
public class EssayActivity extends Activity {

    private static final long REFRESH_INTERVAL_MS = 90L;
    private static final int DEFAULT_WORDS = 800;

    private final Handler handler = new Handler();
    private final StringBuilder answer = new StringBuilder();

    private Net.Cancel cancel;
    private boolean streaming;
    private long lastRefresh;

    private EditText topicInput;
    private Spinner styleSpinner;
    private Spinner languageSpinner;
    private SeekBar wordSlider;
    private TextView wordLabel;
    private TextView resultCountLabel;
    private TextView statusView;
    private TextView resultText;
    private Button writeButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_essay);

            topicInput = (EditText) findViewById(R.id.topicInput);
            styleSpinner = (Spinner) findViewById(R.id.styleSpinner);
            languageSpinner = (Spinner) findViewById(R.id.languageSpinner);
            wordSlider = (SeekBar) findViewById(R.id.wordCountSlider);
            wordLabel = (TextView) findViewById(R.id.wordCountLabel);
            resultCountLabel = (TextView) findViewById(R.id.resultCountLabel);
            statusView = (TextView) findViewById(R.id.essayStatus);
            resultText = (TextView) findViewById(R.id.resultText);
            writeButton = (Button) findViewById(R.id.writeButton);

            findViewById(R.id.backButton).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    finish();
                }
            });

            ArrayAdapter<String> styleAdapter = new ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_item, Prompts.ESSAY_STYLES);
            styleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            styleSpinner.setAdapter(styleAdapter);
            styleSpinner.setSelection(0);

            ArrayAdapter<String> languageAdapter = new ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_item, Prompts.ESSAY_LANGUAGES);
            languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            languageSpinner.setAdapter(languageAdapter);
            // 默认中文
            languageSpinner.setSelection(0);

            int span = Prompts.ESSAY_MAX_WORDS - Prompts.ESSAY_MIN_WORDS;
            wordSlider.setMax(span);
            wordSlider.setProgress(DEFAULT_WORDS - Prompts.ESSAY_MIN_WORDS);
            wordSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    refreshWordLabel();
                }

                public void onStartTrackingTouch(SeekBar bar) {
                }

                public void onStopTrackingTouch(SeekBar bar) {
                }
            });
            refreshWordLabel();

            writeButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    startWriting();
                }
            });
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

    private int targetWords() {
        try {
            return Prompts.ESSAY_MIN_WORDS + wordSlider.getProgress();
        } catch (Throwable t) {
            return DEFAULT_WORDS;
        }
    }

    private void refreshWordLabel() {
        wordLabel.setText(targetWords() + " 字");
    }

    private void setStreaming(boolean running) {
        streaming = running;
        writeButton.setEnabled(!running);
        wordSlider.setEnabled(!running);
        styleSpinner.setEnabled(!running);
        languageSpinner.setEnabled(!running);
        statusView.setText(running ? getString(R.string.generating) : "");
    }

    private void startWriting() {
        if (streaming) {
            if (cancel != null) {
                cancel.cancel();
            }
            setStreaming(false);
            return;
        }
        final String topic = topicInput.getText().toString().trim();
        if (topic.length() == 0) {
            Util.toast(this, R.string.essay_empty_topic);
            return;
        }
        int styleIndex = clamp(styleSpinner.getSelectedItemPosition(), Prompts.ESSAY_STYLES.length);
        int languageIndex = clamp(languageSpinner.getSelectedItemPosition(),
                Prompts.ESSAY_LANGUAGES.length);
        String style = Prompts.ESSAY_STYLES[styleIndex];
        String language = Prompts.ESSAY_LANGUAGES[languageIndex];
        int words = targetWords();

        answer.setLength(0);
        answer.append("");
        resultText.setText(R.string.generating);
        resultCountLabel.setText("");
        lastRefresh = 0L;
        setStreaming(true);

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(message("system", Prompts.essaySystem(style, language, words)));
        messages.add(message("user", topic));

        cancel = Net.stream(messages, new Net.Sink() {
            public void onThinking(String delta) {
                // 需求：作文不展示思考内容
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
                        String text = fullContent == null ? "" : fullContent.trim();
                        resultText.setText(text);
                        resultCountLabel.setText(getString(R.string.essay_count,
                                Integer.valueOf(Util.countWords(text))));
                    }
                });
            }

            public void onError(final String text) {
                handler.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        setStreaming(false);
                        resultText.setText(text);
                    }
                });
            }
        });
    }

    private static int clamp(int index, int length) {
        if (index < 0 || index >= length) {
            return 0;
        }
        return index;
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
