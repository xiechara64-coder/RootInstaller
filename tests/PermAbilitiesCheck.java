// ===========================================================================
//  权限风险汇总的离线验证（**不属于 app 源码**，不会被编进 APK）
//
//  为什么需要它：这套逻辑的产出是「一句话人话结论」，对错没法靠界面观察 ——
//  得拿一组组典型的权限组合去跑，看结论是否符合预期、有没有危言耸听或漏报。
//  而 PermAbilities / Perms 都不依赖 Android API，可以脱离设备直接验证。
//
//  跑法（在项目根目录）：
//     javac -encoding UTF-8 -d build/tests \
//         -classpath <SDK>/platforms/android-19/android.jar \
//         app/src/main/java/com/dsh/rootinstaller/PermAbilities.java \
//         app/src/main/java/com/dsh/rootinstaller/Perms.java \
//         app/src/main/java/com/dsh/rootinstaller/ApkCard.java \
//         tests/PermAbilitiesCheck.java
//     java -cp build/tests PermAbilitiesCheck
//
//  期望输出以 ">>> 全部通过" 结尾，退出码 0。
// ===========================================================================

import com.dsh.rootinstaller.ApkCard;
import com.dsh.rootinstaller.PermAbilities;
import com.dsh.rootinstaller.Perms;

import java.util.HashMap;
import java.util.Map;

public class PermAbilitiesCheck {

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("=== 权限风险汇总验证 ===\n");

        // ---------- 1. 干净应用：没有权限 ----------
        ApkCard clean = card();
        show("1. 零权限", clean, null);
        expect("无权限时不报风险", clean.riskLevel == 0);
        expect("结论提到没有权限", clean.riskHeadline.contains("没有申请任何权限"));

        // ---------- 2. 典型联网应用：只有 INTERNET ----------
        ApkCard netOnly = card("android.permission.INTERNET");
        show("2. 只有 INTERNET", netOnly, null);
        expect("INTERNET 不应被判为风险", netOnly.riskLevel == 0);
        expect("结论不应恐吓用户", !netOnly.riskHeadline.contains("传出去"));

        // ---------- 3. 只有 READ_SMS（不能联网）----------
        ApkCard smsLocal = card("android.permission.READ_SMS");
        show("3. 只有 READ_SMS（无网络）", smsLocal, null);
        // 能读短信但传不出去：等级降到中风险（因为「传得出去」才最致命），
        // 但徽标必须特别标注，否则只看「中风险」会低估「能读你短信」这件事。
        expect("应标记为'有killer能力但离线'", smsLocal.criticalButOffline);
        expect("徽标应说明无法外传", smsLocal.riskBadge != null
                && smsLocal.riskBadge.contains("无法外传"));
        expect("无网络时不应说外传", !smsLocal.riskHeadline.contains("传出去"));

        // ---------- 4. READ_SMS + INTERNET（关键组合）----------
        ApkCard smsNet = card("android.permission.READ_SMS", "android.permission.INTERNET");
        show("4. READ_SMS + INTERNET", smsNet, null);
        expect("组合后应高风险", smsNet.riskLevel == 3);
        expect("有网络时应提示可能外传", smsNet.riskHeadline.contains("传出去"));

        // ---------- 5. 完整的间谍软件特征 ----------
        ApkCard spy = card("android.permission.READ_SMS",
                "android.permission.RECEIVE_SMS",
                "android.permission.READ_CONTACTS",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.RECORD_AUDIO",
                "android.permission.CAMERA",
                "android.permission.READ_CALL_LOG",
                "android.permission.READ_PHONE_STATE",
                "android.permission.INTERNET");
        show("5. 间谍软件特征", spy, null);
        expect("应判高风险", spy.riskLevel == 3);
        expect("结论应提到短信", spy.riskHeadline.contains("短信"));
        expect("结论应提示可能外传", spy.riskHeadline.contains("传出去"));
        expect("结论不应过长(<=120字)", spy.riskHeadline.length() <= 120);

        // ---------- 6. CALL_PHONE 的额外必要条件 ----------
        ApkCard callAlone = card("android.permission.CALL_PHONE");
        show("6. 只有 CALL_PHONE", callAlone, null);
        boolean aloneHasCall = callAlone.riskHeadline.contains("拨打电话");

