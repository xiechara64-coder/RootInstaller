package com.dsh.rootinstaller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把「一大堆权限」翻译成人话。
 *
 * ============================ 设计要点 ============================
 *
 * 难点不在于罗列权限名，而在于**组合推断**：
 *
 *   单看 `INTERNET`，它根本不是风险 —— 几乎所有应用都申请它。
 *   真正的风险来自「拿到数据的能力」+「把数据送出去的能力」同时存在：
 *       RECEIVE_SMS + INTERNET  =>  能拦截你的验证码并传出去
 *       只有 RECEIVE_SMS        =>  只能在本地读，危害小得多
 *
 * 所以这里不用「危险权限计数」这种偷懒办法，而是：
 *   1. 把权限归并成若干「能力」（读取短信、跟踪位置、控制摄像头…）；
 *   2. 标注哪些能力会因为「同时有网络」而升级措辞；
 *   3. 只有满足全部必要条件时才认定该能力存在。
 *
 * **宁可少说，不可危言耸听。** 如果证据不足就不叠加「并上传」，
 * 只陈述「在本地读取」。用户被夸大的警告骗过几次之后，就再也不看这类提示了 ——
 * 那比不做总结更糟。
 *
 * ==================================================================
 */
public final class PermAbilities {

    // ---- 常量的取值都是 android.Manifest.permission.* 去掉前缀后的部分 ----
    // 这里自己定义字符串而不是 import android.Manifest.permission，
    // 是为了让本类不依赖 Android API，可以在电脑上离线跑验证（见 tests/）。

    public static final String INTERNET = "android.permission.INTERNET";

    private static final String READ_SMS = "android.permission.READ_SMS";
    private static final String RECEIVE_SMS = "android.permission.RECEIVE_SMS";
    private static final String SEND_SMS = "android.permission.SEND_SMS";
    private static final String RECEIVE_MMS = "android.permission.RECEIVE_MMS";
    private static final String RECEIVE_WAP_PUSH = "android.permission.RECEIVE_WAP_PUSH";

    private static final String READ_CONTACTS = "android.permission.READ_CONTACTS";
    private static final String WRITE_CONTACTS = "android.permission.WRITE_CONTACTS";
    private static final String READ_PROFILE = "android.permission.READ_PROFILE";
    private static final String WRITE_PROFILE = "android.permission.WRITE_PROFILE";

    private static final String READ_CALL_LOG = "android.permission.READ_CALL_LOG";
    private static final String WRITE_CALL_LOG = "android.permission.WRITE_CALL_LOG";
    private static final String PROCESS_OUTGOING_CALLS = "android.permission.PROCESS_OUTGOING_CALLS";
    private static final String CALL_PHONE = "android.permission.CALL_PHONE";
    private static final String READ_PHONE_STATE = "android.permission.READ_PHONE_STATE";

    private static final String FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION";
    private static final String COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION";

    private static final String CAMERA = "android.permission.CAMERA";
    private static final String RECORD_AUDIO = "android.permission.RECORD_AUDIO";

    private static final String READ_CALENDAR = "android.permission.READ_CALENDAR";
    private static final String WRITE_CALENDAR = "android.permission.WRITE_CALENDAR";

    private static final String GET_ACCOUNTS = "android.permission.GET_ACCOUNTS";
    private static final String USE_CREDENTIALS = "android.permission.USE_CREDENTIALS";
    private static final String MANAGE_ACCOUNTS = "android.permission.MANAGE_ACCOUNTS";

    private static final String READ_EXTERNAL = "android.permission.READ_EXTERNAL_STORAGE";
    private static final String WRITE_EXTERNAL = "android.permission.WRITE_EXTERNAL_STORAGE";

