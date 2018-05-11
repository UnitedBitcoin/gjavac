package gjavac.utils

import gjavac.cecil.ClassDefinition

open class TypeInfo(val signature: String, var isPrimitive: Boolean=false, var isArray: Boolean=false) {
  fun primitiveType(): String? {
    return when(signature) {
      "B" -> "byte"
      "C" -> "char"
      "D" -> "double"
      "F" -> "float"
      "I" -> "int"
      "J" -> "long"
      "S" -> "short"
      "V" -> "void"
      "Z" -> "boolean"
      else -> null
    }
  }
  open fun fullName(): String {
    return primitiveType().orEmpty()
  }
  open fun typeName(): String {
    return fullName().replace("/", ".")
  }
  open fun typeClass(): Class<*> {
    return Class.forName(typeName())
  }
}

class MethodTypeInfo(signature: String, val paramTypes: List<TypeInfo>, val returnType: TypeInfo) : TypeInfo(signature) {
  var classDef: ClassDefinition? = null

  override fun fullName(): String {
    return "(" + paramTypes.map { it.fullName() }.joinToString(",") + ")" + returnType.fullName()
  }
}

class ArrayTypeInfo(signature: String, val itemType: TypeInfo) : TypeInfo(signature, isArray = true) {
  override fun fullName(): String {
    return "[]" + itemType.fullName()
  }
}

class ReferenceTypeInfo(signature: String, val typeStr: String) : TypeInfo(signature, false, false) {
  override fun fullName(): String {
    return typeStr.replace("/", ".")
  }
}
