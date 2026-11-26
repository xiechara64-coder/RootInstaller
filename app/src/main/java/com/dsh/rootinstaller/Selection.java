package com.dsh.rootinstaller;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 列表多选状态（纯 Java，可离线测）。
 *
 * <p>为什么单独一个类：多选的 bug 都是「看起来对、少数几个才错」的那种 ——
 * 反选两次没有回到原状、全选之后又取消一项导致数量算错、删除完没清空选择。
 * 把它做成纯 Java 就能在 PC 上把这些情况全跑一遍。
 *
 * <p>用 {@link LinkedHashSet} 而不是 HashSet：需要保持「用户点选的先后顺序」，
 * 这样删除确认框里列出来的顺序才和用户的操作顺序一致。
 */
public final class Selection {

    private final LinkedHashSet<String> selected = new LinkedHashSet<String>();

    /** 切换某一项的选中状态，返回切换后是否选中。 */
    public boolean toggle(String path) {
        if (path == null) {
            return false;
        }
        if (selected.contains(path)) {
            selected.remove(path);
            return false;
        }
        selected.add(path);
        return true;
    }

    public void select(String path) {
        if (path != null) {
            selected.add(path);
        }
    }

    public void deselect(String path) {
        if (path != null) {
            selected.remove(path);
        }
    }

    public boolean isSelected(String path) {
        return path != null && selected.contains(path);
    }

    public int size() {
        return selected.size();
    }

    public boolean isEmpty() {
        return selected.isEmpty();
    }

    /** 选中项（按点选顺序的副本，调用方随便改）。 */
    public List<String> selectedList() {
        return new ArrayList<String>(selected);
    }

    /** 当前对象里的所有选中项 —— 直接暴露给删除流程用，不要修改它。 */
    public LinkedHashSet<String> raw() {
        return selected;
    }

    public void clear() {
        selected.clear();
    }

    /**
     * 全选：把 {@code all} 里出现的路径都选上（已有的其它项保留）。
     *
     * <p>保留「已有的其它项」是有意的：列表可能因为排序变化而少给几项，
     * 全选不该悄悄把之前选中的东西丢掉。
     */
    public void selectAll(List<String> all) {
        if (all == null) {
            return;
        }
        for (int i = 0; i < all.size(); i++) {
            select(all.get(i));
        }
    }

    /**
     * 反选：在 {@code all} 范围内切换每一项。
     *
     * <p>注意只在这批路径范围内反转 —— 不在列表里的历史选中项保持原样。
     * 这样「反选 → 再反选」能准确回到原状态（有专门用例守住）。
     */
    public void invert(List<String> all) {
        if (all == null) {
            return;
        }
        for (int i = 0; i < all.size(); i++) {
            String p = all.get(i);
            if (p == null) {
                continue;
            }
            if (selected.contains(p)) {
                selected.remove(p);
            } else {
                selected.add(p);
            }
        }
    }

    /** 丢掉已经不存在的路径（删除成功后清理用）。 */
    public void retainOnly(List<String> stillPresent) {
        if (stillPresent == null) {
            selected.clear();
            return;
        }
        java.util.HashSet<String> keep = new java.util.HashSet<String>(stillPresent);
        selected.retainAll(keep);
    }
}