    private static final String RECEIVE_BOOT = "android.permission.RECEIVE_BOOT_COMPLETED";
    private static final String SYSTEM_ALERT = "android.permission.SYSTEM_ALERT_WINDOW";
    private static final String DEVICE_ADMIN = "android.permission.BIND_DEVICE_ADMIN";
    private static final String HISTORY = "android.permission.READ_HISTORY_BOOKMARKS";
    private static final String READ_LOGS = "android.permission.READ_LOGS";
    private static final String NFC = "android.permission.NFC";

    private PermAbilities() {
    }

    // ------------------------------------------------------------------
    // 数据结构
    // ------------------------------------------------------------------

    /** 一项「能力」。 */
    public static final class Ability {
        /** 人话描述，例如「读取你的短信内容」。 */
        public final String text;
        /** 支撑这项能力的权限（用于在界面上展开给想深究的用户看）。 */
        public final List<String> permissions;
        /**
         * 「同时有网络」时的升级说法。为 null 表示这个能力与网络无关，
         * 不会因为应用能联网而改变措辞。
         */
        public final String textWithNetwork;
        /** 风险权重，用于排序与判定总体等级：3=严重 2=较高 1=一般。 */
        public final int severity;
        /** 只有当应用具备这些权限时，本能力才成立（额外必要条件）。 */
        public final List<String> alsoRequires;
        /** 本能力是否依赖网络才有意义（决定要不要套用 textWithNetwork）。 */
        public final boolean networkRelevant;
        /**
         * 所属类别。
         *
         * 同一类别里可能有多条记录，因为它们对应不同强度的权限组合
         * （例如「拦截并读取短信」需要 RECEIVE_SMS+READ_SMS，
         * 「读取短信」只需要 READ_SMS）。汇总时**每个类别只保留最严重的那一条**，
         * 否则会拼出「拦截并读取你的短信、接收并读取短信、以及读取你手机里的短信」
         * 这种同义重复的句子 —— 那是这一版最初踩过的坑。
         */
        public final String category;
        /**
         * 同类同级内的展示优先级，**越小越靠前**（也就是越值得先告诉用户）。
         *
         * 为什么需要它：光按 severity 排序不够。比如「拦截短信」和「调用摄像头」
         * 都是 severity=3，但前者直接关联银行验证码被盗，明显更该排在结论句首位。
         * 早先同级是按类别名做字符串排序，结果 `camera` 排在 `sms` 前面，
         * 间谍软件样本的结论里第一句成了「可以调用摄像头」—— 把最关键的信息挤掉了。
         */
        public final int priority;

        Ability(String category, int priority, String text, String[] perms,
                String textWithNetwork, int severity, String[] alsoRequires,
                boolean networkRelevant) {
            this.category = category;
            this.priority = priority;
            this.text = text;
            this.permissions = Arrays.asList(perms);
            this.textWithNetwork = textWithNetwork;
            this.severity = severity;
            this.alsoRequires = alsoRequires == null
                    ? new ArrayList<String>() : Arrays.asList(alsoRequires);
            this.networkRelevant = networkRelevant;
        }
    }

    /** 汇总结果。 */
    public static final class Summary {
        /** 总体风险等级：3=高 2=中 1=低 0=无明显风险。 */
        public int riskLevel;
        /** 一句话结论，用于列表和详情页首行。 */
        public String headline = "";
        /** 命中的能力，已按严重度降序排列。 */
        public final List<Ability> abilities = new ArrayList<Ability>();
        /** 是否有联网能力（影响措辞与等级）。 */
        public boolean hasNetwork;
        public int dangerousCount;
        /** 有「杀手级」能力但不联网：风险降一档，但徽标要特别标注「无法外传」。 */
        public boolean criticalButOffline;
        public int totalCount;

