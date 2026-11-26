// ===========================================================================
//  「同签名 + 同名称，但包名不同」的分组验证（纯 Java）
//
//  这条提示最怕两件事：
//    · 漏报：同一应用换了包名，用户以为在升级，结果装出第二份 —— 提示没出现；
//    · 误报：把「同一开发者的不同应用」也算进来。实测第一版只比签名时，
//      151 个包里有 140 个被报出来（厂商系统应用共用平台签名），提示等于噪音。
//  两种都不会报错，只能靠用例守住。
//
//  跑法：由 run-tests.ps1 第一组（纯 JVM）调用。
// ===========================================================================

import com.dsh.rootinstaller.SameSigGroups;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SameSigGroupsCheck {

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("=== 同签名 + 同名称、不同包名：分组 ===\n");

        String sigA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String sigB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        // ---------- 1. 核心场景：同签名 + 同名称 ----------
        System.out.println("1. 同签名 + 同名称、包名不同 → 双方都能看到对方");
        {
            List<String> pkgs = Arrays.asList("com.example.old", "com.example.new");
            List<String> certs = Arrays.asList(sigA, sigA);
            List<String> labels = Arrays.asList("示例应用", "示例应用");
            Map<String, SameSigGroups.Info> m = SameSigGroups.compute(pkgs, certs, labels);
            expect("两个包都有提示", m.size() == 2);
            SameSigGroups.Info i1 = m.get("com.example.old");
            SameSigGroups.Info i2 = m.get("com.example.new");
            expect("old 看到 1 个", i1 != null && i1.count == 1
                    && "com.example.new".equals(i1.summary));
            expect("new 看到 1 个", i2 != null && i2.count == 1
                    && "com.example.old".equals(i2.summary));
            System.out.println();
        }

        // ---------- 2. 同签名但名称不同：不该报 ----------
        //
        // 这是第一版在真机上翻车的场景：厂商系统应用共用平台签名，
        // 但它们是不同的应用，报出来只是噪音。
        System.out.println("2. 同签名但名称不同 → 不提示（避免误报）");
        {
            Map<String, SameSigGroups.Info> m = SameSigGroups.compute(
                    Arrays.asList("com.vendor.settings", "com.vendor.music"),
                    Arrays.asList(sigA, sigA),
                    Arrays.asList("设置", "音乐"));
            expect("没有分组", m.isEmpty());
            System.out.println();
        }

        // ---------- 3. 同名称但签名不同：不该报 ----------
        System.out.println("3. 同名称但签名不同 → 不提示");
        {
            Map<String, SameSigGroups.Info> m = SameSigGroups.compute(
                    Arrays.asList("com.a", "com.b"),
                    Arrays.asList(sigA, sigB),
                    Arrays.asList("同名应用", "同名应用"));
            expect("没有分组", m.isEmpty());
            System.out.println();
        }

        // ---------- 4. 名称规范化 ----------
        System.out.println("4. 名称首尾空白、内部多空格、大小写都视为同一名称");
        {
            Map<String, SameSigGroups.Info> m = SameSigGroups.compute(
                    Arrays.asList("com.a", "com.b"),
                    Arrays.asList(sigA, sigA),
                    Arrays.asList("  Demo App ", "demo  app"));
            expect("规范化后算同一组", m.size() == 2 && m.get("com.a").count == 1);
            System.out.println();
        }

        // ---------- 5. 同包名重复：不能把自己算成"别人" ----------
        System.out.println("5. 同一个包名出现多次");
        {
            Map<String, SameSigGroups.Info> m = SameSigGroups.compute(
                    Arrays.asList("com.a", "com.a"),
                    Arrays.asList(sigA, sigA),
                    Arrays.asList("应用", "应用"));
            expect("不产生提示", m.isEmpty());
            System.out.println();
        }

        // ---------- 6. 三个包 ----------
        System.out.println("6. 三个包同签名同名称 → 每个看到另外两个");
        {
            Map<String, SameSigGroups.Info> m = SameSigGroups.compute(
                    Arrays.asList("com.a", "com.b", "com.c"),
                    Arrays.asList(sigA, sigA, sigA),
                    Arrays.asList("应用", "应用", "应用"));
            expect("三个都有提示", m.size() == 3);
            SameSigGroups.Info a = m.get("com.a");
            expect("数量 = 2", a != null && a.count == 2);
            expect("清单含 b 和 c", a != null && a.summary.contains("com.b")
                    && a.summary.contains("com.c"));
            expect("清单不含自己", a != null && !a.summary.contains("com.a"));
            System.out.println("   com.a 看到: " + (a == null ? "null" : a.summary));
            System.out.println();
        }

        // ---------- 7. 缺证书 / 缺名称：不参与 ----------
        System.out.println("7. 证书或名称为空 → 不参与分组");
        {
            expect("缺证书",
                    SameSigGroups.compute(
                            Arrays.asList("com.a", "com.b"),
                            Arrays.asList(null, sigA),
                            Arrays.asList("应用", "应用")).isEmpty());
            expect("缺名称",
                    SameSigGroups.compute(
                            Arrays.asList("com.a", "com.b"),
                            Arrays.asList(sigA, sigA),
                            Arrays.asList(null, "应用")).isEmpty());
            expect("名称为空白",
                    SameSigGroups.compute(
                            Arrays.asList("com.a", "com.b"),
                            Arrays.asList(sigA, sigA),
                            Arrays.asList("   ", "应用")).isEmpty());
            System.out.println();
        }

        // ---------- 8. 两组互相独立 ----------
        System.out.println("8. 两组不同名称的应用互不影响");
        {
            Map<String, SameSigGroups.Info> m = SameSigGroups.compute(
                    Arrays.asList("a1", "a2", "b1", "b2"),
                    Arrays.asList(sigA, sigA, sigB, sigB),
                    Arrays.asList("甲", "甲", "乙", "乙"));
            expect("四个都有提示", m.size() == 4);
            expect("a1 只看到 a2", m.get("a1") != null && "a2".equals(m.get("a1").summary));
            expect("b2 只看到 b1", m.get("b2") != null && "b1".equals(m.get("b2").summary));
            System.out.println();
        }

        // ---------- 9. 边界输入 ----------
        System.out.println("9. 空/长度不一致/null 不抛异常");
        {
            expect("空列表", SameSigGroups.compute(new ArrayList<String>(),
                    new ArrayList<String>(), new ArrayList<String>()).isEmpty());
            expect("null", SameSigGroups.compute(null, null, null).isEmpty());
            expect("长度不一致", SameSigGroups.compute(
                    Arrays.asList("a"), Arrays.asList(sigA, sigB),
                    Arrays.asList("甲")).isEmpty());
            System.out.println();
        }

        if (failures == 0) {
            System.out.println(">>> 全部通过");
        } else {
            System.out.println(">>> 有 " + failures + " 项未通过");
        }
        System.exit(failures == 0 ? 0 : 1);
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
