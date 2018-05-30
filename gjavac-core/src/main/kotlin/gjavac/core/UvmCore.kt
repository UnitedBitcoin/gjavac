package gjavac.core

import gjavac.cecil.Instruction
import gjavac.cecil.MethodDefinition
import gjavac.exceptions.GjavacException
import gjavac.utils.TranslatorUtils
import gjavac.utils.use

open class UvmInstruction(val asmLine: String, var lineNumber: Int = 0, var jvmInstruction: Instruction? = null) {
    var locationLabel: String? = null
    val args: MutableList<Any> = mutableListOf()
    var evalStackOp: EvalStackOpEnum = EvalStackOpEnum.NotEvalStackOp  //add by zq
    override fun toString(): String {
        return asmLine
    }

    fun hasLocationLabel(): Boolean {
        val label = locationLabel
        return label != null && label.length > 0
    }

    fun opName(): String {
        val splited = asmLine.split(' ')
        if (splited.isNotEmpty()) {
            return splited[0].trim().toLowerCase()
        } else {
            return ""
        }
    }

    fun getArg(index: Int): Any? {
        val splited = asmLine.split(' ')
        if (splited.isNotEmpty()) {
            val argsCount = splited.size - 1
            if (argsCount > index) {
                return splited[index + 1]
            }
        }
        return null
//        if (args.size > index) {
//            return args[index]
//        } else {
//            return null
//        }
    }

    // opcode是否是修改第一个参数的register的内容
    fun isSetFirstArgOp(): Boolean {
        return when (opName().toLowerCase()) {
            "push" -> false
            "jmp" -> false
            else -> true
        }
    }

    fun isEvalStackChangeOp(): Boolean {
        return when (opName().toLowerCase()) {
            "push", "pop" -> true
            else -> false
        }
    }

    // 不影响现有堆对象或eval stack的指令
    fun isJustChangeSlotOp(): Boolean {
        return when(opName().toLowerCase()) {
            "move", "loadk", "loadnil", "gettable", "gettop", "getupval", "closure", "concat", "newtable", "len" -> true
            else->false
        }
    }

    // 是否有操作某个寄存器或常数的非A参数
    fun hasArgNotFirstPosition(arg: String): Boolean {
        val splitedByComment = asmLine.split(';')
        if (splitedByComment.isEmpty())
            return false
        val line = splitedByComment[0].trim()
        val splited = line.split(' ')
        if (splited.size <= 2)
            return false
        for (i in 2..(splited.size - 1)) {
            if (splited[i].trim().toLowerCase() == arg.trim().toLowerCase())
                return true
        }
        return false
    }

    fun usedSlotsWithPosition(): List<Int?> {
        val splitedByComment = asmLine.split(';')
        if (splitedByComment.isEmpty())
            return listOf()
        val line = splitedByComment[0].trim()
        val splited = line.split(' ')
        if (splited.size < 2)
            return listOf()
        val slots = mutableListOf<Int?>()
        for (i in 1..(splited.size - 1)) {
            val arg = splited[i].trim().toLowerCase()
            if (arg.startsWith('%') && arg.length > 1 && arg.matches(Regex("%\\d+"))) {
                val argSlot = arg.substring(1).toInt()
                slots += argSlot
            } else {
                slots += null as Int?
            }
        }
        when (opName().toLowerCase()) {
            "call", "tailcall" -> {
                // call/tailcall %a argsCount returnCount
                val startSlot = slots[0]!!
                val argsCount = splited[2].trim().toInt()
                val returnCount = splited[3].trim().toInt() - 1
                val maxAffectSlotsCount = if (argsCount >= (returnCount - 1)) argsCount else (returnCount - 1)

                for (i in 1..maxAffectSlotsCount) {
                    slots += (startSlot + i)
                }
            }
            "return" -> {
                // return %a returnCount+1
                val startSlot = slots[0]!!
                val returnCount = splited[2].trim().toInt() - 1
                if (returnCount > 0) {
                    for (i in 1..(returnCount - 1)) {
                        slots += (startSlot + i)
                    }
                } else {
                    // return all slots after-or-equal startSlot
                    // now return at most 10 slots
                    for (i in 1..10) {
                        slots += (startSlot + i)
                    }
                }
            }
        }
        return slots.toList()
    }

