package com.dsh.rootinstaller;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限含义查询。
 *
 * 系统安装器只列权限字符串，用户在 Android 4.3 上根本看不懂
 * "android.permission.READ_PHONE_STATE" 到底意味着什么。
 *
 * 这里做两件事：
 *   1. 从 PackageManager 里把每个 permission 的中文/系统 label、描述、保护级别查出来；
 *   2. 查不到时（自定义权限、残缺 ROM）退化成一个内置的常用权限中文说明表。
 *
 * 注意 loadPermissions 可能很慢（部分 ROM 上上千条），所以调用方必须放子线程。
 */
public final class Perms {

    /** 一条权限的展示信息。 */
    public static final class Info {
        public final String name;
        public final String label;
        public final String description;
        public final String protection;
        public final boolean dangerous;
        /**
         * 所属权限组的标识（例如 android.permission-group.ACCOUNTS）。
         *
         * 系统自带安装器就是按这个分组的：组说明当标题、组内权限列在下面。
         * 应用自定义权限（com.xxx.permission.YYY）通常没有组，为 null。
         */
        public final String group;
        /** 权限组的说明，例如「查找设备上的账户」。系统安装器用它当分组标题。 */
        public final String groupDescription;

        Info(String name, String label, String description, String protection,
             boolean dangerous, String group, String groupDescription) {
            this.name = name;
            this.label = label;
            this.description = description;
            this.protection = protection;
            this.dangerous = dangerous;
            this.group = group;
            this.groupDescription = groupDescription;
        }

        /**
         * 给外部（离线自检）构造用。
         *
         * 构造器本身保持包内可见，界面代码不该随便造 Info；
         * 这个工厂是给 PermSortCheck 这类脱离设备的验证程序用的。
         */
        public static Info create(String name, String label, String description,
                                  String protection, boolean dangerous) {
            return new Info(name, label, description, protection, dangerous, null, null);
        }
    }

    private static Map<String, Info> cache;

    private Perms() {
    }

    /**
     * 把所有已知权限摊平成数组，供差异诊断之类的遍历使用。
     * 必须在子线程调用（会触发 load）。
     */
    public static Info[] allByName(Context ctx) {
        Map<String, Info> table = load(ctx);
        return table.values().toArray(new Info[table.size()]);
    }

    /** 必须在子线程调用。 */
    public static synchronized Map<String, Info> load(Context ctx) {
        if (cache != null) {
            return cache;
        }
        Map<String, Info> map = new HashMap<String, Info>();
        PackageManager pm = ctx.getPackageManager();
        try {
            List<PackageInfo> pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
            if (pkgs != null) {
                for (int i = 0; i < pkgs.size(); i++) {
                    PermissionInfo[] pis = pkgs.get(i).permissions;
                    if (pis == null) {
                        continue;
                    }
                    for (int j = 0; j < pis.length; j++) {
                        PermissionInfo pi = pis[j];
                        if (pi == null || pi.name == null || map.containsKey(pi.name)) {
                            continue;
                        }
                        map.put(pi.name, fromPermissionInfo(pm, pi));
                    }
                }
            }
        } catch (Throwable t) {
            Log.e("枚举已安装权限失败，将只使用内置表", t);
        }
        cache = map;
        Log.i("权限表加载完成，共 " + map.size() + " 条");
        return map;
    }

