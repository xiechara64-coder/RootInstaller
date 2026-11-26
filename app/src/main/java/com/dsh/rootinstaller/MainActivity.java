package com.dsh.rootinstaller;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 被其它应用「调用」的入口之一，也是本应用的主界面（从桌面图标进来时）。
 *
 * 支持的入口 Intent：
 *   - android.intent.action.INSTALL_PACKAGE      （data = file:// 或 content:// 的 apk）
 *   - android.intent.action.VIEW                 （mime = application/vnd.android.package-archive）
 *   - android.intent.action.MAIN                 （桌面图标）
 */
public class MainActivity extends ListActivity {

    private TextView statusView;
    private View emptyView;
    private ApkListAdapter adapter;
    private final List<ApkCard> items = new ArrayList<ApkCard>();
    private ScanTask scanTask;
    /** 扫描期间的加载提示框。 */
    private BusyDialog busy;
    /** 上次排序时用的方式，供 onResume 比对是否需要重排。 */
    private int apkSortModeShown = -1;

    /**
     * 多选模式：长按列表项进入，底部出现操作条。
     *
     * <p>只在「主界面直接打开」时可用；被别的应用当安装器调起时走的是详情页，
     * 不会到这里。
     */
    private boolean selecting;
    private final Selection selection = new Selection();
    private View selectBar;
    private TextView selectInfo;

    /**
     * 适配器的静态引用，供 DetailsActivity 回填解析出来的图标。
     *
     * 用静态引用是权衡后的选择：正常的做法是 startActivityForResult 回传，
     * 但那样只能带一个路径，而图标是 Bitmap，走 Intent 传要序列化（慢且可能超大）。
     * 这里只在「详情页解析完一个图标」这一处用到，且 MainActivity 销毁时会清空，
     * 风险可控。
     */
    static ApkListAdapter sAdapter;

    /**
     * 最近一次扫描的结果，供详情页回答「还有哪些包和它同签名但包名不同」。
     *
     * <p>和 {@link #sAdapter} 一样是静态引用，同样在 onDestroy 里清空；
     * 只在主界面存活期间有效，不跨进程、不落盘。
     */
    static List<ApkCard> sLastScan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全局崩溃记录器：必须在任何可能出错的事情之前装好
        CrashReporter.install(this);

        setContentView(R.layout.main);

        statusView = (TextView) findViewById(R.id.status);
        emptyView = findViewById(R.id.empty);

