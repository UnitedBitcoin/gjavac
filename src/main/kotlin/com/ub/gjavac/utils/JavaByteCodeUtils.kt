package com.ub.gjavac.utils

import com.ub.gjavac.exceptions.DecodeException

/**
 * 解码jvm字节码中的基本类型描述符比如 "Ljava/lang/String;" , "V: , "C", "(Ljava/lang/String;)V" 等
 */
fun decodeFromTypeSignature(signature: String): TypeInfo {
  if(signature == null || signature.length<1)
    throw DecodeException("type signature error " + signature)
  return when (signature[0]) {
    'L' -> {
      // read until ;
      var semicolonPos = -1
      for(i in 1..(signature.length-1)) {
        val c = signature[i]
        if(c == ';') {
          semicolonPos = i
          break
        }
      }
      if(semicolonPos<=1) {
        throw DecodeException("type signature error " + signature)
      }
      val typeStr = signature.substring(1, semicolonPos)
      ReferenceTypeInfo("L" + typeStr + ";", typeStr)
    }
    'B', 'C', 'D', 'F', 'I', 'J', 'G', 'Z', 'V' -> TypeInfo("" + signature[0], true, false)
    '[' -> {
      // read array
      val subTypeInfo = decodeFromTypeSignature(signature.substring(1))
      ArrayTypeInfo("[" + subTypeInfo.signature, subTypeInfo)
    }
    '(' -> {
      var pos = 1
      var paramTypes: MutableList<TypeInfo> = mutableListOf()
      while(pos < signature.length && signature[pos] != ')') {
        val subTypeInfo = decodeFromTypeSignature(signature.substring(pos))
        paramTypes.add(subTypeInfo)
        pos += subTypeInfo.signature.length
      }
      if(pos >= signature.length-1 || signature[pos] != ')') {
        throw DecodeException("type signature of method signature error " + signature)
      }
      pos += 1
      val returnType = decodeFromTypeSignature(signature.substring(pos))
      pos += returnType.signature.length
      return MethodTypeInfo(signature.substring(0, pos), paramTypes, returnType)
    }
    else -> {
      throw DecodeException("type signature error of unknown signature " + signature)
    }
  }
}
