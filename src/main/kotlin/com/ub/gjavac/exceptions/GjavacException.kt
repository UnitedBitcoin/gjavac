package com.ub.gjavac.exceptions

class GjavacException(msg: String) : Exception(msg) {
  constructor(e: Exception) : this(e.message.orEmpty()) {
  }
}
