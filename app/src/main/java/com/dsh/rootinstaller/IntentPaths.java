package com.dsh.rootinstaller;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

import java.io.File;

/**
 * 从外部传进来的 Intent 里把「APK 文件路径」挖出来。
 *
 * 这里要对付各种奇葩传参方式：
 *   - file:///sdcard/xx.apk            —— 最常见
 *   - content://...                    —— 部分应用用 FileProvider/内容提供者，需要查 _data 列
 *   - 什么都没有，只有 EXTRA 里塞字符串  —— 少数应用这么干
 */
public final class IntentPaths {

    private IntentPaths() {
    }

    /** 解析失败返回 null。 */
    public static String resolve(Context ctx, Intent intent) {
        // 1) EXTRA 里直接给了路径
        String[] extraKeys = new String[] {
                "android.intent.extra.INSTALLER_PACKAGE_NAME", // 不是路径，排除
                "apk_path", "path", "file_path", "filePath", "android.intent.extra.STREAM_PATH"
        };
        for (int i = 0; i < extraKeys.length; i++) {
            if ("android.intent.extra.INSTALLER_PACKAGE_NAME".equals(extraKeys[i])) {
                continue;
            }
            try {
                String v = intent.getStringExtra(extraKeys[i]);
                if (v != null && new File(v).isFile()) {
                    Log.i("从 extra[" + extraKeys[i] + "] 得到路径: " + v);
                    return v;
                }
            } catch (Throwable ignored) {
            }
        }

        // 2) data uri
        Uri data = intent.getData();
        if (data == null) {
            return null;
        }
        String scheme = data.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            String p = data.getPath();
            return p != null && new File(p).isFile() ? p : p;
        }
        if ("content".equalsIgnoreCase(scheme)) {
            return resolveContent(ctx, data);
        }
        return null;
    }

    /** content:// -> 真实路径。失败返回 null（调用方会给用户提示）。 */
    private static String resolveContent(Context ctx, Uri uri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[] { "_data" }, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex("_data");
                if (idx >= 0) {
                    String p = c.getString(idx);
                    if (p != null) {
                        Log.i("content uri 解析到路径: " + p);
                        return p;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w("解析 content uri 失败: " + t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        // 拿不到真实路径时，把 uri 字符串本身返回回去没用，
        // 明确返回 null，让上层提示用户手动选择。
        return null;
    }
}
