package gjavac.exceptions

class ByteCodeDecodeException (msg: String) : Exception(msg) {
  constructor(e: Exception) : this(e.message.orEmpty()) {
  }
}
