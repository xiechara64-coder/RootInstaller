package com.dsh.rootinstaller;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 安装包列表的排序。
 *
 * 单独一个类的原因和 PermSorter / PermDiff 一样：不依赖 Android API，
 * 可以脱离设备做离线验证（排序的边界容易写错，比如字段缺失时的不稳定顺序）。
 *
 * 顺带说明一个原本「隐式」的行为：磁盘扫描出来的顺序是「内部存储 → SD 卡」，
 * 每个存储卷内部又是目录遍历顺序 —— 那个顺序对用户不可预期也不可控制。
 * 所以默认排序改用「修改时间倒序」（最近拿到的包排最前），更符合直觉。
 */
public final class ApkSorter {

    private ApkSorter() {
    }

    /**
     * 原地排序。
     *
     * @param list 要排序的列表（会被直接修改）
     * @param mode 见 Settings.SORT_APK_*
     */
    public static void sort(List<ApkCard> list, int mode) {
        if (list == null || list.size() < 2) {
            return;
        }
        Collections.sort(list, comparator(mode));
    }

    /** 取出对应排序方式的比较器（独立出来便于离线测试）。 */
    public static Comparator<ApkCard> comparator(int mode) {
        switch (mode) {
            case Settings.SORT_APK_NAME:
                return new ByName();
            case Settings.SORT_APK_SIZE:
                return new BySize();
            case Settings.SORT_APK_RISK:
                return new ByRisk();
            case Settings.SORT_APK_TIME:
            default:
                return new ByTime();
        }
    }

    /**
     * 按修改时间倒序（最近的在最前）。
     *
     * 选它当默认的理由：用户通常是「刚下载/刚拷贝进来的那个包要装」，
     * 最近的文件排前面最省事。
     */
    private static final class ByTime implements Comparator<ApkCard> {
        @Override
        public int compare(ApkCard a, ApkCard b) {
            // 时间大的排前面。这里手写比较而不用 Long.compare ——
            // 后者是 Java 7 才加的 API，手写一行就没有任何版本顾虑。
            long ta = a.fileModified;
            long tb = b.fileModified;
            if (ta != tb) {
                return tb > ta ? 1 : -1;
            }
            return cmpName(a, b);
        }
    }

    /** 按名称（显示名优先，没有则用文件名）。 */
    private static final class ByName implements Comparator<ApkCard> {
        @Override
        public int compare(ApkCard a, ApkCard b) {
            return cmpName(a, b);
        }
    }

    /** 按体积从大到小。 */
    private static final class BySize implements Comparator<ApkCard> {
        @Override
        public int compare(ApkCard a, ApkCard b) {
            long sa = a.fileSize;
            long sb = b.fileSize;
            if (sa != sb) {
                return sb > sa ? 1 : -1;
            }
            return cmpName(a, b);
        }
    }

    /**
     * 按权限风险从高到低。
     *
     * 这个排序按「权限危不危险」而不是文件属性排，
     * 适合想先看看「哪些包要的权限最凶」的场景。
     */
    private static final class ByRisk implements Comparator<ApkCard> {
        @Override
        public int compare(ApkCard a, ApkCard b) {
            int ra = effectiveRisk(a);
            int rb = effectiveRisk(b);
            if (ra != rb) {
                return rb - ra;
            }
            // 同级按安装状态排：未装过的排前面（那些才是可能真要装的）
            if (a.installed != b.installed) {
                return a.installed ? 1 : -1;
            }
            return cmpName(a, b);
        }

        private int effectiveRisk(ApkCard c) {
            // 「杀手级能力但无法外传」提升一档参与排序，
            // 否则它在中风险里会被普通中风险淹没
            return c.criticalButOffline ? 3 : c.riskLevel;
        }
    }

    /** 统一的名称比较，保证所有排序在字段相同时都有稳定的兜底顺序。 */
    private static int cmpName(ApkCard a, ApkCard b) {
        return nameOf(a).compareToIgnoreCase(nameOf(b));
    }

    private static String nameOf(ApkCard c) {
        if (c == null) {
            return "";
        }
        if (c.label != null && c.label.length() > 0) {
            return c.label;
        }
        return c.fileName == null ? "" : c.fileName;
    }
}
