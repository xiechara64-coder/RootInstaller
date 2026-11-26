package com.dsh.rootinstaller;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 「整包解析结果」的磁盘缓存。
 *
 * <h3>目的</h3>
 * 冷启动扫描 154 个包要 30 秒以上，其中大头是 {@code getPackageArchiveInfo}
 * 的资源解析与签名完整性校验 —— 而这两件事对**同一个文件**的结论不会变。
 * 把它们的结果按文件身份缓存起来，下次扫描只做「与已装版本对比」这类很轻的工作。
 *
 * <h3>键</h3>
 * 用 {@link IconCacheKey#of} 的同一套哈希，但只取「路径 + 大小 + mtime」三项 ——
 * 因为读缓存时**还没解析**，拿不到包名和 versionCode。
 * 文件被替换（哪怕只是改动一字节）大小或 mtime 几乎必然变，键随之失效。
 *
 * <h3>什么不缓存</h3>
 * 已装版本对比、权限差异、风险等级 —— 它们依赖运行状态，每次必须重算。
 * 见 {@link ParseCacheData} 的类注释。
 */
public final class ParseCache {

    private static final String DIR_NAME = "parse";

    /** 最多保留的条目数。 */
    public static final int MAX_ENTRIES = 600;
    /** 目录总大小上限（8 MB）。现在每条记录里还带着图标 PNG，所以比只有字段时大。 */
    public static final long MAX_BYTES = 8L * 1024L * 1024L;

    private ParseCache() {
    }

    // 一次扫描内的命中统计：用来回答「缓存到底有没有生效」。
    private static int hitCount;
    private static int missCount;

    /** 扫描结束时调用：打一行日志并清零。 */
    public static void logScanSummary() {
        int h = hitCount;
        int m = missCount;
        hitCount = 0;
        missCount = 0;
        int total = h + m;
        if (total == 0) {
            return;
        }
        int pct = (int) Math.round(h * 100.0d / total);
        Log.i("解析缓存：命中 " + h + " / 未命中 " + m + "（命中率 " + pct + "%）");
    }

    /** 缓存目录路径（诊断用；不可用时返回说明文字）。 */
    public static String dirPath(Context ctx) {
        File d = dir(ctx);
        return d == null ? "(不可用)" : d.getAbsolutePath();
    }

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

    /**
     * 删掉 v1.0.1 遗留的独立图标缓存目录（`files/cache/icons` 与 `cache/icons`）。
     * 图标已经并入解析缓存，留着只是占空间。
     */
    private static void cleanupLegacyIconDirs(Context ctx) {
        try {
            removeDir(new File(new File(ctx.getFilesDir(), "cache"), "icons"));
            removeDir(new File(ctx.getCacheDir(), "icons"));
        } catch (Throwable t) {
            // 清理失败无所谓，不影响功能
        }
    }

    private static void removeDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] all = dir.listFiles();
        if (all != null) {
            for (int i = 0; i < all.length; i++) {
                all[i].delete();
            }
        }
        dir.delete();
    }

    private static File dir(Context ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            // 用 getFilesDir() 的原因见 IconCache.dir()：
            // 这台设备 /data 只剩 27.9MB，系统会不停清空 cache 目录，
            // 缓存放那里根本留不住。这里体积更小（154 条约 300KB），
            // 加上 400 项 / 4MB 上限、LRU 和设置页手动清除，够安全。
            File base = new File(ctx.getFilesDir(), "cache");
            File d = new File(base, DIR_NAME);
            if (!d.exists() && !d.mkdirs()) {
                return null;
            }
            // v1.0.1 用的是独立的图标缓存目录，图标已并入本缓存 → 顺手清理掉旧目录
            cleanupLegacyIconDirs(ctx);
            return d;
        } catch (Throwable t) {
            Log.w("解析缓存目录不可用: " + t);
            return null;
        }
    }

    /** 文件身份键：路径 + 大小 + mtime。包名/版本号此时还不知道，不能参与。 */
    public static String keyFor(String apkPath) {
        File apk = new File(apkPath == null ? "" : apkPath);
        String canonical;
        try {
            canonical = apk.getCanonicalPath();
        } catch (Throwable t) {
            canonical = apk.getAbsolutePath();
        }
        return IconCacheKey.of(canonical, "", -1, apk.length(), apk.lastModified());
    }

    private static File fileFor(Context ctx, String apkPath) {
        File d = dir(ctx);
        if (d == null) {
            return null;
        }
        return new File(d, keyFor(apkPath) + ".bin");
    }

    /** 读缓存；没有、损坏、版本不符都返回 null。命中时刷新文件时间戳（供 LRU 使用）。 */
    public static ParseCacheData load(Context ctx, String apkPath) {
        try {
            File f = fileFor(ctx, apkPath);
            if (f == null || !f.exists() || f.length() == 0) {
                missCount++;
                return null;
            }
            byte[] raw = readAll(f);
            ParseCacheData d = ParseCacheCodec.decode(raw);
            if (d == null) {
                // 损坏或旧格式：删掉，下次重新生成
                f.delete();
                missCount++;
                return null;
            }
            f.setLastModified(System.currentTimeMillis());
            hitCount++;
            return d;
        } catch (Throwable t) {
            Log.w("读解析缓存失败: " + t);
            missCount++;
            return null;
        }
    }

    /**
     * 立刻删掉某个 APK 的缓存条目。
     *
     * <p><b>必须在文件还在的时候调用</b>，或者改用 {@link #invalidateKey}：
     * 缓存键里含文件大小与 mtime，文件一删这两个值就变成 0，
     * 算出来的键和写缓存时的键对不上，会「删了个不存在的条目」。
     * （这个坑实测踩过：删了 3 个包，缓存条目数一个都没减少。）
     */
    public static void invalidate(Context ctx, String apkPath) {
        invalidateKey(ctx, keyFor(apkPath));
    }

    /** 按已经算好的键删缓存（删除文件前先取键，删完再用它清）。 */
    public static void invalidateKey(Context ctx, String key) {
        try {
            if (key == null) {
                return;
            }
            File d = dir(ctx);
            if (d == null) {
                return;
            }
            File f = new File(d, key + ".bin");
            if (f.exists() && !f.delete()) {
                Log.w("删除解析缓存条目失败: " + f.getName());
            }
        } catch (Throwable t) {
            Log.w("删除解析缓存条目出错: " + t);
        }
    }

    /** 写缓存。失败只是没有缓存，不影响解析流程。 */
    public static void save(Context ctx, String apkPath, ParseCacheData data) {        if (data == null || !data.parseOk) {
            // 解析失败的**不缓存**：失败可能是临时的（文件正被写、内存不足），
            // 缓存下来会让「下次就好了」永远好不了。
            return;
        }
        FileOutputStream out = null;
        File tmp = null;
        try {
            File f = fileFor(ctx, apkPath);
            if (f == null) {
                return;
            }
            byte[] raw = ParseCacheCodec.encode(data);
            if (raw == null) {
                return;
            }
            tmp = new File(f.getAbsolutePath() + ".tmp");
            out = new FileOutputStream(tmp);
            out.write(raw);
            out.flush();
            out.close();
            out = null;
            if (!tmp.renameTo(f)) {
                Log.w("解析缓存改名失败: " + f.getName());
            } else {
                tmp = null;
            }
        } catch (Throwable t) {
            Log.w("写解析缓存失败: " + t);
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

    /** 把卡片的静态字段取下来准备缓存。 */
    public static ParseCacheData capture(ApkCard card, boolean signatureComputed,
                                         boolean abisComputed) {
        ParseCacheData d = new ParseCacheData();
        d.packageName = card.packageName;
        d.versionName = card.versionName;
        d.label = card.label;
        d.versionCode = card.versionCode;
        d.minSdk = card.minSdk;
        d.targetSdk = card.targetSdk;
        d.installLocation = card.installLocation;
        if (card.permissions != null) {
            d.permissions.addAll(card.permissions);
        }

        d.activityCount = card.activityCount;
        d.serviceCount = card.serviceCount;
        d.receiverCount = card.receiverCount;
        d.providerCount = card.providerCount;
        d.activityCountExported = card.activityCountExported;
        d.serviceCountExported = card.serviceCountExported;
        d.receiverCountExported = card.receiverCountExported;
        if (card.exportedComponents != null) {
            d.exportedComponents.addAll(card.exportedComponents);
        }

        d.parseOk = card.parseOk;
        d.parseError = card.parseError;
        d.usedFallbackParser = card.usedFallbackParser;

        d.signaturePresent = signatureComputed;
        if (signatureComputed) {
            d.signatureStatus = card.signatureStatus;
            d.signatureIntegrity = card.signatureIntegrity;
            d.sigV1 = card.sigV1;
            d.sigV2 = card.sigV2;
            d.sigV3 = card.sigV3;
            d.sigV4 = card.sigV4;
            d.archiveSignerSha1 = card.archiveSignerSha1;
            d.signatureIntegrityDetail = card.signatureIntegrityDetail;
            d.signatureNote = card.signatureNote;
            d.manifestVersionCode = card.manifestVersionCode;
        }

        d.nativeAbisPresent = abisComputed;
        if (abisComputed && card.nativeAbis != null) {
            d.nativeAbis.addAll(card.nativeAbis);
        }
        // 只读证书也存下来：分组要用，且避免每次扫描重读签名块
        d.certSha1 = card.archiveSignerSha1;
        d.certV1 = card.sigV1;
        d.certV2 = card.sigV2;
        d.certV3 = card.sigV3;
        d.certV4 = card.sigV4;

        // 图标一起存进来（见 ParseCacheData.iconPng 的注释）
        d.iconPng = encodePng(card.icon);
        return d;
    }

    /** 把缓存里的静态字段填回卡片（不碰 icon / 已装对比 / 风险）。 */
    public static void fill(ApkCard card, ParseCacheData d) {
        card.packageName = d.packageName;
        card.versionName = d.versionName;
        card.label = d.label;
        card.versionCode = d.versionCode;
        card.minSdk = d.minSdk;
        card.targetSdk = d.targetSdk;
        card.installLocation = d.installLocation;
        card.permissions.clear();
        card.permissions.addAll(d.permissions);

        card.activityCount = d.activityCount;
        card.serviceCount = d.serviceCount;
        card.receiverCount = d.receiverCount;
        card.providerCount = d.providerCount;
        card.activityCountExported = d.activityCountExported;
        card.serviceCountExported = d.serviceCountExported;
        card.receiverCountExported = d.receiverCountExported;
        card.exportedComponents.clear();
        card.exportedComponents.addAll(d.exportedComponents);

        card.parseOk = d.parseOk;
        card.parseError = d.parseError;
        card.usedFallbackParser = d.usedFallbackParser;

        if (d.signaturePresent) {
            card.signatureStatus = d.signatureStatus;
            card.signatureIntegrity = d.signatureIntegrity;
            card.sigV1 = d.sigV1;
            card.sigV2 = d.sigV2;
            card.sigV3 = d.sigV3;
            card.sigV4 = d.sigV4;
            card.archiveSignerSha1 = d.archiveSignerSha1;
            card.signatureIntegrityDetail = d.signatureIntegrityDetail;
            card.signatureNote = d.signatureNote;
            card.manifestVersionCode = d.manifestVersionCode;
        }
        if (d.nativeAbisPresent) {
            card.nativeAbis.clear();
            card.nativeAbis.addAll(d.nativeAbis);
        }
        // 只读证书：即便没做过完整签名解析，也能用它做「同签名分组」
        if (card.archiveSignerSha1 == null && d.certSha1 != null) {
            card.archiveSignerSha1 = d.certSha1;
            card.sigV1 = d.certV1;
            card.sigV2 = d.certV2;
            card.sigV3 = d.certV3;
            card.sigV4 = d.certV4;
        }

        // 图标随解析结果一起回来，不再需要单独一次解码
        card.icon = decodePng(d.iconPng);
    }

    /**
     * 一次扫描结束后调用：清掉「对应 APK 已经不在、且超过宽限期」的孤儿缓存。
     *
     * @param seenKeys 本次扫描实际用到的缓存键（{@link #keyFor} 的结果）
     * @param now      当前时间
     */
    public static void sweep(Context ctx, java.util.Set<String> seenKeys, long now) {
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
            for (int i = 0; i < all.length; i++) {
                if (all[i].isFile() && all[i].getName().endsWith(".bin")) {
                    files.add(all[i]);
                }
            }
            int n = files.size();
            if (n == 0) {
                return;
            }
            long[] lastUsed = new long[n];
            String[] keys = new String[n];
            for (int i = 0; i < n; i++) {
                File f = files.get(i);
                lastUsed[i] = f.lastModified();
                String name = f.getName();
                keys[i] = name.substring(0, name.length() - 4);
            }
            int[] del = CacheSweep.chooseOrphans(lastUsed, keys, seenKeys, now,
                    CacheSweep.DEFAULT_GRACE_MILLIS);
            if (del.length == 0) {
                return;
            }
            long freed = 0;
            for (int i = 0; i < del.length; i++) {
                File f = files.get(del[i]);
                freed += f.length();
                f.delete();
            }
            Log.i("清理孤儿解析缓存 " + del.length + " 项，释放 " + formatBytes(freed));
        } catch (Throwable t) {
            Log.w("清理孤儿解析缓存失败: " + t);
        }
    }

    /**
     * 把「只读证书」补进已存在的缓存条目（列表扫描补齐证书后调用）。
     *
     * <p>不新建条目：没有缓存说明这个包没被成功解析过，下次解析时自然会写。
     */
    public static void updateCertificate(Context ctx, String apkPath, String certSha1,
                                         boolean v1, boolean v2, boolean v3, boolean v4) {
        if (certSha1 == null) {
            return;
        }
        try {
            ParseCacheData d = load(ctx, apkPath);
            if (d == null) {
                return;
            }
            d.certSha1 = certSha1;
            d.certV1 = v1;
            d.certV2 = v2;
            d.certV3 = v3;
            d.certV4 = v4;
            save(ctx, apkPath, d);
        } catch (Throwable t) {
            Log.w("更新缓存里的证书失败: " + t);
        }
    }

    /** 人类可读的体积，设置页展示用。 */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format("%.1f KB", bytes / 1024.0d);
        }
        return String.format("%.1f MB", bytes / (1024.0d * 1024.0d));
    }

    /** 位图 → PNG 字节。失败只意味着这次没缓存图标，不影响显示。 */
    private static byte[] encodePng(android.graphics.Bitmap bmp) {
        if (bmp == null) {
            return null;
        }
        java.io.ByteArrayOutputStream bos = null;
        try {
            bos = new java.io.ByteArrayOutputStream(8192);
            if (!bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)) {
                return null;
            }
            bos.flush();
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w("图标编码失败: " + t);
            return null;
        } finally {
            if (bos != null) {
                try {
                    bos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** PNG 字节 → 位图。 */
    private static android.graphics.Bitmap decodePng(byte[] png) {
        if (png == null || png.length == 0) {
            return null;
        }
        try {
            return android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
        } catch (Throwable t) {
            Log.w("图标解码失败: " + t);
            return null;
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
            Log.w("统计解析缓存失败: " + t);
        }
        return new Stats(count, bytes);
    }

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
            Log.w("清空解析缓存失败: " + t);
        }
    }

    /** 超过上限时按最久未用淘汰（一次扫描结束后调一次）。 */
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
            if (!IconCachePolicy.needsTrim(count, bytes, MAX_ENTRIES, MAX_BYTES)) {
                return;
            }
            long[] lastUsed = new long[count];
            long[] sizes = new long[count];
            for (int i = 0; i < count; i++) {
                lastUsed[i] = files.get(i).lastModified();
                sizes[i] = files.get(i).length();
            }
            int[] evict = IconCachePolicy.chooseEvictions(lastUsed, sizes, MAX_ENTRIES, MAX_BYTES);
            long freed = 0;
            for (int i = 0; i < evict.length; i++) {
                int idx = evict[i];
                freed += sizes[idx];
                files.get(idx).delete();
            }
            Log.i("解析缓存超限，淘汰 " + evict.length + " 项，释放 "
                    + formatBytes(freed) + "（原有 " + formatBytes(bytes) + "）");
        } catch (Throwable t) {
            Log.w("淘汰解析缓存失败: " + t);
        }
    }

    private static byte[] readAll(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            long len = f.length();
            if (len <= 0 || len > 10L * 1024L * 1024L) {
                throw new IllegalStateException("缓存文件大小异常: " + len);
            }
            byte[] buf = new byte[(int) len];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            if (off != buf.length) {
                throw new IllegalStateException("缓存文件读取不完整");
            }
            return buf;
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
