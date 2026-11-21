package com.dsh.rootinstaller;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 真正干活的地方：用 root 权限执行 pm install。
 *
 * 为什么要有这个 Service，而不是在 Activity 里开个线程？
 *   1. 安装可能持续几十秒，用户可能按 Home 退出界面，Service 能继续跑完；
 *   2. 前台 Service 有通知，用户能看到「正在安装」，不会以为卡死；
 *   3. 安装结果统一从广播回传，Activity 在不在都能收到。
 *
 * 关键实现细节（踩过的坑都在这）：
 *   - 必须先把 APK 复制到本应用私有目录（/data/data/<包名>/cache/）。
 *     pm install 是以 shell/root 身份跑的，如果直接读 /sdcard 下别人目录里的文件，
 *     很容易撞上权限或 SELinux 问题；放到自己私有目录后路径一定可读。
 *   - 路径要用单引号包起来，因为命令是通过 stdin 喂给 sh 的，没有第二层 shell 帮我们转义。
 */
public class InstallService extends Service {

    public static final String ACTION_INSTALL = "com.dsh.rootinstaller.action.INSTALL";
    public static final String ACTION_STATUS = "com.dsh.rootinstaller.action.STATUS";

    public static final String EXTRA_APK = "apk";
    public static final String EXTRA_DOWNGRADE = "allow_downgrade";

    /** 状态阶段。 */
    public static final String EXTRA_PHASE = "phase";
    public static final String PHASE_COPYING = "copying";
    public static final String PHASE_INSTALLING = "installing";
    public static final String PHASE_DONE = "done";
    public static final String PHASE_FAILED = "failed";
    public static final String PHASE_NO_ROOT = "no_root";

    /** 回传详情：pm 的原始输出。 */
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_SUCCESS = "success";
    public static final String EXTRA_PACKAGE = "pkg";

    private static final int NOTIFICATION_ID = 0x5249; // 'RI'

    private InputStream copyIn;
    private OutputStream copyOut;
    private volatile boolean cancelled;

    // ------------------------------------------------------------------
    // 对外入口：友好封装，Activity 不用关心 Intent 拼装
    // ------------------------------------------------------------------

    public static void startInstall(Context ctx, String apkPath, boolean allowDowngrade) {
        Intent i = new Intent(ctx, InstallService.class);
        i.setAction(ACTION_INSTALL);
        i.putExtra(EXTRA_APK, apkPath);
        i.putExtra(EXTRA_DOWNGRADE, allowDowngrade);
        ctx.startService(i);
    }

    // ------------------------------------------------------------------

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_INSTALL.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        final String apk = intent.getStringExtra(EXTRA_APK);
        final boolean allowDowngrade = intent.getBooleanExtra(EXTRA_DOWNGRADE, false);

        // 上一次任务结束时 onDestroy 可能把 cancelled 置过 true，这里重置，
        // 否则紧接着的第二次安装会被立刻中断。
        cancelled = false;