    fun usedRegisterSlots(): List<Int> {
        val slotsWithPosition = usedSlotsWithPosition()
        return slotsWithPosition.filterNotNull()
    }

    // 本指令需要读取的slots列表
    fun needReadRegisterSlots(): List<Int> {
        val slots = usedSlotsWithPosition()
        if (slots.isEmpty())
            return listOf()
        val splitedByComment = asmLine.split(';')
        val splited = splitedByComment[0].trim().split(' ')
        when (opName().toLowerCase()) {
            "settable" -> {
                // settable %a rk(b) rk(c)
                return slots.filterNotNull()
            }
            "call", "tailcall" -> {
                // call/tailcall %a argsCount returnCount
                val startSlot = slots[0]!!
                val argsCount = splited[2].trim().toInt()
                val returnCount = splited[3].trim().toInt() -1
                val maxAffectSlotsCount = argsCount
                val mSlots = mutableListOf<Int>()
                mSlots += startSlot
                for (i in 1..maxAffectSlotsCount) {
                    mSlots += (startSlot + i)
                }
                return mSlots.toList()
            }
            "return" -> {
                // return %a returnCount+1
                val startSlot = slots[0]!!
                val returnCount = splited[2].trim().toInt() - 1
                val mSlots = mutableListOf<Int>()
                mSlots += startSlot
                if (returnCount > 0) {
                    for (i in 1..(returnCount - 1)) {
                        mSlots += (startSlot + i)
                    }
                } else {
                    // return all slots after-or-equal startSlot
                    // now return at most 10 slots
                    for (i in 1..10) {
                        mSlots += (startSlot + i)
                    }
                }
                return mSlots.toList()
            }
        }
        return if (isSetFirstArgOp()) {
            slots.subList(1, slots.size).filterNotNull()
        } else {
            slots.filterNotNull()
        }
    }

    fun needSetRegisterSlots(): List<Int> {
        val slots = usedSlotsWithPosition()
        if (slots.isEmpty()) {
            return listOf()
        }
        val splitedByComment = asmLine.split(';')
        val splited = splitedByComment[0].trim().split(' ')
        when (opName().toLowerCase()) {
            "call", "tailcall" -> {
                // call/tailcall %a argsCount returnCount
                val startSlot = slots[0]!!
                val argsCount = splited[2].trim().toInt()
                val returnCount = splited[3].trim().toInt() - 1
                val maxAffectSlotsCount = returnCount - 1
                if (returnCount > 0) {
                    val mSlots = mutableListOf<Int>()
                    mSlots += startSlot
                    for (i in 1..maxAffectSlotsCount) {
                        mSlots += (startSlot + i)
                    }
                    return mSlots.toList()
                } else {
                    return listOf()
                }
            }
            "return" -> {
                // return %a returnCount+1
                val startSlot = slots[0]!!
                val returnCount = splited[2].trim().toInt() - 1
                val mSlots = mutableListOf<Int>()
                if (returnCount > 0) {
                    for (i in 0..(returnCount - 1)) {
                        mSlots += (startSlot + i)
                    }
                } else {
                    // return all slots after-or-equal startSlot
                    // now return at most 10 slots
                    for (i in 0..9) {
                        mSlots += (startSlot + i)
                    }
                }
                return mSlots.toList()
            }
        }
        return if (isSetFirstArgOp()) {
            listOf(slots[0]).filterNotNull()
        } else {
            listOf()
        }
    }
}

class UvmEmptyInstruction(val comment: String = "") : UvmInstruction("", 0) {
    override fun toString(): String {
        return ""
    }
}

//add by zq
enum class EvalStackOpEnum(val value: Int) {
    NotEvalStackOp(0),
    AddEvalStackSize(1),
    SubEvalStackSize(2),
    GetEvalStackTop(3),
    SetEvalStackTop(4),
    AddAndSetEvalStackTop(5)
}


