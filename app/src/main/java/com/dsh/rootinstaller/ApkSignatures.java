package com.dsh.rootinstaller;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * APK 签名状态判断。
 *
 * <p>为什么单独写一个类，而且要刻意不引用任何 Android API：
 * 签名这块的坑全在「格式细节」上（APK Signing Block 的偏移、证书在
 * signed-data 里的位置、MANIFEST.MF 的摘要链），真机上根本没法调试，
 * 所以这里做成纯 Java，在 PC 上配合 apksigner 造出来的样本离线跑用例。
 *
 * <h3>为什么签名判断不能和「是否已安装」绑在一起</h3>
 * 这里判断的是 **APK 文件自身的属性**，和它有没有装在机器上毫无关系。
 *
 * <p>更关键的是：**不能只看 v1**。Android 的签名有三代方案：
 * <ul>
 *   <li>v1（Jar 签名）：证书放在 {@code META-INF/*.RSA|DSA|EC}，Android 1.0 起支持</li>
 *   <li>v2（APK Signing Block）：整包摘要，Android 7.0 起支持</li>
 *   <li>v3：在 v2 基础上加了密钥轮换，Android 9 起支持</li>
 * </ul>
 * 只认 {@code META-INF} 里那几个文件的话，**只做 v2/v3 签名的正常包会被误判成
 * 「未签名」** —— 这是必须避免的误报，因为它会冤枉一个完全正常的安装包。
 *
 * <h3>在本机的实际意义（Android 4.3）</h3>
 * Android 4.3 只认 v1，所以「没有 v1 签名」的包在本机**必然装不上**。
 * 这本身就是一条对用户很有价值的结论 —— 但必须和「未签名」区分开：
 * 前者可能是完全正常的新式签名包，只是老系统不认。
 */
public final class ApkSignatures {

    private ApkSignatures() {
    }

    // ---- 签名状态 ----

    /** 没能判断。 */
    public static final int STATUS_UNKNOWN = 0;
    /** 包里没有任何签名。这是包本身的问题（正式系统上装不上）。 */
    public static final int STATUS_UNSIGNED = 1;
    /** 有签名。 */
    public static final int STATUS_SIGNED = 2;

    // ---- 完整性判断的三态 ----

    /** 没能判断（没有 v1 签名，或者读取过程中出错）。 */
    public static final int INTEGRITY_UNKNOWN = 0;
    /** 通过：摘要链对得上。 */
    public static final int INTEGRITY_OK = 1;
    /** 不通过：签名存在，但内容对不上（被改过）。 */
    public static final int INTEGRITY_BAD = 2;

    // ---- APK Signing Block 的常量 ----

    /** APK Signing Block 尾部 16 字节的魔数。 */
    private static final byte[] APK_SIG_BLOCK_MAGIC = {
            'A', 'P', 'K', ' ', 'S', 'i', 'g', ' ', 'B', 'l', 'o', 'c', 'k', ' ', '4', '2'
    };

    /** v2 签名方案的 ID。 */
    private static final int ID_V2 = 0x7109871a;
    /** v3 签名方案的 ID。 */
    private static final int ID_V3 = 0xf05368c0;

    /** v2/v3 的 signed-data 里，certificates 字段在 digests 之后。 */
    private static final int SIGNED_DATA_CERT_INDEX = 1;

    /** 安全上限：Signing Block 超过 64MB 说明读到的长度不可信，直接放弃。 */
    private static final long MAX_SIG_BLOCK = 64L * 1024 * 1024;

    /** 安全上限：单个长度前缀字段超过 16MB 视为数据损坏。 */
    private static final int MAX_FIELD = 16 * 1024 * 1024;

    /** 一次解析的结果。字段全部 public，方便离线用例直接断言。 */
    public static final class Result {

        /** 结果对应的文件路径。 */
        public String path;

        /** 是否有 v1（Jar）签名。 */
        public boolean v1;
        /** 是否有 v2（APK Signing Block）签名。 */
        public boolean v2;
        /** 是否有 v3 签名。 */
        public boolean v3;
        /** 同目录是否存在 v4 签名文件（xxx.apk.idsig）。 */
        public boolean v4;

        /**
         * 签名者证书的 SHA1（小写十六进制）。
         *
         * 取自 X.509 证书的原始编码，和 Android 的
         * {@code PackageManager} 给出的 {@code Signature} 是同一份字节，
         * 所以「本次 APK」和「已装版本」的可以逐字比较。
         */
        public String certSha1;

        /** 完整性：见 INTEGRITY_* 常量。 */
        public int integrity = INTEGRITY_UNKNOWN;

        /** 完整性不通过的具体原因（给人看的）。 */
        public String integrityDetail;

        /**
         * MANIFEST.MF 里记录的 AndroidManifest.xml 版本号（拿不到为 null）。
         *
         * 和真正解析出来的 versionCode 对比，能发现「改了包没重签名」：
         * 重新打包会重写 MANIFEST.MF，而改包的人极少会去同步这里的值。
         */
        public String manifestVersionCode;

        /** 解析过程中遇到的非致命问题，用于排错。 */
        public String note;

        /** 排查用的内部定位信息（离线用例会打印，正式界面不显示）。 */
        public String debug;

        /** 是否至少有一种签名方案。 */
        public boolean anyScheme() {
            return v1 || v2 || v3 || v4;
        }

        /** 多种方案共存时的描述，例如 "v1 + v2 + v3"。 */
        public String schemeText() {
            List<String> parts = new ArrayList<String>();
            if (v1) {
                parts.add("v1");
            }
            if (v2) {
                parts.add("v2");
            }
            if (v3) {
                parts.add("v3");
            }
            if (v4) {
                parts.add("v4");
            }
            if (parts.isEmpty()) {
                return "无";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) {
                    sb.append(" + ");
                }
                sb.append(parts.get(i));
            }
            return sb.toString();
        }
    }

    /**
     * 读取一个 APK 的签名状态。永不抛异常：读不出来的部分留在 Result 里说明。
     */
    public static Result inspect(String apkPath) {
        Result r = new Result();
        r.path = apkPath;
        if (apkPath == null) {
            r.note = "路径为空";
            return r;
        }
        File f = new File(apkPath);
        if (!f.isFile()) {
            r.note = "文件不存在";
            return r;
        }

        readV1(f, r);
        readSigningBlock(f, r);

        // v4 只是同目录的一个旁路文件，看一眼文件名就知道
        r.v4 = new File(apkPath + ".idsig").isFile();

        // 有 v1 才有摘要链可查；只有 v2/v3 的话本类没有能力验证
        // （那要完整重算整包摘要，代价极高，且系统在安装时会做），如实留 UNKNOWN。
        if (r.v1) {
            verifyV1DigestChain(f, r);
        }

        if (!r.anyScheme() && r.note == null) {
            r.note = "未找到任何签名";
        }
        return r;
    }

    // ------------------------------------------------------------------
    // v1：META-INF 里的证书
    // ------------------------------------------------------------------

    private static void readV1(File f, Result r) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(f);
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName().toUpperCase(Locale.US);
                if (!n.startsWith("META-INF/")) {
                    continue;
                }
                if (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")) {
                    r.v1 = true;
                    if (r.certSha1 == null) {
                        InputStream in = zip.getInputStream(e);
                        try {
                            r.certSha1 = firstCertSha1(in);
                        } finally {
                            closeQuietly(in);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            r.note = appendNote(r.note, "读 v1 签名失败: " + t);
        } finally {
            closeQuietly(zip);
        }
    }

    /** 从 PKCS#7 签名块里取出第一张证书，返回其 SHA1。 */
    private static String firstCertSha1(InputStream in) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            java.util.Collection<? extends java.security.cert.Certificate> certs = cf.generateCertificates(in);
            for (java.security.cert.Certificate c : certs) {
                if (c instanceof X509Certificate) {
                    return sha1Hex(c.getEncoded());
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // v2 / v3：APK Signing Block
    // ------------------------------------------------------------------

    private static void readSigningBlock(File f, Result r) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long fileLen = raf.length();
            // 至少要能放下一个 EOCD 才可能带 Signing Block
            if (fileLen < 64) {
                return;
            }

            // 定位魔数不能简单地「从文件末尾往前数固定字节」：
            // 实测 apksigner 会在最后一个条目和 Signing Block 之间塞一坨零填充做对齐，
            // 填充长度随包而变（样本里就有 200 多字节），任何固定偏移都会算错。
            // 所以从尾部往前**搜**魔数。
            long magicPos = findMagicBackwards(raf, fileLen - APK_SIG_BLOCK_MAGIC.length);
            if (magicPos < 0) {
                // 绝大多数老包都是这个分支：没有 Signing Block，正常，不算错误
                return;
            }

            // 布局：[size1(8)] [id-value 对...] [size2(8)] [magic(16)]
            //
            // 但这里**不按 size1 去推算 id-value 区的起点**。实测 apksigner 会在这
            // 段结构前后再塞零填充做对齐（样本里 size1 位置读到的是 0），
            // 任何「从魔数往前数」的算法都会踩到填充上。
            //
            // 改用更稳的办法：取出 [size2][magic] 之前那 size2 个字节作为搜索区，
            // 直接在区里找 v2/v3 的签名块 ID。这两个 ID 是固定的 4 字节魔数，
            // 定位不依赖任何长度字段。
            if (magicPos < 8) {
                r.note = appendNote(r.note, "Signing Block 起点越界");
                return;
            }
            long size2 = readLongLE(raf, magicPos - 8);
            if (size2 <= 0 || size2 > MAX_SIG_BLOCK) {
                r.note = appendNote(r.note, "Signing Block 长度异常: " + size2);
                return;
            }

            long regionStart = magicPos - 8 - size2;
            if (regionStart < 0) {
                r.note = appendNote(r.note, "Signing Block 起点越界: " + regionStart);
                return;
            }

            byte[] region = new byte[(int) size2];
            raf.seek(regionStart);
            raf.readFully(region);

            r.debug = "magicPos=" + magicPos + " size2=" + size2 + " regionStart=" + regionStart;

            // 遍历方式：先跳到第一个 ID，然后按每条自己的长度逐跳前进。
            //
            // 为什么不直接在整块里搜 v3 的 ID：v2 的 value 里**自己也含一个
            // 「带长度的 ID-value 序列」**（它用同一套编码对整包分块摘要），
            // 在里面能搜到与 v3 ID 相同、长度也自洽的假阳性。
            // 逐跳前进则会整条跳过 v2 的 value，不会串进它的内部。
            int firstId = findFirstSchemeId(region, r);
            if (firstId < 0) {
                if (r.note == null) {
                    r.note = "Signing Block 里没找到 v2/v3 签名块";
                }
            } else {
                walkIdValuePairs(region, firstId, r);
                if (!r.v2 && !r.v3 && r.note == null) {
                    r.note = "Signing Block 里没找到 v2/v3 签名块";
                }
            }
        } catch (Throwable t) {
            r.note = appendNote(r.note, "读 Signing Block 失败: " + t);
        } finally {
            closeQuietly(raf);
        }
    }

    /** 从 from 位置往前搜 APK Signing Block 魔数，找不到返回 -1。 */
    private static long findMagicBackwards(RandomAccessFile raf, long from) throws IOException {
        int m = APK_SIG_BLOCK_MAGIC.length;
        if (from < 0) {
            return -1;
        }
        // 分块往回读，避免为了搜 16 字节把整个文件读进内存
        final int CHUNK = 64 * 1024;
        long pos = from + m;                  // 窗口右端（不含）
        byte[] buf = new byte[CHUNK + m];
        while (pos > 0) {
            long start = Math.max(0, pos - buf.length);
            int len = (int) (pos - start);
            raf.seek(start);
            raf.readFully(buf, 0, len);
            for (int i = len - m; i >= 0; i--) {
                boolean hit = true;
                for (int j = 0; j < m; j++) {
                    if (buf[i + j] != APK_SIG_BLOCK_MAGIC[j]) {
                        hit = false;
                        break;
                    }
                }
                if (hit) {
                    return start + i;
                }
            }
            // 下一轮窗口左移，保留 m-1 字节重叠，避免魔数正好跨块被漏掉
            long next = start + m - 1;
            if (next >= pos) {
                break;
            }
            pos = next;
        }
        return -1;
    }

    /**
     * 找到第一条有效 id-value 对里 ID 的下标。
     *
     * <p>区的前面可能有填充（整块起点不等于第一条对），所以不能假定 ID 就在固定位置；
     * 但一旦找到第一条，后面的就可以按长度逐跳前进（见 {@link #walkIdValuePairs}）。
     *
     * @return ID 所在下标，没找到返回 -1
     */
    private static int findFirstSchemeId(byte[] region, Result r) {
        for (int i = 4; i + 4 <= region.length; i++) {
            boolean isV2 = matchesId(region, i, ID_V2);
            boolean isV3 = matchesId(region, i, ID_V3);
            if (!isV2 && !isV3) {
                continue;
            }
            long pairLen = readLongLE(region, i - 8);
            if (pairLen < 4 || pairLen > MAX_FIELD) {
                continue;
            }
            if (i + 4 + (int) (pairLen - 4) > region.length) {
                continue;
            }
            return i;
        }
        return -1;
    }

    /**
     * 从第一条 id-value 对开始，按长度逐条前进，收集签名方案并取出证书。
     *
     * <p>逐跳前进而不是全块搜索，是为了整条跳过每条 value ——
     * v2 的 value 内部含有同样编码的 ID-value 序列，全块搜索会串进去。
     */
    private static void walkIdValuePairs(byte[] region, int firstIdPos, Result r) {
        int pos = firstIdPos;
        int guard = 0;
        while (pos >= 0 && pos + 4 <= region.length && guard++ < 32) {
            long pairLen = readLongLE(region, pos - 8);
            if (pairLen < 4 || pairLen > MAX_FIELD) {
                return;
            }
            int valueLen = (int) (pairLen - 4);
            if (pos + 4 + valueLen > region.length) {
                return;
            }

            boolean isV2 = matchesId(region, pos, ID_V2);
            boolean isV3 = matchesId(region, pos, ID_V3);
            if (isV2 || isV3) {
                if (isV2) {
                    r.v2 = true;
                } else {
                    r.v3 = true;
                }
                r.debug = appendNote(r.debug, "方案块@region[" + pos + "] valueLen=" + valueLen);
                if (r.certSha1 == null) {
                    byte[] cert = firstSignerCert(region, pos + 4, valueLen, r);
                    if (cert != null) {
                        r.certSha1 = sha1Hex(cert);
                        r.debug = appendNote(r.debug, "cert 长度=" + cert.length);
                    }
                }
            }

            // 下一条：本条的 ID 位置 + 4(ID) + valueLen，再往前 8 就是下一条的长度字段
            int next = pos + 4 + valueLen;
            // 中间可能有填充（实测 apksigner 会留空），往后找下一条 ID
            int found = -1;
            for (int k = next; k + 4 <= region.length; k++) {
                if (matchesId(region, k, ID_V2) || matchesId(region, k, ID_V3)) {
                    long l = readLongLE(region, k - 8);
                    if (l >= 4 && l <= MAX_FIELD && k + 4 + (int) (l - 4) <= region.length) {
                        found = k;
                        break;
                    }
                }
            }
            if (found < 0) {
                return;
            }
            pos = found;
        }
    }

    private static boolean matchesId(byte[] region, int pos, int schemeId) {
        if (pos < 0 || pos + 4 > region.length) {
            return false;
        }
        return (region[pos] & 0xFF) == (schemeId & 0xFF)
                && (region[pos + 1] & 0xFF) == ((schemeId >>> 8) & 0xFF)
                && (region[pos + 2] & 0xFF) == ((schemeId >>> 16) & 0xFF)
                && (region[pos + 3] & 0xFF) == ((schemeId >>> 24) & 0xFF);
    }

    /**
     * 从 v2/v3 的 signer 序列里取出第一位签名者的证书原始编码。
     *
     * <p>嵌套顺序（APK Signature Scheme v2/v3）：
     * <pre>
     * signers: length-prefixed sequence
     *    signer: signed-data, signatures, public-key
     *       signed-data:
     *          digests, certificates, [min-sdk, max-sdk (仅 v3)], additional-attributes
     * </pre>
     *
     * <p><b>为什么不直接按「证书条目 = uint32 长度 + DER」去读：</b>
     * 实测 apksigner 写出来的结构里，条目长度那 4 字节是 0
     * （certsLen=769 而里面紧挨着的 DER 头就是 765 字节的整张证书，
     * 所以条目长度字段并不存在或为 0）。按固定偏移读，读到的会是证书自己的
     * DER 头，算出来的长度是个负数。
     *
     * <p>因为 X.509 证书是自描述的（开头就是 SEQUENCE 和它自己的长度），
     * 所以更稳的做法是：在 signed-data 里定位到这个自描述的证书，
     * 让 DER 自己说明它有多长 —— 完全不依赖任何长度假设。
     */
    private static byte[] firstSignerCert(byte[] d, int signersPos, int signersLen, Result r) {
        try {
            int p = signersPos;
            int signersEnd = signersPos + signersLen;
            if (p + 4 > signersEnd) {
                return null;
            }
            p += 4;                       // 跳过 signers 这层 sequence 长度

            if (p + 4 > signersEnd) {
                return null;
            }
            p += 4;                       // 跳过第一个 signer 的长度

            if (p + 4 > signersEnd) {
                return null;
            }
            int signedDataLen = (int) readIntLE(d, p);
            int q = p + 4;                // 进入 signed-data 内容
            int signedDataEnd = q + signedDataLen;
            if (signedDataEnd > d.length) {
                signedDataEnd = d.length;
            }

            // 跳过 digests（signed-data 里排在 certificates 前面）
            for (int i = 0; i < SIGNED_DATA_CERT_INDEX; i++) {
                if (q + 4 > signedDataEnd) {
                    return null;
                }
                long skipLen = readIntLE(d, q);
                q += 4 + (int) skipLen;
            }

            // certificates 字段：外面套了一层长度，里面才是证书本体
            if (q + 4 > signedDataEnd) {
                return null;
            }
            int certsLen = (int) readIntLE(d, q);
            q += 4;
            int certsEnd = q + certsLen;
            if (certsEnd > signedDataEnd) {
                certsEnd = signedDataEnd;
            }
            if (r != null) {
                r.debug = appendNote(r.debug, "signedDataEnd=" + signedDataEnd
                        + " certsPos=" + q + " certsLen=" + certsLen);
            }

            // 在 [q, certsEnd) 里找自描述的 DER 证书，取第一个能解析成功的
            for (int i = q; i < certsEnd; i++) {
                if (d[i] != 0x30) {
                    continue;
                }
                int len = derLength(d, i);
                if (len <= 0) {
                    continue;
                }
                int total = derHeaderLen(d[i + 1]) + len;
                if (total <= 0 || i + total > d.length) {
                    continue;
                }
                if (r != null) {
                    r.debug = appendNote(r.debug, "找到 DER@region[" + i + "] len=" + len
                            + " total=" + total + " 版本=" + d[i + 1]);
                }
                byte[] out = new byte[total];
                System.arraycopy(d, i, out, 0, total);

                // 验证这确实是一张证书：解析出来必须能拿到编码后的字节
                if (!looksLikeCertificate(out)) {
                    if (r != null) {
                        r.debug = appendNote(r.debug, "这个 DER 不是 X.509 证书，继续找");
                    }
                    continue;
                }
                return out;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读 DER 长度字段。返回内容长度（不含长度字段本身），读不出来返回 -1。
     *
     * @param headerPos 指向 SEQUENCE 标签（0x30）的位置
     */
    private static int derLength(byte[] d, int headerPos) {
        int i = headerPos + 1;
        if (i >= d.length) {
            return -1;
        }
        int b = d[i] & 0xFF;
        if (b < 0x80) {
            return b;
        }
        int count = b & 0x7F;
        if (count < 1 || count > 4 || i + count >= d.length) {
            return -1;
        }
        long v = 0;
        for (int k = 1; k <= count; k++) {
            v = (v << 8) | (d[i + k] & 0xFF);
        }
        if (v <= 0 || v > MAX_FIELD) {
            return -1;
        }
        return (int) v;
    }

    /** DER 长度字段本身占几个字节（1 个长度字节 + 0~4 个长度值字节）。 */
    private static int derHeaderLen(int firstLenByte) {
        int b = firstLenByte & 0xFF;
        if (b < 0x80) {
            return 2;                     // 标签 + 单字节长度
        }
        return 2 + (b & 0x7F);            // 标签 + 长度字节数
    }

    /** 用标准 X.509 解析器确认这确实是一张证书。 */
    private static boolean looksLikeCertificate(byte[] der) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            java.security.cert.Certificate c =
                    cf.generateCertificate(new java.io.ByteArrayInputStream(der));
            return c != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // v1 的摘要链校验
    // ------------------------------------------------------------------

    /**
     * 检查 v1 签名的完整性，分两步。
     *
     * <p><b>第一步：摘要链。</b> {@code XXX.SF} 里记录了对整份 MANIFEST.MF 的摘要，
     * 拿实际值比一比就能发现「MANIFEST.MF 被改过」。
     *
     * <p><b>第二步：清单覆盖。</b> 这一步不能省 —— 实测发现只做第一步会漏判：
     * 往一个已签名的包里**塞进一个新文件**时，MANIFEST.MF 本身一个字节都没变，
     * 于是第一步照样通过，包却是被动过的。真正能抓住它的是「清单里声明的条目」
     * 和「包里实际的条目」对不上（多余的条目说明有东西是签名之后加的，
     * 缺失的条目说明有东西被删了）。
     *
     * <p>两步都做，才算真把「改了包没重签名」这类情况兜住。
     */
    private static void verifyV1DigestChain(File f, Result r) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(f);

            ZipEntry mfEntry = zip.getEntry("META-INF/MANIFEST.MF");
            if (mfEntry == null) {
                r.integrity = INTEGRITY_BAD;
                r.integrityDetail = "有签名文件，但缺少 META-INF/MANIFEST.MF";
                return;
            }
            byte[] mfBytes = readEntry(zip, mfEntry);
            if (mfBytes == null) {
                r.integrity = INTEGRITY_UNKNOWN;
                r.integrityDetail = "MANIFEST.MF 读取失败";
                return;
            }

            ZipEntry sfEntry = findSignatureFile(zip);
            if (sfEntry == null) {
                r.integrity = INTEGRITY_UNKNOWN;
                r.integrityDetail = "找不到 .SF 签名文件";
                return;
            }
            String sf = new String(readEntry(zip, sfEntry), "UTF-8");

            // ---- 第一步：摘要链 ----
            String expectedSha1 = headerValue(sf, "SHA1-Digest-Manifest");
            String expectedSha256 = headerValue(sf, "SHA-256-Digest-Manifest");
            // 有些签名工具写的是不带连字符的写法
            if (expectedSha256 == null) {
                expectedSha256 = headerValue(sf, "SHA256-Digest-Manifest");
            }

            if (expectedSha1 != null) {
                if (!expectedSha1.equals(sha1Base64(mfBytes))) {
                    r.integrity = INTEGRITY_BAD;
                    r.integrityDetail = "MANIFEST.MF 的 SHA1 与签名文件记录的不一致";
                    return;
                }
            } else if (expectedSha256 != null) {
                if (!expectedSha256.equals(sha256Base64(mfBytes))) {
                    r.integrity = INTEGRITY_BAD;
                    r.integrityDetail = "MANIFEST.MF 的 SHA-256 与签名文件记录的不一致";
                    return;
                }
            } else {
                // 早期签名工具只给逐条摘要、不给整份摘要，这一步就无从判断了
                r.integrity = INTEGRITY_UNKNOWN;
                r.integrityDetail = "签名文件未记录 MANIFEST.MF 整体摘要，无法用此方式核对";
                return;
            }

            String mf = new String(mfBytes, "UTF-8");
            // 版本号对比放在调用方做（调用方已经解析出 versionCode 和权限列表），
            // 这里只负责把 MANIFEST.MF 里记录的值带出去。
            r.manifestVersionCode = manifestVersionCode(mf);

            // ---- 第二步：清单覆盖 ----
            String coverIssue = checkManifestCoversEntries(zip, mf);
            if (coverIssue != null) {
                r.integrity = INTEGRITY_BAD;
                r.integrityDetail = coverIssue;
                return;
            }

            r.integrity = INTEGRITY_OK;
        } catch (Throwable t) {
            r.integrity = INTEGRITY_UNKNOWN;
            r.integrityDetail = "核对签名摘要时出错: " + t;
        } finally {
            closeQuietly(zip);
        }
    }

    /**
     * 核对「MANIFEST.MF 里声明了摘要的条目」与「包里实际存在的条目」。
     *
     * <p>返回 null 表示没问题，否则返回一句给人看的原因。
     *
     * <p>META-INF 下的文件按惯例不进清单（清单自己、签名文件），所以一律跳过。
     */
    private static String checkManifestCoversEntries(ZipFile zip, String manifestMf) {
        java.util.Set<String> declared = new java.util.HashSet<String>();
        for (String line : unfoldManifest(manifestMf)) {
            if (line.startsWith("Name:")) {
                String n = line.substring(5).trim();
                if (n.length() > 0) {
                    declared.add(n);
                }
            }
        }
        if (declared.isEmpty()) {
            // 空清单说明不了是坏的，不据此下结论
            return null;
        }

        List<String> extra = new ArrayList<String>();
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) {
                continue;
            }
            String name = e.getName();
            if (name.toUpperCase(Locale.US).startsWith("META-INF/")) {
                continue;
            }
            if (!declared.contains(name)) {
                extra.add(name);
                if (extra.size() >= 3) {
                    break;
                }
            }
        }

        if (!extra.isEmpty()) {
            return "包里有内容文件不在签名清单里，例如 " + describe(extra)
                    + " —— 说明是签名之后加进去的";
        }
        return null;
    }

    /**
     * 把 MANIFEST.MF 的「折行」还原成逻辑行。
     *
     * <p>这一步不能省。JAR 清单规定每行不得超过 72 字节，超长的值会在 72 字节处
     * 断开，续行**以一个空格开头**。文件名很容易超过这个长度，于是
     * {@code Name:} 的值会被切成两行。
     *
     * <p>踩过的坑：一开始没处理折行，只取第一行，于是名字被截断成
     * {@code .../dropdown_ic_arrow_disabled_holo_dark.p}（少了结尾的 "ng"），
     * 拿这个去和包里真实的条目名比对必然对不上 —— 结果把一个
     * **完好的华为系统应用判成了「签名之后被改过」**。
     * 这种误报比漏判更糟：它会去指控一个正常的包。
     *
     * @return 还原后的逻辑行
     */
    public static List<String> unfoldManifest(String text) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = null;
        String[] raw = text.split("\r\n|\n|\r", -1);
        for (int i = 0; i < raw.length; i++) {
            String line = raw[i];
            if (line.length() > 0 && line.charAt(0) == ' ') {
                // 续行：接到上一行末尾（丢掉那个前导空格）
                if (cur != null) {
                    cur.append(line.substring(1));
                }
                continue;
            }
            if (cur != null) {
                out.add(cur.toString());
            }
            cur = new StringBuilder(line);
        }
        if (cur != null) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String describe(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    private static ZipEntry findSignatureFile(ZipFile zip) {
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName().toUpperCase(Locale.US);
            if (n.startsWith("META-INF/") && n.endsWith(".SF")) {
                return e;
            }
        }
        return null;
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry e) throws IOException {
        InputStream in = zip.getInputStream(e);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    /** 从 .SF / MANIFEST.MF 这种「Key: Value」文本里取值（不区分大小写，行首匹配）。 */
    private static String headerValue(String text, String key) {
        String lowerKey = key.toLowerCase(Locale.US);
        String[] lines = text.split("\r\n|\n|\r");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (line.substring(0, colon).trim().toLowerCase(Locale.US).equals(lowerKey)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /**
     * 从 MANIFEST.MF 的 {@code AndroidManifest.xml} 小节里取出版本号。
     *
     * ApkParser 在打包时会把 versionCode 写进这一节，改包的人往往会改
     * versionCode 却忘了（也没法）同步这里，于是出现矛盾。
     */
    private static String manifestVersionCode(String manifestMf) {
        boolean inAndroidManifest = false;
        for (String line : unfoldManifest(manifestMf)) {
            if (line.startsWith("Name:")) {
                inAndroidManifest = line.substring(5).trim().equals("AndroidManifest.xml");
                continue;
            }
            if (!inAndroidManifest) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String k = line.substring(0, colon).trim().toLowerCase(Locale.US);
            if (k.endsWith("versioncode")) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static long readLongLE(RandomAccessFile raf, long pos) throws IOException {
        byte[] b = new byte[8];
        raf.seek(pos);
        raf.readFully(b);
        return readLongLE(b, 0);
    }

    /** 小端 64 位。 */
    private static long readLongLE(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }

    /** 小端 32 位（返回 long 是为了不必担心符号位）。 */
    private static long readIntLE(byte[] b, int off) {
        long v = 0;
        for (int i = 3; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }

    private static String sha1Hex(byte[] data) {
        return digestHex("SHA-1", data);
    }

    private static String sha1Base64(byte[] data) {
        return digestBase64("SHA-1", data);
    }

    private static String sha256Base64(byte[] data) {
        return digestBase64("SHA-256", data);
    }

    private static String digestHex(String algo, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (int i = 0; i < d.length; i++) {
                String h = Integer.toHexString(d[i] & 0xFF);
                if (h.length() == 1) {
                    sb.append('0');
                }
                sb.append(h);
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 摘要的 Base64 编码。
     *
     * 这里刻意不引用 android.util.Base64（本类要能脱离 Android 跑），
     * 自己实现一份标准 Base64。
     */
    private static String digestBase64(String algo, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            return base64(md.digest(data));
        } catch (Throwable t) {
            return null;
        }
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String base64(byte[] d) {
        StringBuilder sb = new StringBuilder(((d.length + 2) / 3) * 4);
        int i = 0;
        while (i + 2 < d.length) {
            int n = ((d[i] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8) | (d[i + 2] & 0xFF);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63])
                    .append(B64[(n >> 6) & 63]).append(B64[n & 63]);
            i += 3;
        }
        int rest = d.length - i;
        if (rest == 1) {
            int n = (d[i] & 0xFF) << 16;
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]).append("==");
        } else if (rest == 2) {
            int n = ((d[i] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63])
                    .append(B64[(n >> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    private static String appendNote(String old, String add) {
        if (old == null) {
            return add;
        }
        return old + "；" + add;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // 关闭失败无所谓
            }
        }
    }
}
