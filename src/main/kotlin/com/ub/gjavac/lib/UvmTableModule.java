package com.ub.gjavac.lib;

import java.util.HashMap;
import java.util.Map;

public class UvmTableModule {

    public static final Map<String, String> libContent;

    static {
        libContent = new HashMap<String, String>();
    }

    public String concat(UvmArray<String> col, String sep) {
        StringBuilder result = new StringBuilder();
        if (col == null) {
            return result.toString();
        }
        for (int i = 0; i < col.size(); i++) {
            if (i > 0) {
                if (sep != null) {
                    result.append(sep);
                }
            }
            result.append(col.get(i + 1));
        }
        return result.toString();
    }

    public <T> int length(UvmArray<T> table) {
        return table.size();
    }

    public <T> void insert(UvmArray<T> col, int pos, T value) {
        if (col == null) {
            return;
        }
        if (pos > col.size() || pos < 1) {
            col.set(pos, value);
        } else {
            // 插入中间，要把pos后位置的值向后移动一位，再把value放入pos位置
            for (int i = col.size(); i > pos; --i) {
                col.set(i + 1, col.get(i));
            }
            col.set(pos, value);
        }
    }

    public <T> void append(UvmArray<T> col, T value) {
        col.add(value);
    }

    public <T> void remove(UvmArray<T> col, int pos) {
        col.set(pos, null);
    }

    public <T> void sort(UvmArray<T> col) {
        if (col == null || col.size() < 2) {
            return;
        }
        // 快排
        int pivot = 1;
        T pivotValue = col.get(pivot);
        UvmArray<T> lessItems = new UvmArray<T>();
        UvmArray<T> greaterItems = new UvmArray<T>();
        for (int i = 1; i <= col.size(); i++) {
            if (i == pivot) {
                continue;
            }
            T item = col.get(i);
            if (pivotValue == null) {
                greaterItems.add(item);
            } else if (item == null) {
                lessItems.add(item);
            } else if (item.toString().compareTo(pivotValue.toString()) < 0) {
                lessItems.add(item);
            } else {
                greaterItems.add(item);
            }
        }
        this.sort(lessItems);
        this.sort(greaterItems);
        UvmArray<T> result = new UvmArray<T>();
        for (int i = 1; i <= lessItems.size(); i++) {
            result.add(lessItems.get(i));
        }
        result.add(pivotValue);
        for (int i = 1; i <= greaterItems.size(); i++) {
            result.add(greaterItems.get(i));
        }
        for (int i = 1; i <= result.size(); i++) {
            col.set(i, result.get(i));
        }
    }
}
