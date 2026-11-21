// ===========================================================================
//  「升级时系统要求变没变」判定的离线验证（**不属于 app 源码**）
//
//  这里要钉住的是**方向**。判断「谁比谁大」最容易写反，而写反的后果是
//  给出完全相反的结论：明明新版本不再支持老设备了，界面却说「支持范围变宽」。
//
//  minSdk 抬高是用户最不容易察觉、又最会吃亏的一种变化：
//  它不报错、不提示，只是从此以后另一台老设备装不上新版本了。
//
//  SdkChange 不引用任何 Android API，所以可以把组合穷举验证。
//
//  跑法：由 run-tests.ps1 调用；也可手工：
//     javac -encoding UTF-8 -d build/tests \
//         app/src/main/java/com/dsh/rootinstaller/SdkChange.java \
//         app/src/main/java/com/dsh/rootinstaller/ApiNames.java \
//         tests/SdkChangeCheck.java
//     java -cp build/tests SdkChangeCheck
//
//  期望输出以 ">>> 全部通过" 结尾，退出码 0。
// ===========================================================================

import com.dsh.rootinstaller.SdkChange;

public class SdkChangeCheck {

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("=== 升级时系统要求变化 判定验证 ===\n");

        // ---------- 1. minSdk 抬高：必须识别为「要提醒」的方向 ----------
        System.out.println("1. minSdk 抬高（老设备从此升级不了，最关键的一种）");
        {
            // 4.3 -> 6.0：老机器装不上新版本了
            SdkChange.Result r = SdkChange.compare(18, 18, 23, 18);
            System.out.println("   已装 minSdk=18 targetSdk=18  →  新包 minSdk=23 targetSdk=18");
            expect("minSdk 方向 = RAISED", r.minSdkDirection == SdkChange.RAISED);
            expect("targetSdk 未变化 = SAME", r.targetSdkDirection == SdkChange.SAME);
            expect("anyChange() 为真", r.anyChange());
            expect("minSdkRaised() 为真（界面据此标红）", r.minSdkRaised());
            System.out.println();
        }

        // ---------- 2. minSdk 降低：放宽，不该标红 ----------
        System.out.println("2. minSdk 降低（支持范围变宽）");
        {
            SdkChange.Result r = SdkChange.compare(23, 23, 18, 23);
            expect("minSdk 方向 = LOWERED", r.minSdkDirection == SdkChange.LOWERED);
            expect("minSdkRaised() 为假（不能标成警告）", !r.minSdkRaised());
            expect("anyChange() 为真", r.anyChange());
            System.out.println();
        }

        // ---------- 3. targetSdk 抬高：中性信息 ----------
        System.out.println("3. targetSdk 抬高（适配了更新的 Android）");
        {
            SdkChange.Result r = SdkChange.compare(18, 18, 18, 29);
            expect("targetSdk 方向 = RAISED", r.targetSdkDirection == SdkChange.RAISED);
            expect("minSdk 未变化 = SAME", r.minSdkDirection == SdkChange.SAME);
            expect("anyChange() 为真", r.anyChange());
            expect("但 minSdkRaised() 为假（这不是警告）", !r.minSdkRaised());
            System.out.println();
        }

        // ---------- 4. targetSdk 降低：少见，要能识别 ----------
        System.out.println("4. targetSdk 降低（正常更新里少见）");
        {
            SdkChange.Result r = SdkChange.compare(18, 29, 18, 18);
            expect("targetSdk 方向 = LOWERED", r.targetSdkDirection == SdkChange.LOWERED);
            expect("anyChange() 为真", r.anyChange());
            System.out.println();
        }

        // ---------- 5. 都没变：不该渲染任何东西 ----------
        System.out.println("5. 完全没变化（不该占界面空间）");
        {
            SdkChange.Result r = SdkChange.compare(18, 18, 18, 18);
            expect("minSdk = SAME", r.minSdkDirection == SdkChange.SAME);
            expect("targetSdk = SAME", r.targetSdkDirection == SdkChange.SAME);
            expect("anyChange() 为假 → 界面整段不显示", !r.anyChange());
            System.out.println();
        }

        // ---------- 6. 两边都变：两个方向都要对 ----------
        System.out.println("6. minSdk 与 targetSdk 同时变化");
        {
            SdkChange.Result r = SdkChange.compare(15, 19, 23, 29);
            expect("minSdk = RAISED", r.minSdkDirection == SdkChange.RAISED);
            expect("targetSdk = RAISED", r.targetSdkDirection == SdkChange.RAISED);
            expect("两者互不干扰，各自独立判定", r.minSdkRaised() && r.anyChange());
            System.out.println();
        }

        // ---------- 7. 取不到值时不能猜 ----------
        //
        // 这是最容易出错的地方：拿不到已装版本的 minSdk 时，
        // 若把 -1 当成「0」去比较，就会得出「提高了」的错误结论。
        System.out.println("7. 任一边取不到值 → 判 UNKNOWN，不能瞎猜");
        {
            SdkChange.Result r = SdkChange.compare(-1, -1, 23, 29);
            expect("旧值取不到 → minSdk = UNKNOWN",
                    r.minSdkDirection == SdkChange.UNKNOWN);
            expect("旧值取不到 → targetSdk = UNKNOWN",
                    r.targetSdkDirection == SdkChange.UNKNOWN);
            expect("anyChange() 为假（宁可不显示，也不给错结论）", !r.anyChange());
            expect("minSdkRaised() 为假", !r.minSdkRaised());

            SdkChange.Result r2 = SdkChange.compare(18, 18, -1, -1);
            expect("新值取不到 → minSdk = UNKNOWN",
                    r2.minSdkDirection == SdkChange.UNKNOWN);
            expect("新值取不到时也不误报", !r2.anyChange());

            SdkChange.Result r3 = SdkChange.compare(0, 0, 23, 29);
            expect("0 也算取不到（不是「提高了」）",
                    r3.minSdkDirection == SdkChange.UNKNOWN && !r3.anyChange());
            System.out.println();
        }

        // ---------- 8. null 结果不能崩 ----------
        System.out.println("8. 边界：compare 的结果字段本身可读");
        {
            SdkChange.Result r = SdkChange.compare(18, 29, 18, 29);
            expect("旧值被记录", r.oldMinSdk == 18 && r.oldTargetSdk == 29);
            expect("新值被记录", r.newMinSdk == 18 && r.newTargetSdk == 29);
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
