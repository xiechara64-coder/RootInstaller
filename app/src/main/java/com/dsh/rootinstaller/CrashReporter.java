package com.dsh.rootinstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.text.method.ScrollingMovementMethod;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.Thread.UncaughtExceptionHandler;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃记录器。
 *
 * 背景：这个应用在老设备上出过一次「安装过程中突然闪退」，但 Android 4.3
 * 上取不到 /dev/log/crash，用户也没法抓 logcat。所以这里自己实现一套：
 *
 *   1. 注册为全局未捕获异常处理器；
 *   2. 崩溃时把完整堆栈 + 设备信息 + 当时的运行状态写进私有文件；
 *   3. 下次启动时发现崩溃记录，直接弹窗展示，并提供「一键复制」，方便发给开发者；
 *   4. 复制后（或用户选择忽略时）删除记录，避免反复弹。
 *
 * 老系统上「写文件」这件事本身也可能失败（比如存储满），
 * 所以这里全程吞异常 —— 崩溃处理器自己再崩一次会导致更难看的表现。
 */
public final class CrashReporter {

    private static final String DIR = "crash";
    private static final String FILE = "last-crash.txt";

    /** 崩溃发生前的一些现场信息，由各处调用 setContext 补充。 */
    private static final StringBuilder CONTEXT = new StringBuilder();

    /** 防止重复安装导致处理器层层嵌套（App 装一次，Activity 里可能又调一次）。 */
    private static volatile boolean installed;

