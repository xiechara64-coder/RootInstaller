// ===========================================================================
//  「整包解析结果缓存」的离线验证
//
//  这一组盯的是缓存最容易出错的地方：
//    1. 编解码往返丢字段 / 中文乱码 —— 会让详情页显示错乱，真机上极难发现；
//    2. 版本或数据损坏时**必须**返回 null，让调用方重新解析，绝不能带着坏数据跑；
//    3. 键必须随「路径 / 大小 / mtime」变化，否则改了 APK 还在用旧结论；
//    4. capture → fill 必须等价，否则缓存命中后卡片字段会缺。
//
//  跑法：由 run-tests.ps1 第二组（需要 android.jar）调用。
// ===========================================================================

import com.dsh.rootinstaller.ApkCard;
import com.dsh.rootinstaller.CacheSweep;
import com.dsh.rootinstaller.IconCacheKey;
import com.dsh.rootinstaller.ParseCache;
import com.dsh.rootinstaller.ParseCacheCodec;
import com.dsh.rootinstaller.ParseCacheData;

import java.io.File;
import java.io.FileOutputStream;

public class ParseCacheCheck {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== 解析结果缓存 ===\n");

        // ---------- 1. 编解码往返 ----------
        System.out.println("1. 所有字段编码后能原样读回");
        {
            ParseCacheData src = sample();
            byte[] raw = ParseCacheCodec.encode(src);
            expect("编码成功", raw != null && raw.length > 0);
            ParseCacheData back = ParseCacheCodec.decode(raw);
            expect("解码成功", back != null);
            if (back != null) {
                expect("包名", eq(src.packageName, back.packageName));
                expect("版本名（含中文）", eq(src.versionName, back.versionName));
                expect("标签（含中文与空格）", eq(src.label, back.label));
                expect("versionCode", src.versionCode == back.versionCode);
                expect("minSdk", src.minSdk == back.minSdk);
                expect("targetSdk", src.targetSdk == back.targetSdk);
                expect("安装位置（含中文）", eq(src.installLocation, back.installLocation));
                expect("权限列表（3 项）", src.permissions.size() == back.permissions.size()
                        && src.permissions.equals(back.permissions));
                expect("组件计数", src.activityCount == back.activityCount
                        && src.serviceCount == back.serviceCount
                        && src.receiverCount == back.receiverCount
                        && src.providerCount == back.providerCount);
                expect("导出计数", src.activityCountExported == back.activityCountExported
                        && src.serviceCountExported == back.serviceCountExported
                        && src.receiverCountExported == back.receiverCountExported);
                expect("导出组件列表", src.exportedComponents.equals(back.exportedComponents));
                expect("parseOk / usedFallback", src.parseOk == back.parseOk
                        && src.usedFallbackParser == back.usedFallbackParser);
                expect("parseError 为 null 时仍为 null",
                        src.parseError == null && back.parseError == null);
                expect("签名标记", src.signaturePresent == back.signaturePresent);
                expect("签名状态/完整性", src.signatureStatus == back.signatureStatus
                        && src.signatureIntegrity == back.signatureIntegrity);
                expect("v1..v4", src.sigV1 == back.sigV1 && src.sigV2 == back.sigV2
                        && src.sigV3 == back.sigV3 && src.sigV4 == back.sigV4);
                expect("证书摘要", eq(src.archiveSignerSha1, back.archiveSignerSha1));
                expect("完整性说明", eq(src.signatureIntegrityDetail, back.signatureIntegrityDetail));
                expect("签名说明（含长中文句子）", eq(src.signatureNote, back.signatureNote));
                expect("清单版本号", eq(src.manifestVersionCode, back.manifestVersionCode));
                expect("架构标记与列表", src.nativeAbisPresent == back.nativeAbisPresent
                        && src.nativeAbis.equals(back.nativeAbis));
                expect("图标字节（PNG）", java.util.Arrays.equals(src.iconPng, back.iconPng));
            }
            System.out.println("   编码后 " + (raw == null ? 0 : raw.length) + " 字节");
            System.out.println();
        }

        // ---------- 2. 坏数据必须被拒绝 ----------
        System.out.println("2. 版本不符 / 截断 / 空数据一律返回 null");
        {
            byte[] good = ParseCacheCodec.encode(sample());

            byte[] badMagic = good.clone();
            badMagic[0] = (byte) 0x00;
            expect("magic 被改 → null", ParseCacheCodec.decode(badMagic) == null);

            // 版本字段是第 4~7 字节（大端）
            byte[] badVersion = good.clone();
            badVersion[7] = (byte) (badVersion[7] + 1);
            expect("版本被改 → null", ParseCacheCodec.decode(badVersion) == null);

            byte[] truncated = new byte[good.length / 2];
            System.arraycopy(good, 0, truncated, 0, truncated.length);
            expect("截断一半 → null", ParseCacheCodec.decode(truncated) == null);

            expect("空数组 → null", ParseCacheCodec.decode(new byte[0]) == null);
            expect("null → null", ParseCacheCodec.decode(null) == null);
            expect("超短（4 字节）→ null", ParseCacheCodec.decode(new byte[4]) == null);
            System.out.println();
        }

