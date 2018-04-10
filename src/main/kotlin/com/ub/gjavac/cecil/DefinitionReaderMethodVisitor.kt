package com.ub.gjavac.cecil

import com.ub.gjavac.utils.decodeFromTypeSignature
import org.objectweb.asm.*
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.util.Printer
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceSignatureVisitor

class DefinitionReaderMethodVisitor(val method: MethodDefinition, mv: MethodVisitor, val p: Textifier) : MethodVisitor(Opcodes.ASM4, mv) {

    private val buf = StringBuffer()

    /**
     * Tab for class members.
     */
    protected val tab = "  "

    /**
     * Tab for bytecode instructions.
     */
    protected val tab2 = "    "

    /**
     * Tab for table and lookup switch instructions.
     */
    protected val tab3 = "      "

    /**
     * Tab for labels.
     */
    protected val ltab = "   "

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        method.maxStack = maxStack
        method.maxLocals = maxLocals
    }

    override fun visitParameter(name: String, access: Int) {
        // TODO
        if (api < Opcodes.ASM5) {
            throw RuntimeException()
        }
        if (mv != null) {
            mv.visitParameter(name, access)
        }
    }

    override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
        method.annotations.add(decodeFromTypeSignature(desc))
        return null
    }

    override fun visitCode() {
    }

    override fun visitInsn(opcode: Int) {
        buf.setLength(0)
        buf.append(tab2).append(Printer.OPCODES[opcode]).append('\n')
        method.addInstruction(Instruction(opcode, instLine = buf.toString()))
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        buf.setLength(0)
        buf.append(tab2)
                .append(Printer.OPCODES[opcode])
                .append(' ')
                .append(if (opcode == Opcodes.NEWARRAY)
                    Printer.TYPES[operand]
                else
                    Integer
                            .toString(operand)).append('\n')
        method.addInstruction(Instruction(opcode, opArgs = mutableListOf(operand as Object), instLine = buf.toString()))
    }

    override fun visitVarInsn(opcode: Int, varCount: Int) {
        buf.setLength(0)
        buf.append(tab2).append(Printer.OPCODES[opcode]).append(' ').append(varCount)
                .append('\n')
        method.addInstruction(Instruction(opcode, opArgs = mutableListOf(varCount as Object), instLine = buf.toString()))
    }

    protected fun appendDescriptor(type: Int, desc: String?) {
        if (type == Textifier.CLASS_SIGNATURE || type == Textifier.FIELD_SIGNATURE
                || type == Textifier.METHOD_SIGNATURE) {
            if (desc != null) {
                buf.append("// signature ").append(desc).append('\n')
            }
        } else {
            buf.append(desc)
        }
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        buf.setLength(0)
        buf.append(tab2).append(Printer.OPCODES[opcode]).append(' ')
        appendDescriptor(Textifier.INTERNAL_NAME, type)
        buf.append('\n')
        method.addInstruction(Instruction(opcode, opArgs = mutableListOf(type as Object), instLine = buf.toString()))
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String,
                                desc: String) {
        buf.setLength(0)
        buf.append(tab2).append(Printer.OPCODES[opcode]).append(' ')
        appendDescriptor(Textifier.INTERNAL_NAME, owner)
        buf.append('.').append(name).append(" : ")
        appendDescriptor(Textifier.FIELD_DESCRIPTOR, desc)
        buf.append('\n')
        method.addInstruction(Instruction(opcode, opArgs = mutableListOf(FieldInfo(owner, name, desc) as Object), instLine = buf.toString()))
    }

    private fun doVisitMethodInsn(opcode: Int, owner: String,
                                  name: String, desc: String, itf: Boolean) {
        buf.setLength(0)
        buf.append(tab2).append(Printer.OPCODES[opcode]).append(' ')
        appendDescriptor(Textifier.INTERNAL_NAME, owner)
        buf.append('.').append(name).append(' ')
        appendDescriptor(Textifier.METHOD_DESCRIPTOR, desc)
        buf.append('\n')
        // TODO: linenumber
        method.addInstruction(Instruction(opcode, opArgs = mutableListOf(MethodInfo(owner, name, desc) as Object), instLine = buf.toString()))
    }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String,
                                 desc: String) {
        if (api >= Opcodes.ASM5) {
            super.visitMethodInsn(opcode, owner, name, desc)
            return
        }
        doVisitMethodInsn(opcode, owner, name, desc,
                opcode == Opcodes.INVOKEINTERFACE)
    }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String,
                                 desc: String, itf: Boolean) {
        if (api >= Opcodes.ASM5) {
            super.visitMethodInsn(opcode, owner, name, desc)
            return
        }
        doVisitMethodInsn(opcode, owner, name, desc,
                opcode == Opcodes.INVOKEINTERFACE)
    }

    protected fun appendHandle(h: Handle) {
        val tag = h.tag
        buf.append("// handle kind 0x").append(Integer.toHexString(tag))
                .append(" : ")
        var isMethodHandle = false
        when (tag) {
            Opcodes.H_GETFIELD -> buf.append("GETFIELD")
            Opcodes.H_GETSTATIC -> buf.append("GETSTATIC")
            Opcodes.H_PUTFIELD -> buf.append("PUTFIELD")
            Opcodes.H_PUTSTATIC -> buf.append("PUTSTATIC")
            Opcodes.H_INVOKEINTERFACE -> {
                buf.append("INVOKEINTERFACE")
                isMethodHandle = true
            }
            Opcodes.H_INVOKESPECIAL -> {
                buf.append("INVOKESPECIAL")
                isMethodHandle = true
            }
            Opcodes.H_INVOKESTATIC -> {
                buf.append("INVOKESTATIC")
                isMethodHandle = true
            }
            Opcodes.H_INVOKEVIRTUAL -> {
                buf.append("INVOKEVIRTUAL")
                isMethodHandle = true
            }
            Opcodes.H_NEWINVOKESPECIAL -> {
                buf.append("NEWINVOKESPECIAL")
                isMethodHandle = true
            }
        }
        buf.append('\n')
        buf.append(tab3)
        appendDescriptor(Textifier.INTERNAL_NAME, h.owner)
        buf.append('.')
        buf.append(h.name)
        if (!isMethodHandle) {
            buf.append('(')
        }
        appendDescriptor(Textifier.HANDLE_DESCRIPTOR, h.desc)
        if (!isMethodHandle) {
            buf.append(')')
        }
    }

    override fun visitInvokeDynamicInsn(name: String, desc: String, bsm: Handle,
                                        vararg bsmArgs: Any) {
        buf.setLength(0)
        buf.append(tab2).append("INVOKEDYNAMIC").append(' ')
        buf.append(name)
        appendDescriptor(Textifier.METHOD_DESCRIPTOR, desc)
        buf.append(" [")
        buf.append('\n')
        buf.append(tab3)
        appendHandle(bsm)
        buf.append('\n')
        buf.append(tab3).append("// arguments:")
        if (bsmArgs.size == 0) {
            buf.append(" none")
        } else {
            buf.append('\n')
            for (i in bsmArgs.indices) {
                buf.append(tab3)
                val cst = bsmArgs[i]
                if (cst is String) {
                    Printer.appendString(buf, cst)
                } else if (cst is Type) {
                    val type = cst
                    if (type.sort == Type.METHOD) {
                        appendDescriptor(Textifier.METHOD_DESCRIPTOR, type.descriptor)
                    } else {
                        buf.append(type.descriptor).append(".class")
                    }
                } else if (cst is Handle) {
                    appendHandle(cst)
                } else {
                    buf.append(cst)
                }
                buf.append(", \n")
            }
            buf.setLength(buf.length - 3)
        }
        buf.append('\n')
        buf.append(tab2).append("]\n")
        method.addInstruction(Instruction(Opcodes.INVOKEDYNAMIC, opArgs = mutableListOf(SimpleInfo(name, desc) as Object), instLine = buf.toString()))
    }

    protected var labelNames: MutableMap<Label, String>? = null
    protected var labelLines: MutableMap<Label, Int> = mutableMapOf()
    protected var labelOffsets: MutableMap<Label, Int> = mutableMapOf()

    protected fun appendLabel(l: Label) {
        if (labelNames == null) {
            labelNames = mutableMapOf()
        }
        var name: String? = labelNames?.get(l)
        if (name == null) {
            name = "L" + labelNames?.size
            labelNames?.put(l, name)
        }
        buf.append(name)
    }

    override fun visitJumpInsn(opcode: Int, label: Label) {
        buf.setLength(0)
        buf.append(tab2).append(Printer.OPCODES[opcode]).append(' ')
        appendLabel(label) // FIXME: label not resolve now
        buf.append('\n')
        method.addInstruction(Instruction(opcode, opArgs = mutableListOf(label as Object), instLine = buf.toString()))
    }

    override fun visitLabel(label: Label) {
        buf.setLength(0)
        buf.append(ltab)
        appendLabel(label)
        buf.append('\n')
        method.addLabelOffset(label, method.instructionsCount())
    }

    override fun visitLdcInsn(cst: Any) {
        buf.setLength(0)
        buf.append(tab2).append("LDC ")
        if (cst is String) {
            Printer.appendString(buf, cst)
        } else if (cst is Type) {
            buf.append(cst.descriptor).append(".class")
        } else {
            buf.append(cst)
        }
        buf.append('\n')
        // TODO: mark consts
        method.addInstruction(Instruction(Opcodes.LDC, opArgs = mutableListOf(cst as Object), instLine = buf.toString()))
    }

    override fun visitIincInsn(varIndex: Int, increment: Int) {
        buf.setLength(0)
        buf.append(tab2).append("IINC ").append(varIndex).append(' ')
                .append(increment).append('\n')
        method.addInstruction(Instruction(Opcodes.IINC, opArgs = mutableListOf(varIndex as Object, increment as Object), instLine = buf.toString()))
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label,
                                      vararg labels: Label) {
        buf.setLength(0)
        buf.append(tab2).append("TABLESWITCH\n")
        for (i in labels.indices) {
            buf.append(tab3).append(min + i).append(": ")
            appendLabel(labels[i])
            buf.append('\n')
        }
        buf.append(tab3).append("default: ")
        appendLabel(dflt)
        buf.append('\n')
        method.addInstruction(Instruction(Opcodes.TABLESWITCH, opArgs = mutableListOf(min as Object, max as Object, dflt as Object, labels as Object), instLine = buf.toString()))
    }

    override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<Label>) {
        buf.setLength(0)
        buf.append(tab2).append("LOOKUPSWITCH\n")
        for (i in labels.indices) {
            buf.append(tab3).append(keys[i]).append(": ")
            appendLabel(labels[i])
            buf.append('\n')
        }
        buf.append(tab3).append("default: ")
        appendLabel(dflt)
        buf.append('\n')
        method.addInstruction(Instruction(Opcodes.LOOKUPSWITCH, opArgs = mutableListOf(dflt as Object, keys as Object, labels as Object), instLine = buf.toString()))
    }

    override fun visitMultiANewArrayInsn(desc: String, dims: Int) {
        buf.setLength(0)
        buf.append(tab2).append("MULTIANEWARRAY ")
        appendDescriptor(Textifier.FIELD_DESCRIPTOR, desc)
        buf.append(' ').append(dims).append('\n')
        method.addInstruction(Instruction(Opcodes.MULTIANEWARRAY, opArgs = mutableListOf(desc as Object, dims as Object), instLine = buf.toString()))
    }

    override fun visitLocalVariable(name: String, desc: String, signature: String?,
                                    start: Label, end: Label, index: Int) {
        buf.setLength(0)
        buf.append(tab2).append("LOCALVARIABLE ").append(name).append(' ')
        appendDescriptor(Textifier.FIELD_DESCRIPTOR, desc)
        buf.append(' ')
        appendLabel(start)
        buf.append(' ')
        appendLabel(end)
        buf.append(' ').append(index).append('\n')

        if (signature != null) {
            buf.append(tab2)
            appendDescriptor(Textifier.FIELD_SIGNATURE, signature)

            val sv = TraceSignatureVisitor(0)
            val r = SignatureReader(signature)
            r.acceptType(sv)
            buf.append(tab2).append("// declaration: ")
                    .append(sv.declaration).append('\n')
        }
        // TODO: mark
    }

    override fun visitLineNumber(line: Int, start: Label) {
        buf.setLength(0)
        buf.append(tab2).append("LINENUMBER ").append(line).append(' ')
        appendLabel(start)
        buf.append('\n')
        labelLines[start] = line
        method.lastLineNumber = line
    }

}
