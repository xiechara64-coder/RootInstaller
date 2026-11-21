package com.dsh.rootinstaller;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewConfiguration;

/**
 * 次级页面的 ActionBar 统一处理：显示标题 + 左上角返回按钮。
 *
 * 为什么单独开一个类而不是抽一个基类：
 *   DetailsActivity 继承 Activity、MainActivity 继承 ListActivity，
 *   没法用一个公共基类同时覆盖。这里只是两个小动作，
 *   用静态工具方法最省事，也避免为了复用而改动继承关系。
 */
public final class ActionBarHelper {

    private ActionBarHelper() {
    }

    /**
     * 设备有没有**实体菜单键**。
     *
     * <p>这个判断决定「菜单入口该用谁的」，因为系统的行为是按它分的：
     * <ul>
     *   <li>有实体菜单键 → Holo 主题**不**画原生溢出按钮（菜单键就是入口）</li>
     *   <li>没有实体菜单键 → Holo 主题**会**画原生溢出按钮</li>
     * </ul>
     * 所以这个值取反，正好就是「系统的溢出按钮会不会出现」。
     */
    public static boolean hasPermanentMenuKey(Context ctx) {
        try {
            // API 14 起有 ViewConfiguration.hasPermanentMenuKey()。
            // 本应用 minSdk=15，所以可以直接调。
            return ViewConfiguration.get(ctx).hasPermanentMenuKey();
        } catch (Throwable t) {
            // 拿不准时按「有菜单键」处理：
            // 这样我们会显示自己画的入口 —— 多一个入口总比一个都没有强。
            Log.w("查询实体菜单键失败，按有菜单键处理: " + t);
            return true;
        }
    }

    /**
     * 判断「系统自己的溢出菜单按钮会不会出现在 ActionBar 上」。
     *
     * <p>两种情况会不出现：设备有实体菜单键，或者 ROM 把 ActionBar 去掉了。
     * 这两种情况下都必须靠我们自绘的入口。
     */
    public static boolean systemOverflowVisible(Activity activity) {
        if (activity == null) {
            return false;
        }
        if (activity.getActionBar() == null) {
            // 没有 ActionBar，系统的溢出按钮自然也无从谈起
            return false;
        }
        return !hasPermanentMenuKey(activity);
    }

    /**
     * 按需隐藏「自绘的菜单入口」菜单项，避免和系统原生溢出按钮重复。
     *
     * <h3>为什么需要这个</h3>
     * 群友在**没有实体按键**的设备上看到右上角有**两个**三个点。
     * 原因是我们自己放了一个 {@code showAsAction="always"} 的入口项
     * （为了照顾「有实体菜单键、系统不画溢出按钮」的老设备），
     * 而这类无按键设备上系统**也会**画原生溢出按钮（因为其余菜单项都是
     * {@code "never"}），于是两个并排出现。
     *
     * <h3>做法</h3>
     * 让两者互斥、互补：
     * <ul>
     *   <li>系统会画溢出按钮 → 把自己那个藏起来，菜单由系统按钮打开</li>
     *   <li>系统不画（有实体菜单键 / 没有 ActionBar）→ 显示自己的，保证有入口</li>
     * </ul>
     * 用 {@code setVisible(false)} 而不是从菜单里移除：入口项的 id
     * （{@code menu_overflow}）在 {@code onOptionsItemSelected} 里还要用，
     * 而且那条分支在有些 ROM 上仍可能被触发，留着更稳。
     *
     * @param anchorId   自绘入口项的 id
     * @param callerName 调用方类名，仅用于日志
     */
    public static void hideOwnOverflowIfSystemShows(Activity activity, Menu menu,
                                                   int anchorId, String callerName) {
        if (menu == null) {
            return;
        }
        MenuItem anchor = menu.findItem(anchorId);
        if (anchor == null) {
            return;
        }
        boolean systemShows = systemOverflowVisible(activity);
        anchor.setVisible(!systemShows);
        Log.i("菜单入口[" + callerName + "] 实体菜单键=" + hasPermanentMenuKey(activity)
                + "，系统溢出=" + systemShows
                + "，自绘入口=" + (!systemShows ? "显示" : "隐藏（交给系统）"));
    }

    /**
     * 设置标题并打开「返回」按钮。
     *
     * @param title 标题栏文字；传 null 表示不改（保留清单里的 label）
     */
    public static void setUpButton(final Activity activity, String title, String subtitle) {
        try {
            ActionBar ab = activity.getActionBar();
            if (ab == null) {
                // 某些 ROM 或主题下没有 ActionBar，此时只能靠物理返回键
                Log.w("本页没有 ActionBar，无法提供返回按钮");
                return;
            }
            if (title != null) {
                ab.setTitle(title);
            }
            if (subtitle != null && subtitle.length() > 0) {
                ab.setSubtitle(subtitle);
            }
            // 这就是左上角那个「‹」返回箭头
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setHomeButtonEnabled(true);
        } catch (Throwable t) {
            Log.w("设置 ActionBar 返回按钮失败: " + t);
        }
    }

    /**
     * 处理返回按钮的点击。在各 Activity 的 onOptionsItemSelected 里调用。
     *
     * @return true 表示这次点击是返回按钮，已处理
     */
    public static boolean handleHome(Activity activity, MenuItem item) {
        if (item == null) {
            return false;
        }
        if (item.getItemId() == android.R.id.home) {
            activity.finish();
            return true;
        }
        return false;
    }
}
