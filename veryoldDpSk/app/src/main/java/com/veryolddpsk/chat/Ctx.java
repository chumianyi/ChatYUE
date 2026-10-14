package com.veryolddpsk.chat;

import java.util.List;
import java.util.Map;

/**
 * 上下文用量估算。
 *
 * 规则（与主应用一致）：中文按 1.5 token / 字，其余按 0.25 token / 字，上限 128K。
 */
public final class Ctx {

    private Ctx() {
    }

    /** 是否属于「中文字符」范畴。 */
    public static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)     // CJK 统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF)  // 兼容表意文字
                || (c >= 0x3000 && c <= 0x303F)  // CJK 标点
                || (c >= 0xFF00 && c <= 0xFFEF)  // 全角字符
                || (c >= 0x3040 && c <= 0x30FF)  // 日文假名
                || (c >= 0xAC00 && c <= 0xD7AF); // 韩文音节
    }

    /** 估算一段文本占用的 token 数。 */
    public static long estimate(String text) {
        if (text == null || text.length() == 0) {
            return 0L;
        }
        long cjk = 0;
        long other = 0;
        for (int k = 0; k < text.length(); k++) {
            if (isCjk(text.charAt(k))) {
                cjk++;
            } else {
                other++;
            }
        }
        return Math.round(cjk * 1.5d + other * 0.25d);
    }

    /** 估算整个消息列表占用的 token 数。 */
    public static long estimateMessages(List<Map<String, Object>> messages) {
        long total = 0L;
        if (messages == null) {
            return 0L;
        }
        for (int k = 0; k < messages.size(); k++) {
            Map<String, Object> m = messages.get(k);
            total += estimate(Json.str(m, "content"));
            total += 4L; // 每条消息的角色与分隔符开销
        }
        return total;
    }

    /** 占用百分比（0~100）。 */
    public static int percent(long tokens) {
        if (tokens <= 0) {
            return 0;
        }
        long p = tokens * 100L / App.CONTEXT_LIMIT;
        if (p < 0) {
            return 100;
        }
        return p > 100 ? 100 : (int) p;
    }

    /** 是否达到了自动压缩的阈值。 */
    public static boolean shouldCompress(long tokens) {
        return percent(tokens) >= App.COMPRESS_THRESHOLD_PERCENT;
    }
}
