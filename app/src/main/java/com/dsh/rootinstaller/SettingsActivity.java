package com.dsh.rootinstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 设置页。
 *
 * 从主界面右上角的溢出菜单进入（见 res/menu/main.xml）。
 *
 * 视图全部用代码构建，没有单独的布局文件。这样做的理由：
 *   · 这一页结构简单（若干开关 + 一个关于入口），XML 反而更啰嗦；
 *   · 开关的文案和说明需要跟着状态变（比如「安装后删除」打开时要把
 *     真实路径显示给用户看），代码里控制更直接。
 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 左上角返回按钮（本页只有这一个入口，返回就是回主界面）
        ActionBarHelper.setUpButton(this, getString(R.string.title_settings), null);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF5F5F5);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        setContentView(scroll);

        // 页面各节的顺序规则：按「功能加入的先后」排，关于固定压在最后。
        //
        //   安装行为   最早有的设置
        //   显示       与安装行为同一批加入
        //   权限列表   后来加的（权限排序方式）
        //   关于       纯展示信息，永远放最底部
        //
        // 这样用户升级后新出现的设置项总是排在熟悉的那几项后面，不用重新找。

        // ---------- 安装行为 ----------
        root.addView(sectionTitle("安装行为"));

        final LinearLayout deleteApkBox = makeCheckBox(
                "安装成功后删除安装包",
                "勾选后，使用本应用安装成功时会删除该 APK 文件本身。\n"
                        + "默认不勾选 —— 删文件是不可逆操作，需要你自己决定。",
                Settings.isDeleteApkAfterInstall(this));
        final CheckBox deleteApk = checkBoxOf(deleteApkBox);
        if (deleteApk != null) {
            deleteApk.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Settings.setDeleteApkAfterInstall(SettingsActivity.this,
                            deleteApk.isChecked());
                    toast(deleteApk.isChecked()
                            ? "已开启：安装成功后删除安装包"
                            : "已关闭：安装包会保留");
                }
            });
        }
        root.addView(deleteApkBox);

        // ---------- 显示 ----------
        root.addView(sectionTitle("显示"));
        final LinearLayout showMoreBox = makeCheckBox(
                "安装页显示更多信息",
                "不勾选（推荐）：只显示基本信息、安装状态对比、申请的权限。\n"
                        + "勾选后额外显示：文件校验值、包含的组件、对外暴露的组件。",
                Settings.isShowMoreDetails(this));
        final CheckBox showMore = checkBoxOf(showMoreBox);
        if (showMore != null) {
            showMore.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Settings.setShowMoreDetails(SettingsActivity.this, showMore.isChecked());
                    toast(showMore.isChecked()
                            ? "已开启：安装页显示完整信息"
                            : "已关闭：安装页只显示常用信息");
                }
            });
        }
        root.addView(showMoreBox);

        // ---------- 安装包列表 ----------
        //
        // 安装包列表的排序方式。原本这个顺序是隐式的（内部存储 → SD 卡，
        // 卷内按目录遍历顺序），用户既看不出来也没法改，所以补上这个设置。
        root.addView(sectionTitle("安装包列表"));

        final TextView apkSortValue = new TextView(this);
        final LinearLayout apkSortEntry = makeEntry("排序方式", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showApkSortDialog();
            }
        });
        apkSortValue.setTextSize(12f);
        apkSortValue.setTextColor(0xFF757575);
        apkSortValue.setPadding(0, dp(4), 0, 0);
        apkSortValue.setText(Settings.APK_SORT_NAMES[Settings.getApkSortMode(this)]);
        apkSortEntry.addView(apkSortValue);
        root.addView(apkSortEntry);

        // ---------- 权限列表 ----------
        //
        // 单独开一节，因为这一项不是开关而是「多选一」，
        // 点击后弹列表选择，比放一个下拉框在 4.3 上更省事也更符合老系统的观感。
        root.addView(sectionTitle("权限列表"));

        final TextView permSortValue = new TextView(this);
        final LinearLayout permSortEntry = makeEntry("排序方式", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPermSortDialog();
            }
        });
        // 把当前选择显示在条目下方，用户不用点进去就知道现在是哪种排序
        permSortValue.setTextSize(12f);
        permSortValue.setTextColor(0xFF757575);
        permSortValue.setPadding(0, dp(4), 0, 0);
        permSortValue.setText(Settings.PERM_SORT_NAMES[Settings.getPermSortMode(this)]);
        permSortEntry.addView(permSortValue);
        root.addView(permSortEntry);

        // ---------- 关于 ----------
        //
        // 固定放在最底部。这一段是纯展示信息（不是可改的设置），
        // 夹在设置项中间会打断「从上往下依次配置」的阅读节奏。
        root.addView(sectionTitle("关于"));

        root.addView(makeEntry("关于 RootInstaller", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAbout();
            }
        }));

        root.addView(makeEntry("包名：com.dsh.rootinstaller", null));

        // 关于的最后补一句环境信息，出问题时方便用户直接口述
        TextView env = new TextView(this);
        env.setText("运行环境：" + ApkParser.deviceSummary());
        env.setTextSize(11f);
        env.setTextColor(0xFF9E9E9E);
        int pad = dp(16);
        env.setPadding(pad, dp(4), pad, pad);
        root.addView(env);
    }

    /**
     * 安装包列表排序方式的选择对话框。
     *
     * 和权限排序一样，选完立刻写入设置；主界面在 onResume 时比对，
     * 发现变了就地对现有列表重排（不重新扫描）。
     */
    private void showApkSortDialog() {
        final int current = Settings.getApkSortMode(this);
        new android.app.AlertDialog.Builder(this)
                .setTitle("安装包列表排序")
                .setSingleChoiceItems(Settings.APK_SORT_NAMES, current,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                Settings.setApkSortMode(SettingsActivity.this, which);
                                toast("已切换为：" + Settings.APK_SORT_NAMES[which]);
                                d.dismiss();
                                recreate();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 权限排序方式的选择对话框。
     *
     * 选完立刻写入设置。详情页是在 onResume 时重新读设置的，
     * 所以返回安装页后列表顺序会马上跟着变。
     */
    private void showPermSortDialog() {
        final int current = Settings.getPermSortMode(this);
        new android.app.AlertDialog.Builder(this)
                .setTitle("权限排序方式")
                .setSingleChoiceItems(Settings.PERM_SORT_NAMES, current,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                Settings.setPermSortMode(SettingsActivity.this, which);
                                toast("已切换为：" + Settings.PERM_SORT_NAMES[which]);
                                d.dismiss();
                                // 重建本页，让「排序方式」下面那行说明文字同步刷新
                                recreate();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------
    // 视图构建小工具
    // ------------------------------------------------------------------

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(0xFF1976D2);
        tv.setAllCaps(false);
        int p = dp(16);
        tv.setPadding(p, dp(18), p, dp(6));
        return tv;
    }

    /**
     * 一个带标题和说明的开关行。
     *
     * 说明文字刻意放在复选框**下面单独一行**，而不是塞进 CheckBox 的 text：
     * 复选框的 text 换行成多行后，左边的方框会垂直居中到多行文本中间，
     * 看起来像错位，观感很差。
     *
     * 返回的是外层容器，但复选框通过 out 参数带出来供调用方挂监听。
     */
    private LinearLayout makeCheckBox(String title, String description, boolean checked) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);

        CheckBox cb = new CheckBox(this);
        cb.setText(title);
        cb.setTextSize(15f);
        cb.setChecked(checked);
        cb.setTag("cb");
        int p = dp(16);
        cb.setPadding(p, dp(12), p, dp(2));
        box.addView(cb);

        if (description != null && description.length() > 0) {
            TextView desc = new TextView(this);
            desc.setText(description);
            desc.setTextSize(12f);
            desc.setTextColor(0xFF757575);
            desc.setPadding(p, 0, p, dp(12));
            box.addView(desc);
        }
        return box;
    }

    /** 从 makeCheckBox 返回的容器里取出那个 CheckBox。 */
    private CheckBox checkBoxOf(LinearLayout box) {
        for (int i = 0; i < box.getChildCount(); i++) {
            View c = box.getChildAt(i);
            if (c instanceof CheckBox) {
                return (CheckBox) c;
            }
        }
        return null;
    }

    /** 带说明文字的可点击条目。 */
    private LinearLayout makeEntry(String title, View.OnClickListener listener) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);
        int p = dp(16);
        box.setPadding(p, dp(14), p, dp(14));

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(15f);
        tv.setTextColor(listener != null ? 0xFF1976D2 : 0xFF616161);
        box.addView(tv);

        if (listener != null) {
            box.setOnClickListener(listener);
        }
        return box;
    }

    // ------------------------------------------------------------------
    // 关于
    // ------------------------------------------------------------------

    /** 关于对话框：图标 + 名称 + 版本号 + 一些环境信息。 */
    private void showAbout() {
        String versionName = "?";
        int versionCode = -1;
        Drawable icon = null;
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            versionName = pi.versionName;
            versionCode = pi.versionCode;
            icon = pi.applicationInfo.loadIcon(getPackageManager());
        } catch (Throwable t) {
            Log.w("读取自身包信息失败: " + t);
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, p, p, p);

        // 顶部一行：图标 + 名称/版本
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);

        ImageView iv = new ImageView(this);
        if (icon != null) {
            iv.setImageDrawable(icon);
        } else {
            iv.setImageResource(R.drawable.ic_launcher);
        }
        int iconSize = dp(56);
        head.addView(iv, new LinearLayout.LayoutParams(iconSize, iconSize));

        LinearLayout nameBox = new LinearLayout(this);
        nameBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(14);
        nameBox.setLayoutParams(nlp);

        TextView name = new TextView(this);
        name.setText(getString(R.string.app_name));
        name.setTextSize(19f);
        name.setTextColor(0xFF212121);
        nameBox.addView(name);

        TextView ver = new TextView(this);
        ver.setText("版本 " + versionName + "（versionCode " + versionCode + "）");
        ver.setTextSize(13f);
        ver.setTextColor(0xFF757575);
        nameBox.addView(ver);

        head.addView(nameBox);
        box.addView(head);

        // 下面一段说明
        TextView desc = new TextView(this);
        desc.setText("\n一个面向 Android 4.3 的 APK 安装器。\n"
                + "通过 root 权限调用 pm install 安装，"
                + "并在安装前展示系统安装器不会告诉你的信息："
                + "包名、版本、签名、升级/降级判定，以及每条权限的中文含义。\n\n"
                + "设备：" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "系统：Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "ROM：" + Build.DISPLAY);
        desc.setTextSize(13f);
        desc.setTextColor(0xFF424242);
        desc.setPadding(0, dp(14), 0, 0);
        box.addView(desc);

        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ------------------------------------------------------------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (ActionBarHelper.handleHome(this, item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toast(String msg) {
        try {
            android.widget.Toast.makeText(this, msg,
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    /** 供外部（比如菜单）打开本页。 */
    public static void open(Activity from) {
        from.startActivity(new Intent(from, SettingsActivity.class));
    }
}
