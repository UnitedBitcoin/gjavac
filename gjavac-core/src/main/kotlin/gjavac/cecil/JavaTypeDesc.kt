package gjavac.cecil

import gjavac.exceptions.GjavacException

// TODO: duplicat with TypeInfo and JavaByteCodeUtils
class JavaTypeDesc(val desc: String, val isMethod: Boolean = false, var primitiveTypeName: String?=null) {
    var methodArgs: MutableList<JavaTypeDesc> = mutableListOf()
    var methodReturnType: JavaTypeDesc? = null

    fun fullName(): String {
        if(isMethod) {
            val builder = StringBuilder()
            builder.append("(")
            for(i in 0 until methodArgs.size) {
                if(i>0)
                    builder.append(",")
                builder.append(methodArgs[i].fullName())
            }
            builder.append(")")
            builder.append(methodReturnType?.fullName())
            return builder.toString()
        } else if(desc.startsWith("L")) {
            return desc.substring(1, desc.length-1).replace("/", ".")
        } else if(primitiveTypeName!=null) {
            return primitiveTypeName ?: "null"
        } else {
            return desc
        }
    }

    fun isBoolean(): Boolean {
        return desc == "Z" || fullName()=="System.Boolean"
    }

    companion object {
        fun parse(typeDesc: String): JavaTypeDesc {
            if(typeDesc == "B")
                return JavaTypeDesc(typeDesc, primitiveTypeName = "byte")
            if(typeDesc == "C")
                return JavaTypeDesc(typeDesc, primitiveTypeName = "char")
            if(typeDesc == "D")
                return JavaTypeDesc(typeDesc, primitiveTypeName = "double")
            if(typeDesc == "F")
                return JavaTypeDesc(typeDesc, primitiveTypeName = "float")
            if(typeDesc == "I")
                return JavaTypeDesc(typeDesc, primitiveTypeName = "int")
            if(typeDesc == "J")
                return JavaTypeDesc(typeDesc, primitiveTypeName = "long")
            if(typeDesc == "S")
                return JavaTypeDesc(typeDesc, primitiveTypeName = "short")
            if(typeDesc == "Z")
                return JavaTypeDesc(typeDesc, primitiveTypeName = "boolean")
            if(typeDesc == "V")
                return JavaTypeDesc(typeDesc, primitiveTypeName = "void")
            if(typeDesc.startsWith("L")) {
                // find first ';' after 'L'
                val semicolonPos = typeDesc.indexOf(';')
                if(semicolonPos < 0 )
                    throw GjavacException("wrong java type desc format " + typeDesc)
                val thisTypeDesc = typeDesc.substring(0, semicolonPos + 1);
                return JavaTypeDesc(thisTypeDesc)
            } else if(typeDesc.startsWith("(")) {
                var pos = 1
                val args = mutableListOf<JavaTypeDesc>()
                while(pos < typeDesc.length) {
                    if(typeDesc[pos] == ')') {
                        break
                    }
                    val descObj = parse(typeDesc.substring(pos))
                    args.add(descObj)
                    pos += descObj.desc.length
                }
                if(pos < (typeDesc.length-1) && typeDesc[pos] == ')') {
                    pos++
                    val returnType = parse(typeDesc.substring(pos))
                    val end = pos + returnType.desc.length
                    val methodInfo = JavaTypeDesc(typeDesc.substring(0, end), true)
                    methodInfo.methodArgs = args
                    methodInfo.methodReturnType = returnType
                    return methodInfo
                } else
                    throw GjavacException("wrong java type desc format " + typeDesc)
            } else {
                if(typeDesc.length> 1 && "BCDFIJSZV".indexOf(typeDesc[0])>=0) {
                    return parse(typeDesc.substring(0, 1))
                }
                throw GjavacException("cant detect java type desc " + typeDesc)
            }
        }
    }
}
