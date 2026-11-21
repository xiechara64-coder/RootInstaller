// ===========================================================================
//  APK 签名状态判断的离线验证（**不属于 app 源码**，不会被编进 APK）
//
//  为什么要离线跑：签名判断的坑全在格式细节上 —— APK Signing Block 的偏移、
//  证书在 signed-data 里的位置、MANIFEST.MF 的摘要链 —— 这些在真机上根本没法
//  调试（4.3 的 logcat 不方便，且崩溃点看不到中间值）。
//
//  配套的样本由 make-signed-samples.ps1 用 apksigner 生成，覆盖：
//    未签名 / 只 v1 / 只 v2 / 只 v2+v3 / v1+v2+v3 / v1 签名后被改包
//
//  跑法：直接执行 tests\run-tests.ps1
//
//  期望输出以 ">>> 全部通过" 结尾，退出码 0。
// ===========================================================================

import com.dsh.rootinstaller.ApkSignatures;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ApkSignaturesCheck {

    private static int failures = 0;

    /** 是否打印内部定位信息（命令行第二个参数给 debug 时打开）。 */
    private static boolean debugOn = false;

    /**
     * 参考证书指纹。
     *
     * <p><b>刻意不写成常量。</b> 样本每次生成都会新建密钥库，指纹每次都变；
     * 写死一个值只会让人在样本重建后困惑「明明没错为什么不过」。
     * 这个值由 make-signed-samples.ps1 从 **apksigner verify --print-certs**
     * 的输出里取出来写进 samples/expected.json，这里读回来用。
     *
     * <p>所以它的来源是独立的：apksigner 从 APK 里读证书，ApkSignatures 也读，
     * 两边必须得出一致的指纹，才能说明「证书位置的解析」是对的。
     */
    private static String expectedCertSha1;

    public static void main(String[] args) {
        System.out.println("=== APK 签名状态判断验证 ===\n");

        String dir = args.length > 0 ? args[0] : "samples";
        // 第二个参数带 debug 时打印内部定位信息，平时不打，保持输出干净
        boolean debug = args.length > 1 && "debug".equalsIgnoreCase(args[1]);
        debugOn = debug;
        File d = new File(dir);
        if (!d.isDirectory()) {
            System.out.println("找不到样本目录：" + d.getAbsolutePath());
            System.out.println("请先运行：powershell -File tests\\make-signed-samples.ps1");
            System.exit(2);
        }

        // 参考指纹从 expected.json 里读（由生成脚本用 apksigner 写出来）
        expectedCertSha1 = readExpectedCertSha1(new File(d, "expected.json"));
        if (expectedCertSha1 == null) {
            System.out.println("读不到 samples/expected.json 里的 CertSha1 —— 请重新生成样本");
            System.exit(2);
        }
        System.out.println("参考证书指纹（apksigner 对 signed-v1v2v3.apk 报出）：" + expectedCertSha1 + "\n");

        // ---------- 1. 完全不签名 ----------
        System.out.println("1. 未签名的包应该被判为「没有任何签名」");
        for (String name : new String[]{"unsigned.apk", "base-unsigned.apk"}) {
            ApkSignatures.Result r = inspect(d, name);
            show(name, r);
            expect(name + " 没有任何签名方案", !r.anyScheme());
            expect(name + " 取不到证书", r.certSha1 == null);
            System.out.println();
        }

        // ---------- 2. 只签 v1 ----------
        System.out.println("2. 只签 v1 的包");
        {
            ApkSignatures.Result r = inspect(d, "signed-v1.apk");
            show("signed-v1.apk", r);
            expect("检出 v1", r.v1);
            expect("没有 v2", !r.v2);
            expect("没有 v3", !r.v3);
            expect("证书指纹与 apksigner 报告的一致", expectedCertSha1.equalsIgnoreCase(r.certSha1));
            expect("摘要链核对通过", r.integrity == ApkSignatures.INTEGRITY_OK);
            System.out.println();
        }

        // ---------- 3. 只签 v2（本用例的重点）----------
        //
        // 这是本次修复要解决的核心缺陷：
        // 旧实现只翻 META-INF/*.RSA|DSA|EC，遇到只有 v2 的包会返回 null，
        // 界面于是写「可能是未签名的包」—— 把一个完全正常的包冤枉成未签名。
        System.out.println("3. 只签 v2 的包（旧实现会误报成「未签名」）");
        {
            ApkSignatures.Result r = inspect(d, "signed-v2.apk");
            show("signed-v2.apk", r);
            expect("检出 v2", r.v2);
            expect("没有 v1", !r.v1);
            expect("没有 v3", !r.v3);
            expect("确实有签名（不能被判成未签名）", r.anyScheme());
            expect("从 Signing Block 里读出了证书", r.certSha1 != null);
            expect("证书指纹与 apksigner 报告的一致", expectedCertSha1.equalsIgnoreCase(r.certSha1));
            System.out.println();
        }

        // ---------- 4. 只签 v2+v3 ----------
        System.out.println("4. 只签 v2+v3 的包");
        {
            ApkSignatures.Result r = inspect(d, "signed-v2v3.apk");
            show("signed-v2v3.apk", r);
            expect("检出 v2", r.v2);
            expect("检出 v3", r.v3);
            expect("没有 v1", !r.v1);
            expect("证书指纹与 apksigner 报告的一致", expectedCertSha1.equalsIgnoreCase(r.certSha1));
            System.out.println();
        }

        // ---------- 5. v1+v2+v3 ----------
        System.out.println("5. v1+v2+v3（现代发布包的典型形态）");
        {
            ApkSignatures.Result r = inspect(d, "signed-v1v2v3.apk");
            show("signed-v1v2v3.apk", r);
            expect("检出 v1", r.v1);
            expect("检出 v2", r.v2);
            expect("检出 v3", r.v3);
            expect("三种方案都读到了证书", r.certSha1 != null);
            expect("证书指纹与 apksigner 报告的一致", expectedCertSha1.equalsIgnoreCase(r.certSha1));
            expect("摘要链核对通过", r.integrity == ApkSignatures.INTEGRITY_OK);
            System.out.println();
        }

        // ---------- 6. 改了包但没重新签名 ----------
        System.out.println("6. v1 签名之后被改过的包（应判为签名不通过）");
        {
            ApkSignatures.Result r = inspect(d, "tampered-v1.apk");
            show("tampered-v1.apk", r);
            expect("仍然检出 v1（签名文件还在）", r.v1);
            expect("证书还是原来那把", expectedCertSha1.equalsIgnoreCase(r.certSha1));
            expect("摘要链核对**不**通过", r.integrity == ApkSignatures.INTEGRITY_BAD);
            if (r.integrityDetail != null) {
                System.out.println("     原因：" + r.integrityDetail);
            }
            System.out.println();
        }

        // ---------- 7. 同一把密钥签出来的包，证书指纹应当完全一致 ----------
        //
        // 这条直接对应产品需求：判断「本次 APK 和已装版本是不是同一签名」，
        // 靠的就是这个值可比。v1 取证书文件和 v2/v3 取 Signing Block，
        // 两条路径必须落到同一个指纹上。
        System.out.println("7. 同一密钥、不同签名组合，证书指纹必须一致");
        {
            List<String> sameKey = new ArrayList<String>();
            sameKey.add("signed-v1.apk");
            sameKey.add("signed-v2.apk");
            sameKey.add("signed-v2v3.apk");
            sameKey.add("signed-v1v2v3.apk");
            String first = null;
            boolean allSame = true;
            for (String name : sameKey) {
                ApkSignatures.Result r = inspect(d, name);
                String got = r.certSha1;
                System.out.println("     " + pad(name, 20) + " -> " + got);
                if (first == null) {
                    first = got;
                } else if (got == null || !got.equalsIgnoreCase(first)) {
                    allSame = false;
                }
            }
            expect("四份样本的证书指纹完全相同", allSame);
            expect("且等于 apksigner 报告的值", expectedCertSha1.equalsIgnoreCase(first));
            System.out.println();
        }

        // ---------- 8. targetSdk 29 + 只有 v1（老包常态）----------
        //
        // 这是最常见的旧包形态：targetSdk 停在 29，只做了 v1 签名。
        // 它到处都能装 —— 不受「targetSdk ≥ 30 必须有 v2+」那条新规则约束。
        // 这里只验签名判定的部分（targetSdk 与规则的组合判定由 SigCompatCheck 验）。
        System.out.println("8. targetSdk 29 且只有 v1 的包（旧包常态）");
        {
            ApkSignatures.Result r = inspect(d, "signed-v1-target29.apk");
            show("signed-v1-target29.apk", r);
            expect("检出 v1", r.v1);
            expect("没有 v2", !r.v2);
            expect("没有 v3", !r.v3);
            expect("证书指纹与 apksigner 报告的一致", expectedCertSha1.equalsIgnoreCase(r.certSha1));
            expect("完整性核对通过", r.integrity == ApkSignatures.INTEGRITY_OK);
            System.out.println();
        }

        // ---------- 9. 不存在的文件不能崩 ----------
        System.out.println("9. 边界：文件不存在 / 空路径");
        {
            ApkSignatures.Result r = ApkSignatures.inspect(new File(d, "no-such-file.apk").getAbsolutePath());
            expect("文件不存在时不抛异常，并给出说明", !r.anyScheme() && r.note != null);
            System.out.println("     note = " + r.note);

            ApkSignatures.Result r2 = ApkSignatures.inspect(null);
            expect("路径为 null 时不抛异常", !r2.anyScheme());
            System.out.println("     note = " + r2.note);
            System.out.println();
        }

        System.out.println();
        if (failures == 0) {
            System.out.println(">>> 全部通过");
        } else {
            System.out.println(">>> 有 " + failures + " 项未通过");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static ApkSignatures.Result inspect(File dir, String name) {
        return ApkSignatures.inspect(new File(dir, name).getAbsolutePath());
    }

    /**
     * 从 expected.json 里取 CertSha1。
     *
     * 这里手写一小段提取，不引 JSON 库：用例要能用一条 javac 命令跑起来，
     * 多一个依赖就多一件要在别的机器上准备的东西。取值时先定位到键，
     * 再从它后面找第一个带引号的串 —— 比直接对整个文本做正则更不容易误匹配。
     */
    private static String readExpectedCertSha1(File json) {
        if (!json.isFile()) {
            return null;
        }
        try {
            String text = new String(readAllBytes(json), "UTF-8");
            String key = "\"CertSha1\"";
            int at = text.indexOf(key);
            if (at < 0) {
                return null;
            }
            int q1 = text.indexOf('"', at + key.length());
            if (q1 < 0) {
                return null;
            }
            int q2 = text.indexOf('"', q1 + 1);
            if (q2 < 0) {
                return null;
            }
            return text.substring(q1 + 1, q2).trim().toLowerCase();
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readAllBytes(File f) throws java.io.IOException {
        java.io.InputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
                // 关不掉也无所谓
            }
        }
    }

    private static void show(String name, ApkSignatures.Result r) {
        System.out.println("   " + pad(name, 20)
                + " 方案=" + pad(r.schemeText(), 12)
                + " 证书=" + (r.certSha1 == null ? "(无)" : r.certSha1)
                + " 完整性=" + integrityText(r.integrity));
        if (r.note != null) {
            System.out.println("     备注：" + r.note);
        }
        if (debugOn && r.debug != null) {
            System.out.println("     调试：" + r.debug);
        }
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

    private static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < w) {
            sb.append(' ');
        }
        return sb.toString();
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
