package com.dsh.rootinstaller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限集合差异对比。
 *
 * 从 ApkParser 里抽出来的原因和 PermSorter 一样：这段逻辑不依赖任何 Android API，
 * 抽出来才能脱离设备做穷举验证 —— 而**「误报新增权限」是这里最危险的错误**：
 * 把一个老权限说成「新版本多要的」，会让用户对完全正常的更新产生不必要的警惕，
 * 几次之后他就不再相信这个提示了。
 */
public final class PermDiff {

    /** 对比结果。 */
    public static final class Result {
        /**
         * 值得用户关注的**新增**权限：危险级 + 签名级。
         *
         * 为什么只留这两档 —— 这是实测踩出来的：
         * 某个真实的升级场景（KingRoot 4.6.0 → 5.4.0）里，逐条列出的「新增」
         * 多达 144 项，其中 **139 项是普通级别**。而普通级别权限系统会
         * 自动授予、用户根本无从拒绝 —— 把它们一条条摆出来纯属噪音，
         * 反而把真正需要留意的少数几项淹没了。
         *
         * 系统自带安装器同样只提示需要关注的权限（用户反馈那边报 7 项，
         * 与本应用筛选后的数量级一致）。
         */
        public final List<String> added = new ArrayList<String>();
        /** 本次移除的权限（已装版本有、这次没有的），全部保留 —— 数量通常很少。 */
        public final List<String> removed = new ArrayList<String>();
        /**
         * 新增权限的总数（含被上面过滤掉的普通级别）。
         * 界面用它来交代「还有 N 项普通权限也新增了」，保证信息完整。
         */
        public int addedTotal;
        /** 新增的普通级别权限（系统自动授予，不需要用户同意）。 */
        public int addedNormalCount;
        /**
         * 是否成功拿到了已装版本的权限列表。
         *
         * false 表示**信息不可用**，此时 added/removed 必然为空 ——
         * 调用方必须把这个状态和「对比过了、没有变化」区分开，
         * 否则会把「拿不到数据」说成「权限没变」。
         */
        public final boolean known;

        Result(boolean known) {
            this.known = known;
        }

        public boolean hasChanges() {
            return !added.isEmpty() || !removed.isEmpty() || addedTotal > 0;
        }

        /** 是否有值得提醒用户的新增权限。 */
        public boolean hasNotableAdditions() {
            return !added.isEmpty();
        }
    }

    private PermDiff() {
    }

    /**
     * @param newPerms 本次 APK 申请的权限（可以是任意顺序、含重复）
     * @param oldPerms 已装版本申请的权限；**传 null 表示拿不到信息**
     * @param classifier 判断每条权限是否「值得用户关注」；传 null 表示全部保留
     *                   （离线测试用 null，设备上会传入按保护级别判断的实现）
     */
    public static Result compare(List<String> newPerms, String[] oldPerms,
                                Classifier classifier) {
        // 关键分支：拿不到已装版本的权限时，绝不能把全部权限当成「新增」。
        // 早期设计里差点这么做，那会造成灾难性的误报。
        if (oldPerms == null) {
            return new Result(false);
        }

        Result r = new Result(true);

        Set<String> oldSet = new HashSet<String>();
        for (int i = 0; i < oldPerms.length; i++) {
            if (oldPerms[i] != null) {
                oldSet.add(oldPerms[i]);
            }
        }

        Set<String> newSet = new HashSet<String>();
        List<String> newList = newPerms == null ? new ArrayList<String>() : newPerms;
        for (int i = 0; i < newList.size(); i++) {
            String p = newList.get(i);
            if (p != null) {
                newSet.add(p);
            }
        }

        // 新增：本次有、已装没有
        for (String p : newSet) {
            if (!oldSet.contains(p)) {
                r.addedTotal++;
                if (classifier == null || classifier.isNotable(p)) {
                    r.added.add(p);
                } else {
                    r.addedNormalCount++;
                }
            }
        }
        // 移除：已装有、本次没有
        for (String p : oldSet) {
            if (!newSet.contains(p)) {
                r.removed.add(p);
            }
        }
        return r;
    }

    /** 便捷重载：不筛选，全部保留（离线测试用）。 */
    public static Result compare(List<String> newPerms, String[] oldPerms) {
        return compare(newPerms, oldPerms, null);
    }

    /**
     * 判断一条权限是否「值得提醒用户」。
     *
     * 设备上会传入一个按保护级别实现的实例：
     * 危险级（需要用户同意）和签名级（能拿到会很不寻常）返回 true，
     * 普通级（系统自动授予、用户无从拒绝）返回 false。
     */
    public interface Classifier {
        boolean isNotable(String permission);
    }
}
