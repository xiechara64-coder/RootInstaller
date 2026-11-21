package com.dsh.rootinstaller;

import java.util.ArrayList;
import java.util.List;

/**
 * 「整包解析结果缓存」里存的东西（纯 Java，不引用 Android API）。
 *
 * <h3>为什么单独开一个类，而不是直接序列化 {@link ApkCard}</h3>
 * 一是 ApkCard 里有 {@code Bitmap icon}，那属于图标缓存，不该在这里重复存。
 * 二是 ApkCard 里有一半字段（已装版本对比、权限差异、风险等级）**依赖运行状态**，
 * 每次扫描都必须重算 —— 缓存它们只会让「升级后状态没刷新」这类问题变多。
 *
 * <p>所以这里只放**APK 文件自身的静态信息**：
 * 包名/版本/标签/API 级别/权限/组件计数，以及（详情页才算的）签名结论与 CPU 架构。
 */
public final class ParseCacheData {

    // ---- 基础（来自 AndroidManifest / PackageInfo）----
    public String packageName;
    public String versionName;
    public String label;
    public int versionCode = -1;
    public int minSdk = -1;
    public int targetSdk = -1;
    public String installLocation = "";
    public final List<String> permissions = new ArrayList<String>();

    // ---- 组件 ----
    public int activityCount;
    public int serviceCount;
    public int receiverCount;
    public int providerCount;
    public int activityCountExported;
    public int serviceCountExported;
    public int receiverCountExported;
    public final List<String> exportedComponents = new ArrayList<String>();

    // ---- 解析状态 ----
    public boolean parseOk;
    public String parseError;
    public boolean usedFallbackParser;

    // ---- 签名（只有详情页的「完整解析」才算，所以带一个「是否算过」的标记）----
    public boolean signaturePresent;
    public int signatureStatus = ApkSignatures.STATUS_UNKNOWN;
    public int signatureIntegrity = ApkSignatures.INTEGRITY_UNKNOWN;
    public boolean sigV1;
    public boolean sigV2;
    public boolean sigV3;
    public boolean sigV4;
    public String archiveSignerSha1;
    public String signatureIntegrityDetail;
    public String signatureNote;
    public String manifestVersionCode;

    // ---- CPU 架构（同样是完整解析才算）----
    public boolean nativeAbisPresent;
    public final List<String> nativeAbis = new ArrayList<String>();

    /**
     * 图标的 PNG 字节（没有图标时为 null）。
     *
     * <p>为什么图标要并进这里、而不是单独做一个图标缓存：
     * 两份缓存就必然有两个键、两套清理逻辑，只要有一处对不上，就会出现
     * 「图标明明写了却每次都要重新解码」——实测踩过这个坑（热扫卡在 17 秒）。
     * 一个 APK 一个文件、读一次什么都拿到，这类问题从根上没有了。
     */
    public byte[] iconPng;
}
