// ===========================================================================
//  「危险权限」判定的离线验证（**不属于 app 源码**）
//
//  钉住的是一个真实出现过的自相矛盾：
//    总评说「低风险 / 没有命中已知的隐私读取行为」，
//    而明细里 INTERNET 却标着「[危险（涉及隐私/安全）]」。
//
//  根因：判定「危险」有两套来源，而系统的保护级别盖过了本应用的风险模型——
//    系统表里 INTERNET 是 dangerous（它的 protectionLevel 就是 DANGEROUS），
//    内置表却故意不把它算作危险。而 lookup() 优先返回系统表，
//    于是那个 dangerous=true 一路透到「详情页渲染」和「汇总计数」两处。
//
//  修法：让内置表对**它提到过的**权限拥有最终裁决权；
//  没提到的才退回系统判定（否则会把系统标为危险的冷门权限漏报成安全）。
//
//  ⚠ 本用例最初写错过一次，记在这里免得后人重踩：
//     第一版直接往「系统表」里 put 了一个 Info 再调 lookup()，期待它能覆盖 ——
//     但覆盖发生在**构造系统表**那一步（fromPermissionInfo），
//     lookup() 只是原样返回表里的东西。测试于是测了一个根本不存在的路径，
//     表现为「改了源码用例仍然全红」。
//     现在改为直接验证裁决函数本身（resolveJudgment）+ 兜底路径（lookup）。
//
//  Perms 不引用 Android API，可以脱离设备跑。跑法：由 run-tests.ps1 调用。
// ===========================================================================

import com.dsh.rootinstaller.Perms;

import java.util.HashMap;
import java.util.Map;

public class PermRiskCheck {

    private static int failures = 0;

    /** android.content.pm.PermissionInfo 里 PROTECTION_DANGEROUS 的取值（=1）。 */
    private static final int SYS_DANGEROUS = 1;
    /** PROTECTION_NORMAL = 0。 */
    private static final int SYS_NORMAL = 0;
    /** PROTECTION_SIGNATURE = 2。 */
    private static final int SYS_SIGNATURE = 2;

    /** 一条**不在**内置表里的权限，用来验证「未覆盖 → 退回系统判定」。 */
    private static final String NOT_COVERED = "android.permission.MANAGE_DOCUMENTS";