        public boolean hasAbilities() {
            return !abilities.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 能力表
    //
    // 新增一种能力只需要在这里加一行，逻辑不用动。
    // 排序上把 severity 高的放前面，方便阅读，但匹配顺序不影响结果。
    // ------------------------------------------------------------------

    private static final List<Ability> TABLE = new ArrayList<Ability>();

    private static void add(String category, int priority, String text, String[] perms,
                            String textWithNetwork, int severity, String[] alsoRequires) {
        TABLE.add(new Ability(category, priority, text, perms, textWithNetwork, severity,
                alsoRequires, textWithNetwork != null));
    }

    static {
        // ---------- 严重：能拿到验证码 / 窃听 / 实时跟踪 ----------
        //
        // 同一 category 的几条是「不同强度的同一件事」，汇总时会自动只留最强的那条。

        add("sms", 10, "拦截并读取你的短信（包括银行验证码）",
                new String[] { RECEIVE_SMS, READ_SMS },
                "拦截并读取你的短信，还能把它们上传出去", 3, null);

        add("sms", 11, "接收并读取短信",
                new String[] { RECEIVE_SMS },
                "接收并读取短信，还能把它们上传出去", 3, null);

        add("sms", 12, "读取你手机里的短信",
                new String[] { READ_SMS },
                "读取你手机里的短信，还能把它们上传出去", 3, null);

        add("mic", 20, "偷偷录音（麦克风）",
                new String[] { RECORD_AUDIO },
                "偷偷录音并上传", 3, null);

        add("camera", 30, "调用摄像头拍照/录像",
                new String[] { CAMERA },
                "调用摄像头拍照并上传", 3, null);

        add("location", 25, "实时跟踪你的位置（GPS 精确定位）",
                new String[] { FINE_LOCATION },
                "实时跟踪你的位置并上传", 3, null);

        add("callhijack", 22, "切断或劫持你拨出的电话",
                new String[] { PROCESS_OUTGOING_CALLS },
                "切断或劫持你拨出的电话，并把号码上传", 3, null);

        // ---------- 较高：能拿到大量隐私数据 ----------

        add("location", 26, "跟踪你的大致位置（网络定位）",
                new String[] { COARSE_LOCATION },
                "跟踪你的大致位置并上传", 2, null);

        add("calllog", 40, "读取你的通话记录",
                new String[] { READ_CALL_LOG },
                "读取你的通话记录并上传", 2, null);

        add("contacts", 45, "读取你的通讯录",
                new String[] { READ_CONTACTS },
                "读取你的通讯录并上传", 2, null);

        add("deviceid", 50, "读取设备识别码（IMEI）和运营商信息",
                new String[] { READ_PHONE_STATE },
                "读取设备识别码和运营商信息并上传（可用于跨应用追踪你）", 2, null);

        add("accounts", 60, "读取你已登录的账号列表",
                new String[] { GET_ACCOUNTS },
                "读取你已登录的账号列表并上传", 2, null);

        add("credentials", 23, "使用你的账号凭据（可能以你的身份登录）",
                new String[] { USE_CREDENTIALS }, null, 3, null);

        add("history", 70, "读取浏览器历史与书签",
                new String[] { HISTORY },
                "读取浏览器历史与书签并上传", 2, null);

        add("logs", 24, "读取系统日志（可能还原出你的操作痕迹）",
                new String[] { READ_LOGS }, null, 3, null);

        // ---------- 需要「额外必要条件」的能力 ----------
        //
        // 这一类是组合推断的核心：
        // 单独看 CALL_PHONE 只是「能打电话」；叠加 READ_PHONE_STATE 之后，
        // 它才可能做到「趁你不在通话时偷偷拨出去」。证据不足就不报。

        add("call", 65, "不经确认就拨打电话（可能产生费用）",
                new String[] { CALL_PHONE }, null, 2,
                new String[] { READ_PHONE_STATE });

        add("calendar", 80, "读取你的日历日程",
                new String[] { READ_CALENDAR },
                "读取你的日历日程并上传", 2, null);

        // ---------- 一般：主要是对设备和文件的控制 ----------

        add("storage", 85, "读取你存储卡上的所有文件",
                new String[] { READ_EXTERNAL },
                "读取你存储卡上的所有文件并上传", 2, null);

        add("storage_write", 86, "修改或删除你存储卡上的文件",
                new String[] { WRITE_EXTERNAL },
                "修改或删除你存储卡上的文件", 2, null);

        add("profile", 90, "读取你的个人资料（姓名、头像等）",
                new String[] { READ_PROFILE }, null, 2, null);

        add("deviceadmin", 21, "申请设备管理器权限（可锁屏、擦除数据）",
                new String[] { DEVICE_ADMIN }, null, 3, null);

        add("overlay", 35, "在其他应用上层显示悬浮窗（可伪装界面骗取输入）",
                new String[] { SYSTEM_ALERT }, null, 2, null);

        add("boot", 95, "开机后自动启动", new String[] { RECEIVE_BOOT }, null, 1, null);

        add("nfc", 96, "通过 NFC 读写标签", new String[] { NFC }, null, 1, null);
    }

    // ------------------------------------------------------------------
    // 汇总
    // ------------------------------------------------------------------

    public static Summary summarize(ApkCard card, Map<String, Perms.Info> table) {
        Summary s = new Summary();
        if (card == null) {
            return s;
        }
        List<String> perms = card.permissions;
        s.totalCount = perms.size();
        if (perms.isEmpty()) {
            s.riskLevel = 0;
            s.headline = "这个应用没有申请任何权限";
            // 注意别忘了写回卡片：早先这里直接 return 了，
            // 结果「零权限」的包 riskHeadline 一直是 null，列表里拿不到结论。
            card.dangerousPermissionCount = 0;
            card.riskLevel = 0;
            card.riskHeadline = s.headline;
            card.criticalButOffline = false;
            card.riskBadge = "";
            return s;
        }

        Set<String> set = new HashSet<String>(perms);
        s.hasNetwork = set.contains(INTERNET);

        // 统计危险权限个数（用于在没有任何能力命中时也能给出有用结论）
        for (int i = 0; i < perms.size(); i++) {
            Perms.Info info = Perms.lookup(table, perms.get(i));
            if (info != null && info.dangerous) {
                s.dangerousCount++;
            }
            // 顺便让卡片自己也算一份，列表页要用
            card.dangerousPermissionCount = s.dangerousCount;
        }
        card.dangerousPermissionCount = s.dangerousCount;

        // 逐条匹配能力。
        //
        // 关键点：**按 category 去重，每个类别只保留最严重的那一条**。
        // 因为同类别里几条是「同一件事的不同强度」—— 一个应用同时有
        // RECEIVE_SMS 和 READ_SMS 时，三条短信记录都会命中，
        // 不去重就会拼出「拦截并读取你的短信、接收并读取短信、以及读取你手机里的短信」
        // 这种同义重复的句子。
        Map<String, Ability> bestPerCategory = new HashMap<String, Ability>();
        for (int i = 0; i < TABLE.size(); i++) {
            Ability a = TABLE.get(i);
            if (!set.containsAll(a.permissions)) {
                continue;
            }
            if (!set.containsAll(a.alsoRequires)) {
                continue;
            }
            Ability exist = bestPerCategory.get(a.category);
            if (exist == null || a.severity > exist.severity) {
                bestPerCategory.put(a.category, a);
            }
        }
        s.abilities.addAll(bestPerCategory.values());

        // 排序：先按严重度降序，再按 priority 升序（越小越该先说）。
        // 同级里**不能**按类别名字符串排 —— 那样 camera 会排在 sms 前面，
        // 把「拦截短信」这种最要命的信息挤出结论句。
        java.util.Collections.sort(s.abilities, new java.util.Comparator<Ability>() {
            @Override
            public int compare(Ability x, Ability y) {
                if (x.severity != y.severity) {
                    return y.severity - x.severity;
                }
                if (x.priority != y.priority) {
                    return x.priority - y.priority;
                }
                // priority 相同时再用类别名兜底，保证顺序稳定
                return x.category.compareTo(y.category);
            }
        });

        buildHeadline(s);
        s.riskLevel = computeRisk(s);
        card.riskLevel = s.riskLevel;
        card.riskHeadline = s.headline;
        card.criticalButOffline = s.criticalButOffline;
        card.riskBadge = badgeText(s);
        return s;
    }

    /**
     * 拼一句话结论。
     *
     * 两个刻意的设计：
     *
     *   1. **最多说 3 项**。再长用户就不看了，剩下的只报个数。
     *   2. **「能上传」只统一说一次**，不在每项后面都跟一句。
     *      早先每条能力都带「还能把它们上传出去」，三条连起来就是
     *      「…上传出去、…上传出去、以及…上传出去」，读起来像复读机。
     *      改成本句末尾统一提示一次，同时保留了「有没有联网」这个关键区分。
     */
    private static void buildHeadline(Summary s) {
        if (s.abilities.isEmpty()) {
            // 没有任何能力命中。分两种：有敏感权限但说不出具体行为、以及确实很干净。
            //
            // 措辞上避开了「没有命中已知的隐私读取行为」这种说法（曾经用过）：
            // 它和前半句「有 N 项敏感权限」放在一起自相矛盾 —— 既然承认有敏感权限，
            // 又说没命中隐私读取，用户会不知道该信哪半句。
            // 现在改成陈述事实：**说不出它具体做了什么**，并明确「不代表安全」，
            // 同时引导用户去看下面的逐条明细（那里才有权限本身的信息）。
            if (s.dangerousCount == 0) {
                s.headline = "没有发现明显的隐私风险权限";
            } else if (s.hasNetwork) {
                s.headline = "申请了 " + s.dangerousCount
                        + " 项需要留意的权限，但对照已知的隐私行为，还说不出它具体做了什么。"
                        + "这不代表它安全 —— 建议看下面的逐条权限。";
            } else {
                s.headline = "申请了 " + s.dangerousCount
                        + " 项需要留意的权限。它不能联网，这些权限拿到的东西传不出设备。";
            }
            return;
        }

        int top = Math.min(3, s.abilities.size());
        StringBuilder sb = new StringBuilder("这个应用可以");
        for (int i = 0; i < top; i++) {
            if (i > 0) {
                sb.append(i == top - 1 ? "，以及" : "、");
            }
            // 这里一律用「不带上传」的措辞，联网提示统一放到句尾
            sb.append(s.abilities.get(i).text);
        }
        sb.append("。");

        if (s.hasNetwork) {
            sb.append("它还能联网，读到的东西有可能被传出去。");
        } else {
            sb.append("它不能联网，读到的东西传不出设备。");
        }

        // 这里**故意不写**「另外还有 N 项」。
        //
        // 曾经写过，结果让人困惑：结论句只列了最严重的 3 项，而下方列表会把
        // 全部能力都列出来（含句子里已提到的那 3 项）。于是用户看到
        // 「另外还有 4 项」+ 6 条橙色，会以为那 4 项是列表之外的另外一批。
        // 实际上「N 项」指的是「没进结论句的能力」，和列表里的条数对不上。
        //
        // 现在改为：结论句只说重点，具体有哪些一律看下面的完整列表 ——
        // 两处数字不需要对应，也就不会误导。
        if (s.abilities.size() > top) {
            sb.append("下面列出了全部 ").append(s.abilities.size()).append(" 项。");
        }
        s.headline = sb.toString();
    }

    /** 按是否有网络，选该能力的措辞。 */
    public static String describe(Ability a, boolean hasNetwork) {
        if (hasNetwork && a.networkRelevant && a.textWithNetwork != null) {
            return a.textWithNetwork;
        }
        return a.text;
    }

    /**
     * 「杀手级」能力类别：这些做了就是高风险。
     *
     * 为什么用白名单而不是笼统看 severity：
     *   摄像头、定位、通讯录这些确实敏感，但绝大多数正常应用都会申请，
     *   把它们也算高风险，徽标就会满屏都是 —— 徽标一旦到处出现就等于没有。
     *   真正值得优先警示的是「能拿到验证码 / 能监听 / 能控制设备」这几类：
     *   普通应用没有理由需要它们。
     */
    private static final Set<String> CRITICAL_CATEGORIES = new HashSet<String>(
            Arrays.asList("sms", "mic", "deviceadmin", "credentials", "logs", "callhijack"));

    /**
     * 总体风险等级。
     *
     * 分级规则（改过两轮，这里记录一下为什么）：
     *
     *   v1 是「只要联网、且能力 severity >= 2 就抬到高风险」——
     *      结果几乎所有应用都成了高风险，因为「读存储卡」这种人人皆有的能力
     *      叠上联网就够了。徽标失去区分度。
     *   v2 改成白名单：只有 killer 类别（短信/录音/摄像头/设备管理器…）命中，
     *      并且**能联网**（数据可能传出去）时才算高风险。
     */
    private static int computeRisk(Summary s) {
        if (s.abilities.isEmpty()) {
            // 没命中具体能力时，「敏感权限又多又能联网」给中风险
            if (s.dangerousCount >= 8 && s.hasNetwork) {
                return 2;
            }
            return s.dangerousCount > 0 ? 1 : 0;
        }

        boolean critical = false;
        boolean sensitive = false;
        for (int i = 0; i < s.abilities.size(); i++) {
            Ability a = s.abilities.get(i);
            if (CRITICAL_CATEGORIES.contains(a.category)) {
                critical = true;
            }
            if (a.severity >= 2) {
                sensitive = true;
            }
        }

        if (critical && s.hasNetwork) {
            // killer 能力 + 能把数据送出去 —— 这才是真正该警示的组合
            s.criticalButOffline = false;
            return 3;
        }
        if (critical) {
            // 有 killer 能力但传不出去。降一档，但记下这个特殊状态：
            // 徽标要用「高风险权限（无法外传）」这种说法，
            // 免得只写「中风险」让人低估了「能读你短信」这件事。
            s.criticalButOffline = true;
            return 2;
        }
        if (sensitive) {
            return 2;
        }
        return 1;
    }

    /** 风险等级对应的短标签，用于列表徽标。 */
    public static String riskLabel(int level) {
        switch (level) {
            case 3: return "高风险";
            case 2: return "中风险";
            case 1: return "低风险";
            default: return "";
        }
    }

    /**
     * 权限相关的徽标文案。返回空串表示不显示徽标。
     *
     * 三档都覆盖，且颜色一一对应（红/橙/绿）：
     *   高风险  → 有杀手级能力且能联网
     *   中风险  → 有杀手级能力但传不出去（标「高风险权限（无法外传）」），
     *             或只有敏感能力（定位/通讯录/存储…）
     *   低风险  → 有敏感权限，但没有任何杀手级能力 —— 这种明确标绿，
     *             比什么都不显示更有信息量（用户知道「有权限但不至于危险」）
     *   （无）  → 连敏感权限都没有
     */
    public static String badgeText(Summary s) {
        if (s == null) {
            return "";
        }
        if (s.criticalButOffline) {
            // 「有杀手级能力但传不出去」特意不写成普通「中风险」——
            // 「能读你短信」这件事本身就够严重，只写中风险会让人低估它。
            return "高风险权限（无法外传）";
        }
        String label = riskLabel(s.riskLevel);
        return label.length() == 0 ? "" : label + "权限";
    }

    /** 徽标是否值得显示：低风险也显示（绿色），只有「没有敏感权限」才不显示。 */
    public static boolean shouldShowBadge(Summary s) {
        if (s == null) {
            return false;
        }
        return s.criticalButOffline || s.riskLevel >= 1;
    }

    /** 风险等级对应的颜色。 */
    public static int riskColor(int level) {
        switch (level) {
            case 3: return 0xFFC62828; // 红
            case 2: return 0xFFEF6C00; // 橙
            case 1: return 0xFF2E7D32; // 绿
            default: return 0xFF757575; // 灰
        }
    }
}
