package com.dsh.rootinstaller;

import android.app.Application;

/**
 * 应用入口。
 *
 * 这里唯一但很关键的作用：**在最早的时机安装全局崩溃记录器**。
 *
 * 曾经把这个安装动作放在 MainActivity.onCreate 里，结果是：
 * 如果应用不是从 MainActivity 启动的（例如被别的应用直接调起安装流程、
 * 或者 adb 直接打开某个 Activity），崩溃处理器根本没注册，崩溃日志就丢了。
 * Application.onCreate 在任何组件之前执行，是唯一可靠的时机。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashReporter.install(this);
        Log.i("App 启动，崩溃记录器已安装");
    }
}
