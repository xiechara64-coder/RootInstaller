package com.dsh.rootinstaller;

/**
 * 「这个文件能不能删」的判断（纯 Java，可离线测）。
 *
 * <p>为什么要把这件事单独拎出来：界面上删一个包，最坏的结果不是删不掉，
 * 而是**删掉了不该删的东西**。所以规则要显式、可测、只有一处。
 *
 * <h3>这些目录一律不删</h3>
 * <ul>
 *   <li>{@code /system}、{@code /vendor}、{@code /cust}、{@code /firmware} —— 系统分区，
 *       删了轻则功能异常，重则需要刷机；</li>
 *   <li>{@code /data/app}、{@code /data/app-lib}、{@code /data/priv-app} —— 已安装应用的
 *       实际文件，删掉等于破坏系统对它的记录（正确做法是 pm uninstall）；</li>
 *   <li>路径里包含 {@code /asec} 的 —— 那是加密容器的挂载点
 *       （本机就有 {@code /storage/sdcard1/asec_apks/*}），
 *       删掉可能让对应的已装应用直接崩溃。</li>
 * </ul>
 *
 * <p>剩下的就是用户自己放的安装包目录（/sdcard、/storage/sdcard1 等），这些才是可删的。
 */
public final class DeletablePaths {

    /** 这些前缀下的文件不删。 */
    private static final String[] PROTECTED_PREFIXES = {
            "/system/",
            "/vendor/",
            "/cust/",
            "/firmware/",
            "/data/app/",
            "/data/app-lib/",
            "/data/priv-app/",
            "/data/dalvik-cache/",
            "/data/data/",
            "/mnt/asec/",
            "/mnt/secure/asec/",
    };

    private DeletablePaths() {
    }

    /**
     * 返回不能删除的原因（给用户看的短句）；可以删除时返回 null。
     *
     * @param path APK 的绝对路径
     */
    public static String blockedReason(String path) {
        if (path == null || path.trim().length() == 0) {
            return "路径为空";
        }
        String p = path.replace('\\', '/');
        if (!isApk(p)) {
            return "不是 .apk 文件";
        }
        for (int i = 0; i < PROTECTED_PREFIXES.length; i++) {
            if (p.startsWith(PROTECTED_PREFIXES[i])) {
                return "位于系统/应用目录（" + PROTECTED_PREFIXES[i] + "），不能删除";
            }
        }
        if (p.indexOf("/asec") >= 0) {
            return "位于加密容器（asec）里，删掉可能弄坏已安装的应用";
        }
        if (p.endsWith("/")) {
            return "这是一个目录";
        }
        return null;
    }

    /** 是否允许删除。 */
    public static boolean canDelete(String path) {
        return blockedReason(path) == null;
    }

    /** 是否是 .apk 结尾（大小写不敏感）。 */
    public static boolean isApk(String path) {
        if (path == null) {
            return false;
        }
        return path.toLowerCase(java.util.Locale.US).endsWith(".apk");
    }

    /** 取出文件名，用于提示里显示。 */
    public static String fileNameOf(String path) {
        if (path == null) {
            return "";
        }
        int i = path.lastIndexOf('/');
        return i >= 0 && i + 1 < path.length() ? path.substring(i + 1) : path;
    }
}
