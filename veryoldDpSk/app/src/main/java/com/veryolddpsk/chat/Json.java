package com.veryolddpsk.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析与生成。
 *
 * 刻意不依赖 org.json —— 该实现在不同年代的 ROM 上字段与方法差异较大，
 * 自己实现可以把行为固定住，避免在老设备上出现意料之外的异常。
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String text) {
        this.s = text;
    }

    // ==================== 对外接口 ====================

    /** 解析任意 JSON 文本，失败返回 null。 */
    public static Object parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            Json p = new Json(text);
            p.skipWs();
            return p.readValue();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解析成对象；失败返回空 Map（不会是 null，调用方无需判空）。 */
    public static Map<String, Object> parseObject(String text) {
        return asMap(parse(text));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        return new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        if (o instanceof List) {
            return (List<Object>) o;
        }
        return new ArrayList<Object>();
    }

    /** 取字符串字段，缺失或非字符串时返回 null。 */
    public static String str(Map<String, Object> m, String key) {
        if (m == null) {
            return null;
        }
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    /** 取字符串字段，缺失时返回空串。 */
    public static String strOrEmpty(Map<String, Object> m, String key) {
        String v = str(m, key);
        return v == null ? "" : v;
    }

    /** 取嵌套对象，缺失时返回空 Map。 */
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        return m == null ? new LinkedHashMap<String, Object>() : asMap(m.get(key));
    }

    /** 取数组，缺失时返回空 List。 */
    public static List<Object> arr(Map<String, Object> m, String key) {
        return m == null ? new ArrayList<Object>() : asList(m.get(key));
    }

    /** 取长整型字段。 */
    public static long num(Map<String, Object> m, String key, long fallback) {
        if (m == null) {
            return fallback;
        }
        Object v = m.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong((String) v);
            } catch (Throwable ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** 生成 JSON 字符串字面量（含首尾引号）。 */
    public static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    /** 转义 JSON 字符串内部字符。 */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(value.length() + 16);
        for (int k = 0; k < value.length(); k++) {
            char c = value.charAt(k);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                case '\b':
                    b.append("\\b");
                    break;
                case '\f':
                    b.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        b.append("\\u");
                        for (int pad = hex.length(); pad < 4; pad++) {
                            b.append('0');
                        }
                        b.append(hex);
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    // ==================== 解析实现 ====================

    private void skipWs() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private void expect(String literal) {
        if (s.startsWith(literal, i)) {
            i += literal.length();
        } else {
            i++;
        }
    }

    private Object readValue() {
        skipWs();
        char c = peek();
        if (c == '{') {
            return readObject();
        }
        if (c == '[') {
            return readArray();
        }
        if (c == '"') {
            return readString();
        }
        if (c == 't') {
            expect("true");
            return Boolean.TRUE;
        }
        if (c == 'f') {
            expect("false");
            return Boolean.FALSE;
        }
        if (c == 'n') {
            expect("null");
            return null;
        }
        return readNumber();
    }

    private Map<String, Object> readObject() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++; // '{'
        skipWs();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (i < s.length()) {
            skipWs();
            if (peek() != '"') {
                break;
            }
            String key = readString();
            skipWs();
            if (peek() == ':') {
                i++;
            }
            Object value = readValue();
            m.put(key, value);
            skipWs();
            char c = peek();
            if (c == ',') {
                i++;
                continue;
            }
            if (c == '}') {
                i++;
                break;
            }
            break;
        }
        return m;
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<Object>();
        i++; // '['
        skipWs();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (i < s.length()) {
            Object value = readValue();
            list.add(value);
            skipWs();
            char c = peek();
            if (c == ',') {
                i++;
                continue;
            }
            if (c == ']') {
                i++;
                break;
            }
            break;
        }
        return list;
    }

    private String readString() {
        i++; // opening quote
        StringBuilder b = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) {
                break;
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"':
                    b.append('"');
                    break;
                case '\\':
                    b.append('\\');
                    break;
                case '/':
                    b.append('/');
                    break;
                case 'b':
                    b.append('\b');
                    break;
                case 'f':
                    b.append('\f');
                    break;
                case 'n':
                    b.append('\n');
                    break;
                case 'r':
                    b.append('\r');
                    break;
                case 't':
                    b.append('\t');
                    break;
                case 'u':
                    if (i + 4 <= s.length()) {
                        try {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (Throwable ignored) {
                            // 转义不合法时直接跳过
                        }
                        i += 4;
                    }
                    break;
                default:
                    b.append(e);
            }
        }
        return b.toString();
    }

    private Object readNumber() {
        int start = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            boolean part = (c >= '0' && c <= '9')
                    || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E';
            if (!part) {
                break;
            }
            i++;
        }
        String raw = s.substring(start, i);
        if (raw.length() == 0) {
            i++;
            return null;
        }
        try {
            if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                return Long.valueOf(raw);
            }
            return Double.valueOf(raw);
        } catch (Throwable t) {
            return null;
        }
    }
}
