package com.dsh.rootinstaller;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 文件选择器：让用户自己挑一个 APK。
 *
 * 为什么需要它？因为「扫描存储卡」只能覆盖常见目录，
 * 有些人的安装包放在很深的、名字很怪的自定义目录里，这时候手动选更靠谱。
 */
public class FilePickerActivity extends Activity {

    private TextView pathView;
    private ListView listView;
    private TextView emptyView;
    private Button upButton;

    private final List<File> dirs = new ArrayList<File>();
    private final List<File> files = new ArrayList<File>();
    private FileListAdapter adapter;
    private File current;
    private boolean showAllFiles;
    /** onCreate 里已经 open 过一次，避免 onResume 又白跑一遍。 */
    private boolean firstResume = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.file_picker);

        // 左上角返回按钮
        ActionBarHelper.setUpButton(this, getString(R.string.title_picker), null);

        pathView = (TextView) findViewById(R.id.path);
        listView = (ListView) findViewById(R.id.file_list);
        emptyView = (TextView) findViewById(R.id.file_empty);
        upButton = (Button) findViewById(R.id.btn_up);

        adapter = new FileListAdapter(this, dirs, files, false);
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyView);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                File f = (File) adapter.getItem(position);
                if (f == null) {
                    return;
                }
                if (f.isDirectory()) {
                    open(f);
                } else if (f.getName().toLowerCase().endsWith(".apk")) {
                    openDetails(f);
                } else {
                    Toast.makeText(FilePickerActivity.this,
                            "这不是 APK 文件：" + f.getName(),
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        upButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File parent = current == null ? null : current.getParentFile();
                if (parent != null && parent.canRead()) {
                    open(parent);
                } else {
                    Toast.makeText(FilePickerActivity.this,
                            "已经是顶层目录了", Toast.LENGTH_SHORT).show();
                }
            }
        });

        buildQuickLinks();
        open(defaultStart());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
            return;
        }
        // 从详情页返回时刷新，文件可能被删了
        if (current != null) {
            open(current);
        }
    }

    @SuppressWarnings("deprecation")
    private File defaultStart() {
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null && ext.isDirectory() && ext.canRead()) {
            return ext;
        }
        return new File("/");
    }

    private void buildQuickLinks() {
        LinearLayout bar = (LinearLayout) findViewById(R.id.quick_links);
        String[] names = new String[] { "内部存储", "Download", "bluetooth", "根目录" };
        String[] paths = new String[] {
                Paths.primaryExternal(),
                Paths.primaryExternal() + "/Download",
                Paths.primaryExternal() + "/bluetooth",
                "/"
        };
        for (int i = 0; i < names.length; i++) {
            final String p = paths[i];
            Button b = new Button(this);
            b.setText(names[i]);
            b.setTextSize(12f);
            b.setPadding(12, 4, 12, 4);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    File f = new File(p);
                    if (f.isDirectory() && f.canRead()) {
                        open(f);
                    } else {
                        Toast.makeText(FilePickerActivity.this,
                                "这个目录不存在：" + p, Toast.LENGTH_SHORT).show();
                    }
                }
            });
            bar.addView(b, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void open(File dir) {
        if (dir == null || !dir.isDirectory() || !dir.canRead()) {
            Toast.makeText(this, "无法打开这个目录（权限不足）", Toast.LENGTH_SHORT).show();
            return;
        }
        current = dir;
        pathView.setText(dir.getAbsolutePath());

        dirs.clear();
        files.clear();
        List<File> d = new ArrayList<File>();
        List<File> f = new ArrayList<File>();
        Paths.listDir(dir, d, f);

        // 排序：目录和文件各自按名字排，目录在前
        Comparator<File> byName = new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        };
        Collections.sort(d, byName);
        Collections.sort(f, byName);

        dirs.addAll(d);
        files.addAll(f);
        adapter.notifyDataSetChanged();

        // 空目录给个明确提示，别让用户以为是卡了
        emptyView.setText("这个目录是空的，或者里面的内容都被隐藏了。");
        upButton.setEnabled(dir.getParentFile() != null);
    }

    private void openDetails(File apk) {
        Intent i = new Intent(this, DetailsActivity.class);
        i.putExtra(DetailsActivity.EXTRA_APK, apk.getAbsolutePath());
        startActivity(i);
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(0, 1, 0, showAllFiles ? "只显示 APK" : "显示全部文件");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        // 左上角返回按钮优先处理
        if (ActionBarHelper.handleHome(this, item)) {
            return true;
        }
        if (item.getItemId() == 1) {
            showAllFiles = !showAllFiles;
            adapter = new FileListAdapter(this, dirs, files, showAllFiles);
            listView.setAdapter(adapter);
            Toast.makeText(this, showAllFiles
                    ? "现在显示所有文件（非 APK 点击无效）"
                    : "现在只显示 APK 文件", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