        if (apk == null) {
            broadcast(PHASE_FAILED, false, null, "没有拿到 APK 路径");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // 放进独立线程，绝不能在主线程碰 su
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                doInstall(apk, allowDowngrade);
            }
        }, "root-install");
        t.start();

        return START_NOT_STICKY;
    }

    private void doInstall(String srcApk, boolean allowDowngrade) {
        String copied = null;
        File stagingFile = null;
        try {
            File src = new File(srcApk);
            if (!src.isFile()) {
                broadcast(PHASE_FAILED, false, null, "文件不存在：" + srcApk);
                return;
            }
            CrashReporter.note("[InstallService] 开始安装 src=" + srcApk
                    + " size=" + src.length() + " 允许降级=" + allowDowngrade);

            // ---- 步骤 1：探测 root ----
            broadcast(PHASE_COPYING, true, null, "正在检查 root 权限…");
            CrashReporter.note("[InstallService] 步骤1: 探测 root");
            if (!RootShell.isRootAvailable()) {
                Log.w("root 不可用");
                CrashReporter.note("[InstallService] root 不可用，终止");
                broadcast(PHASE_NO_ROOT, false, null,
                        "没有取得 root 权限。请确认设备已 root，"
                                + "并在授权弹窗里允许「RootInstaller」获得权限。\n\n"
                                + "提示：如果弹窗一闪而过或根本没出现，"
                                + "请打开你的授权管理应用（Superuser / SuperSU），"
                                + "手动把本应用设为「允许」。");
                return;
            }
            CrashReporter.note("[InstallService] root 可用");

            // ---- 步骤 2：把 APK 放到 root shell 一定能读到、且 pm 一定接受的路径 ----
            //
            // 路径选择很讲究，实测结论：
            //   · /data/local/tmp/  → pm 接受（标准做法，首选）
            //   · 应用私有目录      → 部分 ROM 报 INSTALL_FAILED_INVALID_URI
            //     （华为 C8816D / 4.3 实测如此，所以不作为首选）
            //
            // 关键：**中文文件名不能直接交给 shell**。
            // Java 在 4.3 上把中文路径按默认字符集（GBK）编码后传给 shell，
            // 而文件系统用的是 UTF-8，于是 cp 会报 "No such file or directory"。
            // 解决办法是分两步：应用自己复制到私有缓存（应用内路径不经过 shell），
            // 再用 root 把这个纯 ASCII 的临时文件移到目标位置。
            File cacheDir = new File(getCacheDir(), "install");
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                broadcast(PHASE_FAILED, false, null, "无法创建缓存目录：" + cacheDir);
                return;
            }
            broadcast(PHASE_COPYING, true, null,
                    "正在准备安装包…\n" + ApkParser.humanSize(src.length()));

            File staging = new File(cacheDir, "staging.apk");
            stagingFile = staging;
            CrashReporter.note("[InstallService] 步骤2a: 应用内复制到 " + staging);
            if (!copyFile(src, staging)) {
                broadcast(PHASE_FAILED, false, null, "复制安装包失败（存储空间不足？）");
                return;
            }
            long expectedSize = src.length();

            String tmpPath = "/data/local/tmp/ri_install.apk";
            copied = tmpPath;
            CrashReporter.note("[InstallService] 步骤2b: root 移动到 " + tmpPath
                    + " 期望大小=" + expectedSize);
            boolean staged = moveAsRoot(staging, tmpPath, expectedSize);

            if (!staged) {
                // 退回私有目录：直接让 pm 从 staging 装
                copied = staging.getAbsolutePath();
                CrashReporter.note("[InstallService] /data/local/tmp 不可用，退回 " + copied);
                try {
                    staging.setReadable(true, false);
                } catch (Throwable ignored) {
                }
            }
            CrashReporter.note("[InstallService] 复制完成: " + copied);

            // ---- 步骤 3：执行 pm install ----
            broadcast(PHASE_INSTALLING, true, null, "正在安装…（可能需要几十秒）");
            CrashReporter.note("[InstallService] 步骤3: 执行 pm install");
            RootShell.Result r = runPmInstall(copied, allowDowngrade);
            CrashReporter.note("[InstallService] pm 返回 exitCode=" + r.exitCode
                    + " suUnavailable=" + r.suUnavailable);

            if (r.suUnavailable) {
                broadcast(PHASE_NO_ROOT, false, null, "su 执行失败，root 不可用。\n\n" + r.output);
                return;
            }
            if (r.isTimeout()) {
                broadcast(PHASE_FAILED, false, null,
                        "安装超时（180 秒）。\n\n可能是安装过程卡住了，"
                                + "也可能是授权弹窗在等你点确认。\n\n输出：\n" + r.output);
                return;
            }

            // 判定成败。
            // 特别注意：pm install 失败时**退出码也可能是 0**，
            // 真正可靠的信号是输出里的 "Success" / "Failure"。
            // 早先版本写成 `outputContains("Success") || exitCode == 0`，
            // 结果把 "Failure [...]" 也判成了成功 —— 这个错误很隐蔽，必须按下面这样判。
            boolean ok = isPmSuccess(r);
            CrashReporter.note("[InstallService] 判定结果 ok=" + ok);
            broadcast(ok ? PHASE_DONE : PHASE_FAILED, ok, null,
                    friendlyPmMessage(r, ok));
        } catch (Throwable t) {
            Log.e("安装过程异常", t);
            CrashReporter.note("[InstallService] 安装过程异常: " + t);
            broadcast(PHASE_FAILED, false, null, "安装过程异常：" + t);
        } finally {
            // 清理临时文件。
            // /data/local/tmp 下的文件属于 shell 用户，应用自己删不掉，得借 root；
            // 私有目录里的 staging 则直接删。
            if (stagingFile != null) {
                try {
                    stagingFile.delete();
                } catch (Throwable ignored) {
                }
            }
            if (copied != null) {
                try {
                    File f = new File(copied);
                    if (!f.delete()) {
                        // 删不掉就用 root 删（典型情况就是 /data/local/tmp 里的那份）
                        RootShell.run("rm -f " + RootShell.quote(copied), 20000L, true);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 依次尝试几种 pm install 参数组合。
     *
     * 为什么不全用 -r 一次搞定？因为不同 ROM 的 pm 对参数的支持程度不一样：
     *   - -r 允许覆盖安装（同版本或更高版本）
     *   - -d 允许降级（不是所有 ROM 都认这个参数）
     *   - -t 允许带 testOnly 标记的包（debug 包常见）
     * 所以按「最小必要」的顺序试，成功了就停。
     */
    private RootShell.Result runPmInstall(String apk, boolean allowDowngrade) {
        String q = RootShell.quote(apk);
        String base = "pm install -r";
        if (allowDowngrade) {
            base = base + " -d";
        }

        String[] attempts = new String[] {
                base + " " + q,
                base + " -t " + q,
                "pm install " + q,          // 极老 ROM 不认识 -r 时的兜底
        };

        RootShell.Result last = null;
        for (int i = 0; i < attempts.length; i++) {
            Log.i("尝试执行: " + attempts[i]);
            RootShell.Result r = RootShell.run(attempts[i], 180000L, true);
            last = r;

            if (r.suUnavailable || r.isTimeout()) {
                return r;
            }
            String out = r.output.toLowerCase();
            if (r.outputContains("Success") || r.exitCode == 0) {
                return r;
            }
            // 参数不被支持时才换下一种；真正的安装失败（签名冲突等）没必要重试
            boolean paramProblem = out.contains("unknown option")
                    || out.contains("unknown command")
                    || out.contains("usage:")
                    || out.contains("no such file or directory: -");
            if (!paramProblem) {
                return r;
            }
            Log.w("参数不被支持，换下一种写法");
        }
        return last;
    }

    /**
     * 判断 pm install 到底成没成功。
     *
     * 这是踩过坑的地方，规则必须严格：
     *   1. 输出里出现 "Failure" —— 一定失败（哪怕退出码是 0）；
     *   2. 输出里出现 "Success" —— 成功；
     *   3. 两者都没有 —— 保守判为失败，并把原始输出给用户看。
     *
     * 之前写成 `exitCode == 0` 就算成功是错的：实测这个华为 ROM 上
     * `pm install` 失败时退出码依然是 0，于是 "Failure [INSTALL_FAILED_INVALID_URI]"
     * 被当成了安装成功，用户会以为装上了。
     */
    private static boolean isPmSuccess(RootShell.Result r) {
        if (r == null) {
            return false;
        }
        String out = r.output == null ? "" : r.output;
        if (out.indexOf("Failure") >= 0) {
            return false;
        }
        return out.indexOf("Success") >= 0;
    }

    /** 把 pm 的英文报错翻译成中文人话。 */
    private static String friendlyPmMessage(RootShell.Result r, boolean ok) {
        String raw = r.output;
        String low = raw.toLowerCase();
        StringBuilder sb = new StringBuilder();

        if (ok) {
            sb.append("安装成功。");
        } else if (low.contains("install_failed_invalid_uri")) {
            sb.append("安装失败：这个 ROM 不接受从应用私有目录安装（INSTALL_FAILED_INVALID_URI）。\n\n")
              .append("程序已自动改用 /data/local/tmp 重试；如果仍然失败，\n")
              .append("可能是 pm 命令的路径限制，可以试试用 adb install 手动装。");
        } else if (low.contains("install_failed_already_exists")) {
            sb.append("安装失败：应用已存在，且不允许覆盖。");
        } else if (low.contains("install_failed_version_downgrade")) {
            sb.append("安装失败：这是降级安装（版本号低于已装版本）。\n")
              .append("请勾选「允许降级」后重试。");
        } else if (low.contains("install_failed_update_incompatible")) {
            sb.append("安装失败：与已安装版本不兼容（包名相同但签名不同）。\n")
              .append("请先卸载已安装的版本再装。");
        } else if (low.contains("install_failed_inconsistent_certificates")
                || low.contains("install_parse_failed_no_certificates")) {
            sb.append("安装失败：签名冲突或无签名。\n")
              .append("包名相同但签名不一致时，必须先卸载旧版本。");
        } else if (low.contains("install_failed_insufficient_storage")) {
            sb.append("安装失败：存储空间不足。");
        } else if (low.contains("install_failed_older_sdk")) {
            sb.append("安装失败：应用要求的最低系统版本高于当前系统。");
        } else if (low.contains("install_failed_no_shared_user")) {
            sb.append("安装失败：缺少共享用户（sharedUserId）配置。");
        } else if (low.contains("install_failed_invalid_apk")
                || low.contains("install_parse_failed")) {
            sb.append("安装失败：APK 已损坏或无法解析。\n")
              .append("请确认文件完整下载（可对比 MD5）。");
        } else if (low.contains("install_failed_internal_error")) {
            sb.append("安装失败：系统内部错误。\n")
              .append("可以试试重启设备，或换用「先复制到 /data/local/tmp 再装」的方式。");
        } else if (low.contains("failure")) {
            sb.append("安装失败。");
        } else {
            sb.append("安装结束，但没能确认结果。退出码 ").append(r.exitCode).append("。");
        }

        if (raw != null && raw.trim().length() > 0) {
            sb.append("\n\n—— pm 原始输出 ——\n").append(raw.trim());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------

    /**
     * 用 root 把一个纯 ASCII 的临时文件搬到 /data/local/tmp/，并**校验文件大小**。
     *
     * 为什么必须校验大小？
     *   曾经这里只判断 `ls` 的输出里有没有文件名，结果踩了坑：
     *   上一次安装残留的 ri_install.apk 还在，cp 明明失败了，
     *   却因为看到了这个旧文件而判定「复制成功」，
     *   最终把**上一个 APK** 装了进去还报告成功。
     *   用期望字节数比对，才能真正确认这次搬的是这次的包。
     *
     * @param expectedSize 期望的字节数（取自源文件）
     * @return true 表示目标文件存在且大小正确
     */
    private boolean moveAsRoot(File staging, String dstPath, long expectedSize) {
        try {
            String srcQ = RootShell.quote(staging.getAbsolutePath());
            String dstQ = RootShell.quote(dstPath);

            // 先删掉可能残留的旧文件，避免任何形式的「陈旧数据」误判
            RootShell.run("rm -f " + dstQ, 20000L, true);
            // cp 之后再 chmod，保证 pending 安装时能读到
            RootShell.run("cp " + srcQ + " " + dstQ, 180000L, true);
            RootShell.run("chmod 644 " + dstQ, 20000L, true);

            // 用 wc -c 拿到目标文件的真实大小并比对
            RootShell.Result check = RootShell.run("wc -c " + dstQ, 30000L, true);
            long actual = -1;
            if (check != null && check.output != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(\\d+)").matcher(check.output);
                if (m.find()) {
                    try {
                        actual = Long.parseLong(m.group(1));
                    } catch (Throwable ignored) {
                    }
                }
            }
            boolean ok = actual == expectedSize;
            Log.i("moveAsRoot: 期望=" + expectedSize + " 实际=" + actual + " 结果=" + ok
                    + " 输出=" + (check == null ? "null" : check.output));
            CrashReporter.note("[InstallService] moveAsRoot 期望=" + expectedSize
                    + " 实际=" + actual + " ok=" + ok);
            return ok;
        } catch (Throwable t) {
            Log.e("moveAsRoot 失败", t);
            return false;
        }
    }

    /** 复制文件，失败返回 false。 */
    private boolean copyFile(File src, File dst) {
        try {
            copyIn = new FileInputStream(src);
            copyOut = new FileOutputStream(dst, false);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = copyIn.read(buf)) > 0) {
                if (cancelled) {
                    return false;
                }
                copyOut.write(buf, 0, n);
            }
            copyOut.flush();
            return true;
        } catch (Throwable t) {
            Log.e("复制文件失败", t);
            return false;
        } finally {
            closeQuietly(copyIn);
            closeQuietly(copyOut);
            copyIn = null;
            copyOut = null;
        }
    }

    private void broadcast(String phase, boolean success, String pkg, String message) {
        Log.i("阶段=" + phase + " 成功=" + success + " 说明=" + message);

        // 前台通知，让用户知道后台在干活（仅安装中显示）
        if (PHASE_INSTALLING.equals(phase)) {
            showNotification("正在安装应用…");
        }

        Intent i = new Intent(ACTION_STATUS);
        i.putExtra(EXTRA_PHASE, phase);
        i.putExtra(EXTRA_SUCCESS, success);
        i.putExtra(EXTRA_PACKAGE, pkg);
        i.putExtra(EXTRA_MESSAGE, message);
        // 用包名限定，避免泄漏给其它应用
        i.setPackage(getPackageName());
        sendBroadcast(i);

        if (PHASE_DONE.equals(phase) || PHASE_FAILED.equals(phase)
                || PHASE_NO_ROOT.equals(phase)) {
            hideNotification();
            // 注意：这个方法是在后台线程里被调用的（doInstall 跑在 worker 线程），
            // 而 Toast 必须在主线程 show —— 直接在后台线程调会在部分 ROM 上崩溃。
            // 所以这里 post 回主线程。
            final boolean okFinal = success;
            try {
                new android.os.Handler(getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Toast.makeText(getApplicationContext(),
                                    okFinal ? "安装成功" : "安装失败，请看详情",
                                    Toast.LENGTH_LONG).show();
                        } catch (Throwable t) {
                            Log.w("显示 Toast 失败: " + t);
                        }
                    }
                });
            } catch (Throwable t) {
                Log.w("post Toast 失败: " + t);
            }
            stopSelf();
        }
    }

    private boolean foregroundStarted;

    /**
     * 显示「正在安装」的前台通知。
     *
     * 这里必须按版本分两条路，原因是一个很容易踩的坑：
     *
     *   旧写法是 `n.setLatestEventInfo(...)`，这个 API **在 API 23 (Android 6.0)
     *   被彻底移除了**。它被 try/catch 包着，所以在 6.0+ 上不会崩，
     *   但会在 setLatestEventInfo 那一行抛 NoSuchMethodError，
     *   导致后面的 startForeground() **永远不会执行** ——
     *   也就是前台服务没建立起来。这种「不崩溃但静默失效」比直接崩更难发现：
     *   Android 8.0+ 对后台服务的存活限制很严，没有前台通知的话，
     *   安装服务可能中途被系统干掉。
     *
     *   Notification.Builder 从 API 11 就有，所以用它覆盖 API 15+ 是安全的，
     *   同时在高版本上也是受支持的正路。只有 API 11 以下才需要退回旧 API，
     *   而本应用 minSdk 是 15，那条分支只是为了完整性保留。
     */
    private void showNotification(String text) {
        try {
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT);

            Notification n;
            if (Build.VERSION.SDK_INT >= 11) {
                // API 11+：受支持的正路，且在高版本上仍然有效
                n = new Notification.Builder(this)
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setTicker(text)
                        .setWhen(System.currentTimeMillis())
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(text)
                        .setContentIntent(pi)
                        .setOngoing(true)
                        .getNotification();
            } else {
                // API 11 以下（本项目不会走到，minSdk 15），保留旧写法
                n = new Notification();
                n.icon = R.drawable.ic_launcher;
                n.tickerText = text;
                n.when = System.currentTimeMillis();
                n.flags |= Notification.FLAG_ONGOING_EVENT;
                n.setLatestEventInfo(this, getString(R.string.app_name), text, pi);
            }

            startForeground(NOTIFICATION_ID, n);
            foregroundStarted = true;
            Log.i("前台通知已启动");
        } catch (Throwable t) {
            // 通知失败不能影响安装本身，但要把原因记清楚
            Log.w("显示通知失败: " + t);
        }
    }

    private void hideNotification() {
        if (!foregroundStarted) {
            return;
        }
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        foregroundStarted = false;
    }

    @Override
    public void onDestroy() {
        cancelled = true;
        closeQuietly(copyIn);
        closeQuietly(copyOut);
        super.onDestroy();
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