enum class UvmTypeInfoEnum(val value: Int) {
    LTI_OBJECT(0),
    LTI_NIL(1),
    LTI_STRING(2),
    LTI_INT(3),
    LTI_NUMBER(4),
    LTI_BOOL(5),
    LTI_TABLE(6),
    LTI_FUNCTION(7), // coroutine as function type
    LTI_UNION(8),
    LTI_RECORD(9), // 新语法, type <RecordName> = { <name> : <type> , ... }
    LTI_GENERIC(10), // 新语法，泛型类型
    LTI_ARRAY(11), // 新语法，列表类型
    LTI_MAP(12), // 新语法，单纯的哈希表，并且key类型只能是字符串
    LTI_LITERIAL_TYPE(13), // 新语法，literal type 字符串/数字/布尔值的字面值的union的类型，比如: "Male" | "Female"
    LTI_STREAM(14), // Stream类型，没有直接的字面量
    LTI_UNDEFINED(100) // 未定义值，类似undefined
}

enum class StorageValueTypes(val value: Int) {
    storage_value_null(0),
    storage_value_int(1),
    storage_value_number(2),
    storage_value_bool(3),
    storage_value_string(4),
    storage_value_stream(5),

    storage_value_unknown_table(50),
    storage_value_int_table(51),
    storage_value_number_table(52),
    storage_value_bool_table(53),
    storage_value_string_table(54),
    storage_value_stream_table(55),

    storage_value_unknown_array(100),
    storage_value_int_array(101),
    storage_value_number_array(102),
    storage_value_bool_array(103),
    storage_value_string_array(104),
    storage_value_stream_array(105),

    storage_value_userdata(201),
    storage_value_not_support(202)

}


data class UvmLocVar(val name: String, val slotIndex: Int) {
}

/**
 * upvalue name (for debug information)
 * whether it is in stack (register)
 * index of upvalue (in stack or in outer function's list)
 */
class UvmUpvaldesc(var name: String) {
    var instack: Boolean = false
    var idx: Int = 0
}

class UvmProto {
    companion object {
        var protoNameIncrementor = 0
    }

    /* name of proto */
    var name: String? = null
    /* number of fixed parameters */
    var numparams: Int = 0
    /* 2: declared vararg 1: uses vararg */
    var isvararg: Boolean = false
    /* number of registers needed by this function */
    var maxStackSize: Int = 0

    /* size of 'upvalues' */
    var sizeupvalues: Int = 0
    /* size of 'k' */
    var sizek: Int = 0
    var sizeCode: Int = 0
    var sizeLineinfo: Int = 0
    /* size of 'p' */
    var sizeP: Int = 0
    var sizeLocVars: Int = 0
    /* debug information  */
    var lineDefined: Int = 0
    /* debug information  */
    var lastLineDefined: Int = 0
    /* constants used by the function */
    val constantValues: MutableList<Object> = mutableListOf()
    /* opcodes */
    val codeInstructions: MutableList<UvmInstruction> = mutableListOf()
    /* functions defined inside the function */
    val subProtos: MutableList<UvmProto> = mutableListOf()
    /* map from opcodes to source lines (debug information) */
    val lineinfo: MutableList<Int> = mutableListOf()
    /* information about local variables (debug information) */
    val locvars: MutableList<UvmLocVar> = mutableListOf()
    /* upvalue information */
    val upvalues: MutableList<UvmUpvaldesc> = mutableListOf()
    /* used for debug information */
    var source: String? = null

    // java 指令到uvm指令的映射
    val jvmInstructionsToUvmInstructionsMap: MutableMap<Instruction, UvmInstruction> = mutableMapOf()

    val neededLocationsMap: MutableMap<Int, String> = mutableMapOf()

    var parent: UvmProto? = null

