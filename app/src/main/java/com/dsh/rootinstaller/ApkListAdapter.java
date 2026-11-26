package com.dsh.rootinstaller;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主界面的 APK 列表。
 * 每行显示：图标 + 文件名 + 包名/版本 + 体积 + 状态（已装 / 可升级 / 降级 / 新装）。
 *
 * 关于图标：很多 ROM 对「外部存储上的 APK」调用 getPackageArchiveInfo 会返回 null，
 * 于是图标拿不到、列表一片空白。完整解析需要先把 APK 复制到私有目录（慢），
 * 所以这里采取「行可见时异步补」的策略：列表先快速出来，图标随后逐个填上。
 */
public class ApkListAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<ApkCard> data;
    private final LayoutInflater inflater;
    private final PackageManager pm;

    /** 已安装应用的图标缓存。 */
    private final Map<String, Drawable> installedIconCache = new HashMap<String, Drawable>();
    /** 从详情页带回来的图标缓存，供下一轮 getView 直接命中。 */
    private final Map<String, Bitmap> iconCache = new HashMap<String, Bitmap>();

    /** 是否处于多选模式；以及选中的路径集合（由 MainActivity 持有，这里只读）。 */
    private boolean selectionMode;
    private Selection selection;

    /** 进入/退出多选模式。由 MainActivity 在长按和取消时调用。 */
    public void setSelectionMode(boolean on, Selection s) {
        this.selectionMode = on;
        this.selection = s;
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public ApkListAdapter(Context ctx, List<ApkCard> data) {
        this.ctx = ctx;
        this.data = data;
        this.inflater = LayoutInflater.from(ctx);
        this.pm = ctx.getPackageManager();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Object getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder h;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_apk, parent, false);
            h = new ViewHolder();
            h.check = (ImageView) convertView.findViewById(R.id.check);
            h.icon = (ImageView) convertView.findViewById(R.id.icon);
            h.name = (TextView) convertView.findViewById(R.id.name);
            h.info = (TextView) convertView.findViewById(R.id.info);
            h.status = (TextView) convertView.findViewById(R.id.status);
            convertView.setTag(h);
        } else {
            h = (ViewHolder) convertView.getTag();
        }

        ApkCard c = data.get(position);

        // 多选模式的视觉：左侧圆点 + 行背景。
        // 圆点是「实心=选中、空心=未选中」，不依赖任何字形（见 ic_select_on.xml）。
        if (h.check != null) {
            boolean checked = selectionMode && selection != null && selection.isSelected(c.filePath);
            h.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            if (selectionMode) {
                h.check.setImageResource(checked ? R.drawable.ic_select_on : R.drawable.ic_select_off);
            }
            convertView.setBackgroundColor(checked ? 0xFFE3F2FD : 0xFFFFFFFF);
        }

        // 在名称后面追加来源标记：SD 卡上的包用醒目的琥珀色标出来，
        // 内部存储的就不加（大多数情况是内部存储，加了反而满屏都是字）。
        String displayName = (c.label != null && c.label.length() > 0)
                ? c.label : c.fileName;
        if (c.fromSdCard) {
            String badge = "  [在 SD 卡]";
            android.text.SpannableString title =
                    new android.text.SpannableString(displayName + badge);
            // 整段标记（含开头的「在」）一起上色加粗。
            //
            // 这里必须用 badge.length() 精确算区间，不能用「总长减几」那种写法 ——
            // 早先写的是 length()-6 到 length()-1，正好把第一个字「在」漏在外面，
            // 看起来就是「在」没有颜色、只有「SD 卡」有色。
            int start = displayName.length();
            int end = title.length();
            title.setSpan(new android.text.style.ForegroundColorSpan(0xFFF9A825),
                    start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            title.setSpan(new android.text.style.StyleSpan(
                            android.graphics.Typeface.BOLD),
                    start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            h.name.setText(title);
        } else {
            h.name.setText(displayName);
        }

        StringBuilder sb = new StringBuilder();
        if (c.packageName != null) {
            sb.append(c.packageName);
        } else {
            sb.append("（未能解析包信息）");
        }
        // 外部存储路径本身也带出来，方便用户确认到底是哪个位置的包
        if (c.fromSdCard && c.storageRoot != null) {
            sb.append("   ← ").append(c.storageRoot);
        }
        sb.append('\n');
        sb.append("版本 ").append(c.versionName != null ? c.versionName : "?")
          .append(" (").append(c.versionCode).append(")");
        sb.append("   ").append(ApkParser.humanSize(c.fileSize));
        if (c.minSdk > 0) {
            sb.append("   最低 ").append(ApkParser.apiName(c.minSdk));
        }
        h.info.setText(sb.toString());

        // 状态行 = 安装状态 +（可选）权限风险徽标。
        //
        // 两段用**各自的颜色**：
        //   安装状态  蓝/绿/红/橙（沿用 statusColor，表示装没装、能不能升级）
        //   风险徽标  按风险等级红/橙/绿（表示权限危不危险）
        //
        // 这两件事维度不同，不能共用一个颜色 —— 否则「已装」（蓝）的包
        // 连高风险徽标也变成蓝色，红色警示就没了；反之低风险徽标也会被
        // 染成安装状态的颜色，绿色的「安全」含义同样丢失。
        String status = statusText(c);
        // 注意顺序：先 setTextColor（它作用于整段文本），
        // 再 setText(SpannableString)。
        // 反过来写的话，setTextColor 会把已经设好的 ForegroundColorSpan 覆盖掉，
        // 徽标就还是跟着安装状态变色了 —— 这正是用户报的那个问题。
        h.status.setTextColor(statusColor(c));

        // 徽标显示条件：中/高风险，或者「有敏感权限但相对安全」（显示绿色）。
        // 后一种情况值得给个绿色 —— 用户看到「它有定位和存储权限，
        // 但没有任何杀手级能力」这种明确结论，比什么都不显示更有信息量。
        if (c.riskBadge != null && c.riskBadge.length() > 0) {
            String badge = "   ·   " + c.riskBadge;
            android.text.SpannableString line =
                    new android.text.SpannableString(status + badge);
            int start = status.length();
            line.setSpan(new android.text.style.ForegroundColorSpan(riskBadgeColor(c)),
                    start, line.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // 徽标再加粗，在混排里更容易跳出来
            line.setSpan(new android.text.style.StyleSpan(
                            android.graphics.Typeface.BOLD),
                    start, line.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            h.status.setText(line);
        } else {
            h.status.setText(status);
        }

        // 图标：
        //   1. 已解析出位图的（进过详情页的包）直接用；
        //   2. 已安装的应用，它的图标是常驻资源，取起来几乎零成本；
        //   3. 其余一律用占位图，**不做任何后台复制/解析**。
        //
        // 为什么第 3 条要这么保守：早先这里会对每个可见行做
        // 「复制 APK 到私有目录 + 完整解析」来取图标。实测这条路对解析
        // 成功率毫无帮助（见 ApkParser.parse 里的实测数据），
        // 却要每个包多花 80~210ms 的复制时间 —— 列表滚动时反复触发，
        // 直接把扫描从 3 秒拖到 7~18 秒。
        // 真实图标在用户点进详情页时自然会加载出来，没必要为列表提前付出这个代价。
        Bitmap bmp = c.icon != null ? c.icon : iconCache.get(c.filePath);
        if (bmp != null) {
            h.icon.setImageBitmap(bmp);
        } else {
            Drawable installed = installedIcon(c);
            if (installed != null) {
                h.icon.setImageDrawable(installed);
            } else {
                h.icon.setImageResource(R.drawable.ic_apk);
            }
        }
        return convertView;
    }

    /** 把详情页解析出来的图标回填到列表，避免下次进列表又变回占位图。 */
    public void putIcon(String path, Bitmap bmp) {
        if (path != null && bmp != null) {
            iconCache.put(path, bmp);
        }
    }


    private String statusText(ApkCard c) {
        if (c.usedFallbackParser) {
            return "兜底解析 · " + (c.installed ? "已安装" : "未安装");
        }
        if (!c.installed) {
            return "未安装 · 全新安装";
        }
        if (c.isDowngrade) {
            return "已装 " + c.installedVersionName + " · 降级安装";
        }
        if (c.isSameVersion) {
            return "已装 " + c.installedVersionName + " · 同版本覆盖";
        }
        if (c.isUpgrade) {
            return "已装 " + c.installedVersionName + " · 可升级";
        }
        return "已安装";
    }

    private int statusColor(ApkCard c) {
        if (c.usedFallbackParser) {
            return 0xFFE65100; // 橙
        }
        if (!c.installed) {
            return 0xFF2E7D32; // 绿
        }
        if (c.isDowngrade) {
            return 0xFFC62828; // 红
        }
        return 0xFF1565C0;     // 蓝
    }

    /**
     * 权限风险徽标自己的颜色，**独立于安装状态**。
     *
     * 这一维度表示「权限危不危险」，和「装没装 / 能不能升级」是完全不同的两件事，
     * 不能共用一个颜色 —— 早先徽标跟着安装状态走，于是「已装」（蓝）的包
     * 连高风险徽标也变成蓝色，红色警示等于没了。
     */
    private int riskBadgeColor(ApkCard c) {
        if (c.criticalButOffline) {
            return 0xFFC62828; // 红：有杀手级能力，只是传不出去
        }
        switch (c.riskLevel) {
            case 3: return 0xFFC62828; // 红
            case 2: return 0xFFEF6C00; // 橙
            case 1: return 0xFF2E7D32; // 绿
            default: return 0xFF757575;
        }
    }

    /** 已安装应用的真实图标（这个一定能拿到，因为已装应用的资源是常驻的）。 */
    private Drawable installedIcon(ApkCard c) {
        if (c.packageName == null || !c.installed) {
            return null;
        }
        Drawable cached = installedIconCache.get(c.packageName);
        if (cached != null) {
            return cached;
        }
        try {
            PackageInfo pi = pm.getPackageInfo(c.packageName, 0);
            if (pi != null && pi.applicationInfo != null) {
                Drawable d = pi.applicationInfo.loadIcon(pm);
                if (d != null) {
                    installedIconCache.put(c.packageName, d);
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static class ViewHolder {
        ImageView check;
        ImageView icon;
        TextView name;
        TextView info;
        TextView status;
    }

    /** 便于外部（比如详情页解析完）清掉缓存，强制刷新。 */
    public void invalidateIcons() {
        iconCache.clear();
        installedIconCache.clear();
        notifyDataSetChanged();
    }
}
