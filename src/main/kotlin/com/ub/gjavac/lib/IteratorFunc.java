package com.ub.gjavac.lib;

import kotlin.Pair;
import kotlin.jvm.functions.Function2;

/**
 * iterator of (col, key) => (nextKey, nextValue)
 */
public interface IteratorFunc extends Function2<Object, Object, Pair<Object, Object>> {
}
