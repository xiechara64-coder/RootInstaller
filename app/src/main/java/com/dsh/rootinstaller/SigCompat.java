package com.dsh.rootinstaller;

/**
 * 「这套签名方案，能不能装到某个 Android 版本上」的判定。
 *
 * <p>刻意做成不引用任何 Android API 的纯逻辑类：两端各有一条硬规则，
 * 方向相反，最容易写反。放在这里就能在 PC 上把每种组合跑一遍，
 * 而不是等真机上装失败了再去猜。
 *
 * <h3>两条方向相反的规则</h3>
 * <ul>
 *   <li><b>没有 v1</b>：Android 7.0（API 24）以前只认 v1（Jar）签名，
 *       所以老系统上装不上。包本身是正常的。</li>
 *   <li><b>只有 v1，且 targetSdk ≥ 30</b>：从 Android 11（API 30）起，
 *       targetSdk ≥ 30 的应用必须带 v2 或更高版本的签名，
 *       否则直接被拒装（报 {@code NO_SIGNATURE} /
 *       「No signature found in package of version 2 or newer」）。
 *       这是打包配置问题，不是包坏了。</li>
 * </ul>
 *
 * <p>把这两条说成同一件事，或者漏掉一条，用户都会拿到一个
 * 无法解释的安装失败：老机器上装不上、或者新机器上装不上，
 * 而界面什么都没提。
 */
public final class SigCompat {

    private SigCompat() {
    }

    /** Android 7.0：从这里开始支持 v2，并且仍然认 v1。 */
    public static final int API_V2_INTRODUCED = 24;

    /** Android 11：从这里开始，targetSdk ≥ 30 的包必须带 v2+ 签名。 */
    public static final int API_V2_REQUIRED_FOR_NEW_TARGET = 30;

    // ---- 判定结果 ----

    /** 没问题。 */
    public static final int OK = 0;
    /** 老系统（API < 24）只认 v1，而这个包没有 v1 → 装不上。 */
    public static final int BLOCK_MISSING_V1_ON_OLD_OS = 1;
    /** targetSdk ≥ 30 却没有 v2+，新系统（API ≥ 30）直接拒装。 */
    public static final int BLOCK_MISSING_V2_FOR_NEW_TARGET = 2;

    /**
     * 判断「能不能装到本机」。
     *
     * <p>这是用来决定**要不要在点安装前拦住**的判定，所以只回答
     * 「在这台机器上会不会失败」，不掺「别的机器上会不会失败」。
     * 比如「只有 v1 且 targetSdk 30」在本机（旧系统）上是能装的，
     * 就不能拦 —— 拦了会把用户明明装得上的包挡在门外。
     *
     * @param deviceSdk     本机 API 级别
     * @param v1            是否含 v1 签名
     * @param v2OrV3        是否含 v2 或 v3 签名
     * @param effectiveTargetSdk 有效 targetSdk（本次包与已装版本取较大值），取不到传 -1
     * @return 见本类的 BLOCK_* / OK 常量
     */
    public static int installBlocker(int deviceSdk, boolean v1, boolean v2OrV3,
                                     int effectiveTargetSdk) {
        if (!v1 && deviceSdk < API_V2_INTRODUCED) {
            return BLOCK_MISSING_V1_ON_OLD_OS;
        }
        if (v1 && !v2OrV3 && effectiveTargetSdk >= API_V2_REQUIRED_FOR_NEW_TARGET
                && deviceSdk >= API_V2_REQUIRED_FOR_NEW_TARGET) {
            return BLOCK_MISSING_V2_FOR_NEW_TARGET;
        }
        return OK;
    }

    /**
     * 取有效 targetSdk：本次包与已装版本取较大值。
     *
     * <p>为什么取较大值：覆盖安装时，只要有一边达到 30，
     * 系统就会按新规则校验签名。取较大值不会漏报。
     * 两边都取不到（-1）时返回 -1。
     */
    public static int effectiveTargetSdk(int apkTargetSdk, int installedTargetSdk) {
        return apkTargetSdk > installedTargetSdk ? apkTargetSdk : installedTargetSdk;
    }

    /**
     * 这个 targetSdk 是否已经触发「必须有 v2+」的要求。
     */
    public static boolean requiresV2OrHigher(int effectiveTargetSdk) {
        return effectiveTargetSdk >= API_V2_REQUIRED_FOR_NEW_TARGET;
    }

    /**
     * 把 API 级别说成用户认得的 Android 版本名。
     *
     * <p>实现统一在 {@link ApiNames}，这里只是转发 —— 免得出现两份版本名映射，
     * 一处改了另一处没改。
     */
    public static String androidName(int api) {
        return ApiNames.name(api);
    }

    /**
     * 带 API 号的完整描述，例如「Android 4.3 (API 18)」。
     *
     * <p>实现统一在 {@link ApiNames}。
     */
    public static String describeApi(int api) {
        return ApiNames.describe(api);
    }
}
