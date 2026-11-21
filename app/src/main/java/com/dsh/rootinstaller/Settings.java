package com.dsh.rootinstaller;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 应用设置的读写。
 *
 * 用 SharedPreferences 存，理由：4.3 上没有任何依赖，读写是同步的、立刻生效，
 * 而且用户清应用数据时会一起清掉，行为符合预期。
 *
 * 约定：所有开关的默认值都是「不勾选」，也就是保持最保守的行为 ——
 * 不删用户的文件、不显示过多信息。需要的人自己去设置里打开。
 */
public final class Settings {

    private static final String FILE = "settings";

    /** 安装成功后删除对应路径的 APK 文件。默认关闭（删用户文件必须用户主动开）。 */
    private static final String KEY_DELETE_APK = "delete_apk_after_install";

    /** 详情页显示更多信息（文件校验 / 组件 / 对外暴露组件）。默认关闭。 */
    private static final String KEY_SHOW_MORE = "show_more_details";

    /** 权限列表的排序方式。默认按风险等级，见 MODE_PERM_RISK。 */
    private static final String KEY_PERM_SORT = "perm_sort_mode";

    // ---- 权限排序方式 ----

    /** 按风险等级从高到低：危险 → 普通 → 签名级 → 无说明。 */
    public static final int SORT_PERM_RISK = 0;
    /** 按权限名的字母顺序（看的是短名，如 CAMERA、READ_SMS）。 */
    public static final int SORT_PERM_ALPHA = 1;
    /** 保持 APK 里声明的原始顺序。 */
    public static final int SORT_PERM_DECLARED = 2;

    /** 各排序方式在界面上的名字，下标要和上面的常量对应。 */
    public static final String[] PERM_SORT_NAMES = new String[] {
            "按风险等级（默认）",
            "按名称字母顺序",
            "按安装包声明顺序"
    };

    // ---- 安装包列表排序方式 ----
    //
    // 原本列表顺序是隐式的：内部存储 → SD 卡，卷内再按目录遍历顺序 ——
    // 那个顺序对用户不可预期也不可控制，所以补上这个设置。

    /** 按修改时间倒序（最近的在前）。默认。 */
    public static final int SORT_APK_TIME = 0;
    /** 按名称（显示名优先，没有则用文件名）。 */
    public static final int SORT_APK_NAME = 1;
    /** 按体积从大到小。 */
    public static final int SORT_APK_SIZE = 2;
    /** 按权限风险从高到低。 */
    public static final int SORT_APK_RISK = 3;

    /** 各排序方式在界面上的名字，下标要和上面的常量对应。 */
    public static final String[] APK_SORT_NAMES = new String[] {
            "按时间（最近在前，默认）",
            "按名称",
            "按体积（大的在前）",
            "按权限风险（高的在前）"
    };

    private static final String KEY_APK_SORT = "apk_sort_mode";

    private Settings() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isDeleteApkAfterInstall(Context ctx) {
        try {
            return sp(ctx).getBoolean(KEY_DELETE_APK, false);
        } catch (Throwable t) {
            Log.w("读取设置失败: " + t);
            return false;
        }
    }

    public static void setDeleteApkAfterInstall(Context ctx, boolean v) {
        try {
            sp(ctx).edit().putBoolean(KEY_DELETE_APK, v).apply();
        } catch (Throwable t) {
            Log.w("写入设置失败: " + t);
        }
    }

    public static boolean isShowMoreDetails(Context ctx) {
        try {
            return sp(ctx).getBoolean(KEY_SHOW_MORE, false);
        } catch (Throwable t) {
            Log.w("读取设置失败: " + t);
            return false;
        }
    }

    public static void setShowMoreDetails(Context ctx, boolean v) {
        try {
            sp(ctx).edit().putBoolean(KEY_SHOW_MORE, v).apply();
        } catch (Throwable t) {
            Log.w("写入设置失败: " + t);
        }
    }

    /** 权限列表排序方式。读不到或值越界时回退到「按风险等级」。 */
    public static int getPermSortMode(Context ctx) {
        try {
            int v = sp(ctx).getInt(KEY_PERM_SORT, SORT_PERM_RISK);
            if (v < 0 || v >= PERM_SORT_NAMES.length) {
                return SORT_PERM_RISK;
            }
            return v;
        } catch (Throwable t) {
            Log.w("读取设置失败: " + t);
            return SORT_PERM_RISK;
        }
    }

    public static void setPermSortMode(Context ctx, int mode) {
        try {
            sp(ctx).edit().putInt(KEY_PERM_SORT, mode).apply();
        } catch (Throwable t) {
            Log.w("写入设置失败: " + t);
        }
    }

    /** 安装包列表排序方式。读不到或值越界时回退到「按时间」。 */
    public static int getApkSortMode(Context ctx) {
        try {
            int v = sp(ctx).getInt(KEY_APK_SORT, SORT_APK_TIME);
            if (v < 0 || v >= APK_SORT_NAMES.length) {
                return SORT_APK_TIME;
            }
            return v;
        } catch (Throwable t) {
            Log.w("读取设置失败: " + t);
            return SORT_APK_TIME;
        }
    }

    public static void setApkSortMode(Context ctx, int mode) {
        try {
            sp(ctx).edit().putInt(KEY_APK_SORT, mode).apply();
        } catch (Throwable t) {
            Log.w("写入设置失败: " + t);
        }
    }
}
