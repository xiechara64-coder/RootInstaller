package com.dsh.rootinstaller;

/**
 * 极小的日志封装。
 *
 * Android 4.3 机器上跑 adb logcat 很方便，所以所有关键流程都打日志。
 * 统一 TAG 便于过滤：adb logcat -s RootInstaller
 *
 * 注意：类名故意叫 Log，和 android.util.Log 同名，所以内部必须写全限定名。
 */
public final class Log {

    public static final String TAG = "RootInstaller";

    private Log() {
    }

    public static void i(String msg) {
        println("I", msg);
    }

    public static void w(String msg) {
        println("W", msg);
    }

    public static void e(String msg) {
        println("E", msg);
    }

    public static void e(String msg, Throwable t) {
        println("E", msg + " -> " + t);
        if (t != null) {
            t.printStackTrace();
        }
    }

    private static void println(String level, String msg) {
        // 先走 logcat
        try {
            if ("I".equals(level)) {
                android.util.Log.i(TAG, msg);
            } else if ("W".equals(level)) {
                android.util.Log.w(TAG, msg);
            } else {
                android.util.Log.e(TAG, msg);
            }
        } catch (Throwable ignored) {
            // 少数定制 ROM 在早期启动阶段 Log 不可用，忽略即可
        }
        // 再走标准输出，便于从 shell 侧观察
        System.out.println(level + "/" + TAG + ": " + msg);
    }
}
