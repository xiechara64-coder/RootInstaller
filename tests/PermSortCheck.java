// ===========================================================================
//  权限排序的离线验证程序（**不属于 app 源码**，不会被编进 APK）
//
//  为什么需要它：目标设备（华为 C8816D / Android 4.3）的 input swipe 注入
//  不可用，没法靠滚动把完整的权限列表翻出来看。而 PermSorter 和
//  Perms.lookup/riskRank 都不依赖任何 Android API，所以可以脱离设备直接验证。
//
//  跑法（在项目根目录）：
//     javac -encoding UTF-8 -d build/tests \
//         -classpath <SDK>/platforms/android-19/android.jar \
//         app/src/main/java/com/dsh/rootinstaller/PermSorter.java \
//         app/src/main/java/com/dsh/rootinstaller/Perms.java \
//         app/src/main/java/com/dsh/rootinstaller/Settings.java \
//         app/src/main/java/com/dsh/rootinstaller/Log.java \
//         tests/PermSortCheck.java
//     java -cp build/tests PermSortCheck
//
//  期望输出以 ">>> 全部通过" 结尾，退出码 0。
// ===========================================================================
import com.dsh.rootinstaller.Perms;
import com.dsh.rootinstaller.PermSorter;
import com.dsh.rootinstaller.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 权限排序的离线验证。
 *
 * 为什么要在电脑上单独跑：目标设备（华为 C8816D / 4.3）的 input swipe 注入不可用，
 * 没法靠滚动把完整的权限列表翻出来看。而 PermSorter 和 Perms.lookup/riskRank
 * 都不依赖任何 Android API，所以可以脱离设备直接验证排序结果。
 *
 * 编译（需要 Perms / PermSorter / Settings / Log 的 class）：
 *   javac -encoding UTF-8 -d out PermSortCheck.java
 * 运行：
 *   java -cp out PermSortCheck
 */
public class PermSortCheck {

    private static int failures = 0;

