package com.dsh.rootinstaller;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 存储路径工具。Android 4.x 时代的存储布局很乱，这里统一兜一下。
 */
public final class Paths {

    private Paths() {
    }

    /** 主外部存储，通常是 /storage/sdcard0 或 /mnt/sdcard。 */
    @SuppressWarnings("deprecation")
    public static String primaryExternal() {
        try {
            File f = Environment.getExternalStorageDirectory();
            if (f != null) {
                return f.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return "/sdcard";
    }

    /**
     * 第二块外部存储。
     *
     * API 19 起有 Context.getExternalFilesDirs() 能准确拿到，
     * 但那返回的是 /storage/sdcard1/Android/data/<pkg>/files 这种应用私有路径，
     * 需要往上剥掉后缀才是存储卡根目录。API 19 以下没有公开 API，只能按常见挂载点猜。
     */
    public static List<String> secondaryExternal(Context ctx) {
        List<String> out = new ArrayList<String>();

        if (Build.VERSION.SDK_INT >= 19 && ctx != null) {
            try {
                Method m = Context.class.getMethod("getExternalFilesDirs", String.class);
                Object ret = m.invoke(ctx, (String) null);
                File[] dirs = (File[]) ret;
                if (dirs != null) {
                    String pkgPath = "/Android/data/" + ctx.getPackageName();
                    for (int i = 0; i < dirs.length; i++) {
                        if (dirs[i] == null) {
                            continue;
                        }
                        String p = dirs[i].getAbsolutePath();
                        int cut = p.indexOf(pkgPath);
                        if (cut > 0) {
                            p = p.substring(0, cut);
                        }
                        if (!out.contains(p)) {
                            out.add(p);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w("获取第二存储失败: " + t);
            }
        }

        // 常见挂载点兜底（各家 ROM 命名都不一样，只能多列几个）
        String[] guesses = new String[] {
                "/storage/sdcard1", "/storage/extSdCard", "/storage/extsd",
                "/storage/usbdisk", "/mnt/extsd", "/mnt/external_sd", "/mnt/sdcard2"
        };
        for (int i = 0; i < guesses.length; i++) {
            try {
                File f = new File(guesses[i]);
                if (f.isDirectory() && f.canRead() && !out.contains(guesses[i])) {
                    out.add(guesses[i]);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /**
     * 递归找 .apk 文件。
     *
     * @param dir      起始目录
     * @param out      结果收集
     * @param maxDepth 最大递归深度（防止超深目录拖死扫描）
     * @param maxFiles 最多收集多少个，够了就停
     */
    public static void findApks(String dir, List<String> out, int maxDepth, int maxFiles) {
        if (dir == null || maxDepth < 0 || out.size() >= maxFiles) {
            return;
        }
        File d = new File(dir);
        if (!d.isDirectory() || !d.canRead()) {
            return;
        }
        File[] children;
        try {
            children = d.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.length && out.size() < maxFiles; i++) {
            File c = children[i];
            if (c == null) {
                continue;
            }
            String name = c.getName();
            if (name.startsWith(".")) {
                continue;
            }
            if (c.isDirectory()) {
                // Android/data 和 Android/obb 里不会有可安装的 apk，跳过能快很多
                if ("Android".equals(name)) {
                    continue;
                }
                findApks(c.getAbsolutePath(), out, maxDepth - 1, maxFiles);
            } else if (name.toLowerCase().endsWith(".apk")) {
                out.add(c.getAbsolutePath());
            }
        }
    }

    /** 列出某个目录下的子项，目录在前、文件在后，用于文件选择器。 */
    public static void listDir(File dir, List<File> dirs, List<File> files) {
        File[] children = null;
        try {
            children = dir.listFiles();
        } catch (Throwable ignored) {
        }
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.length; i++) {
            File c = children[i];
            if (c == null || c.getName().startsWith(".")) {
                continue;
            }
            if (c.isDirectory()) {
                dirs.add(c);
            } else {
                files.add(c);
            }
        }
    }

    /**
     * 判断一个文件是不是在「可插拔的 SD 卡」上。
     *
     * 这里的难点：不同 ROM 的存储布局差异极大。
     * 实测华为 C8816D / Android 4.3：
     *     EXTERNAL_STORAGE  = /storage/sdcard0   ← 内部存储（也叫手机存储）
     *     SECONDARY_STORAGE = /storage/sdcard1   ← 真实 SD 卡
     * 而且两块都是 vfat 真实挂载，所以 Environment.isExternalStorageEmulated()
     * 这种靠 fuse 挂载判断的办法在这里完全没用。
     *
     * 因此策略是：**以主外部存储为基准**，不在它下面的、又落在任一外部存储卷上的，
     * 就算 SD 卡。这样即使 SECONDARY_STORAGE 没定义（只能靠猜的挂载点也识别不到），
     * 也不会把内部存储上的包误判成 SD 卡 —— 宁可漏判，不要误判。
     */
    public static boolean isOnSdCard(String path, Context ctx) {
        if (path == null) {
            return false;
        }
        String canon = canonical(path);
        String primary = canonical(primaryExternal());
        if (canon.startsWith(primary)) {
            return false; // 在内部存储下，直接排除
        }
        // 不在主存储下，再确认它确实落在某个外部存储卷里（而不是 /data 之类）
        if (ctx != null) {
            List<String> secondaries = secondaryExternal(ctx);
            for (int i = 0; i < secondaries.size(); i++) {
                if (canon.startsWith(canonical(secondaries.get(i)))) {
                    return true;
                }
            }
        }
        // 兜底：/storage/ 或 /mnt/ 下、又不是主存储的，基本就是外置卡
        return canon.startsWith("/storage/") && !canon.startsWith("/storage/emulated/");
    }

    /** 找出路径落在哪个存储卷上，用于展示。 */
    public static String storageRootOf(String path, Context ctx) {
        if (path == null) {
            return null;
        }
        String canon = canonical(path);
        String primary = canonical(primaryExternal());
        if (canon.startsWith(primary)) {
            return primary;
        }
        if (ctx != null) {
            List<String> secondaries = secondaryExternal(ctx);
            for (int i = 0; i < secondaries.size(); i++) {
                String s = canonical(secondaries.get(i));
                if (canon.startsWith(s)) {
                    return s;
                }
            }
        }
        return null;
    }

    /** 供 UI 显示用的短路径。 */
    public static String shorten(String path, int maxLen) {
        if (path == null) {
            return "";
        }
        if (path.length() <= maxLen) {
            return path;
        }
        return "…" + path.substring(path.length() - maxLen + 1);
    }

    /**
     * 取「规范路径」，用于判断两个路径是不是同一个文件。
     *
     * 为什么需要它：Android 的存储路径有大量符号链接，例如
     *     /sdcard      -> /storage/sdcard0
     *     /mnt/sdcard  -> /storage/sdcard0
     * 同一张卡上的同一个文件会因此出现好几个不同的路径字符串。
     * 只按字符串去重会把它们当成不同文件，列表里就会出现重复项。
     *
     * File.getCanonicalPath() 会解析掉符号链接和 ".."，
     * 所以上面三种写法会得到同一个结果。
     *
     * 万一解析失败（权限、路径过长等），退回绝对路径，保证不抛异常。
     */
    public static String canonical(String path) {
        if (path == null) {
            return "";
        }
        try {
            return new File(path).getCanonicalPath();
        } catch (Throwable t) {
            try {
                return new File(path).getAbsolutePath();
            } catch (Throwable t2) {
                return path;
            }
        }
    }

    /**
     * 对路径列表按「规范路径」去重，保留每一组里最短的那个写法。
     *
     * 保留最短写法是因为用户看起来更顺眼（/sdcard/xx.apk 比
     * /storage/sdcard0/xx.apk 短），而且这通常是设备上更通用的写法。
     */
    public static List<String> dedupe(List<String> paths) {
        java.util.LinkedHashMap<String, String> best =
                new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < paths.size(); i++) {
            String p = paths.get(i);
            if (p == null) {
                continue;
            }
            String key = canonical(p);
            String existing = best.get(key);
            if (existing == null || p.length() < existing.length()) {
                best.put(key, p);
            }
        }
        return new ArrayList<String>(best.values());
    }
}
