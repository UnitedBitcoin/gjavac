package gjavac.exceptions

class DecodeException(msg: String) : Exception(msg) {
  constructor(e: Exception) : this(e.message.orEmpty()) {
  }
}
