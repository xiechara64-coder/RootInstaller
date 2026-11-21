package com.dsh.rootinstaller;

/**
 * API 等级 ↔ Android 版本名 的映射。
 *
 * <p>单独拆成一个类，是因为这份表被三处用到：
 * <ul>
 *   <li>{@link ApkParser} 展示基本信息的「最低系统 / 目标系统」</li>
 *   <li>{@link SigCompat} 描述 targetSdk 对应的 Android 版本</li>
 *   <li>{@link SdkChange} 描述升级前后的 SDK 变化</li>
 * </ul>
 * 早先这张表长在 {@code ApkParser} 里，而 {@code ApkParser} 依赖 Android API，
 * 于是纯逻辑的类想用一下版本名就得跟着依赖 Android。
 * 抽出来之后它零依赖，纯 JVM 用例也能直接用。
 *
 * <p>光给一个「API 23」用户是没感觉的，配上「Android 6.0」才直观。
 */
public final class ApiNames {

    private ApiNames() {
    }

    /**
     * 完整的版本名，带「Android」前缀。例：23 → "Android 6.0"。
     *
     * @param api API 等级；≤ 0 表示未声明
     */
    public static String name(int api) {
        switch (api) {
            case 1: return "Android 1.0";
            case 2: return "Android 1.1";
            case 3: return "Android 1.5";
            case 4: return "Android 1.6";
            case 5: return "Android 2.0";
            case 6: return "Android 2.0.1";
            case 7: return "Android 2.1";
            case 8: return "Android 2.2";
            case 9: return "Android 2.3";
            case 10: return "Android 2.3.3";
            case 11: return "Android 3.0";
            case 12: return "Android 3.1";
            case 13: return "Android 3.2";
            case 14: return "Android 4.0";
            case 15: return "Android 4.0.3";
            case 16: return "Android 4.1";
            case 17: return "Android 4.2";
            case 18: return "Android 4.3";
            case 19: return "Android 4.4";
            case 20: return "Android 4.4W";
            case 21: return "Android 5.0";
            case 22: return "Android 5.1";
            case 23: return "Android 6.0";
            case 24: return "Android 7.0";
            case 25: return "Android 7.1";
            case 26: return "Android 8.0";
            case 27: return "Android 8.1";
            case 28: return "Android 9";
            case 29: return "Android 10";
            case 30: return "Android 11";
            case 31: return "Android 12";
            case 32:
                // 12L 是给大屏的独立版本，单独标出来更准确
                return "Android 12L";
            case 33: return "Android 13";
            case 34: return "Android 14";
            case 35: return "Android 15";
            case 36: return "Android 16";
            default:
                // 比上表更新的版本，就笼统表示 —— 至少比只显示一个裸数字友好
                return api > 30 ? "Android 11 之后" : ("API " + api);
        }
    }

    /**
     * 完整描述一个 API 等级。例：23 → "Android 6.0 (API 23)"。
     */
    public static String describe(int api) {
        if (api <= 0) {
            return "未声明";
        }
        return name(api) + " (API " + api + ")";
    }

    /**
     * 简短描述，只给版本名不加 API 号。例：23 → "Android 6.0"。
     *
     * <p>用在已经很拥挤、或者上下文里不需要精确 API 号的地方。
     */
    public static String shortName(int api) {
        if (api <= 0) {
            return "未声明";
        }
        return name(api);
    }
}
