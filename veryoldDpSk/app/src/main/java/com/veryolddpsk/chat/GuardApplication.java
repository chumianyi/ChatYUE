package com.veryolddpsk.chat;

import android.app.Application;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 进程级兜底。
 *
 * 任何漏网的未捕获异常都会先留一份现场到私有目录，再交给系统默认处理。
 * 这样即便在老设备上遇到预料之外的情况，也能拿到线索而不是一片空白。
 */
public class GuardApplication extends Application {

    public static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            final Thread.UncaughtExceptionHandler fallback =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread thread, Throwable error) {
                    try {
                        save(thread, error);
                    } catch (Throwable ignored) {
                        // 记录崩溃本身失败时不能再次抛出
                    }
                    if (fallback != null) {
                        fallback.uncaughtException(thread, error);
                    }
                }
            });
        } catch (Throwable ignored) {
            // 设置兜底失败不影响正常启动
        }
    }

    private void save(Thread thread, Throwable error) {
        StringBuilder b = new StringBuilder();
        b.append("thread=").append(thread == null ? "?" : thread.getName()).append('\n');
        b.append("time=").append(System.currentTimeMillis()).append('\n');
        if (error != null) {
            b.append(error.getClass().getName()).append(": ").append(error.getMessage()).append('\n');
            StackTraceElement[] stack = error.getStackTrace();
            for (int k = 0; k < stack.length && k < 40; k++) {
                b.append("    at ").append(stack[k].toString()).append('\n');
            }
        }
        FileOutputStream out = null;
        try {
            out = openFileOutput(CRASH_FILE, MODE_PRIVATE);
            out.write(b.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            Util.closeQuietly(out);
        }
    }

    /** 读取上一次的崩溃记录。 */
    public static String readLastCrash(Application app) {
        try {
            return Util.readTextFile(new File(app.getFilesDir(), CRASH_FILE));
        } catch (Throwable t) {
            return null;
        }
    }
}
