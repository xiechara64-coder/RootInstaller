package com.dsh.rootinstaller;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个待安装 APK 的全部可展示信息。
 *
 * 这就是「比系统安装器多出来的东西」的载体：系统只给一个权限列表，
 * 这里额外给出包名、版本、签名、组件、体积、哈希、以及和已安装版本的对比。
 */
public class ApkCard {

    // ---- 基础 ----
    public String filePath;
    public long fileSize;
    public long fileModified;
    public String fileName;

    /**
     * 这个包来自哪里：内部存储还是 SD 卡。
     *
     * 为什么需要区分：设备同时有内部存储和真实 SD 卡时，两者的 APK 会混在一个列表里，
     * 用户看不出装的是哪一个（尤其是同一应用在两个位置各有一份时）。
     */
    public boolean fromSdCard;

    /** 该包所在的存储卷根路径，用于在详情页展示（例如 /storage/sdcard1）。 */
    public String storageRoot;

    // ---- 从 APK 里解析出来的 ----
    public String packageName;
    public String versionName;
    public int versionCode = -1;
    public String label;
    public int minSdk = -1;
    public int targetSdk = -1;
    public String installLocation = "";
    public final List<String> permissions = new ArrayList<String>();
    public int activityCount;
    public int serviceCount;
    public int receiverCount;
    public int providerCount;
    public int activityCountExported;
    public int serviceCountExported;
    public int receiverCountExported;
    public List<String> exportedComponents = new ArrayList<String>();
    public List<String> nativeAbis = new ArrayList<String>();

    // ---- 与已安装版本的对比 ----
    public boolean installed;
    public String installedVersionName;
    public int installedVersionCode = -1;

    /**
     * 已安装版本的 targetSdk。
     *
     * 用来判断「targetSdk ≥ 30 必须有 v2+ 签名」这条规则是否适用：
     * 本次包与已装版本只要有一个达到 30，升级时就会被系统按新规则校验。
     */
    public int installedTargetSdk = -1;

    /**
     * 已安装版本的 minSdk。
     *
     * 和本次包的 minSdk 对比，可以看出新版本有没有抬高最低系统要求
     * （抬高了就意味着更老的设备从此升级不上去了）。
     */
    public int installedMinSdk = -1;

    /** 覆盖安装：新版本号更高。 */
    public boolean isUpgrade;
    /** 降级安装：新版本号更低（需要 -d 参数）。 */
    public boolean isDowngrade;
    /** 版本号相同。 */
    public boolean isSameVersion;
    /** 已安装版本的签名摘要（SHA1），用于比对签名是否一致。 */
    public String installedSignerSha1;

    /**
     * 本次 APK 的签名摘要（SHA1）。
     *
     * <p>不管这个包有没有装，都应该有值 —— 签名是文件自身的属性，
     * 和「装没装」没有关系。取的是签名者证书的 SHA1，
     * 和系统给出的 {@code Signature} 是同一份字节，所以能直接比较。
     *
     * <p>只有 v2/v3 签名的包同样能取到（从 APK Signing Block 里读）。
     */
    public String archiveSignerSha1;

    // ---- 签名状态（由 ApkSignatures.inspect 填充）----

    /**
     * 签名状态：见 {@link ApkSignatures#STATUS_*}。
     *
     * <p>刻意把「未签名」和「有签名但本机不认」分开：前者是包的问题，
     * 后者（例如只有 v2/v3）包本身完全正常，只是老系统装不上。
     * 混成一句「未签名」会冤枉一个正常的包 —— 这正是要避免的误报。
     */
    public int signatureStatus = ApkSignatures.STATUS_UNKNOWN;

    /** 是否含 v1（Jar）签名。这项决定能不能装在 Android 7.0 以前的老系统上。 */
    public boolean sigV1;
    /** 是否含 v2（APK Signing Block）签名。 */
    public boolean sigV2;
    /** 是否含 v3 签名。 */
    public boolean sigV3;
    /** 是否含 v4 签名。 */
    public boolean sigV4;

    /** 完整性核对结果：见 {@link ApkSignatures#INTEGRITY_*}。 */
    public int signatureIntegrity = ApkSignatures.INTEGRITY_UNKNOWN;

    /** 完整性核对不通过时的具体原因。 */
    public String signatureIntegrityDetail;

    /** 签名状态的一句话说明（给人看的，含排错信息）。 */
    public String signatureNote;

    /** MANIFEST.MF 里记录的版本号，用于发现「改了包没重签名」。 */
    public String manifestVersionCode;

    // ---- 解析状态 ----
    public boolean parseOk;
    public String parseError;
    /** 解析是否走了二进制 AndroidManifest 兜底方案。 */
    public boolean usedFallbackParser;

    /**
     * 从 APK 里抽出来的图标。
     *
     * 这里存 Bitmap 而不是 Drawable：Drawable 可能仍持有 APK 内部资源的引用，
     * 而解析时临时创建的 AssetManager 用完就关了，之后绘制会变成空白。
     * 转成 Bitmap 才是自包含的，随时能画。
     */
    public android.graphics.Bitmap icon;

    /**
     * 是否做过「完整解析」。
     *
     * 完整解析需要把 APK 复制到私有目录，成本高，所以只在打开详情页时做；
     * 列表扫描只用轻量解析（快），图标等行可见时再异步补。
     */
    public boolean fullParse;

    // ---- 权限风险汇总（由 PermAbilities.summarize 填充）----

    /** 危险权限的数量。 */
    public int dangerousPermissionCount;
    /** 总体风险等级：3=高 2=中 1=低 0=无明显风险。 */
    public int riskLevel;
    /** 一句话结论，例如「这个应用可以读取你的短信，还能把它们上传出去。」 */
    public String riskHeadline;
    /** 列表徽标文案，例如「高风险权限」「高风险权限（无法外传）」。 */
    public String riskBadge;
    /** 有杀手级能力但不联网（徽标要特别说明「无法外传」）。 */
    public boolean criticalButOffline;

    // ---- 与已安装版本的权限差异（升级安装时用）----

    /**
     * 本次版本**新增**的、值得提醒的权限（危险级 + 签名级）。
     *
     * 这是升级安装时最该警惕的东西：应用可能在更新时悄悄多要权限
     * （「权限扩张」），而用户点「更新」时通常不会重看权限列表。
     *
     * 只保留危险级/签名级 —— 普通级权限系统会自动授予、用户无从拒绝，
     * 逐条列出纯属噪音（实测某次升级 144 项新增里 139 项是普通级）。
     */
    public List<String> addedPermissions = new ArrayList<String>();

    /** 新增权限的**总数**（含被过滤掉的普通级），用于界面上如实交代完整数量。 */
    public int addedPermissionsTotal;

    /** 新增权限中属于普通级（系统自动授予）的数量。 */
    public int addedPermissionsNormal;

    /** 本次版本**移除**的权限（已装版本有、这次没有的）。属于好消息，但一并列出更透明。 */
    public List<String> removedPermissions = new ArrayList<String>();

    /** 是否成功取到了已装版本的权限列表（取不到时上面两个列表为空，不能当作「没有变化」）。 */
    public boolean installedPermissionsKnown;

    public boolean hasPermission(String name) {
        for (int i = 0; i < permissions.size(); i++) {
            if (name.equals(permissions.get(i))) {
                return true;
            }
        }
        return false;
    }
}
