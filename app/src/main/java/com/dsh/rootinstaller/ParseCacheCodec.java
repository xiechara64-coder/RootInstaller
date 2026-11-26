package com.dsh.rootinstaller;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.util.List;

/**
 * {@link ParseCacheData} 的二进制编解码（纯 Java，可离线验证）。
 *
 * <h3>为什么用二进制而不是 JSON / Properties</h3>
 * 这里没有 JSON 库（项目零依赖），而 {@code Properties} 会把中文写成 \\uXXXX、
 * 体积翻几倍，也不是为「按字段解析」设计的。
 * {@link DataOutputStream#writeUTF} 自带长度前缀、能正确表示中文，
 * 读回来一一对应，而且**纯 java.io，不需要 Android**——所以这段逻辑能在 PC 上测。
 *
 * <h3>格式</h3>
 * <pre>
 * int   magic   = 0x44534850 ("DSHP")
 * int   version = 1
 * 然后按固定顺序写各字段（新增字段时**必须**提升 version）
 * </pre>
 *
 * <p>解析失败（magic 不符、版本不符、文件被截断、数据损坏）一律返回 {@code null}，
 * 调用方按「没有缓存」处理重新解析 —— 缓存读坏绝不能影响功能。
 */
public final class ParseCacheCodec {

    /** magic = "DSHP"（DSH Parse）。 */
    public static final int MAGIC = 0x44534850;

    /** 格式版本。字段有任何增删改都要 +1，旧缓存会被自动忽略并覆盖。 */
    public static final int VERSION = 3;

    private ParseCacheCodec() {
    }