    private static Info fromPermissionInfo(PackageManager pm, PermissionInfo pi) {
        String label = null;
        String desc = null;
        try {
            CharSequence c = pi.loadLabel(pm);
            if (c != null) {
                label = c.toString();
            }
        } catch (Throwable ignored) {
        }
        try {
            CharSequence c = pi.loadDescription(pm);
            if (c != null) {
                desc = c.toString();
            }
        } catch (Throwable ignored) {
        }
        int level = pi.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
        // 判定的先后关系抽到 resolveJudgment 里 —— 见那个方法的注释。
        // 抽出来是为了能离线验证：否则这段逻辑只有真机跑得到，等于没测过。
        int[] verdict = resolveJudgment(pi.name, level);
        boolean dangerous = verdict[0] == 1;
        String protection = protectionName(verdict[1]);

        // 权限组：系统自带安装器就用它分组显示（组说明当标题）。
        // 应用自定义权限通常没有组，这里会保持为 null。
        String group = pi.group;
        String groupDesc = null;
        if (group != null) {
            try {
                PermissionGroupInfo g = pm.getPermissionGroupInfo(group, 0);
                if (g != null) {
                    CharSequence c = g.loadDescription(pm);
                    if (c != null && c.length() > 0) {
                        groupDesc = c.toString();
                    } else {
                        c = g.loadLabel(pm);
                        if (c != null && c.length() > 0) {
                            groupDesc = c.toString();
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 组信息查不到不影响主体功能
            }
        }
        return new Info(pi.name, label, desc, protection, dangerous,
                group, groupDesc);
    }

    /** 保护级别转中文。 */
    public static String protectionName(int baseLevel) {
        switch (baseLevel) {
            case PermissionInfo.PROTECTION_DANGEROUS:
                return "危险（涉及隐私/安全）";
            case PermissionInfo.PROTECTION_NORMAL:
                return "普通（风险较低）";
            case PermissionInfo.PROTECTION_SIGNATURE:
                return "签名级（仅同签名应用可用）";
            case PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM:
                return "签名或系统级";
            default:
                return "未知级别";
        }
    }

    /**
     * 查一条权限的展示信息：优先用系统表，查不到用内置中文表，再查不到就返回 null。
     */
    public static Info lookup(Map<String, Info> systemTable, String permission) {
        if (systemTable != null) {
            Info i = systemTable.get(permission);
            if (i != null) {
                return i;
            }
        }
        String shortName = shortName(permission);
        String builtin = BUILTIN.get(shortName);
        if (builtin != null) {
            // 内置兜底表里没有权限组信息，组保持 null；
            // 但**危险判定必须走同一个裁决**，不能在这里另写一份。
            //
            // 踩过的坑：这里原先硬编码 "未知级别"，于是同一个 INTERNET
            // 走系统表时判「普通」、走兜底时判「未知级别」，
            // riskRank 跟着从 2 档掉到 4 档（被当成"看不懂的权限"排到最后）。
            // 同一判定有两套实现，正是本次那个自相矛盾的成因，这里一并统一。
            int[] verdict = resolveJudgment(permission, PermissionInfo.PROTECTION_NORMAL);
            return new Info(permission, shortName, builtin, protectionName(verdict[1]),
                    verdict[0] == 1, null, null);
        }
        return null;
    }

    /**
     * 权限的风险排序权重，用于「按风险等级」排序。
     *
     * 数值越小越靠前。顺序按用户要求：危险 → 普通 → 签名级 → 无说明。
     *
     * 这里把「无说明」单独放最后，而不是塞进「普通」：
     * 系统表里查不到、内置表里也没有的权限，用户完全无法判断它做什么，
     * 混在普通权限里反而容易让人误以为「看着挺正常」。
     */
    public static int riskRank(Map<String, Info> systemTable, String permission) {
        Info info = lookup(systemTable, permission);
        if (info == null) {
            return 4; // 无说明
        }
        if (info.dangerous) {
            return 1; // 危险
        }
        String p = info.protection == null ? "" : info.protection;
        if (p.contains("签名")) {
            return 3; // 签名级
        }
        if (p.contains("普通")) {
            return 2; // 普通
        }
        // 保护级别读不出来（可能是 ROM 没提供），归到「无说明」一档
        return 4;
    }

    /** android.permission.READ_SMS -> READ_SMS */
    public static String shortName(String permission) {
        if (permission == null) {
            return "";
        }
        int dot = permission.lastIndexOf('.');
        return dot >= 0 && dot + 1 < permission.length() ? permission.substring(dot + 1) : permission;
    }

    private static boolean isLikelyDangerous(String shortName) {
        return BUILTIN_DANGEROUS.contains(shortName);
    }

    /**
     * 裁决一条权限的「危险与否」和「保护级别展示文字」。
     *
     * <p><b>为什么需要这个裁决</b>：系统的 {@code protectionLevel} 回答的是
     * 「系统要不要在运行时向用户申请这条权限」，而本应用要回答的是
     * 「这条权限对用户有没有隐私风险」。**两者不是同一件事**，
     * 典型分歧就是 INTERNET：系统把它标成 {@code PROTECTION_DANGEROUS}，
     * 但本应用**故意不把它算作危险权限** —— 只联网本身不构成隐私风险，
     * 真正的风险是「能读隐私」**且**「能联网」，那个组合判断由
     * {@link PermAbilities} 专门负责。
     *
     * <p><b>踩过的坑</b>：早先只在内置表里写了 {@code put("INTERNET", ...)}
     * （即非 {@code putDangerous}），以为就把它排除在危险之外了。
     * 但 {@link #lookup} **优先返回系统表**，而系统表是按
     * {@code protectionLevel} 构造的，那个 {@code dangerous=true} 于是一路透到两处：
     * <ul>
     *   <li>详情页把 INTERNET 渲染成红色 + {@code [危险（涉及隐私/安全）]}</li>
     *   <li>{@link PermAbilities#summarize} 拿它数「危险权限」个数</li>
     * </ul>
     * 结果自相矛盾：「简单说」写着「没有命中已知的隐私读取行为」，
     * 明细里 INTERNET 却标着危险。同一类偏差还散落在
     * {@code ApkParser.isNotablePermission}（权限差异筛选）里。
     *
     * <p><b>规则</b>：内置表**提到过的**权限由内置表说了算（可以加重、也可以减轻）；
     * 内置表**没提到**的才退回系统判定 —— 否则会把系统标为危险的冷门权限漏报成安全。
     *
     * <p>返回 {@code int[]{dangerous ? 1 : 0, 保护级别}}。
     * 之所以不用一个自定义小类：这里只需要两个值，为此多开一个类型不值当。
     *
     * <p>可见性设为 public 是**为了离线自检**（和 {@link Info#create} 同样的理由）：
     * 这段优先级逻辑只有真机跑得到，而这个 bug 恰恰就是它出错造成的，
     * 不放出来就没法在 PC 上把它钉住。
     *
     * @param permission 权限全名（{@code android.permission.XXX}）
     * @param systemLevel 系统给出的保护级别（已与 {@code PROTECTION_MASK_BASE} 做过与运算）
     */
    public static int[] resolveJudgment(String permission, int systemLevel) {
        int rank = builtinRiskRank(permission);
        if (rank == BUILTIN_DANGEROUS_RANK) {
            return new int[]{1, PermissionInfo.PROTECTION_DANGEROUS};
        }
        if (rank == BUILTIN_NOT_DANGEROUS_RANK) {
            return new int[]{0, PermissionInfo.PROTECTION_NORMAL};
        }
        boolean sysDangerous = systemLevel == PermissionInfo.PROTECTION_DANGEROUS;
        return new int[]{sysDangerous ? 1 : 0, systemLevel};
    }

    /**
     * 内置表里是否**明确提到过**这条权限。
     *
     * <p>它被用作「内置表要不要覆盖系统判定」的开关，所以要区分两种「查不到」：
     * <ul>
     *   <li>内置表里**没有**它 → 返回 false → 沿用系统的危险判定（宁可保守，也不漏报）</li>
     *   <li>内置表里**有**它 → 返回 true → 用本应用的风险模型裁决
     *       （既可能是「加重」，也可能是像 INTERNET 那样「减轻」）</li>
     * </ul>
     *
     * <p>注意这里用 {@code containsKey} 而不是「取出来的值是否非空」——
     * 表里存在只登记风险级别、不提供中文说明的条目，
     * 用值判空会把它们误判成「没提到」，从而丢掉本该有的覆盖。
     */
    public static boolean isNotablePermission(String permission) {
        return permission != null && BUILTIN.containsKey(shortName(permission));
    }

    // ---- 内置风险模型的裁决结果（给「权限差异」筛选用的三态）----

    /** 内置表明确标为危险。 */
    public static final int BUILTIN_DANGEROUS_RANK = 1;
    /** 内置表提到了、但**故意不算危险**（例如 INTERNET）。 */
    public static final int BUILTIN_NOT_DANGEROUS_RANK = 2;
    /** 内置表没覆盖，无法用本应用的风险模型裁决。 */
    public static final int BUILTIN_UNKNOWN_RANK = 0;

    /**
     * 用**本应用的风险模型**裁决一条权限是否危险。
     *
     * <p>三态而不是布尔，是因为「内置表没覆盖」和「内置表说它不危险」
     * 必须分开处理：前者要退回系统判定（否则会漏报冷门危险权限），
     * 后者要**压过**系统判定（INTERNET 就属于这种）。
     *
     * <p>命名上刻意不叫 {@code isNotable} —— {@link PermDiff.Classifier#isNotable}
     * 是同签名的 SAM 方法，两个同名重载会因类型擦除冲突而无法编译。
     */
    public static int builtinRiskRank(String permission) {
        if (permission == null) {
            return BUILTIN_UNKNOWN_RANK;
        }
        String s = shortName(permission);
        if (!BUILTIN.containsKey(s)) {
            return BUILTIN_UNKNOWN_RANK;
        }
        return BUILTIN_DANGEROUS.contains(s)
                ? BUILTIN_DANGEROUS_RANK : BUILTIN_NOT_DANGEROUS_RANK;
    }

    // ------------------------------------------------------------------
    // 内置兜底表：只覆盖 4.3 时代最常见、也是用户最该警惕的那些权限。
    // ------------------------------------------------------------------
    private static final Map<String, String> BUILTIN = new HashMap<String, String>();

    private static final java.util.Set<String> BUILTIN_DANGEROUS =
            new java.util.HashSet<String>();

    private static void put(String name, String desc) {
        BUILTIN.put(name, desc);
    }

    private static void putDangerous(String name, String desc) {
        BUILTIN.put(name, desc);
        BUILTIN_DANGEROUS.add(name);
    }

    static {
        // 注意：INTERNET **故意不算危险权限**。
        //
        // 它单独存在时没有任何隐私风险 —— 只是「能联网」，而几乎所有应用都要联网。
        // 真正的风险来自「读取隐私的能力」+「能联网」同时具备，
        // 那个组合判断由 PermAbilities 专门负责。
        //
        // 早先把它标成危险，导致只有 INTERNET 的应用也被判成「低风险」，
        // 汇总里还会凑出一个莫名其妙的「1 项敏感权限」。
        put("INTERNET", "访问网络，可能上传或下载数据");

        // 下面这批**同样故意不算危险权限**，理由和 INTERNET 是一类：
        // 它们只是「读写设备自身的状态」，拿不到任何用户数据，也控制不了别的东西。
        //
        // 为什么必须显式登记（而不是干脆不写）：本类的裁决规则是
        // 「内置表提到过的由内置表说了算，没提到的才退回系统判定」。
        // 这些权限在系统里恰好都是 PROTECTION_DANGEROUS —— 如果内置表不提它们，
        // 就会退回系统判定、被判成危险，于是老问题原样复发。
        //
        // 踩过的坑（第二轮）：修 INTERNET 时只处理了它一条，结果用户换了个
        // 只申请 INTERNET + ACCESS_NETWORK_STATE 的包，同样的矛盾又出现一次
        // ——明细里 ACCESS_NETWORK_STATE 标「危险」，汇总却说「低风险」。
        // 根子不在某一条权限，而在**这类「系统要求运行时授权、但零隐私含义」的权限
        // 被整批当成了风险**。所以这里一次把这一类理清。
        //
        // 判定尺度：**这条权限能不能拿到用户数据，或控制用户会察觉的东西？**
        //   读网络/Wi-Fi 状态、振动、保持唤醒、闪光灯、锁屏幕方向、收合状态栏
        //   —— 全是设备自身状态，一律不算风险（下面这些）。
        //   但能**改**网络状态、能读 Wi-Fi 细节、能用蓝牙/NFC 通信的，仍然算敏感
        //   —— 它们要么能改设备行为，要么能对外通信，留在上面的 putDangerous 里。
        put("ACCESS_NETWORK_STATE", "读取网络连接状态");
        put("CHANGE_WIMAX_STATE", "开关并修改 WiMax 状态");
        put("VIBRATE", "控制振动器");
        put("WAKE_LOCK", "阻止设备进入休眠，会明显增加耗电");
        put("FLASHLIGHT", "使用闪光灯");
        put("SET_ORIENTATION", "锁定屏幕方向");
        put("EXPAND_STATUS_BAR", "展开或收起状态栏");

        putDangerous("READ_PHONE_STATE", "读取设备识别码(IMEI)、当前通话状态、运营商信息");
        putDangerous("CALL_PHONE", "直接拨打电话，可能产生费用");
        putDangerous("CALL_PRIVILEGED", "拨打任意号码，包括紧急号码，无需确认");
        putDangerous("READ_CONTACTS", "读取通讯录中的联系人数据");
        putDangerous("WRITE_CONTACTS", "修改通讯录中的联系人数据");
        putDangerous("READ_CALL_LOG", "读取通话记录");
        putDangerous("WRITE_CALL_LOG", "修改通话记录");
        putDangerous("READ_SMS", "读取短信内容");
        putDangerous("RECEIVE_SMS", "接收并读取短信，可被用于拦截验证码");
        putDangerous("SEND_SMS", "发送短信，可能产生费用");
        putDangerous("RECEIVE_MMS", "接收彩信");
        putDangerous("RECEIVE_WAP_PUSH", "接收 WAP 推送消息");
        putDangerous("PROCESS_OUTGOING_CALLS", "监听拨出的电话并可以改写号码");
        putDangerous("RECORD_AUDIO", "录制音频，可能窃听");
        putDangerous("CAMERA", "使用摄像头拍照或录像");
        putDangerous("ACCESS_FINE_LOCATION", "获取精确 GPS 定位");
        putDangerous("ACCESS_COARSE_LOCATION", "获取大致网络定位");
        putDangerous("ACCESS_LOCATION_EXTRA_COMMANDS", "向定位模块发送额外指令");
        putDangerous("READ_EXTERNAL_STORAGE", "读取 SD 卡上的所有文件");
        putDangerous("WRITE_EXTERNAL_STORAGE", "修改或删除 SD 卡上的所有文件");
        putDangerous("GET_ACCOUNTS", "读取设备上已登录的账户列表");
        putDangerous("USE_CREDENTIALS", "使用账户凭据，可能以你的身份登录");
        putDangerous("MANAGE_ACCOUNTS", "增删设备上的账户");
        putDangerous("AUTHENTICATE_ACCOUNTS", "以账户身份进行验证");
        putDangerous("READ_PROFILE", "读取机主的个人资料");
        putDangerous("WRITE_PROFILE", "修改机主的个人资料");
        putDangerous("READ_SOCIAL_STREAM", "读取社交动态数据");
        putDangerous("WRITE_SOCIAL_STREAM", "发布社交动态");
        putDangerous("READ_USER_DICTIONARY", "读取自定义词库（可能泄露输入习惯）");
        putDangerous("WRITE_USER_DICTIONARY", "写入自定义词库");
        putDangerous("READ_HISTORY_BOOKMARKS", "读取浏览器历史和书签");
        putDangerous("WRITE_HISTORY_BOOKMARKS", "修改浏览器历史和书签");
        putDangerous("SET_ALARM", "设置闹钟");
        putDangerous("BODY_SENSORS", "读取身体传感器数据");
        putDangerous("READ_CALENDAR", "读取日历事件");
        putDangerous("WRITE_CALENDAR", "修改日历事件");
        putDangerous("BLUETOOTH_ADMIN", "开关蓝牙、搜索并配对设备");
        putDangerous("NFC", "通过 NFC 读写标签");
        putDangerous("RECEIVE_BOOT_COMPLETED", "开机自动启动");
        putDangerous("SYSTEM_ALERT_WINDOW", "在其他应用上方显示悬浮窗");
        putDangerous("BIND_DEVICE_ADMIN", "申请设备管理器权限，可获得锁屏/擦除数据等能力");
        putDangerous("DISABLE_KEYGUARD", "关闭锁屏");
        putDangerous("KILL_BACKGROUND_PROCESSES", "结束后台进程");
        putDangerous("MODIFY_AUDIO_SETTINGS", "修改全局音频设置");
        putDangerous("MOUNT_UNMOUNT_FILESYSTEMS", "挂载或卸载文件系统");
        putDangerous("FORMAT_EXTERNAL_STORAGE", "格式化外部存储，会清空数据");
        putDangerous("DELETE_PACKAGES", "卸载其他应用");
        putDangerous("INSTALL_PACKAGES", "静默安装其他应用");
        putDangerous("CLEAR_APP_USER_DATA", "清除其他应用的数据");
        putDangerous("CHANGE_CONFIGURATION", "修改全局系统配置");
        putDangerous("SET_TIME", "修改系统时间");
        putDangerous("SET_TIME_ZONE", "修改系统时区");
        putDangerous("WRITE_SETTINGS", "修改系统设置");
        putDangerous("WRITE_SECURE_SETTINGS", "修改系统安全设置（极高权限）");
        putDangerous("REBOOT", "重启设备");
        putDangerous("FACTORY_TEST", "以工厂测试模式运行");
        putDangerous("BRICK", "极具破坏性的底层权限，可导致设备变砖");
        putDangerous("HARDWARE_TEST", "访问硬件测试接口");
        putDangerous("DEVICE_POWER", "控制电源状态");
        putDangerous("STATUS_BAR", "控制系统状态栏，可伪装界面骗取输入");
        putDangerous("FORCE_STOP_PACKAGES", "强制停止其他应用");
        putDangerous("PACKAGE_USAGE_STATS", "读取应用使用记录");
        putDangerous("GET_TASKS", "查看正在运行的应用（4.1 后已受限）");
        putDangerous("SUBSCRIBED_FEEDS_READ", "读取订阅源");
        putDangerous("SUBSCRIBED_FEEDS_WRITE", "修改订阅源");
        putDangerous("WRITE_APN_SETTINGS", "修改 APN 接入点设置");
        putDangerous("CHANGE_NETWORK_STATE", "改变网络连接状态");
        putDangerous("CHANGE_WIFI_STATE", "开关并修改 Wi-Fi 状态");
        putDangerous("ACCESS_WIFI_STATE", "读取 Wi-Fi 信息");
        putDangerous("BLUETOOTH", "连接已配对的蓝牙设备");
        putDangerous("BROADCAST_STICKY", "发送粘性广播");
        putDangerous("BROADCAST_SMS", "以系统身份广播收到短信");
        putDangerous("SIGNAL_PERSISTENT_PROCESSES", "以信号方式结束持久进程");
        putDangerous("READ_FRAME_BUFFER", "读取屏幕帧缓冲，等同于截屏监控");
        putDangerous("READ_INPUT_STATE", "读取按键输入状态，可能记录你的按键");
        putDangerous("SET_PREFERRED_APPLICATIONS", "修改默认打开方式");
        putDangerous("SET_PROCESS_LIMIT", "限制系统可运行的进程数");
        putDangerous("SET_ACTIVITY_WATCHER", "监视并操纵所有 Activity 的启动");
        putDangerous("DUMP", "导出系统内部状态信息");
        putDangerous("READ_LOGS", "读取系统日志，可能从中还原隐私信息");
        putDangerous("SET_DEBUG_APP", "指定调试目标应用");
        putDangerous("INTERNAL_SYSTEM_WINDOW", "创建只应由系统创建的内部窗口");
        putDangerous("MANAGE_APP_TOKENS", "管理应用窗口令牌，可劫持其他应用窗口");
        putDangerous("INSTALL_LOCATION_PROVIDER", "注入自定义定位数据，可伪造位置");
        putDangerous("ACCOUNT_MANAGER", "作为账户管理器运行");
        putDangerous("BACKUP", "备份应用数据");
        putDangerous("BIND_WALLPAPER", "绑定壁纸服务");
        putDangerous("SET_WALLPAPER", "更换壁纸");
        putDangerous("SET_WALLPAPER_HINTS", "设置壁纸提示");
        putDangerous("REORDER_TASKS", "调整任务栈顺序");
        putDangerous("RESTART_PACKAGES", "重启其他应用");
    }
}