        // ---------- 3. 缓存键必须跟着文件走 ----------
        System.out.println("3. 键随文件变化（路径 / 大小 / mtime）");
        {
            File a = File.createTempFile("dsh-a", ".apk");
            File b = File.createTempFile("dsh-b", ".apk");
            try {
                writeBytes(a, new byte[]{1, 2, 3});
                writeBytes(b, new byte[]{1, 2, 3});

                String k1 = ParseCache.keyFor(a.getAbsolutePath());
                String k2 = ParseCache.keyFor(a.getAbsolutePath());
                expect("同一文件 → 键稳定", eq(k1, k2));
                expect("键是 16 位小写十六进制", IconCacheKey.isValidKey(k1));

                String kOther = ParseCache.keyFor(b.getAbsolutePath());
                expect("不同路径 → 换键", !eq(k1, kOther));

                // 改大小（追加一个字节）
                writeBytes(a, new byte[]{1, 2, 3, 4});
                String kBigger = ParseCache.keyFor(a.getAbsolutePath());
                expect("文件变大 → 换键", !eq(k1, kBigger));

                // 只改 mtime（内容长度回到 3 字节）
                writeBytes(a, new byte[]{1, 2, 3});
                a.setLastModified(a.lastModified() + 2000L);
                String kTouched = ParseCache.keyFor(a.getAbsolutePath());
                expect("只改 mtime → 换键", !eq(k1, kTouched));
            } finally {
                a.delete();
                b.delete();
            }
            System.out.println();
        }

        // ---------- 4. capture / fill 等价 ----------
        System.out.println("4. capture(card) → fill(card2) 字段一致");
        {
            ApkCard card = new ApkCard();
            card.packageName = "com.example.demo";
            card.versionName = "1.2.3";
            card.label = "示例应用";
            card.versionCode = 7;
            card.minSdk = 15;
            card.targetSdk = 18;
            card.installLocation = "自动（优先内部存储）";
            card.permissions.add("android.permission.INTERNET");
            card.permissions.add("android.permission.RECEIVE_SMS");
            card.activityCount = 3;
            card.activityCountExported = 1;
            card.exportedComponents.add("com.example.demo.MainActivity");
            card.parseOk = true;
            card.usedFallbackParser = false;
            card.signatureStatus = 1;
            card.signatureIntegrity = 2;
            card.sigV1 = true;
            card.sigV2 = true;
            card.sigV3 = false;
            card.archiveSignerSha1 = "0123456789abcdef0123456789abcdef01234567";
            card.signatureIntegrityDetail = "条目摘要一致";
            card.signatureNote = "v1+v2，完整性通过";
            card.manifestVersionCode = "7";
            card.nativeAbis.add("armeabi-v7a");

            ParseCacheData d = ParseCache.capture(card, true, true);
            expect("capture 带上了签名", d.signaturePresent);
            expect("capture 带上了架构", d.nativeAbisPresent && d.nativeAbis.size() == 1);

            ApkCard card2 = new ApkCard();
            ParseCache.fill(card2, d);
            expect("包名", eq(card.packageName, card2.packageName));
            expect("标签", eq(card.label, card2.label));
            expect("权限列表", card.permissions.equals(card2.permissions));
            expect("导出组件", card.exportedComponents.equals(card2.exportedComponents));
            expect("组件计数", card.activityCount == card2.activityCount
                    && card.activityCountExported == card2.activityCountExported);
            expect("签名状态", card.signatureStatus == card2.signatureStatus
                    && card.signatureIntegrity == card2.signatureIntegrity);
            expect("签名方案", card.sigV1 == card2.sigV1 && card.sigV2 == card2.sigV2
                    && card.sigV3 == card2.sigV3);
            expect("证书摘要", eq(card.archiveSignerSha1, card2.archiveSignerSha1));
            expect("签名说明", eq(card.signatureNote, card2.signatureNote));
            expect("架构列表", card.nativeAbis.equals(card2.nativeAbis));

            // 不完整解析时不该声称「签名已算过」
            ParseCacheData d2 = ParseCache.capture(card, false, false);
            expect("capture(false,false) 不带签名", !d2.signaturePresent);
            expect("capture(false,false) 不带架构", !d2.nativeAbisPresent);
            System.out.println();
        }

        // ---------- 5. 解析失败的结果不缓存 ----------
        System.out.println("5. parseOk=false 的数据不该进缓存");
        {
            // save() 里判断的是传入数据自身的 parseOk；这里验证字段能如实表达失败
            ApkCard bad = new ApkCard();
            bad.parseOk = false;
            bad.parseError = "解析异常: 测试";
            ParseCacheData d = ParseCache.capture(bad, false, false);
            expect("失败状态被如实记录", !d.parseOk);
            expect("失败原因被记录", eq("解析异常: 测试", d.parseError));
            ParseCacheData back = ParseCacheCodec.decode(ParseCacheCodec.encode(d));
            expect("往返后仍是失败状态", back != null && !back.parseOk);
            System.out.println();
        }

