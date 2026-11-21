package com.dsh.rootinstaller;


/**
 * 升级安装时「最低系统 / 目标系统要求变了」的判定。
 *
 * <p>为什么值得单独做：用户点「更新」时默认以为只是修 bug、加功能，
 * 很少会想到「这次更新之后，我另一台老机器就装不上了」。
 * 而 minSdk 被抬高恰恰是这种影响 —— 它不报错、不提示，
 * 只是从此以后老设备上再也装不上新版本。
 *
 * <p>targetSdk 的变化则主要说明「这个应用适配到哪个 Android 版本了」：
 * 抬高通常意味着适配了新系统的行为变更（也意味着更合规），
 * 值得如实告诉用户，但不必渲染成风险。
 *
 * <p>刻意不引用任何 Android API：这样能在 PC 上把各种组合穷举验证。
 * 判定方向最容易写反（谁比谁大），写反了就会给出相反的结论。
 */
public final class SdkChange {

    private SdkChange() {
    }

    // ---- minSdk 的变化方向 ----

    /** 取不到任何一边的值，无法比较。 */
    public static final int UNKNOWN = 0;
    /** 没变化。 */
    public static final int SAME = 1;
    /** 抬高了 (新 &gt; 旧)：老设备从此装不上。这是要重点提醒的一种。 */
    public static final int RAISED = 2;
    /** 降低了 (新 &lt; 旧)：放宽了支持范围。 */
    public static final int LOWERED = 3;

    /** 一次比较的结果。字段全部 public，方便离线用例直接断言。 */
    public static final class Result {

        /** minSdk 的变化方向，见上面的常量。 */
        public int minSdkDirection = UNKNOWN;
        /** targetSdk 的变化方向，见上面的常量。 */
        public int targetSdkDirection = UNKNOWN;

        /** 旧值（已安装版本），取不到为 -1。 */
        public int oldMinSdk = -1;
        public int oldTargetSdk = -1;
        /** 新值（本次 APK），取不到为 -1。 */
        public int newMinSdk = -1;
        public int newTargetSdk = -1;

        /** 有没有任何一边发生了变化（供调用方决定要不要渲染这一段）。 */
        public boolean anyChange() {
            return minSdkDirection == RAISED || minSdkDirection == LOWERED
                    || targetSdkDirection == RAISED || targetSdkDirection == LOWERED;
        }

        /** minSdk 是不是被抬高了 —— 这是唯一需要「警告」语气的一种。 */
        public boolean minSdkRaised() {
            return minSdkDirection == RAISED;
        }
    }

    /**
     * 比较已安装版本与本次 APK 的 minSdk / targetSdk。
     *
     * @param installedMinSdk    已装版本的 minSdk，取不到传 -1
     * @param installedTargetSdk 已装版本的 targetSdk，取不到传 -1
     * @param newMinSdk          本次 APK 的 minSdk，取不到传 -1
     * @param newTargetSdk       本次 APK 的 targetSdk，取不到传 -1
     */
    public static Result compare(int installedMinSdk, int installedTargetSdk,
                                 int newMinSdk, int newTargetSdk) {
        Result r = new Result();
        r.oldMinSdk = installedMinSdk;
        r.oldTargetSdk = installedTargetSdk;
        r.newMinSdk = newMinSdk;
        r.newTargetSdk = newTargetSdk;
        r.minSdkDirection = direction(installedMinSdk, newMinSdk);
        r.targetSdkDirection = direction(installedTargetSdk, newTargetSdk);
        return r;
    }

    /**
     * 判断方向。任一边取不到就返回 {@link #UNKNOWN} ——
     * 拿不到值时不猜，宁可不说，也不给一个可能相反的结论。
     */
    static int direction(int oldValue, int newValue) {
        if (oldValue <= 0 || newValue <= 0) {
            return UNKNOWN;
        }
        if (newValue > oldValue) {
            return RAISED;
        }
        if (newValue < oldValue) {
            return LOWERED;
        }
        return SAME;
    }

}
