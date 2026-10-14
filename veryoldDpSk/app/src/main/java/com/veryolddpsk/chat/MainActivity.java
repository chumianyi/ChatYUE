package com.veryolddpsk.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主界面。
 *
 * 功能与 DeepSeekOld 保持一致：固定接口与模型、仅思考模式、流式输出、
 * 上下文百分比与 85% 自动压缩、侧边栏工具与按时间分组的对话记录、附件。
 * 全部使用 API 3 已有的系统控件实现，不引入任何第三方库。
 */
public class MainActivity extends Activity {

    private static final int REQUEST_IMAGE = 2001;
    private static final int REQUEST_TEXT = 2002;

    /** 流式刷新节流，避免高频 setText 拖卡老设备。 */
    private static final long STREAM_REFRESH_MS = 90L;

    /** 压缩时保留的最近消息条数。 */
    private static final int COMPRESS_KEEP_TAIL = 2;

    private final Handler handler = new Handler();

    // 界面
    private ScrollView messageScroll;
    private LinearLayout messageContainer;
    private EditText input;
    private Button sendButton;
    private TextView barTitle;
    private TextView contextChip;
    private View drawer;
    private View scrim;
    private LinearLayout conversationContainer;
    private View attachBar;
    private ImageView attachThumb;
    private TextView attachName;

    // 会话
    private ArrayList<Store.Conv> conversations = new ArrayList<Store.Conv>();
    private long currentId;
    private View welcomeView;

    // 生成状态
    private Net.Cancel cancel;
    private boolean generating;
    private boolean compressing;
    private boolean drawerOpen;

    private View streamingThinkRow;
    private TextView streamingThinkBody;
    private View streamingAiRow;
    private TextView streamingAiBubble;
    private final StringBuilder streamingThinking = new StringBuilder();
    private final StringBuilder streamingContent = new StringBuilder();
    private long lastStreamRefresh;

    private final StringBuilder compressBuffer = new StringBuilder();

    // 附件
    private Uri imageUri;
    private String imageName;
    private String textName;
    private String textContent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 首次启动必须先同意用户协议与隐私政策
        if (!Store.isAgreementAccepted(this)) {
            try {
                startActivity(new Intent(this, AgreementActivity.class));
            } catch (Throwable ignored) {
            }
            finish();
            return;
        }

