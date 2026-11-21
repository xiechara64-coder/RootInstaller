// ===========================================================================
//  图标缓存：键生成 与 淘汰策略 的离线验证（**不属于 app 源码**）
//
//  IconCacheKey / IconCachePolicy 刻意不引用 Android API，就是为了能在 PC 上验证：
//  这两个类的逻辑错了，表现是「图标显示成别的包的」「缓存越用越大」，真机上很难查。
//
//  重点钉住两件事：
//    1. 缓存键必须随「版本 / 文件大小 / 时间戳 / 路径 / 包名」任一变化而变化 ——
//       否则会出现「新版本换了图标，界面还是旧图标」的问题。
//    2. 淘汰必须按「最久未用」优先，且降到上限的 70%，不能一删删到空。
//
//  跑法：由 run-tests.ps1 调用。
// ===========================================================================

import com.dsh.rootinstaller.IconCacheKey;
import com.dsh.rootinstaller.IconCachePolicy;

public class IconCacheCheck {

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("=== 图标缓存：键与淘汰策略 ===\n");

        // ---------- 1. 键的稳定性与形态 ----------
        System.out.println("1. 相同的输入必须得到相同的键");
        {
            String a = IconCacheKey.of("/sdcard/demo.apk", "com.example.demo", 3, 1000L, 2000L);
            String b = IconCacheKey.of("/sdcard/demo.apk", "com.example.demo", 3, 1000L, 2000L);
            expect("两次调用结果一致", a.equals(b));
            expect("长度 = " + IconCacheKey.KEY_LENGTH,
                    a.length() == IconCacheKey.KEY_LENGTH);
            expect("是合法的键（小写十六进制）", IconCacheKey.isValidKey(a));
            System.out.println("   键 = " + a);
            System.out.println();
        }

        // ---------- 2. 任一要素变化都必须换键 ----------
        //
        // 这一组是本功能的核心：用户担心的正是「新版本换了图标，但文件名没变」。
        // 只要版本号/大小/时间戳任一变化，键就会变，缓存自然失效。
        System.out.println("2. 五个要素任一变化都要换键");
        {
            String base = IconCacheKey.of("/sdcard/demo.apk", "com.example.demo", 3, 1000L, 2000L);
            String otherPath = IconCacheKey.of("/sdcard/other.apk", "com.example.demo", 3, 1000L, 2000L);
            String otherPkg = IconCacheKey.of("/sdcard/demo.apk", "com.example.other", 3, 1000L, 2000L);
            String otherVer = IconCacheKey.of("/sdcard/demo.apk", "com.example.demo", 4, 1000L, 2000L);
            String otherLen = IconCacheKey.of("/sdcard/demo.apk", "com.example.demo", 3, 1001L, 2000L);
            String otherTime = IconCacheKey.of("/sdcard/demo.apk", "com.example.demo", 3, 1000L, 2001L);

            expect("路径变化 → 换键", !base.equals(otherPath));
            expect("包名变化 → 换键", !base.equals(otherPkg));
            expect("版本号变化 → 换键（改图标必然伴随版本号变化）", !base.equals(otherVer));
            expect("文件大小变化 → 换键", !base.equals(otherLen));
            expect("修改时间变化 → 换键（覆盖同版本号重打包也能发现）", !base.equals(otherTime));
            System.out.println();
        }

        // ---------- 3. 脏输入不能抛异常 ----------
        System.out.println("3. null / 异常输入不崩，且仍产出合法键");
        {
            String k1 = IconCacheKey.of(null, null, -1, 0L, 0L);
            expect("null 路径与包名可用", IconCacheKey.isValidKey(k1));
            String k2 = IconCacheKey.of(null, null, -1, 0L, 0L);
            expect("同样输入仍然稳定", k1.equals(k2));
            String k3 = IconCacheKey.of("/sdcard/demo.apk", "com.example.demo", -1, 0L, 0L);
            expect("版本号 -1（解析失败）也能算", IconCacheKey.isValidKey(k3));
            System.out.println();
        }

        // ---------- 4. 包名前缀与文件名 ----------
        System.out.println("4. 同包的缓存文件名有共同前缀（用于清理旧版本）");
        {
            String p1 = IconCacheKey.packagePrefix("com.example.demo");
            String p2 = IconCacheKey.packagePrefix("com.example.demo");
            String p3 = IconCacheKey.packagePrefix("com.example.other");
            expect("同包名前缀稳定", p1.equals(p2));
            expect("不同包名前缀不同", !p1.equals(p3));
            expect("前缀长度 = " + IconCacheKey.PREFIX_LENGTH,
                    p1.length() == IconCacheKey.PREFIX_LENGTH);

            String key = IconCacheKey.of("/sdcard/demo.apk", "com.example.demo", 3, 1000L, 2000L);
            String name = IconCacheKey.fileName("com.example.demo", key);
            expect("文件名 = 前缀-键.png", name.equals(p1 + "-" + key + ".png"));
            expect("文件名以 .png 结尾", name.endsWith(".png"));
            System.out.println("   文件名 = " + name);
            System.out.println();
        }

