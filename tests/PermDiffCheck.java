// ===========================================================================
//  权限差异对比的离线验证（**不属于 app 源码**，不会被编进 APK）
//
//  重点验证「不误报」：把老权限说成「新版本多要的」，会让用户对完全正常的
//  更新产生不必要的警惕；几次之后他就不再相信这个提示了。
//  而 PermDiff 不依赖 Android API，可以脱离设备穷举验证。
//
//  跑法（在项目根目录）：
//     javac -encoding UTF-8 -d build/tests \
//         app/src/main/java/com/dsh/rootinstaller/PermDiff.java \
//         tests/PermDiffCheck.java
//     java -cp build/tests PermDiffCheck
//
//  期望输出以 ">>> 全部通过" 结尾，退出码 0。
// ===========================================================================

import com.dsh.rootinstaller.PermDiff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PermDiffCheck {

    private static int failures = 0;

    private static final String READ_SMS = "android.permission.READ_SMS";
    private static final String INTERNET = "android.permission.INTERNET";
    private static final String CAMERA = "android.permission.CAMERA";
    private static final String LOCATION = "android.permission.ACCESS_FINE_LOCATION";
    private static final String VIBRATE = "android.permission.VIBRATE";

    public static void main(String[] args) {
        System.out.println("=== 权限差异对比验证 ===\n");

        // 1. 拿不到已装版本权限 -> 必须标记为「未知」，且不能报任何新增
        PermDiff.Result r = PermDiff.compare(list(READ_SMS, INTERNET), null);
        show("1. 已装版本权限信息不可用", r);
        expect("应标记为未知", !r.known);
        expect("不能报新增（否则全部权限都会被误报）", r.added.isEmpty());
        expect("不能报移除", r.removed.isEmpty());
        expect("hasChanges 应为 false", !r.hasChanges());

        // 2. 完全一致
        r = PermDiff.compare(list(READ_SMS, INTERNET),
                new String[] { READ_SMS, INTERNET });
        show("2. 权限完全一致", r);
        expect("应标记为已知", r.known);
        expect("无新增", r.added.isEmpty());
        expect("无移除", r.removed.isEmpty());
        expect("hasChanges=false", !r.hasChanges());

        // 3. 顺序不同但集合相同 -> 不能报差异
        r = PermDiff.compare(list(INTERNET, READ_SMS),
                new String[] { READ_SMS, INTERNET });
        show("3. 仅顺序不同（集合相同）", r);
        expect("顺序不同不算变化", !r.hasChanges());

        // 4. 真有新增（权限扩张）
        r = PermDiff.compare(list(READ_SMS, INTERNET, CAMERA),
                new String[] { READ_SMS, INTERNET });
        show("4. 新增 CAMERA（权限扩张）", r);
        expect("应报告 1 项新增", r.added.size() == 1);
        expect("新增项应是 CAMERA", r.added.contains(CAMERA));
        expect("不应报移除", r.removed.isEmpty());

        // 5. 真有移除
        r = PermDiff.compare(list(READ_SMS),
                new String[] { READ_SMS, INTERNET });
        show("5. 移除了 INTERNET", r);
        expect("应报告 1 项移除", r.removed.size() == 1);
        expect("移除项应是 INTERNET", r.removed.contains(INTERNET));
        expect("不应报新增", r.added.isEmpty());

        // 6. 同时有增有减
        r = PermDiff.compare(list(READ_SMS, CAMERA, LOCATION),
                new String[] { READ_SMS, INTERNET, VIBRATE });
        show("6. 同时有增有减", r);
        expect("新增 2 项", r.added.size() == 2);
        expect("移除 2 项", r.removed.size() == 2);
        expect("新增含 CAMERA 与 LOCATION",
                r.added.contains(CAMERA) && r.added.contains(LOCATION));
        expect("移除含 INTERNET 与 VIBRATE",
                r.removed.contains(INTERNET) && r.removed.contains(VIBRATE));

        // 7. 从「无权限」变成「有权限」—— 全是新增
        r = PermDiff.compare(list(READ_SMS, INTERNET), new String[0]);
        show("7. 已装版本零权限 -> 本次有权限", r);
        expect("零权限不等于未知", r.known);
        expect("应报告 2 项新增", r.added.size() == 2);

        // 8. 从「有权限」变成「无权限」—— 全是移除
        r = PermDiff.compare(list(), new String[] { READ_SMS, INTERNET });
        show("8. 已装版本有权限 -> 本次零权限", r);
        expect("应报告 2 项移除", r.removed.size() == 2);

        // 9. 边界：本次列表为 null
        r = PermDiff.compare(null, new String[] { READ_SMS });
        show("9. 本次权限列表为 null", r);
        expect("不应崩溃，且报 1 项移除", r.known && r.removed.size() == 1);

        // 10. 边界：已装版本数组里含 null 元素
        r = PermDiff.compare(list(READ_SMS), new String[] { READ_SMS, null });
        show("10. 已装版本数组含 null 元素", r);
        expect("null 元素应被忽略，无差异", !r.hasChanges());

        // 11. 边界：本次列表有重复项 -> 不应重复报新增
        r = PermDiff.compare(list(CAMERA, CAMERA, CAMERA),
                new String[] { READ_SMS });
        show("11. 本次列表有重复项", r);
        expect("重复项只报一次", r.added.size() == 1);

        System.out.println();
        if (failures == 0) {
            System.out.println(">>> 全部通过");
        } else {
            System.out.println(">>> 有 " + failures + " 项失败");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static List<String> list(String... items) {
        return new ArrayList<String>(Arrays.asList(items));
    }

    private static void show(String title, PermDiff.Result r) {
        System.out.println("[" + title + "]");
        System.out.println("  已知=" + r.known
                + "  新增=" + r.added.size() + "  移除=" + r.removed.size());
        if (!r.added.isEmpty()) {
            System.out.println("  + " + shortNames(r.added));
        }
        if (!r.removed.isEmpty()) {
            System.out.println("  - " + shortNames(r.removed));
        }
        System.out.println();
    }

    private static String shortNames(List<String> perms) {
        StringBuilder sb = new StringBuilder();
        for (String p : perms) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            int dot = p.lastIndexOf('.');
            sb.append(dot >= 0 ? p.substring(dot + 1) : p);
        }
        return sb.toString();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  [通过] " : "  [失败] ") + what);
        if (!ok) {
            failures++;
        }
    }
}
