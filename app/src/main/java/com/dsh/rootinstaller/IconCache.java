package com.dsh.rootinstaller;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 图标的磁盘缓存。
 *
 * <p>为什么放在 {@code getCacheDir()} 而不是 {@code getFilesDir()}：
 * 前者在存储空间不足时**系统会自己清空**，等于免费获得一道兜底；后者会被算成应用数据，
 * 用户还得手动清。缓存本来就是可丢弃的数据，放对目录比写多少清理逻辑都重要。
 *
 * <p>键的构成见 {@link IconCacheKey}，淘汰策略见 {@link IconCachePolicy}。
 * 这个类只负责「读写文件 + 调策略」，那些需要设备才能跑的逻辑都尽量挪到了那两个纯 Java 类里。
 */
public final class IconCache {

    private static final String DIR_NAME = "icons";

    private IconCache() {
    }

    // 一次扫描内的命中统计。用来回答「缓存到底有没有生效」——
    // 光看「有没有变快」不可靠（图标解码本来就不是最慢的一步）。
    private static int hitCount;
    private static int missCount;      // 没有缓存文件（首次，或这个包本来就没图标）
    private static int corruptCount;   // 有文件但解码失败

    /** 扫描结束时调用：打一行日志并清零，便于对比冷/热两次扫描。 */
    public static void logScanSummary() {
        int h = hitCount;
        int m = missCount;
        int c = corruptCount;
        hitCount = 0;
        missCount = 0;
        corruptCount = 0;
        int total = h + m;
        if (total == 0) {
            return;
        }
        int pct = (int) Math.round(h * 100.0d / total);
        Log.i("图标缓存：命中 " + h + " / 无缓存 " + m + " / 损坏 " + c
                + "（命中率 " + pct + "%）");
    }

    /** 缓存统计，供设置页展示。 */
    public static final class Stats {
        public final int count;
        public final long bytes;

        Stats(int count, long bytes) {
            this.count = count;
            this.bytes = bytes;
        }

        public String describe() {
            if (count == 0) {
                return "暂无缓存";
            }
            return formatBytes(bytes) + " / " + count + " 项";
        }
    }

