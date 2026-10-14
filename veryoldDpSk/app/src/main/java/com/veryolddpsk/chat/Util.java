package com.veryolddpsk.chat;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.TypedValue;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

/** 通用工具：单位换算、提示、异常人话化、文件读取。 */
public final class Util {

    /** 文本附件最大 200KB。 */
    public static final int MAX_TEXT_BYTES = 200 * 1024;

    private Util() {
    }

    public static int dp(Context c, float value) {
        return (int) (TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics()) + 0.5f);
    }

    // ==================== 提示 ====================

    public static void toast(Context c, String message) {
        if (c == null || message == null) {
            return;
        }
        try {
            Toast.makeText(c, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // 老设备上 Toast 偶发失败不应影响主流程
        }
    }

    public static void toast(Context c, int resId) {
        if (c == null) {
            return;
        }
        try {
            Toast.makeText(c, resId, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    public static void toastLong(Context c, int resId) {
        if (c == null) {
            return;
        }
        try {
            Toast.makeText(c, resId, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    public static void toastLong(Context c, String message) {
        if (c == null || message == null) {
            return;
        }
        try {
            Toast.makeText(c, message, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 错误人话化 ====================

    /** 把网络/IO 异常翻译成用户能看懂的话。 */
    public static String humanize(Throwable t) {
        if (t == null) {
            return "出现未知问题，请重试。";
        }
        String name = t.getClass().getName();
        if (name.indexOf("UnknownHost") >= 0) {
            return "无法解析服务器地址，请检查网络是否可用。";
        }
        if (name.indexOf("ConnectException") >= 0 || name.indexOf("ConnectTimeout") >= 0) {
            return "连接服务器失败，请稍后重试。";
        }
        if (name.indexOf("SocketTimeout") >= 0) {
            return "服务器响应超时，请稍后重试。";
        }
        if (name.indexOf("SocketException") >= 0) {
            return "网络连接中断，请检查网络后重试。";
        }
        if (name.indexOf("SSL") >= 0 || name.indexOf("CertPath") >= 0) {
            return "安全连接建立失败。";
        }
        if (name.indexOf("OutOfMemory") >= 0) {
            return "内存不足，请关闭其他应用后重试。";
        }
        String msg = t.getMessage();
        return (msg == null || msg.length() == 0) ? name : msg;
    }

    /** 把服务端返回的错误文案翻译成人话，认不出来就原样返回。 */
    public static String humanizeMessage(String message) {
        if (message == null || message.length() == 0) {
            return "出现未知问题，请重试。";
        }
        String lower = message.toLowerCase();
        if (lower.indexOf("insufficient") >= 0 || message.indexOf("余额") >= 0
                || lower.indexOf("30001") >= 0) {
            return "接口账户余额不足，请联系服务提供方充值。";
        }
        if (lower.indexOf("invalid api key") >= 0 || lower.indexOf("unauthorized") >= 0
                || lower.indexOf("401") >= 0) {
            return "接口密钥无效或已失效。";
        }
        if (lower.indexOf("rate limit") >= 0 || lower.indexOf("429") >= 0
                || lower.indexOf("too many requests") >= 0) {
            return "请求过于频繁，请稍后再试。";
        }
        if (lower.indexOf("timeout") >= 0 || lower.indexOf("timed out") >= 0) {
            return "服务端处理超时，请稍后重试。";
        }
        if (lower.indexOf("model") >= 0 && lower.indexOf("not") >= 0) {
            return "当前模型暂不可用，请稍后重试。";
        }
        if (lower.indexOf("502") >= 0 || lower.indexOf("503") >= 0
                || lower.indexOf("bad gateway") >= 0) {
            return "接口服务暂时不可用，请稍后重试。";
        }
        return message;
    }

    // ==================== 文本 ====================

    /** 取服务端返回内容的一小段，便于定位问题。 */
    public static String snippet(String raw) {
        if (raw == null) {
            return "";
        }
        String flat = raw.replace('\n', ' ').replace('\r', ' ').trim();
        while (flat.indexOf("  ") >= 0) {
            flat = flat.replace("  ", " ");
        }
        if (flat.length() == 0) {
            return "";
        }
        int limit = 200;
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    /**
     * 统计字数：中文按字计数，英文按空白分词计数，与主流写作软件的统计口径接近。
     */
    public static int countWords(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        boolean inWord = false;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (Ctx.isCjk(c)) {
                count++;
                inWord = false;
                continue;
            }
            if (Character.isWhitespace(c)) {
                inWord = false;
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                if (!inWord) {
                    count++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }
        return count;
    }

    /** 截取对话标题。 */
    public static String titleOf(String content) {
        if (content == null) {
            return "";
        }
        String flat = content.replace('\n', ' ').replace('\r', ' ').trim();
        while (flat.indexOf("  ") >= 0) {
            flat = flat.replace("  ", " ");
        }
        if (flat.length() > 18) {
            return flat.substring(0, 18) + "…";
        }
        return flat.length() == 0 ? "新的对话" : flat;
    }

    // ==================== 文件 ====================

    /** 读取本地文件为文本，超过上限或读取失败返回 null。 */
    public static String readTextFile(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        if (file.length() > MAX_TEXT_BYTES) {
            return null;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            return readStream(in, file.length());
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** 读取输入流为文本。 */
    public static String readStream(InputStream in, long knownSize) {
        if (in == null) {
            return null;
        }
        if (knownSize > MAX_TEXT_BYTES) {
            return null;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"), 8192);
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                b.append(line).append('\n');
                if (b.length() > MAX_TEXT_BYTES) {
                    return null;
                }
            }
            return b.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(reader);
        }
    }

    public static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 其他 ====================

    /** 把简单 HTML 转成纯文本（老设备上 WebView 不可靠，统一用文本渲染）。 */
    public static String htmlToText(String html) {
        if (html == null) {
            return null;
        }
        String s = dropBlock(html, "<script", "</script>");
        s = dropBlock(s, "<style", "</style>");
        s = dropBlock(s, "<!--", "-->");
        s = s.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n");
        s = s.replace("</p>", "\n\n").replace("</div>", "\n").replace("</li>", "\n");
        s = s.replace("</h1>", "\n\n").replace("</h2>", "\n\n").replace("</h3>", "\n\n");
        s = s.replace("</tr>", "\n").replace("</td>", "    ").replace("</th>", "    ");

        StringBuilder b = new StringBuilder(s.length());
        boolean inTag = false;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '<') {
                inTag = true;
                continue;
            }
            if (c == '>') {
                inTag = false;
                continue;
            }
            if (!inTag) {
                b.append(c);
            }
        }
        String text = b.toString();
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");

        // 压缩多余空白行
        StringBuilder out = new StringBuilder(text.length());
        int blank = 0;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '\n') {
                blank++;
                if (blank <= 2) {
                    out.append('\n');
                }
            } else {
                blank = 0;
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    private static String dropBlock(String source, String open, String close) {
        String s = source;
        String lowerOpen = open.toLowerCase();
        String lowerClose = close.toLowerCase();
        while (true) {
            int start = s.toLowerCase().indexOf(lowerOpen);
            if (start < 0) {
                return s;
            }
            int end = s.toLowerCase().indexOf(lowerClose, start);
            if (end < 0) {
                return s.substring(0, start);
            }
            s = s.substring(0, start) + s.substring(end + close.length());
        }
    }

    public static void openUrl(Context c, String url) {
        if (c == null || url == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(intent);
        } catch (Throwable t) {
            toast(c, "没有可用的浏览器");
        }
    }
}
