package com.dsh.rootinstaller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * 「被其它应用调用」的第二个入口。
 *
 * 为什么光有 Activity 的 intent-filter 还不够？
 *   因为有些应用（尤其是老应用、以及用 adb/反射直接发广播的应用）
 *   会直接 sendBroadcast(ACTION_INSTALL_PACKAGE)，而不是 startActivity。
 *   这种情况下只有注册了广播接收器才能收到。
 *
 * 收到的广播里通常不会带文件路径（广播传 Uri 的写法五花八门），
 * 所以这里的处理原则是：能拿到路径就跳详情页，拿不到就提示用户手动选择，
 * 绝对不要静默失败 —— 用户以为点了安装却什么都没发生是最糟糕的体验。
 */
public class InstallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        Log.i("InstallReceiver 收到广播: " + action);

        String path = IntentPaths.resolve(context, intent);
        if (path != null) {
            Intent i = new Intent(context, DetailsActivity.class);
            i.putExtra(DetailsActivity.EXTRA_APK, path);
            i.putExtra(DetailsActivity.EXTRA_FROM_EXTERNAL, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return;
        }

        String raw = intent.getData() == null ? "" : intent.getData().toString();
        Toast.makeText(context,
                "收到安装请求，但没能读到文件路径。\n请打开 RootInstaller 手动选择安装包。"
                        + (raw.length() > 0 ? "\n(" + raw + ")" : ""),
                Toast.LENGTH_LONG).show();

        // 顺便把界面带到前台，用户直接就能选文件
        try {
            Intent open = new Intent(context, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(open);
        } catch (Throwable t) {
            Log.w("打开主界面失败: " + t);
        }
    }
}
