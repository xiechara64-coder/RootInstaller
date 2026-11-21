package com.dsh.rootinstaller;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * APK 解析：把一个 .apk 文件变成可展示的 {@link ApkCard}。
 *
 * 两条路线：
 *   A. 首选 PackageManager.getPackageArchiveInfo()，信息最全；
 *   B. 如果 A 失败（常见于 minSdk 高于系统、或厂商 ROM 的解析器挑食），
 *      退化到自己解析二进制的 AndroidManifest.xml，至少把包名/版本号挖出来。
 *
 * 另外负责：和已安装版本做升级/降级/签名差异对比。
 */
public final class ApkParser {

    private ApkParser() {
    }

    /**
     * 解析入口。必须在子线程调用（会读文件、扫描组件）。
     *
     * @param full 是否做「完整解析」。
     *             列表扫描传 false：跳过最贵的 so 架构遍历（41MB 的包要 2.6 秒），
     *             基本信息、图标、权限都照常拿。
     *             详情页传 true：额外读取 CPU 架构。
     */
    public static ApkCard parse(Context ctx, String path, boolean full) {
        ApkCard card = new ApkCard();
        card.filePath = path;

        File f = new File(path);
        card.fileName = f.getName();
        card.fileSize = f.length();
        card.fileModified = f.lastModified();

        // 记录来源：内部存储还是 SD 卡（列表里要用颜色区分）
        try {
            card.fromSdCard = Paths.isOnSdCard(path, ctx);
            card.storageRoot = Paths.storageRootOf(path, ctx);
        } catch (Throwable t) {
            Log.w("判断存储位置失败: " + t);
        }

        PackageManager pm = ctx.getPackageManager();
        int flags = PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS
                | PackageManager.GET_PROVIDERS
                | PackageManager.GET_PERMISSIONS;

        PackageInfo pi = null;
        try {
            pi = pm.getPackageArchiveInfo(path, flags);
        } catch (Throwable t) {
            Log.e("getPackageArchiveInfo 抛异常", t);
        }

        // ---- 说明：这里曾经有一段「把 APK 复制到私有目录再解析」的兜底。
        //
        // 当时的假设是「某些 ROM 只认私有目录里的包」，但这个假设被实测证伪了。
        // 用 PerfProbeActivity 在真机（华为 C8816D / 4.3）上量出来的结果：
        //
        //     A) 直接 getPackageArchiveInfo    2~19ms    成功
        //     E) 复制到私有目录 + 解析副本     80~210ms  结果完全一样
        //     => 复制是否有用 = false（三个样本全部如此）
        //
        // 也就是说这段复制从来没救回过任何一次解析失败，只是白白多花
        // 每个包 80~210ms。而它又挂在「列表滚动时异步加载图标」的路径上，
        // 直接把扫描从 3 秒拖到 7~18 秒。所以删掉。
        //
        // 真正救不回的那类包（例如小米的 mimo_asset.apk，本身就不是标准 APK），
        // 复制同样没用；那种情况交给下面的 AXML 兜底解析更实在。
        String parsedFrom = path;

        try {
            if (pi != null && pi.packageName != null) {
                // 注意这里**不做** JarFile 遍历（不读 so 架构）。
                //
                // 原因：new JarFile(apk) 要解析整个 APK 的中央目录，
                // 实测一个 41MB 的包需要 2.6 秒 —— 而 so 架构只有详情页才用得到，
                // 列表扫描完全不需要。所以把它推迟到详情页按需加载（见 ensureNativeAbis）。
                // 这一项就占掉了扫描时间的一半以上。
                fillFromPackageInfo(ctx, pm, pi, card, parsedFrom);

                // 但 AXML 里的 minSdk/targetSdk 是列表要显示的（「最低 Android x.x」），
                // 所以这一项仍然要读；只是改成读一次、复用字节，不再重复开文件。
                if (card.minSdk <= 0 || card.targetSdk <= 0) {
                    byte[] manifestBytes = readManifestBytes(parsedFrom);
                    fillSdkFromBytes(manifestBytes, card);
                }

                card.parseOk = true;
                Log.i("解析成功 pkg=" + card.packageName
                        + " label=" + card.label
                        + " minSdk=" + card.minSdk + " targetSdk=" + card.targetSdk
                        + " icon=" + (card.icon == null ? "null"
                        : card.icon.getWidth() + "x" + card.icon.getHeight()));
            } else {
                Log.w("getPackageArchiveInfo 返回 null，改用二进制清单兜底解析");
                card.usedFallbackParser = true;
                fillFromBinaryManifest(path, card);
            }
        } catch (Throwable t) {
            Log.e("解析过程异常: " + path, t);
            card.parseError = "解析异常: " + t;
        }

        compareWithInstalled(ctx, pm, card);

        // 顺带算出权限风险等级，这样列表页不用再遍历一遍权限就能显示徽标。
        // 代价只是把已有的权限列表过一遍，很轻。
        // 这里 table 传 null：详情页会带着完整的系统权限表再算一次，
        // 覆盖掉这份粗略结果（含更准确的能力描述）。
        try {
            PermAbilities.summarize(card, null);
        } catch (Throwable t) {
            Log.w("权限风险汇总失败: " + t);
        }

        // 完整解析才去读 CPU 架构（最贵的一步，列表用不到）
        if (full) {
            ensureNativeAbis(path, card);
            card.fullParse = true;

            // 读签名状态。
            //
            // 为什么必须在解析阶段就做、而且**不管包有没有装都要做**：
            //  1. 签名是 APK 文件自身的属性，和「装没装」无关。之前把它挂在
            //     详情页的「点击计算签名摘要」上，而那个入口属于默认隐藏的
            //     「更多信息」，于是默认设置下签名永远算不出来，
            //     安装时那道「签名不一致就别装」的拦截也永远不会触发。
            //  2. 只翻 META-INF 是不够的：只做 v2/v3 签名的正常包在那里
            //     什么都没有，会被误报成「未签名」。必须连 Signing Block 一起看。
            //
            // 开销可以接受：证书摘要是对证书字节做 SHA1（几 KB），
            // 查找 Signing Block 只需从文件尾部读固定几块，不随包大小线性增长。
            applySignatureInfo(path, card);
        }
        return card;
    }

