package com.ub.gjavac.lib;


import kotlin.Pair;
import kotlin.jvm.functions.Function2;

/**
 * (array, key) => pair(key, result)
 * @param <T>
 */
public interface ArrayIterator<T> extends Function2<UvmArray<T>, Object, Pair<Object, T>> {

}
