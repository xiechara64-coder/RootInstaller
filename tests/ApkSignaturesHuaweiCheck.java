// ===========================================================================
//  针对「折行清单」的回归用例（**不属于 app 源码**）
//
//  背景（真实踩坑）：JAR 清单每行不得超过 72 字节，超长的 Name 值会折到下一行，
//  续行以一个空格开头。最初的实现只取第一行，于是名字被截断，
//  再拿去和包里真实条目名比对必然对不上 ——
//  结果把一个**完好的华为系统应用判成了「签名之后被改过」**。
//
//  这个用例用一份真实固件里的 APK 把这条钉住：
//  它必须判「完整」，不能报「被改动过」。
//
//  跑法：由 run-tests.ps1 调用；也可手工：
//    java -cp build/tests ApkSignaturesHuaweiCheck <apk 路径> [debug]
// ===========================================================================

import com.dsh.rootinstaller.ApkSignatures;

import java.io.File;

public class ApkSignaturesHuaweiCheck {

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("=== 折行清单回归（真实系统应用）===\n");

        if (args.length < 1) {
            System.out.println("用法: ApkSignaturesHuaweiCheck <apk 路径>");
            System.exit(2);
        }
        File apk = new File(args[0]);
        if (!apk.isFile()) {
            // 这份 APK 是从设备上取下来的，不在仓库里。
            // 取不到就跳过，不算失败 —— 免得在别的机器上跑不通。
            System.out.println("找不到样本 " + apk.getAbsolutePath() + "，跳过这个用例。");
            System.out.println("（它是从真机 /storage/sdcard1/apk/ 取出来的系统应用，"
                    + "用于验证折行清单不会造成误报）");
            System.exit(0);
        }

        ApkSignatures.Result r = ApkSignatures.inspect(apk.getAbsolutePath());
        System.out.println("  文件   : " + apk.getName() + "  (" + apk.length() + " 字节)");
        System.out.println("  方案   : " + r.schemeText());
        System.out.println("  证书   : " + r.certSha1);
        System.out.println("  完整性 : " + integrityText(r.integrity)
                + (r.integrityDetail != null ? "  （" + r.integrityDetail + "）" : ""));
        if (args.length > 1 && "debug".equalsIgnoreCase(args[1]) && r.debug != null) {
            System.out.println("  调试   : " + r.debug);
        }

        expect("检出 v1 签名", r.v1);
        expect("取到了证书", r.certSha1 != null);
        // 这条是本用例存在的全部意义
        expect("完整性必须判「通过」，不能误报成被改动过",
                r.integrity == ApkSignatures.INTEGRITY_OK);

        // 折行还原本身也要正确：拼回来的名字必须和包里真实条目同名
        String manifest = "Name: res/drawable-sw320dp-hdpi/dropdown_ic_arrow_disabled_holo_dark.p\n"
                + " ng\n"
                + "SHA1-Digest: abc=\n"
                + "\n"
                + "Name: AndroidManifest.xml\n"
                + "SHA1-Digest: def=\n";
        java.util.List<String> lines = ApkSignatures.unfoldManifest(manifest);
        expect("折行被正确拼接（去掉续行前导空格）",
                lines.contains("Name: res/drawable-sw320dp-hdpi/"
                        + "dropdown_ic_arrow_disabled_holo_dark.png"));
        // 折行不该把后续内容吃掉或错位 —— 第二条仍然要完整出现。
        // 这里刻意不断言总行数：末尾换行会多出一个空串，断言行数只会让用例变脆，
        // 而真正要保证的是「拼对了名字、后续内容没被破坏」。
        expect("折行没有破坏后续条目", lines.contains("SHA1-Digest: abc=")
                && lines.contains("Name: AndroidManifest.xml")
                && lines.contains("SHA1-Digest: def="));

        System.out.println();
        if (failures == 0) {
            System.out.println(">>> 全部通过");
        } else {
            System.out.println(">>> 有 " + failures + " 项未通过");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String integrityText(int v) {
        if (v == ApkSignatures.INTEGRITY_OK) {
            return "通过";
        }
        if (v == ApkSignatures.INTEGRITY_BAD) {
            return "不通过";
        }
        return "未能判断";
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