    // translating states
    var paramsStartIndex: Int = 0
    var evalStackIndex: Int = 0
    var evalStackSizeIndex: Int = 0
    var tmp1StackTopSlotIndex: Int = 0
    var tmp2StackTopSlotIndex: Int = 0
    var tmp3StackTopSlotIndex: Int = 0
    var tmpMaxStackTopSlotIndex: Int = 0
    var callStackStartIndex: Int = 0
    var maxCallStackSize: Int = 0
    var method: MethodDefinition? = null

    // 是否处于proto数据不受影响的模式（调用proto函数不会改变proto状态的模式，伪装纯函数）
    var inNotAffectMode: Boolean = false

    // 没有映射到uvm instruction的.Net IL的nop等指令的列表，为了将每条IL指令关联到uvm指令方便跳转查找，
    // 对于不产生uvm instructions的IL指令，加入这个队列等待下一个有效非空uvm instruction一起映射关联
    val notMappedJvmInstructions: MutableList<Instruction> = mutableListOf()

    constructor(name: String? = null) {
        this.name = if (name != null) name else ("tmp_" + (protoNameIncrementor++))
        this.source = ""
    }

    fun toUvmAss(isTop: Boolean = false): String {
        val builder = StringBuilder()

        // 如果是顶部proto，增加.upvalues num
        if (isTop) {
            builder.append(".upvalues " + upvalues.size + "\r\n")
        }
        if (isTop) {
            name = "main"
        }
        builder.append(".func " + name + " " + maxStackSize + " " + numparams + " " + sizeLocVars + "\r\n")

        builder.append(".begin_const\r\n")
        for (value: Any? in constantValues) {
            builder.append("\t")
            if (value == null) {
                builder.append("nil\r\n")
            } else if (value is String) {
                builder.append("\"" + TranslatorUtils.escapeToAss(value) + "\"\r\n")
            } else if (value is Int || value is Long || value is Double || value is Short || value is Byte) {
                builder.append(value.toString() + "\r\n")
            } else if (value is Boolean) {
                builder.append((if (value) "true" else "false") + "\r\n")
            } else {
                builder.append(value.toString() + "\r\n")
            }
        }
        builder.append(".end_const\r\n")

        builder.append(".begin_upvalue\r\n")
        for (upvalue in upvalues) {
            builder.append("\t" + (if (upvalue.instack) 1 else 0) + " " + upvalue.idx + " \"" + upvalue.name + "\"" + "\r\n")
        }
        builder.append(".end_upvalue\r\n")

        builder.append(".begin_code\r\n")
        for (inst in codeInstructions) {
            if (inst.hasLocationLabel()) {
                builder.append(inst.locationLabel + ":\r\n")
            }
            builder.append("\t")
            builder.append(inst.toString())
            builder.append("\r\n")
        }
        builder.append(".end_code\r\n")

        for (subProto in subProtos) {
            builder.append("\r\n")
            builder.append(subProto.toUvmAss(false))
        }
        builder.append("\r\n")
        return builder.toString()
    }

    override fun toString(): String {
        return toUvmAss()
    }

    fun findMainProto(): UvmProto? {
        if (method != null && method?.name.equals("main")) {
            return this
        }
        for (proto in subProtos) {
            var found = proto.findMainProto()
            if (found != null) {
                return found
            }
        }
        return null
    }

    fun findLocvar(name: String): UvmLocVar? {
        for (locvar in locvars) {
            if (locvar.name.equals(name)) {
                return locvar
            }
        }
        return null
    }

    // 在proto的常量池中找常量的索引，从0开始，如果常量没在proto常量池，就加入常量池再返回索引
    fun <T> internConstantValue(value: T?): Int {
        if (value == null) {
            throw GjavacException("Can't put null in constant pool")
        }
        if (inNotAffectMode) {
            return 0
        }
        if (!constantValues.contains(value as Object)) {
            constantValues.add(value as Object)
        }
        return constantValues.indexOf(value)
    }

