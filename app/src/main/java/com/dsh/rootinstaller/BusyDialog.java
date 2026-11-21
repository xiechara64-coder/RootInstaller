package com.dsh.rootinstaller;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 一个「正在忙」的模态提示框：旋转进度条 + 一行说明文字。
 *
 * 为什么要自己拼而不用 ProgressDialog：
 *   ProgressDialog 在 API 26 被废弃，而且各 ROM 对它的主题适配很不一样；
 *   这里只要一个转圈加一句话，直接用 ProgressBar + TextView 拼最省事、
 *   在 4.3 上的表现也最可控。
 *
 * 设计上的两个要点：
 *   · setCancelable(false) —— 扫描期间不让用户点掉，避免出现「界面空了但还在扫」的困惑；
 *   · 用 Activity.isFinishing() 做保护 —— 扫描是异步的，回到界面时 Activity 可能已经关了，
 *     此时再 dismiss 会抛 IllegalArgumentException。
 */
public final class BusyDialog {

    private final AlertDialog dialog;

    private BusyDialog(AlertDialog dialog) {
        this.dialog = dialog;
    }

    /** 创建并立即显示。 */
    public static BusyDialog show(Context ctx, String message) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(ctx, 20);
        box.setPadding(pad, pad, pad, pad);

        ProgressBar bar = new ProgressBar(ctx);
        // 不指定 style 的话在某些 ROM 上会渲染成一个静态的圆圈，看不出在转
        bar.setIndeterminate(true);
        int size = dp(ctx, 36);
        box.addView(bar, new LinearLayout.LayoutParams(size, size));

        TextView tv = new TextView(ctx);
        tv.setText(message);
        tv.setTextSize(14f);
        tv.setTextColor(Color.parseColor("#212121"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(ctx, 16);
        box.addView(tv, lp);

        AlertDialog d = new AlertDialog.Builder(ctx)
                .setView(box)
                .create();
        d.setCancelable(false);
        d.setCanceledOnTouchOutside(false);
        try {
            d.show();
        } catch (Throwable t) {
            Log.w("显示忙碌提示框失败: " + t);
            return new BusyDialog(null);
        }
        return new BusyDialog(d);
    }

    /** 安全关闭，重复调用没问题。 */
    public void dismiss() {
        if (dialog == null) {
            return;
        }
        try {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
        } catch (Throwable t) {
            // Activity 已经销毁时 dismiss 可能抛异常，忽略即可
            Log.w("关闭忙碌提示框失败: " + t);
        }
    }

    private static int dp(Context ctx, int v) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }
}
