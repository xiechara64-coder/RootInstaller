package com.dsh.rootinstaller;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 安装详情页 —— 本应用的核心。
 *
 * 系统自带的安装器只告诉你「需要这些权限」，然后就是一个安装按钮。
 * 这里额外给出：
 *   · 包名 / 版本号 / 版本代码 / 最低与目标 API / 安装位置
 *   · 每个权限的中文含义和保护级别，危险的标红
 *   · 组件统计，以及「对外暴露」的组件清单（安全风险点）
 *   · 体积、修改时间、MD5、SHA1、签名摘要
 *   · 和已安装版本的对比：全新安装 / 可升级 / 同版本覆盖 / 降级，以及签名是否一致
 *   · 带 so 的架构列表（判断能不能在这台机器上跑）
 */
public class DetailsActivity extends Activity {

    public static final String EXTRA_APK = "apk_path";
    public static final String EXTRA_FROM_EXTERNAL = "from_external";

    private ImageView iconView;
    private TextView titleView;
    private TextView subtitleView;
    private TextView statusView;
    /** 状态区容器（含进度条 + 文字），整条一起显示/隐藏。 */
    private View statusArea;
    /** 安装/加载时显示的横向不确定进度条。 */
    private android.widget.ProgressBar progressBar;
    /** 文字旁的旋转指示器（比细条更显眼，避免用户没注意到正在干活）。 */
    private android.widget.ProgressBar spinner;
    private CheckBox downgradeBox;
    private TextView installButton;
    private TextView uninstallButton;
    private ListView list;
    private InfoAdapter adapter;

    private String apkPath;
    private ApkCard card;
    private boolean installing;
    private boolean hashesComputed;
    /** 是否是「被别的应用调起来装东西」的场景（决定返回键行为）。 */
    private boolean launchedFromExternal;
    /** 上次渲染权限列表时用的排序方式，用于在 onResume 里判断是否需要重渲染。 */
    private int permSortModeShown = -1;
    /**
     * 解析完成后要恢复显示的状态文字。
     *
     * 有它的原因：卸载成功后我们会立刻重新解析安装包来刷新界面，
     * 而解析完成时会把状态栏清空 —— 那样刚显示出来的「卸载成功」立刻就被抹掉了，
     * 用户什么也看不到。所以先把结果存这里，等渲染完成再显示。
     */
    private String pendingStatus;

