package com.dsh.rootinstaller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「同签名 + 同名称，但包名不同」的分组（纯 Java，可离线测）。
 *
 * <h3>为什么不能只按签名分组</h3>
 * 第一版只比签名，实测在目标设备上 **151 个包里有 140 个**被判定为「同签名不同包名」——
 * 因为厂商系统应用共用同一个平台/厂商签名。提示出现 140 次就等于没有提示，
 * 而且会把正常的「同一开发者的多个应用」也算进来。
 *
 * <h3>实际采用的判据</h3>
 * 两条同时成立才算一组：
 * <ol>
 *   <li><b>签名相同</b> —— 覆盖安装的必要条件之一；</li>
 *   <li><b>应用名称相同</b>（去掉首尾空白、忽略大小写）—— 改包名的应用通常保留同一名称，
 *       而「同一开发者的不同应用」几乎不会同名。</li>
 * </ol>
 *
 * <p>这样命中的就是真正值得提醒的情况：**看起来是同一个应用，却是另一个包名**。
 * 覆盖安装要求「包名相同且签名相同」，所以这类包不能互相覆盖，会作为两个应用共存。
 */
public final class SameSigGroups {

    /** 同一个包名之外的其它同名签名包。 */
    public static final class Info {
        /** 其它包的数量（不含自己）。 */
        public int count;
        /** 其它包名的展示串，例如「com.a、com.b」。 */
        public String summary;
    }

    private SameSigGroups() {
    }

    /**
     * 计算每个包「同签名且同名称、但包名不同」的其它包。
     *
     * @param packages 包名列表
     * @param certs    对应证书 SHA1；null/空表示签名未知，不参与分组
     * @param labels   对应应用名称；null/空表示名称未知，不参与分组
     * @return 包名 → Info；没有同伴的包不会出现在结果里
     */
    public static Map<String, Info> compute(List<String> packages, List<String> certs,
                                            List<String> labels) {
        Map<String, Info> result = new HashMap<String, Info>();
        if (packages == null || certs == null || labels == null
                || packages.size() != certs.size() || packages.size() != labels.size()) {
            return result;
        }

        // 1. 分组键 = 证书 + 规范化名称
        Map<String, List<String>> byKey = new HashMap<String, List<String>>();
        for (int i = 0; i < packages.size(); i++) {
            String pkg = packages.get(i);
            String cert = certs.get(i);
            String label = normalize(labels.get(i));
            if (pkg == null || pkg.length() == 0 || cert == null || cert.length() == 0
                    || label == null) {
                continue;
            }
            String key = cert + '\u0000' + label;
            List<String> list = byKey.get(key);
            if (list == null) {
                list = new ArrayList<String>();
                byKey.put(key, list);
            }
            if (!list.contains(pkg)) {
                list.add(pkg);
            }
        }

        // 2. 每个包挑出「同键、但包名不同」的那些
        for (int i = 0; i < packages.size(); i++) {
            String pkg = packages.get(i);
            String cert = certs.get(i);
            String label = normalize(labels.get(i));
            if (pkg == null || cert == null || cert.length() == 0 || label == null) {
                continue;
            }
            List<String> same = byKey.get(cert + '\u0000' + label);
            if (same == null) {
                continue;
            }
            List<String> others = new ArrayList<String>();
            for (int k = 0; k < same.size(); k++) {
                if (!pkg.equals(same.get(k))) {
                    others.add(same.get(k));
                }
            }
            if (others.isEmpty()) {
                continue;
            }
            Info info = new Info();
            info.count = others.size();
            info.summary = join(others);
            result.put(pkg, info);
        }
        return result;
    }

    /** 规范化应用名称：去首尾空白、压缩内部连续空白、转小写。空/全空白返回 null。 */
    static String normalize(String label) {
        if (label == null) {
            return null;
        }
        String s = label.trim().replaceAll("\\s+", " ");
        if (s.length() == 0) {
            return null;
        }
        return s.toLowerCase(java.util.Locale.US);
    }

    private static String join(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(names.get(i));
        }
        return sb.toString();
    }
}