        // ---------- 5. isValidKey 要能挡住脏文件名 ----------
        System.out.println("5. isValidKey 的边界");
        {
            expect("接受合法键", IconCacheKey.isValidKey("0123456789abcdef"));
            expect("拒绝 null", !IconCacheKey.isValidKey(null));
            expect("拒绝过短", !IconCacheKey.isValidKey("0123456789abcde"));
            expect("拒绝过长", !IconCacheKey.isValidKey("0123456789abcdef0"));
            expect("拒绝大写十六进制", !IconCacheKey.isValidKey("0123456789ABCDEF"));
            expect("拒绝非十六进制字符", !IconCacheKey.isValidKey("0123456789abcdeZ"));
            System.out.println();
        }

        // ---------- 6. 淘汰触发条件 ----------
        System.out.println("6. 未超限时不淘汰");
        {
            expect("条目数与体积都在限内",
                    !IconCachePolicy.needsTrim(10, 1024L,
                            IconCachePolicy.MAX_ENTRIES, IconCachePolicy.MAX_BYTES));
            expect("刚好等于上限不算超",
                    !IconCachePolicy.needsTrim(IconCachePolicy.MAX_ENTRIES,
                            IconCachePolicy.MAX_BYTES,
                            IconCachePolicy.MAX_ENTRIES, IconCachePolicy.MAX_BYTES));
            expect("超一个条目就算超",
                    IconCachePolicy.needsTrim(IconCachePolicy.MAX_ENTRIES + 1, 0L,
                            IconCachePolicy.MAX_ENTRIES, IconCachePolicy.MAX_BYTES));
            expect("超一个字节就算超",
                    IconCachePolicy.needsTrim(0, IconCachePolicy.MAX_BYTES + 1,
                            IconCachePolicy.MAX_ENTRIES, IconCachePolicy.MAX_BYTES));
            System.out.println();
        }

        // ---------- 7. 条目超限：最旧的先删，并且只降到 70% ----------
        System.out.println("7. 条目超限：按最久未用淘汰，降到上限的 70%");
        {
            int n = 10;
            long[] lastUsed = new long[n];
            long[] sizes = new long[n];
            for (int i = 0; i < n; i++) {
                lastUsed[i] = 1000L + i; // 下标 0 最旧
                sizes[i] = 10L;
            }
            int maxEntries = 5;      // 目标 = floor(5 * 0.7) = 3
            int[] evict = IconCachePolicy.chooseEvictions(lastUsed, sizes, maxEntries, 1024L * 1024L);
            expect("需要淘汰 7 项（剩 3 项）", evict.length == 7);
            boolean oldestFirst = true;
            for (int i = 0; i < evict.length; i++) {
                if (evict[i] != i) {
                    oldestFirst = false;
                }
            }
            expect("淘汰顺序是最旧的先（下标 0..6）", oldestFirst);
            System.out.println();
        }

        // ---------- 8. 体积超限 ----------
        System.out.println("8. 体积超限：按最久未用淘汰到 70% 以内");
        {
            int n = 4;
            long[] lastUsed = new long[]{10L, 20L, 30L, 40L};
            long[] sizes = new long[]{
                    1024L * 1024L, 1024L * 1024L, 1024L * 1024L, 1024L * 1024L
            };
            long maxBytes = 2L * 1024L * 1024L;  // 目标 = 1.4 MB
            int[] evict = IconCachePolicy.chooseEvictions(lastUsed, sizes, 1000, maxBytes);
            // 删 3 个后剩 1 MB ≤ 1.4 MB；删 2 个剩 2 MB 仍超
            expect("需要淘汰 3 项", evict.length == 3);
            boolean oldestFirst = evict[0] == 0 && evict[1] == 1 && evict[2] == 2;
            expect("淘汰的是最旧的 3 个", oldestFirst);
            System.out.println();
        }

        // ---------- 9. 边界输入 ----------
        System.out.println("9. 空数组 / 长度不一致时返回空，不抛异常");
        {
            expect("空数组 → 无需淘汰",
                    IconCachePolicy.chooseEvictions(new long[0], new long[0], 5, 100L).length == 0);
            expect("长度不一致 → 返回空（而不是越界崩溃）",
                    IconCachePolicy.chooseEvictions(new long[3], new long[2], 1, 1L).length == 0);
            expect("null → 返回空",
                    IconCachePolicy.chooseEvictions(null, null, 1, 1L).length == 0);
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
