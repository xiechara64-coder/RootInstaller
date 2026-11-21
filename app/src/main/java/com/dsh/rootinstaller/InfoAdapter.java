package com.dsh.rootinstaller;

import java.util.ArrayList;
import java.util.List;

/**
 * 详情页的数据模型：把解析出来的信息拍平成一行行「标题 / 内容」。
 *
 * 为什么拍平？因为详情页用 ListView 展示，能整体滚动、能复用视图，
 * 比 ScrollView 套一堆 TextView 省内存，4.3 的老机器扛得住。
 */
public class InfoAdapter extends android.widget.BaseAdapter {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_FIELD = 1;
    public static final int TYPE_SPACER = 2;

    /** 一行。 */
    public static class Item {
        public final int type;
        public String key;
        public String value;
        public boolean clickable;
        public Runnable onClick;
        public int valueColor;

        private Item(int type) {
            this.type = type;
            this.valueColor = 0;
        }

        public static Item header(String title) {
            Item i = new Item(TYPE_HEADER);
            i.key = title;
            return i;
        }

        public static Item field(String key, String value) {
            Item i = new Item(TYPE_FIELD);
            i.key = key;
            i.value = value == null ? "—" : value;
            return i;
        }

        public static Item field(String key, String value, int valueColor) {
            Item i = field(key, value);
            i.valueColor = valueColor;
            return i;
        }

        public static Item action(String key, String value, Runnable onClick) {
            Item i = field(key, value);
            i.clickable = true;
            i.onClick = onClick;
            return i;
        }

        public static Item spacer() {
            return new Item(TYPE_SPACER);
        }
    }

    private final List<Item> items = new ArrayList<Item>();
    private final android.view.LayoutInflater inflater;

    public InfoAdapter(android.content.Context ctx) {
        this.inflater = android.view.LayoutInflater.from(ctx);
    }

    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    public void add(Item i) {
        items.add(i);
    }

    public void addAll(List<Item> list) {
        items.addAll(list);
    }

    /** 批量加完再刷新，避免每加一行都重绘。 */
    public void commit() {
        notifyDataSetChanged();
    }

    /**
     * 就地把某一行的内容换掉（按 key 匹配）。
     *
     * 用于「点击计算后才拿到结果」的场景，比如 MD5/SHA1/签名摘要，
     * 这样不必重建整个列表、也不会丢掉用户的滚动位置。
     */
    public boolean updateValue(String key, String value) {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.type == TYPE_FIELD && key.equals(it.key)) {
                it.value = value == null ? "—" : value;
                return true;
            }
        }
        return false;
    }

    /** 删除某一行的可点击标记（比如算完之后不让再点）。 */
    public void clearClickable(String key) {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.type == TYPE_FIELD && key.equals(it.key)) {
                it.clickable = false;
                it.onClick = null;
                return;
            }
        }
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @Override
    public int getViewTypeCount() {
        return 3;
    }

    @Override
    public boolean isEnabled(int position) {
        return items.get(position).clickable;
    }

    @Override
    public android.view.View getView(int position, android.view.View convertView,
                                     android.view.ViewGroup parent) {
        Item it = items.get(position);

        if (it.type == TYPE_HEADER) {
            android.widget.TextView tv;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_info_header, parent, false);
            }
            tv = (android.widget.TextView) convertView;
            tv.setText(it.key);
            return convertView;
        }

        if (it.type == TYPE_SPACER) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_info_spacer, parent, false);
            }
            return convertView;
        }

        // TYPE_FIELD
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_info_field, parent, false);
        }
        android.widget.TextView k = (android.widget.TextView) convertView.findViewById(R.id.key);
        android.widget.TextView v = (android.widget.TextView) convertView.findViewById(R.id.value);
        k.setText(it.key);
        v.setText(it.value);
        if (it.valueColor != 0) {
            v.setTextColor(it.valueColor);
        } else {
            v.setTextColor(0xFF212121);
        }

        if (it.clickable && it.onClick != null) {
            v.setTextColor(0xFF1565C0);
            final Runnable r = it.onClick;
            convertView.setClickable(true);
            convertView.setOnClickListener(new android.view.View.OnClickListener() {
                @Override
                public void onClick(android.view.View view) {
                    r.run();
                }
            });
        } else {
            convertView.setOnClickListener(null);
            convertView.setClickable(false);
        }
        return convertView;
    }
}