        try {
            setContentView(R.layout.activity_main);
            bindViews();
            setupDrawer();
            setupComposer();
            loadConversations();
            refreshAll();
        } catch (Throwable t) {
            Util.toastLong(this, Util.humanize(t));
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && drawerOpen) {
            closeDrawer();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ==================== 初始化 ====================

    private void bindViews() {
        messageScroll = (ScrollView) findViewById(R.id.messageScroll);
        messageContainer = (LinearLayout) findViewById(R.id.messageContainer);
        input = (EditText) findViewById(R.id.input);
        sendButton = (Button) findViewById(R.id.sendButton);
        barTitle = (TextView) findViewById(R.id.barTitle);
        contextChip = (TextView) findViewById(R.id.contextChip);
        drawer = findViewById(R.id.drawer);
        scrim = findViewById(R.id.scrim);
        conversationContainer = (LinearLayout) findViewById(R.id.conversationContainer);
        attachBar = findViewById(R.id.attachBar);
        attachThumb = (ImageView) findViewById(R.id.attachThumb);
        attachName = (TextView) findViewById(R.id.attachName);

        TextView version = (TextView) findViewById(R.id.drawerVersion);
        version.setText("v" + App.VERSION_NAME + "    " + getString(R.string.app_engine));

        final Activity self = this;
        listen(R.id.menuButton, new Runnable() {
            public void run() {
                toggleDrawer();
            }
        });
        listen(R.id.attachButton, new Runnable() {
            public void run() {
                showAttachMenu();
            }
        });
        listen(R.id.attachRemove, new Runnable() {
            public void run() {
                clearAttachment();
            }
        });
        listen(R.id.rowNewChat, new Runnable() {
            public void run() {
                closeDrawer();
                startNewConversation();
            }
        });
        listen(R.id.rowTranslate, new Runnable() {
            public void run() {
                closeDrawer();
                open(TranslateActivity.class);
            }
        });
        listen(R.id.rowEssay, new Runnable() {
            public void run() {
                closeDrawer();
                open(EssayActivity.class);
            }
        });
        listen(R.id.rowCheckUpdate, new Runnable() {
            public void run() {
                closeDrawer();
                openTextPage(TextPageActivity.MODE_UPDATE, R.string.update_title, null);
            }
        });
        listen(R.id.rowChangelog, new Runnable() {
            public void run() {
                closeDrawer();
                openTextPage(TextPageActivity.MODE_CHANGELOG, R.string.changelog_title, null);
            }
        });
        listen(R.id.rowAbout, new Runnable() {
            public void run() {
                closeDrawer();
                openTextPage(TextPageActivity.MODE_ABOUT, R.string.about_title, null);
            }
        });

        sendButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    onSendClicked();
                } catch (Throwable t) {
                    Util.toastLong(self, Util.humanize(t));
                }
            }
        });
    }

    private void listen(int id, final Runnable action) {
        View view = findViewById(id);
        if (view == null) {
            return;
        }
        view.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    action.run();
                } catch (Throwable t) {
                    Util.toastLong(MainActivity.this, Util.humanize(t));
                }
            }
        });
    }

    private void setupDrawer() {
        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
            }
        });
    }

    private void setupComposer() {
        sendButton.setEnabled(true);
    }

    private void open(Class<?> target) {
        try {
            startActivity(new Intent(this, target));
        } catch (Throwable t) {
            Util.toast(this, R.string.loading);
        }
    }

    private void openTextPage(int mode, int titleRes, String body) {
        try {
            Intent intent = new Intent(this, TextPageActivity.class);
            intent.putExtra(TextPageActivity.EXTRA_MODE, mode);
            intent.putExtra(TextPageActivity.EXTRA_TITLE, getString(titleRes));
            if (body != null) {
                intent.putExtra(TextPageActivity.EXTRA_BODY, body);
            }
            startActivity(intent);
        } catch (Throwable t) {
            Util.toast(this, R.string.loading);
        }
    }

    // ==================== 抽屉 ====================

    private void toggleDrawer() {
        if (drawerOpen) {
            closeDrawer();
        } else {
            openDrawer();
        }
    }

    private void openDrawer() {
        refreshConversationList();
        drawer.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        TranslateAnimation animation = new TranslateAnimation(Util.dp(this, -290), 0, 0, 0);
        animation.setDuration(180);
        drawer.startAnimation(animation);
        drawerOpen = true;
    }

    private void closeDrawer() {
        if (!drawerOpen) {
            return;
        }
        TranslateAnimation animation = new TranslateAnimation(0, Util.dp(this, -290), 0, 0);
        animation.setDuration(180);
        drawer.startAnimation(animation);
        drawer.setVisibility(View.GONE);
        scrim.setVisibility(View.GONE);
        drawerOpen = false;
    }

    // ==================== 会话管理 ====================

    private void loadConversations() {
        conversations = Store.load(this);
        sortConversations();
        currentId = Store.currentConversationId(this);
        Store.Conv conv = Store.findById(conversations, currentId);
        if (conv == null) {
            conv = new Store.Conv(Store.nextId(conversations), getString(R.string.new_chat_title));
            conversations.add(conv);
            currentId = conv.id;
            Store.setCurrentConversationId(this, currentId);
            persist();
        }
    }

    private void sortConversations() {
        Collections.sort(conversations, new Comparator<Store.Conv>() {
            public int compare(Store.Conv a, Store.Conv b) {
                if (a.updatedAt == b.updatedAt) {
                    return a.id == b.id ? 0 : (a.id > b.id ? -1 : 1);
                }
                return a.updatedAt > b.updatedAt ? -1 : 1;
            }
        });
    }

    private Store.Conv currentConversation() {
        Store.Conv conv = Store.findById(conversations, currentId);
        if (conv == null && !conversations.isEmpty()) {
            conv = conversations.get(0);
        }
        return conv;
    }

    private Store.Conv ensureConversation() {
        Store.Conv conv = currentConversation();
        if (conv == null) {
            conv = new Store.Conv(Store.nextId(conversations), getString(R.string.new_chat_title));
            conversations.add(conv);
            currentId = conv.id;
            Store.setCurrentConversationId(this, currentId);
        }
        return conv;
    }

    private void startNewConversation() {
        if (generating) {
            stopGeneration();
        }
        Store.Conv conv = currentConversation();
        if (conv != null && conv.messages.isEmpty()) {
            // 当前就是空会话，不重复创建
            Util.toast(this, R.string.new_chat_title);
            return;
        }
        Store.Conv fresh = new Store.Conv(Store.nextId(conversations),
                getString(R.string.new_chat_title));
        conversations.add(fresh);
        currentId = fresh.id;
        Store.setCurrentConversationId(this, currentId);
        persist();
        refreshAll();
    }

    private void deleteConversation(final Store.Conv conv) {
        conversations.remove(conv);
        if (conv.id == currentId) {
            Store.Conv next = conversations.isEmpty() ? null : conversations.get(0);
            if (next == null) {
                next = new Store.Conv(Store.nextId(conversations),
                        getString(R.string.new_chat_title));
                conversations.add(next);
            }
            currentId = next.id;
            Store.setCurrentConversationId(this, currentId);
        }
        persist();
        refreshAll();
        Util.toast(this, R.string.deleted);
    }

    private void persist() {
        Store.save(this, conversations);
    }

    private void refreshAll() {
        renderMessages();
        refreshBarTitle();
        updateContextChip();
        refreshConversationList();
    }

    private void refreshBarTitle() {
        Store.Conv conv = currentConversation();
        String title = (conv == null || conv.title.length() == 0)
                ? getString(R.string.app_name) : conv.title;
        barTitle.setText(title);
    }

    // ==================== 消息渲染 ====================

    private void renderMessages() {
        messageContainer.removeAllViews();
        welcomeView = null;
        Store.Conv conv = currentConversation();
        if (conv == null || conv.messages.isEmpty()) {
            showWelcome();
            return;
        }
        for (int k = 0; k < conv.messages.size(); k++) {
            addMessageView(conv.messages.get(k));
        }
        scrollToBottom();
    }

    private void showWelcome() {
        try {
            welcomeView = getLayoutInflater().inflate(R.layout.item_msg_welcome,
                    messageContainer, false);
            messageContainer.addView(welcomeView);
        } catch (Throwable ignored) {
        }
    }

    private void addMessageView(Store.Msg msg) {
        if (msg == null) {
            return;
        }
        if (Store.Msg.ROLE_SUMMARY.equals(msg.role)) {
            addSummaryView(msg.content);
            return;
        }
        if (Store.Msg.ROLE_USER.equals(msg.role)) {
            addBubbleView(R.layout.item_msg_user, msg.content);
            return;
        }
        if (msg.thinking != null && msg.thinking.length() > 0) {
            addThinkingView(msg.thinking, true);
        }
        addBubbleView(R.layout.item_msg_ai, msg.content);
    }

    private View addBubbleView(int layout, String text) {
        View row = getLayoutInflater().inflate(layout, messageContainer, false);
        TextView bubble = (TextView) row.findViewById(R.id.bubble);
        if (bubble != null) {
            bubble.setText(text == null ? "" : text);
        }
        messageContainer.addView(row);
        return row;
    }

    private View addSummaryView(String text) {
        View row = getLayoutInflater().inflate(R.layout.item_msg_summary,
                messageContainer, false);
        TextView body = (TextView) row.findViewById(R.id.summaryBody);
        body.setText(text == null ? "" : text);
        messageContainer.addView(row);
        return row;
    }

    private View addThinkingView(String text, boolean collapsed) {
        View row = getLayoutInflater().inflate(R.layout.item_msg_think,
                messageContainer, false);
        final TextView body = (TextView) row.findViewById(R.id.thinkBody);
        final TextView toggle = (TextView) row.findViewById(R.id.thinkToggle);
        body.setText(text == null ? "" : text);

        final boolean[] state = new boolean[]{collapsed};
        body.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        toggle.setText(collapsed ? R.string.expand : R.string.collapse);

        View header = row.findViewById(R.id.thinkHeader);
        header.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                state[0] = !state[0];
                body.setVisibility(state[0] ? View.GONE : View.VISIBLE);
                toggle.setText(state[0] ? R.string.expand : R.string.collapse);
            }
        });
        messageContainer.addView(row);
        return row;
    }

    private void scrollToBottom() {
        try {
            messageScroll.post(new Runnable() {
                public void run() {
                    try {
                        messageScroll.fullScroll(View.FOCUS_DOWN);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // ==================== 上下文用量 ====================

    private List<Map<String, Object>> buildPayload(Store.Conv conv) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        if (conv == null) {
            return list;
        }
        for (int k = 0; k < conv.messages.size(); k++) {
            Store.Msg msg = conv.messages.get(k);
            Map<String, Object> node = new HashMap<String, Object>();
            if (Store.Msg.ROLE_SUMMARY.equals(msg.role)) {
                node.put("role", "system");
                node.put("content", "以下是之前对话的摘要，请在后续回答中沿用：\n" + msg.content);
            } else if (Store.Msg.ROLE_ASSISTANT.equals(msg.role)) {
                node.put("role", "assistant");
                node.put("content", msg.content);
            } else {
                node.put("role", "user");
                node.put("content", msg.content);
            }
            list.add(node);
        }
        return list;
    }

    private void updateContextChip() {
        Store.Conv conv = currentConversation();
        long tokens = conv == null ? 0L : Ctx.estimateMessages(buildPayload(conv));
        int percent = Ctx.percent(tokens);
        contextChip.setText(getString(R.string.context_usage, Integer.valueOf(percent)));
    }

    // ==================== 会话列表 ====================

    private void refreshConversationList() {
        conversationContainer.removeAllViews();
        sortConversations();
        long now = System.currentTimeMillis();
        int lastSection = -1;
        for (int k = 0; k < conversations.size(); k++) {
            final Store.Conv conv = conversations.get(k);
            if (conv.messages.isEmpty() && conv.id != currentId) {
                continue;
            }
            int section = Store.sectionOf(conv.updatedAt, now);
            if (section != lastSection) {
                lastSection = section;
                addSectionTitle(sectionLabel(section));
            }
            addConversationRow(conv);
        }
    }

    private int sectionLabel(int section) {
        switch (section) {
            case Store.SECTION_TODAY:
                return R.string.section_today;
            case Store.SECTION_YESTERDAY:
                return R.string.section_yesterday;
            case Store.SECTION_LAST_7_DAYS:
                return R.string.section_last_7_days;
            case Store.SECTION_LAST_30_DAYS:
                return R.string.section_last_30_days;
            default:
                return R.string.section_earlier;
        }
    }

    private void addSectionTitle(int labelRes) {
        TextView title = (TextView) getLayoutInflater().inflate(R.layout.item_section,
                conversationContainer, false);
        title.setText(labelRes);
        conversationContainer.addView(title);
    }

    private void addConversationRow(final Store.Conv conv) {
        View row = getLayoutInflater().inflate(R.layout.item_conversation,
                conversationContainer, false);
        TextView title = (TextView) row.findViewById(R.id.convTitle);
        String text = conv.title.length() == 0 ? getString(R.string.new_chat_title) : conv.title;
        title.setText(text);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (generating) {
                    stopGeneration();
                }
                currentId = conv.id;
                Store.setCurrentConversationId(MainActivity.this, currentId);
                closeDrawer();
                refreshAll();
            }
        });

        View delete = row.findViewById(R.id.convDelete);
        delete.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmDelete(conv);
            }
        });

        conversationContainer.addView(row);
    }

    private void confirmDelete(final Store.Conv conv) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.delete_conversation)
                .setPositiveButton(R.string.confirm, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        deleteConversation(conv);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ==================== 发送 ====================

    private void onSendClicked() {
        if (compressing) {
            Util.toast(this, R.string.loading);
            return;
        }
        if (generating) {
            stopGeneration();
            return;
        }
        send();
    }

    private void send() {
        String typed = input.getText().toString().trim();
        if (typed.length() == 0 && textContent == null && imageUri == null) {
            Util.toast(this, R.string.empty_input);
            return;
        }

        final Store.Conv conv = ensureConversation();
        String userContent = buildUserContent(typed);
        conv.messages.add(new Store.Msg(Store.Msg.ROLE_USER, userContent, ""));
        if (conv.title.length() == 0 || getString(R.string.new_chat_title).equals(conv.title)) {
            conv.title = Util.titleOf(typed.length() > 0 ? typed : userContent);
        }
        conv.updatedAt = System.currentTimeMillis();
        persist();

        if (welcomeView != null) {
            messageContainer.removeAllViews();
            welcomeView = null;
        }
        addMessageView(conv.messages.get(conv.messages.size() - 1));

        input.setText("");
        clearAttachment();
        refreshBarTitle();
        updateContextChip();
        refreshConversationList();
        scrollToBottom();

        dispatch(conv);
    }

    private String buildUserContent(String typed) {
        StringBuilder b = new StringBuilder();
        if (typed.length() > 0) {
            b.append(typed);
        }
        if (textContent != null) {
            if (b.length() > 0) {
                b.append("\n\n");
            }
            b.append("【文本附件：").append(textName == null ? "txt" : textName).append("】\n");
            b.append(textContent);
        }
        if (imageUri != null) {
            if (b.length() > 0) {
                b.append("\n\n");
            }
            b.append("【图片附件：").append(imageName == null ? "image" : imageName).append("】\n");
            b.append("（本模型仅支持识别文本，无法读取图片内容）");
        }
        return b.toString();
    }

    private void dispatch(Store.Conv conv) {
        streamingThinking.setLength(0);
        streamingContent.setLength(0);
        streamingThinkRow = null;
        streamingThinkBody = null;
        streamingAiRow = null;
        streamingAiBubble = null;
        lastStreamRefresh = 0L;
        generating = true;
        sendButton.setText(R.string.stop);
        updateContextChip();

        final List<Map<String, Object>> payload = buildPayload(conv);

        cancel = Net.stream(payload, new Net.Sink() {
            public void onThinking(final String delta) {
                handler.post(new Runnable() {
                    public void run() {
                        if (isFinishing() || !generating) {
                            return;
                        }
                        appendThinking(delta);
                    }
                });
            }

            public void onContent(final String delta) {
                handler.post(new Runnable() {
                    public void run() {
                        if (isFinishing() || !generating) {
                            return;
                        }
                        appendContent(delta);
                    }
                });
            }

            public void onFinish(final String thinking, final String content) {
                handler.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        completeGeneration(thinking, content, null);
                    }
                });
            }

            public void onError(final String message) {
                handler.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        completeGeneration(null, null, message);
                    }
                });
            }
        });
    }

    private void appendThinking(String delta) {
        streamingThinking.append(delta);
        if (streamingThinkRow == null) {
            streamingThinkRow = addThinkingView("", false);
            streamingThinkBody = (TextView) streamingThinkRow.findViewById(R.id.thinkBody);
            lastStreamRefresh = 0L;
        }
        long now = System.currentTimeMillis();
        if (now - lastStreamRefresh < STREAM_REFRESH_MS || streamingThinkBody == null) {
            return;
        }
        lastStreamRefresh = now;
        streamingThinkBody.setText(streamingThinking.toString());
        scrollToBottom();
    }

    private void appendContent(String delta) {
        streamingContent.append(delta);
        if (streamingAiRow == null) {
            streamingAiRow = addBubbleView(R.layout.item_msg_ai, "");
            streamingAiBubble = (TextView) streamingAiRow.findViewById(R.id.bubble);
            lastStreamRefresh = 0L;
        }
        long now = System.currentTimeMillis();
        if (now - lastStreamRefresh < STREAM_REFRESH_MS || streamingAiBubble == null) {
            return;
        }
        lastStreamRefresh = now;
        streamingAiBubble.setText(streamingContent.toString());
        scrollToBottom();
    }

    private void stopGeneration() {
        if (cancel != null) {
            cancel.cancel();
        }
        generating = false;
        sendButton.setText(R.string.send);
        // 把已经收到的内容落库，避免用户白白等待
        completeGeneration(streamingThinking.toString(), streamingContent.toString(), null);
    }

    private void completeGeneration(String thinking, String content, String error) {
        generating = false;
        sendButton.setText(R.string.send);

        Store.Conv conv = currentConversation();
        if (conv == null) {
            return;
        }

        if (error != null) {
            if (streamingAiBubble != null) {
                streamingAiBubble.setText(error);
            } else {
                addBubbleView(R.layout.item_msg_ai, error);
            }
            conv.messages.add(new Store.Msg(Store.Msg.ROLE_ASSISTANT, error, ""));
        } else {
            String finalThinking = thinking == null ? "" : thinking;
            String finalContent = content == null ? "" : content;
            if (streamingThinkRow != null && finalThinking.length() > 0
                    && streamingThinkBody != null) {
                streamingThinkBody.setText(finalThinking);
            }
            if (finalContent.length() > 0) {
                if (streamingAiRow == null) {
                    streamingAiRow = addBubbleView(R.layout.item_msg_ai, finalContent);
                } else if (streamingAiBubble != null) {
                    streamingAiBubble.setText(finalContent);
                }
                conv.messages.add(new Store.Msg(Store.Msg.ROLE_ASSISTANT, finalContent,
                        finalThinking));
            }
        }

        conv.updatedAt = System.currentTimeMillis();
        persist();
        refreshBarTitle();
        updateContextChip();
        refreshConversationList();
        scrollToBottom();

        if (error == null) {
            maybeCompress();
        }
    }

    // ==================== 上下文自动压缩 ====================

    private void maybeCompress() {
        Store.Conv conv = currentConversation();
        if (conv == null) {
            return;
        }
        long tokens = Ctx.estimateMessages(buildPayload(conv));
        if (!Ctx.shouldCompress(tokens)) {
            return;
        }
        int compressCount = conv.messages.size() - COMPRESS_KEEP_TAIL;
        if (compressCount < 2) {
            return;
        }
        Util.toast(this, getString(R.string.compress_started,
                Integer.valueOf(Ctx.percent(tokens))));
        startCompress(conv, compressCount);
    }

    private void startCompress(final Store.Conv conv, final int compressCount) {
        StringBuilder dialog = new StringBuilder();
        for (int k = 0; k < compressCount && k < conv.messages.size(); k++) {
            Store.Msg msg = conv.messages.get(k);
            String role;
            if (Store.Msg.ROLE_USER.equals(msg.role)) {
                role = "用户";
            } else if (Store.Msg.ROLE_SUMMARY.equals(msg.role)) {
                role = "前情摘要";
            } else {
                role = "助手";
            }
            dialog.append(role).append("：").append(msg.content).append('\n');
        }

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        Map<String, Object> system = new HashMap<String, Object>();
        system.put("role", "system");
        system.put("content", "你是一个对话整理助手，负责把长对话压缩成简洁摘要。");
        messages.add(system);
        Map<String, Object> user = new HashMap<String, Object>();
        user.put("role", "user");
        user.put("content", Prompts.compressPrompt(dialog.toString()));
        messages.add(user);

        compressing = true;
        compressBuffer.setLength(0);
        sendButton.setEnabled(false);

        cancel = Net.stream(messages, new Net.Sink() {
            public void onThinking(String delta) {
            }

            public void onContent(String delta) {
                compressBuffer.append(delta);
            }

            public void onFinish(String thinking, final String content) {
                final String summary = (content == null || content.length() == 0)
                        ? compressBuffer.toString() : content;
                handler.post(new Runnable() {
                    public void run() {
                        if (!isFinishing()) {
                            applyCompress(conv, compressCount, summary);
                        }
                    }
                });
            }

            public void onError(final String message) {
                handler.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        compressing = false;
                        sendButton.setEnabled(true);
                        Util.toast(MainActivity.this,
                                getString(R.string.compress_failed, message));
                    }
                });
            }
        });
    }

    private void applyCompress(Store.Conv conv, int compressCount, String summary) {
        compressing = false;
        sendButton.setEnabled(true);
        String text = summary == null ? "" : summary.trim();
        if (text.length() == 0) {
            Util.toast(this, getString(R.string.compress_failed, ""));
            return;
        }
        // 用一条摘要替换掉前半段对话
        int removable = Math.min(compressCount, conv.messages.size());
        for (int k = removable - 1; k >= 0; k--) {
            conv.messages.remove(k);
        }
        conv.messages.add(0, new Store.Msg(Store.Msg.ROLE_SUMMARY, text, ""));
        conv.updatedAt = System.currentTimeMillis();
        persist();
        renderMessages();
        updateContextChip();
        Util.toast(this, R.string.compress_done);
    }

    // ==================== 附件 ====================

    private void showAttachMenu() {
        final String[] labels = new String[]{
                getString(R.string.attach_image),
                getString(R.string.attach_text),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.attach)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == 0) {
                            pickImage();
                        } else {
                            pickTextFile();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void pickImage() {
        try {
            startActivityForResult(new Intent(this, ImagePickerActivity.class), REQUEST_IMAGE);
        } catch (Throwable t) {
            Util.toast(this, R.string.file_read_failed);
        }
    }

    private void pickTextFile() {
        try {
            startActivityForResult(new Intent(this, FilePickerActivity.class), REQUEST_TEXT);
        } catch (Throwable t) {
            Util.toast(this, R.string.file_read_failed);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        try {
            if (requestCode == REQUEST_IMAGE) {
                Uri uri = data.getData();
                if (uri == null) {
                    return;
                }
                imageUri = uri;
                imageName = data.getStringExtra(ImagePickerActivity.EXTRA_IMAGE_NAME);
                if (imageName == null) {
                    imageName = getString(R.string.attach_image_label);
                }
                refreshAttachBar();
                loadAttachThumb(uri);
                Util.toastLong(this, R.string.image_text_only);
            } else if (requestCode == REQUEST_TEXT) {
                textName = data.getStringExtra(FilePickerActivity.EXTRA_FILE_NAME);
                textContent = data.getStringExtra(FilePickerActivity.EXTRA_FILE_CONTENT);
                refreshAttachBar();
            }
        } catch (Throwable t) {
            Util.toastLong(this, Util.humanize(t));
        }
    }

    private void refreshAttachBar() {
        if (imageUri == null && textContent == null) {
            attachBar.setVisibility(View.GONE);
            return;
        }
        attachBar.setVisibility(View.VISIBLE);
        if (imageUri != null) {
            attachName.setText(imageName);
        } else {
            attachThumb.setImageBitmap(null);
            attachName.setText(getString(R.string.attach_text_label) + "：" + textName);
        }
    }

    private void clearAttachment() {
        imageUri = null;
        imageName = null;
        textName = null;
        textContent = null;
        attachThumb.setImageBitmap(null);
        attachBar.setVisibility(View.GONE);
    }

    private void loadAttachThumb(final Uri uri) {
        Thread worker = new Thread(new Runnable() {
            public void run() {
                final Bitmap bitmap = decodeThumb(uri);
                handler.post(new Runnable() {
                    public void run() {
                        if (!isFinishing() && bitmap != null && imageUri != null) {
                            attachThumb.setImageBitmap(bitmap);
                        }
                    }
                });
            }
        }, "veryold-attach");
        worker.setDaemon(true);
        worker.start();
    }

    private Bitmap decodeThumb(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream probe = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(probe, null, bounds);
            Util.closeQuietly(probe);

            int width = bounds.outWidth;
            int height = bounds.outHeight;
            int sample = 1;
            while (width / (sample * 2) >= 128 && height / (sample * 2) >= 128) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            InputStream stream = getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(stream, null, options);
            Util.closeQuietly(stream);
            return bitmap;
        } catch (Throwable t) {
            return null;
        }
    }
}