    public static void main(String[] args) {
        System.out.println("=== 权限风险判定一致性验证 ===\n");

        // ---------- 1. INTERNET：系统说危险，本应用必须判为非危险 ----------
        //
        // 这是用户实际看到的矛盾。系统把它标为 DANGEROUS（确实如此），
        // 但内置表里它是**唯一**一条用 put(...) 登记、而非 putDangerous(...) 的权限。
        System.out.println("1. INTERNET：系统判危险级，本应用必须压成非危险");
        {
            expect("内置表确实覆盖了 INTERNET",
                    Perms.isNotablePermission("android.permission.INTERNET"));
            expect("裁决为「不危险」", !isDangerous("android.permission.INTERNET", SYS_DANGEROUS));
            expect("保护级别压成普通级（明细那行红字就来自它）",
                    protectionOf("android.permission.INTERNET", SYS_DANGEROUS) == SYS_NORMAL);
            expect("三态裁决 = 明确不危险",
                    Perms.builtinRiskRank("android.permission.INTERNET")
                            == Perms.BUILTIN_NOT_DANGEROUS_RANK);
            System.out.println();
        }

        // ---------- 2. 内置表没覆盖的权限：必须沿用系统判定（防漏报）----------
        //
        // 这是修法里最容易出错的一侧：如果写成「查不到就当安全」，
        // 会把系统标为危险的冷门权限全部漏报。
        System.out.println("2. 内置表未覆盖的权限：沿用系统判定，不能漏报");
        {
            expect("MANAGE_DOCUMENTS 不在内置表里",
                    !Perms.isNotablePermission(NOT_COVERED));
            expect("系统说危险 → 必须也是危险（防漏报）",
                    isDangerous(NOT_COVERED, SYS_DANGEROUS));
            expect("系统说普通 → 判普通",
                    !isDangerous(NOT_COVERED, SYS_NORMAL));
            expect("系统说签名级 → 不是「危险」，但保护级别要原样保留",
                    !isDangerous(NOT_COVERED, SYS_SIGNATURE)
                            && protectionOf(NOT_COVERED, SYS_SIGNATURE) == SYS_SIGNATURE);
            expect("三态裁决 = 未覆盖",
                    Perms.builtinRiskRank(NOT_COVERED) == Perms.BUILTIN_UNKNOWN_RANK);
            System.out.println();
        }

        // ---------- 3. 内置表标为危险的权限，不能被削弱 ----------
        System.out.println("3. 内置表标为危险的权限（READ_SMS / BLUETOOTH）仍然危险");
        {
            for (String p : new String[]{"android.permission.READ_SMS",
                                         "android.permission.BLUETOOTH",
                                         "android.permission.SET_WALLPAPER"}) {
                expect(p + " 在内置表里且为危险",
                        Perms.isNotablePermission(p)
                                && Perms.builtinRiskRank(p) == Perms.BUILTIN_DANGEROUS_RANK);
                expect(p + " 即使系统说普通级，也判为危险（本应用可以加重）",
                        isDangerous(p, SYS_NORMAL));
            }
            System.out.println();
        }

        // ---------- 4. 兜底路径：系统表里查不到时用内置表 ----------
        //
        // lookup() 的这条分支只有在**系统表里没有**该权限时才会走到，
        // 所以这里要传一个空的系统表 —— 上一版用例就是错在传了带值的表。
        System.out.println("4. lookup 的兜底：系统表查不到 → 用内置表");
        {
            Map<String, Perms.Info> empty = new HashMap<String, Perms.Info>();
            Perms.Info i = Perms.lookup(empty, "android.permission.INTERNET");
            expect("兜底拿到了 INTERNET", i != null);
            expect("兜底路径也判为非危险（不能又变回危险）", i != null && !i.dangerous);
            expect("兜底路径给的中文说明是内置那条",
                    i != null && i.description != null && i.description.contains("访问网络"));

            Perms.Info sms = Perms.lookup(empty, "android.permission.READ_SMS");
            expect("兜底拿到 READ_SMS 且判为危险", sms != null && sms.dangerous);

            expect("标准权限前缀也能查到（shortName 处理正确）",
                    Perms.isNotablePermission("android.permission.READ_SMS"));
            expect("裸名也认（shortName 本身）",
                    Perms.isNotablePermission("READ_SMS"));
            expect("空值不崩", !Perms.isNotablePermission(null));
            expect("完全未知的权限返回 null", Perms.lookup(empty,
                    "com.example.permission.NOT_A_REAL_ONE") == null);
            System.out.println();
        }

        // ---------- 5. 排序权重跟着裁决走 ----------
        System.out.println("5. 风险排序权重与裁决一致");
        {
            Map<String, Perms.Info> empty = new HashMap<String, Perms.Info>();
            expect("READ_SMS 权重 = 危险档(1)",
                    Perms.riskRank(empty, "android.permission.READ_SMS") == 1);
            expect("INTERNET 权重 = 普通档(2)，不是危险档",
                    Perms.riskRank(empty, "android.permission.INTERNET") == 2);
            System.out.println();
        }

        // ---------- 6. 这一类权限必须整批压住，不能只修一条 ----------
        //
        // 这是**第二轮**踩的坑，务必记住：
        //   第一轮用户报的是「INTERNET 标危险但总评低风险」，我只把 INTERNET 修了。
        //   第二轮用户换了一个包（The sea chef：只有 INTERNET + BILLING +
        //   ACCESS_NETWORK_STATE），同样的矛盾立刻复发 —— 明细里
        //   ACCESS_NETWORK_STATE 标「危险」，总评却是「低风险（1 项敏感权限）」。
        //
        // 根子不在某一条权限上，而在**「系统要求运行时授权、但零隐私含义」的权限
        // 被整批当成了风险**。所以这里用一组断言把这一整类都钉住：
        // 任何一条将来被改回 putDangerous，这个用例就会红。
        System.out.println("6. 「零隐私含义」的权限整批不得算危险（第二轮那个坑）");
        {
            String[] benign = {
                    "android.permission.INTERNET",            // 只是能联网
                    "android.permission.ACCESS_NETWORK_STATE", // 只是读连接状态
                    "android.permission.CHANGE_WIMAX_STATE",   // 只是改 WiMax 开关
                    "android.permission.VIBRATE",              // 只是振动器
                    "android.permission.WAKE_LOCK",            // 只是保持唤醒
                    "android.permission.FLASHLIGHT",           // 只是闪光灯
                    "android.permission.SET_ORIENTATION",      // 只是锁屏幕方向
                    "android.permission.EXPAND_STATUS_BAR",    // 只是收合状态栏
            };
            for (String p : benign) {
                expect("不算危险：" + p,
                        Perms.builtinRiskRank(p) == Perms.BUILTIN_NOT_DANGEROUS_RANK);
                expect("即使系统判危险级也不危险：" + p,
                        !isDangerous(p, SYS_DANGEROUS));
            }
            System.out.println();
        }

        // ---------- 7. 反过来：真有隐私含义的权限一条都不能被放过 ----------
        //
        // 上一条是「减轻」，这一条是「加重」——两个方向必须同时钉住，
        // 否则把 benign 那批压下去的同时，很容易顺手把真危险的也一起放行。
        System.out.println("7. 真有隐私/控制含义的权限仍然算危险");
        {
            String[] risky = {
                    "android.permission.READ_SMS",             // 读短信
                    "android.permission.RECEIVE_SMS",          // 收短信
                    "android.permission.CALL_PHONE",           // 打电话
                    "android.permission.READ_CONTACTS",        // 通讯录
                    "android.permission.ACCESS_FINE_LOCATION", // 精确定位
                    "android.permission.RECORD_AUDIO",         // 录音
                    "android.permission.CAMERA",               // 摄像头
                    "android.permission.READ_CALENDAR",        // 日历（有隐私含义但非杀手级）
                    "android.permission.ACCESS_WIFI_STATE",    // 读 Wi-Fi 细节（能反推位置）
                    "android.permission.CHANGE_NETWORK_STATE", // 能改网络状态
                    "android.permission.BLUETOOTH",            // 能用蓝牙通信
                    "android.permission.NFC",                  // 能用 NFC 通信
                    "android.permission.READ_LOGS",            // 读系统日志
                    "android.permission.RECEIVE_BOOT_COMPLETED", // 开机自启
            };
            for (String p : risky) {
                expect("仍算危险：" + p,
                        Perms.builtinRiskRank(p) == Perms.BUILTIN_DANGEROUS_RANK);
                expect("系统说普通级也仍算危险：" + p,
                        isDangerous(p, SYS_NORMAL));
            }
            System.out.println();
        }

        if (failures == 0) {
            System.out.println(">>> 全部通过");
        } else {
            System.out.println(">>> 有 " + failures + " 项未通过");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static boolean isDangerous(String permission, int systemLevel) {
        return Perms.resolveJudgment(permission, systemLevel)[0] == 1;
    }

    private static int protectionOf(String permission, int systemLevel) {
        return Perms.resolveJudgment(permission, systemLevel)[1];
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
