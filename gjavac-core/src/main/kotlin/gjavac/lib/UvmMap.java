package gjavac.lib;

import kotlin.Pair;

public class UvmMap<T> extends UvmTable {
    public static <T> UvmMap<T> create() {
        return new UvmMap<T>();
    }

    public void set(String key, T value) {
        if (value == null) {
            hashitems.remove(key);
        } else {
            hashitems.put(key, value);
        }
    }

    public T get(String key) {
        if (hashitems.containsKey(key)) {
            return (T) hashitems.get(key);
        } else {
            return null;
        }
    }

    public MapIterator pairs() {
        return new MapIterator<T>() {
            public Pair<Object, T> invoke(UvmMap<T> map, Object key) {
                boolean foundKey = false;
                String nextKey = null;
                T nextValue = null;
                for (Object k : map.hashitems.keySet()) {
                    if (key == null) {
                        nextKey = (String) k;
                        nextValue = (T) map.hashitems.get(k);
                        break;
                    }
                    if (!foundKey && (String) k == key) {
                        foundKey = true;
                    } else if (foundKey) {
                        nextKey = (String) k;
                        nextValue = (T) map.hashitems.get(k);
                        break;
                    }
                }
                return new Pair<Object, T>(nextKey, nextValue);
            }
        };
    }

}
