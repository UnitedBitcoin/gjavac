package gjavac.utils

import java.io.Closeable


inline fun <T: Closeable,R> use(t: T, block: (T) -> R): R {
  try {
    return block(t);
  } finally {
    t.close()
  }
}