        ApkCard callWithState = card("android.permission.CALL_PHONE",
                "android.permission.READ_PHONE_STATE");
        show("7. CALL_PHONE + READ_PHONE_STATE", callWithState, null);
        boolean bothHasCall = callWithState.riskHeadline.contains("拨打电话");
        expect("缺必要条件时不应报'拨打电话'", !aloneHasCall);
        expect("条件齐全时才报'拨打电话'", bothHasCall);

        // ---------- 8. 危害不大的组合：不应被夸大 ----------
        ApkCard mild = card("android.permission.VIBRATE",
                "android.permission.WAKE_LOCK",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.INTERNET");
        show("8. 轻度权限组合", mild, null);
        expect("轻度组合不应判高风险", mild.riskLevel <= 2);
        expect("不应出现外传提示以外的恐吓", !mild.riskHeadline.contains("隐私"));

        // ---------- 9. 危险权限多但说不出具体行为 ----------
        ApkCard manyNoAbility = card("android.permission.SET_WALLPAPER",
                "android.permission.DISABLE_KEYGUARD",
                "android.permission.KILL_BACKGROUND_PROCESSES",
                "android.permission.MODIFY_AUDIO_SETTINGS",
                "android.permission.EXPAND_STATUS_BAR",
                "android.permission.INTERNET");
        show("9. 危险权限多但无具体能力", manyNoAbility, null);
        expect("仍应给出结论", manyNoAbility.riskHeadline != null
                && manyNoAbility.riskHeadline.length() > 0);
        expect("不应编造具体行为", !manyNoAbility.riskHeadline.contains("上传"));

        // ---------- 10. 边界：结论不能为空 ----------
        ApkCard one = card("android.permission.CAMERA");
        show("10. 单个 CAMERA", one, null);
        expect("结论非空", one.riskHeadline != null && one.riskHeadline.length() > 0);

        // ---------- 11. 典型正常应用：不该被判高风险 ----------
        // 这是最关键的一条：绝大多数应用都长这样。
        // 如果它们全被标成高风险，徽标就失去意义了（第一版就踩了这个坑）。
        ApkCard normal = card("android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.ACCESS_WIFI_STATE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.WAKE_LOCK",
                "android.permission.VIBRATE",
                "android.permission.READ_PHONE_STATE");
        show("11. 典型正常应用", normal, null);
        expect("典型应用不应判高风险", normal.riskLevel < 3);

        // ---------- 12. 相机类应用：有摄像头但不应是高风险 ----------
        ApkCard cameraApp = card("android.permission.CAMERA",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.INTERNET",
                "android.permission.ACCESS_FINE_LOCATION");
        show("12. 相机类应用", cameraApp, null);
        expect("相机应用不应判高风险", cameraApp.riskLevel < 3);

        // ---------- 13. 真高风险：读短信且能联网 ----------
        ApkCard bad = card("android.permission.READ_SMS",
                "android.permission.INTERNET");
        show("13. 读短信 + 联网", bad, null);
        expect("读短信且能联网应判高风险", bad.riskLevel == 3);

        // ---------- 14. 低风险档：有敏感权限但无杀手级能力 ----------
        // 这一档要显式标绿色，否则「绿色」永远不会出现在界面上。
        //
        // 选权限这里踩过两次坑，记下来免得再选错：
        //   · 最早用 VIBRATE，理由是「它算敏感权限」——那个理由本身就错，
        //     振动器没有隐私含义，它后来被降级了。
        //   · 改用 READ_CALENDAR 又不对：**它本来就有对应的能力**
        //     （「读取你的日历日程」），于是落进中风险，不是这一档。
        // 所以这一档要用的权限必须满足：**是危险权限，但没有任何已登记的能力用到它**。
        // WRITE_CALENDAR（写日历）正好符合 —— 有隐私含义、能引起注意，
        // 但没登记成能力，于是只能判「低风险」。
        ApkCard lowRisk = card("android.permission.INTERNET",
                "android.permission.WRITE_CALENDAR");
        show("14. 有敏感权限但无杀手级能力", lowRisk, null);
        expect("应为低风险(1)", lowRisk.riskLevel == 1);
        expect("徽标应为绿色档'低风险权限'", "低风险权限".equals(lowRisk.riskBadge));

        // ---------- 15. 确实干净的包：不该有徽标 ----------
        // 只有 INTERNET（不算危险权限），也不命中任何能力。
        ApkCard noDanger = card("android.permission.INTERNET");
        show("15. 无敏感权限", noDanger, null);
        expect("应为无风险(0)", noDanger.riskLevel == 0);
        expect("不应显示徽标", noDanger.riskBadge == null
                || noDanger.riskBadge.length() == 0);

        // ---------- 16. 回归：用户实际遇到的那个包（The sea chef）----------
        //
        // 它的真实权限清单只有三项：
        //     INTERNET + com.android.vending.BILLING + ACCESS_NETWORK_STATE
        // 用户看到的现象是：总评「低风险」，但明细里 ACCESS_NETWORK_STATE
        // 标着「危险（涉及隐私/安全）」，而「简单说」那句又写着
        // 「…没有命中已知的隐私读取行为」—— 同一页自相矛盾。
        //
        // 修好之后这三项没有一项该算隐私风险：INTERNET 只是能联网，
        // BILLING 是应用内购买（自定义权限，内置表里没有），
        // ACCESS_NETWORK_STATE 只是读连接状态。
        // 所以正确结果是**完全不显示风险结论**，而不是硬挤出一句自相矛盾的话。
        ApkCard seachef = card("android.permission.INTERNET",
                "com.android.vending.BILLING",
                "android.permission.ACCESS_NETWORK_STATE");
        show("16. 回归：The sea chef 的权限组合", seachef, null);
        expect("不应判为任何风险等级(0)", seachef.riskLevel == 0);
        expect("不应显示风险徽标", seachef.riskBadge == null
                || seachef.riskBadge.length() == 0);
        expect("敏感权限数应为 0", seachef.dangerousPermissionCount == 0);

        // ---------- 17. 有敏感权限但说不出行为时，措辞不能自相矛盾 ----------
        //
        // 这一档（有危险权限、但没命中任何已登记能力）以前会拼出
        // 「有 N 项敏感权限，但没有命中已知的隐私读取行为」——
        // 既然承认有敏感权限，又说没命中隐私读取，用户不知道该信哪半句。
        // 现在要求它①不为空 ②不出现「没有命中」这种自相矛盾的说法
        // ③明确提示去看逐条明细。
        //
        // 同样要注意选权限：必须是**没有任何能力用到**的危险权限，
        // 否则会走「有具体行为」那条分支（用 READ_CALENDAR 就上过这个当）。
        ApkCard vague = card("android.permission.INTERNET",
                "android.permission.WRITE_CALENDAR");
        show("17. 有敏感权限但无具体行为", vague, null);
        String h = vague.riskHeadline;
        expect("结论句不应为空", h != null && h.length() > 0);
        expect("不应自称『没有命中已知的隐私读取行为』（自相矛盾）",
                h == null || h.indexOf("没有命中") < 0);
        expect("应引导用户看逐条权限", h != null && h.indexOf("逐条权限") >= 0);

        System.out.println();
        if (failures == 0) {
            System.out.println(">>> 全部通过");
        } else {
            System.out.println(">>> 有 " + failures + " 项失败");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static ApkCard card(String... perms) {
        ApkCard c = new ApkCard();
        c.packageName = "com.example.test";
        if (perms != null) {
            for (String p : perms) {
                c.permissions.add(p);
            }
        }
        return c;
    }

    private static void show(String title, ApkCard c, Map<String, Perms.Info> table) {
        PermAbilities.Summary s = PermAbilities.summarize(c, table);
        System.out.println("[" + title + "]  权限 " + c.permissions.size() + " 项");
        System.out.println("  风险等级: " + s.riskLevel + " ("
                + PermAbilities.riskLabel(s.riskLevel) + ")  可联网: " + s.hasNetwork);
        System.out.println("  结论: " + s.headline);
        if (!s.abilities.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (PermAbilities.Ability a : s.abilities) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append(PermAbilities.describe(a, s.hasNetwork));
            }
            System.out.println("  能力(" + s.abilities.size() + "): " + sb);
        }
        System.out.println();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  [通过] " : "  [失败] ") + what);
        if (!ok) {
            failures++;
        }
    }
}
