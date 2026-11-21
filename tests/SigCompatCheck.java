// ===========================================================================
//  签名方案 × 系统版本 兼容性判定的离线验证（**不属于 app 源码**）
//
//  这里要钉住的是两条**方向相反**的硬规则，它们最容易被写反或漏掉：
//
//    ① 没有 v1  → Android 7.0（API 24）以前装不上
//    ② 只有 v1 且 targetSdk ≥ 30 → Android 11+ 拒装（NO_SIGNATURE）
//
//  这两条一旦说反，用户拿到的就是一个无法解释的安装失败：
//  明明界面说没问题，装上去就被系统拒绝。
//
//  SigCompat 不引用任何 Android API，所以可以把组合穷举验证。
//
//  跑法（在项目根目录）：
//     javac -encoding UTF-8 -d build/tests \
//         app/src/main/java/com/dsh/rootinstaller/SigCompat.java \
//         tests/SigCompatCheck.java
//     java -cp build/tests SigCompatCheck
//
//  期望输出以 ">>> 全部通过" 结尾，退出码 0。
// ===========================================================================

import com.dsh.rootinstaller.SigCompat;

public class SigCompatCheck {

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("=== 签名方案 × 系统版本 兼容性验证 ===\n");

        // ---------- ① 没有 v1：老系统装不上 ----------
        System.out.println("1. 只做 v2/v3（没有 v1）");
        expect("Android 4.3 (18) 装不上，要拦住",
                SigCompat.installBlocker(18, false, true, 18)
                        == SigCompat.BLOCK_MISSING_V1_ON_OLD_OS);
        expect("Android 6.0 (23) 也装不上，要拦住",
                SigCompat.installBlocker(23, false, true, 23)
                        == SigCompat.BLOCK_MISSING_V1_ON_OLD_OS);
        expect("Android 7.0 (24) 开始就能装，不能拦",
                SigCompat.installBlocker(24, false, true, 24) == SigCompat.OK);
        expect("Android 11 (30) 也能装，不能拦",
                SigCompat.installBlocker(30, false, true, 30) == SigCompat.OK);
        System.out.println();

        // ---------- ② 只有 v1 且 targetSdk ≥ 30：新系统拒装 ----------
        System.out.println("2. 只有 v1，targetSdk ≥ 30");
        expect("在本机 4.3 (18) 上**能装**，绝不能拦（拦了会挡住能装的包）",
                SigCompat.installBlocker(18, true, false, 30) == SigCompat.OK);
        expect("在 Android 10 (29) 上还能装",
                SigCompat.installBlocker(29, true, false, 30) == SigCompat.OK);
        expect("在 Android 11 (30) 上必须拦住（NO_SIGNATURE）",
                SigCompat.installBlocker(30, true, false, 30)
                        == SigCompat.BLOCK_MISSING_V2_FOR_NEW_TARGET);
        expect("在 Android 13 (33) 上同样拦住",
                SigCompat.installBlocker(33, true, false, 33)
                        == SigCompat.BLOCK_MISSING_V2_FOR_NEW_TARGET);
        System.out.println();

        // ---------- ③ targetSdk 29 只有 v1：到处都能装 ----------
        //
        // 这是最常见的那种老包：targetSdk 停在 29，只做了 v1 签名。
        // 它不受 ② 的约束（API 30 的门槛针对的是 targetSdk ≥ 30），
        // 所以在任何系统上都应当判「可以装」。
        System.out.println("3. 只有 v1，targetSdk 29（旧包常态，到处都能装）");
        for (int sdk : new int[]{18, 23, 24, 29, 30, 33}) {
            expect("Android API " + sdk + " 上都能装",
                    SigCompat.installBlocker(sdk, true, false, 29) == SigCompat.OK);
        }
        expect("targetSdk 29 不该被要求 v2+", !SigCompat.requiresV2OrHigher(29));
        expect("targetSdk 30 应当被要求 v2+", SigCompat.requiresV2OrHigher(30));
        System.out.println();

        // ---------- ④ v1 + v2/v3：哪儿都能装 ----------
        System.out.println("4. v1 + v2/v3（正常发布包）");
        for (int sdk : new int[]{18, 24, 30, 33}) {
            for (int t : new int[]{18, 29, 30, 34}) {
                expect("API " + sdk + " / targetSdk " + t + " 能装",
                        SigCompat.installBlocker(sdk, true, true, t) == SigCompat.OK);
            }
        }
        System.out.println();

        // ---------- ⑤ 有效 targetSdk 取较大值 ----------
        //
        // 覆盖安装时只要有一边达到 30，系统就按新规则校验，
        // 所以必须取大值，否则会漏报。
        System.out.println("5. 有效 targetSdk 取较大值");
        expect("本次 29 / 已装 30 → 30", SigCompat.effectiveTargetSdk(29, 30) == 30);
        expect("本次 30 / 已装 29 → 30", SigCompat.effectiveTargetSdk(30, 29) == 30);
        expect("本次 29 / 已装 29 → 29", SigCompat.effectiveTargetSdk(29, 29) == 29);
        expect("两边都取不到(-1) → -1", SigCompat.effectiveTargetSdk(-1, -1) == -1);
        expect("本次取不到 / 已装 30 → 30（不能漏报）",
                SigCompat.effectiveTargetSdk(-1, 30) == 30);
        System.out.println();

        // ---------- ⑥ 取不到信息时不要乱拦 ----------
        //
        // targetSdk 拿不到时传 -1。这时不能据此判定「必须 v2+」，
        // 否则会把一堆正常包挡在门外。
        System.out.println("6. targetSdk 未知（-1）时保守处理");
        expect("未知 targetSdk 不触发 v2+ 要求",
                !SigCompat.requiresV2OrHigher(-1));
        expect("只有 v1 + 未知 targetSdk → 不拦（宁可放行也不误拦）",
                SigCompat.installBlocker(30, true, false, -1) == SigCompat.OK);
        System.out.println();

        // ---------- ⑦ 版本名映射 ----------
        //
        // 注意这里断言的是「带 Android 前缀」的形式。
        // 早先 SigCompat 自己维护过一张只返回数字（"11"）的小表，
        // 后来统一到 ApiNames 了 —— 那张表长在 ApkParser 里，
        // 于是纯逻辑类想用一下版本名就得依赖 Android API。
        System.out.println("7. API 级别 → Android 版本名");
        expect("30 → Android 11", "Android 11".equals(SigCompat.androidName(30)));
        expect("33 → Android 13", "Android 13".equals(SigCompat.androidName(33)));
        expect("18 → Android 4.3", "Android 4.3".equals(SigCompat.androidName(18)));
        expect("描述里带 API 号",
                "Android 6.0 (API 23)".equals(SigCompat.describeApi(23)));
        expect("取不到时说明「未声明」",
                "未声明".equals(SigCompat.describeApi(-1)));
        // 比表里更新的版本给一句人话，而不是干巴巴的裸数字
        expect("未知的高版本回落成人话",
                "Android 11 之后".equals(SigCompat.androidName(99)));
        expect("无效的低值回落成 API 号", "API 0".equals(SigCompat.androidName(0)));
        System.out.println();

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