    private BroadcastReceiver statusReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.details);

        // 是不是被别的应用拉起来当安装器用的？
        // 这个标记决定返回键和「完成」按钮的行为：外部调用时应该直接退出，
        // 而不是掉进我们自己的安装包列表（用户会一头雾水）。
        launchedFromExternal = getIntent().getBooleanExtra(EXTRA_FROM_EXTERNAL, false);

        iconView = (ImageView) findViewById(R.id.app_icon);
        titleView = (TextView) findViewById(R.id.app_title);
        subtitleView = (TextView) findViewById(R.id.app_subtitle);
        statusView = (TextView) findViewById(R.id.install_status);
        statusArea = findViewById(R.id.status_area);
        progressBar = (android.widget.ProgressBar) findViewById(R.id.install_progress);
        spinner = (android.widget.ProgressBar) findViewById(R.id.install_spinner);
        downgradeBox = (CheckBox) findViewById(R.id.allow_downgrade);
        installButton = (TextView) findViewById(R.id.btn_install);
        uninstallButton = (TextView) findViewById(R.id.btn_uninstall);
        list = (ListView) findViewById(R.id.info_list);

        adapter = new InfoAdapter(this);
        list.setAdapter(adapter);

        installButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startInstall();
            }
        });
        uninstallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startUninstall();
            }
        });

        apkPath = getIntent().getStringExtra(EXTRA_APK);
        if (apkPath == null) {
            // 也可能是从 ACTION_VIEW 直接进来的
            apkPath = IntentPaths.resolve(this, getIntent());
        }
        if (apkPath == null) {
            titleView.setText("没有拿到安装包路径");
            installButton.setEnabled(false);
            uninstallButton.setEnabled(false);
            return;
        }

        registerStatusReceiver();
        load(apkPath);
    }

    /**
     * 从设置页返回时，如果排序方式被改过就重新渲染一遍。
     *
     * 详情页没有直接通往设置页的入口，但用户可能按返回回到主界面、
     * 进设置改完再点回来；也可能本页一直留在返回栈里。
     * 用「进后台时记住当时的设置，回来时比对」的办法判断要不要刷新，
     * 避免每次 onResume 都白重渲染一次（重渲染会丢掉列表滚动位置）。
     */
    @Override
    protected void onResume() {
        super.onResume();
        int now = Settings.getPermSortMode(this);
        if (card != null && now != permSortModeShown) {
            CrashReporter.note("权限排序方式变化: " + permSortModeShown + " -> " + now
                    + "，重新渲染详情页");
            permSortModeShown = now;
            // 复用已经解析好的 card，只重建列表；权限表也已经在 Perms 里缓存了
            render(card, Perms.load(this));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String p = intent.getStringExtra(EXTRA_APK);
        if (p == null) {
            p = IntentPaths.resolve(this, intent);
        }
        if (p != null) {
            apkPath = p;
            hashesComputed = false;
            load(p);
        }
    }

    // ------------------------------------------------------------------
    // 加载与渲染
    // ------------------------------------------------------------------

    /**
     * 统一的「显示一条状态」方法。
     *
     * 为什么要有它：状态栏（底部那条深色区域）既用来显示加载进度，
     * 也用来显示安装进度和结果。如果各处分散地 setText，很容易出现
     * 「加载完成后文字没被清掉，一直残留」这种事（这个坑已经踩过一次）。
     * 所以：凡是写状态就走这里，传空串则自动把整条隐藏。
     *
     * 进度条也跟着一起管：busy=true 时显示那条横向动画，
     * 用来表示「正在进行一个无法量化进度的耗时操作」。
     */
    private void setStatus(String text) {
        setStatus(text, false);
    }

    private void setStatus(String text, boolean busy) {
        boolean hasText = text != null && text.length() > 0;
        if (statusView != null) {
            statusView.setText(hasText ? text : "");
        }
        if (progressBar != null) {
            progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        if (spinner != null) {
            spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        // 容器本身：有文字或有进度条时才占位置，否则整条收起
        if (statusArea != null) {
            statusArea.setVisibility((busy || hasText) ? View.VISIBLE : View.GONE);
        }
    }

    private void load(final String path) {
        adapter.clear();
        adapter.add(InfoAdapter.Item.header("解析中…"));
        adapter.commit();
        // 换了安装包，重置排序记录 —— 等这次渲染完它会记上当前值
        permSortModeShown = -1;
        titleView.setText("解析中…");
        setStatus("正在读取安装包信息…");
        CrashReporter.note("开始解析 APK: " + path);

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 完整解析：会先把 APK 复制到私有目录再读，
                // 因为很多 ROM 对「外部存储上的 apk」直接返回 null（图标和版本都会缺）
                final ApkCard c = ApkParser.parse(DetailsActivity.this, path, true);
                // 权限表加载可能很慢，单独放这里，UI 先出来
                final Map<String, Perms.Info> permTable =
                        Perms.load(DetailsActivity.this);
                CrashReporter.note("解析完成: parseOk=" + c.parseOk
                        + " fallback=" + c.usedFallbackParser
                        + " pkg=" + c.packageName
                        + " icon=" + (c.icon != null));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        card = c;
                        render(c, permTable);
                    }
                });
            }
        }, "apk-parse").start();
    }

    private void render(ApkCard c, Map<String, Perms.Info> permTable) {
        // ---- 标题区 ----
        titleView.setText(c.label != null && c.label.length() > 0 ? c.label : c.fileName);
        subtitleView.setText(c.fileName + "   " + ApkParser.humanSize(c.fileSize));

        // 是否显示「更多信息」。
        // 默认关闭：普通用户只需要基本信息 + 安装状态对比 + 权限，
        // 文件校验值、组件清单这些对他们是噪音。需要的人去设置里打开。
        final boolean showMore = Settings.isShowMoreDetails(this);

        // 图标：优先用从 APK 里解出来的真实图标；
        // 只有已安装的应用才回退到「已装版本」的图标；再不行才用占位图
        if (c.icon != null) {
            iconView.setImageBitmap(c.icon);
            // 顺手回填给列表 —— 这样用户从详情页返回后，
            // 列表里这一项也有真图标了（列表本身不做昂贵的解析）
            if (MainActivity.sAdapter != null) {
                MainActivity.sAdapter.putIcon(c.filePath, c.icon);
            }
        } else {
            Drawable icon = loadInstalledIcon(c);
            if (icon != null) {
                iconView.setImageDrawable(icon);
            } else {
                iconView.setImageResource(R.drawable.ic_apk);
            }
        }

        // ---- 组装详情列表 ----
        adapter.clear();

        if (!c.parseOk) {
            // 文件不在了，和「文件在但解析不了」是两回事，要分开说。
            //
            // 最典型的场景就是这个应用自己造成的：设置里勾了
            // 「安装成功后删除安装包」，装完就把它删了。
            // 如果这时还去渲染「无法解析 + 三种原因（损坏/非标准/ROM拒绝）」，
            // 用户根本无从判断 —— 那三条全都不对。
            File apkFile = new File(c.filePath == null ? "" : c.filePath);
            boolean fileMissing = !apkFile.exists();

            if (fileMissing) {
                adapter.add(InfoAdapter.Item.header("这个安装包已经不在了"));
                adapter.add(InfoAdapter.Item.field("文件",
                        (c.filePath == null ? "(未知)" : c.filePath) + "（不存在）"));
                adapter.add(InfoAdapter.Item.spacer());
                adapter.add(InfoAdapter.Item.field("说明",
                        "它可能已经被删除。最常见的原因是设置里勾选了"
                                + "「安装成功后删除安装包」—— 装完就把它删掉了，"
                                + "这是正常的，不是出错。",
                        0xFF757575));
                adapter.commit();
                installButton.setEnabled(false);
                installButton.setText("安装包已被删除");
                uninstallButton.setEnabled(c.packageName != null && c.installed);
                // load() 在开头写了「正在读取安装包信息…」，这条分支必须把它清掉，
                // 否则底部会一直挂着那句话，而界面上明明已经说清「文件不在了」。
                // （和以前那个「解析完成后残留灰条」是同一类问题。）
                setStatus("");
                return;
            }

            adapter.add(InfoAdapter.Item.header("⚠ 无法解析这个安装包"));
            adapter.add(InfoAdapter.Item.field("原因", c.parseError));
            adapter.add(InfoAdapter.Item.field("文件", c.filePath));
            adapter.add(InfoAdapter.Item.spacer());
            adapter.add(InfoAdapter.Item.header("可能的原因"));
            adapter.add(InfoAdapter.Item.field("1", "文件下载不完整或已损坏"));
            adapter.add(InfoAdapter.Item.field("2", "不是标准的 APK 文件"));
            adapter.add(InfoAdapter.Item.field("3", "厂商 ROM 的解析器拒绝了这个包"));
            adapter.add(InfoAdapter.Item.field("4",
                    "文件刚被删除或被移走（例如本应用按设置删掉了它）"));
            adapter.commit();
            installButton.setEnabled(false);
            uninstallButton.setEnabled(false);
            return;
        }

        // ===== 基本信息 =====
        adapter.add(InfoAdapter.Item.header("基本信息"));
        adapter.add(InfoAdapter.Item.field("应用名称", c.label));
        adapter.add(InfoAdapter.Item.field("包名", c.packageName));
        adapter.add(InfoAdapter.Item.field("版本名称", c.versionName));
        adapter.add(InfoAdapter.Item.field("版本代码", String.valueOf(c.versionCode)));
        // 系统要求：优先把 Android 版本名摆出来（比裸 API 号直观得多）
        adapter.add(InfoAdapter.Item.field("最低系统", ApkParser.describeApi(c.minSdk)));
        adapter.add(InfoAdapter.Item.field("目标系统", ApkParser.describeApi(c.targetSdk)));
        adapter.add(InfoAdapter.Item.field("安装位置", c.installLocation));
        adapter.add(InfoAdapter.Item.field("文件大小", ApkParser.humanSize(c.fileSize)
                + "（" + c.fileSize + " 字节）"));
        adapter.add(InfoAdapter.Item.field("CPU 架构", c.nativeAbis.isEmpty()
                ? "不含原生库（纯 Java，任意机型可用）"
                : join(c.nativeAbis)));
        // 明确标出这个包来自哪个存储卷，避免和同名的另一个位置搞混
        adapter.add(InfoAdapter.Item.field("存储位置", c.fromSdCard
                ? "SD 卡" + (c.storageRoot == null ? "" : "（" + c.storageRoot + "）")
                : "内部存储" + (c.storageRoot == null ? "" : "（" + c.storageRoot + "）"),
                c.fromSdCard ? 0xFFF9A825 : 0xFF212121));

        // ===== 与已安装版本对比 =====
        adapter.add(InfoAdapter.Item.spacer());
        adapter.add(InfoAdapter.Item.header("安装状态对比"));
        if (!c.installed) {
            adapter.add(InfoAdapter.Item.field("状态", "未安装过 · 全新安装", 0xFF2E7D32));
            adapter.add(InfoAdapter.Item.field("说明",
                    "设备上没有同名应用，安装不会覆盖任何数据。"));
        } else {
            adapter.add(InfoAdapter.Item.field("设备上已装版本",
                    c.installedVersionName + " (" + c.installedVersionCode + ")"));
            if (c.isDowngrade) {
                adapter.add(InfoAdapter.Item.field("状态",
                        "降级安装（新版本号更低）", 0xFFC62828));
                adapter.add(InfoAdapter.Item.field("说明",
                        "系统默认拒绝降级。需要勾选下方「允许降级」才能装。"));
            } else if (c.isSameVersion) {
                adapter.add(InfoAdapter.Item.field("状态", "同版本覆盖安装", 0xFF1565C0));
                adapter.add(InfoAdapter.Item.field("说明",
                        "版本号相同，会覆盖安装并保留原有数据。"));
            } else {
                adapter.add(InfoAdapter.Item.field("状态", "升级安装", 0xFF2E7D32));
                adapter.add(InfoAdapter.Item.field("说明",
                        "版本号更高，会覆盖安装并保留原有数据。"));
            }

            // 系统要求有没有变。
            //
            // 放在「版本号」和「签名」之间：它和「这次更新会带来什么变化」是同一类信息。
            // 最要紧的是 minSdk 被抬高 —— 用户点「更新」时不会想到
            // 「更新完我另一台老机器就装不上了」，而这件事不报错、不提示。
            // 只念「提高了」而不说后果，等于把判断的活又丢回给用户。
            appendSdkChange(c);

            // 签名对比：这是判断「能不能覆盖装上去」的关键。
            //
            // 分工刻意分成两层：
            //   · 结论（一致/不一致）留在「安装状态对比」里 ——
            //     它是可操作的判断，而且安装拦截也依赖它，不能藏。
            //   · 具体的 SHA1 摘要放进「更多信息」——
            //     那只是支撑结论的技术细节，普通用户不需要看到一长串哈希。
            String inst = c.installedSignerSha1;
            String mine = c.archiveSignerSha1;
            boolean signerKnown = inst != null && mine != null;

            if (signerKnown) {
                boolean same = inst.equalsIgnoreCase(mine);
                adapter.add(InfoAdapter.Item.field("签名一致性",
                        same ? "一致 · 可以正常覆盖安装" : "不一致 · 覆盖安装会失败！",
                        same ? 0xFF2E7D32 : 0xFFC62828));
                if (!same) {
                    adapter.add(InfoAdapter.Item.field("解决办法",
                            "两者签名不同，系统会拒绝覆盖。必须先在设置里卸载旧版本"
                                    + "（会丢失该应用数据），或者拿到与原版同一签名的包。",
                            0xFFC62828));
                }
            } else if (inst != null) {
                // 已装版本有签名、但本次 APK 取不到。
                //
                // 到这里要先把「未签名」和「有签名但读不出证书」分开 ——
                // 这两种情况的后果完全不同，混成一句「可能是未签名的包」
                // 会把一个正常的包冤枉掉。
                if (c.signatureStatus == ApkSignatures.STATUS_UNSIGNED) {
                    adapter.add(InfoAdapter.Item.field("签名一致性",
                            "这个 APK 没有任何签名。正式系统会拒绝安装它。",
                            0xFFC62828));
                } else {
                    adapter.add(InfoAdapter.Item.field("签名一致性",
                            "这个包有签名，但没能读出签名者证书，所以无法判断是否一致。",
                            0xFFB26A00));
                }
            } else {
                adapter.add(InfoAdapter.Item.field("签名一致性",
                        "无法读取已装版本的签名，这次无法判断是否一致。",
                        0xFF757575));
            }
        }

        // ===== 签名状态（与「有没有装」无关）=====
        //
        // 这一段对**每个包**都要出，因为签名是 APK 文件自身的属性。
        // 四种必须分清的形态：
        //   ① 有 v1，也有 v2/v3  → 正常，老系统新系统都能装
        //   ② 只有 v1            → 正常，但新系统上少一层保护（本机是 4.3，能装）
        //   ③ 只有 v2/v3，没有 v1 → 包本身正常，但 Android 7.0 以前装不上（含本机）
        //   ④ 完全没签名          → 包有问题，任何系统都装不上
        // 把 ③ 和 ④ 混为一谈是最容易犯、也最伤用户的错 —— 前者是正常的包。
        appendSignatureStatus(c);

        // ===== 与已装版本的权限差异 =====
        //
        // 放在「安装状态对比」之后、"文件校验"之前：升级时这是用户最该看到的一段。
        // 应用常在更新时顺手多要权限（权限扩张），而用户点「更新」基本不会重看权限列表。
        // 原生安装器会单独提示，这个应用更该做 —— 毕竟卖点就是「把权限讲清楚」。
        if (c.installed) {
            adapter.add(InfoAdapter.Item.spacer());
            adapter.add(InfoAdapter.Item.header("和已装版本的权限差异"));

            if (!c.installedPermissionsKnown) {
                // 拿不到已装版本的权限信息时如实说明，不能谎报「没有变化」
                adapter.add(InfoAdapter.Item.field("无法比对",
                        "系统没有返回已装版本的权限列表，所以这次无法判断"
                                + "权限有没有变化。", 0xFF757575));
            } else if (c.addedPermissions.isEmpty() && c.removedPermissions.isEmpty()) {
                adapter.add(InfoAdapter.Item.field("没有变化",
                        "本次版本申请的权限与已装版本完全一致。", 0xFF2E7D32));
            } else {
                if (!c.addedPermissions.isEmpty()) {
                    adapter.add(InfoAdapter.Item.field("⚠ 新增了 "
                                    + c.addedPermissions.size() + " 项需要关注的权限",
                            "这些是已装版本没有、本次新要的权限（危险级或签名级）。"
                                    + "更新应用时最该留意这里。",
                            0xFFC62828));
                    // 按权限组归类展示 —— 系统自带安装器就是这么排的：
                    // 组说明当标题（例如「查找设备上的账户」），组内权限列在下面。
                    appendGroupedAddedPermissions(c, permTable);
                    // 普通级权限不逐条列，但如实交代数量 ——
                    // 它们由系统自动授予、用户无从拒绝，一条条摆出来只会淹没重点。
                    int normalNew = c.addedPermissionsNormal;
                    if (normalNew > 0) {
                        adapter.add(InfoAdapter.Item.field("另有 "
                                        + normalNew + " 项普通权限",
                                "这些权限系统会自动授予，你无法拒绝，所以不逐条列出。"
                                        + "（本次新增权限总计 " + c.addedPermissionsTotal
                                        + " 项）",
                                0xFF757575));
                    }
                } else if (c.addedPermissionsTotal > 0) {
                    // 极少数情况：新增的全是普通级权限
                    adapter.add(InfoAdapter.Item.field("新增的权限都不敏感",
                            "本次新增了 " + c.addedPermissionsTotal
                                    + " 项权限，但全属普通级（系统自动授予），"
                                    + "没有危险级或签名级权限。",
                            0xFF2E7D32));
                }
                if (!c.removedPermissions.isEmpty()) {
                    adapter.add(InfoAdapter.Item.field("移除了 "
                                    + c.removedPermissions.size() + " 项权限",
                            "本次不再申请这些权限（相对更安全）。", 0xFF2E7D32));
                    for (int i = 0; i < c.removedPermissions.size(); i++) {
                        adapter.add(InfoAdapter.Item.field("−",
                                Perms.shortName(c.removedPermissions.get(i)),
                                0xFF2E7D32));
                    }
                }
            }
        }

        // ===== 文件校验（属于「更多信息」，由设置控制）=====
        if (showMore) {
            adapter.add(InfoAdapter.Item.spacer());
            adapter.add(InfoAdapter.Item.header("文件校验"));
            adapter.add(InfoAdapter.Item.field("文件路径", c.filePath));
            adapter.add(InfoAdapter.Item.field("修改时间", new java.util.Date(c.fileModified)
                    .toString()));
            adapter.add(InfoAdapter.Item.action("MD5", "点击计算", new Runnable() {
                @Override
                public void run() {
                    computeHashes(true);
                }
            }));
            adapter.add(InfoAdapter.Item.action("SHA1", "点击计算", new Runnable() {
                @Override
                public void run() {
                    computeHashes(true);
                }
            }));
            // 签名摘要放在这里（属于「更多信息」）。
            //
            // 结论已经在上面「签名状态」里给了，这里只留技术细节。
            // 注意措辞：取不到摘要时**不能**再说「可能是未签名的包」——
            // 到底有没有签名，上面的签名状态已经判定了，这里只是没读出证书而已。
            adapter.add(InfoAdapter.Item.field("本次 APK 签名(SHA1)",
                    c.archiveSignerSha1 != null ? c.archiveSignerSha1 : "未读出签名者证书",
                    c.archiveSignerSha1 != null ? 0xFF212121 : 0xFF757575));
            // 已装版本的签名同样归到「更多信息」—— 它只是支撑结论的技术细节
            if (c.installed) {
                adapter.add(InfoAdapter.Item.field("已装版本签名(SHA1)",
                        c.installedSignerSha1 != null ? c.installedSignerSha1
                                : "未读出签名者证书",
                        c.installedSignerSha1 != null ? 0xFF212121 : 0xFF757575));
            }
        }

        // ===== 权限（本应用的重点，始终显示）=====
        adapter.add(InfoAdapter.Item.spacer());
        if (c.permissions.isEmpty()) {
            adapter.add(InfoAdapter.Item.header("申请的权限（共 0 项）"));
            adapter.add(InfoAdapter.Item.field("—", "这个应用没有申请任何权限"));
        } else {
            // ===== 首要位置：「这个应用能做什么」的人话总结 =====
            //
            // 放在权限明细之前，因为普通用户看不懂 "android.permission.READ_SMS"
            // 这种字符串，但看得懂「读取你的短信」。明细仍然保留在下面，
            // 给想深究的人看。
            PermAbilities.Summary summary = PermAbilities.summarize(c, permTable);
            boolean hasRiskSection = summary.hasAbilities() || summary.dangerousCount > 0;

            // 关键：**「申请的权限」这个标题只在真有风险结论时才加**。
            //
            // 踩过的坑：早先它是无条件加的，于是在「零隐私风险」的包上，
            // 它下面紧跟着第二个标题「逐条权限明细」，中间空无一物 ——
            // 看起来就像第一段被折叠了、内容没显示出来（用户就是这么报的）。
            // 两个嵌套标题、第一个还是空的，纯属误导。
            //
            // 现在的结构只在两种形态之间切换，各自都只有一个标题：
            //   有风险结论：申请的权限 → 风险/简单说/能力清单 → 逐条权限明细 → 明细
            //   无风险结论：逐条权限明细 → 明细
            if (hasRiskSection) {
                adapter.add(InfoAdapter.Item.header("申请的权限（共 "
                        + c.permissions.size() + " 项）"));
                adapter.add(InfoAdapter.Item.field("⚠ 权限风险",
                        PermAbilities.riskLabel(summary.riskLevel)
                                + "（" + summary.dangerousCount + " 项敏感权限）",
                        PermAbilities.riskColor(summary.riskLevel)));
                adapter.add(InfoAdapter.Item.field("简单说", summary.headline,
                        0xFF212121));

                // 免责说明放在这里 —— 「简单说」之后、具体能力清单之前。
                //
                // 位置很讲究，两个方向都不理想：
                //   · 放最末尾：用户读完「这个应用可以拦截并读取你的短信（包括银行验证码）」
                //     这种带强烈事实语气的句子之后才看到「其实只是推断」，
                //     很可能已经认定这是恶意软件了 —— 等于替一个无辜应用扣帽子。
                //   · 放最开头：会破坏「一句话结论」的节奏，削弱警示力。
                //
                // 放在「被约束内容的紧前面」才是对的：它的作用不是给自己免责，
                // 而是限定**紧随其后那批具体指控**的性质。
                adapter.add(InfoAdapter.Item.field("说明",
                        "以下是「根据它申请的权限」推断出来的可能能力，"
                                + "不代表它真的这么做过。",
                        0xFF757575));

                // 逐条列出推断出的能力，这样用户能核对结论是怎么来的
                for (int i = 0; i < summary.abilities.size(); i++) {
                    PermAbilities.Ability a = summary.abilities.get(i);
                    adapter.add(InfoAdapter.Item.field("·",
                            PermAbilities.describe(a, summary.hasNetwork),
                            PermAbilities.riskColor(a.severity)));
                }
                if (summary.hasNetwork && summary.hasAbilities()) {
                    adapter.add(InfoAdapter.Item.field("特别注意",
                            "这个应用还能联网，所以上面读到的数据都有可能被传出去。",
                            0xFFC62828));
                } else if (!summary.hasNetwork && summary.hasAbilities()) {
                    adapter.add(InfoAdapter.Item.field("相对好一点",
                            "这个应用不能联网，读到的东西传不出设备。"));
                }
            }

            adapter.add(InfoAdapter.Item.spacer());
            adapter.add(InfoAdapter.Item.header("逐条权限明细（共 "
                    + c.permissions.size() + " 项）"));

            // 排序方式由设置决定，见 SettingsActivity.showPermSortDialog()
            int sortMode = Settings.getPermSortMode(this);
            permSortModeShown = sortMode; // 记下来，供 onResume 比对
            List<String> ordered = PermSorter.sort(c.permissions, permTable, sortMode);
            for (int i = 0; i < ordered.size(); i++) {
                adapter.add(permissionItem(permTable, ordered.get(i)));
            }
            // 加一行小字说明当前排序，免得用户以为顺序是乱排的
            adapter.add(InfoAdapter.Item.field("排序方式",
                    Settings.PERM_SORT_NAMES[sortMode]));
        }

        // ===== 组件（属于「更多信息」，由设置控制）=====
        if (showMore) {
            adapter.add(InfoAdapter.Item.spacer());
            adapter.add(InfoAdapter.Item.header("包含的组件"));
            adapter.add(InfoAdapter.Item.field("Activity", String.valueOf(c.activityCount)
                    + (c.activityCountExported > 0
                    ? "（" + c.activityCountExported + " 个对外暴露）" : "")));
            adapter.add(InfoAdapter.Item.field("Service", String.valueOf(c.serviceCount)
                    + (c.serviceCountExported > 0
                    ? "（" + c.serviceCountExported + " 个对外暴露）" : "")));
            adapter.add(InfoAdapter.Item.field("Receiver", String.valueOf(c.receiverCount)
                    + (c.receiverCountExported > 0
                    ? "（" + c.receiverCountExported + " 个对外暴露）" : "")));
            adapter.add(InfoAdapter.Item.field("Provider", String.valueOf(c.providerCount)));

            if (!c.exportedComponents.isEmpty()) {
                adapter.add(InfoAdapter.Item.spacer());
                adapter.add(InfoAdapter.Item.header("对外暴露的组件（其它应用可调用）"));
                for (int i = 0; i < c.exportedComponents.size(); i++) {
                    adapter.add(InfoAdapter.Item.field("·", c.exportedComponents.get(i)));
                }
            }
        }

        adapter.commit();

        // 渲染完成后打一行诊断，用来确认各区块确实被加进去了。
        //
        // 这一行是**有意保留**的（不是调试残留）：目标 ROM 上无法用
        // `input swipe` 滚动，`uiautomator dump` 又只包含可见行，
        // 所以「详情页到底渲染了多少行」只能靠日志确认。
        // 排查布局、区块缺失这类问题时很省事。
        // 想看的话：adb logcat -s RootInstaller:* | findstr 详情页渲染完成
        Log.i("详情页渲染完成 共 " + adapter.getCount() + " 行"
                + " | 已装=" + c.installed
                + " 权限差异已知=" + c.installedPermissionsKnown
                + " 新增=" + c.addedPermissions.size()
                + " 移除=" + c.removedPermissions.size()
                + " 风险等级=" + c.riskLevel);

        // ---- 按钮状态 ----
        installButton.setEnabled(!installing);
        // 没装过就干脆不显示卸载按钮，「安装」自动铺满整行
        uninstallButton.setVisibility(c.installed ? View.VISIBLE : View.GONE);
        uninstallButton.setEnabled(c.installed && !installing);
        downgradeBox.setVisibility(c.isDowngrade ? View.VISIBLE : View.GONE);
        if (c.isDowngrade) {
            downgradeBox.setChecked(true); // 默认就勾上，省得用户点了报错再回来勾
        }

        // 解析走了兜底方案就提醒一下；正常解析成功则清空状态栏。
        //
        // 这里必须有 else 分支：早先只写了 if，结果 load() 里那句
        // 「正在读取安装包信息…」在解析完成后一直残留在界面底部（连同灰色背景），
        // 看起来像还在加载。现在只要正常渲染完成就会把它清掉。
        //
        // 但如果有 pendingStatus（例如刚卸载完），就优先显示它，
        // 否则用户会看不到「卸载成功」这条反馈。
        if (pendingStatus != null && pendingStatus.length() > 0) {
            setStatus(pendingStatus);
            pendingStatus = null;
        } else if (c.usedFallbackParser) {
            setStatus("提示：这个包用常规方式解析失败，当前信息来自兜底解析，"
                    + "可能不完整。仍然可以尝试安装。");
        } else {
            setStatus("");
        }
    }

    /**
     * 输出「签名状态」这一段。对每个包都出，与是否已安装无关。
     *
     * <p>这一段的唯一职责是**不要把不同的情况说成同一件事**：
     * 「未签名」是包的问题，「只有 v2/v3、没有 v1」是包正常但老系统装不上。
     * 后者在只认 v1 的 Android 4.3 上必然安装失败，是本机用户最需要提前知道的一条。
     */
    private void appendSignatureStatus(ApkCard c) {
        adapter.add(InfoAdapter.Item.spacer());
        adapter.add(InfoAdapter.Item.header("签名状态"));

        if (c.signatureStatus == ApkSignatures.STATUS_UNKNOWN) {
            adapter.add(InfoAdapter.Item.field("无法判断",
                    "没能读取这个包的签名信息"
                            + (c.signatureNote != null ? "（" + c.signatureNote + "）" : "")
                            + "。这不等于没有签名。",
                    0xFF757575));
            return;
        }

        if (c.signatureStatus == ApkSignatures.STATUS_UNSIGNED) {
            adapter.add(InfoAdapter.Item.field("未签名",
                    "这个 APK 没有任何签名。Android 会拒绝安装未签名的包，"
                            + "任何系统都一样。",
                    0xFFC62828));
            return;
        }

        // ---- 签名方案 ----
        StringBuilder schemes = new StringBuilder();
        if (c.sigV1) {
            schemes.append("v1");
        }
        if (c.sigV2) {
            appendScheme(schemes, "v2");
        }
        if (c.sigV3) {
            appendScheme(schemes, "v3");
        }
        if (c.sigV4) {
            appendScheme(schemes, "v4");
        }
        adapter.add(InfoAdapter.Item.field("签名方案", schemes.toString()));

        // ---- 签发者 ----
        if (c.archiveSignerSha1 != null) {
            adapter.add(InfoAdapter.Item.field("签发者证书(SHA1)", c.archiveSignerSha1));
        }

        // ---- 完整性（只对 v1 有摘要链可查）----
        if (c.signatureIntegrity == ApkSignatures.INTEGRITY_BAD) {
            adapter.add(InfoAdapter.Item.field("⚠ 签名验证不通过",
                    "这个包被改动过，改动之后没有重新签名。"
                            + (c.signatureIntegrityDetail != null
                            ? "（" + c.signatureIntegrityDetail + "）" : "")
                            + " 包里的内容已经不能证明来自原作者，建议不要安装。",
                    0xFFC62828));
            return;
        } else if (c.signatureIntegrity == ApkSignatures.INTEGRITY_OK) {
            adapter.add(InfoAdapter.Item.field("签名验证", "通过 —— 包的内容和签名时一致",
                    0xFF2E7D32));
        }

        // ---- 兼容性：没有 v1 / 没有 v2 各自会在哪一端装不上 ----
        //
        // 两端各有一条硬规则，方向相反，必须分开说：
        //   · 没有 v1 → Android 7.0（API 24）以前只认 v1，老系统装不上。
        //   · 只有 v1 且 targetSdk ≥ 30 → Android 11+ 直接拒装，报 NO_SIGNATURE。
        // 前者在 4.3 上是「本机装不上」，后者在新机型上是「那边装不上」，
        // 说反了或漏掉一条，用户都会拿到一个无法解释的安装失败。
        int sdk = Build.VERSION.SDK_INT;
        int effectiveTargetSdk = SigCompat.effectiveTargetSdk(c.targetSdk, c.installedTargetSdk);
        boolean needsV2 = SigCompat.requiresV2OrHigher(effectiveTargetSdk);
        boolean hasV2Plus = c.sigV2 || c.sigV3;

        if (!c.sigV1) {
            if (sdk < SigCompat.API_V2_INTRODUCED) {
                adapter.add(InfoAdapter.Item.field("⚠ 本机装不上",
                        "这个包只用了 v2/v3 签名，没有 v1（Jar）签名。"
                                + "Android 7.0（API 24）以前只认 v1，"
                                + "所以在本机（Android " + sdk + "）上安装一定会失败。"
                                + "包本身是正常的，需要找带 v1 签名的版本。",
                        0xFFC62828));
            } else {
                adapter.add(InfoAdapter.Item.field("兼容性说明",
                        "没有 v1 签名。从 Android 7.0 起这没有问题，"
                                + "但在 7.0 以前的设备上装不上。",
                        0xFF757575));
            }
        } else if (!hasV2Plus && needsV2) {
            // 目标 API ≥ 30 却只做 v1 签名 —— 这是打包配置不对，
            // Android 11+ 会以「找不到 v2 及以上签名」直接拒绝安装。
            //
            // 注意本机在这一条上是**能装**的（本机是旧系统），所以下面
            // 只提示、不拦截 —— 拦了会把用户明明装得上的包挡在门外。
            boolean hereAlsoAffected = sdk >= SigCompat.API_V2_REQUIRED_FOR_NEW_TARGET;
            adapter.add(InfoAdapter.Item.field("⚠ 新机型装不上",
                    "这个包只有 v1（Jar）签名，但它的 targetSdk 是 "
                            + SigCompat.describeApi(effectiveTargetSdk) + "。"
                            + "从 Android 11（API 30）起，targetSdk ≥ 30 的应用"
                            + "必须带 v2 或更高版本的签名，否则会被直接拒绝安装"
                            + "（报 NO_SIGNATURE）。\n"
                            + (hereAlsoAffected
                            ? "本机（Android " + sdk + "）同样在这个范围里，安装一定会失败。"
                            : "本机是 Android " + sdk + "，不受这条限制，能装；")
                            + "但这个包在 Android 11 及以后的机型上都装不上，"
                            + "属于打包配置问题，建议让开发者补上 v2 签名后重新打包。",
                    0xFFC62828));
        } else if (!hasV2Plus) {
            adapter.add(InfoAdapter.Item.field("兼容性说明",
                    "只有 v1（Jar）签名。所有版本的 Android 都认它，"
                            + "但缺少 v2/v3 意味着整包完整性少一层保护"
                            + "（v2/v3 对整包做摘要，v1 是逐文件摘要，"
                            + "存在被塞入文件的可能）。"
                            + "对 targetSdk " + effectiveTargetSdk + " 来说还能装；"
                            + "一旦 targetSdk 提到 30 及以上就会被 Android 11+ 拒装。",
                    0xFF757575));
        }

        // ---- 额外发现：清单里的版本号与实际解析的不一致 ----
        //
        // 重新打包必然重写 MANIFEST.MF，而改包的人极少会同步这个值，
        // 于是二者矛盾就是「包被动过」的直接证据。
        if (c.manifestVersionCode != null && c.versionCode >= 0
                && !c.manifestVersionCode.equals(String.valueOf(c.versionCode))) {
            adapter.add(InfoAdapter.Item.field("⚠ 版本号对不上",
                    "签名清单里记录的版本号是 " + c.manifestVersionCode
                            + "，而包里清单实际是 " + c.versionCode + "。"
                            + "通常说明这个包在签名之后被改动过。",
                    0xFFC62828));
        }
    }

    private static void appendScheme(StringBuilder sb, String scheme) {
        if (sb.length() > 0) {
            sb.append(" + ");
        }
        sb.append(scheme);
    }

    /** 把变化方向说成日志里看得懂的字。 */
    private static String directionText(int dir) {
        switch (dir) {
            case SdkChange.RAISED:
                return "提高";
            case SdkChange.LOWERED:
                return "降低";
            case SdkChange.SAME:
                return "不变";
            default:
                return "未知";
        }
    }

    /**
     * 输出「系统要求有没有变」这一段（仅已安装时调用）。
     *
     * <p>两种变化的语气刻意不同：
     * <ul>
     *   <li><b>minSdk 抬高</b>：这是会让人吃亏的一件事（老设备从此升级不了），
     *       所以标红并把后果说清楚，而不是只说「提高了」。</li>
     *   <li><b>targetSdk 变化</b>：属于「适配到哪个 Android 版本」，
     *       正常更新里很常见，用中性色说明即可，不渲染成风险。</li>
     * </ul>
     * 没有任何变化时**什么都不显示** —— 没变化不值得占一行。
     */
    private void appendSdkChange(ApkCard c) {
        SdkChange.Result ch = SdkChange.compare(
                c.installedMinSdk, c.installedTargetSdk, c.minSdk, c.targetSdk);

        // 一行诊断，和「详情页渲染完成」同样的用途。
        //
        // 这段判断在真机上没法目视核对：界面列表一次只显示几行，
        // uiautomator 只导可见行，而 dumpsys 的视图树在部分 ROM 上又不含文本。
        // 尤其是「已装版本的 minSdk」只能反射读（API 24 才是公开字段），
        // 反射失败会静默返回 -1、整段不显示 —— 那种情况下光看界面
        // 只会觉得「没提示」，根本分不清是「没变化」还是「没读到」。
        Log.i("系统要求变化 " + c.packageName
                + " | 已装 minSdk=" + c.installedMinSdk + " targetSdk=" + c.installedTargetSdk
                + " | 本次 minSdk=" + c.minSdk + " targetSdk=" + c.targetSdk
                + " | minSdk方向=" + directionText(ch.minSdkDirection)
                + " | targetSdk方向=" + directionText(ch.targetSdkDirection)
                + " | 是否展示=" + ch.anyChange());

        if (!ch.anyChange()) {
            return;
        }
        // 抬高 minSdk 用红色（要留意），其余用中性色
        int raisedColor = 0xFFC62828;
        int normalColor = 0xFF757575;

        if (ch.minSdkRaised()) {
            adapter.add(InfoAdapter.Item.field("⚠ 最低系统要求提高了",
                    "从 " + ApkParser.describeApi(ch.oldMinSdk) + " 提高到 "
                            + ApkParser.describeApi(ch.newMinSdk) + "。"
                            + "比 " + ApiNames.shortName(ch.newMinSdk)
                            + " 更老的设备从此装不上这个新版本了"
                            + "（已装在老设备上的旧版本不受影响，但升不了级）。",
                    raisedColor));
        } else if (ch.minSdkDirection == SdkChange.LOWERED) {
            adapter.add(InfoAdapter.Item.field("最低系统要求降低了",
                    "从 " + ApkParser.describeApi(ch.oldMinSdk) + " 降到 "
                            + ApkParser.describeApi(ch.newMinSdk)
                            + "。支持范围变宽了，是好事。",
                    0xFF2E7D32));
        }

        if (ch.targetSdkDirection == SdkChange.RAISED) {
            adapter.add(InfoAdapter.Item.field("目标系统提高了",
                    "从 " + ApkParser.describeApi(ch.oldTargetSdk) + " 提高到 "
                            + ApkParser.describeApi(ch.newTargetSdk)
                            + "。一般说明这个版本适配了更新的 Android 行为规范。",
                    normalColor));
        } else if (ch.targetSdkDirection == SdkChange.LOWERED) {
            adapter.add(InfoAdapter.Item.field("目标系统降低了",
                    "从 " + ApkParser.describeApi(ch.oldTargetSdk) + " 降到 "
                            + ApkParser.describeApi(ch.newTargetSdk)
                            + "。正常更新里很少见，可以留意一下。",
                    normalColor));
        }
    }

    /**
     * 按权限组把「新增的需关注权限」归类输出。
     *
     * 为什么要分组：这是从系统自带安装器的排版学来的 —— 它用**权限组说明**
     * 当小标题（例如「查找设备上的账户」），把组内权限列在下面。
     * 平铺一长串权限名（AUTHENTICATE_ACCOUNTS、USE_CREDENTIALS…）
     * 对普通用户没有任何意义，而归到「账户」这个主题下就容易理解了。
     *
     * 同一组内的权限排在一起，组之间按「首项出现的顺序」排列，
     * 保证顺序稳定、可预期。
     */
    private void appendGroupedAddedPermissions(ApkCard c,
                                               Map<String, Perms.Info> permTable) {
        // 按组归并，同时保持组的出现顺序
        java.util.LinkedHashMap<String, java.util.List<String>> byGroup =
                new java.util.LinkedHashMap<String, java.util.List<String>>();
        java.util.List<String> noGroup = new java.util.ArrayList<String>();

        for (int i = 0; i < c.addedPermissions.size(); i++) {
            String p = c.addedPermissions.get(i);
            Perms.Info info = Perms.lookup(permTable, p);
            String groupDesc = info == null ? null : info.groupDescription;
            if (groupDesc == null || groupDesc.length() == 0) {
                noGroup.add(p);
                continue;
            }
            java.util.List<String> bucket = byGroup.get(groupDesc);
            if (bucket == null) {
                bucket = new java.util.ArrayList<String>();
                byGroup.put(groupDesc, bucket);
            }
            bucket.add(p);
        }

        // 有组的：先出一条组说明，再列组内权限
        java.util.Iterator<java.util.Map.Entry<String, java.util.List<String>>> it =
                byGroup.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, java.util.List<String>> e = it.next();
            adapter.add(InfoAdapter.Item.field("· " + e.getKey(),
                    "（这类权限共 " + e.getValue().size() + " 项）", 0xFFC62828));
            java.util.List<String> perms = e.getValue();
            for (int i = 0; i < perms.size(); i++) {
                adapter.add(InfoAdapter.Item.field("    ",
                        describeAddedPermission(permTable, perms.get(i)), 0xFFC62828));
            }
        }

        // 没有组信息的（应用自定义权限、或 ROM 权限表不全）：退回平铺
        for (int i = 0; i < noGroup.size(); i++) {
            adapter.add(InfoAdapter.Item.field("+",
                    describeAddedPermission(permTable, noGroup.get(i)), 0xFFC62828));
        }
    }

    /** 一条新增权限的展示文案：中文说明 + 权限全名。 */
    private String describeAddedPermission(Map<String, Perms.Info> permTable, String p) {
        Perms.Info info = Perms.lookup(permTable, p);
        StringBuilder sb = new StringBuilder();
        if (info != null && info.description != null && info.description.length() > 0) {
            sb.append(info.description).append('\n');
        } else if (info != null && info.label != null && info.label.length() > 0) {
            sb.append(info.label).append('\n');
        }
        sb.append(p);
        return sb.toString();
    }

    private InfoAdapter.Item permissionItem(Map<String, Perms.Info> table, String perm) {
        Perms.Info info = Perms.lookup(table, perm);
        String label = Perms.shortName(perm);
        StringBuilder sb = new StringBuilder();
        if (info != null) {
            if (info.description != null && info.description.length() > 0) {
                sb.append(info.description);
            } else if (info.label != null && info.label.length() > 0) {
                sb.append(info.label);
            }
            if (info.protection != null) {
                sb.append("\n[").append(info.protection).append("]");
            }
        } else {
            sb.append("（内置表中没有说明，请谨慎判断）");
        }
        sb.append("\n").append(perm);

        int color = 0xFF212121;
        if (info != null) {
            if (info.dangerous) {
                color = 0xFFC62828;
            } else if (info.protection != null && info.protection.contains("签名")) {
                color = 0xFFEF6C00;
            } else {
                color = 0xFF2E7D32;
            }
        }
        return InfoAdapter.Item.field(label, sb.toString(), color);
    }

    private Drawable loadInstalledIcon(ApkCard c) {
        if (c.packageName == null || !c.installed) {
            return null;
        }
        try {
            return getPackageManager().getApplicationIcon(c.packageName);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }


    // ------------------------------------------------------------------
    // 按需计算的哈希
    // ------------------------------------------------------------------

    private void computeHashes(final boolean refresh) {
        if (hashesComputed) {
            return;
        }
        hashesComputed = true;
        setStatus("正在计算文件校验值…（大文件可能要十几秒）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String md5 = ApkParser.md5OfFile(apkPath);
                final String sha1 = ApkParser.sha1OfFile(apkPath);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (md5 == null && sha1 == null) {
                            setStatus("读取文件失败，无法计算校验值。");
                            return;
                        }
                        // 就地更新对应行，不重建整个列表
                        adapter.updateValue("MD5", md5);
                        adapter.updateValue("SHA1", sha1);
                        adapter.commit();
                        setStatus("校验值已计算完成。");
                    }
                });
            }
        }, "hash").start();
    }

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    private void startInstall() {
        if (card == null || apkPath == null || installing) {
            return;
        }
        // 降级且用户没勾选时先提醒
        if (card.isDowngrade && !downgradeBox.isChecked()) {
            setStatus("这是降级安装，请先勾选「允许降级安装」再点安装。");
            return;
        }
        if (card.installed && card.archiveSignerSha1 != null
                && card.installedSignerSha1 != null
                && !card.archiveSignerSha1.equalsIgnoreCase(card.installedSignerSha1)) {
            // 已经明确知道签名不一致，提前拦一下，别让用户白等
            setStatus("签名和已装版本不一致，直接安装会被系统拒绝。\n"
                    + "请先卸载旧版本（会清除该应用数据）再安装。");
            return;
        }

        // 完整性不通过：包被改动过，安装必然失败（系统会校验签名）。
        // 提前说清楚原因，比让用户等一趟 pm install 再看到一个含糊的报错好。
        if (card.signatureIntegrity == ApkSignatures.INTEGRITY_BAD) {
            setStatus("这个包的签名验证不通过——内容在签名之后被改动过，"
                    + "安装会被系统拒绝。\n"
                    + (card.signatureIntegrityDetail != null
                    ? "原因：" + card.signatureIntegrityDetail : "")
                    + "\n建议换一个来源可靠的安装包。");
            return;
        }

        // 签名方案层面的「装不上」：两条方向相反的硬规则统一交给 SigCompat 判，
        // 这里只负责把结论翻成人话。判定放到纯逻辑类里，是为了能在 PC 上
        // 把每种组合都跑一遍 —— 这两条写反过一次，代价是用户拿到
        // 一个无法解释的安装失败。
        if (card.signatureStatus == ApkSignatures.STATUS_SIGNED) {
            boolean hasV2Plus = card.sigV2 || card.sigV3;
            int effTarget = SigCompat.effectiveTargetSdk(card.targetSdk, card.installedTargetSdk);
            int blocker = SigCompat.installBlocker(Build.VERSION.SDK_INT,
                    card.sigV1, hasV2Plus, effTarget);
            if (blocker == SigCompat.BLOCK_MISSING_V1_ON_OLD_OS) {
                setStatus("这个包只用了 v2/v3 签名，没有 v1（Jar）签名。\n"
                        + "本机是 Android " + Build.VERSION.SDK_INT + "，只认 v1，"
                        + "安装一定会失败。\n包本身是正常的，请找带 v1 签名的版本。");
                return;
            }
            if (blocker == SigCompat.BLOCK_MISSING_V2_FOR_NEW_TARGET) {
                setStatus("这个包只有 v1（Jar）签名，但 targetSdk 是 " + effTarget
                        + "。\n从 Android 11（API 30）起，targetSdk ≥ 30 的应用"
                        + "必须带 v2 或更高版本的签名，本机（Android "
                        + Build.VERSION.SDK_INT + "）会直接拒绝安装。\n"
                        + "建议让开发者重新签名后再装。");
                return;
            }
        }

        // 完全没有签名：任何系统都装不上
        if (card.signatureStatus == ApkSignatures.STATUS_UNSIGNED) {
            setStatus("这个包没有任何签名，Android 会拒绝安装。");
            return;
        }

        installing = true;
        installButton.setEnabled(false);
        uninstallButton.setEnabled(false);
        installButton.setText("正在安装…");
        setStatus("已提交安装任务，等待 root 授权…\n"
                + "如果弹出授权对话框，请点「允许」。");
        CrashReporter.note("用户点击安装: pkg=" + card.packageName
                + " versionCode=" + card.versionCode
                + " 降级允许=" + downgradeBox.isChecked()
                + " 已安装=" + card.installed);
        InstallService.startInstall(this, apkPath, downgradeBox.isChecked());
    }

    private void startUninstall() {
        if (card == null || card.packageName == null || installing) {
            return;
        }

        // 判断这是不是系统应用。
        // 本应用只能看到「本地有 apk 的包」，正常情况下碰不到系统应用；
        // 但如果用户手里留着系统应用的 apk（比如从 ROM 里提出来备份的），
        // 一键卸载就会把系统组件干掉 —— 那是很难恢复的事故。
        // 所以这里用更醒目的措辞 + 默认不聚焦确认按钮来降低误操作概率。
        boolean system = isSystemApp(card.packageName);

        String msg = "将以 root 权限执行 pm uninstall。\n"
                + "注意：该应用的所有数据都会被删除。";
        if (system) {
            msg = "⚠ 这是一个【系统应用】。\n\n"
                    + "卸载它可能导致系统功能异常、开机异常，甚至无法恢复。\n"
                    + "除非你非常确定自己在做什么，否则请点「取消」。\n\n"
                    + "包名：" + card.packageName;
        }

        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle((system ? "⚠ 卸载系统应用：" : "卸载 ") + card.packageName)
                .setMessage(msg)
                .setPositiveButton("卸载", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        doUninstall(card.packageName);
                    }
                })
                .setNegativeButton("取消", null);
        android.app.AlertDialog dlg = b.create();
        dlg.show();
        if (system) {
            // 让「取消」成为默认按钮，避免用户顺手回车就把系统应用卸了
            try {
                dlg.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
                        .setFocusableInTouchMode(true);
                dlg.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).requestFocus();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 这个包是不是系统应用（安装在 /system、/system/priv-app 等分区）。 */
    private boolean isSystemApp(String pkg) {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(pkg, 0);
            android.content.pm.ApplicationInfo ai = pi.applicationInfo;
            if (ai == null) {
                return false;
            }
            boolean flagged = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            String src = ai.sourceDir;
            boolean inSystem = src != null && (src.startsWith("/system/")
                    || src.startsWith("/system"));
            return flagged || inSystem;
        } catch (Throwable t) {
            return false;
        }
    }

    private void doUninstall(final String pkg) {
        installing = true;
        installButton.setEnabled(false);
        uninstallButton.setEnabled(false);
        setStatus("正在卸载 " + pkg + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final RootShell.Result r = RootShell.run(
                        "pm uninstall " + RootShell.quote(pkg), 120000L, true);

                // 判定方式和安装保持一致：pm 系列命令**失败时退出码也可能是 0**，
                // 所以必须看输出里的 Success / Failure，不能只看退出码。
                final boolean ok = isShellSuccess(r);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        installing = false;
                        installButton.setEnabled(true);
                        installButton.setText(getString(R.string.btn_install));

                        // 给一个明确的 Toast 反馈，不用让用户去盯底部那行小字
                        if (ok) {
                            toast("已卸载：" + pkg);
                            setStatus("卸载成功：" + pkg);
                        } else {
                            toast("卸载失败，请看下方详情");
                            setStatus("卸载失败：\n" + r.output);
                        }

                        // 重新解析，状态会变成「全新安装」。
                        // 注意 load() 会重设状态栏文字，所以上面的结果先记到
                        // pendingStatus 里，等解析完再显示，避免被覆盖掉。
                        pendingStatus = ok ? "卸载成功：" + pkg
                                : "卸载失败：\n" + r.output;
                        load(apkPath);
                    }
                });
            }
        }, "uninstall").start();
    }

    /** 判断 pm 系列命令是否成功：出现 Failure 即失败，否则要求出现 Success。 */
    private static boolean isShellSuccess(RootShell.Result r) {
        if (r == null || r.suUnavailable || r.isTimeout()) {
            return false;
        }
        String out = r.output == null ? "" : r.output;
        if (out.indexOf("Failure") >= 0) {
            return false;
        }
        return out.indexOf("Success") >= 0;
    }

    /** 在主线程弹一个 Toast。 */
    private void toast(final String msg) {
        try {
            android.widget.Toast.makeText(this, msg,
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w("显示 Toast 失败: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 安装结果广播
    // ------------------------------------------------------------------

    private void registerStatusReceiver() {
        statusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String phase = intent.getStringExtra(InstallService.EXTRA_PHASE);
                String msg = intent.getStringExtra(InstallService.EXTRA_MESSAGE);
                boolean success = intent.getBooleanExtra(InstallService.EXTRA_SUCCESS, false);
                onInstallStatus(phase, success, msg);
            }
        };
        IntentFilter f = new IntentFilter(InstallService.ACTION_STATUS);
        registerReceiver(statusReceiver, f);
    }

    private void onInstallStatus(String phase, boolean success, String message) {
        CrashReporter.note("安装状态回调: phase=" + phase + " success=" + success);
        if (InstallService.PHASE_COPYING.equals(phase)) {
            // 准备阶段也是耗时操作，一起给进度条
            setStatus(message, true);
        } else if (InstallService.PHASE_INSTALLING.equals(phase)) {
            // 进度条就是给这一步用的：pm install 的时间无法预估，
            // 有条动画在动，用户就知道程序没卡死，不会去点关闭屏幕。
            setStatus("正在安装…", true);
        } else if (InstallService.PHASE_DONE.equals(phase)) {
            installing = false;
            installButton.setEnabled(true);
            installButton.setText(getString(R.string.btn_install));
            setStatus("✓ " + message);

            // 顺序很关键：**先删、再决定要不要重新解析**。
            //
            // 原来的顺序反了（先 load 再删），于是勾选「安装成功后删除安装包」时
            // 会出现一个很怪的现象：安装完成的弹窗后面，详情页又显示「无法解析」。
            // 原因是 load() 是**异步**的（后台线程去解析），等它真正读文件时，
            // 随后的 deleteApkIfRequested() 已经把文件删掉了。
            //
            // 文件都删了，就不该再去解析它 —— 那不是「解析失败」，
            // 而是这个包已经按用户设置处理掉了。
            boolean removed = deleteApkIfRequested();
            if (!removed) {
                // 重新解析，界面上「安装状态对比」会立刻变成同版本覆盖
                load(apkPath);
            }
            // 像原生安装器那样给「完成 / 打开」两个选择
            showInstallDoneDialog();
        } else if (InstallService.PHASE_FAILED.equals(phase)
                || InstallService.PHASE_NO_ROOT.equals(phase)) {
            installing = false;
            installButton.setEnabled(true);
            installButton.setText(getString(R.string.btn_install));
            setStatus("✗ " + message);
        }
    }

    /**
     * 安装成功且设置里勾了「安装成功后删除安装包」时，删除这个 APK。
     *
     * <p>有意做得比较保守：
     * <ul>
     *   <li>只删这一个文件，不动它的目录，也不去递归删别的；</li>
     *   <li>删除前先记进崩溃日志，万一出问题能查到删了什么；</li>
     *   <li>删失败（只读介质、权限不足）就明确告诉用户，不假装成功。</li>
     * </ul>
     *
     * @return true 表示**确实删掉了**（调用方据此决定不要再解析这个已消失的文件）；
     *         false 表示没删（没勾选设置、路径为空，或者删除失败 —— 文件还在）
     */
    private boolean deleteApkIfRequested() {
        if (apkPath == null || !Settings.isDeleteApkAfterInstall(this)) {
            return false;
        }
        File f = new File(apkPath);
        CrashReporter.note("按设置删除安装包: " + apkPath);
        boolean deleted;
        try {
            deleted = f.delete();
        } catch (Throwable t) {
            Log.e("删除安装包失败", t);
            deleted = false;
        }
        if (deleted) {
            toast("已按设置删除安装包");
            setStatus("✓ 安装成功，并已删除安装包\n" + apkPath);
        } else {
            toast("安装成功，但删除安装包失败");
            setStatus("安装成功，但删除安装包失败（可能存储卡是只读的）\n" + apkPath);
        }
        return deleted;
    }

    /**
     * 安装成功后的收尾对话框，模仿系统安装器的「完成 / 打开」。
     *
     * 「打开」是去启动刚装上的应用。这里要注意：
     *   getLaunchIntentForPackage 有可能返回 null（应用没有可启动的 Activity，
     *   或者启动 Activity 是导出的但被禁用），直接 startActivity(null) 会崩，
     *   所以必须判空并给出提示。
     */
    private void showInstallDoneDialog() {
        final String pkg = card == null ? null : card.packageName;
        if (pkg == null) {
            return;
        }

        // 正文优先显示「应用名称」，读不到才退回包名。
        //
        // 为什么不直接用 card.label：那是从**APK 文件**里解析出来的名字，
        // 而这里更有意义的是**刚装上那个应用在系统里的名字**（两者通常一样，
        // 但系统里那份才是用户之后实际会看到的）。所以先查 PackageManager，
        // 查不到再退回 APK 里的 label，最后才用包名。
        String appName = null;
        try {
            android.content.pm.ApplicationInfo ai =
                    getPackageManager().getApplicationInfo(pkg, 0);
            if (ai != null) {
                CharSequence lbl = getPackageManager().getApplicationLabel(ai);
                if (lbl != null && lbl.length() > 0) {
                    appName = lbl.toString();
                }
            }
        } catch (Throwable t) {
            Log.w("读取已安装应用名称失败: " + t);
        }
        if (appName == null && card != null && card.label != null
                && card.label.length() > 0) {
            appName = card.label;
        }
        // 有名称就「名称 + 包名」两行；只有包名时就只显示包名，不留空行
        final String message = (appName != null) ? (appName + "\n" + pkg) : pkg;

        try {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                    .setTitle("安装完成")
                    .setMessage(message)
                    .setPositiveButton("打开", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            openInstalledApp(pkg);
                        }
                    })
                    .setNegativeButton("完成", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            // 「完成」就是「我用完了，离开安装界面」——和原生安装器一致。
                            //
                            // 这里**不能**再判断 launchedFromExternal。
                            // 踩过的坑：早先写成「只有外部调用才 finish」，
                            // 于是从本应用列表点进详情页装完再点「完成」时什么都不发生，
                            // 那个页面就留在后台，用户回到桌面后它还在（表现为
                            // 「安装器仍残留在后台」）。而「完成」这个按钮本身
                            // 已经是用户明确的结束信号，与入口无关。
                            //
                            // 从列表进来时 finish 会回到列表页，这也正是用户期望的。
                            //
                            // 用 finishThisPage() 而不是 finish()：外部调用场景下
                            // 任务里只剩本页，需要在结束时把空任务一并摘掉，
                            // 否则「最近任务」里会留下一条点进去什么都没有的记录。
                            finishThisPage();
                        }
                    });
            android.app.AlertDialog dlg = b.create();
            dlg.show();
            // 外部调用时用户多半只想装完就走，让「完成」默认获得焦点
            if (launchedFromExternal) {
                try {
                    dlg.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).requestFocus();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w("显示安装完成对话框失败: " + t);
        }
    }

    /** 启动刚安装好的应用。 */
    private void openInstalledApp(String pkg) {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) {
                toast("这个应用没有可启动的界面");
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            // 点「打开」之后用户已经去用新装的应用了，安装界面没有理由留在后台
            // （同样不能按 launchedFromExternal 区分，理由见「完成」按钮处的注释）。
            finishThisPage();
        } catch (Throwable t) {
            Log.e("启动应用失败: " + pkg, t);
            toast("启动失败：" + t);
        }
    }

    /**
     * 结束本页；**如果本页是任务里最后一个 Activity，连任务一起从「最近任务」里摘掉**。
     *
     * <p>为什么需要它：被别的应用拉起来当安装器时，{@code MainActivity} 会
     * {@code finish()} 掉自己，任务栈里只剩本页。本页结束时若不处理，
     * 就会留下一条**已经空掉的任务记录**，用户退回桌面后在「最近任务」里
     * 还能看到它 —— 点进去什么也没有，残留感很明显。
     *
     * <p>实现上分两层：
     * <ul>
     *   <li>清单里给本 Activity 加了 {@code android:excludeFromRecents="true"}，
     *       这是**唯一在 API 15 就可用**的公开手段（本机是 4.3 / API 18）。</li>
     *   <li>API 21 起另有 {@code Activity.finishAndRemoveTask()} 能主动把任务摘掉，
     *       换新设备时更干净。它不能用直接调用（编译目标是 android-19，
     *       那个 class 里没有这个方法，会「找不到符号」），所以用反射。</li>
     * </ul>
     *
     * <p>注意这里**不能**用 {@code noHistory}：那会在 SuperSU 授权框弹出、
     * 本页被推到后台时立刻销毁本页（详见清单里的注释），是踩过的坑。
     */
    private void finishThisPage() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                getClass().getMethod("finishAndRemoveTask").invoke(this);
                return;
            }
        } catch (Throwable ignored) {
            // 反射失败就退回普通 finish，不影响主流程
        }
        finish();
    }

    /**
     * 返回键处理。
     *
     * 被别的应用拉起来当安装器用时，用户按返回就应该**退出**，
     * 而不是掉进我们自己的安装包列表 —— 那对「我只是来装个东西」的用户很困惑。
     * 所以这种情况直接结束本页（并把空任务从最近任务里摘掉）。
     */
    @Override
    public void onBackPressed() {
        if (launchedFromExternal) {
            finishThisPage();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (statusReceiver != null) {
            try {
                unregisterReceiver(statusReceiver);
            } catch (Throwable ignored) {
            }
            statusReceiver = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // 标题栏：标题 + 包名摘要 + 左上角返回按钮
        String sub = null;
        if (apkPath != null) {
            int slash = apkPath.lastIndexOf('/');
            sub = Paths.shorten(slash >= 0 ? apkPath.substring(slash + 1) : apkPath, 32);
        }
        ActionBarHelper.setUpButton(this, "安装详情", sub);
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (ActionBarHelper.handleHome(this, item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

