package com.dsh.rootinstaller;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 删除安装包文件。
 *
 * <p>只做三件事，但每件都必须做对：
 * <ol>
 *   <li>先过 {@link DeletablePaths} 的白名单/黑名单 —— 系统目录、asec 容器一律不碰；</li>
 *   <li>直接 {@code File.delete()}，失败才退到 {@code su rm}（只读 SD 卡或权限不足时）；</li>
 *   <li>删成功后**立刻**让解析缓存失效，并发一条媒体库扫描广播 ——
 *       否则文件管理器里还会看到那个文件，用户会以为没删掉。</li>
 * </ol>
 *
 * <p>注意：删除本身不可逆，所以这个方法**不做任何确认**，确认必须由调用方负责。
 */
public final class ApkDeleter {

    private ApkDeleter() {
    }

    /** 单个文件的删除结果。 */
    public static final class Result {
        public final boolean ok;
        public final String message;
        /** 是否走了 root（只读卡的情况）。 */
        public final boolean usedRoot;

        Result(boolean ok, String message, boolean usedRoot) {
            this.ok = ok;
            this.message = message;
            this.usedRoot = usedRoot;
        }
    }

    /** 批量删除的汇总。 */
    public static final class Batch {
        public int okCount;
        public long freedBytes;
        /** 失败项：文件名 + 原因。 */
        public final List<String> failures = new ArrayList<String>();

        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (okCount > 0) {
                sb.append("已删除 ").append(okCount).append(" 个，释放 ")
                        .append(ApkParser.humanSize(freedBytes));
            }
            if (!failures.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append(failures.size()).append(" 个失败");
            }
            if (sb.length() == 0) {
                sb.append("没有删除任何文件");
            }
            return sb.toString();
        }
    }

    /** 删除一个安装包。调用方负责先让用户确认。 */
    public static Result delete(Context ctx, String apkPath) {
        String blocked = DeletablePaths.blockedReason(apkPath);
        if (blocked != null) {
            return new Result(false, blocked, false);
        }

        File f = new File(apkPath);
        if (!f.exists()) {
            return new Result(false, "文件已经不存在了", false);
        }

        CrashReporter.note("用户删除安装包: " + apkPath);

        // 缓存键必须在删文件**之前**取：键里含大小与 mtime，
        // 文件一删这两个值就变成 0，键就对不上了（实测踩过）。
        final String cacheKey = ParseCache.keyFor(apkPath);

        boolean deleted = false;
        try {
            deleted = f.delete();
        } catch (Throwable t) {
            Log.e("删除安装包异常: " + apkPath, t);
        }

        boolean usedRoot = false;
        if (!deleted) {
            // 只读介质（不少 4.3 机器的 SD 卡就是只读挂载）或权限不足。
            // 有 root 就试一次 rm：这是本应用存在的意义之一。
            try {
                if (RootShell.isRootAvailable()) {
                    Log.i("直接删除失败，改用 root: " + apkPath);
                    RootShell.run("rm -f " + RootShell.quote(apkPath), 20000L, true);
                    usedRoot = true;
                    deleted = !f.exists();
                }
            } catch (Throwable t) {
                Log.e("root 删除失败: " + apkPath, t);
            }
        }

        if (!deleted) {
            return new Result(false,
                    "删除失败（可能是只读的存储卡，或文件被系统占用）", usedRoot);
        }

        // 关键：缓存立刻失效。我们确切知道文件没了，不该等孤儿清理的宽限期。
        // 用删除前算好的键 —— 此刻文件已不在，重新算会算出另一个键。
        ParseCache.invalidateKey(ctx, cacheKey);
        notifyMediaScanner(ctx, f);
        Log.i("已删除安装包: " + apkPath + "（root=" + usedRoot + "）");
        return new Result(true, usedRoot ? "已删除（通过 root）" : "已删除", usedRoot);
    }

    /**
     * 批量删除。
     *
     * @param sizes 可选，用于统计释放了多少空间（键是路径）
     */
    public static Batch deleteAll(Context ctx, List<String> paths, Map<String, Long> sizes) {
        Batch b = new Batch();
        if (paths == null) {
            return b;
        }
        for (int i = 0; i < paths.size(); i++) {
            String p = paths.get(i);
            long size = 0;
            if (sizes != null && sizes.containsKey(p)) {
                Long v = sizes.get(p);
                if (v != null) {
                    size = v;
                }
            }
            Result r = delete(ctx, p);
            if (r.ok) {
                b.okCount++;
                b.freedBytes += size;
            } else {
                b.failures.add(DeletablePaths.fileNameOf(p) + "：" + r.message);
            }
        }
        return b;
    }

    /**
     * 通知媒体库「这个文件没了」。
     *
     * <p>不发也行（文件确实删了），但 4.3 的文件管理器/相册会继续显示缓存里的条目，
     * 用户会以为没删掉 —— 这类「看起来没生效」的反馈最耗人。
     */
    private static void notifyMediaScanner(Context ctx, File f) {
        try {
            ctx.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    Uri.fromFile(f)));
        } catch (Throwable t) {
            Log.w("发送媒体库扫描广播失败: " + t);
        }
    }
}