        // 信息条上的「更多」按钮：默认隐藏，只留 ActionBar 右上角那一个入口。
        //
        // 为什么不保留两个：功能完全重复，界面上显得累赘。
        // 那为什么还要留下这个按钮的代码：为了应对「设备没有菜单键、
        // 或者菜单键坏了」的极端情况 —— 此时 ActionBar 上的入口其实还在
        // （它是我们自己画的 always item，不依赖硬件菜单键），但万一遇到
        // 系统把 ActionBar 吃掉的主题/ROM，长按左边的状态文字就能把它唤出来。
        final View fallbackMore = findViewById(R.id.btn_more);
        fallbackMore.setVisibility(View.GONE);
        fallbackMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOverflowPopup(v);
            }
        });
        // 长按状态条：切换备用按钮的显隐
        statusView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                boolean showing = fallbackMore.getVisibility() == View.VISIBLE;
                fallbackMore.setVisibility(showing ? View.GONE : View.VISIBLE);
                android.widget.Toast.makeText(MainActivity.this,
                        showing ? "已隐藏备用菜单按钮" : "已显示备用菜单按钮（长按可切换）",
                        android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        // 上次崩溃过就弹窗，并提供一键复制，方便把日志发出来排查
        CrashReporter.showDialogIfAny(this);

        adapter = new ApkListAdapter(this, items);
        setListAdapter(adapter);
        getListView().setEmptyView(emptyView);
        // 让详情页解析出来的图标能回填到列表（进过一次详情页就不再是占位图）
        sAdapter = adapter;

        // 点击列表项 -> 进入详情页。
        // 这里之前漏掉了，导致整个列表点不动（只能干看着），是个很影响可用性的 bug。
        getListView().setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent,
                                    android.view.View view, int position, long id) {
                Object obj = parent.getItemAtPosition(position);
                if (!(obj instanceof ApkCard)) {
                    return;
                }
                ApkCard c = (ApkCard) obj;
                if (selecting) {
                    toggleSelection(c);
                    return;
                }
                CrashReporter.note("用户点击列表项: " + c.filePath);
                Intent detail = new Intent(MainActivity.this, DetailsActivity.class);
                detail.putExtra(DetailsActivity.EXTRA_APK, c.filePath);
                startActivity(detail);
            }
        });
        // 长按进入多选模式（Android 惯例）。
        //
        // 注意：以前长按是「进详情页」的救急入口。现在单击仍然进详情，
        // 所以功能没有丢；多选模式下单击则是勾选/取消。
        getListView().setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent,
                                           android.view.View view, int position, long id) {
                if (position < 0 || position >= items.size()) {
                    return false;
                }
                enterSelection(position);
                return true;
            }
        });

        // 底部多选操作条
        selectBar = findViewById(R.id.select_bar);
        selectInfo = (TextView) findViewById(R.id.select_info);
        findViewById(R.id.select_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selection.selectAll(allPaths());
                adapter.notifyDataSetChanged();
                updateSelectBar();
            }
        });
        findViewById(R.id.select_invert).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selection.invert(allPaths());
                adapter.notifyDataSetChanged();
                updateSelectBar();
            }
        });
        findViewById(R.id.select_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDeleteSelected();
            }
        });
        findViewById(R.id.select_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitSelection();
            }
        });

        // 作为「安装器」被别的应用拉起来时，直接把路径转给详情页。
        // 注意：这种情况下**不要**顺手扫描列表 —— 用户是被别的应用拉过来装东西的，
        // 装完就该退出，不该把他丢进我们的列表界面。
        if (!handleExternalIntent(getIntent())) {
            rescan();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExternalIntent(intent);
    }

    /**
     * 处理外部调用。
     *
     * @return true 表示这个 Intent 是「打开某个 apk」，已经转交详情页
     */
    private boolean handleExternalIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        if (action == null) {
            return false;
        }
        boolean looksLikeInstall = Intent.ACTION_VIEW.equals(action)
                || "android.intent.action.INSTALL_PACKAGE".equals(action);
        if (!looksLikeInstall) {
            return false;
        }
        String path = IntentPaths.resolve(this, intent);
        if (path == null) {
            // 有些应用传的是 content:// ，解析不到就提示一下，别静默失败
            String raw = intent.getData() == null ? "" : intent.getData().toString();
            statusView.setText("收到了安装请求，但无法读取文件路径：\n" + raw
                    + "\n\n可以从下面的列表里手动选择安装包。");
            rescan();
            return false;
        }
        Log.i("外部调用，目标 APK: " + path);
        Intent detail = new Intent(this, DetailsActivity.class);
        detail.putExtra(DetailsActivity.EXTRA_APK, path);
        detail.putExtra(DetailsActivity.EXTRA_FROM_EXTERNAL, true);
        startActivity(detail);

        // 关键一步：把自己结束掉。
        //
        // 否则返回栈是 [主界面, 详情页]，用户在详情页按返回会掉进我们的安装包列表 ——
        // 而他是被文件管理器拉来装东西的，期待的是回到文件管理器。
        // finish 掉之后栈里只剩详情页，返回就直接退出，和系统安装器的行为一致。
        //
        // 这里不 rescan()：没必要为一装一卸把整张存储卡扫一遍。
        finish();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter == null) {
            return;
        }
        // 从详情页回来时，安装/卸载可能已经改变了「已装版本」，
        // 所以要在**不改变滚动位置**的前提下刷新一遍列表。
        //
        // 为什么不直接 rescan()：
        //   重新扫描要几秒，而且会把列表滚回顶部，用户刚才看到哪就丢了。
        //   这里只需要更新「已安装状态」这几个字段，代价很小。
        refreshInstalledState();

        // 文件可能已经不在了：详情页里刚删过，或者用户在文件管理器里删的。
        // 不剔掉的话，列表会留着一个点开就报「已经不在了」的死项 ——
        // 而且它会跟着进入多选，删除时再报一次「文件已经不存在了」。
        pruneMissingItems();

        // 用户可能刚从设置页改了排序方式，比对一下，变了就重排。
        // 同样不重新扫描 —— 只对现有列表重排，代价可以忽略。
        int mode = Settings.getApkSortMode(this);
        if (mode != apkSortModeShown) {
            apkSortModeShown = mode;
            applySort();
            adapter.notifyDataSetChanged();
            statusView.setText("排序方式已切换为：" + Settings.APK_SORT_NAMES[mode]);
        }
    }

    /** 按设置里的方式给当前列表排序。 */
    private void applySort() {
        ApkSorter.sort(items, Settings.getApkSortMode(this));
    }

    /**
     * 原地刷新每一项的「安装状态」，保持滚动位置和列表顺序不变。
     *
     * 只更新和已安装版本相关的字段（installed / versionCode / 升级降级判定），
     * 不重新解析 APK，所以很快。
     */
    private void refreshInstalledState() {
        final android.content.pm.PackageManager pm = getPackageManager();
        for (int i = 0; i < items.size(); i++) {
            ApkCard c = items.get(i);
            if (c.packageName == null) {
                continue;
            }
            android.content.pm.PackageInfo pi = null;
            try {
                pi = pm.getPackageInfo(c.packageName, 0);
            } catch (Throwable ignored) {
                // 没装
            }
            if (pi == null) {
                c.installed = false;
                c.installedVersionName = null;
                c.installedVersionCode = -1;
                c.isUpgrade = false;
                c.isDowngrade = false;
                c.isSameVersion = false;
            } else {
                c.installed = true;
                c.installedVersionName = pi.versionName;
                c.installedVersionCode = pi.versionCode;
                if (c.versionCode >= 0) {
                    c.isUpgrade = c.versionCode > pi.versionCode;
                    c.isDowngrade = c.versionCode < pi.versionCode;
                    c.isSameVersion = c.versionCode == pi.versionCode;
                }
            }
        }
        int first = getListView().getFirstVisiblePosition();
        adapter.notifyDataSetChanged();
        // notifyDataSetChanged 一般不改变滚动位置，但保险起见再恢复一次
        getListView().setSelection(first);
    }

    /** 重新扫描（会弹出加载提示，并跳回列表顶部）。 */
    // ------------------------------------------------------------------
    // 多选与删除安装包
    // ------------------------------------------------------------------

    private void enterSelection(int position) {
        ApkCard c = items.get(position);
        selecting = true;
        selection.clear();
        selection.select(c.filePath);
        adapter.setSelectionMode(true, selection);
        selectBar.setVisibility(View.VISIBLE);
        updateSelectBar();
        CrashReporter.note("进入多选模式: " + c.filePath);
    }

    private void exitSelection() {
        selecting = false;
        selection.clear();
        if (adapter != null) {
            adapter.setSelectionMode(false, null);
        }
        if (selectBar != null) {
            selectBar.setVisibility(View.GONE);
        }
    }

    private void toggleSelection(ApkCard c) {
        selection.toggle(c.filePath);
        adapter.notifyDataSetChanged();
        updateSelectBar();
    }

    /**
     * 剔除列表里「文件已经不存在」的项。
     *
     * <p>触发场景：在详情页删了包再返回、或在文件管理器里删了文件再切回来。
     * 只做 {@code exists()} 检查（159 次 stat，代价可忽略），不重新解析、不动滚动位置。
     */
    private void pruneMissingItems() {
        if (items.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            String p = items.get(i).filePath;
            if (p == null || !new java.io.File(p).exists()) {
                items.remove(i);
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        if (selecting) {
            List<String> alive = new ArrayList<String>();
            for (int i = 0; i < items.size(); i++) {
                alive.add(items.get(i).filePath);
            }
            selection.retainOnly(alive);
            updateSelectBar();
        }
        adapter.notifyDataSetChanged();
        Log.i("列表里有 APK 已经不存在，已从列表移除");
    }

    private List<String> allPaths() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < items.size(); i++) {
            out.add(items.get(i).filePath);
        }
        return out;
    }

    private void updateSelectBar() {
        if (selectInfo == null) {
            return;
        }
        int n = selection.size();
        long bytes = 0;
        for (int i = 0; i < items.size(); i++) {
            ApkCard c = items.get(i);
            if (selection.isSelected(c.filePath)) {
                bytes += c.fileSize;
            }
        }
        selectInfo.setText("已选 " + n + " 项 · 共 " + ApkParser.humanSize(bytes));
        View del = findViewById(R.id.select_delete);
        if (del != null) {
            del.setEnabled(n > 0);
            del.setAlpha(n > 0 ? 1f : 0.4f);
        }
    }

    private ApkCard findCard(String path) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).filePath != null && items.get(i).filePath.equals(path)) {
                return items.get(i);
            }
        }
        return null;
    }

    private void confirmDeleteSelected() {
        // 先剔掉已经不存在的文件，否则确认框会列出「注定删不掉」的项
        pruneMissingItems();
        final List<String> selected = selection.selectedList();
        if (selected.isEmpty()) {
            toastMsg("还没有选中任何安装包");
            return;
        }
        // 先把「不允许删」的分出来：确认框里就告诉用户，别等失败才说
        final List<String> deletable = new ArrayList<String>();
        final List<String> blocked = new ArrayList<String>();
        long bytes = 0;
        for (int i = 0; i < selected.size(); i++) {
            String p = selected.get(i);
            if (DeletablePaths.canDelete(p)) {
                deletable.add(p);
                ApkCard c = findCard(p);
                if (c != null) {
                    bytes += c.fileSize;
                }
            } else {
                blocked.add(DeletablePaths.fileNameOf(p));
            }
        }
        if (deletable.isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("没有可删除的文件")
                    .setMessage("选中的 " + blocked.size()
                            + " 个包位于系统目录或 asec 容器里，本应用不删除它们。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        StringBuilder msg = new StringBuilder();
        msg.append("合计 ").append(ApkParser.humanSize(bytes))
                .append("，删除后无法恢复。\n\n");
        int show = Math.min(deletable.size(), 6);
        for (int i = 0; i < show; i++) {
            msg.append("· ").append(DeletablePaths.fileNameOf(deletable.get(i))).append('\n');
        }
        if (deletable.size() > show) {
            msg.append("· …还有 ").append(deletable.size() - show).append(" 个\n");
        }
        if (!blocked.isEmpty()) {
            msg.append("\n其中 ").append(blocked.size()).append(" 个位于系统目录，将被跳过。");
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("删除 " + deletable.size() + " 个安装包？")
                .setMessage(msg.toString())
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        performDelete(deletable);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void performDelete(final List<String> paths) {
        final java.util.Map<String, Long> sizes = new java.util.HashMap<String, Long>();
        for (int i = 0; i < items.size(); i++) {
            ApkCard c = items.get(i);
            if (paths.contains(c.filePath)) {
                sizes.put(c.filePath, c.fileSize);
            }
        }
        statusView.setText("正在删除 " + paths.size() + " 个安装包…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ApkDeleter.Batch batch =
                        ApkDeleter.deleteAll(MainActivity.this, paths, sizes);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 文件确实没了的才从列表摘掉。
                        // 这里**不重扫**：暖扫也要十几秒，删几个包却让用户等一轮扫描太蠢。
                        java.util.HashSet<String> gone = new java.util.HashSet<String>();
                        for (int i = 0; i < paths.size(); i++) {
                            if (!new java.io.File(paths.get(i)).exists()) {
                                gone.add(paths.get(i));
                            }
                        }
                        for (int i = items.size() - 1; i >= 0; i--) {
                            if (gone.contains(items.get(i).filePath)) {
                                items.remove(i);
                            }
                        }
                        exitSelection();
                        adapter.notifyDataSetChanged();
                        String msg = batch.summary();
                        statusView.setText((batch.failures.isEmpty() ? "✓ " : "! ") + msg);
                        toastMsg(msg);
                        if (!batch.failures.isEmpty()) {
                            showDeleteFailures(batch.failures);
                        }
                    }
                });
            }
        }, "apk-delete").start();
    }

    private void showDeleteFailures(final List<String> failures) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(failures.size(), 12);
        for (int i = 0; i < limit; i++) {
            sb.append("· ").append(failures.get(i)).append('\n');
        }
        if (failures.size() > limit) {
            sb.append("…还有 ").append(failures.size() - limit).append(" 条\n");
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("有 " + failures.size() + " 个没删掉")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    private void toastMsg(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (selecting) {
            // 多选模式下返回键先退出多选，而不是直接退出应用
            exitSelection();
            return;
        }
        super.onBackPressed();
    }

    private void rescan() {
        if (scanTask != null) {
            scanTask.cancelled = true;
        }
        // 重新扫描会重建列表，之前的选择已经没有意义
        if (selecting) {
            exitSelection();
        }
        showBusy();
        scanTask = new ScanTask();
        scanTask.start();
    }

    private void showBusy() {
        try {
            if (busy != null) {
                busy.dismiss();
            }
            busy = BusyDialog.show(this, getString(R.string.scanning));
        } catch (Throwable t) {
            Log.w("显示扫描提示失败: " + t);
        }
    }

    private void hideBusy() {
        if (busy != null) {
            busy.dismiss();
            busy = null;
        }
    }

    // ------------------------------------------------------------------

    private class ScanTask extends Thread {
        volatile boolean cancelled;

        ScanTask() {
            super("apk-scan");
        }

        @Override
        public void run() {
            long t0 = System.currentTimeMillis();
            final List<ApkCard> found = new ArrayList<ApkCard>();
            // 候选扫描根目录。
            // 注意：这里先按「规范路径」去重，因为 Android 上 /sdcard、
            // /mnt/sdcard 都只是 /storage/sdcard0 的符号链接，
            // 不去重的话同一张卡要被完整扫 3 遍（慢 3 倍，结果还重复）。
            List<String> roots = new ArrayList<String>();
            roots.add(Paths.primaryExternal());
            roots.addAll(Paths.secondaryExternal(MainActivity.this));
            roots.add("/sdcard");
            roots.add("/mnt/sdcard");

            List<String> uniqueRoots = Paths.dedupe(roots);
            Log.i("扫描根目录（去符号链接后）: " + uniqueRoots);

            final List<String> files = new ArrayList<String>();
            long tFind = System.currentTimeMillis();
            for (int i = 0; i < uniqueRoots.size(); i++) {
                if (cancelled) {
                    return;
                }
                Paths.findApks(uniqueRoots.get(i), files, 4, 400);
            }
            long costFind = System.currentTimeMillis() - tFind;

            // 兜底再去重一次：以防某两张卡之间有交叉挂载之类的情况
            final List<String> uniq = Paths.dedupe(files);
            if (uniq.size() != files.size()) {
                Log.i("去重生效: " + files.size() + " -> " + uniq.size());
            }
            Log.i("阶段计时: 遍历目录 " + costFind + "ms，找到 " + uniq.size() + " 个候选");

            long tParse = System.currentTimeMillis();
            long slowest = 0;
            String slowestPath = null;
            // 记下本次扫描实际用到的缓存键：扫描结束后用它判断哪些缓存已成孤儿
            final java.util.Set<String> seenCacheKeys = new java.util.HashSet<String>();
            for (int i = 0; i < uniq.size(); i++) {
                if (cancelled) {
                    return;
                }
                String p = uniq.get(i);
                try {
                    long t1 = System.currentTimeMillis();
                    ApkCard c = ApkParser.parse(MainActivity.this, p);
                    long dt = System.currentTimeMillis() - t1;
                    if (dt > slowest) {
                        slowest = dt;
                        slowestPath = p;
                    }
                    found.add(c);
                    try {
                        seenCacheKeys.add(ParseCache.keyFor(p));
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable t) {
                    Log.e("解析失败: " + p, t);
                }
            }
            long costParse = System.currentTimeMillis() - tParse;
            Log.i("阶段计时: 解析 " + uniq.size() + " 个包 " + costParse
                    + "ms，最慢单包 " + slowest + "ms (" + slowestPath + ")");

            // 一次扫描结束后统一淘汰一次图标缓存。
            // 不放在每个包的解析里：那样每存一个图标都要列一遍目录，反而更慢。
            ParseCache.trim(MainActivity.this);
            ParseCache.logScanSummary();
            // 清掉「对应 APK 已经不在、且超过宽限期」的孤儿缓存。
            // 不能只看「这次没扫到就删」：SD 卡没插时卡上所有包都会消失，
            // 那样会把好缓存全清掉 —— 宽限期就是为了扛住这种情况。
            ParseCache.sweep(MainActivity.this, seenCacheKeys, System.currentTimeMillis());
            // 缓存是否真的落地了？只靠命中数看不出来（命中 0 可能是「没写过」，
            // 也可能是「写了但读不到」），把目录和占用一起打出来。
            Log.i("缓存占用：解析 " + ParseCache.stats(MainActivity.this).describe()
                    + " @ " + ParseCache.dirPath(MainActivity.this));

            final long cost = System.currentTimeMillis() - t0;
            Log.i("扫描完成，找到 " + found.size() + " 个 APK，耗时 " + cost + "ms");

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (cancelled) {
                        return;
                    }
                    hideBusy();
                    items.clear();
                    items.addAll(found);
                    // 扫描出来的是「内部存储 → SD 卡」的磁盘遍历顺序，
                    // 那个顺序对用户没有意义，按设置重排一次
                    int sortMode = Settings.getApkSortMode(MainActivity.this);
                    apkSortModeShown = sortMode;
                    ApkSorter.sort(items, sortMode);
                    adapter.notifyDataSetChanged();
                    sLastScan = items;
                    statusView.setText("设备：" + ApkParser.deviceSummary()
                            + "\n扫描到 " + found.size() + " 个安装包（耗时 "
                            + cost + "ms）"
                            + "\n排序：" + Settings.APK_SORT_NAMES[sortMode]);
                }
            });

            // 列表显示之后再补证书 + 算「同签名不同包名」——
            // 这一步要读每个包的签名块，放在扫描主流程里会拖慢首屏。
            startSignaturePass(found);
        }
    }

    /**
     * 后台补齐每个包的签名者证书，然后算出「同签名但包名不同」的其它包。
     *
     * <p>为什么不放在解析里：解析路线是「列表要快」，而分组只是**提示**，
     * 晚一两秒出现完全可以接受；放在主流程里会让每次扫描都变慢。
     *
     * <p>代价只在第一次扫描时付：证书读出来后会随解析缓存一起落盘
     * （{@code signaturePresent}），下次扫描就有了。
     */
    private void startSignaturePass(final List<ApkCard> cards) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();
                int computed = 0;
                for (int i = 0; i < cards.size(); i++) {
                    if (scanTask == null || scanTask.cancelled) {
                        return;
                    }
                    ApkCard c = cards.get(i);
                    if (c.archiveSignerSha1 != null) {
                        continue;
                    }
                    try {
                        ApkSignatures.Result r = ApkSignatures.inspectCertificate(c.filePath);
                        c.archiveSignerSha1 = r.certSha1;
                        c.sigV1 = r.v1;
                        c.sigV2 = r.v2;
                        c.sigV3 = r.v3;
                        c.sigV4 = r.v4;
                        // 存进解析缓存：下次扫描就不用再读签名块了
                        ParseCache.updateCertificate(MainActivity.this, c.filePath,
                                r.certSha1, r.v1, r.v2, r.v3, r.v4);
                        computed++;
                    } catch (Throwable t) {
                        Log.w("读取签名证书失败: " + c.filePath + " -> " + t);
                    }
                    if (computed > 0 && (computed % 8) == 0) {
                        notifyListLater();
                    }
                }
                Log.i("证书补齐: " + computed + " 个，耗时 " + (System.currentTimeMillis() - t0) + "ms");
                applySameSigGroups(cards);
                notifyListLater();
            }
        }, "sig-pass").start();
    }

    /** 算「同签名不同包名」并写回每张卡片。 */
    private void applySameSigGroups(List<ApkCard> cards) {
        final List<String> pkgs = new ArrayList<String>();
        final List<String> certs = new ArrayList<String>();
        final List<String> labels = new ArrayList<String>();
        for (int i = 0; i < cards.size(); i++) {
            pkgs.add(cards.get(i).packageName);
            certs.add(cards.get(i).archiveSignerSha1);
            labels.add(cards.get(i).label);
        }
        java.util.Map<String, SameSigGroups.Info> map = SameSigGroups.compute(pkgs, certs, labels);
        int groups = 0;
        StringBuilder sample = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            ApkCard c = cards.get(i);
            SameSigGroups.Info info = c.packageName == null ? null : map.get(c.packageName);
            if (info == null) {
                c.sameSigCount = 0;
                c.sameSigSummary = null;
            } else {
                c.sameSigCount = info.count;
                c.sameSigSummary = info.summary;
                groups++;
                if (groups <= 5) {
                    if (sample.length() > 0) {
                        sample.append("；");
                    }
                    sample.append(c.packageName).append(" → ").append(info.summary);
                }
            }
        }
        Log.i("同签名分组: " + groups + " 个包存在「同签名不同包名」的其它包"
                + (sample.length() > 0 ? "： " + sample : ""));
    }

    /** 从后台线程安全地刷新列表。 */
    private void notifyListLater() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        hideBusy();
        if (sAdapter == adapter) {
            sAdapter = null;
        }
        if (sLastScan == items) {
            sLastScan = null;
        }
        if (scanTask != null) {
            scanTask.cancelled = true;
        }
        super.onDestroy();
    }

    // 菜单走 res/menu/main.xml。
    //
    // 为什么不用 menu.add()：代码添加的项在老设备上依赖硬件菜单键；
    // XML + showAsAction="never" 会被收进 ActionBar 右上角的溢出菜单，
    // 在没有实体菜单键（或者菜单键坏了）的设备上照样能打开。
    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);

        // 这一步是修「右上角出现两个三个点」的关键。
        //
        // 我们为了照顾有实体菜单键的老设备，自己放了一个 always 显示的入口项；
        // 而在**没有**实体菜单键的设备上，系统自己也会画原生溢出按钮
        // （因为其余项都是 "never"），于是两个并排出现。
        // 这里让两者互斥：系统会画就把自己的藏起来。
        ActionBarHelper.hideOwnOverflowIfSystemShows(this, menu,
                R.id.menu_overflow, "主界面");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        return onMenuAction(item.getItemId()) || super.onOptionsItemSelected(item);
    }

    /**
     * 弹出「更多」菜单。
     *
     * 文案直接从 res/menu/main.xml 里读，不在这里再写一遍 ——
     * 三条件入口（ActionBar 上的竖点 / 信息条上的兜底按钮 / 硬件菜单键）
     * 共用同一份定义，不会走偏。
     *
     * 注意要跳过 menu_overflow 这一项：它自己就是「打开这个菜单」的按钮，
     * 出现在菜单列表里没有意义。
     */
    private void showOverflowPopup(View anchor) {
        android.view.Menu m = new android.widget.PopupMenu(this, anchor).getMenu();
        getMenuInflater().inflate(R.menu.main, m);

        // 先收集出真正要显示给用户的条目
        final java.util.List<String> titles = new java.util.ArrayList<String>();
        final java.util.List<Integer> ids = new java.util.ArrayList<Integer>();
        for (int i = 0; i < m.size(); i++) {
            android.view.MenuItem it = m.getItem(i);
            if (it.getItemId() == R.id.menu_overflow) {
                continue;
            }
            titles.add(it.getTitle().toString());
            ids.add(it.getItemId());
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("菜单")
                .setItems(titles.toArray(new String[titles.size()]),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                if (which >= 0 && which < ids.size()) {
                                    onMenuAction(ids.get(which));
                                }
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 菜单动作的统一处理。
     * 系统 ActionBar 菜单和界面内的「更多」弹窗都走这里，避免两份实现走偏。
     */
    private boolean onMenuAction(int id) {
        // ActionBar 上那个竖点按钮：点它就是打开菜单本身
        if (id == R.id.menu_overflow) {
            showOverflowPopup(findViewById(R.id.menu_anchor));
            return true;
        }
        if (id == R.id.menu_pick_file) {
            startActivity(new Intent(this, FilePickerActivity.class));
            return true;
        }
        if (id == R.id.menu_rescan) {
            rescan();
            return true;
        }
        if (id == R.id.menu_settings) {
            SettingsActivity.open(this);
            return true;
        }
        if (id == R.id.menu_check_root) {
            checkRoot();
            return true;
        }
        if (id == R.id.menu_clear_crash) {
            clearCrashLog();
            return true;
        }
        return false;
    }

    private void clearCrashLog() {
        CrashReporter.clear(this);
        android.widget.Toast.makeText(this, "崩溃记录已清除",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void checkRoot() {
        statusView.setText("正在检测 root…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final RootShell.Result r = RootShell.run("id", 20000L, true);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String msg;
                        if (r.suUnavailable) {
                            msg = "✗ 没有可用的 root。\n命令输出：" + r.output;
                        } else if (r.outputContains("uid=0")) {
                            msg = "✓ root 可用。\n" + r.output.trim();
                        } else {
                            msg = "? 命令执行了，但身份看起来不是 root。\n" + r.output.trim();
                        }
                        statusView.setText(msg);
                    }
                });
            }
        }, "root-check").start();
    }
}
