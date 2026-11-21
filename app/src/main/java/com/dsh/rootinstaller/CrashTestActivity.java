package com.dsh.rootinstaller;

import android.app.Activity;
import android.os.Bundle;

/**
 * 自测用的 Activity：故意抛一个异常，用来验证崩溃记录器是否真的工作。
 *
 * 安全考量（重要）：
 *   这个 Activity **刻意不导出**（没有 intent-filter，也没有 exported="true"）。
 *   否则任何应用都能发一个 Intent 让本应用立刻崩溃 —— 那是一个拒绝服务漏洞。
 *
 *   所以要调起它必须用 root：
 *       adb shell su -c "am start -n com.dsh.rootinstaller/.CrashTestActivity"
 *
 *   触发后重新打开应用，应该会弹出「上次运行崩溃了」的对话框。
 *   这个类在流程验证通过后可以删除（见 README 的「崩溃排查」一节）。
 *
 *   注意：这里 **不能** 在 onCreate 里让进程提前退出。
 *   Android 4.3 对 startActivity 有 5 秒超时，如果进程立刻消失，
 *   系统会把这次启动判成失败，也不会给用户任何提示。
 *   直接抛异常才是真实的崩溃现场。
 */
public class CrashTestActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashReporter.note("用户主动触发崩溃自测");
        // 制造一个可控的崩溃现场
        throw new IllegalStateException(
                "这是崩溃记录器的自测异常，不是真实故障。如果看到这段文字，"
                        + "说明崩溃捕获链路是通的。");
    }
}
