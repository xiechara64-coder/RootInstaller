package com.dsh.rootinstaller;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * 文件选择器的列表：目录在上、文件在下。
 * 只把 .apk 显示成「可安装」，其他文件灰掉但仍然可见（有些人会把 apk 改成别的后缀）。
 */
public class FileListAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<File> dirs;
    private final List<File> files;
    private final boolean showAllFiles;

    public FileListAdapter(Context ctx, List<File> dirs, List<File> files, boolean showAllFiles) {
        this.inflater = LayoutInflater.from(ctx);
        this.dirs = dirs;
        this.files = files;
        this.showAllFiles = showAllFiles;
    }

    @Override
    public int getCount() {
        return dirs.size() + files.size();
    }

    @Override
    public File getItem(int position) {
        if (position < dirs.size()) {
            return dirs.get(position);
        }
        return files.get(position - dirs.size());
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder h;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_file, parent, false);
            h = new ViewHolder();
            h.icon = (TextView) convertView.findViewById(R.id.icon);
            h.name = (TextView) convertView.findViewById(R.id.name);
            h.info = (TextView) convertView.findViewById(R.id.info);
            convertView.setTag(h);
        } else {
            h = (ViewHolder) convertView.getTag();
        }

        File f = getItem(position);
        boolean isDir = position < dirs.size();

        if (isDir) {
            h.icon.setText("📁");
            h.name.setText(f.getName());
            h.info.setText("目录");
            h.name.setTextColor(0xFF212121);
        } else {
            boolean isApk = f.getName().toLowerCase().endsWith(".apk");
            h.icon.setText(isApk ? "📦" : "📄");
            h.name.setText(f.getName());
            String date = DateFormat.format("yyyy-MM-dd HH:mm", f.lastModified()).toString();
            h.info.setText(ApkParser.humanSize(f.length()) + "   " + date
                    + (isApk ? "" : "   （非 APK）"));
            // 非 apk 灰显，避免误点
            h.name.setTextColor(isApk || showAllFiles ? 0xFF212121 : 0xFF9E9E9E);
        }
        return convertView;
    }

    private static class ViewHolder {
        TextView icon;
        TextView name;
        TextView info;
    }
}