    /** 把 ApkSignatures 的结果填进卡片。 */
    private static void applySignatureInfo(String path, ApkCard card) {
        try {
            ApkSignatures.Result sig = ApkSignatures.inspect(path);
            card.sigV1 = sig.v1;
            card.sigV2 = sig.v2;
            card.sigV3 = sig.v3;
            card.sigV4 = sig.v4;
            card.archiveSignerSha1 = sig.certSha1;
            card.signatureIntegrity = sig.integrity;
            card.signatureIntegrityDetail = sig.integrityDetail;
            card.signatureNote = sig.note;
            card.manifestVersionCode = sig.manifestVersionCode;

            if (sig.anyScheme()) {
                card.signatureStatus = ApkSignatures.STATUS_SIGNED;
            } else if (sig.note != null && sig.note.indexOf("未找到任何签名") >= 0) {
                card.signatureStatus = ApkSignatures.STATUS_UNSIGNED;
            } else {
                // 读失败（文件坏了、权限不对等）—— 不能当成未签名
                card.signatureStatus = ApkSignatures.STATUS_UNKNOWN;
            }
        } catch (Throwable t) {
            Log.w("读取签名状态失败: " + t);
            card.signatureStatus = ApkSignatures.STATUS_UNKNOWN;
            card.signatureNote = "读取签名状态出错: " + t;
        }

        // 一行诊断，和「详情页渲染完成」那条同样的用途：
        // 签名这块的结论没法在真机上单步调试，出问题时先把判定依据打出来。
        Log.i("签名 " + card.packageName
                + " | 状态=" + signatureStatusText(card.signatureStatus)
                + " | 方案=" + (card.sigV1 ? "v1" : "") + (card.sigV2 ? "v2" : "")
                + (card.sigV3 ? "v3" : "") + (card.sigV4 ? "v4" : "")
                + " | 证书=" + (card.archiveSignerSha1 != null ? "有" : "无")
                + " | 完整性=" + integrityText(card.signatureIntegrity)
                + (card.signatureIntegrityDetail != null
                ? "(" + card.signatureIntegrityDetail + ")" : "")
                + " | 已装=" + card.installed
                + " | 已装证书=" + (card.installedSignerSha1 != null ? "有" : "无")
                + " | 一致=" + (card.installedSignerSha1 != null && card.archiveSignerSha1 != null
                ? String.valueOf(card.installedSignerSha1.equalsIgnoreCase(card.archiveSignerSha1))
                : "无法判断"));
    }

    private static String signatureStatusText(int status) {
        if (status == ApkSignatures.STATUS_SIGNED) {
            return "有签名";
        }
        if (status == ApkSignatures.STATUS_UNSIGNED) {
            return "未签名";
        }
        return "未知";
    }

    private static String integrityText(int v) {
        if (v == ApkSignatures.INTEGRITY_OK) {
            return "通过";
        }
        if (v == ApkSignatures.INTEGRITY_BAD) {
            return "不通过";
        }
        return "未判断";
    }

    /** 向后兼容的简化入口：不复制，只做轻量解析。 */
    public static ApkCard parse(Context ctx, String path) {
        return parse(ctx, path, false);
    }

