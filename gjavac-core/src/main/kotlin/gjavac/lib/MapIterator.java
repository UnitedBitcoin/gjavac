package gjavac.lib;

import kotlin.Pair;
import kotlin.jvm.functions.Function2;

/**
 * (map, key) => pair(key, result)
 * @param <T>
 */
public interface MapIterator<T> extends Function2<UvmMap<T>, Object, Pair<Object, T>> {
}
