package gjavac.cecil

import gjavac.utils.MethodTypeInfo
import gjavac.utils.TypeInfo
import gjavac.utils.use
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.util.Printer
import org.objectweb.asm.util.TraceClassVisitor
import java.io.File
import java.io.FileInputStream
import java.io.PrintWriter

class ModuleDefinition {
  // 若干个java的.class文件构成的集合
  val classes: MutableList<ClassDefinition> = mutableListOf()
}

class ClassDefinition(val name: String) {
  val methods: MutableList<MethodDefinition> = mutableListOf()
  val fields: MutableList<FieldDefinition> = mutableListOf()
  val annotations: MutableList<TypeInfo> = mutableListOf()
  val innerClasses: MutableList<ClassDefinition> = mutableListOf() // TODO
  val interfaces: MutableList<String> = mutableListOf()
  var superClassName: String? = null
}

class MethodDefinition(val definitionClass: ClassDefinition) {
  var name: String? = null
  var isStatic = false
  var isPublic = false
  var maxStack = 0
  var maxLocals = 0
  var desc: String? = null
  var signature: MethodTypeInfo? = null
  var annotations: MutableList<TypeInfo> = mutableListOf()
  val code: MutableList<Instruction> = mutableListOf()
  var labelOffsets: MutableMap<Label, Int> = mutableMapOf()
  var lastLineNumber: Int = 0

  fun addInstruction(inst: Instruction) {
    inst.offset = code.size
    if(inst.linenumber<=0) {
      inst.linenumber = lastLineNumber
    }
    code.add(inst)
  }

  fun addLabelOffset(label: Label, offset: Int) {
    labelOffsets[label] = offset
  }

  fun lastInstruction(): Instruction? {
    return if(code.isEmpty()) null else code.last()
  }

  fun instructionsCount(): Int = code.size

  fun offsetOfLabel(label: Label): Int? {
    if(labelOffsets.containsKey(label)) {
      return labelOffsets[label]
    } else {
      return null
    }
  }

  fun fullName(): String {
    return name.orEmpty()
  }
}

class FieldDefinition(val name: String, val isPublic: Boolean, val isStatic: Boolean, val signature: TypeInfo) {

}

class FieldInfo(val owner: String, val name: String, val desc: String) {

}

class MethodInfo(val owner: String, val name: String, val desc: String) {

}

class SimpleInfo(val name: String, val desc: String) {

}

class Instruction(val opCode: Int, var opArgs: MutableList<Any> = mutableListOf(), var instLine: String = "",
                  var linenumber: Int = 0, var offset: Int = 0) {

  fun opCodeName(): String {
    return Printer.OPCODES[opCode]
  }

  override fun toString(): String {
    return "$instLine${if(opCode==Opcodes.LDC)" $opArgs[0]" else ""}"
  }

}

class ClassDefinitionReader {
  var curClassDefinition: ClassDefinition? = null
  var curMethodDefinition: MethodDefinition? = null
  fun readClass(clsPaths: List<String>): ModuleDefinition {
    val module = ModuleDefinition()
    for(clsPath in clsPaths) {
      use(FileInputStream(File(clsPath))) { clsInputStream ->
        val clsReader = ClassReader(clsInputStream)
        val printWriter = PrintWriter(System.out)
        val traceClassVisitor: ClassVisitor = TraceClassVisitor(printWriter)
        val myClassVisitor = DefinitionReaderClassVisitor(clsReader.className, this, traceClassVisitor)
        clsReader.accept(myClassVisitor, ClassReader.EXPAND_FRAMES)
        val classDef = curClassDefinition
        if (classDef != null) {
          classDef.interfaces.addAll(clsReader.interfaces)
          classDef.superClassName = clsReader.superName.replace("/", ".")
          module.classes.add(classDef)
        }
      }
    }
    return module
  }
}