    /** 序列化。出错时返回 null（调用方不写缓存即可）。 */
    public static byte[] encode(ParseCacheData d) {
        if (d == null) {
            return null;
        }
        ByteArrayOutputStream bos = null;
        DataOutputStream out = null;
        try {
            bos = new ByteArrayOutputStream(512);
            out = new DataOutputStream(bos);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);

            writeStr(out, d.packageName);
            writeStr(out, d.versionName);
            writeStr(out, d.label);
            out.writeInt(d.versionCode);
            out.writeInt(d.minSdk);
            out.writeInt(d.targetSdk);
            writeStr(out, d.installLocation);
            writeList(out, d.permissions);

            out.writeInt(d.activityCount);
            out.writeInt(d.serviceCount);
            out.writeInt(d.receiverCount);
            out.writeInt(d.providerCount);
            out.writeInt(d.activityCountExported);
            out.writeInt(d.serviceCountExported);
            out.writeInt(d.receiverCountExported);
            writeList(out, d.exportedComponents);

            out.writeBoolean(d.parseOk);
            writeStr(out, d.parseError);
            out.writeBoolean(d.usedFallbackParser);

            out.writeBoolean(d.signaturePresent);
            out.writeInt(d.signatureStatus);
            out.writeInt(d.signatureIntegrity);
            out.writeBoolean(d.sigV1);
            out.writeBoolean(d.sigV2);
            out.writeBoolean(d.sigV3);
            out.writeBoolean(d.sigV4);
            writeStr(out, d.archiveSignerSha1);
            writeStr(out, d.signatureIntegrityDetail);
            writeStr(out, d.signatureNote);
            writeStr(out, d.manifestVersionCode);

            out.writeBoolean(d.nativeAbisPresent);
            writeList(out, d.nativeAbis);

            // 只读证书（供同签名分组用）
            writeStr(out, d.certSha1);
            out.writeBoolean(d.certV1);
            out.writeBoolean(d.certV2);
            out.writeBoolean(d.certV3);
            out.writeBoolean(d.certV4);

            // 图标：长度 -1 表示「没有图标」，和「空数组」区分开
            if (d.iconPng == null) {
                out.writeInt(-1);
            } else {
                out.writeInt(d.iconPng.length);
                out.write(d.iconPng);
            }

            out.flush();
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w("序列化解析缓存失败: " + t);
            return null;
        } finally {
            closeQuietly(out);
            closeQuietly(bos);
        }
    }

    /** 反序列化。任何异常/版本不符都返回 null。 */
    public static ParseCacheData decode(byte[] raw) {
        if (raw == null || raw.length < 8) {
            return null;
        }
        ByteArrayInputStream bis = null;
        DataInputStream in = null;
        try {
            bis = new ByteArrayInputStream(raw);
            in = new DataInputStream(bis);
            if (in.readInt() != MAGIC) {
                return null;
            }
            if (in.readInt() != VERSION) {
                // 旧格式：直接当没有缓存，让它重新解析并用新格式覆盖
                return null;
            }

            ParseCacheData d = new ParseCacheData();
            d.packageName = readStr(in);
            d.versionName = readStr(in);
            d.label = readStr(in);
            d.versionCode = in.readInt();
            d.minSdk = in.readInt();
            d.targetSdk = in.readInt();
            d.installLocation = readStr(in);
            readList(in, d.permissions);

            d.activityCount = in.readInt();
            d.serviceCount = in.readInt();
            d.receiverCount = in.readInt();
            d.providerCount = in.readInt();
            d.activityCountExported = in.readInt();
            d.serviceCountExported = in.readInt();
            d.receiverCountExported = in.readInt();
            readList(in, d.exportedComponents);

            d.parseOk = in.readBoolean();
            d.parseError = readStr(in);
            d.usedFallbackParser = in.readBoolean();

            d.signaturePresent = in.readBoolean();
            d.signatureStatus = in.readInt();
            d.signatureIntegrity = in.readInt();
            d.sigV1 = in.readBoolean();
            d.sigV2 = in.readBoolean();
            d.sigV3 = in.readBoolean();
            d.sigV4 = in.readBoolean();
            d.archiveSignerSha1 = readStr(in);
            d.signatureIntegrityDetail = readStr(in);
            d.signatureNote = readStr(in);
            d.manifestVersionCode = readStr(in);

            d.nativeAbisPresent = in.readBoolean();
            readList(in, d.nativeAbis);

            d.certSha1 = readStr(in);
            d.certV1 = in.readBoolean();
            d.certV2 = in.readBoolean();
            d.certV3 = in.readBoolean();
            d.certV4 = in.readBoolean();

            int iconLen = in.readInt();
            if (iconLen >= 0) {
                // 防御：明显的坏数据直接放弃，别去分配 4MB 以上的数组
                if (iconLen > 4 * 1024 * 1024) {
                    throw new EOFException("图标数据过大: " + iconLen);
                }
                byte[] icon = new byte[iconLen];
                in.readFully(icon);
                d.iconPng = icon;
            }
            return d;
        } catch (EOFException e) {
            // 文件被截断（写到一半断电之类）：当作没有缓存
            Log.w("解析缓存不完整，已忽略");
            return null;
        } catch (Throwable t) {
            Log.w("反序列化解析缓存失败: " + t);
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(bis);
        }
    }

    /**
     * 写一个字符串：先写 boolean 表示是否为 null，再写内容。
     * writeUTF 自带 2 字节长度前缀，上限 65535 字节 —— 这些字段都远小于它。
     */
    private static void writeStr(DataOutputStream out, String s) throws Exception {
        if (s == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeUTF(s);
        }
    }

    private static String readStr(DataInputStream in) throws Exception {
        if (!in.readBoolean()) {
            return null;
        }
        return in.readUTF();
    }

    private static void writeList(DataOutputStream out, List<String> list) throws Exception {
        int n = list == null ? 0 : list.size();
        out.writeInt(n);
        for (int i = 0; i < n; i++) {
            writeStr(out, list.get(i));
        }
    }

    private static void readList(DataInputStream in, List<String> into) throws Exception {
        int n = in.readInt();
        // 防御：明显不合理的数量直接放弃（避免坏数据导致巨大内存分配）
        if (n < 0 || n > 100000) {
            throw new EOFException("列表长度不合理: " + n);
        }
        for (int i = 0; i < n; i++) {
            into.add(readStr(in));
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
