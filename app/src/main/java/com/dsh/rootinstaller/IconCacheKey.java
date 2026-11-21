package com.dsh.rootinstaller;

import java.security.MessageDigest;

/**
 * 图标磁盘缓存的**键**（纯 Java，不引用 Android API，可离线验证）。
 *
 * <h3>为什么不比对图标内容的哈希</h3>
 * 最直觉的做法是「解码出图标 → 算 MD5 → 看缓存里有没有」。问题是：**解码本身就是最贵的一步**
 * （41.8 MB 的包单是解图标就要 2.4 秒），为了判断要不要解码而先解码，等于缓存白做。
 *
 * <h3>实际采用的办法</h3>
 * 用「打开 APK 就能零成本拿到的元信息」组合成键：
 * <ul>
 *   <li>{@code canonicalPath} —— 不同文件当然要分开（同一张卡可能通过符号链接访问到）</li>
 *   <li>{@code packageName} —— 防止路径复用（删了再放一个同名包）时串味</li>
 *   <li>{@code versionCode} —— 作者发新版**必然**改它；改图标不改版本号的包，
 *       系统本身也不认作新版本</li>
 *   <li>{@code length} + {@code lastModified} —— 覆盖「同版本号被重新打包」这种少见情况</li>
 * </ul>
 * 这五项任意一个变化，键就变，缓存自然失效，不会出现「新图标沿用旧图标名」的问题。
 *
 * <p><b>已知局限</b>：如果有人刻意保持「同路径 + 同版本号 + 同大小 + 同时间戳」替换文件，
 * 缓存会命中旧图标。这属于对抗性场景，不值得为它付出每次解码的代价；
 * 兜底手段是设置页里的「清除图标缓存」。
 */
public final class IconCacheKey {

    /** 键的长度（十六进制字符数）。16 位 = 64 bit，缓存规模下碰撞概率可忽略。 */
    public static final int KEY_LENGTH = 16;

    /** 包名前缀的长度，用于「同一个包的旧缓存」清扫。 */
    public static final int PREFIX_LENGTH = 8;

    private IconCacheKey() {
    }

    /**
     * 生成缓存键。
     *
     * @param canonicalPath  APK 的规范路径（不要传符号链接路径，否则同一文件会有两个键）
     * @param packageName    包名
     * @param versionCode    版本号；解析失败时传 -1 也可以（负值同样参与计算）
     * @param length         APK 字节数
     * @param lastModified   文件最后修改时间（毫秒）
     * @return 16 位小写十六进制字符串；输入为 null 时按空串处理，不会抛异常
     */
    public static String of(String canonicalPath, String packageName, int versionCode,
                            long length, long lastModified) {
        // 用 \u0000 分隔，避免「a|b」和「a」+「b」这类拼接歧义
        StringBuilder sb = new StringBuilder(96);
        sb.append(canonicalPath == null ? "" : canonicalPath).append('\u0000')
          .append(packageName == null ? "" : packageName).append('\u0000')
          .append(versionCode).append('\u0000')
          .append(length).append('\u0000')
          .append(lastModified);
        return sha1Hex(sb.toString(), KEY_LENGTH);
    }

    /**
     * 同一个包所有缓存文件的公共前缀。
     *
     * <p>写入新图标时用它找到「同一包的旧缓存」并删掉，避免一个包留下多份。
     * 用包名哈希而不是包名本身，是因为包名里有 '.'、个别 ROM 上还可能出现
     * 不适合做文件名的字符。
     */
    public static String packagePrefix(String packageName) {
        return sha1Hex(packageName == null ? "" : packageName, PREFIX_LENGTH);
    }

    /** 缓存文件名：{@code <包名前缀>-<键>.png}。 */
    public static String fileName(String packageName, String key) {
        return packagePrefix(packageName) + "-" + key + ".png";
    }

    /** 校验一个键是否形如 {@link #KEY_LENGTH} 位小写十六进制（读缓存时防御脏文件名用）。 */
    public static boolean isValidKey(String key) {
        if (key == null || key.length() != KEY_LENGTH) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** 取 SHA-1 十六进制的前 {@code chars} 位。 */
    static String sha1Hex(String text, int chars) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (int i = 0; i < digest.length; i++) {
                int b = digest[i] & 0xFF;
                if (b < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(b));
            }
            return chars >= sb.length() ? sb.toString() : sb.substring(0, chars);
        } catch (Throwable t) {
            // SHA-1 一定存在；真出意外也不能让解析流程挂掉，退化成一个固定串
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < chars; i++) {
                sb.append('0');
            }
            return sb.toString();
        }
    }
}