    fun internUpvalue(upvalueName: String): Int {
        if (upvalueName.length < 1) {
            throw GjavacException("upvalue名称不能为空")
        }
        if (inNotAffectMode) {
            return 0
        }

        for (i in 0..(upvalues.size - 1)) {
            var upvalueItem = upvalues[i]
            if (upvalueItem.name == upvalueName) {
                return i
            }
        }

        val upvalue = UvmUpvaldesc(upvalueName)

        // 从上级proto中查找是否存在对应的localvars，判断instack的值
        if (parent != null) {
            val locvar = parent?.findLocvar(upvalueName)
            if (locvar != null) {
                upvalue.instack = true
                upvalue.name = upvalueName
                upvalue.idx = locvar.slotIndex
            }
        } else {
            upvalue.instack = false
            upvalue.name = upvalueName
        }
        if (!upvalue.instack) {
            // 在上层proto的upvalues中找，没有找到就返回上层的 count(upvalues),因为上层proto需要增加新upvalue
            if (parent != null) {
                val parentUpvalueIndex = parent?.internUpvalue(upvalueName)
                if (parentUpvalueIndex != null) {
                    upvalue.idx = parentUpvalueIndex
                    upvalue.instack = false
                }
            } else {
                upvalue.idx = upvalues.size
                upvalue.instack = true
            }
        }
        upvalues.add(upvalue)
        return upvalues.size - 1
    }

    fun addNotMappedILInstruction(i: Instruction) {
        if (inNotAffectMode) {
            return
        }
        notMappedJvmInstructions.add(i)
    }

    fun addInstruction(inst: UvmInstruction) {
        this.codeInstructions.add(inst)
        val instJvmInstruction = inst.jvmInstruction
        if (instJvmInstruction != null && !(inst is UvmEmptyInstruction)) {
            if (!jvmInstructionsToUvmInstructionsMap.containsKey(instJvmInstruction)) {
                jvmInstructionsToUvmInstructionsMap.put(instJvmInstruction, inst)
            }
            if (notMappedJvmInstructions.size > 0) {
                for (notMappedItem in notMappedJvmInstructions) {
                    jvmInstructionsToUvmInstructionsMap[notMappedItem] = inst
                }
                notMappedJvmInstructions.clear()
            }
        }
    }

    fun findUvmInstructionMappedByIlInstruction(ilInstruction: Instruction): UvmInstruction? {
        if (jvmInstructionsToUvmInstructionsMap.containsKey(ilInstruction)) {
            return jvmInstructionsToUvmInstructionsMap.get(ilInstruction)
        } else {
            return null
        }
    }

    fun notEmptyCodeInstructions(): MutableList<UvmInstruction> {
        val notEmptyInsts: MutableList<UvmInstruction> = mutableListOf()
        for (codeInst in codeInstructions) {
            if (!(codeInst is UvmEmptyInstruction)) {
                notEmptyInsts.add(codeInst)
            }
        }
        return notEmptyInsts
    }

    fun indexOfUvmInstruction(inst: UvmInstruction?): Int {
        if (inst == null) {
            return -1
        }
        return notEmptyCodeInstructions().indexOf(inst)
    }

    fun addInstructionLine(line: String, ilInstruction: Instruction?) {
        var inst = UvmInstruction(line, 0, ilInstruction)
        addInstruction(inst)
    }

    fun makeInstructionLine(line: String, jvmInstruction: Instruction?): UvmInstruction {
        val inst = UvmInstruction(line, 0, jvmInstruction)
        return inst
    }

    fun addEmptyInstruction(comment: String) {
        addInstruction(UvmEmptyInstruction(comment))
    }

    fun makeEmptyInstruction(comment: String): UvmInstruction {
        return UvmEmptyInstruction(comment)
    }

    /**
     * 如果已经存在这个loc对应的label，直接复用，否则用参数的label构造
     */
    fun internNeedLocationLabel(loc: Int, label: String): String {
        if (inNotAffectMode) {
            return ""
        }
        if (neededLocationsMap.containsKey(loc)) {
            return neededLocationsMap.get(loc).orEmpty()
        } else {
            neededLocationsMap.put(loc, label)
            return label
        }
    }

}
