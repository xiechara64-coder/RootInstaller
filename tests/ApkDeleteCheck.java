// ===========================================================================
//  「删除安装包」的两块纯逻辑验证：能不能删（DeletablePaths）、选了什么（Selection）
//
//  为什么这两块要单独钉住：
//    · 删错文件是不可逆的事故，规则写错不会报错，只会安静地删掉不该删的东西；
//    · 多选的 bug 都是「少数几个才错」的那种（反选两次回不到原状、全选后数量算错）。
//
//  跑法：由 run-tests.ps1 第一组（纯 JVM）调用。
// ===========================================================================

import com.dsh.rootinstaller.DeletablePaths;
import com.dsh.rootinstaller.Selection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ApkDeleteCheck {

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("=== 删除安装包：保护规则 + 多选状态 ===\n");

        // ---------- 1. 可删的路径 ----------
        System.out.println("1. 用户自己放的安装包可以删");
        {
            expect("SD 卡下载目录",
                    DeletablePaths.canDelete("/sdcard/Download/app.apk"));
            expect("中文目录里的包",
                    DeletablePaths.canDelete("/storage/sdcard1/新文件夹/UserDictionaryProvider.apk"));
            expect("扩展名大写也算 APK",
                    DeletablePaths.canDelete("/sdcard/a.APK"));
            System.out.println("   文件名提取: " + DeletablePaths.fileNameOf("/sdcard/Download/app.apk"));
            expect("文件名提取正确",
                    "app.apk".equals(DeletablePaths.fileNameOf("/sdcard/Download/app.apk")));
            System.out.println();
        }

        // ---------- 2. 绝对不能删的路径 ----------
        //
        // 这一组是本文件的重点：这些路径删了会产生不可逆的损坏。
        System.out.println("2. 系统目录 / 已装应用 / 加密容器一律拒绝");
        {
            String[] blocked = {
                    "/system/app/Calculator.apk",
                    "/system/priv-app/Settings.apk",
                    "/vendor/app/Foo.apk",
                    "/cust/app/Bar.apk",
                    "/data/app/com.example-1/base.apk",
                    "/data/app-lib/com.example-1/libx.so.apk",
                    "/data/priv-app/X.apk",
                    "/mnt/asec/com.example-1/pkg.apk",
                    "/storage/sdcard1/asec_apks/com.mojang.minecraftpe.apk",
            };
            for (int i = 0; i < blocked.length; i++) {
                expect("拒绝 " + blocked[i], !DeletablePaths.canDelete(blocked[i]));
            }
            System.out.println("   给出的原因示例: " + DeletablePaths.blockedReason(blocked[0]));
            System.out.println();
        }

        // ---------- 3. 非法输入 ----------
        System.out.println("3. 非法输入不能当成可删");
        {
            expect("null 不可删", !DeletablePaths.canDelete(null));
            expect("空串不可删", !DeletablePaths.canDelete("   "));
            expect("非 apk 文件不可删", !DeletablePaths.canDelete("/sdcard/Download/readme.txt"));
            expect("null 的 isApk 为 false", !DeletablePaths.isApk(null));
            expect("blockedReason(null) 非空", DeletablePaths.blockedReason(null) != null);
            System.out.println();
        }

        // ---------- 4. 多选：基本操作 ----------
        System.out.println("4. 选中 / 取消 / 切换");
        {
            Selection s = new Selection();
            expect("初始为空", s.isEmpty() && s.size() == 0);
            expect("toggle 选中返回 true", s.toggle("/a.apk"));
            expect("再次 toggle 取消返回 false", !s.toggle("/a.apk"));
            expect("取消后为空", s.isEmpty());
            s.select("/a.apk");
            s.select("/b.apk");
            expect("select 两项", s.size() == 2 && s.isSelected("/a.apk") && s.isSelected("/b.apk"));
            s.deselect("/a.apk");
            expect("deselect 生效", s.size() == 1 && !s.isSelected("/a.apk"));
            s.clear();
            expect("clear 清空", s.isEmpty());
            expect("null 路径被忽略", !s.toggle(null) && s.isEmpty());
            s.select(null);
            expect("select(null) 不改变状态", s.isEmpty());
            System.out.println();
        }

        // ---------- 5. 全选 / 反选 ----------
        System.out.println("5. 全选与反选（反选两次必须回到原状）");
        {
            List<String> all = Arrays.asList("/a.apk", "/b.apk", "/c.apk");
            Selection s = new Selection();
            s.selectAll(all);
            expect("全选后 3 项", s.size() == 3);
            s.selectAll(all);
            expect("重复全选不会重复计数", s.size() == 3);

            List<String> before = sorted(s.selectedList());
            s.invert(all);
            expect("反选后为空", s.size() == 0);
            s.invert(all);
            expect("再反选回到全选", s.size() == 3);
            expect("反选两次后集合与原来一致", before.equals(sorted(s.selectedList())));

            // 只选一项再反选 → 选中剩下的两项
            Selection one = new Selection();
            one.select("/b.apk");
            one.invert(all);
            expect("反选后只选剩下的两项",
                    one.size() == 2 && one.isSelected("/a.apk") && one.isSelected("/c.apk")
                            && !one.isSelected("/b.apk"));
            System.out.println();
        }

        // ---------- 6. retainOnly：删除成功后清理选择 ----------
        System.out.println("6. 删掉文件后把选择里已经不存在的路径去掉");
        {
            Selection s = new Selection();
            s.selectAll(Arrays.asList("/a.apk", "/b.apk", "/c.apk"));
            s.retainOnly(Arrays.asList("/a.apk", "/c.apk"));
            expect("只保留还在的", s.size() == 2
                    && s.isSelected("/a.apk") && !s.isSelected("/b.apk") && s.isSelected("/c.apk"));
            s.retainOnly(new ArrayList<String>());
            expect("全没了就清空", s.isEmpty());
            s.select("/x.apk");
            s.retainOnly(null);
            expect("null → 清空", s.isEmpty());
            System.out.println();
        }

        // ---------- 7. 顺序 ----------
        System.out.println("7. 选中顺序按用户点选的先后（删除确认框按这个顺序列）");
        {
            Selection s = new Selection();
            s.select("/c.apk");
            s.select("/a.apk");
            s.select("/b.apk");
            List<String> order = s.selectedList();
            expect("顺序 c, a, b",
                    order.size() == 3 && order.get(0).equals("/c.apk")
                            && order.get(1).equals("/a.apk") && order.get(2).equals("/b.apk"));
            System.out.println("   顺序: " + order);
            System.out.println();
        }

        if (failures == 0) {
            System.out.println(">>> 全部通过");
        } else {
            System.out.println(">>> 有 " + failures + " 项未通过");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static List<String> sorted(List<String> in) {
        List<String> out = new ArrayList<String>(in);
        java.util.Collections.sort(out);
        return out;
    }

    private static void expect(String what, boolean ok) {
        if (ok) {
            System.out.println("   [OK] " + what);
        } else {
            System.out.println("   [!!] " + what);
            failures++;
        }
    }
}
