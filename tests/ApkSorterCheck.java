// ===========================================================================
//  安装包列表排序的离线验证（**不属于 app 源码**，不会被编进 APK）
//
//  排序的坑主要在边界：字段缺失时顺序是否稳定、空列表/单元素是否安全、
//  以及「判等」的兜底比较会不会把结果打乱。ApkSorter 不依赖 Android API，
//  可以脱离设备穷举验证。
//
//  跑法（在项目根目录）：
//     javac -encoding UTF-8 -d build/tests \
//         app/src/main/java/com/dsh/rootinstaller/ApkSorter.java \
//         app/src/main/java/com/dsh/rootinstaller/ApkCard.java \
//         app/src/main/java/com/dsh/rootinstaller/Settings.java \
//         app/src/main/java/com/dsh/rootinstaller/Log.java \
//         tests/ApkSorterCheck.java
//     java -cp build/tests ApkSorterCheck
//
//  期望输出以 ">>> 全部通过" 结尾，退出码 0。
// ===========================================================================

import com.dsh.rootinstaller.ApkCard;
import com.dsh.rootinstaller.ApkSorter;
import com.dsh.rootinstaller.Settings;

import java.util.ArrayList;
import java.util.List;

public class ApkSorterCheck {

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("=== 安装包列表排序验证 ===\n");

        // ---------- 1. 按时间倒序 ----------
        List<ApkCard> l = list(
                card("旧", 1000L, 10, 1),
                card("新", 5000L, 20, 0),
                card("中", 3000L, 30, 2));
        ApkSorter.sort(l, Settings.SORT_APK_TIME);
        show("1. 按时间倒序", l);
        expect("最近的在最前", "新".equals(name(l, 0)));
        expect("最旧的在最后", "旧".equals(name(l, 2)));

        // ---------- 2. 按名称 ----------
        l = list(card("bbb", 1, 1, 0), card("aaa", 2, 2, 0), card("ccc", 3, 3, 0));
        ApkSorter.sort(l, Settings.SORT_APK_NAME);
        show("2. 按名称", l);
        expect("顺序为 aaa,bbb,ccc", "aaa".equals(name(l, 0))
                && "bbb".equals(name(l, 1)) && "ccc".equals(name(l, 2)));

        // ---------- 3. 名称忽略大小写 ----------
        l = list(card("Zebra", 1, 1, 0), card("apple", 2, 2, 0));
        ApkSorter.sort(l, Settings.SORT_APK_NAME);
        show("3. 名称忽略大小写", l);
        expect("apple 应排在 Zebra 前", "apple".equals(name(l, 0)));

        // ---------- 4. 按体积从大到小 ----------
        l = list(card("小", 1, 10, 0), card("大", 2, 900, 0), card("中", 3, 100, 0));
        ApkSorter.sort(l, Settings.SORT_APK_SIZE);
        show("4. 按体积倒序", l);
        expect("大的在前", "大".equals(name(l, 0)) && "小".equals(name(l, 2)));

        // ---------- 5. 按权限风险倒序 ----------
        ApkCard low = card("低危", 1, 1, 1);
        ApkCard mid = card("中危", 2, 2, 2);
        ApkCard high = card("高危", 3, 3, 3);
        ApkCard offline = card("高危但离线", 4, 4, 2);
        offline.criticalButOffline = true;
        l = list(low, mid, high, offline);
        ApkSorter.sort(l, Settings.SORT_APK_RISK);
        show("5. 按权限风险倒序", l);
        expect("无风险/低风险在最后", "低危".equals(name(l, 3)));
        expect("高风险在最前两位（含'杀手级但离线'）",
                l.get(0).riskLevel == 3 || l.get(0).criticalButOffline);
        expect("'杀手级但离线'不能被普通中风险淹没",
                index(l, "高危但离线") < index(l, "中危"));

        // ---------- 6. 稳定兜底：主字段相同则按名称 ----------
        l = list(card("bbb", 1000L, 10, 0), card("aaa", 1000L, 10, 0));
        ApkSorter.sort(l, Settings.SORT_APK_TIME);
        show("6. 时间完全相同（应回退到名称）", l);
        expect("回退到名称排序", "aaa".equals(name(l, 0)));

        // ---------- 7. 边界：空列表 / 单元素 ----------
        List<ApkCard> empty = new ArrayList<ApkCard>();
        ApkSorter.sort(empty, Settings.SORT_APK_TIME);
        expect("空列表不报错", empty.isEmpty());

        List<ApkCard> one = list(card("only", 5, 5, 0));
        ApkSorter.sort(one, Settings.SORT_APK_SIZE);
        expect("单元素不变", one.size() == 1 && "only".equals(name(one, 0)));

        // ---------- 8. 边界：null 列表 ----------
        ApkSorter.sort(null, Settings.SORT_APK_TIME);
        expect("null 列表不报错", true);

        // ---------- 9. 边界：label 缺失时用文件名 ----------
        ApkCard noLabel = new ApkCard();
        noLabel.fileName = "aaa.apk";
        noLabel.label = null;
        ApkCard withLabel = card("bbb", 1, 1, 0);
        l = list(withLabel, noLabel);
        ApkSorter.sort(l, Settings.SORT_APK_NAME);
        show("9. label 缺失时回退到文件名", l);
        // 注意断言不能用 label 比对：noLabel 的 label 就是 null。
        // ApkSorter 内部用的是 nameOf()（label 为空时回退到 fileName），
        // 所以这里直接比对象身份，才对应它的实际行为。
        expect("无 label 的项按其文件名(aaa.apk)应排在 bbb 前", l.get(0) == noLabel);

        // ---------- 10. 未知的排序方式应回退到默认（按时间），不能崩 ----------
        l = list(card("旧", 1000L, 1, 0), card("新", 9000L, 1, 0));
        ApkSorter.sort(l, 999);
        show("10. 非法排序值", l);
        expect("回退到按时间", "新".equals(name(l, 0)));

        System.out.println();
        if (failures == 0) {
            System.out.println(">>> 全部通过");
        } else {
            System.out.println(">>> 有 " + failures + " 项失败");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static ApkCard card(String name, long mtime, long size, int risk) {
        ApkCard c = new ApkCard();
        c.label = name;
        c.fileName = name + ".apk";
        c.fileModified = mtime;
        c.fileSize = size;
        c.riskLevel = risk;
        return c;
    }

    private static List<ApkCard> list(ApkCard... cards) {
        List<ApkCard> l = new ArrayList<ApkCard>();
        for (ApkCard c : cards) {
            l.add(c);
        }
        return l;
    }

    private static String name(List<ApkCard> l, int i) {
        return l.get(i).label;
    }

    private static int index(List<ApkCard> l, String label) {
        for (int i = 0; i < l.size(); i++) {
            if (label.equals(l.get(i).label)) {
                return i;
            }
        }
        return -1;
    }

    private static void show(String title, List<ApkCard> l) {
        StringBuilder sb = new StringBuilder();
        for (ApkCard c : l) {
            if (sb.length() > 0) {
                sb.append(" -> ");
            }
            sb.append(c.label);
        }
        System.out.println("[" + title + "]  " + sb);
        System.out.println();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  [通过] " : "  [失败] ") + what);
        if (!ok) {
            failures++;
        }
    }
}
