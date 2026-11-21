package cn.xanderye.android.jdck.util;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 轻量运行日志：同时写 logcat 和 App 私有目录下的文本文件，方便回溯后台自动登录失败的原因。
 * 只保留当前日志和一个轮转备份（超过 256KB 转成 .1），不会无限增长。
 */
public final class LogUtil {

    private static final String TAG = "JdCk";

    private static final String FILE_NAME = "auto_login.log";

    private static final long MAX_BYTES = 256 * 1024L;

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();

    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA);

    private static File logFile;

    private LogUtil() {
    }

    public static void init(Context context) {
        if (logFile != null) {
            return;
        }
        File dir = context.getExternalFilesDir("log");
        if (dir == null) {
            dir = new File(context.getFilesDir(), "log");
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        logFile = new File(dir, FILE_NAME);
        i("日志启动，路径 " + logFile.getAbsolutePath());
    }

    public static File file() {
        return logFile;
    }

    public static void i(String msg) {
        write("I", msg, null);
    }

    public static void e(String msg) {
        write("E", msg, null);
    }

    public static void e(String msg, Throwable t) {
        write("E", msg, t);
    }

    /**
     * 敏感值脱敏，只留前 6 位，日志里不出现完整 pt_key / cookie
     */
    public static String mask(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= 6) {
            return "***(" + value.length() + ")";
        }
        return value.substring(0, 6) + "***(" + value.length() + ")";
    }

    public static String read() {
        File f = logFile;
        if (f == null) {
            return "日志未初始化";
        }
        // 等异步写入落盘
        try {
            WRITER.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder();
        File old = new File(f.getParentFile(), FILE_NAME + ".1");
        if (old.exists()) {
            sb.append(readFile(old));
        }
        sb.append(readFile(f));
        return sb.length() == 0 ? "暂无日志" : sb.toString();
    }

    public static void clear() {
        File f = logFile;
        if (f == null) {
            return;
        }
        f.delete();
        new File(f.getParentFile(), FILE_NAME + ".1").delete();
        WRITER.execute(() -> append(f, "日志已清空\n"));
    }

    private static String readFile(File f) {
        if (!f.exists()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException ignored) {
        }
        return sb.toString();
    }

    private static void write(String level, String msg, Throwable t) {
        String detail = msg + (t == null ? "" : " | " + Log.getStackTraceString(t));
        if ("E".equals(level)) {
            Log.e(TAG, detail);
        } else {
            Log.i(TAG, detail);
        }
        File f = logFile;
        if (f == null) {
            return;
        }
        String line = TIME.format(new Date()) + " [" + level + "] " + detail + "\n";
        WRITER.execute(() -> append(f, line));
    }

    private static void append(File f, String text) {
        try {
            if (f.length() > MAX_BYTES) {
                File old = new File(f.getParentFile(), FILE_NAME + ".1");
                if (old.exists()) {
                    old.delete();
                }
                f.renameTo(old);
            }
            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                writer.write(text);
                writer.flush();
            }
        } catch (IOException ignored) {
        }
    }
}
