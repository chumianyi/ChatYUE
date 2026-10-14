package com.veryolddpsk.chat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 固定接口的客户端。
 *
 * 只使用 java.net 与 java.util.zip 中 API 1 就存在的接口，不依赖 OkHttp 等第三方库。
 */
public final class Net {

    /** 流式回调，全部在后台线程触发，调用方需自行切回主线程。 */
    public interface Sink {
        void onThinking(String delta);

        void onContent(String delta);

        void onFinish(String fullThinking, String fullContent);

        void onError(String message);
    }

    /** 可取消的任务句柄。 */
    public static final class Cancel {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;

        public void cancel() {
            cancelled = true;
            HttpURLConnection c = connection;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                    // 断开连接失败不影响取消语义
                }
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }

        void attach(HttpURLConnection c) {
            connection = c;
            if (cancelled && c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 180000;
    private static final int RAW_KEEP_LIMIT = 200000;

    private Net() {
    }

    // ==================== 流式对话 ====================

    /** 发起一次流式对话请求，立即返回可取消句柄。 */
    public static Cancel stream(final List<Map<String, Object>> messages, final Sink sink) {
        final Cancel cancel = new Cancel();
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    // 老版本 HttpURLConnection 的 keep-alive 实现有问题，直接关掉最稳
                    System.setProperty("http.keepAlive", "false");
                } catch (Throwable ignored) {
                }
                try {
                    doStream(messages, sink, cancel);
                } catch (Throwable t) {
                    if (!cancel.isCancelled()) {
                        sink.onError(Util.humanize(t));
                    }
                }
            }
        }, "veryold-net");
        worker.setDaemon(true);
        worker.start();
        return cancel;
    }

    private static void doStream(List<Map<String, Object>> messages, Sink sink, Cancel cancel)
            throws Exception {
        StringBuilder payload = new StringBuilder(256);
        payload.append("{\"model\":").append(Json.quote(App.MODEL));
        payload.append(",\"stream\":true,\"messages\":[");
        for (int k = 0; k < messages.size(); k++) {
            if (k > 0) {
                payload.append(',');
            }
            Map<String, Object> m = messages.get(k);
            String role = Json.str(m, "role");
            payload.append("{\"role\":").append(Json.quote(role == null ? "user" : role));
            payload.append(",\"content\":").append(Json.quote(Json.str(m, "content")));
            payload.append('}');
        }
        payload.append("]}");

        HttpURLConnection conn = null;
        try {
            URL url = new URL(App.ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            cancel.attach(conn);

            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Authorization", "Bearer " + App.API_KEY);
            conn.setRequestProperty("Connection", "close");

            byte[] body = payload.toString().getBytes("UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (cancel.isCancelled()) {
                return;
            }
            if (code < 200 || code >= 300) {
                String detail = readAll(wrap(conn.getErrorStream(), conn.getContentEncoding()), 4000);
                sink.onError(describeHttpError(code, detail));
                return;
            }

            InputStream in = wrap(conn.getInputStream(), conn.getContentEncoding());

            StringBuilder thinking = new StringBuilder();
            StringBuilder content = new StringBuilder();
            StringBuilder raw = new StringBuilder();
            String streamError = null;
            boolean sawEvent = false;

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"), 8192);
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancel.isCancelled()) {
                    break;
                }
                if (raw.length() < RAW_KEEP_LIMIT) {
                    raw.append(line).append('\n');
                }
                if (line.length() == 0 || line.charAt(0) == ':') {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.length() == 0) {
                    continue;
                }
                sawEvent = true;
                if ("[DONE]".equals(data)) {
                    break;
                }

                Map<String, Object> event = Json.parseObject(data);
                if (event.isEmpty()) {
                    continue;
                }
                Map<String, Object> err = Json.obj(event, "error");
                if (!err.isEmpty()) {
                    String msg = Json.str(err, "message");
                    streamError = (msg == null || msg.length() == 0) ? err.toString() : msg;
                    break;
                }

                List<Object> choices = Json.arr(event, "choices");
                if (choices.isEmpty()) {
                    continue;
                }
                Map<String, Object> choice = Json.asMap(choices.get(0));
                Map<String, Object> delta = Json.obj(choice, "delta");
                if (delta.isEmpty()) {
                    delta = Json.obj(choice, "message");
                }
                String thinkDelta = Json.str(delta, "reasoning_content");
                String textDelta = Json.str(delta, "content");
                if (thinkDelta != null && thinkDelta.length() > 0) {
                    thinking.append(thinkDelta);
                    sink.onThinking(thinkDelta);
                }
                if (textDelta != null && textDelta.length() > 0) {
                    content.append(textDelta);
                    sink.onContent(textDelta);
                }
            }
            Util.closeQuietly(reader);

            if (cancel.isCancelled()) {
                return;
            }
            if (streamError != null) {
                sink.onError(Util.humanizeMessage(streamError));
                return;
            }
            if (content.length() == 0 && thinking.length() == 0) {
                // 完全不是 SSE：可能服务端忽略了 stream 参数，直接返回普通 JSON
                if (parsePlainJson(raw.toString(), thinking, content, sink)) {
                    return;
                }
                sink.onError("服务端没有返回可显示的内容。\nHTTP " + code
                        + "\nContent-Type: " + conn.getContentType()
                        + "\n响应片段：" + emptyHint(Util.snippet(raw.toString())));
                return;
            }
            // 有些代理只推 delta，不给 finish_reason / [DONE]：有内容就按正常结束处理
            sink.onFinish(thinking.toString(), content.toString());
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 把普通（非流式）JSON 响应解析出内容。 */
    private static boolean parsePlainJson(String raw, StringBuilder thinking,
                                          StringBuilder content, Sink sink) {
        int start = raw.indexOf('{');
        if (start < 0) {
            return false;
        }
        Map<String, Object> root = Json.parseObject(raw.substring(start));
        if (root.isEmpty()) {
            return false;
        }
        Map<String, Object> err = Json.obj(root, "error");
        if (!err.isEmpty()) {
            String msg = Json.str(err, "message");
            sink.onError(Util.humanizeMessage(
                    (msg == null || msg.length() == 0) ? err.toString() : msg));
            return true;
        }
        List<Object> choices = Json.arr(root, "choices");
        if (choices.isEmpty()) {
            return false;
        }
        Map<String, Object> choice = Json.asMap(choices.get(0));
        Map<String, Object> message = Json.obj(choice, "message");
        String text = Json.str(message, "content");
        String think = Json.str(message, "reasoning_content");
        if (text == null) {
            text = Json.str(choice, "text");
        }
        if ((text == null || text.length() == 0) && (think == null || think.length() == 0)) {
            return false;
        }
        if (think != null && think.length() > 0) {
            thinking.append(think);
            sink.onThinking(think);
        }
        if (text != null && text.length() > 0) {
            content.append(text);
            sink.onContent(text);
        }
        sink.onFinish(thinking.toString(), content.toString());
        return true;
    }

    // ==================== 普通 GET ====================

    /** 拉取文本内容（用于更新日志、用户协议）。 */
    public static String get(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Accept", "application/json, text/html, */*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Connection", "close");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            return readAll(wrap(conn.getInputStream(), conn.getContentEncoding()), RAW_KEEP_LIMIT);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ==================== 内部工具 ====================

    private static InputStream wrap(InputStream in, String encoding) throws Exception {
        if (in == null) {
            return null;
        }
        if (encoding != null && encoding.toLowerCase().indexOf("gzip") >= 0) {
            try {
                return new GZIPInputStream(in);
            } catch (Throwable t) {
                return in;
            }
        }
        return in;
    }

    private static String readAll(InputStream in, int limit) {
        if (in == null) {
            return "";
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"), 4096);
            StringBuilder b = new StringBuilder();
            char[] buf = new char[2048];
            int n;
            while ((n = reader.read(buf)) > 0) {
                b.append(buf, 0, n);
                if (b.length() >= limit) {
                    break;
                }
            }
            return b.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            Util.closeQuietly(reader);
        }
    }

    private static String describeHttpError(int code, String detail) {
        String parsed = "";
        if (detail != null && detail.length() > 0) {
            Map<String, Object> root = Json.parseObject(detail);
            Map<String, Object> err = Json.obj(root, "error");
            String msg = Json.str(err, "message");
            if (msg == null || msg.length() == 0) {
                msg = Json.str(root, "message");
            }
            if (msg != null && msg.length() > 0) {
                parsed = msg;
            }
        }
        if (parsed.length() > 0) {
            return Util.humanizeMessage(parsed);
        }
        if (code == 401 || code == 403) {
            return "接口拒绝访问（HTTP " + code + "），密钥可能已失效。";
        }
        if (code == 404) {
            return "接口地址不存在（HTTP 404）。";
        }
        if (code == 429) {
            return "请求过于频繁（HTTP 429），请稍后再试。";
        }
        if (code >= 500) {
            return "接口服务异常（HTTP " + code + "），请稍后重试。";
        }
        return "请求失败（HTTP " + code + "）。" + Util.snippet(detail);
    }

    private static String emptyHint(String snippet) {
        return snippet == null || snippet.length() == 0 ? "（空响应）" : snippet;
    }
}
