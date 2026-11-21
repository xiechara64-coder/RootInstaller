package com.dsh.rootinstaller;

import java.util.Set;

/**
 * 判断哪些缓存条目已经成为「孤儿」（对应的 APK 已经不在了）。
 *
 * <p>为什么要单独一个类、而且要能离线测：这里的判定一旦写错，
 * 表现是「缓存被误删」或者「垃圾永远不清」，两种都不会报错，只能靠用例守住。
 *
 * <h3>为什么不能「这次没扫到就立刻删」</h3>
 * 因为 **SD 卡可能没插 / 没挂载**。那时卡上所有 APK 都会从扫描结果里消失，
 * 如果立刻删缓存，就等于每次拔卡都把缓存清一遍 —— 插回去又得重新解析。
 *
 * <p>所以规则是两条同时成立才删：
 * <ol>
 *   <li>本次扫描**没有**用到这个键（说明文件可能没了）；</li>
 *   <li>这个缓存文件**超过宽限期**没被访问过（说明不是刚刚拔卡的临时现象）。</li>
 * </ol>
 *
 * <p>缓存命中时会刷新文件 mtime（见 ParseCache.load），所以「最后访问时间」
 * 天然就等于「这个 APK 最后一次还在的时间」。
 */
public final class CacheSweep {

    /** 宽限期：7 天。比「拔卡几天」这种常见情况留足余量。 */
    public static final long DEFAULT_GRACE_MILLIS = 7L * 24L * 60L * 60L * 1000L;

    private CacheSweep() {
    }

    /**
     * 选出应当删除的孤儿条目。
     *
     * @param lastUsed   每个缓存文件的最后访问时间（毫秒）
     * @param keys       对应的缓存键（文件名去掉后缀）
     * @param seen       本次扫描用到的键；null 表示「一个都没看到」（此时**不删任何东西**）
     * @param now        当前时间（毫秒）
     * @param graceMillis 宽限期
     * @return 需要删除的下标数组；没有孤儿时返回空数组
     */
    public static int[] chooseOrphans(long[] lastUsed, String[] keys, Set<String> seen,
                                      long now, long graceMillis) {
        if (lastUsed == null || keys == null || lastUsed.length != keys.length) {
            return new int[0];
        }
        // 一个都没扫到 = 很可能根本没读到存储（卡没挂载、权限异常）。
        // 这种情况下删缓存是灾难性的：宁可留着。
        if (seen == null || seen.isEmpty()) {
            return new int[0];
        }

        int n = lastUsed.length;
        int count = 0;
        int[] tmp = new int[n];
        for (int i = 0; i < n; i++) {
            String key = keys[i];
            if (key == null || seen.contains(key)) {
                continue;
            }
            // 时钟被改到过去时 lastUsed 可能大于 now，按「刚用过」处理，不删
            long age = now - lastUsed[i];
            if (age < 0) {
                continue;
            }
            if (age > graceMillis) {
                tmp[count++] = i;
            }
        }

        int[] result = new int[count];
        System.arraycopy(tmp, 0, result, 0, count);
        return result;
    }
}
