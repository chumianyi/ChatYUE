package com.veryolddpsk.chat;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** 骨架 Activity，用于验证 API 3 构建链路。 */
public class MainActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        view.setText(R.string.app_name);
        setContentView(view);
    }
}