    public static void main(String[] args) {
        // 一份刻意打乱顺序的真实权限集合：
        //   INTERNET 危险、READ_PHONE_STATE 危险、VIBRATE 普通…
        //   还有一些系统表里查不到的自定义权限
        List<String> perms = new ArrayList<String>(Arrays.asList(
                "android.permission.WRITE_EXTERNAL_STORAGE",   // 危险
                "com.example.CUSTOM_UNKNOWN_PERM",             // 无说明
                "android.permission.VIBRATE",                  // 普通/危险(内置表)
                "android.permission.INTERNET",                 // 危险
                "android.permission.ACCESS_NETWORK_STATE",     // 普通
                "android.permission.READ_PHONE_STATE",         // 危险
                "android.permission.WAKE_LOCK",                // 普通
                "android.permission.ACCESS_COARSE_LOCATION",   // 危险
                "android.permission.READ_LOGS"                 // 签名级(系统表可能没有)
        ));

        Map<String, Perms.Info> table = new java.util.HashMap<String, Perms.Info>();
        // 模拟「系统权限表」（真实设备上是从 PackageManager 枚举出来的）
        put(table, "android.permission.INTERNET", true, "危险（涉及隐私/安全）");
        put(table, "android.permission.READ_PHONE_STATE", true, "危险（涉及隐私/安全）");
        put(table, "android.permission.ACCESS_COARSE_LOCATION", true, "危险（涉及隐私/安全）");
        put(table, "android.permission.WRITE_EXTERNAL_STORAGE", true, "危险（涉及隐私/安全）");
        put(table, "android.permission.VIBRATE", false, "普通（风险较低）");
        put(table, "android.permission.ACCESS_NETWORK_STATE", false, "普通（风险较低）");
        put(table, "android.permission.WAKE_LOCK", false, "普通（风险较低）");
        put(table, "android.permission.READ_LOGS", false, "签名级（仅同签名应用可用）");

        System.out.println("=== 输入（声明顺序）===");
        System.out.println("  " + PermSorter.describe(perms, table));
        System.out.println();

        // ---- 按风险 ----
        List<String> byRisk = PermSorter.sort(perms, table, Settings.SORT_PERM_RISK);
        System.out.println("=== 按风险等级 ===");
        System.out.println("  " + PermSorter.describe(byRisk, table));
        System.out.println();

        check("元素数量不变", byRisk.size() == perms.size(),
                byRisk.size() + " vs " + perms.size());
        check("元素集合不变", new HashSet<String>(byRisk).equals(new HashSet<String>(perms)),
                "集合比较");

        // 风险等级单调不减
        boolean mono = true;
        int prev = -1;
        StringBuilder ranks = new StringBuilder();
        for (String p : byRisk) {
            int r = Perms.riskRank(table, p);
            ranks.append(r).append(' ');
            if (r < prev) {
                mono = false;
            }
            prev = r;
        }
        check("风险等级单调不减", mono, "等级序列: " + ranks.toString().trim());

        // 危险的一定在最前面
        check("首项是危险权限",
                Perms.riskRank(table, byRisk.get(0)) == 1,
                byRisk.get(0) + " 等级=" + Perms.riskRank(table, byRisk.get(0)));

        // 无说明的一定在最后
        check("末项是无说明权限",
                Perms.riskRank(table, byRisk.get(byRisk.size() - 1)) == 4,
                byRisk.get(byRisk.size() - 1) + " 等级="
                        + Perms.riskRank(table, byRisk.get(byRisk.size() - 1)));

        // 4 档的顺序：不能出现「普通」排在「危险」前面
        boolean orderOk = true;
        StringBuilder bad = new StringBuilder();
        int lastSeen = 0;
        for (String p : byRisk) {
            int r = Perms.riskRank(table, p);
            if (r < lastSeen) {
                orderOk = false;
                bad.append(p).append(' ');
            }
            lastSeen = Math.max(lastSeen, r);
        }
        check("档位顺序正确(危险→普通→签名→无说明)", orderOk, bad.length() == 0 ? "无逆序" : bad.toString());

        // ---- 按字母 ----
        List<String> byAlpha = PermSorter.sort(perms, table, Settings.SORT_PERM_ALPHA);
        System.out.println();
        System.out.println("=== 按名称字母顺序 ===");
        System.out.println("  " + PermSorter.describe(byAlpha, table));
        boolean alphaOk = true;
        StringBuilder abad = new StringBuilder();
        for (int i = 1; i < byAlpha.size(); i++) {
            String a = Perms.shortName(byAlpha.get(i - 1)).toLowerCase();
            String b = Perms.shortName(byAlpha.get(i)).toLowerCase();
            if (a.compareTo(b) > 0) {
                alphaOk = false;
                abad.append(a).append(">").append(b).append(' ');
            }
        }
        check("字母序非递减", alphaOk, abad.length() == 0 ? "无逆序" : abad.toString());

        // ---- 声明顺序 ----
        List<String> byDeclared = PermSorter.sort(perms, table, Settings.SORT_PERM_DECLARED);
        check("声明顺序与输入完全一致", byDeclared.equals(perms), "逐项比较");

        // ---- 边界：空列表 / 单元素 ----
        check("空列表返回空", PermSorter.sort(new ArrayList<String>(), table,
                Settings.SORT_PERM_RISK).isEmpty(), "empty");
        check("null 返回空", PermSorter.sort(null, table,
                Settings.SORT_PERM_RISK).isEmpty(), "null");
        List<String> one = new ArrayList<String>(Arrays.asList("android.permission.INTERNET"));
        check("单元素排序正常", PermSorter.sort(one, table,
                Settings.SORT_PERM_RISK).size() == 1, "size=1");

        // ---- 边界：table 为 null（系统表读不出来时）----
        List<String> nullTable = PermSorter.sort(perms, null, Settings.SORT_PERM_RISK);
        check("权限表为 null 时不崩溃", nullTable.size() == perms.size(),
                "size=" + nullTable.size());

        System.out.println();
        if (failures == 0) {
            System.out.println(">>> 全部通过");
        } else {
            System.out.println(">>> 有 " + failures + " 项失败");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void put(Map<String, Perms.Info> table, String name,
                            boolean dangerous, String protection) {
        table.put(name, Perms.Info.create(name, Perms.shortName(name),
                "说明", protection, dangerous));
    }

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "  [通过] " : "  [失败] ") + what + "  (" + detail + ")");
        if (!ok) {
            failures++;
        }
    }
}
