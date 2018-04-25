package com.ub.gjavac.cecil

import com.ub.gjavac.exceptions.ByteCodeDecodeException
import com.ub.gjavac.utils.MethodTypeInfo
import com.ub.gjavac.utils.decodeFromTypeSignature
import org.objectweb.asm.*
import org.objectweb.asm.util.Textifier
import java.io.PrintWriter

// TODO: read sub classes

class DefinitionReaderClassVisitor (val clsName: String, val definitionReader: ClassDefinitionReader, cv: ClassVisitor) : ClassVisitor(Opcodes.ASM5, cv) {

  val classDef = ClassDefinition(clsName)

  init {
    definitionReader.curClassDefinition = classDef
  }

  override fun visitField(access: Int, name: String, desc: String,
                          signature: String?, value: Any?): FieldVisitor? {
    val field = FieldDefinition(name, access and Opcodes.ACC_PUBLIC == Opcodes.ACC_PUBLIC,
            access and Opcodes.ACC_STATIC == Opcodes.ACC_STATIC, decodeFromTypeSignature(desc))
    classDef.fields.add(field)
    return null
  }

  override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
    classDef.annotations.add(decodeFromTypeSignature(desc))
    return null
  }

  override fun visitInnerClass(name: String, outerName: String,
                      innerName: String, access: Int) {
    val parentCurClass = definitionReader.curClassDefinition
    val innerCls = ClassDefinition(name)
    definitionReader.curClassDefinition = innerCls
    parentCurClass?.innerClasses?.add(innerCls)
    if (cv != null) {
      cv.visitInnerClass(name, outerName, innerName, access)
    }
    definitionReader.curClassDefinition = parentCurClass
    // TODO: 内部类暂时还没测
  }

  override fun visit(version: Int, access: Int, name: String, signature: String?,
            superName: String, interfaces: Array<String>) {
    if (cv != null) {
      cv.visit(version, access, name, signature, superName, interfaces)
    }
  }

  override fun visitEnd() {
  }

  override fun visitMethod(access: Int, name: String, desc: String?,
                           signature: String?, exceptions: Array<String>?): MethodVisitor? {
    val mv = cv.visitMethod(access, name, desc, signature, exceptions)

    val methodDef = MethodDefinition(classDef)
    methodDef.name = name
    methodDef.desc = desc
    methodDef.isPublic = ((access and Opcodes.ACC_PUBLIC) == Opcodes.ACC_PUBLIC)
    methodDef.isStatic = ((access and Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC)
    if(desc!=null) {
      methodDef.signature = decodeFromTypeSignature(desc) as MethodTypeInfo
      methodDef.signature?.classDef = classDef
    } else {
      throw ByteCodeDecodeException("Can't find method's signature, method name is " + name)
    }
    classDef.methods.add(methodDef)
    definitionReader.curMethodDefinition = methodDef

    val p = object : Textifier(Opcodes.ASM5) {
      override fun visitMethodEnd() {
        print(PrintWriter(System.out)) // print it after it has been visited
      }
    }
//    return TraceMethodVisitor(mv, p)
    // TODO: read labels: now ifnull, goto instructions etc. 's destination in invalid

    return DefinitionReaderMethodVisitor(methodDef, super.visitMethod(access, name, desc, signature, exceptions), p)
  }
}