    /**
     * 把 APK 复制到私有缓存目录，返回副本；失败返回 null。
     *
     * 用时间戳做文件名，避免并发解析多个包时互相踩。
     */
    private static File copyToPrivateCache(Context ctx, File src) {
        InputStream in = null;
        java.io.OutputStream out = null;
        try {
            if (!src.isFile()) {
                return null;
            }
            File dir = new File(ctx.getCacheDir(), "parse");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File dst = new File(dir, "tmp-" + System.currentTimeMillis() + "-"
                    + Thread.currentThread().getId() + ".apk");
            in = new java.io.FileInputStream(src);
            out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            return dst;
        } catch (Throwable t) {
            Log.e("复制到私有目录失败: " + src, t);
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    // ------------------------------------------------------------------
    // 路线 A
    // ------------------------------------------------------------------

    private static void fillFromPackageInfo(Context ctx, PackageManager pm, PackageInfo pi,
                                            ApkCard card, String sourcePath) {
        card.packageName = pi.packageName;
        card.versionName = pi.versionName;
        card.versionCode = pi.versionCode;

        if (pi.applicationInfo != null) {
            ApplicationInfo ai = pi.applicationInfo;
            // 这两行是加载包内资源的必要前置，否则 loadLabel / loadIcon 会失败
            ai.sourceDir = sourcePath;
            ai.publicSourceDir = sourcePath;
            try {
                CharSequence label = pm.getApplicationLabel(ai);
                if (label != null) {
                    card.label = label.toString();
                }
            } catch (Throwable t) {
                Log.w("读取应用名称失败: " + t);
            }
            // 图标：这是「列表里图标一片空白」的修复点。
            //
            // 先查磁盘缓存：解码图标是整个扫描里最重的一步（41.8 MB 的包单是这一步
            // 就要 2.4 秒），缓存命中后就不再碰 AssetManager。
            // 缓存键只用「打开文件就能拿到的元信息」（见 IconCacheKey），
            // 所以换版本/换文件时自然失效，不会出现「新图标沿用旧图标名」的问题。
            android.graphics.Bitmap icon = IconCache.load(ctx, sourcePath,
                    card.packageName, card.versionCode);
            if (icon == null) {
                icon = loadIconBitmap(pm, ai);
                if (icon != null) {
                    IconCache.store(ctx, sourcePath, card.packageName, card.versionCode, icon);
                }
            }
            card.icon = icon;
        }

        // minSdkVersion 没有公开 API，只能反射；4.3 上字段名就是 minSdkVersion
        card.minSdk = reflectInt(pi.applicationInfo, "minSdkVersion", -1);
        card.targetSdk = reflectInt(pi.applicationInfo, "targetSdkVersion", -1);
        int loc = reflectInt(pi.applicationInfo, "installLocation", -1);
        card.installLocation = loc == 1 ? "自动（优先内部存储）"
                : (loc == 2 ? "优先外部存储" : "默认");

        // 权限
        if (pi.requestedPermissions != null) {
            for (int i = 0; i < pi.requestedPermissions.length; i++) {
                if (pi.requestedPermissions[i] != null) {
                    card.permissions.add(pi.requestedPermissions[i]);
                }
            }
        }

        // 组件
        if (pi.activities != null) {
            card.activityCount = pi.activities.length;
            for (int i = 0; i < pi.activities.length; i++) {
                if (pi.activities[i] != null && pi.activities[i].exported) {
                    card.activityCountExported++;
                    addExported(card, "Activity", pi.activities[i].name);
                }
            }
        }
        if (pi.services != null) {
            card.serviceCount = pi.services.length;
            for (int i = 0; i < pi.services.length; i++) {
                if (pi.services[i] != null && pi.services[i].exported) {
                    card.serviceCountExported++;
                    addExported(card, "Service", pi.services[i].name);
                }
            }
        }
        if (pi.receivers != null) {
            card.receiverCount = pi.receivers.length;
            for (int i = 0; i < pi.receivers.length; i++) {
                if (pi.receivers[i] != null && pi.receivers[i].exported) {
                    card.receiverCountExported++;
                    addExported(card, "Receiver", pi.receivers[i].name);
                }
            }
        }
        if (pi.providers != null) {
            card.providerCount = pi.providers.length;
            for (int i = 0; i < pi.providers.length; i++) {
                if (pi.providers[i] != null && pi.providers[i].exported) {
                    addExported(card, "Provider", pi.providers[i].name);
                }
            }
        }

        // nativeAbis 在这里**不填** —— 需要 JarFile 遍历，代价高，
    }
    /**
     * 从 APK 里把图标加载成 Bitmap。
     *
     * 为什么必须转成 Bitmap，而不是直接留着 Drawable？
     *   从 APK 内资源加载出来的 Drawable 可能延迟加载，仍依赖那个 APK 的资源表；
     *   而解析用的临时 AssetManager 用完就关了，之后绘制会变成空白 —— 正是
     *   「图标一片空白」现象的成因之一。Bitmap 是自包含的，任何时候都能画。
     */
    private static android.graphics.Bitmap loadIconBitmap(PackageManager pm, ApplicationInfo ai) {
        Drawable d = null;
        try {
            d = pm.getApplicationIcon(ai);
        } catch (Throwable t) {
            Log.w("getApplicationIcon 失败: " + t);
        }
        if (d == null) {
            try {
                d = ai.loadIcon(pm);
            } catch (Throwable t) {
                Log.w("loadIcon 失败: " + t);
            }
        }
        if (d == null) {
            return null;
        }
        try {
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                return ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            }
            // 图标可能是 XML 矢量/选择器，需要先栅格化
            int w = d.getIntrinsicWidth();
            int h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = 96;
                h = 96;
            }
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, w, h);
            d.draw(canvas);
            return bmp;
        } catch (Throwable t) {
            Log.w("图标转 Bitmap 失败: " + t);
            return null;
        }
    }

    private static void addExported(ApkCard card, String kind, String name) {        if (name != null) {
            card.exportedComponents.add(kind + "  " + name);
        }
    }

    private static int reflectInt(Object target, String field, int def) {
        if (target == null) {
            return def;
        }
        try {
            Field f = target.getClass().getField(field);
            Object v = f.get(target);
            if (v instanceof Integer) {
                return (Integer) v;
            }
        } catch (Throwable ignored) {
        }
        return def;
    }


    /**
     * 只把 AndroidManifest.xml 读成字节数组。
     *
     * 代价说明：`new JarFile(apk)` 需要解析整个 APK 的中央目录，
     * 实测 3MB 的包约 100~180ms、41MB 的包高达 2.6 秒。
     * 所以这个方法只在「确实需要 AXML 字节」时才调用，
     * 而且每次解析只调用一次（结果复用，不重复开文件）。
     */
    private static byte[] readManifestBytes(String apkPath) {
        JarFile jar = null;
        InputStream in = null;
        try {
            jar = new JarFile(apkPath);
            JarEntry e = jar.getJarEntry("AndroidManifest.xml");
            if (e == null) {
                return null;
            }
            in = jar.getInputStream(e);
            return readAll(in, (int) e.getSize());
        } catch (Throwable t) {
            Log.w("读取 AndroidManifest.xml 失败: " + t);
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(jar);
        }
    }

    /**
     * 按需补上「CPU 架构」信息（详情页才需要）。
     *
     * 这是整个解析里最贵的一步（要遍历 APK 中央目录，41MB 的包要 2.6 秒），
     * 所以从 parse() 里挪出来，只在用户真正打开详情页时调用一次。
     */
    public static void ensureNativeAbis(String apkPath, ApkCard card) {
        if (card == null || apkPath == null) {
            return;
        }
        if (card.nativeAbis != null && !card.nativeAbis.isEmpty()) {
            return; // 已经读过了
        }
        List<String> abis = new ArrayList<String>();
        JarFile jar = null;
        try {
            jar = new JarFile(apkPath);
            Enumeration<JarEntry> en = jar.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String n = e.getName();
                if (n.startsWith("lib/") && n.endsWith(".so")) {
                    int slash = n.indexOf('/', 4);
                    if (slash > 4) {
                        String abi = n.substring(4, slash);
                        if (!abis.contains(abi)) {
                            abis.add(abi);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w("读取 so 架构失败: " + t);
        } finally {
            closeQuietly(jar);
        }
        card.nativeAbis = abis;
        Log.i("按需读取 so 架构: " + abis + " (" + new File(apkPath).getName() + ")");
    }

    // ------------------------------------------------------------------
    // 路线 B：自己解析二进制 AndroidManifest.xml
    // ------------------------------------------------------------------

    /**
     * 兜底路线：不用 PackageManager，自己解析 APK 里的 AndroidManifest.xml。
     *
     * 关于为什么直接走字节解析（这段历史值得记下来）：
     *   最初这里有两条路 —— 先用反射调 AssetManager.addAssetPath 挂载 APK，
     *   再用 am.openXml() 拿到 XmlResourceParser 遍历。但用真实的 android.jar
     *   编译时发现：**openXml 根本不是公开 API**，android.jar 里没有这个方法，
     *   代码通不过编译。
     *
     *   这一点很关键：它在 SDK 里不存在，意味着不同 ROM 上是否可用、签名如何
     *   都无法保证，靠反射去赌它反而更不可靠。所以现在只有字节解析这一条路，
     *   自己把 AXML 的字符串池和起始标签读出来 —— 行为完全确定，不依赖任何隐藏 API。
     */
    private static void fillFromBinaryManifest(String apkPath, ApkCard card) {
        parseRawBinaryXml(apkPath, card);
    }

    /**
     * 读已装应用的 minSdk，带两条回退。
     *
     * <p>顺序刻意是这样：
     * <ol>
     *   <li>先读已装应用的 APK 清单 —— 最可靠，任何版本都适用；</li>
     *   <li>读不到再试反射 {@code ApplicationInfo.minSdkVersion} ——
     *       在 API 24+ 的机器上这个字段存在，能兜住「APK 路径读不到」的情况；</li>
     *   <li>都不行就 -1，此时上层判「未知」而不显示这一段（宁可不显示，也不给错结论）。</li>
     * </ol>
     */
    private static int readInstalledMinSdk(PackageInfo installed, int targetSdkFallback) {
        try {
            if (installed != null && installed.applicationInfo != null
                    && installed.applicationInfo.sourceDir != null) {
                int[] sdk = readInstalledSdk(installed.applicationInfo.sourceDir);
                if (sdk != null && sdk[0] > 0) {
                    return sdk[0];
                }
            }
        } catch (Throwable t) {
            Log.w("从已装 APK 读 minSdk 失败: " + t);
        }
        return reflectInt(installed == null ? null : installed.applicationInfo,
                "minSdkVersion", -1);
    }

    /**
     * 读**已安装应用**的 APK 文件，只取 minSdk / targetSdk，返回 {min, target}。
     *
     * <h3>为什么需要这个，而不是直接用 PackageManager</h3>
     * 已装应用的信息本来可以从 {@code ApplicationInfo} 反射拿，但
     * {@code ApplicationInfo.minSdkVersion} 这个字段**是 API 24 才加进去的** ——
     * 在 Android 4.3 的 android.jar 里根本没有它，反射只会静默失败。
     * （{@code targetSdkVersion} 一直存在，所以那个能读到。）
     *
     * <p>这是实测发现的：诊断日志显示
     * {@code 已装 minSdk=-1 targetSdk=18}，于是「最低系统要求提高了」
     * 这个提示在目标机型上永远不会出现，而界面看上去只是「没变化」，
     * 完全分不清是「真没变」还是「没读到」。
     *
     * <p>所以改用最可靠的来源：直接读已装应用的 APK 清单。
     * 这条路径不需要复制文件，也不依赖任何高版本 API。
     *
     * @return {minSdk, targetSdk}，读不到的那一项为 -1；整体失败返回 null
     */
    public static int[] readInstalledSdk(String apkPath) {
        if (apkPath == null) {
            return null;
        }
        try {
            byte[] manifest = readManifestBytes(apkPath);
            if (manifest == null) {
                return null;
            }
            int[] sdk = probeSdkFromAxml(manifest, manifest.length);
            if (sdk == null) {
                return null;
            }
            return new int[]{sdk[0] > 0 ? sdk[0] : -1, sdk[1] > 0 ? sdk[1] : -1};
        } catch (Throwable t) {
            Log.w("读取已装应用的 SDK 信息失败: " + apkPath + " -> " + t);
            return null;
        }
    }

    /** 二进制 AXML 的块类型。 */
    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_START_TAG = 0x0102;
    /** Res_value 里「值存在字符串池中」的类型标记。 */
    private static final int TYPE_STRING = 0x03;
    /**
     * 整数类属性在 AXML 里的类型标记。
     *
     * 这一点很容易搞错：versionCode / minSdkVersion 这些属性类型是 0x10，
     * 但它们的 valueData **并不是那个整数本身**，而是字符串池里「十进制文本」的下标。
     * 早先只把 0x03 当作池下标，于是 versionCode 被读成了池下标那个数字，
     * 表现出来就是「版本代码 0」或者一个莫名其妙的数。
     */
    private static final int TYPE_INT_DEC = 0x10;
    /** 十六进制整数，同样是池下标。 */
    private static final int TYPE_INT_HEX = 0x11;

    /**
     * 解析二进制 AndroidManifest.xml。
     *
     * 文件结构（全部小端序）：
     *
     *   文件头         magic(0x00080003) + 文件总长
     *   ┌ 字符串池块  type=0x0001，含 stringCount / flags / 偏移表 / 池数据
     *   ├ 资源映射块  type=0x0180（这里跳过）
     *   └ 起始标签块  type=0x0102，第一个必然是被解析文件里第一个元素
     *                 对 AndroidManifest.xml 来说就是 <manifest>
     *
     * 每个属性（5 个字 = 20 字节）：
     *   ns(4)  nameIndex(4)  rawValueIndex(4)  valueSize(2)  valueRes0(1)  valueType(1)  valueData(4)
     *
     * 拿到起始标签的属性后，利用「XML 允许属性任意排列」这一事实，
     * 按属性名去取值，而不是依赖固定的顺序 —— 这是它比早先那版
     * 「在字节里猜字符串」可靠得多的原因。
     */
    private static void parseRawBinaryXml(String apkPath, ApkCard card) {
        JarFile jar = null;
        InputStream in = null;
        try {
            jar = new JarFile(apkPath);
            JarEntry entry = jar.getJarEntry("AndroidManifest.xml");
            if (entry == null) {
                card.parseError = "APK 内没有 AndroidManifest.xml";
                return;
            }
            in = jar.getInputStream(entry);
            byte[] buf = readAll(in, (int) entry.getSize());

            if (buf.length < 8) {
                card.parseError = "AndroidManifest.xml 太短，不是有效的二进制清单";
                return;
            }
            int magic = readInt16(buf, 0);
            if (magic != 0x0003) {
                card.parseError = "不认识的文件头（magic=0x"
                        + Integer.toHexString(magic) + "），可能不是二进制 AXML";
                return;
            }

            // ---- 遍历各块 ----
            //
            // 需要两个起始标签：
            //   第一个是 <manifest>（package / versionCode / versionName）
            //   另一个是 <uses-sdk>（minSdkVersion / targetSdkVersion）
            // 后者的信息很重要，因为 ApplicationInfo 里那两个字段是隐藏的，
            // 反射在部分 ROM（实测华为 C8816D）上取不到，只能从这里补。
            String[] strings = null;
            int[] manifestAttrs = null;
            int[] sdkAttrs = null;

            int pos = 8;
            while (pos + 8 <= buf.length) {
                int type = readInt16(buf, pos);
                int chunkSize = readInt32(buf, pos + 4);
                if (chunkSize < 8 || pos + chunkSize > buf.length) {
                    break; // 长度不合法，停止，用已解析到的部分
                }

                if (type == CHUNK_STRING_POOL && strings == null) {
                    strings = parseStringPool(buf, pos, chunkSize);
                } else if (type == CHUNK_START_TAG && strings != null) {
                    String tag = str(strings, readInt32(buf, pos + 20));
                    if ("manifest".equals(tag) && manifestAttrs == null) {
                        manifestAttrs = readAttrs(buf, pos);
                    } else if ("uses-sdk".equals(tag) && sdkAttrs == null) {
                        sdkAttrs = readAttrs(buf, pos);
                    }
                }

                if (manifestAttrs != null && sdkAttrs != null) {
                    break;
                }
                pos += chunkSize;
            }

            if (strings == null) {
                card.parseError = "没有找到 AXML 字符串池";
                return;
            }
            if (manifestAttrs == null) {
                card.parseError = "没有找到 manifest 起始标签";
                return;
            }

            // ---- 从 manifest 取基本信息 ----
            //
            // 注意 readAttrs 返回的是「5 个 int 一组」的扁平数组，一组的布局是：
            //   [0]=nameIndex  [1]=rawValueIndex  [2]=valueType  [3]=valueData  [4]=未用
            // 所以取每一项要按 i / i+1 / i+2 / i+3 来，不能整体错开一格。
            // （这里曾经错位过一次：把 valueType 当成 i+3 读，结果 versionCode
            //   读到的是相邻属性的垃圾位置，显示成 0。)
            for (int i = 0; i + 4 < manifestAttrs.length; i += 5) {
                String name = str(strings, manifestAttrs[i]);
                if (name == null) {
                    continue;
                }
                if ("package".equals(name)) {
                    card.packageName = str(strings, resolveValueIndex(strings,
                            manifestAttrs[i + 1], manifestAttrs[i + 2], manifestAttrs[i + 3]));
                } else if ("versionName".equals(name)) {
                    card.versionName = str(strings, resolveValueIndex(strings,
                            manifestAttrs[i + 1], manifestAttrs[i + 2], manifestAttrs[i + 3]));
                } else if ("versionCode".equals(name)) {
                    parseVersionNumber(strings, card, manifestAttrs[i + 1],
                            manifestAttrs[i + 2], manifestAttrs[i + 3]);
                }
            }

            // ---- 从 uses-sdk 补 minSdk / targetSdk ----
            applySdkAttrs(strings, sdkAttrs, card);

            if (card.packageName != null) {
                card.parseOk = true;
                Log.i("兜底解析成功：包名=" + card.packageName
                        + " 版本=" + card.versionName + "(" + card.versionCode + ")"
                        + " minSdk=" + card.minSdk + " targetSdk=" + card.targetSdk);
            } else {
                card.parseError = "AXML 里没有 package 属性";
            }
        } catch (Throwable t) {
            card.parseError = "二进制清单解析失败: " + t;
            Log.e("解析二进制清单失败", t);
        } finally {
            closeQuietly(in);
            closeQuietly(jar);
        }
    }

    /**
     * 读一个起始标签块的属性表，拍平成 int 数组，每 5 个一组：
     *   [nameIndex, rawValueIndex, valueType, valueData, 保留]
     *
     * 这样比开四个平行数组好传参，也不会因为某一处漏初始化而出错。
     */
    private static int[] readAttrs(byte[] buf, int chunkPos) {
        int headerSize = readInt16(buf, chunkPos + 2);
        int attrStart = readInt16(buf, chunkPos + 24);
        int attrCount = readInt16(buf, chunkPos + 28);
        int n = Math.max(0, attrCount);
        int[] out = new int[n * 5];
        int base = chunkPos + (headerSize > 0 ? headerSize : 36) + attrStart;
        for (int i = 0; i < n; i++) {
            int a = base + i * 20;
            if (a + 20 > buf.length) {
                break;
            }
            out[i * 5] = readInt32(buf, a + 4);        // nameIndex
            out[i * 5 + 1] = readInt32(buf, a + 8);    // rawValueIndex
            // valueSize(2) 在 a+12、valueRes0(1) 在 a+14，都跳过
            out[i * 5 + 2] = buf[a + 15] & 0xFF;       // valueType
            out[i * 5 + 3] = readInt32(buf, a + 16);   // valueData
        }
        return out;
    }

    /** 把 <uses-sdk> 的 minSdkVersion / targetSdkVersion 写进 card。 */
    private static void applySdkAttrs(String[] strings, int[] attrs, ApkCard card) {
        if (attrs == null) {
            return;
        }
        for (int i = 0; i + 4 < attrs.length; i += 5) {
            String name = str(strings, attrs[i]);
            if (name == null) {
                continue;
            }
            if ("minSdkVersion".equals(name)) {
                int v = resolveIntValue(strings, attrs[i + 1], attrs[i + 2], attrs[i + 3]);
                if (v > 0) {
                    card.minSdk = v;
                }
            } else if ("targetSdkVersion".equals(name)) {
                int v = resolveIntValue(strings, attrs[i + 1], attrs[i + 2], attrs[i + 3]);
                if (v > 0) {
                    card.targetSdk = v;
                }
            }
        }
    }

    /**
     * 取一个属性的整数值。
     *
     * 规则（用真实字节验证过，见 README 的「AXML 整数属性」一节）：
     *   · valueType = 0x10 (TYPE_INT_DEC) / 0x11 (TYPE_INT_HEX) 时，
     *     valueData **就是数值本身**，不要去当字符串池下标用；
     *   · valueType = 0x03 (TYPE_STRING) 时，valueData 是池下标，
     *     值可能是 "123" 这种文本，需要解析；
     *   · rawValueIndex >= 0 时，它指向池里的原始文本，优先用它。
     *
     * 之所以强调第一条：曾经想当然地认为「整数也应该去池里找」，
     * 结果 vdata=2018121400 被当成池下标，越界取到 null，versionCode 就成了 0。
     */
    private static int resolveIntValue(String[] strings, int rawIndex, int valueType, int valueData) {
        if (valueType == TYPE_INT_DEC || valueType == TYPE_INT_HEX) {
            return valueData;
        }
        if (rawIndex >= 0 && rawIndex < strings.length) {
            return parseInt(str(strings, rawIndex), valueData);
        }
        if (valueType == TYPE_STRING) {
            String s = str(strings, valueData);
            if (s != null) {
                return parseInt(s, valueData);
            }
        }
        return valueData;
    }

    /**
     * 从已经读到的 AXML 字节里补 minSdk / targetSdk。
     *
     * 这条路径**不再打开任何文件** —— 字节是 readAbisAndManifest 顺手带回来的。
     * 早先这里是自己按路径重开一次 JarFile，一个包多花 100~180ms，
     * 是扫描变慢的主因之一。
     */
    private static void fillSdkFromBytes(byte[] manifestBytes, ApkCard card) {
        if (manifestBytes == null) {
            return;
        }
        try {
            int[] sdk = probeSdkFromAxml(manifestBytes, manifestBytes.length);
            if (sdk != null) {
                if (card.minSdk <= 0 && sdk[0] > 0) {
                    card.minSdk = sdk[0];
                }
                if (card.targetSdk <= 0 && sdk[1] > 0) {
                    card.targetSdk = sdk[1];
                }
            }
        } catch (Throwable t) {
            Log.w("从 AXML 字节补 SDK 信息失败: " + t);
        }
    }

    /** readSdkOnly 的返回。 */
    private static final class SdkOnly {
        int min = -1;
        int target = -1;
    }


    private static void applySdkAttrsTo(String[] strings, int[] attrs, SdkOnly out) {
        for (int i = 0; i + 4 < attrs.length; i += 5) {
            String name = str(strings, attrs[i]);
            if (name == null) {
                continue;
            }
            if ("minSdkVersion".equals(name)) {
                int v = resolveIntValue(strings, attrs[i + 1], attrs[i + 2], attrs[i + 3]);
                if (v > 0) {
                    out.min = v;
                }
            } else if ("targetSdkVersion".equals(name)) {
                int v = resolveIntValue(strings, attrs[i + 1], attrs[i + 2], attrs[i + 3]);
                if (v > 0) {
                    out.target = v;
                }
            }
        }
    }

    /**
     * 把 versionCode 写进 card。
     *
     * 统一走 resolveIntValue：整数类属性在 AXML 里的值是「字符串池下标」，
     * 不是数值本身 —— 早先这里判断错了，导致 versionCode 读成 0。
     */
    private static void parseVersionNumber(String[] strings, ApkCard card,
                                           int rawIndex, int valueType, int valueData) {
        int v = resolveIntValue(strings, rawIndex, valueType, valueData);
        if (v >= 0) {
            card.versionCode = v;
        }
    }

    /**
     * 得到一个属性的「字符串池下标」。
     *
     * rawValueIndex 有效时它才是权威值（例如 versionName 这种本来就带引号的字符串）；
     * 否则看 valueType：字符串类型直接取 valueData 作为下标，其他类型没有池下标。
     */
    private static int resolveValueIndex(String[] strings, int rawIndex,
                                         int valueType, int valueData) {
        if (rawIndex >= 0 && rawIndex < strings.length) {
            return rawIndex;
        }
        if (valueType == TYPE_STRING) {
            return valueData;
        }
        return -1;
    }

    /** 从字符串池安全取串。 */
    private static String str(String[] pool, int index) {
        if (pool == null || index < 0 || index >= pool.length) {
            return null;
        }
        return pool[index];
    }

    /**
     * 解析 AXML 字符串池块。
     *
     * 块头布局（相对块起点）：
     *   0  type=0x0001
     *   2  headerSize=28
     *   4  chunkSize
     *   8  stringCount
     *   12 styleCount
     *   16 flags        bit0=1 表示 UTF-8 编码，bit8=1 表示已经是排序过的
     *   20 stringsStart （相对块起点的偏移）
     *   24 stylesStart
     *   28 起是 stringCount 个 4 字节偏移
     *
     * 每个字符串：先是一个长度字段（最高位为 1 要用两个 16 位来读），
     * 然后是数据，最后跟一个 0 结尾。
     */
    private static String[] parseStringPool(byte[] buf, int base, int chunkSize) {
        int stringCount = readInt32(buf, base + 8);
        int flags = readInt32(buf, base + 16);
        int stringsStart = readInt32(buf, base + 20);
        boolean utf8 = (flags & 0x00000100) != 0;

        if (stringCount <= 0 || stringCount > 200000) {
            return null;
        }
        String[] out = new String[stringCount];

        int offsetsBase = base + 28;
        int dataBase = base + stringsStart;

        for (int i = 0; i < stringCount; i++) {
            int offPos = offsetsBase + i * 4;
            if (offPos + 4 > buf.length) {
                break;
            }
            int off = readInt32(buf, offPos);
            int p = dataBase + off;
            if (p < 0 || p >= buf.length) {
                continue;
            }
            try {
                if (utf8) {
                    int len0 = buf[p] & 0xFF;
                    if ((len0 & 0x80) != 0) {
                        len0 = ((len0 & 0x7F) << 8) | (buf[p + 1] & 0xFF);
                        p += 2;
                    } else {
                        p += 1;
                    }
                    int len1 = buf[p] & 0xFF;
                    if ((len1 & 0x80) != 0) {
                        len1 = ((len1 & 0x7F) << 8) | (buf[p + 1] & 0xFF);
                        p += 2;
                    } else {
                        p += 1;
                    }
                    if (len1 >= 0 && p + len1 <= buf.length) {
                        // AXML 的 UTF-8 用的是标准 UTF-8，Java 的 new String 能直接处理
                        out[i] = new String(buf, p, len1, "UTF-8");
                    }
                } else {
                    int len0 = readInt16(buf, p);
                    if ((len0 & 0x8000) != 0) {
                        len0 = ((len0 & 0x7FFF) << 16) | readInt16(buf, p + 2);
                        p += 4;
                    } else {
                        p += 2;
                    }
                    int byteLen = len0 * 2;
                    if (len0 >= 0 && p + byteLen <= buf.length) {
                        out[i] = new String(buf, p, byteLen, "UTF-16LE");
                    }
                }
            } catch (Throwable t) {
                // 单个字符串读不出来不影响其它字符串
                Log.w("读取字符串池第 " + i + " 项失败: " + t);
            }
        }
        return out;
    }

    private static byte[] readAll(InputStream in, int sizeHint) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(
                sizeHint > 0 ? sizeHint : 8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------
    // 与已安装版本对比
    // ------------------------------------------------------------------

    private static void compareWithInstalled(Context ctx, PackageManager pm, ApkCard card) {
        if (card.packageName == null) {
            return;
        }
        PackageInfo installed = null;
        try {
            // 这里必须把 GET_PERMISSIONS 一起要上 —— 早先只查了 GET_SIGNATURES，
            // 结果拿不到已装版本的权限列表，「新版本多了哪些权限」这个功能就无从谈起。
            installed = pm.getPackageInfo(card.packageName,
                    PackageManager.GET_SIGNATURES | PackageManager.GET_PERMISSIONS);
        } catch (Throwable ignored) {
            // 没装过
        }
        if (installed == null) {
            card.installed = false;
            return;
        }
        card.installed = true;
        card.installedVersionName = installed.versionName;
        card.installedVersionCode = installed.versionCode;
        card.installedTargetSdk = reflectInt(installed.applicationInfo, "targetSdkVersion", -1);
        // minSdk 走「读已装 APK 的清单」，不要用反射。
        //
        // ApplicationInfo.minSdkVersion 是 API 24 才加进去的字段，
        // 在 Android 4.3 上反射必然失败（返回 -1），
        // 于是「最低系统要求提高了」这个提示永远不会出现 ——
        // 实测就是这个结果：已装 minSdk=-1，方向判成「未知」，整段不显示。
        card.installedMinSdk = readInstalledMinSdk(installed, card.installedTargetSdk);

        // ---- 权限差异对比（升级安装时最有价值的信息）----
        comparePermissions(ctx, installed, card);

        if (card.versionCode >= 0) {
            card.isUpgrade = card.versionCode > installed.versionCode;
            card.isDowngrade = card.versionCode < installed.versionCode;
            card.isSameVersion = card.versionCode == installed.versionCode;
        }

        try {
            Signature[] sigs = installed.signatures;
            if (sigs != null && sigs.length > 0) {
                card.installedSignerSha1 = sha1Hex(sigs[0].toByteArray());
            }
        } catch (Throwable t) {
            Log.w("读取已安装签名失败: " + t);
        }
    }

    /**
     * 对比「本次 APK 申请的权限」与「已安装版本申请的权限」，算出差异。
     *
     * 为什么这件事重要：应用常常在更新时顺手多要权限（权限扩张），
     * 而用户点「更新」时基本不会重看权限列表。原生安装器会单独提示，
     * 这个应用更应该做 —— 毕竟它的卖点就是「把权限讲清楚」。
     *
     * 实际对比逻辑在 {@link PermDiff}（不依赖 Android API，可离线穷举验证）。
     * 这里只负责取数据。
     */
    private static void comparePermissions(final Context ctx, PackageInfo installed, ApkCard card) {
        try {
            // installed.requestedPermissions 在少数情况下会是 null
            // （ROM 没提供、或该应用确实没申请权限）。
            // PermDiff 会把这个情况和「对比过、没有变化」区分开，
            // 避免把全部权限误报成「新增」。
            String[] oldPerms = installed.requestedPermissions;

            // 传入「按保护级别筛选」的分类器：只把危险级/签名级权限
            // 当作值得提醒的新增。
            //
            // 这个筛选是实测逼出来的：某次真实升级（KingRoot 4.6.0 → 5.4.0）
            // 逐条列出了 144 项「新增」，其中 139 项是**普通级**权限 ——
            // 普通级系统会自动授予、用户无从拒绝，一条条摆出来纯属噪音，
            // 把真正需要留意的少数几项（4 项危险级 + 1 项签名级）全淹没了。
            // 系统自带安装器同样只提示需要关注的权限。
            PermDiff.Result r = PermDiff.compare(card.permissions, oldPerms,
                    new PermDiff.Classifier() {
                        @Override
                        public boolean isNotable(String permission) {
                            return isNotablePermission(ctx, permission);
                        }
                    });

            card.installedPermissionsKnown = r.known;
            if (!r.known) {
                return;
            }
            card.addedPermissions.addAll(r.added);
            card.removedPermissions.addAll(r.removed);
            card.addedPermissionsTotal = r.addedTotal;
            card.addedPermissionsNormal = r.addedNormalCount;

            Log.i("权限差异 [" + card.packageName + "] 新增总计 " + r.addedTotal
                    + " 项（需关注 " + r.added.size() + "，普通级 "
                    + r.addedNormalCount + "），移除 " + r.removed.size() + " 项");
        } catch (Throwable t) {
            Log.w("权限差异对比失败: " + t);
            card.installedPermissionsKnown = false;
        }
    }

    /**
     * 判断一条新增权限是否「值得提醒用户」。
     *
     * **只在能确定是危险级或签名级时才返回 true。**
     *
     * 原则是「宁可少报，也不淹没重点」：
     *   · 危险级 —— 涉及隐私/安全，正常应当由用户同意 → 报
     *   · 签名级 —— 正常应用根本拿不到，出现了就该注意 → 报
     *   · 普通级 —— 系统自动授予，用户无从拒绝 → 不报
     *   · **查不到级别的**（权限由本机未安装的应用声明、或 ROM 权限表不全）
     *     → 不报，只计入「其他」数量
     *
     * 最后那条为什么这样定：一开始写的是「查不到就当作值得关注」，
     * 理由是「宁可多报」。结果在真实样本上炸了 —— 144 项新增里有 141 项
     * 查不到级别，全被放行，重点彻底淹没（正是用户报的那个问题）。
     * 这类权限绝大多数是应用自定义的普通权限，不值得逐条提醒。
     *
     * **内置表优先**：我们自己的风险模型比系统保护级别更贴近「对用户有没有隐私风险」
     * （系统那个级别回答的是「要不要在运行时申请」）。典型分歧仍是 INTERNET ——
     * 系统把它标成危险级，但那只是「能联网」，不构成隐私风险。
     * 若这里继续只看系统级别，就会出现「汇总说低风险、权限差异却说值得关注」的矛盾。
     */
    private static boolean isNotablePermission(Context ctx, String permission) {
        // 本应用的风险模型能裁决的，就按它判（覆盖 INTERNET 这类要减轻的）
        int builtin = Perms.builtinRiskRank(permission);
        if (builtin == Perms.BUILTIN_DANGEROUS_RANK) {
            return true;
        }
        if (builtin == Perms.BUILTIN_NOT_DANGEROUS_RANK) {
            return false;
        }
        // 内置表没覆盖 → 沿用系统保护级别（宁可保守，也不漏报冷门危险权限）
        try {
            PermissionInfo pi = ctx.getPackageManager().getPermissionInfo(permission, 0);
            if (pi == null) {
                return false;
            }
            int lvl = pi.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
            return lvl == PermissionInfo.PROTECTION_DANGEROUS
                    || lvl == PermissionInfo.PROTECTION_SIGNATURE
                    || lvl == PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM;
        } catch (Throwable ignored) {
            return false;
        }
    }


    // ------------------------------------------------------------------
    // 签名 / 哈希
    // ------------------------------------------------------------------

    /** 整包 SHA1（用于校验文件完整性/比对来源）。 */
    public static String sha1OfFile(String path) {
        return digestOfFile(path, "SHA-1");
    }

    public static String md5OfFile(String path) {
        return digestOfFile(path, "MD5");
    }

    private static String digestOfFile(String path, String algo) {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            in = new FileInputStream(path);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return toHex(md.digest());
        } catch (Throwable t) {
            Log.w("计算 " + algo + " 失败: " + t);
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    public static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return toHex(md.digest(data));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            if (v < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------

    private static int parseInt(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s);
        } catch (Throwable ignored) {
            return def;
        }
    }

    /**
     * 诊断用：直接从 AXML 字节数组里读 min/target SDK，返回 {min, target}。
     *
     * 之所以单独开一个「吃 byte[]」的入口，是因为 PerfProbeActivity 需要
     * 在不复制文件的前提下测量「只读 uses-sdk」的耗时。
     * 正常解析走 parseRawBinaryXml / readSdkOnly 那两条路，不用这个方法。
     */
    static int[] probeSdkFromAxml(byte[] buf, int len) {
        try {
            if (buf == null || len < 8 || readInt16(buf, 0) != 0x0003) {
                return null;
            }
            String[] strings = null;
            int[] sdkAttrs = null;
            int pos = 8;
            while (pos + 8 <= len) {
                int type = readInt16(buf, pos);
                int chunkSize = readInt32(buf, pos + 4);
                if (chunkSize < 8 || pos + chunkSize > len) {
                    break;
                }
                if (type == CHUNK_STRING_POOL && strings == null) {
                    strings = parseStringPool(buf, pos, chunkSize);
                } else if (type == CHUNK_START_TAG && strings != null) {
                    String tag = str(strings, readInt32(buf, pos + 20));
                    if ("uses-sdk".equals(tag)) {
                        sdkAttrs = readAttrs(buf, pos);
                        break;
                    }
                }
                pos += chunkSize;
            }
            if (strings == null || sdkAttrs == null) {
                return null;
            }
            SdkOnly s = new SdkOnly();
            applySdkAttrsTo(strings, sdkAttrs, s);
            return new int[] { s.min, s.target };
        } catch (Throwable t) {
            return null;
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 小端序读取工具
    //
    // AXML 全部使用小端序。Java 运行时的字节序在 Android 支持的所有平台上
    // 都是小端，所以直接用移位拼接即可，不需要判断 ByteOrder。
    // ------------------------------------------------------------------

    /** 读 16 位无符号整数。 */
    private static int readInt16(byte[] buf, int off) {
        if (off < 0 || off + 2 > buf.length) {
            return 0;
        }
        return (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8);
    }

    /** 读 32 位整数。 */
    private static int readInt32(byte[] buf, int off) {
        if (off < 0 || off + 4 > buf.length) {
            return 0;
        }
        return (buf[off] & 0xFF)
                | ((buf[off + 1] & 0xFF) << 8)
                | ((buf[off + 2] & 0xFF) << 16)
                | ((buf[off + 3] & 0xFF) << 24);
    }

    /** 供 UI 判断当前系统版本，便于显示个别提示。 */
    public static String deviceSummary() {
        return Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") / "
                + Build.MANUFACTURER + " " + Build.MODEL;
    }

    // ------------------------------------------------------------------
    // API 等级 → Android 版本名
    // ------------------------------------------------------------------

    /**
     * API 等级对应的 Android 版本名。
     *
     * 光给一个「API 23」用户是没感觉的，配上「Android 6.0」才直观。
     */
    public static String apiName(int api) {
        // 实现在 ApiNames（零依赖的纯映射表），这里只转发。
        // 保留这个方法名是因为详情页用了很久，改调用点不如留个转发省事，
        // 也不会有两份映射表走偏。
        return ApiNames.name(api);
    }

    /**
     * 完整描述一个 API 等级：优先显示 Android 版本名。
     * 例：23 -> "Android 6.0 (API 23)"
     */
    public static String describeApi(int api) {
        return ApiNames.describe(api);
    }

    /** 把字节数格式化成人类可读。 */
    public static String humanSize(long bytes) {        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(java.util.Locale.US, "%.2f MB", mb);
        }
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
    }
}
