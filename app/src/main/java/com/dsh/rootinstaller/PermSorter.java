package com.dsh.rootinstaller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 权限列表的排序。
 *
 * 单独抽成一个类（而不是留在 DetailsActivity 里）有两个原因：
 *   · 这段逻辑不依赖任何界面状态，抽出来才能单独测、单独验证；
 *   · 排序规则以后还可能变（用户已经提过一次），放这里改一处就够。
 */
public final class PermSorter {

    private PermSorter() {
    }

    /**
     * 按指定方式排序，返回新列表（不改动传入的列表）。
     *
     * @param mode 见 Settings.SORT_PERM_*
     */
    public static List<String> sort(List<String> source,
                                   final Map<String, Perms.Info> table,
                                   int mode) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<String>();
        }
        // 声明顺序：原样返回
        if (mode == Settings.SORT_PERM_DECLARED) {
            return new ArrayList<String>(source);
        }

        List<String> copy = new ArrayList<String>(source);

        if (mode == Settings.SORT_PERM_ALPHA) {
            Collections.sort(copy, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    String sa = Perms.shortName(a);
                    String sb = Perms.shortName(b);
                    int c = sa.compareToIgnoreCase(sb);
                    // 短名相同时（理论上不会）用全名兜底，避免比较结果不稳定
                    return c != 0 ? c : a.compareToIgnoreCase(b);
                }
            });
            return copy;
        }

        // 默认：按风险等级 危险(1) → 普通(2) → 签名级(3) → 无说明(4)
        Collections.sort(copy, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int ra = Perms.riskRank(table, a);
                int rb = Perms.riskRank(table, b);
                if (ra != rb) {
                    return ra - rb;
                }
                // 同一档内再按名称排。
                // 这一步很重要：否则同档内的顺序取决于 APK 里的原始声明顺序，
                // 看起来像是随机排列，而且不同应用之间也没法对比。
                return Perms.shortName(a).compareToIgnoreCase(Perms.shortName(b));
            }
        });
        return copy;
    }

    /** 把排序结果拼成一行，用于日志与自检输出。 */
    public static String describe(List<String> permissions,
                                 Map<String, Perms.Info> table) {
        if (permissions == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < permissions.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String p = permissions.get(i);
            sb.append(Perms.shortName(p));
            sb.append('(').append(Perms.riskRank(table, p)).append(')');
        }
        return sb.toString();
    }
}
