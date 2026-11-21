package com.dsh.rootinstaller;

/**
 * 图标缓存的淘汰策略（纯 Java，不引用 Android API，可离线验证）。
 *
 * <p>为什么要有上限：正常情况 154 个包也就 1 MB 左右，真正会膨胀的是**孤儿**——
 * 包被用户删掉了，它的缓存文件还留着。定时全量清理需要额外的调度器，收益不大；
 * 更简单的办法是「超过上限就按最久未用淘汰」，配合 {@code getCacheDir()}
 * （系统在存储不足时会自己清空该目录）作为最后一道保险。
 *
 * <p>淘汰目标是**降到上限的 70%**，而不是刚好卡在上限：否则每次多存一个图标就要
 * 淘汰一个，读写放大明显。
 */
public final class IconCachePolicy {

    /** 最多保留的缓存文件数。 */
    public static final int MAX_ENTRIES = 500;

    /** 缓存目录总大小上限（8 MB）。 */
    public static final long MAX_BYTES = 8L * 1024L * 1024L;

    /** 淘汰后保留的比例（相对上限）。 */
    public static final double KEEP_RATIO = 0.7d;

    private IconCachePolicy() {
    }

    /** 当前数量/体积是否已经超过上限，需要淘汰。 */
    public static boolean needsTrim(int count, long totalBytes) {
        return needsTrim(count, totalBytes, MAX_ENTRIES, MAX_BYTES);
    }

    public static boolean needsTrim(int count, long totalBytes, int maxEntries, long maxBytes) {
        return count > maxEntries || totalBytes > maxBytes;
    }

    /**
     * 计算应当删除哪些条目。
     *
     * @param lastUsed   每个缓存文件的「最后使用时间」（毫秒），越小的越旧
     * @param sizes      对应文件的大小（字节）
     * @param maxEntries 条目数上限
     * @param maxBytes   总字节上限
     * @return 需要删除的下标（按「最旧优先」的顺序）；不需要淘汰时返回空数组
     */
    public static int[] chooseEvictions(long[] lastUsed, long[] sizes,
                                        int maxEntries, long maxBytes) {
        if (lastUsed == null || sizes == null || lastUsed.length != sizes.length) {
            return new int[0];
        }
        int count = lastUsed.length;
        if (count == 0) {
            return new int[0];
        }

        long totalBytes = 0;
        for (int i = 0; i < count; i++) {
            totalBytes += sizes[i] < 0 ? 0 : sizes[i];
        }
        if (!needsTrim(count, totalBytes, maxEntries, maxBytes)) {
            return new int[0];
        }

        // 目标：条目数与体积都降到上限的 70%
        int targetEntries = (int) Math.floor(maxEntries * KEEP_RATIO);
        long targetBytes = (long) Math.floor(maxBytes * KEEP_RATIO);

        // 按最久未用排序（简单插入排序即可：条目数上限只有几百）
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        for (int i = 1; i < count; i++) {
            int cur = order[i];
            int j = i - 1;
            while (j >= 0 && lastUsed[order[j]] > lastUsed[cur]) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = cur;
        }

        // 从最旧的开始删，直到两个指标都降到目标以内
        int remaining = count;
        long remainingBytes = totalBytes;
        int evictCount = 0;
        for (int k = 0; k < count; k++) {
            if (remaining <= targetEntries && remainingBytes <= targetBytes) {
                break;
            }
            int idx = order[k];
            remaining--;
            remainingBytes -= (sizes[idx] < 0 ? 0 : sizes[idx]);
            evictCount++;
        }

        int[] result = new int[evictCount];
        System.arraycopy(order, 0, result, 0, evictCount);
        return result;
    }
}