    /** 缓存目录；创建失败返回 null（调用方按「没有缓存」处理，不影响功能）。 */
    private static File dir(Context ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            File d = new File(ctx.getCacheDir(), DIR_NAME);
            if (!d.exists() && !d.mkdirs()) {
                return null;
            }
            return d;
        } catch (Throwable t) {
            Log.w("图标缓存目录不可用: " + t);
            return null;
        }
    }

    private static File apkFile(String apkPath) {
        return new File(apkPath == null ? "" : apkPath);
    }

    private static String keyFor(String apkPath, String pkg, int versionCode) {
        File apk = apkFile(apkPath);
        return IconCacheKey.of(canonical(apk), pkg, versionCode, apk.length(), apk.lastModified());
    }

    private static File fileFor(Context ctx, String apkPath, String pkg, int versionCode) {
        File d = dir(ctx);
        if (d == null) {
            return null;
        }
        return new File(d, IconCacheKey.fileName(pkg, keyFor(apkPath, pkg, versionCode)));
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Throwable t) {
            return f.getAbsolutePath();
        }
    }

    /**
     * 读缓存。命中返回位图，未命中或解码失败返回 null（调用方应回退到真正解码）。
     *
     * <p>命中时会顺便把文件时间戳更新为「现在」，这样淘汰策略的「最久未用」才是真的
     * 按访问时间算，而不是按写入时间。
     */
    public static Bitmap load(Context ctx, String apkPath, String pkg, int versionCode) {
        try {
            File f = fileFor(ctx, apkPath, pkg, versionCode);
            if (f == null || !f.exists() || f.length() == 0) {
                missCount++;
                return null;
            }
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp == null) {
                // 文件损坏（写到一半断电之类）：删掉，下次重新生成
                Log.w("图标缓存损坏，已删除: " + f.getName());
                f.delete();
                corruptCount++;
                return null;
            }
            f.setLastModified(System.currentTimeMillis());
            hitCount++;
            return bmp;
        } catch (Throwable t) {
            Log.w("读图标缓存失败: " + t);
            missCount++;
            return null;
        }
    }

    /**
     * 写缓存。失败只是没有缓存，不能影响解析流程，所以全程吞异常（只记日志）。
     *
     * <p>写文件用「临时文件 + rename」：直接写目标文件的话，中途被杀会留下半截文件，
     * 下次 decode 失败还要走删除逻辑。
     */
    public static void store(Context ctx, String apkPath, String pkg, int versionCode, Bitmap bmp) {
        if (bmp == null) {
            return;
        }
        FileOutputStream out = null;
        File tmp = null;
        try {
            File f = fileFor(ctx, apkPath, pkg, versionCode);
            if (f == null) {
                return;
            }
            tmp = new File(f.getAbsolutePath() + ".tmp");
            out = new FileOutputStream(tmp);
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IllegalStateException("PNG 编码返回 false");
            }
            out.flush();
            out.close();
            out = null;

            if (tmp.renameTo(f)) {
                tmp = null;
                // 同一个包的旧缓存（上一版的图标）删掉，避免一个包留多份
                removeSiblings(f, IconCacheKey.packagePrefix(pkg));
            } else {
                Log.w("图标缓存改名失败: " + f.getName());
            }
        } catch (Throwable t) {
            Log.w("写图标缓存失败: " + t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            if (tmp != null) {
                tmp.delete();
            }
        }
    }

    /** 删除同一包名（同前缀）但文件名不同的缓存，也就是这个包的历史版本。 */
    private static void removeSiblings(File keep, String prefix) {
        try {
            File d = keep.getParentFile();
            if (d == null) {
                return;
            }
            File[] all = d.listFiles();
            if (all == null) {
                return;
            }
            for (int i = 0; i < all.length; i++) {
                File f = all[i];
                if (f.equals(keep)) {
                    continue;
                }
                String name = f.getName();
                // 同前缀 = 同包名；名字不同 = 这个包的历史版本（含可能残留的 .tmp）
                if (name.startsWith(prefix + "-")) {
                    f.delete();
                }
            }
        } catch (Throwable t) {
            Log.w("清理旧图标缓存失败: " + t);
        }
    }

    public static Stats stats(Context ctx) {
        File d = dir(ctx);
        if (d == null) {
            return new Stats(0, 0);
        }
        int count = 0;
        long bytes = 0;
        try {
            File[] all = d.listFiles();
            if (all != null) {
                for (int i = 0; i < all.length; i++) {
                    if (all[i].isFile()) {
                        count++;
                        bytes += all[i].length();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w("统计图标缓存失败: " + t);
        }
        return new Stats(count, bytes);
    }

    /** 清空全部缓存（设置页里的按钮）。 */
    public static void clear(Context ctx) {
        File d = dir(ctx);
        if (d == null) {
            return;
        }
        try {
            File[] all = d.listFiles();
            if (all != null) {
                for (int i = 0; i < all.length; i++) {
                    all[i].delete();
                }
            }
        } catch (Throwable t) {
            Log.w("清空图标缓存失败: " + t);
        }
    }

    /**
     * 超过上限时按「最久未用」淘汰。应在一次扫描结束后调用一次
     * （而不是每存一个图标都调用），否则每个包都要列一遍目录。
     */
    public static void trim(Context ctx) {
        File d = dir(ctx);
        if (d == null) {
            return;
        }
        try {
            File[] all = d.listFiles();
            if (all == null || all.length == 0) {
                return;
            }
            List<File> files = new ArrayList<File>();
            int count = 0;
            long bytes = 0;
            for (int i = 0; i < all.length; i++) {
                if (all[i].isFile()) {
                    files.add(all[i]);
                    count++;
                    bytes += all[i].length();
                }
            }
            if (!IconCachePolicy.needsTrim(count, bytes)) {
                return;
            }
            long[] lastUsed = new long[count];
            long[] sizes = new long[count];
            for (int i = 0; i < count; i++) {
                lastUsed[i] = files.get(i).lastModified();
                sizes[i] = files.get(i).length();
            }
            int[] evict = IconCachePolicy.chooseEvictions(lastUsed, sizes,
                    IconCachePolicy.MAX_ENTRIES, IconCachePolicy.MAX_BYTES);
            long freed = 0;
            for (int i = 0; i < evict.length; i++) {
                int idx = evict[i];
                freed += sizes[idx];
                files.get(idx).delete();
            }
            Log.i("图标缓存超限，淘汰 " + evict.length + " 项，释放 "
                    + formatBytes(freed) + "（原有 " + formatBytes(bytes) + "）");
        } catch (Throwable t) {
            Log.w("淘汰图标缓存失败: " + t);
        }
    }

    /** 人类可读的体积，设置页展示用。 */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format("%.1f KB", bytes / 1024.0d);
        }
        return String.format("%.1f MB", bytes / (1024.0d * 1024.0d));
    }
}