        // ---------- 6. 孤儿缓存的清理规则 ----------
        //
        // 规则一旦写错，表现是「缓存被误删」或「垃圾永远不清」，都不会报错。
        // 尤其要守住：SD 卡没挂载时（一个键都没扫到）**绝对不能删**。
        System.out.println("6. 孤儿缓存：该删的删、该留的留");
        {
            long now = 1000000000000L;
            long day = 24L * 60L * 60L * 1000L;
            long grace = CacheSweep.DEFAULT_GRACE_MILLIS;
            String keyA = "aaaaaaaaaaaaaaaa";
            String keyB = "bbbbbbbbbbbbbbbb";

            java.util.Set<String> seenA = new java.util.HashSet<String>();
            seenA.add(keyA);
            java.util.Set<String> seenB = new java.util.HashSet<String>();
            seenB.add(keyB);
            java.util.Set<String> none = new java.util.HashSet<String>();

            long[] oldA = new long[]{now - 30 * day};
            long[] freshA = new long[]{now - day};
            String[] keysA = new String[]{keyA};

            expect("仍在用（本次扫到了）→ 不删",
                    CacheSweep.chooseOrphans(oldA, keysA, seenA, now, grace).length == 0);
            // 注意：这里用 seenB（本次扫到了**别的**键，但这个文件没被扫到）
            // 才是真正的「孤儿」场景；空集合代表「什么都没扫到」，见下一条。
            expect("不在用 + 超过宽限期（30 天）→ 删",
                    CacheSweep.chooseOrphans(oldA, keysA, seenB, now, grace).length == 1);
            expect("不在用 + 未超宽限期（1 天）→ 不删（可能只是刚拔了卡）",
                    CacheSweep.chooseOrphans(freshA, keysA, seenB, now, grace).length == 0);
            expect("一个键都没扫到（卡没挂载）→ 不删",
                    CacheSweep.chooseOrphans(oldA, keysA, none, now, grace).length == 0);
            expect("seen 为 null → 不删",
                    CacheSweep.chooseOrphans(oldA, keysA, null, now, grace).length == 0);
            expect("最后访问时间在未来（时钟被改）→ 不删",
                    CacheSweep.chooseOrphans(new long[]{now + 5 * day}, keysA, none, now, grace).length == 0);
            expect("长度不一致 → 返回空，不越界",
                    CacheSweep.chooseOrphans(new long[2], keysA, none, now, grace).length == 0);
            expect("null 输入 → 返回空",
                    CacheSweep.chooseOrphans(null, null, none, now, grace).length == 0);

            long[] mixedLast = new long[]{now - 30 * day, now - 30 * day};
            String[] mixedKeys = new String[]{keyA, keyB};
            int[] del = CacheSweep.chooseOrphans(mixedLast, mixedKeys, seenB, now, grace);
            expect("混合场景只删不在用的那条", del.length == 1 && del[0] == 0);
            System.out.println();
        }

        if (failures == 0) {
            System.out.println(">>> 全部通过");
        } else {
            System.out.println(">>> 有 " + failures + " 项未通过");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static ParseCacheData sample() {
        ParseCacheData d = new ParseCacheData();
        d.packageName = "com.example.demo";
        d.versionName = "1.2.3-测试版";
        d.label = "示例 应用";
        d.versionCode = 42;
        d.minSdk = 15;
        d.targetSdk = 18;
        d.installLocation = "优先外部存储";
        d.permissions.add("android.permission.INTERNET");
        d.permissions.add("android.permission.READ_CONTACTS");
        d.permissions.add("android.permission.RECEIVE_SMS");
        d.activityCount = 5;
        d.serviceCount = 2;
        d.receiverCount = 1;
        d.providerCount = 0;
        d.activityCountExported = 2;
        d.serviceCountExported = 1;
        d.receiverCountExported = 0;
        d.exportedComponents.add("com.example.demo.MainActivity");
        d.exportedComponents.add("com.example.demo.SyncService");
        d.parseOk = true;
        d.parseError = null;
        d.usedFallbackParser = false;
        d.signaturePresent = true;
        d.signatureStatus = 1;
        d.signatureIntegrity = 2;
        d.sigV1 = true;
        d.sigV2 = true;
        d.sigV3 = true;
        d.sigV4 = false;
        d.archiveSignerSha1 = "0123456789abcdef0123456789abcdef01234567";
        d.signatureIntegrityDetail = "条目摘要一致，清单完整";
        d.signatureNote = "v1+v2+v3；这是刻意写得比较长的一句说明，用来确认长中文串不会被截断或乱码。";
        d.manifestVersionCode = "42";
        d.nativeAbisPresent = true;
        d.nativeAbis.add("armeabi-v7a");
        // 一小段假的 PNG 头 + 数据：只验证字节能原样往返（真图标在设备上验证）
        d.iconPng = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 1, 2, 3};
        return d;
    }

    private static void writeBytes(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
            out.flush();
        } finally {
            out.close();
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
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
