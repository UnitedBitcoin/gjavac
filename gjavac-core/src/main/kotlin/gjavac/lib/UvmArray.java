package gjavac.lib;

import kotlin.Pair;

public class UvmArray<T> extends UvmTable {
    public static <T> UvmArray<T> create() {
        return new UvmArray<T>();
    }

    public void add(T value) {
        items.add(value);
    }

    public void pop() {
        if (items.size() > 0) {
            items.remove(items.size() - 1);
        }
    }

    public T get(int index) {
        if (index >= 1 && index <= items.size()) {
            return (T) items.get(index - 1);
        } else if (hashitems.containsKey(index)) {
            return (T) hashitems.get(index);
        } else {
            return null;
        }
    }

    public int size() {
        return items.size();
    }

    public void set(int index, Object value) {
        if (index >= 1 && index <= items.size()) {
            items.set(index - 1, value);
            if (value == null && index == items.size()) {
                items.remove(items.size() - 1);
            }
        } else if (index == items.size() + 1) {
            if (value != null) {
                items.add(value);
            }
        } else {
            if (value != null) {
                hashitems.put(index, value);
            } else {
                hashitems.remove(index);
            }
        }
    }

    public ArrayIterator ipairs() {
        return new ArrayIterator<T>() {
            public Pair<Object, T> invoke(UvmArray<T> array, Object key) {
                boolean foundKey = false;
                Object nextKey = null;
                T nextValue = null;
                for (int k = 1; k <= array.size(); k++) {
                    if (key == null) {
                        nextKey = k;
                        nextValue = (T) array.items.get(k - 1);
                        break;
                    }
                    if (!foundKey && (key instanceof Integer || key instanceof Long) && (k == (Integer) key)) {
                        foundKey = true;
                    } else if (foundKey) {
                        nextKey = k;
                        nextValue = (T) array.items.get(k - 1);
                        break;
                    }
                }
                return new Pair<Object, T>(nextKey, nextValue);
            }
        };
    }
}