    private CrashReporter() {
    }

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    /**
     * 安装全局未捕获异常处理器。应该在 Application.onCreate 里调用，
     * 这样无论从哪个组件启动都能捕获到崩溃。
     * 重复调用是安全的（会被忽略）。
     */
    public static synchronized void install(final Context appContext) {
        if (installed) {
            return;
        }
        installed = true;

        final Context ctx = appContext.getApplicationContext();
        final UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                try {
                    writeCrash(ctx, thread, ex);
                } catch (Throwable ignored) {
                    // 记录失败也不能让原来的崩溃被掩盖
                }
                if (previous != null) {
                    previous.uncaughtException(thread, ex);
                }
            }
        });
        Log.i("崩溃记录器已安装");
    }

    /**
     * 记录「当前正在做什么」，崩溃日志里会带出来。
     * 例如正在安装哪个包、走到哪一步。这对定位问题非常关键。
     */
    public static synchronized void setContext(String key, String value) {
        note(key + " = " + value);
    }

    public static synchronized void note(String line) {
        try {
            CONTEXT.append(line).append('\n');
            // 防止无限增长
            if (CONTEXT.length() > 4000) {
                CONTEXT.delete(0, CONTEXT.length() - 4000);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    private static void writeCrash(Context ctx, Thread thread, Throwable ex) {
        File dir = new File(ctx.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File f = new File(dir, FILE);

        Writer sw = null;
        try {
            FileOutputStream fos = new FileOutputStream(f, false);
            sw = new OutputStreamWriter(fos, "UTF-8");

            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            sw.write("========== RootInstaller 崩溃报告 ==========\n");
            sw.write("时间: " + fmt.format(new Date()) + "\n");
            sw.write("线程: " + (thread == null ? "?" : thread.getName()) + "\n");
            sw.write("应用版本: " + versionName(ctx) + " (versionCode "
                    + versionCode(ctx) + ")\n");
            sw.write("设备: " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
            sw.write("系统: Android " + Build.VERSION.RELEASE
                    + " (API " + Build.VERSION.SDK_INT + ")\n");
            sw.write("ROM: " + Build.DISPLAY + "\n");
            // 注意：Build.SUPPORTED_ABIS 是 API 21 才有的，这里目标 API 18，
            // 所以只能用 CPU_ABI / CPU_ABI2（已废弃但在 4.3 上可用）。
            sw.write("ABI: " + Build.CPU_ABI
                    + (Build.CPU_ABI2 == null ? "" : ", " + Build.CPU_ABI2) + "\n");
            sw.write("------------------------------------------\n");
            sw.write("崩溃前运行状态:\n");
            String notes = CONTEXT.toString();
            sw.write(notes.length() == 0 ? "(无记录)\n" : notes);
            sw.write("------------------------------------------\n");
            sw.write("异常类型: " + (ex == null ? "?" : ex.getClass().getName()) + "\n");
            sw.write("异常信息: " + (ex == null ? "?" : String.valueOf(ex.getMessage())) + "\n");
            sw.write("------------------------------------------\n");
            sw.write("完整堆栈:\n");

            if (ex != null) {
                StringWriter buffer = new StringWriter();
                PrintWriter pw = new PrintWriter(buffer);
                ex.printStackTrace(pw);
                pw.flush();
                sw.write(buffer.toString());
                sw.write("\n");
                // 打印 cause 链，有些崩溃真正的原因藏在深层 cause 里
                Throwable cause = ex.getCause();
                int depth = 0;
                while (cause != null && depth < 10) {
                    sw.write("\n--- cause[" + depth + "] ---\n");
                    cause.printStackTrace(pw = new PrintWriter(buffer = new StringWriter()));
                    pw.flush();
                    sw.write(buffer.toString());
                    cause = cause.getCause();
                    depth++;
                }
            }
            sw.write("==========================================\n");
            sw.flush();
            Log.e("崩溃已记录到 " + f.getAbsolutePath());
        } catch (Throwable t) {
            // 记录崩溃的过程本身失败也不能再抛出去，否则会掩盖原始崩溃
            Log.e("写入崩溃记录失败: " + t);
        } finally {
            closeQuietly(sw);
        }
    }

    private static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static int versionCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }


    // ------------------------------------------------------------------
    // 读取 / 弹窗
    // ------------------------------------------------------------------

    /** 有没有未查看过的崩溃记录。 */
    public static boolean hasCrash(Context ctx) {
        File f = crashFile(ctx);
        return f != null && f.isFile() && f.length() > 0;
    }

    public static String readCrash(Context ctx) {
        File f = crashFile(ctx);
        if (f == null || !f.isFile()) {
            return null;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.e("读取崩溃记录失败", t);
            return null;
        } finally {
            closeQuietly(r);
        }
    }

    public static void clear(Context ctx) {
        File f = crashFile(ctx);
        if (f != null) {
            try {
                f.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    private static File crashFile(Context ctx) {
        try {
            return new File(new File(ctx.getFilesDir(), DIR), FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 如果存在崩溃记录就弹窗展示。
     *
     * 弹窗刻意做得很朴素（纯代码构建，不用自定义布局），
     * 因为崩溃可能和资源加载有关，此时用 XML 布局反而有风险。
     */
    public static void showDialogIfAny(final Activity activity) {
        final String text = readCrash(activity);
        if (text == null || text.trim().length() == 0) {
            return;
        }

        final ScrollView scroll = new ScrollView(activity);
        final TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(10f);
        tv.setTextColor(0xFF212121);
        tv.setPadding(16, 16, 16, 16);
        // 让长堆栈可以滚动查看，否则弹窗里只能看到开头几行
        tv.setMovementMethod(new ScrollingMovementMethod());
        scroll.addView(tv);

        // 给一个固定高度，保证长内容可滚动而不是撑爆屏幕
        int height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.55f);
        new AlertDialog.Builder(activity)
                .setTitle("上次运行崩溃了")
                .setView(scroll)
                .setPositiveButton("复制崩溃信息", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        copyToClipboard(activity, text);
                    }
                })
                .setNeutralButton("关闭（保留记录）", null)
                .setNegativeButton("删除记录", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        clear(activity);
                        Toast.makeText(activity, "已删除崩溃记录", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /** 把崩溃信息放进剪贴板——Android 4.3 用旧的 ClipboardManager API。 */
    @SuppressWarnings("deprecation")
    public static void copyToClipboard(Context ctx, String text) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                Toast.makeText(ctx, "剪贴板不可用", Toast.LENGTH_SHORT).show();
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText("RootInstaller 崩溃日志", text));
            Toast.makeText(ctx, "已复制，粘贴发给开发者即可", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.e("复制到剪贴板失败", t);
            Toast.makeText(ctx, "复制失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
