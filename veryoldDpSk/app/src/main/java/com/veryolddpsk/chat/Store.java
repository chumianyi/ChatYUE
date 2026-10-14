package com.veryolddpsk.chat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本地存储：会话、消息与协议同意状态。
 *
 * 只使用 SharedPreferences（API 1 即有），会话数据以 JSON 文本形式保存，
 * 避免依赖 SQLite 在新老系统上的差异。
 */
public final class Store {

    private static final String PREF_NAME = "veryold_store";
    private static final String KEY_CONVERSATIONS = "conversations";
    private static final String KEY_CURRENT = "current_conversation";
    private static final String KEY_AGREEMENT = "agreement_accepted";

    /** 一条消息。 */
    public static final class Msg {
        public static final String ROLE_USER = "user";
        public static final String ROLE_ASSISTANT = "assistant";
        /** 上下文压缩后生成的摘要，以 system 身份参与后续请求。 */
        public static final String ROLE_SUMMARY = "summary";

        public String role;
        public String content;
        public String thinking;

        public Msg(String role, String content, String thinking) {
            this.role = role == null ? ROLE_USER : role;
            this.content = content == null ? "" : content;
            this.thinking = thinking == null ? "" : thinking;
        }
    }

    /** 一个会话。 */
    public static final class Conv {
        public long id;
        public String title;
        public long updatedAt;
        public ArrayList<Msg> messages = new ArrayList<Msg>();

        public Conv() {
            this(0L, "");
        }

        public Conv(long id, String title) {
            this.id = id;
            this.title = title == null ? "" : title;
        }

        public boolean isEmpty() {
            return messages.isEmpty();
        }
    }

    /** 会话分组，按时间由近到远。 */
    public static final int SECTION_TODAY = 0;
    public static final int SECTION_YESTERDAY = 1;
    public static final int SECTION_LAST_7_DAYS = 2;
    public static final int SECTION_LAST_30_DAYS = 3;
    public static final int SECTION_EARLIER = 4;

    private Store() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ==================== 协议 ====================

    public static boolean isAgreementAccepted(Context c) {
        try {
            return prefs(c).getBoolean(KEY_AGREEMENT, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setAgreementAccepted(Context c, boolean accepted) {
        try {
            prefs(c).edit().putBoolean(KEY_AGREEMENT, accepted).commit();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 当前会话 ====================

    public static long currentConversationId(Context c) {
        try {
            return prefs(c).getLong(KEY_CURRENT, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static void setCurrentConversationId(Context c, long id) {
        try {
            prefs(c).edit().putLong(KEY_CURRENT, id).commit();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 会话读写 ====================

    public static ArrayList<Conv> load(Context c) {
        ArrayList<Conv> list = new ArrayList<Conv>();
        String raw = null;
        try {
            raw = prefs(c).getString(KEY_CONVERSATIONS, null);
        } catch (Throwable ignored) {
        }
        if (raw == null || raw.length() == 0) {
            return list;
        }
        Object parsed = Json.parse(raw);
        List<Object> array = Json.asList(parsed);
        for (int k = 0; k < array.size(); k++) {
            Map<String, Object> node = Json.asMap(array.get(k));
            Conv conv = new Conv();
            conv.id = Json.num(node, "id", 0L);
            conv.title = Json.strOrEmpty(node, "title");
            conv.updatedAt = Json.num(node, "updatedAt", 0L);
            List<Object> msgs = Json.arr(node, "messages");
            for (int j = 0; j < msgs.size(); j++) {
                Map<String, Object> mn = Json.asMap(msgs.get(j));
                String role = Json.strOrEmpty(mn, "role");
                if (role.length() == 0) {
                    role = Msg.ROLE_USER;
                }
                conv.messages.add(new Msg(role, Json.strOrEmpty(mn, "content"),
                        Json.strOrEmpty(mn, "thinking")));
            }
            list.add(conv);
        }
        return list;
    }

    public static void save(Context c, ArrayList<Conv> conversations) {
        try {
            prefs(c).edit().putString(KEY_CONVERSATIONS, encode(conversations)).commit();
        } catch (Throwable ignored) {
        }
    }

    private static String encode(List<Conv> conversations) {
        StringBuilder b = new StringBuilder(1024);
        b.append('[');
        for (int k = 0; k < conversations.size(); k++) {
            if (k > 0) {
                b.append(',');
            }
            Conv conv = conversations.get(k);
            b.append("{\"id\":").append(conv.id);
            b.append(",\"title\":").append(Json.quote(conv.title));
            b.append(",\"updatedAt\":").append(conv.updatedAt);
            b.append(",\"messages\":[");
            for (int j = 0; j < conv.messages.size(); j++) {
                if (j > 0) {
                    b.append(',');
                }
                Msg m = conv.messages.get(j);
                b.append("{\"role\":").append(Json.quote(m.role));
                b.append(",\"content\":").append(Json.quote(m.content));
                b.append(",\"thinking\":").append(Json.quote(m.thinking));
                b.append('}');
            }
            b.append("]}");
        }
        b.append(']');
        return b.toString();
    }

    // ==================== 查询辅助 ====================

    public static Conv findById(List<Conv> list, long id) {
        for (int k = 0; k < list.size(); k++) {
            if (list.get(k).id == id) {
                return list.get(k);
            }
        }
        return null;
    }

    public static long nextId(List<Conv> list) {
        long max = 0L;
        for (int k = 0; k < list.size(); k++) {
            if (list.get(k).id > max) {
                max = list.get(k).id;
            }
        }
        return max + 1L;
    }

    /** 判断时间戳落在哪个分组里。 */
    public static int sectionOf(long timestamp, long now) {
        long todayStart = startOfDay(now, 0);
        if (timestamp >= todayStart) {
            return SECTION_TODAY;
        }
        if (timestamp >= startOfDay(now, -1)) {
            return SECTION_YESTERDAY;
        }
        if (timestamp >= startOfDay(now, -7)) {
            return SECTION_LAST_7_DAYS;
        }
        if (timestamp >= startOfDay(now, -30)) {
            return SECTION_LAST_30_DAYS;
        }
        return SECTION_EARLIER;
    }

    /** 取某天零点的时间戳（按本地时区）。 */
    private static long startOfDay(long timestamp, int dayOffset) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        cal.add(java.util.Calendar.DAY_OF_YEAR, dayOffset);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }
}
