package com.dsh.rootinstaller;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 通过 su 以 root 身份执行命令。
 *
 * Android 4.3 时代的现实情况：su 的实现五花八门（Superuser / SuperSU / 各种 ROM 自带），
 * 所以这里刻意做了多级回退，而不是假设某一种写法一定成功。
 *
 * 关于「引号地狱」的说明（很重要）：
 *   - Command 数组形式（ProcessBuilder("su","-c",cmd)）在部分 su 上会把参数重新拼接，
 *     导致 cmd 里的空格被拆开，所以这里统一走 stdin 写命令行的方式；
 *   - 走 stdin 时没有 shell 再去帮我们剥引号，所以路径必须自己用单引号包好，
 *     并且把路径里的单引号转义成 '\'' ，否则路径含空格或特殊字符就会失败。
 */
public final class RootShell {

    /** 超时哨兵：exitCode 用它区分「命令超时」和「命令正常失败」。 */
    public static final int EXIT_TIMEOUT = -999;

    /** 一条命令的执行结果。 */
    public static final class Result {
        public final boolean success;
        public final int exitCode;
        public final List<String> lines;
        /** 原始输出拼接（stdout + stderr，按行）。 */
        public final String output;
        /** su 本身没起来时为 true，用于给用户提示「没有授权/没有 root」。 */
        public final boolean suUnavailable;

        Result(boolean success, int exitCode, List<String> lines, boolean suUnavailable) {
            this.success = success;
            this.exitCode = exitCode;
            this.lines = Collections.unmodifiableList(lines);
            this.suUnavailable = suUnavailable;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append(lines.get(i));
            }
            this.output = sb.toString();
        }

        public boolean isTimeout() {
            return !suUnavailable && exitCode == EXIT_TIMEOUT;
        }

        /** 输出里是否包含指定关键字（大小写不敏感）。 */
        public boolean outputContains(String needle) {
            if (needle == null) {
                return false;
            }
            return output.toLowerCase().contains(needle.toLowerCase());
        }
    }

    private RootShell() {
    }

    /** 把路径安全地包成 shell 单引号字符串。 */
    public static String quote(String path) {
        if (path == null) {
            return "''";
        }
        return "'" + path.replace("'", "'\\''") + "'";
    }

    /**
     * 执行一条需要在 root shell 里跑的命令。
     *
     * @param command      要执行的命令（不要自己加 su 前缀）
     * @param timeoutMs    超时，0 表示不限
     * @param captureStderr 是否把 stderr 重定向进 stdout
     */
    public static Result run(String command, long timeoutMs, boolean captureStderr) {
        String line = command;
        if (captureStderr) {
            // 用 exec 2>&1 的等价写法：把 stderr 也导到 stdout，便于统一解析 pm 的报错
            line = command + " 2>&1";
        }

        // 依次尝试几种调用 su 的写法。第一种是绝大多数 su 都支持的。
        String[][] variants = new String[][] {
                { "su", "-c", "sh" },
                { "su", "-c" },
                { "su", "-s", "/system/bin/sh" },
                { "su", "0", "sh" },
                { "su" }
        };

        Result last = null;
        for (int i = 0; i < variants.length; i++) {
            Result r = execOnce(variants[i], line, timeoutMs);
            last = r;
            if (r.suUnavailable) {
                // su 根本没起来（不存在 / 拒绝授权），换下一种写法
                Log.w("su 调用方式失败（" + join(variants[i]) + "），尝试下一种");
                continue;
            }
            // su 起来了并且命令已经执行（成功、失败、超时都算），直接返回结果
            return r;
        }
        return last != null ? last
                : new Result(false, -1, new ArrayList<String>(), true);
    }

    /** 探测设备是否真的有可用的 root。 */
    public static boolean isRootAvailable() {
        Result r = run("id", 15000L, true);
        if (r.suUnavailable) {
            return false;
        }
        return r.outputContains("uid=0") || r.exitCode == 0;
    }

    private static Result execOnce(String[] suArgs, String commandLine, long timeoutMs) {
        Process process = null;
        DataOutputStream stdin = null;
        BufferedReader reader = null;
        final List<String> lines = new ArrayList<String>();
        int exit = -1;
        boolean suUnavailable = false;

        try {
            CrashReporter.note("[RootShell] su 参数=" + join(suArgs)
                    + " 命令=" + commandLine);
            ProcessBuilder pb = new ProcessBuilder(suArgs);
            pb.redirectErrorStream(true);
            process = pb.start();

            stdin = new DataOutputStream(process.getOutputStream());
            // 关键：commandLine 末尾必须换行，否则 sh 不会执行
            stdin.writeBytes(commandLine + "\n");
            // 再补一个 exit，让非交互 shell 干净退出并交出退出码
            stdin.writeBytes("exit\n");
            stdin.flush();
            stdin.close();
            stdin = null;

            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String l;
            while ((l = reader.readLine()) != null) {
                lines.add(l);
                Log.i("shell> " + l);
            }

            if (timeoutMs > 0L) {
                exit = waitWithTimeout(process, timeoutMs);
            } else {
                exit = process.waitFor();
            }
        } catch (Exception e) {
            // su 不存在 / 被拒绝授权 / 权限被 SELinux 拦，都走这里
            suUnavailable = true;
            Log.e("执行 su 失败: " + join(suArgs), e);
            lines.add("su 执行失败: " + e);
        } finally {
            closeQuietly(stdin);
            closeQuietly(reader);
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }

        return new Result(!suUnavailable && exit == 0, exit, lines, suUnavailable);
    }

    /**
     * 带超时的 waitFor。
     * API 18 上没法在 Process 上直接定时，这里用轮询 + 到点强杀。
     */
    private static int waitWithTimeout(Process process, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException notYet) {
                if (System.currentTimeMillis() >= deadline) {
                    Log.w("命令超时（" + timeoutMs + "ms），强制结束进程");
                    process.destroy();
                    return EXIT_TIMEOUT;
                }
                Thread.sleep(50L);
            }
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

    private static String join(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(arr[i]);
        }
        return sb.toString();
    }
}
