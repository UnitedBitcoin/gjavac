package com.ub.gjavac.translater

import com.google.gson.Gson
import com.ub.gjavac.exceptions.GjavacException
import com.ub.gjavac.cecil.*
import com.ub.gjavac.core.*
import com.ub.gjavac.lib.*
import com.ub.gjavac.utils.TranslatorUtils
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

open class JavaToUvmTranslator {
    val generatedInstructions: MutableList<UvmInstruction> = mutableListOf()
    val eventNames: MutableList<String> = mutableListOf()
    val contractApiNames: MutableList<String> = mutableListOf()
    val contractOfflineApiNames: MutableList<String> = mutableListOf()
    val contractStoragePropertiesTypes: MutableMap<String, StorageValueTypes> = mutableMapOf()
    val contractApiArgsTypes: MutableMap<String, MutableList<UvmTypeInfoEnum>> = mutableMapOf()
    var contractType: ClassDefinition? = null
    val definedTypes: MutableList<ClassDefinition> = mutableListOf()

    private val gson = Gson()

    /**
     * 获取一些元信息，比如emit的event names, 合约的apis, 合约的offline apis
     */
    fun getMetaInfoJson(): String {
        val info: MutableMap<String, Any> = mutableMapOf()
        info["event"] = eventNames as Any
        info["api"] = contractApiNames as Any
        info["offline_api"] = contractOfflineApiNames as Any
        val storagePropertiesTypesArray: MutableList<MutableList<Any>> = mutableListOf()
        for (key in contractStoragePropertiesTypes.keys) {
            storagePropertiesTypesArray.add(mutableListOf(key as Any, contractStoragePropertiesTypes.get(key)?.value as Any))
        }
        info["storage_properties_types"] = storagePropertiesTypesArray as Any
        val contractApiArgsTypesArray: MutableList<MutableList<Any>> = mutableListOf()
        for (key in contractApiArgsTypes.keys) {
            contractApiArgsTypesArray.add(mutableListOf(key as Any, contractApiArgsTypes.get(key)?.map { t -> t.value } as Any))
        }
        info.put("api_args_types", contractApiArgsTypesArray as Any)
        return gson.toJson(info)
    }

    fun translateModule(module: ModuleDefinition, jvmContentBuilder: StringBuilder, luaAsmBuilder: StringBuilder) {
        generatedInstructions.clear()
        eventNames.clear()
        contractApiNames.clear()
        contractOfflineApiNames.clear()
        contractStoragePropertiesTypes.clear()
        contractApiArgsTypes.clear()
        definedTypes.clear()
        this.contractType = null;

        definedTypes.clear()
        definedTypes.addAll(module.classes)

        val utilTypes = module.classes.filter { !TranslatorUtils.isMainClass(it) && !TranslatorUtils.isContractType(it) && TranslatorUtils.isComponentClass(it) }

        val mainTypes = module.classes.filter { TranslatorUtils.isMainClass(it) }
        if (mainTypes.size != 1) {
            throw GjavacException("必需提供1个且只有1个main方法(非static)的类型")
        }
        val eventEmitterTypes = module.classes.filter { TranslatorUtils.isEventEmitterType(it) }
        for (emitterType in eventEmitterTypes) {
            val eventNames = TranslatorUtils.getEmitEventNamesFromEventEmitterType(emitterType)
            // TODO: event in eventEmitter
            for (eventName in eventNames) {
                if (!this.eventNames.contains(eventName)) {
                    this.eventNames.add(eventName)
                }
            }
        }
        val contractTypes = module.classes.filter { TranslatorUtils.isContractType(it) }
        if (contractTypes.size > 1) {
            throw GjavacException("暂时不支持一个文件中定义超过1个合约类型")
        }
        // 合约类型不直接定义，但是MainType里用到合约类型时，调用构造函数时需要去调用对应构造函数并设置各成员函数
        if (contractTypes.size > 0) {
            this.contractType = contractTypes[0]
        }
        val contractType_ = this.contractType
        if (contractType_ == null) {
            throw GjavacException("合约类型不可为空")
        }
        if (TranslatorUtils.isMainClass(contractType_)) {
            throw GjavacException("合约类型不能直接包含main方法，请另外定义一个类型包含main方法")
        }

        // 抽取合约API列表和offline api列表
        TranslatorUtils.loadContractTypeInfo(contractType_, contractApiNames, contractOfflineApiNames, contractApiArgsTypes)

        if (contractType_.superClassName != null && contractType_.superClassName == UvmContract::class.java.canonicalName) {
            val contractAnnotation = Class.forName(contractType_.name.replace("/", ".")).declaredAnnotations.filter { t -> Contract::class.java.isAssignableFrom(t.javaClass) }.firstOrNull() as Contract?
            if (contractAnnotation == null) {
                throw GjavacException("contract class must have @Contract annotation, but class ${contractType_.name} not")
            }
            Contract::storage.get(contractAnnotation)
            val contractAnnotationTypeName = contractAnnotation.toString()
            val storageTypeName = contractAnnotationTypeName.substring(contractAnnotationTypeName.indexOf("(storage=class ")+"(storage=class ".length, contractAnnotationTypeName.lastIndexOf(")"))
            val storageType = Class.forName(storageTypeName)
            for (storageField in storageType.declaredFields) {
                val storagePropName = storageField.name
                val storageValueType = TranslatorUtils.getStorageValueTypeFromTypeName(storageField.type.canonicalName)
                this.contractStoragePropertiesTypes[storagePropName] = storageValueType
            }
            for (propMethod in storageType.declaredMethods) {
                val propName = TranslatorUtils.getFieldNameFromProperty(propMethod.name)
                if (propName.isEmpty())
                    continue
                if (propMethod.returnType.toString() == "void")
                    continue
                if (!propName.startsWith("get") && !propName.startsWith("set") && !propName.startsWith("is"))
                    continue
                val storagePropName = propName
                val storageValueType = TranslatorUtils.getStorageValueTypeFromTypeName(propMethod.returnType.canonicalName)
                this.contractStoragePropertiesTypes[storagePropName] = storageValueType
            }
        }

        // TODO: other utils types

        val buildResultProtos = mutableListOf<UvmProto>()
        var mainProto: UvmProto? = null
        for (typeDefinition in mainTypes) {
            val proto = translateJvmType(typeDefinition, jvmContentBuilder, luaAsmBuilder, true, null)
            buildResultProtos.add(proto)
            mainProto = proto
        }
        var contractProto: UvmProto? = null
        if (contractType_ != null) {
            contractProto = translateJvmType(contractType_, jvmContentBuilder, luaAsmBuilder, false, mainProto?.findMainProto())
        }
        for (utilType in utilTypes) {
            val proto = translateJvmType(utilType, jvmContentBuilder, luaAsmBuilder, false, contractProto ?: mainProto)
        }
        for (proto in buildResultProtos) {
            luaAsmBuilder.append(proto.toUvmAss(true))
        }
        if (contractProto != null) {
            // FIXME: duplicate
//            luaAsmBuilder.append(contractProto.toUvmAss(false))
        }
    }

    fun translateJvmType(typeDefinition: ClassDefinition, jvmContentBuilder: StringBuilder,
                         luaAsmBuilder: StringBuilder, isMainType: Boolean, parentProto: UvmProto?): UvmProto {
        val proto = UvmProto(TranslatorUtils.makeProtoNameOfTypeConstructor(typeDefinition))
        proto.parent = parentProto
        if (parentProto != null) {
            parentProto.subProtos.add(proto)
        }
        // 把类型转换成的proto被做成有一些slot指向成员函数的构造函数，保留slot指向成员函数是为了方便子对象upval访问(不一定需要)
        var tableSlot = 0;
        proto.addInstructionLine("newtable %" + tableSlot + " 0 0", null)
        var tmp1Slot = typeDefinition.methods.size + 1;
        for (m in typeDefinition.methods) {
            var methodProto = translateJvmMethod(m, jvmContentBuilder, luaAsmBuilder, proto)
            if (methodProto == null) {
                continue
            }
            // 把各成员函数加入slots
            proto.internConstantValue(methodProto.name)
            var slotIndex = proto.subProtos.size + 1
            proto.addInstructionLine("closure %" + slotIndex + " " + methodProto.name, null)
            proto.internConstantValue(m.name)
            proto.addInstructionLine("loadk %" + tmp1Slot + " const \"" + m.name + "\"", null)
            proto.addInstructionLine(
                    "settable %" + tableSlot + " %" + tmp1Slot + " %" + slotIndex, null)
            val methodProtoName = methodProto.name
            if (methodProtoName == null) {
                throw GjavacException("null method proto name")
            }
            proto.locvars.add(UvmLocVar(methodProtoName, slotIndex))
            proto.subProtos.add(methodProto)
        }

        proto.maxStackSize = tmp1Slot + 1
        val mainProto = proto.findMainProto()
        if (mainProto != null && isMainType) {
            proto.maxStackSize = tmp1Slot + 4;
            var mainFuncSlot = proto.subProtos.size + 2; // proto.SubProtos.IndexOf(mainProto) + 1;
            proto.addInstructionLine("closure %" + mainFuncSlot + " " + mainProto.name, null)
            proto.addInstructionLine("move %" + (mainFuncSlot + 1) + " %0", null)
            var returnCount = if (mainProto.method?.signature?.returnType?.fullName() != "void") 1 else 0
            var argsCount = 1;
            proto.addInstructionLine("call %" + mainFuncSlot + " " + (argsCount + 1) + " " + (returnCount + 1), null)
            if (returnCount > 0) {
                proto.addInstructionLine("return %" + mainFuncSlot + " " + (returnCount + 1), null)
            }
            proto.addInstructionLine("return %0 1", null)
        } else {
            proto.addInstructionLine("return %" + tableSlot + " 2", null) // 构造函数的返回值
            proto.addInstructionLine("return %0 1", null)
        }

        return proto
    }

    fun jvmVariableNameFromDefinition(varInfo: String): String {
        return varInfo
    }

    fun jvmParamterNameFromDefinition(argInfo: String): String {
        return argInfo
    }

    fun makeJmpToInstruction(proto: UvmProto, i: Instruction, opName: String,
                             toJmpToInst: Instruction, result: MutableList<UvmInstruction>, commentPrefix: String, onlyNeedResultCount: Boolean) {
        // 满足条件，跳转到目标指令
        // 在要跳转的目标指令的前面增加 label:
        var jmpLabel = proto.name + "_to_dest_" + opName + "_" + i.offset;
        var toJmpTooffset = toJmpToInst.offset;
        if (toJmpTooffset < i.offset) {
            var uvmInstToJmp = proto.findUvmInstructionMappedByIlInstruction(toJmpToInst)
            var idx = proto.indexOfUvmInstruction(uvmInstToJmp)
            if (idx < 1 && !onlyNeedResultCount) {
                throw GjavacException("Can't find mapped instruction to jmp")
            }
            jmpLabel = proto.internNeedLocationLabel(idx, jmpLabel)
            result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel + commentPrefix + " " + opName,
                    i))
        } else {
            // 未来的指令位置
            var toJmpUvmInstsCount = 1;
            val idx1 = proto.method?.code?.indexOf(i)
            val idx2 = proto.method?.code?.indexOf(toJmpToInst)
            if (idx1 == null) {
                throw GjavacException("not found instruction " + i)
            }
            if (idx2 == null) {
                throw GjavacException("not found instruction " + toJmpToInst)
            }
            if (idx1 < 0 || idx2 < 0 || idx1 >= idx2) {
                throw GjavacException("wrong to jmp instruction index")
            }
            for (j in (idx1 + 1)..(idx2 - 1)) {
                var oldNotAffectMode = proto.inNotAffectMode;
                proto.inNotAffectMode = true;
                var uvmInsts = translateJvmInstruction(proto, proto.method!!.code[j],
                        "", true) // 因为可能有嵌套情况，这里只需要获取准确的指令数量不需要准确的指令内容
                proto.inNotAffectMode = oldNotAffectMode;
                var notEmptyGluaInstsCount = (uvmInsts.filter
                {
                    !(it is UvmEmptyInstruction)
                }).size
                toJmpUvmInstsCount += notEmptyGluaInstsCount
            }
            jmpLabel = proto.internNeedLocationLabel(toJmpUvmInstsCount + proto.notEmptyCodeInstructions().size + notEmptyGluaInstructionsCountInList(result), jmpLabel)
            result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel + commentPrefix + " " + opName,
                    i))
        }
    }

    //fun addEvalStackSizeInstructions(proto: UvmProto, i: Instruction, result: MutableList<UvmInstruction>, commentPrefix: String) {
     //   proto.internConstantValue(1)
      //  var uvmInst = proto.makeInstructionLine("add %" + proto.evalStackSizeIndex + " %" + proto.evalStackSizeIndex + " const 1" + commentPrefix, i)
      //  uvmInst.evalStackOp = EvalStackOpEnum.AddEvalStackSize
      //  result.add(uvmInst)
    //}

    //fun subEvalStackSizeInstructions(proto: UvmProto, i: Instruction, result: MutableList<UvmInstruction>, commentPrefix: String) {
     //   proto.internConstantValue(1)
      //  var uvmInst = proto.makeInstructionLine("sub %" + proto.evalStackSizeIndex + " %" + proto.evalStackSizeIndex + " const 1" + commentPrefix, i)
      //  uvmInst.evalStackOp = EvalStackOpEnum.SubEvalStackSize
      //  result.add(uvmInst)
    //}

    fun popFromEvalStackToSlot(proto: UvmProto, slotIndex: Int, i: Instruction,
                               result: MutableList<UvmInstruction>, commentPrefix: String) {
        var uvmInst = proto.makeInstructionLine("gettable %" + slotIndex + " %" + proto.evalStackIndex +
                " %" + proto.evalStackSizeIndex + commentPrefix, i)
        uvmInst.evalStackOp = EvalStackOpEnum.GetEvalStackTop
        result.add(uvmInst)
        proto.internConstantValue(1)
        uvmInst = proto.makeInstructionLine("sub %" + proto.evalStackSizeIndex + " %" + proto.evalStackSizeIndex + " const 1" + commentPrefix, i)
        uvmInst.evalStackOp = EvalStackOpEnum.SubEvalStackSize
        result.add(uvmInst)
    }

    fun pushIntoEvalStackTopSlot(proto: UvmProto, slotIndex: Int, i: Instruction,
                                 result: MutableList<UvmInstruction>, commentPrefix: String) {
        proto.internConstantValue(1)
        var uvmInst = proto.makeInstructionLine("add %" + proto.evalStackSizeIndex + " %" + proto.evalStackSizeIndex + " const 1" + commentPrefix, i)
        uvmInst.evalStackOp = EvalStackOpEnum.AddEvalStackSize
        result.add(uvmInst)
        uvmInst =proto.makeInstructionLine("settable %" + proto.evalStackIndex + " %" + proto.evalStackSizeIndex + " %" + slotIndex + commentPrefix, i)
        uvmInst.evalStackOp = EvalStackOpEnum.SetEvalStackTop
        result.add(uvmInst)
    }

    fun loadNilInstruction(proto: UvmProto, slotIndex: Int, i: Instruction, result: MutableList<UvmInstruction>, commentPrefix: String) {
        result.add(proto.makeInstructionLine(
                "loadnil %" + slotIndex + " 0" + commentPrefix, i))
    }

    fun makeArithmeticInstructions(proto: UvmProto, gluaOpName: String, i: Instruction, result: MutableList<UvmInstruction>,
                                   commentPrefix: String, convertResultTypeBoolIfInt: Boolean) {
        result.add(proto.makeEmptyInstruction(i.toString()))
        proto.internConstantValue(1)

        var arg1SlotIndex = proto.tmp3StackTopSlotIndex + 1; // top-1
        var arg2SlotIndex = proto.tmp3StackTopSlotIndex + 2; // top

        //loadNilInstruction(proto, proto.tmp3StackTopSlotIndex, i, result, commentPrefix)

        popFromEvalStackToSlot(proto, arg2SlotIndex, i, result, commentPrefix)
        popFromEvalStackToSlot(proto, arg1SlotIndex, i, result, commentPrefix)

        // 执行算术操作符，结果存入tmp2
        result.add(proto.makeInstructionLine(gluaOpName + " %" + proto.tmp2StackTopSlotIndex + " %" + arg1SlotIndex + " %" + arg2SlotIndex + commentPrefix, i))

        if (convertResultTypeBoolIfInt) {
            // 判断是否是0，如果是就是false，需要使用jmp
            proto.internConstantValue(0)
            proto.internConstantValue(true)
            proto.internConstantValue(false)
            result.add(proto.makeInstructionLine("loadk %" + proto.tmp1StackTopSlotIndex + " const false" + commentPrefix, i))
            // if tmp2==0 then pc++
            result.add(proto.makeInstructionLine("eq 0 %" + proto.tmp2StackTopSlotIndex + " const 0" + commentPrefix, i))

            var labelWhenTrue = proto.name + "_true_" + i.offset;
            var labelWhenFalse = proto.name + "_false_" + i.offset;
            labelWhenTrue = proto.internNeedLocationLabel(
                    2 + proto.notEmptyCodeInstructions().size + notEmptyGluaInstructionsCountInList(result), labelWhenTrue)

            result.add(proto.makeInstructionLine("jmp 1 $" + labelWhenTrue + commentPrefix, i))
            labelWhenFalse =
                    proto.internNeedLocationLabel(
                            2 + proto.notEmptyCodeInstructions().size + notEmptyGluaInstructionsCountInList(result), labelWhenFalse)
            result.add(proto.makeInstructionLine("jmp 1 $" + labelWhenFalse + commentPrefix, i))

            result.add(proto.makeInstructionLine("loadk %" + proto.tmp1StackTopSlotIndex + " const true" + commentPrefix, i))
            result.add(proto.makeInstructionLine("move %" + proto.tmp2StackTopSlotIndex + " %" + proto.tmp1StackTopSlotIndex + commentPrefix, i))
        }

        // 把add结果存入eval stack
        pushIntoEvalStackTopSlot(proto,proto.tmp2StackTopSlotIndex,i,result,commentPrefix)
    }

    fun makeCompareInstructions(proto: UvmProto, compareType: String, i: Instruction, result: MutableList<UvmInstruction>,
                                commentPrefix: String) {
        // 从eval stack弹出两个值(top和top-1)，比较大小，比较结果存入eval stack
        result.add(proto.makeEmptyInstruction(i.toString()))

        // 消耗eval stack的顶部2个值, 然后比较，比较结果存入eval stack
        // 获取eval stack顶部的值
        proto.internConstantValue(1)
        var arg1SlotIndex = proto.tmp3StackTopSlotIndex + 1; // top
        var arg2SlotIndex = proto.tmp3StackTopSlotIndex + 2; // top-1

        popFromEvalStackToSlot(proto,arg1SlotIndex,i,result,commentPrefix)

        // 再次获取eval stack栈顶的值
        popFromEvalStackToSlot(proto,arg2SlotIndex,i,result,commentPrefix)

        // 比较arg1和arg2
        // glua的lt指令: if ((RK(B) <  RK(C)) ~= A) then pc++
        if (compareType == "gt") {
            result.add(proto.makeInstructionLine(
                    "lt 0 %" + arg1SlotIndex + " %" + arg2SlotIndex +
                            commentPrefix, i))
        } else if (compareType == "lt") {
            // lt: if ((RK(B) <  RK(C)) ~= A) then pc++
            result.add(proto.makeInstructionLine(
                    "lt 0 %" + arg2SlotIndex + " %" + arg1SlotIndex +
                            commentPrefix, i))
        } else if (compareType == "ne") {
            result.add(proto.makeInstructionLine(
                    "eq 1 %" + arg1SlotIndex + " %" + arg2SlotIndex +
                            commentPrefix, i))
        } else {
            // eq: if ((RK(B) == RK(C)) ~= A) then pc++
            result.add(proto.makeInstructionLine(
                    "eq 0 %" + arg1SlotIndex + " %" + arg2SlotIndex +
                            commentPrefix, i))
        }
        // 满足条件就执行下下条指令(把1压eval stack栈)，否则执行下条jmp指令(把0压eval stack栈)
        // 构造下条jmp指令和下下条指令
        var jmpLabel1 = proto.name + "_1_cmp_" + i.offset;
        var offsetOfInst1 = 2; // 如果比较失败，跳转到把0压eval-stack栈的指令
        jmpLabel1 = proto.internNeedLocationLabel(offsetOfInst1 + proto.notEmptyCodeInstructions().size + notEmptyGluaInstructionsCountInList(result), jmpLabel1)
        result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel1 + commentPrefix,
                i))

        var jmpLabel2 = proto.name + "_2_cmp_" + i.offset;
        var offsetOfInst2 = 5; // 如果比较成功，跳转到把1压eval-stack栈的指令
        jmpLabel2 = proto.internNeedLocationLabel(offsetOfInst2 + proto.notEmptyCodeInstructions().size + notEmptyGluaInstructionsCountInList(result), jmpLabel2)
        result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel2 + commentPrefix,
                i))

        proto.internConstantValue(0)
        proto.internConstantValue(1)


        // 把结果0存入eval stack
        result.add(proto.makeInstructionLine(
                "loadk %" + proto.tmp2StackTopSlotIndex + " const 0" + commentPrefix, i))
        pushIntoEvalStackTopSlot(proto,proto.tmp2StackTopSlotIndex,i,result,commentPrefix)

        // jmp到压栈第1个分支后面
        var jmpLabel3 = proto.name + "_3_cmp_" + i.offset;
        var offsetOfInst3 = 4;
        jmpLabel3 = proto.internNeedLocationLabel(offsetOfInst3 + proto.notEmptyCodeInstructions().size + notEmptyGluaInstructionsCountInList(result), jmpLabel3)
        result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel3 + commentPrefix,
                i))

        // 把结果1存入eval stack

        result.add(proto.makeInstructionLine(
                "loadk %" + proto.tmp3StackTopSlotIndex + " const 1" + commentPrefix, i))
        pushIntoEvalStackTopSlot(proto,proto.tmp3StackTopSlotIndex,i,result,commentPrefix)

        result.add(proto.makeEmptyInstruction(""))
    }

    /**
     * 读取并弹出eval stack top-1(table)和top(value)值，top值弹出, 执行table写属性操作，然后table压栈回到eval-stack
     */
    fun makeSetTablePropInstructions(proto: UvmProto, propName: String, i: Instruction, result: MutableList<UvmInstruction>,
                                     commentPrefix: String, needConvtToBool: Boolean) {
        proto.internConstantValue(propName)
        var tableSlot = proto.tmp2StackTopSlotIndex + 1;
        var valueSlot = proto.tmp2StackTopSlotIndex + 2;

        // 加载value

        popFromEvalStackToSlot(proto,valueSlot,i,result,commentPrefix)
        // 对于布尔类型，因为.net中布尔类型参数加载的时候用的ldc.i，加载的是整数，所以这里要进行类型转换成bool类型，使用 not not a来转换
        if (needConvtToBool) {
            result.add(proto.makeInstructionLine("not %" + valueSlot + " %" + valueSlot + commentPrefix, i))
            result.add(proto.makeInstructionLine("not %" + valueSlot + " %" + valueSlot + commentPrefix, i))
        }

        // 加载table
        //result.add(proto.makeInstructionLine("gettable %" + tableSlot + " %" + proto.evalStackIndex + " %" + proto.evalStackSizeIndex + commentPrefix, i))

        popFromEvalStackToSlot(proto,tableSlot,i,result,commentPrefix)

        // settable
        result.add(proto.makeInstructionLine(
                "loadk %" + proto.tmp2StackTopSlotIndex + " const \"" + propName + "\"" + commentPrefix, i))
        result.add(proto.makeInstructionLine(
                "settable %" + tableSlot + " % " + proto.tmp2StackTopSlotIndex + " %" + valueSlot + commentPrefix, i))
    }

    /**
     * 读取eval stack top(table)值，执行table读属性操作,读取结果放入eval stack
     */
    fun makeGetTablePropInstructions(proto: UvmProto, propName: String, i: Instruction, result: MutableList<UvmInstruction>,
                                     commentPrefix: String, needConvtToBool: Boolean) {
        proto.internConstantValue(propName)
        var tableSlot = proto.tmp2StackTopSlotIndex + 1
        var valueSlot = proto.tmp2StackTopSlotIndex + 2

        // 加载table
        popFromEvalStackToSlot(proto,tableSlot,i,result,commentPrefix)

        // gettable
        result.add(proto.makeInstructionLine(
                "loadk %" + proto.tmp2StackTopSlotIndex + " const \"" + propName + "\"" + commentPrefix, i))
        result.add(proto.makeInstructionLine(
                "gettable %" + valueSlot + " % " + tableSlot + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))

        // 对于布尔类型，因为.net中布尔类型参数加载的时候用的ldc.i，加载的是整数，所以这里要进行类型转换成bool类型，使用 not not a来转换
        if (needConvtToBool) {
            result.add(proto.makeInstructionLine("not %" + valueSlot + " %" + valueSlot + commentPrefix, i))
            result.add(proto.makeInstructionLine("not %" + valueSlot + " %" + valueSlot + commentPrefix, i))
        }
        proto.internConstantValue(1)
        // value放回eval stack
        pushIntoEvalStackTopSlot(proto,valueSlot,i,result,commentPrefix)

    }

    /**
     * 单元操作符转换成指令
     */
    fun makeSingleArithmeticInstructions(proto: UvmProto, gluaOpName: String, i: Instruction, result: MutableList<UvmInstruction>,
                                         commentPrefix: String, convertResultTypeBoolIfInt: Boolean) {
        result.add(proto.makeEmptyInstruction(i.toString()))
        proto.internConstantValue(1)
        var arg1SlotIndex = proto.tmp3StackTopSlotIndex + 1; // top

        popFromEvalStackToSlot(proto,arg1SlotIndex,i,result,commentPrefix)

        // 执行算术操作符，结果存入tmp2
        result.add(proto.makeInstructionLine(gluaOpName + " %" + proto.tmp2StackTopSlotIndex + " %" + arg1SlotIndex + commentPrefix, i))

        if (convertResultTypeBoolIfInt) {
            // 判断是否是0，如果是就是false，需要使用jmp
            proto.internConstantValue(0)
            proto.internConstantValue(true)
            proto.internConstantValue(false)
            result.add(proto.makeInstructionLine("loadk %" + proto.tmp1StackTopSlotIndex + " const false" + commentPrefix, i))
            // if tmp2==0 then pc++
            result.add(proto.makeInstructionLine("eq 0 %" + proto.tmp2StackTopSlotIndex + " const 0" + commentPrefix, i))

            var labelWhenTrue = proto.name + "_true_" + i.offset;
            var labelWhenFalse = proto.name + "_false_" + i.offset;
            labelWhenTrue =
                    proto.internNeedLocationLabel(
                            2 + proto.notEmptyCodeInstructions().size + notEmptyGluaInstructionsCountInList(result), labelWhenTrue)

            result.add(proto.makeInstructionLine("jmp 1 $" + labelWhenTrue + commentPrefix, i))
            labelWhenFalse =
                    proto.internNeedLocationLabel(
                            2 + proto.notEmptyCodeInstructions().size + notEmptyGluaInstructionsCountInList(result), labelWhenFalse)
            result.add(proto.makeInstructionLine("jmp 1 $" + labelWhenFalse + commentPrefix, i))

            result.add(proto.makeInstructionLine("loadk %" + proto.tmp1StackTopSlotIndex + " const true" + commentPrefix, i))
            result.add(proto.makeInstructionLine("move %" + proto.tmp2StackTopSlotIndex + " %" + proto.tmp1StackTopSlotIndex + commentPrefix, i))
        }

        // 把add结果存入eval stack
        pushIntoEvalStackTopSlot(proto,proto.tmp2StackTopSlotIndex,i,result,commentPrefix)
    }

    //fun makeGetTopOfEvalStackInst(proto: UvmProto, i: Instruction, result: MutableList<UvmInstruction>,
      //                            targetSlot: Int, commentPrefix: String) {
      //  var uvmInst = proto.makeInstructionLine("gettable %" + targetSlot + " %" + proto.evalStackIndex +
       //         " %" + proto.evalStackSizeIndex + commentPrefix, i)
       // uvmInst.evalStackOp = EvalStackOpEnum.GetEvalStackTop
       // result.add(uvmInst)
   // }

    //fun makeSetTopOfEvalStackInst(proto: UvmProto, i: Instruction, result: MutableList<UvmInstruction>,
           //                       valueSlot: Int, commentPrefix: String) {
       // var uvmInst =proto.makeInstructionLine("settable %" + proto.evalStackIndex + " %" + proto.evalStackSizeIndex + " %" + valueSlot + commentPrefix, i)
       // uvmInst.evalStackOp = EvalStackOpEnum.SetEvalStackTop
       // result.add(uvmInst)
    //}

    fun makeLoadNilInst(proto: UvmProto, i: Instruction, result: MutableList<UvmInstruction>,
                        targetSlot: Int, commentPrefix: String) {
        result.add(proto.makeInstructionLine("loadnil %" + targetSlot + " 0" + commentPrefix, i))
    }

    fun makeLoadConstInst(proto: UvmProto, i: Instruction, result: MutableList<UvmInstruction>,
                          targetSlot: Int, value: Any, commentPrefix: String) {
        if (value is String) {
            val literalValueInGluas = TranslatorUtils.escape(value as String)
        }
        var constantIndex = proto.internConstantValue(value)
        result.add(proto.makeInstructionLine("loadk %" + targetSlot + " const " + (if (value is String) ("\"" + value + "\"") else value) + commentPrefix, i))
    }

    /**
     * 生成调用单参数单返回值的全局函数的指令
     */
    fun makeSingleArgumentSingleReturnGlobalFuncCall(proto: UvmProto, i: Instruction, result: MutableList<UvmInstruction>,
                                                     funcName: String, commentPrefix: String) {
        popFromEvalStackToSlot(proto,proto.tmp3StackTopSlotIndex,i,result,commentPrefix)

        val paramsCount = 1
        val returnCount = 1
        var envUp = proto.internUpvalue("ENV")
        proto.internConstantValue(funcName)
        result.add(proto.makeInstructionLine("gettabup %" + proto.tmp2StackTopSlotIndex + " @" + envUp + " const \"" + funcName + "\"" + commentPrefix, i))

        result.add(proto.makeInstructionLine("call %" + proto.tmp2StackTopSlotIndex + " " + (paramsCount + 1) + " " + (returnCount + 1) + commentPrefix, i))

        pushIntoEvalStackTopSlot(proto,proto.tmp2StackTopSlotIndex,i,result,commentPrefix)
    }

    fun translateJvmInstruction(proto: UvmProto, i: Instruction, commentPrefix: String, onlyNeedResultCount: Boolean): MutableList<UvmInstruction> {
        // TODO
        val result: MutableList<UvmInstruction> = mutableListOf()
        when (i.opCode) {
            Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.DALOAD, Opcodes.FALOAD, Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.SALOAD -> {
                // load reference from array

                popFromEvalStackToSlot(proto,proto.tmp2StackTopSlotIndex,i,result,commentPrefix)

                popFromEvalStackToSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)

                result.add(proto.makeInstructionLine("gettable %" + proto.tmp1StackTopSlotIndex + " %" + proto.tmp1StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))

                pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)
            }
            Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.DASTORE, Opcodes.FASTORE, Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.SASTORE -> {
                // store into reference array
                // value
                popFromEvalStackToSlot(proto,proto.tmp3StackTopSlotIndex,i,result,commentPrefix)
                // index
                popFromEvalStackToSlot(proto,proto.tmp2StackTopSlotIndex,i,result,commentPrefix)
                // arrayref
                popFromEvalStackToSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)
                result.add(proto.makeInstructionLine("settable %" + proto.tmp1StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + " %" + proto.tmp3StackTopSlotIndex + commentPrefix, i))
            }
            Opcodes.ACONST_NULL -> {
                // push null to operand stack
                makeLoadNilInst(proto, i, result, proto.tmpMaxStackTopSlotIndex, commentPrefix)

                pushIntoEvalStackTopSlot(proto,proto.tmpMaxStackTopSlotIndex,i,result,commentPrefix)
            }
            Opcodes.POP -> {
                popFromEvalStackToSlot(proto,proto.tmpMaxStackTopSlotIndex,i,result,commentPrefix)
            }
            Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 -> {
                // push integer to operand stack
                val value = i.opCode - Opcodes.ICONST_0
                makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, value, commentPrefix)
                pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix + " ldc " + value)
            }
            Opcodes.LDC -> {
                // push const value to operand stack
                var value = i.opArgs[0]
                if (value is Type) {
                    value = "" as Any
                }
                makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, value, commentPrefix)
                pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix + " ldc " + value)
            }
            Opcodes.ICONST_M1 -> {
                val value = i.opArgs[0]
                makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, value, commentPrefix)
                pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix + " ldc " + value)
            }
            Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.DLOAD, Opcodes.FLOAD, Opcodes.LLOAD -> {
                // load reference from local variable
                val variableIndex = i.opArgs[0] as Int
                val slotIndex = variableIndex
                pushIntoEvalStackTopSlot(proto,slotIndex,i,result,commentPrefix + " ldloc " + slotIndex + " var" + variableIndex)
            }
            Opcodes.ASTORE, Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.LSTORE, Opcodes.DSTORE -> {
                // store reference info into local variable
                val variableIndex = i.opArgs[0] as Int
//                val slotIndex = proto.callStackStartIndex + variableIndex
                val slotIndex = variableIndex
                // 获取eval stack的栈顶值
                popFromEvalStackToSlot(proto,slotIndex,i,result,commentPrefix)
            }
            Opcodes.ANEWARRAY -> {
                // create new array of reference
                result.add(proto.makeInstructionLine("newtable %" + proto.tmp1StackTopSlotIndex + " 0 0" + commentPrefix, i))
                pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)
            }
            Opcodes.ARETURN, Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.DRETURN, Opcodes.FRETURN, Opcodes.RETURN -> {
                // return reference from method
                val hasReturn = !(proto.method?.signature?.returnType?.fullName().orEmpty().equals("void"))
                val returnCount = if (hasReturn) 1 else 0
                if (hasReturn) {
                    popFromEvalStackToSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)
                    result.add(proto.makeInstructionLine("return %" + proto.tmp1StackTopSlotIndex + " " + (returnCount + 1) + commentPrefix, i))
                }
                result.add(proto.makeInstructionLine("return %0 1" + commentPrefix + " ret", i))
            }
            Opcodes.ARRAYLENGTH -> {
                // get length of array
                // arrayref
                popFromEvalStackToSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)
                result.add(proto.makeInstructionLine("len %" + proto.tmp2StackTopSlotIndex + " %" + proto.tmp1StackTopSlotIndex + commentPrefix, i))
                pushIntoEvalStackTopSlot(proto,proto.tmp2StackTopSlotIndex,i,result,commentPrefix)

            }
            Opcodes.ATHROW -> {
                // throw exception or error, call error(...)
                makeLoadConstInst(proto, i, result, proto.tmp2StackTopSlotIndex, "exception", commentPrefix)
                val envSlot = proto.internUpvalue("ENV")
                proto.internConstantValue("error")
                result.add(proto.makeInstructionLine("gettabup %" + proto.tmp1StackTopSlotIndex + " @" + envSlot + " const \"error\"" + commentPrefix, i))
                result.add(proto.makeInstructionLine("call %" + proto.tmp1StackTopSlotIndex + " 2 1" + commentPrefix, i))
            }
            Opcodes.BIPUSH, Opcodes.SIPUSH -> {
                // push byte
                val value = i.opArgs[0] as Int
                makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, value, commentPrefix)
                pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix+ " bipush " + value)
            }
            Opcodes.CHECKCAST -> {
                // Check whether object is of given type
                proto.addNotMappedILInstruction(i)
            }
            Opcodes.D2F, Opcodes.F2D, Opcodes.I2B, Opcodes.I2C, Opcodes.I2L, Opcodes.L2I -> {
                // not affect
                proto.addNotMappedILInstruction(i)
            }
            Opcodes.D2I, Opcodes.F2I, Opcodes.D2L, Opcodes.F2L -> {
                // convert from float to int
                // 做成tointeger(...)
                makeSingleArgumentSingleReturnGlobalFuncCall(proto, i, result, "tointeger", commentPrefix)
            }
            Opcodes.I2D, Opcodes.I2F, Opcodes.L2D, Opcodes.L2F -> {
                // convert from int to float
                // 做成tonumber(...)
                makeSingleArgumentSingleReturnGlobalFuncCall(proto, i, result, "tonumber", commentPrefix)
            }
            Opcodes.DADD, Opcodes.FADD, Opcodes.IADD, Opcodes.LADD -> {
                // add
                makeArithmeticInstructions(proto, "add", i, result, commentPrefix, false)
            }
            Opcodes.DSUB, Opcodes.FSUB, Opcodes.ISUB, Opcodes.LSUB -> {
                // sub
                makeArithmeticInstructions(proto, "sub", i, result, commentPrefix, false)
            }
            Opcodes.DMUL, Opcodes.FMUL, Opcodes.IMUL, Opcodes.LMUL -> {
                // mul
                makeArithmeticInstructions(proto, "mul", i, result, commentPrefix, false)
            }
            Opcodes.DDIV, Opcodes.FDIV, Opcodes.IDIV, Opcodes.LDIV -> {
                // div
                makeArithmeticInstructions(proto, "div", i, result, commentPrefix, false)
            }
            Opcodes.LAND -> {
                makeArithmeticInstructions(proto, "band", i, result, commentPrefix, false)
            }
            Opcodes.LOR -> {
                makeArithmeticInstructions(proto, "bor", i, result, commentPrefix, false)
            }
            Opcodes.LXOR -> {
                makeArithmeticInstructions(proto, "bxor", i, result, commentPrefix, false)
            }
            Opcodes.IINC -> {
                // i++
                val varIndex = i.opArgs[0] as Int
                val slotIndex = varIndex
                val increment = i.opArgs[1] as Int
                if (increment < 10) {
                    result.add(proto.makeInstructionLine("add %$slotIndex %$slotIndex const $increment" + commentPrefix, i))
                } else {
                    makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, increment, commentPrefix)
                    result.add(proto.makeInstructionLine("add %$slotIndex %$slotIndex const ${proto.tmp1StackTopSlotIndex}" + commentPrefix, i))
                }
            }
            Opcodes.DCMPG, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.FCMPL -> {
                // compare double
                // operand stack: ..., value1, value2 -> operand stack: ..., result
                // when <t>cmpg, if value1 > value2, then result = 1; else if value 1 == value2, then result = 0; else result = -1
                // TODO
                val op: String
                if (i.opCode == Opcodes.DCMPG || i.opCode == Opcodes.FCMPG) {
                    op = "gt"
                } else if (i.opCode == Opcodes.DCMPL || i.opCode == Opcodes.FCMPL) {
                    op = "lt"
                } else {
                    throw GjavacException("not supported opcode now " + i.opCodeName())
                }

                popFromEvalStackToSlot(proto,proto.tmp2StackTopSlotIndex,i,result,commentPrefix)
                popFromEvalStackToSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)

                proto.internConstantValue(0)
                proto.internConstantValue(1)
                proto.internConstantValue(-1)

                // 注释以op="gt"为例
                // if ((RK(B) >  RK(C)) ~= A) then pc++
                result.add(proto.makeInstructionLine("gt 0 %" + proto.tmp1StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
                var jmpLabel1 = proto.name + "_1_cmp_" + i.offset
                val offsetOfLabel1 = 2 // 跳转到区分 = 还是 < 的判断
                jmpLabel1 = proto.internNeedLocationLabel(offsetOfLabel1 + proto.notEmptyCodeInstructions().size + notEmptyGluaInstructionsCountInList(result), jmpLabel1)
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel1 + commentPrefix,
                        i))

                var jmpLabel2 = proto.name + "_2_cmp_" + i.offset;
                var offsetOfInst2 = 6; // 跳转到把1压eval-stack栈的指令
                jmpLabel2 = proto.internNeedLocationLabel(offsetOfInst2 + proto.notEmptyCodeInstructions().size + notEmptyGluaInstructionsCountInList(result), jmpLabel2)
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel2 + commentPrefix,
                        i))

                // 区分等于还是小于
                // if ((RK(B) eq  RK(C)) ~= A) then pc++
                result.add(proto.makeInstructionLine("eq 0 %" + proto.tmp1StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
                var jmpLabel3 = proto.name + "_3_cmp_" + i.offset
                val offsetOfLabel3 = 2 // 跳转到把0压operand stack栈
                jmpLabel3 = proto.internNeedLocationLabel(offsetOfLabel3 + proto.notEmptyCodeInstructions().size + notEmptyGluaInstructionsCountInList(result), jmpLabel3)
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel3 + commentPrefix,
                        i))

                var jmpLabel4 = proto.name + "_4_cmp_" + i.offset;
                var offsetOfInst4 = 5; // 如果比较成功，跳转到把-1压eval-stack栈的指令
                jmpLabel4 = proto.internNeedLocationLabel(offsetOfInst4 + proto.notEmptyCodeInstructions().size + notEmptyGluaInstructionsCountInList(result), jmpLabel4)
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel4 + commentPrefix, i))

                var jmpLabel5 = proto.name + "_5_cmp_" + i.offset
                var offsetOfInst5 = 5; // 跳转到本jvm指令的end
                jmpLabel5 = proto.internNeedLocationLabel(offsetOfInst5 + proto.notEmptyCodeInstructions().size + notEmptyGluaInstructionsCountInList(result), jmpLabel5)

                result.add(proto.makeInstructionLine("loadk %" + proto.tmp3StackTopSlotIndex + " const 0" + commentPrefix, i))
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel5 + commentPrefix, i))

                result.add(proto.makeInstructionLine("loadk %" + proto.tmp3StackTopSlotIndex + " const " + (if (op == "gt") 1 else -1) + commentPrefix, i))
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel5 + commentPrefix, i))

                result.add(proto.makeInstructionLine("loadk %" + proto.tmp3StackTopSlotIndex + " const " + (if (op == "gt") -1 else 1) + commentPrefix, i))

                pushIntoEvalStackTopSlot(proto,proto.tmp3StackTopSlotIndex,i,result,commentPrefix)
            }
            Opcodes.NEW -> {
                // 如果是String或StringBuilder或StringBuffer, push ""
                val operand = if (i.opArgs.size > 0) i.opArgs[0] else null
                if (i.opArgs.size > 0 && operand != null && (operand.javaClass == String::class.java)) {
                    val calledTypeName = (operand as String).replace("/", ".")
                    if (calledTypeName == String::class.java.canonicalName
                            || calledTypeName == StringBuilder::class.java.canonicalName
                            || calledTypeName == StringBuffer::class.java.canonicalName) {

                        proto.internConstantValue("")
                        makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, "", commentPrefix)

                        pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix + " newstr")

                        return result
                    }
                }
                // 如果是contract类型 or with @Component type,调用构造函数，而不是设置各成员函数
                if (operand != null && operand.javaClass == String::class.java
                        && ((operand.equals(this.contractType?.name)
                        && this.contractType != null) || (TranslatorUtils.isComponentClass(Class.forName((operand as String).replace("/", ".")))))) {
                    val protoName = TranslatorUtils.makeProtoNameOfTypeConstructorByTypeName(operand as String) // 构造函数的名字
                    result.add(proto.makeInstructionLine(
                            "closure %" + proto.tmp2StackTopSlotIndex + " " + protoName, i))
                    result.add(proto.makeInstructionLine(
                            "call %" + proto.tmp2StackTopSlotIndex + " 1 2", i))
                    // 返回值(新对象处于tmp2 slot)
                } else {
                    // 创建一个空的未初始化对象放入eval-stack顶
                    // 获取eval stack顶部的值
                    result.add(proto.makeInstructionLine("newtable %" + proto.tmp2StackTopSlotIndex + " 0 0" + commentPrefix, i))
                }

                pushIntoEvalStackTopSlot(proto,proto.tmp2StackTopSlotIndex,i,result,commentPrefix + " newobj")
            }
            Opcodes.DUP -> {
                // 把eval stack栈顶元素复制一份到eval-stack顶
                // 获取eval stack顶部的值
                popFromEvalStackToSlot(proto,proto.tmp2StackTopSlotIndex,i,result,commentPrefix)
                pushIntoEvalStackTopSlot(proto,proto.tmp2StackTopSlotIndex,i,result,commentPrefix + " dup")
                pushIntoEvalStackTopSlot(proto,proto.tmp2StackTopSlotIndex,i,result,commentPrefix + " dup")
            }
            Opcodes.NOP -> {
                proto.addNotMappedILInstruction(i)
                return result
            }
            Opcodes.GETSTATIC -> {
                result.add(proto.makeInstructionLine("newtable %" + proto.tmp1StackTopSlotIndex + " 0 0" + commentPrefix, i))

                pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix + i.instLine)
                return result
            }
            Opcodes.GETFIELD -> {
                val fieldInfo = i.opArgs[0] as FieldInfo
                val fieldName = fieldInfo.name
                val needConvToBool = fieldInfo.desc == "Z"
                proto.internConstantValue(fieldName)
                makeGetTablePropInstructions(proto, fieldName, i, result, commentPrefix, needConvToBool)
            }
            Opcodes.INVOKESTATIC, Opcodes.INVOKESPECIAL, Opcodes.INVOKEDYNAMIC, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE -> {
                // TODO: invoke a static method and puts the result on the stack (might be void) the method is identified by method reference index in constant pool (indexbyte1 << 8 + indexbyte2)
                // [arg1, arg2, ...] → result
                result.add(proto.makeEmptyInstruction(i.toString()))
                val operand = i.opArgs[0] as MethodInfo
                val calledMethod = operand
                val methodName = calledMethod.name
                val calledType = Class.forName(operand.owner.replace("/", "."))
                val calledTypeName = operand.owner.replace("/", ".")
                val methodInfo = JavaTypeDesc.parse(operand.desc)
                val methodParams = methodInfo.methodArgs
                var paramsCount = methodParams.size
                var hasThis = i.opCode != Opcodes.INVOKESTATIC
                val hasReturn = methodInfo.methodReturnType != null && !methodInfo.methodReturnType?.desc.equals("V")
                var needPopFirstArg = false // 一些函数，比如import module的函数，因为用object成员函数模拟，而在glua中是table中属性的函数，所以.net中多传了个this对象
                var returnCount = if (hasReturn) 1 else 0
                var isUserDefineFunc = false; // 如果是本类中要生成glua字节码的方法，这里标记为true
                var isUserDefinedInTableFunc = false; // 是否是模拟table的类型中的成员方法，这样的函数需要gettable取出函数再调用
                var targetModuleName = ""; // 转成lua后对应的模块名称，比如可能转成print，也可能转成table.concat等
                var targetFuncName = ""; // 全局方法名或模块中的方法名，或者本模块中的名称
                var useOpcode = false;
                var needNeedPopThis = false
                if (methodName == "<init>") {
                    // constructor
                    // pop arguments, push newtable
                    //makeLoadNilInst(proto, i, result, proto.tmp1StackTopSlotIndex, commentPrefix)
                    for (j in 0..(methodInfo.methodArgs.size - 1)) {
                        popFromEvalStackToSlot(proto,proto.tmpMaxStackTopSlotIndex,i,result,commentPrefix)
                    }
                    popFromEvalStackToSlot(proto,proto.tmpMaxStackTopSlotIndex,i,result,commentPrefix)
                    return result
                }
                if (calledTypeName == "kotlin.jvm.internal.Intrinsics") {
                    if (methodName == "checkParameterIsNotNull") {
                        makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, true, commentPrefix)
                        pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix + " checkParameterIsNotNull")
                    } else if (methodName == "areEqual") {
                        // pop 2 args and push true
                        //subEvalStackSizeInstructions(proto, i, result, commentPrefix)
                        //proto.internConstantValue(true)
                        //makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, true, commentPrefix)
                        //makeSetTopOfEvalStackInst(proto, i, result, proto.tmp1StackTopSlotIndex, commentPrefix)
                        popFromEvalStackToSlot(proto,proto.tmpMaxStackTopSlotIndex,i,result,commentPrefix)
                        popFromEvalStackToSlot(proto,proto.tmpMaxStackTopSlotIndex,i,result,commentPrefix)
                        makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, true, commentPrefix)
                        pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)
                    } else {
                        throw GjavacException("not implemented kotlink internal Intrinsics")
                    }
                    return result
                }
                if (calledTypeName == "java.io.PrintStream") {
                    if (methodName == "print" || methodName == "println") {
                        targetFuncName = "print"
                    }
                    hasThis = false
                    needNeedPopThis = true
                } else if ((calledTypeName == java.lang.Integer::class.java.canonicalName
                        || calledTypeName == java.lang.Long::class.java.canonicalName
                        || calledTypeName == java.lang.Float::class.java.canonicalName
                        || calledTypeName == java.lang.Double::class.java.canonicalName
                        || calledTypeName == java.lang.String::class.java.canonicalName)
                        && (methodName == "valueOf" || methodName == "longValue" || methodName=="toLong"
                        || methodName == "intValue" || methodName == "toInt"
                        || methodName == "floatValue" || methodName == "toFloat"
                        || methodName == "doubleValue" || methodName == "toDouble")) {
                    proto.addNotMappedILInstruction(i)
                    return result
                } else if (methodName == "toString") {
                    targetFuncName = "tostring"
                    returnCount = 1
                } else if (calledTypeName == StringBuilder::class.java.canonicalName
                        || calledTypeName == StringBuffer::class.java.canonicalName) {
                    if (methodName == "append") {
                        // 连接字符串可以直接使用op_concat指令
                        targetFuncName = "concat"
                        useOpcode = true
                        hasThis = false // FIXME
                        paramsCount = 2
                    } else {
                        throw GjavacException("not supported method " + calledTypeName + "::" + methodName)
                    }
                } else if (calledTypeName == String::class.java.canonicalName) {
                    if (methodName == "concat") // TODO
                    {
                        // 连接字符串可以直接使用op_concat指令
                        targetFuncName = "concat";
                        useOpcode = true;
                        hasThis = false;
                        if (paramsCount == 1) {
                            targetFuncName = "tostring"; // 只有一个参数的情况下，当成tostring处理
                            useOpcode = false;
                        }
                    } else if (methodName == "op_Equality") // TODO
                    {
                        makeCompareInstructions(proto, "eq", i, result, commentPrefix)
                        return result;
                    } else if (methodName == "op_Inequality") // TODO
                    {
                        makeCompareInstructions(proto, "ne", i, result, commentPrefix)
                        return result;
                    } else if (methodName == "get_Length") {
                        targetFuncName = "len";
                        useOpcode = true;
                        hasThis = true;
                    } else {
                        throw GjavacException("not supported method " + calledTypeName + "::" + methodName)
                    }
                    // TODO: 其他字符串特殊函数
                } else if (calledTypeName.equals(proto.method?.signature?.classDef?.name?.replace('/', '.'))) {
                    // 调用本类型的方法
                    isUserDefineFunc = true
                    targetFuncName = methodName
                    isUserDefinedInTableFunc = false
                } else if (calledTypeName == UvmCoreLibs::class.java.canonicalName) {
                    // core lib functions, eg. print, pprint
                    when (methodName) {
                        "caller" -> {
                            proto.internConstantValue("caller")
                            val envIndex = proto.internUpvalue("ENV")
                            val globalPropName = "caller"
                            result.add(proto.makeInstructionLine("gettabup %" + proto.tmp1StackTopSlotIndex + " @" + envIndex + " const \"" + globalPropName + "\"" + commentPrefix, i))
                            pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix )
                            return result
                        }
                        "caller_address" -> {
                            proto.internConstantValue("caller_address")
                            val envIndex = proto.internUpvalue("ENV")
                            val globalPropName = "caller_address"
                            result.add(proto.makeInstructionLine("gettabup %" + proto.tmp1StackTopSlotIndex + " @" + envIndex + " const \"" + globalPropName + "\"" + commentPrefix, i))
                            pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix )
                            return result
                        }
                        "debug" -> {
                            result.addAll(debugEvalStack(proto))
                            return result
                        }
                        "set_mock_contract_balance_amount" -> {
                            proto.addNotMappedILInstruction(i)
                            return result
                        }
                        "importModule" -> {
                            targetFuncName = "require"
                            // pop second last argument of module class
                            //makeGetTopOfEvalStackInst(proto, i, result, proto.tmp1StackTopSlotIndex, commentPrefix)
                            //subEvalStackSizeInstructions(proto, i, result, commentPrefix)
                            //makeSetTopOfEvalStackInst(proto, i, result, proto.tmp1StackTopSlotIndex, commentPrefix)

                            popFromEvalStackToSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)
                            popFromEvalStackToSlot(proto,proto.tmpMaxStackTopSlotIndex,i,result,commentPrefix)
                            pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)
                            paramsCount--
                        }
                        "importContract" -> {
                            targetFuncName = "import_contract"
                            // pop second last argument of contract class
                            //makeGetTopOfEvalStackInst(proto, i, result, proto.tmp1StackTopSlotIndex, commentPrefix)
                            //subEvalStackSizeInstructions(proto, i, result, commentPrefix)
                            //makeSetTopOfEvalStackInst(proto, i, result, proto.tmp1StackTopSlotIndex, commentPrefix)

                            popFromEvalStackToSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)
                            popFromEvalStackToSlot(proto,proto.tmpMaxStackTopSlotIndex,i,result,commentPrefix)
                            pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)
                            paramsCount--
                        }
                        "importContractFromAddress" -> {
                            targetFuncName = "import_contract_from_address"
                            // pop second last argument of contract class
                            //makeGetTopOfEvalStackInst(proto, i, result, proto.tmp1StackTopSlotIndex, commentPrefix)
                           // subEvalStackSizeInstructions(proto, i, result, commentPrefix)
                            //makeSetTopOfEvalStackInst(proto, i, result, proto.tmp1StackTopSlotIndex, commentPrefix)

                            popFromEvalStackToSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)
                            popFromEvalStackToSlot(proto,proto.tmpMaxStackTopSlotIndex,i,result,commentPrefix)
                            pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)
                            paramsCount--
                        }
                        "neg" -> {
                            targetFuncName = "unm"
                            useOpcode = true
                            hasThis = false
                            makeSingleArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, false)
                            return result
                        }
                        "and" -> {
                            targetFuncName = "band"
                            useOpcode = true
                            hasThis = false
                            makeArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, true)
                            return result
                        }
                        "or" -> {
                            targetFuncName = "bor"
                            useOpcode = true
                            hasThis = false
                            makeArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, true)
                            return result
                        }
                        "div" -> {
                            targetFuncName = "div"
                            useOpcode = true
                            hasThis = false
                            makeArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, false)
                            return result
                        }
                        "idiv"-> {
                            targetFuncName = "idiv"
                            useOpcode = true
                            hasThis = false
                            makeArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, false)
                            return result
                        }
                        "not"-> {
                            targetFuncName = "not";
                            useOpcode = true;
                            hasThis = false;
                            makeSingleArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, true)
                            return result
                        }
                        else -> {
                            targetFuncName = methodName
                        }
                    }
                } else if (calledTypeName == UvmArray::class.java.canonicalName || calledTypeName == UvmMap::class.java.canonicalName) {
                    val isArrayType = calledTypeName == UvmArray::class.java.canonicalName
                    if (methodName == "create") {
                        useOpcode = true
                        targetFuncName = "newtable"
                    } else if (methodName == "add") {
                        if (isArrayType) {
                            useOpcode = true
                            targetFuncName = "array.add"
                        } else {
                            useOpcode = true
                            targetFuncName = "map.add"
                        }
                    } else if (methodName == "size") {
                        useOpcode = true
                        targetFuncName = "len"
                    } else if (methodName == "set") {
                        if (isArrayType) {
                            useOpcode = true
                            targetFuncName = "array.set"
                        } else {
                            useOpcode = true
                            targetFuncName = "map.set"
                        }
                    } else if (methodName == "get") {
                        if (isArrayType) {
                            useOpcode = true
                            targetFuncName = "array.get"
                        } else {
                            useOpcode = true
                            targetFuncName = "map.get"
                        }
                    } else if (methodName == "pop") {
                        useOpcode = true
                        targetFuncName = "array.pop"
                    } else if (methodName == "pairs" && !isArrayType) {
                        useOpcode = true
                        targetFuncName = "map.pairs"
                    } else if (methodName == "ipairs" && isArrayType) {
                        useOpcode = true
                        targetFuncName = "array.ipairs"
                    } else {
                        throw GjavacException("not supported func $calledTypeName::$methodName")
                    }
                } else if (calledTypeName == ArrayIterator::class.java.canonicalName || calledTypeName == MapIterator::class.java.canonicalName) {
                    if (methodName == "invoke") {
                        useOpcode = true
                        targetFuncName = "iterator_call"
                    } else {
                        throw GjavacException("not supported func $calledTypeName::$methodName")
                    }
                } else if (calledTypeName == UvmStringModule::class.java.canonicalName) {
                    // string module's function
                    targetFuncName = UvmStringModule.libContent[methodName] ?: methodName
                    isUserDefineFunc = true
                    isUserDefinedInTableFunc = true
                    needPopFirstArg = true
                    hasThis = false
                } else if (calledTypeName == UvmMathModule::class.java.canonicalName) {
                    // math module's function
                    targetFuncName = methodName
                    isUserDefineFunc = true
                    isUserDefinedInTableFunc = true
                    needPopFirstArg = true
                    hasThis = false
                } else if (calledTypeName == UvmTableModule::class.java.canonicalName) {
                    // table module's function
                    targetFuncName = UvmTableModule.libContent[methodName] ?: methodName
                    isUserDefineFunc = true
                    isUserDefinedInTableFunc = true
                    needPopFirstArg = true
                    hasThis = false
                } else if (calledTypeName == UvmJsonModule::class.java.canonicalName) {
                    // json module's function
                    targetFuncName = methodName
                    isUserDefineFunc = true
                    isUserDefinedInTableFunc = true
                    needPopFirstArg = true
                    hasThis = false
                } else if (calledTypeName == UvmTimeModule::class.java.canonicalName) {
                    // time module's function
                    targetFuncName = methodName
                    isUserDefineFunc = true
                    isUserDefinedInTableFunc = true
                    needPopFirstArg = true
                    hasThis = false
                }
//        else if(calledTypeName == typeof(UvmCoreLib.GluaStringModule).FullName)
//        {
//          // 调用string模块的方法
//          targetFuncName = UvmCoreLib.GluaStringModule.libContent[methodName];
//          isUserDefineFunc = true;
//          isUserDefinedInTableFunc = true;
//          needPopFirstArg = true;
//          hasThis = false;
//        }
//        else if (calledTypeName == typeof(UvmCoreLib.GluaTableModule).FullName)
//        {
//          // 调用table模块的方法
//          targetFuncName = UvmCoreLib.GluaTableModule.libContent[methodName];
//          isUserDefineFunc = true;
//          isUserDefinedInTableFunc = true;
//          needPopFirstArg = true;
//          hasThis = false;
//        }
//        else if (calledTypeName == typeof(UvmCoreLib.GluaMathModule).FullName)
//        {
//          // 调用math模块的方法
//          targetFuncName = UvmCoreLib.GluaMathModule.libContent[methodName];
//          isUserDefineFunc = true;
//          isUserDefinedInTableFunc = true;
//          needPopFirstArg = true;
//          hasThis = false;
//        }
//        else if (calledTypeName == typeof(UvmCoreLib.UvmTimeModule).FullName)
//        {
//          // 调用time模块的方法
//          targetFuncName = UvmCoreLib.UvmTimeModule.libContent[methodName];
//          isUserDefineFunc = true;
//          isUserDefinedInTableFunc = true;
//          needPopFirstArg = true;
//          hasThis = false;
//        }
//        else if (calledTypeName == typeof(UvmCoreLib.UvmJsonModule).FullName)
//        {
//          // 调用json模块的方法
//          targetFuncName = UvmCoreLib.UvmJsonModule.libContent[methodName];
//          isUserDefineFunc = true;
//          isUserDefinedInTableFunc = true;
//          needPopFirstArg = true;
//          hasThis = false;
//        }
//        else if (calledTypeName == proto.method.DeclaringType.FullName)
//        {
//          // 调用本类型的方法
//          isUserDefineFunc = true;
//          targetFuncName = methodName;
//          isUserDefinedInTableFunc = false;
//        }
//        else if (calledTypeName == typeof(UvmCoreLib.UvmCoreFuncs).FullName)
//        {
//          if (methodName == "and")
//          {
//            targetFuncName = "band";
//            useOpcode = true;
//            hasThis = false;
//            MakeArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, true)
//            break;
//          }
//          else if (methodName == "or")
//          {
//            targetFuncName = "bor";
//            useOpcode = true;
//            hasThis = false;
//            MakeArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, true)
//            break;
//          }
//          else if (methodName == "div")
//          {
//            targetFuncName = "div";
//            useOpcode = true;
//            hasThis = false;
//            MakeArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, false)
//            break;
//          }
//          else if (methodName == "idiv")
//          {
//            targetFuncName = "idiv";
//            useOpcode = true;
//            hasThis = false;
//            MakeArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, false)
//            break;
//          }
//          else if (methodName == "not")
//          {
//            targetFuncName = "not";
//            useOpcode = true;
//            hasThis = false;
//            MakeSingleArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, true)
//            break;
//          }
//          else if (methodName == "neg")
//          {
//            targetFuncName = "unm";
//            useOpcode = true;
//            hasThis = false;
//            MakeSingleArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, false)
//            break;
//          }
//          else if (methodName == "print")
//          {
//            targetFuncName = "print";
//          }
//          else if (methodName == "tostring")
//          {
//            targetFuncName = "tostring";
//          }
//          else if(methodName=="tojsonstring")
//          {
//            targetFuncName = "tojsonstring";
//          }
//          else if(methodName == "pprint")
//          {
//            targetFuncName = "pprint";
//          }
//          else if(methodName == "tointeger")
//          {
//            targetFuncName = "tointeger";
//          }
//          else if(methodName == "tonumber")
//          {
//            targetFuncName = "tonumber";
//          }
//          else if(methodName == "importModule")
//          {
//            targetFuncName = "require";
//          }
//          else if(methodName == "importContract")
//          {
//            targetFuncName = "import_contract";
//          }
//          else if(methodName == "Debug")
//          {
//            result.addRange(DebugEvalStack(proto))
//            return result;
//          }
//          else if(methodName == "set_mock_contract_balance_amount")
//          {
//            proto.addNotMappedILInstruction(i)
//            return result;
//          }
//          else if(UvmCoreLib.UvmCoreFuncs.GlobalFuncsMapping.ContainsKey(methodName))
//          {
//            targetFuncName = UvmCoreLib.UvmCoreFuncs.GlobalFuncsMapping[methodName];
//          }
//          else
//          {
//            targetFuncName = methodName;
//          }
//        }
//        else if(calledTypeName.StartsWith("UvmCoreLib.GluaArray")
//                || calledTypeName.StartsWith("UvmCoreLib.GluaMap"))
//        {
//          bool isArrayType = calledTypeName.StartsWith("UvmCoreLib.GluaArray")
//          if (methodName == "create")
//          {
//            useOpcode = true;
//            targetFuncName = "newtable";
//          }
//          else if (methodName == "add")
//          {
//            if (isArrayType)
//            {
//              useOpcode = true;
//              targetFuncName = "array.add";
//            }
//            else
//            {
//              useOpcode = true;
//              targetFuncName = "map.add";
//            }
//          }
//          else if (methodName == "Count")
//          {
//            useOpcode = true;
//            targetFuncName = "len";
//          }
//          else if (methodName == "Set")
//          {
//            if (isArrayType)
//            {
//              useOpcode = true;
//              targetFuncName = "array.set";
//            }
//            else
//            {
//              useOpcode = true;
//              targetFuncName = "map.set";
//            }
//          }
//          else if (methodName == "Get")
//          {
//            if (isArrayType)
//            {
//              useOpcode = true;
//              targetFuncName = "array.get";
//            }
//            else
//            {
//              useOpcode = true;
//              targetFuncName = "map.get";
//            }
//          }
//          else if (methodName == "Pop")
//          {
//            useOpcode = true;
//            targetFuncName = "array.pop";
//          }
//          else if (methodName == "Pairs" && !isArrayType)
//          {
//            useOpcode = true;
//            targetFuncName = "map.pairs";
//          }
//          else if (methodName == "Ipairs" && isArrayType)
//          {
//            useOpcode = true;
//            targetFuncName = "array.ipairs";
//          }
//          else if (calledTypeName.Contains("/MapIterator") && methodName == "Invoke")
//          {
//            useOpcode = true;
//            targetFuncName = "iterator_call";
//          }
//          else if (calledTypeName.Contains("/ArrayIterator") && methodName == "Invoke")
//          {
//            useOpcode = true;
//            targetFuncName = "iterator_call";
//          }
//          else
//          {
//            throw new Exception("Not supported func " + calledTypeName + "::" + methodName)
//          }
//        }
//        else if((calledType is TypeDefinition) && (TranslatorUtils.IsEventEmitterType(calledType as TypeDefinition)))
//        {
//          var eventName = TranslatorUtils.GetEventNameFromEmitterMethodName(calledMethod)
//          if(eventName!=null)
//          {
//            // 把eventName压栈,调用全局emit函数
//            targetFuncName = "emit";
//            paramsCount++;
//            // 弹出eventArg，压入eventName，然后压回eventArg
//            makeGetTopOfEvalStackInst(proto, i, result, proto.tmp2StackTopSlotIndex, commentPrefix)
//            subEvalStackSizeInstructions(proto, i, result, commentPrefix)
//            MakeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, eventName, commentPrefix)
//            addEvalStackSizeInstructions(proto, i, result, commentPrefix)
//            MakeSetTopOfEvalStackInst(proto, i, result, proto.tmp1StackTopSlotIndex, commentPrefix)
//            addEvalStackSizeInstructions(proto, i, result, commentPrefix)
//            MakeSetTopOfEvalStackInst(proto, i, result, proto.tmp2StackTopSlotIndex, commentPrefix)
//          }
//          else
//          {
//            throw new Exception("不支持调用方法" + calledType + "::" + methodName)
//          }
//        }
                var preaddParamsCount = 0 // 前置额外增加的参数，比如this
                if (hasThis) {
                    paramsCount++
                    preaddParamsCount = 1
                }

                // 如果methodName是setXXXX或者getXXXX，则是java的属性操作，转换成glua的table属性读写操作
                if (hasThis && methodName.startsWith("set") && methodName.length >= 4 && methodParams.size == 1 // TODO
                        && (targetFuncName == "" || targetFuncName == methodName)) {
                    // setXXXX，属性写操作
                    var needConvtToBool = methodParams[0].isBoolean()

                    var propName = TranslatorUtils.getFieldNameFromProperty(methodName)
                    makeSetTablePropInstructions(proto, propName, i, result, commentPrefix, needConvtToBool)
                    return result
                } else if (hasThis && methodName.startsWith("get") && methodName.length >= 4
                        && methodParams.size == 0 && returnCount == 1
                        && (targetFuncName == "" || targetFuncName == methodName)) {
                    // getXXXX, table属性读操作
                    var propName = TranslatorUtils.getFieldNameFromProperty(methodName)
                    var needConvtToBool = returnCount == 1 && (methodInfo.methodReturnType?.isBoolean() ?: false)
                    makeGetTablePropInstructions(proto, propName, i, result, commentPrefix, needConvtToBool)
                    return result
                } else if (!calledTypeName.equals(proto.method?.signature?.classDef?.name?.replace('/', '.')) && targetFuncName.length < 1) {
                    // 调用其他类的方法
                    isUserDefineFunc = true
                    targetFuncName = methodName
                    isUserDefinedInTableFunc = true
                    // TODO
                }
                // TODO: 更多内置库的函数支持
                if (targetFuncName.isEmpty()) {
                    throw GjavacException("暂时不支持使用方法" + methodInfo.fullName())
                }
                if (paramsCount > proto.tmpMaxStackTopSlotIndex - proto.tmp1StackTopSlotIndex - 1) {
                    throw GjavacException("暂时不支持超过" + (proto.tmpMaxStackTopSlotIndex - proto.tmp1StackTopSlotIndex - 1) + "个参数的C#方法调用")
                }

                // TODO: 把抽取若干个参数的方法剥离成单独函数

                // 消耗eval stack顶部若干个值用来调用相应的本类成员函数或者静态函数, 返回值存入eval stack
                // 不断取出eval-stack中数据(paramsCount)个，倒序翻入tmp stot，然后调用函数
                var argStartSlot = proto.tmp3StackTopSlotIndex;
                for (c in 0..(paramsCount - 1)) {
                    // 倒序遍历插入参数,不算this, c是第 index=paramsCount-c-1-preaddParamsCount个参数
                    // 栈顶取出的是最后一个参数
                    // tmp2 slot用来存放eval stack或者其他值，参数从tmp3开始存放
                    var slotIndex = paramsCount - c + argStartSlot - 1; // 存放参数的slot位置
                    if (slotIndex >= proto.tmpMaxStackTopSlotIndex) {
                        throw GjavacException("不支持将超过" + (proto.tmpMaxStackTopSlotIndex - proto.tmp2StackTopSlotIndex) + "个参数的C#函数编译到glua字节码")
                    }
                    var methodParamIndex = paramsCount - c - 1 - preaddParamsCount; // 此参数是参数在方法的.net参数(不包含this)中的索引
                    var needConvtToBool = false
                    if (methodParamIndex < methodParams.size && methodParamIndex >= 0) {
                        var paramType = methodParams[methodParamIndex]
                        if (paramType.fullName() == "System.Boolean") {
                            needConvtToBool = true;
                        }
                    }

                    popFromEvalStackToSlot(proto,slotIndex,i,result,commentPrefix)
                    // 对于布尔类型，因为.net中布尔类型参数加载的时候用的ldc.i，加载的是整数，所以这里要进行类型转换成bool类型，使用 not not a来转换
                    if (needConvtToBool) {
                        result.add(proto.makeInstructionLine("not %" + slotIndex + " %" + slotIndex + commentPrefix, i))
                        result.add(proto.makeInstructionLine("not %" + slotIndex + " %" + slotIndex + commentPrefix, i))
                    }

                }

                var resultSlotIndex = proto.tmp2StackTopSlotIndex;
                // tmp2StackTopSlotIndex 用来存放函数本身, tmp3是第一个参数
                if (useOpcode && !isUserDefineFunc) {
                    if (targetFuncName == "concat") {
                        result.add(proto.makeInstructionLine("concat %" + resultSlotIndex + " %" + proto.tmp3StackTopSlotIndex + " %" + (proto.tmp3StackTopSlotIndex + 1) + commentPrefix, i))
                    } else if (targetFuncName == "newtable") {
                        result.add(proto.makeInstructionLine("newtable %" + resultSlotIndex + " 0 0" + commentPrefix, i))
                    } else if (targetFuncName == "array.add") {
                        var tableSlot = argStartSlot;
                        var valueSlot = argStartSlot + 1;
                        result.add(proto.makeInstructionLine("len %" + proto.tmp1StackTopSlotIndex + " %" + tableSlot + commentPrefix, i))
                        result.add(proto.makeInstructionLine("add %" + proto.tmp1StackTopSlotIndex + " %" + proto.tmp1StackTopSlotIndex + " const 1" + commentPrefix, i))
                        result.add(proto.makeInstructionLine("settable %" + tableSlot + " %" + proto.tmp1StackTopSlotIndex + " %" + valueSlot + commentPrefix + "array.add", i))
                    } else if (targetFuncName == "array.set") {
                        var tableSlot = argStartSlot;
                        var keySlot = argStartSlot + 1;
                        var valueSlot = argStartSlot + 2;
                        result.add(proto.makeInstructionLine("settable %" + tableSlot + " %" + keySlot + " %" + valueSlot + commentPrefix + " array.set", i))
                    } else if (targetFuncName == "map.set") {
                        var tableSlot = argStartSlot;
                        var keySlot = argStartSlot + 1;
                        var valueSlot = argStartSlot + 2;
                        result.add(proto.makeInstructionLine("settable %" + tableSlot + " %" + keySlot + " %" + valueSlot + commentPrefix + " map.set", i))
                    } else if (targetFuncName == "array.get") {
                        var tableSlot = argStartSlot;
                        var keySlot = argStartSlot + 1;
                        result.add(proto.makeInstructionLine("gettable %" + resultSlotIndex + " %" + tableSlot + " %" + keySlot + commentPrefix + " array.set", i))
                    } else if (targetFuncName == "map.get") {
                        var tableSlot = argStartSlot;
                        var keySlot = argStartSlot + 1;
                        result.add(proto.makeInstructionLine("gettable %" + resultSlotIndex + " %" + tableSlot + " %" + keySlot + commentPrefix + " map.set", i))
                    } else if (targetFuncName == "len") {
                        var tableSlot = argStartSlot;
                        result.add(proto.makeInstructionLine("len %" + resultSlotIndex + " %" + tableSlot + commentPrefix + "array.len", i))
                    } else if (targetFuncName == "array.pop") {
                        var tableSlot = argStartSlot;
                        result.add(proto.makeInstructionLine("len %" + proto.tmp1StackTopSlotIndex + " %" + tableSlot + commentPrefix, i))
                        loadNilInstruction(proto, resultSlotIndex, i, result, commentPrefix)
                        result.add(proto.makeInstructionLine("settable %" + tableSlot + " %" + proto.tmp1StackTopSlotIndex + " %" + resultSlotIndex + commentPrefix + "array.pop", i))
                    } else if (targetFuncName == "map.pairs") {
                        // 调用pairs函数，返回1个结果，迭代器函数对象
                        var tableSlot = argStartSlot;
                        var envIndex = proto.internUpvalue("ENV")
                        proto.internConstantValue("pairs")
                        result.add(proto.makeInstructionLine("gettabup %" + proto.tmp1StackTopSlotIndex + " @" + envIndex + " const \"pairs\"" + commentPrefix, i))
                        result.add(proto.makeInstructionLine("move %" + proto.tmp2StackTopSlotIndex + " %" + tableSlot + commentPrefix, i))
                        result.add(proto.makeInstructionLine("call %" + proto.tmp1StackTopSlotIndex + " 2 2" + commentPrefix, i))
                        // pairs函数返回1个函数，返回在刚刚函数调用时函数所处slot tmp1
                        result.add(proto.makeInstructionLine("move %" + resultSlotIndex + " %" + proto.tmp1StackTopSlotIndex + commentPrefix + " map.pairs", i))
                    } else if (targetFuncName == "array.ipairs") {
                        // 调用ipairs函数，返回1个结果，迭代器函数对象
                        var tableSlot = argStartSlot;
                        var envIndex = proto.internUpvalue("ENV")
                        proto.internConstantValue("ipairs")
                        result.add(proto.makeInstructionLine("gettabup %" + proto.tmp1StackTopSlotIndex + " @" + envIndex + " const \"ipairs\"" + commentPrefix, i))
                        result.add(proto.makeInstructionLine("move %" + proto.tmp2StackTopSlotIndex + " %" + tableSlot + commentPrefix, i))
                        result.add(proto.makeInstructionLine("call %" + proto.tmp1StackTopSlotIndex + " 2 2" + commentPrefix, i))
                        // ipairs函数返回1个函数，返回在刚刚函数调用时函数所处slot tmp1
                        result.add(proto.makeInstructionLine("move %" + resultSlotIndex + " %" + proto.tmp1StackTopSlotIndex + commentPrefix + " array.ipairs", i))
                    } else if (targetFuncName == "iterator_call") {
                        // 调用迭代器函数，接受两个参数(map和上一个key),返回2个结果写入一个新的table中, {"first": 第一个返回值, "second": 第二个返回值}
                        var iteratorSlot = argStartSlot;
                        var tableSlot = argStartSlot + 1;
                        var keySlot = argStartSlot + 2;
                        result.add(proto.makeInstructionLine("call %" + iteratorSlot + " 3 3" + commentPrefix, i))
                        // 迭代器函数返回2个结果，返回在刚刚函数调用时函数所处slot，用来构造{"first": 返回值1, "second": 返回值2}
                        var resultKeySlot = iteratorSlot;
                        var resultValueSlot = iteratorSlot + 1;
                        result.add(proto.makeInstructionLine(
                                "newtable %" + proto.tmp1StackTopSlotIndex + " 0 0" + commentPrefix, i))
                        makeLoadConstInst(proto, i, result, proto.tmp2StackTopSlotIndex, "first", commentPrefix)
                        result.add(proto.makeInstructionLine(
                                "settable %" + proto.tmp1StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + " %" + resultKeySlot + commentPrefix, i))
                        makeLoadConstInst(proto, i, result, proto.tmp2StackTopSlotIndex, "second", commentPrefix)
                        result.add(proto.makeInstructionLine(
                                "settable %" + proto.tmp1StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + " %" + resultValueSlot + commentPrefix, i))
                        // 把产生的table作为结果
                        result.add(proto.makeInstructionLine(
                                "move %" + resultSlotIndex + " %" + proto.tmp1StackTopSlotIndex + commentPrefix + " map.iterator_call", i))
                    } else {
                        throw GjavacException("not supported opcode " + targetFuncName)
                    }
                } else if (isUserDefineFunc) {
                   // if (!isUserDefinedInTableFunc) {
                        // 访问本类的其他成员方法，访问的是父proto的局部变量，所以是访问upvalue
                     //   var protoName = TranslatorUtils.makeProtoName(calledMethod)
                     //   var funcUpvalIndex = proto.internUpvalue(protoName)
                     //   proto.internConstantValue(protoName)
                     //   result.add(proto.makeInstructionLine(
                      //          "getupval %" + proto.tmp2StackTopSlotIndex + " @" + funcUpvalIndex + commentPrefix, i))
                   // } else {
                        // 访问其他类的成员方法，需要gettable取出函数
                        var protoName = TranslatorUtils.makeProtoName(calledMethod)
                        var funcUpvalIndex = proto.internUpvalue(protoName)
                        proto.internConstantValue(protoName)
                        if (targetFuncName == null || targetFuncName.length < 1) {
                            targetFuncName = calledMethod.name;
                        }
                        makeLoadConstInst(proto, i, result, proto.tmp2StackTopSlotIndex, targetFuncName, commentPrefix)
                        if (needPopFirstArg) {
                            // object模拟glua module，module信息在calledMethod的this参数中
                            // 这时候eval stack应该是[this], argStart开始的数据应该是this, ...
                            // result.addRange(DebugEvalStack(proto))
                            popFromEvalStackToSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)

                            result.add(proto.makeInstructionLine(
                                    "gettable %" + proto.tmp2StackTopSlotIndex + " %" + proto.tmp1StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
                        } else {
                            result.add(proto.makeInstructionLine(
                                    "gettable %" + proto.tmp2StackTopSlotIndex + " %" + argStartSlot + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
                        }
                    //}
                } else if (targetModuleName.length < 1) {
                    // 全局函数或局部函数
                    // TODO: 这里要从上下文查找是否是局部变量，然后分支处理，暂时都当全局函数处理
                    var envUp = proto.internUpvalue("ENV")
                    proto.internConstantValue(targetFuncName)
                    result.add(proto.makeInstructionLine("gettabup %" + proto.tmp2StackTopSlotIndex + " @" + envUp + " const \"" + targetFuncName + "\"" + commentPrefix, i))
                } else {
                    throw GjavacException("not supported yet")
                }
                if (!useOpcode) {
                    // 调用tmp2位置的函数，函数调用返回结果会存回tmp2开始的slots
                    result.add(proto.makeInstructionLine(
                            "call %" + proto.tmp2StackTopSlotIndex + " " + (paramsCount + 1) + " " + (returnCount + 1) +
                                    commentPrefix, i))
                    result.add(proto.makeInstructionLine("move %" + proto.tmp3StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
                } else if (hasReturn) {
                    result.add(proto.makeInstructionLine("move %" + proto.tmp3StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
                }
                // 把调用结果存回eval-stack
                if (hasReturn) {
                    // 调用结果在tmp3
                    pushIntoEvalStackTopSlot(proto,proto.tmp3StackTopSlotIndex,i,result,commentPrefix )
                }
                if (needNeedPopThis) {
                    //makeLoadNilInst(proto, i, result, proto.tmp2StackTopSlotIndex, commentPrefix)
                    popFromEvalStackToSlot(proto, proto.tmp1StackTopSlotIndex, i, result, commentPrefix)
                }

            }
            Opcodes.GOTO -> {
                val gotoLabel = i.opArgs[0] as Label
                val gotoInstIndex = proto.method?.offsetOfLabel(gotoLabel)
                if (gotoInstIndex == null) throw GjavacException("Can't find position of label " + gotoLabel)
                val toJmpToInst = proto.method?.code?.get(gotoInstIndex)
                if (toJmpToInst == null) {
                    throw GjavacException("goto dest line not found " + i)
                }
                makeJmpToInstruction(proto, i, "goto", toJmpToInst, result, commentPrefix, onlyNeedResultCount)
            }
            Opcodes.TABLESWITCH -> {
                // TODO
                throw GjavacException("not supported yet")
            }
            Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLT, Opcodes.IFLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
            Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPLE -> {
                val opType = when (i.opCode) {
                    Opcodes.IFNULL -> "null"
                    Opcodes.IFNONNULL -> "notnull"
                    Opcodes.IFEQ -> "eq"
                    Opcodes.IFNE -> "ne"
                    Opcodes.IF_ACMPEQ, Opcodes.IF_ICMPEQ -> "cmp_eq"
                    Opcodes.IF_ACMPNE, Opcodes.IF_ICMPNE -> "cmp_ne"
                    Opcodes.IFLT -> "lt"
                    Opcodes.IFLE -> "le"
                    Opcodes.IFGT -> "gt"
                    Opcodes.IFGE -> "ge"
                    Opcodes.IF_ICMPLT -> "cmp_lt"
                    Opcodes.IF_ICMPLE -> "cmp_le"
                    Opcodes.IF_ICMPGT -> "cmp_gt"
                    Opcodes.IF_ICMPGE -> "cmp_ge"
                    else -> {
                        throw GjavacException("unknown condition opcode " + i.opCodeName())
                    }
                }

                val gotoLabel = i.opArgs[0] as Label
                val gotoInstIndex = proto.method?.offsetOfLabel(gotoLabel)
                if (gotoInstIndex == null) throw GjavacException("Can't find position of label " + gotoLabel)
                val toJmpToInst = proto.method?.code?.get(gotoInstIndex)
                if (toJmpToInst == null) {
                    throw GjavacException("goto dest line not found " + i)
                }

                popFromEvalStackToSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)

                var arg1SlotIndex = proto.tmp1StackTopSlotIndex
                var arg2SlotIndex = proto.tmp2StackTopSlotIndex

                when (opType) {
                    "null", "notnull" -> makeLoadNilInst(proto, i, result, proto.tmp2StackTopSlotIndex, commentPrefix)
                    "cmp_lt", "cmp_le", "cmp_gt", "cmp_ge", "cmp_eq", "cmp_ne" -> {
                        // pop arg1
                        popFromEvalStackToSlot(proto,proto.tmp2StackTopSlotIndex,i,result,commentPrefix)
                        // swap arg1 and arg2
                        val tmpSlot = arg1SlotIndex
                        arg1SlotIndex = arg2SlotIndex
                        arg2SlotIndex = tmpSlot
                    }
                    else -> {
                        makeLoadConstInst(proto, i, result, proto.tmp2StackTopSlotIndex, 0, commentPrefix)
                    }
                }


                // compare arg1 and arg2
                when (opType) {
                    "null", "eq", "cmp_eq" -> {
                        // eq: if ((RK(B) == RK(C)) != A) then pc++
                        result.add(proto.makeInstructionLine("eq " + 1 + " %" + arg1SlotIndex + " %" + arg2SlotIndex +
                                commentPrefix, i))
                    }
                    "notnull", "ne", "cmp_ne" -> {
                        result.add(proto.makeInstructionLine("eq " + 0 + " %" + arg1SlotIndex + " %" + arg2SlotIndex +
                                commentPrefix, i))
                    }
                    "lt", "cmp_lt" -> {
                        // lt: if ((RK(B) <  RK(C)) ~= A) then pc++
                        result.add(proto.makeInstructionLine("lt " + 1 + " %" + arg1SlotIndex + " %" + arg2SlotIndex +
                                commentPrefix, i))
                    }
                    "le", "cmp_le" -> {
                        result.add(proto.makeInstructionLine("le " + 1 + " %" + arg1SlotIndex + " %" + arg2SlotIndex +
                                commentPrefix, i))
                    }
                    "gt", "cmp_gt" -> {
                        result.add(proto.makeInstructionLine("le " + 0 + " %" + arg1SlotIndex + " %" + arg2SlotIndex +
                                commentPrefix, i))
                    }
                    "ge", "cmp_ge" -> {
                        result.add(proto.makeInstructionLine("lt " + 0 + " %" + arg1SlotIndex + " %" + arg2SlotIndex +
                                commentPrefix, i))
                    }
                    else -> throw GjavacException("not supported compare type " + opType)
                }
                // 满足相反的条件，跳转到目标指令
                makeJmpToInstruction(proto, i, i.opCodeName(), toJmpToInst, result, commentPrefix, onlyNeedResultCount)
            }
            Opcodes.INSTANCEOF -> {
                makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, true, commentPrefix)
                //makeSetTopOfEvalStackInst(proto, i, result, proto.tmp1StackTopSlotIndex, commentPrefix)
                popFromEvalStackToSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)
                pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix )
            }
        // TODO: other opcodes
            else -> {
//                println("not supported jvm opcde ${i.opCodeName()}")
                throw GjavacException("not supported jvm opcode " + i.opCodeName() + " to compile to glua instruction")
            }
        }
        /*
        switch (i.OpCode.Code)
        {
          case Code.Nop:
          proto.addNotMappedILInstruction(i)
          return result;
          case Code.Stloc:
          case Code.Stloc_S:
          case Code.Stloc_0:
          case Code.Stloc_1:
          case Code.Stloc_2:
          case Code.Stloc_3:
          {
            // 从evaluation stack 弹出栈顶数据复制到call stack slot
            // ->glua. 取出eval stack的长度, 从eval stack的slot中弹出数据到栈顶，然后move到合适slot，然后弹出栈顶
            int loc;
            VariableDefinition varInfo = null;
            if (i.OpCode.Code == Code.Stloc_S)
            {
              varInfo = i.Operand as VariableDefinition;
              loc = varInfo.Index;
            }
            else
            {
              loc = i.OpCode.Value - 10;
            }
            if (loc > proto.maxCallStackSize)
            {
              proto.maxCallStackSize = loc;
            }
            // 获取eval stack的栈顶值
            makeGetTopOfEvalStackInst(proto, i, result, proto.callStackStartIndex + loc, commentPrefix)
            // 移除eval stack的栈顶（设为nil)
            MakeLoadNilInst(proto, i, result, proto.tmp3StackTopSlotIndex, commentPrefix)
            MakeSetTopOfEvalStackInst(proto, i, result, proto.tmp3StackTopSlotIndex, commentPrefix + " stloc " + loc + " " + ILVariableNameFromDefinition(varInfo))
            subEvalStackSizeInstructions(proto, i, result, commentPrefix)
          }
          break;
          case Code.Starg:
          case Code.Starg_S:
          {
            // 将位于计算堆栈顶部的值存储到位于指定索引的参数槽中
            int argLoc;
            ParameterDefinition argInfo = i.Operand as ParameterDefinition;
            argLoc = argInfo.Index;
            var argSlot = argLoc;
            if (!proto.method.isStatic)
            {
              argSlot++;
            }
            // 获取eval stack的栈顶值
            makeGetTopOfEvalStackInst(proto, i, result, argSlot, commentPrefix)
            // 移除eval stack的栈顶（设为nil)
            MakeLoadNilInst(proto, i, result, proto.tmp3StackTopSlotIndex, commentPrefix)
            MakeSetTopOfEvalStackInst(proto, i, result, proto.tmp3StackTopSlotIndex,
              commentPrefix + " starg " + argLoc + " " + jvmParamterNameFromDefinition(argInfo))
            subEvalStackSizeInstructions(proto, i, result, commentPrefix)
          }
          break;
          case Code.Stfld:
          {
            // 用新值替换在对象引用或指针的字段中存储的值
            // top-1是table, top是value
            var fieldDefinition = i.Operand as FieldDefinition;
            var fieldName = fieldDefinition.name;
            bool needConvToBool = fieldDefinition.FieldType.FullName == typeof(bool).FullName;
            makeSetTablePropInstructions(proto, fieldName, i, result, commentPrefix, needConvToBool)
          }
          break;
          case Code.Ldfld:
          case Code.Ldflda:
          {
            // 查找对象中其引用当前位于计算堆栈的字段的值
            var fieldDefinition = i.Operand as FieldReference;
            var fieldName = fieldDefinition.name;
            bool needConvToBool = fieldDefinition.FieldType.FullName == typeof(bool).FullName;

            MakeGetTablePropInstructions(proto, fieldName, i, result, commentPrefix, needConvToBool)
          }
          break;
          case Code.Ldarg:
          case Code.Ldarg_S:
          case Code.Ldarg_0:
          case Code.Ldarg_1:
          case Code.Ldarg_2:
          case Code.Ldarg_3:
          case Code.Ldarga:
          case Code.Ldarga_S:
          {
            // 将参数（由指定索引值引用）加载到eval stack
            int argLoc;
            ParameterDefinition argInfo = null;
            var opCode = i.OpCode.Code;
            if (opCode == Code.Ldarg || opCode == Code.Ldarg_S
              || opCode == Code.Ldarga || opCode == Code.Ldarga_S)
            {
              argInfo = i.Operand as ParameterDefinition;
              argLoc = argInfo.Index;
            }
            else
            {
              argLoc = i.OpCode.Value - 3;
            }
            if (proto.method.HasThis)
            {
              argLoc++;
            }
            result.add(proto.makeEmptyInstruction("")) ;

            addEvalStackSizeInstructions(proto, i, result, commentPrefix)
            var slotIndex = argLoc;
            // 复制数据到eval stack
            MakeSetTopOfEvalStackInst(proto, i, result, slotIndex, commentPrefix + " ldarg " + argLoc + " " + ILParamterNameFromDefinition(argInfo))
          }
          break;
          case Code.Ldloc:
          case Code.Ldloc_S:
          case Code.Ldloc_0:
          case Code.Ldloc_1:
          case Code.Ldloc_2:
          case Code.Ldloc_3:
          case Code.Ldloca:
          case Code.Ldloca_S:
          {
            int loc;
            VariableDefinition varInfo = null;
            var opCode = i.OpCode.Code;
            // FIXME: ldloca, ldloca_S的意义是加载局部变量的地址，和其他几个不一样
            if (opCode == Code.Ldloc_S || opCode == Code.Ldloc
              || opCode == Code.Ldloca || opCode == Code.Ldloca_S)
            {
              varInfo = i.Operand as VariableDefinition;
              loc = varInfo.Index;
            }
            else
            {
              loc = i.OpCode.Value - 6;
              if (loc > proto.maxCallStackSize)
              {
                proto.maxCallStackSize = loc;
              }
            }
            // 从当前函数栈的call stack(slots区域)把某个数据复制到eval stack
            addEvalStackSizeInstructions(proto, i, result, commentPrefix) var slotIndex = proto.callStackStartIndex + loc;
            // 复制数据到eval stack
            MakeSetTopOfEvalStackInst(proto, i, result, slotIndex, commentPrefix + " ldloc " + loc + " " + ILVariableNameFromDefinition(varInfo))
          }
          break;
          case Code.Ldc_I4:
          case Code.Ldc_I4_0:
          case Code.Ldc_I4_1:
          case Code.Ldc_I4_2:
          case Code.Ldc_I4_3:
          case Code.Ldc_I4_4:
          case Code.Ldc_I4_5:
          case Code.Ldc_I4_6:
          case Code.Ldc_I4_7:
          case Code.Ldc_I4_8:
          case Code.Ldc_I4_S:
          case Code.Ldc_I4_M1:

          case Code.Ldc_R4:
          case Code.Ldc_R8:

          case Code.Ldc_I8:
          {
            // 加载int/float常量到eval stack
            var opCode = i.OpCode.Code;
            var value = (opCode == Code.Ldc_I4_S || opCode == Code.Ldc_I4
              || opCode == Code.Ldc_I4_M1 || opCode == Code.Ldc_R4
              || opCode == Code.Ldc_R8 || opCode == Code.Ldc_I8) ? i.Operand : (i.OpCode.Value - 22)
            MakeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, value, commentPrefix)
            addEvalStackSizeInstructions(proto, i, result, commentPrefix)
            // 再增加一个把栈顶值放入eval-stack的指令
            MakeSetTopOfEvalStackInst(proto, i, result, proto.tmp1StackTopSlotIndex,
              commentPrefix + " ldc " + value)
          }
          break;
          case Code.Ldnull:
          {
            // 加载null到eval stack

            // 加载nil
            MakeLoadNilInst(proto, i, result, proto.tmp1StackTopSlotIndex, commentPrefix)
            addEvalStackSizeInstructions(proto, i, result, commentPrefix)
            // 设置nil
            MakeSetTopOfEvalStackInst(proto, i, result, proto.tmp1StackTopSlotIndex, commentPrefix + " ldnull")
          }
          break;
          case Code.Ldstr:
          {
            // 加载字符串常量到eval stack
            var constValue = i.Operand.ToString()
            var literalValueInGluas = TranslatorUtils.Escape(constValue)
            var valueIdx = proto.internConstantValue(literalValueInGluas)
            addEvalStackSizeInstructions(proto, i, result, commentPrefix)
            // 设置string
            result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_SETTABLE,
              "settable %" + proto.evalStackIndex + " %" + proto.evalStackSizeIndex + " const \"" + literalValueInGluas + "\"" + commentPrefix + " ldstr", i))
          }
          break;
          case Code.add:
          case Code.add_Ovf:
          case Code.add_Ovf_Un:
          case Code.Sub:
          case Code.Sub_Ovf:
          case Code.Sub_Ovf_Un:
          case Code.Mul:
          case Code.Mul_Ovf:
          case Code.Mul_Ovf_Un:
          case Code.Div:
          case Code.Div_Un:
          case Code.Rem:
          case Code.Rem_Un:
          case Code.And:
          case Code.Or:
          case Code.Xor:
          case Code.Shl:
          case Code.Shr:
          case Code.Shr_Un:
          {
            // 消耗eval stack的顶部2个值(top-1和top), 计算结果存入eval stack
            // 获取eval stack顶部的值

            string gluaOpName;
            var dotNetOpCode = i.OpCode.Code;
            if (dotNetOpCode == Code.add
              || dotNetOpCode == Code.add_Ovf
              || dotNetOpCode == Code.add_Ovf
              || dotNetOpCode == Code.add_Ovf_Un)
            {
              gluaOpName = "add";
            }
            else if (dotNetOpCode == Code.Sub
              || dotNetOpCode == Code.Sub_Ovf
              || dotNetOpCode == Code.Sub_Ovf_Un)
            {
              gluaOpName = "sub";
            }
            else if (dotNetOpCode == Code.Mul
              || dotNetOpCode == Code.Mul_Ovf
              || dotNetOpCode == Code.Mul_Ovf_Un)
            {
              gluaOpName = "mul";
            }
            else if (dotNetOpCode == Code.Div
              || dotNetOpCode == Code.Div_Un)
            {
              gluaOpName = "idiv";
            }
            else if (dotNetOpCode == Code.Rem
              || dotNetOpCode == Code.Rem_Un)
            {
              gluaOpName = "mod";
            }
            else if (dotNetOpCode == Code.And)
            {
              gluaOpName = "band";
            }
            else if (dotNetOpCode == Code.Or)
            {
              gluaOpName = "bor";
            }
            else if (dotNetOpCode == Code.Xor)
            {
              gluaOpName = "bxor";
            }
            else if (dotNetOpCode == Code.Shl)
            {
              gluaOpName = "shl";
            }
            else if (dotNetOpCode == Code.Shr
              || dotNetOpCode == Code.Shr_Un)
            {
              gluaOpName = "shr";
            }
            else
            {
              throw new Exception("not supported op code " + dotNetOpCode)
            }
            MakeArithmeticInstructions(proto, gluaOpName, i, result, commentPrefix, false)
          }
          break;
          case Code.Neg:
          case Code.Not:
          {
            var dotNetOpCode = i.OpCode.Code;
            string gluaOpName;
            var needConvertToBool = false;
            if (dotNetOpCode == Code.Neg)
            {
              gluaOpName = "unm";
            }
            else if (dotNetOpCode == Code.Not)
            {
              gluaOpName = "not";
              needConvertToBool = true;
            }
            else
            {
              throw new Exception("not supported op code " + dotNetOpCode)
            }
            MakeSingleArithmeticInstructions(proto, gluaOpName, i, result, commentPrefix, needConvertToBool)
          }
          break;
          case Code.Box:
          case Code.Unbox:
          case Code.Unbox_Any:
          {
            // Box: 把eval stack栈顶的基本类型数值比如int类型值弹出，装箱成对象类型，重新把引用压栈到eval stack顶部
            // Unbox: 拆箱
            // 转成glua字节码指令实际什么都不做
            proto.addNotMappedILInstruction(i)
          }
          break;
          case Code.Call:
          case Code.Callvirt:
          {
            result.add(proto.makeEmptyInstruction(i.ToString()))
            var operand = (MethodReference)i.Operand;
            var calledMethod = operand;
            var methodName = calledMethod.name;
            var calledType = operand.DeclaringType;
            var calledTypeName = calledType.FullName;
            var methodParams = operand.Parameters;
            var paramsCount = methodParams.Count;
            var hasThis = operand.HasThis;
            var hasReturn = operand.ReturnType.FullName != "System.Void";
            var needPopFirstArg = false; // 一些函数，比如import module的函数，因为用object成员函数模拟，而在glua中是table中属性的函数，所以.net中多传了个this对象
            var returnCount = hasReturn ? 1 : 0;
            var isUserDefineFunc = false; // 如果是本类中要生成glua字节码的方法，这里标记为true
            var isUserDefinedInTableFunc = false; // 是否是模拟table的类型中的成员方法，这样的函数需要gettable取出函数再调用
            var targetModuleName = ""; // 转成lua后对应的模块名称，比如可能转成print，也可能转成table.concat等
            var targetFuncName = ""; // 全局方法名或模块中的方法名，或者本模块中的名称
            var useOpcode = false;
            if (calledTypeName == "System.Console")
            {
              if (methodName == "WriteLine")
              {
                targetFuncName = "print";
              }
            }
            else if (methodName == "ToString")
            {
              targetFuncName = "tostring";
              returnCount = 1;
            }
            else if (calledTypeName == typeof(System.String).FullName)
            {
              if (methodName == "Concat")
              {
                // 连接字符串可以直接使用op_concat指令
                targetFuncName = "concat";
                useOpcode = true;
                hasThis = false;
                if(paramsCount==1)
                {
                  targetFuncName = "tostring"; // 只有一个参数的情况下，当成tostring处理
                  useOpcode = false;
                }
              }
              else if (methodName == "op_Equality")
              {
                MakeCompareInstructions(proto, "eq", i, result, commentPrefix)
                return result;
              }
              else if(methodName == "op_Inequality")
              {
                MakeCompareInstructions(proto, "ne", i, result, commentPrefix)
                return result;
              }
              else if(methodName == "get_Length")
              {
                targetFuncName = "len";
                useOpcode = true;
                hasThis = true;
              }
              else
              {
                throw new Exception("not supported method " + calledTypeName + "::" + methodName)
              }
              // TODO: 其他字符串特殊函数
            }
            else if(calledTypeName == typeof(UvmCoreLib.GluaStringModule).FullName)
            {
              // 调用string模块的方法
              targetFuncName = UvmCoreLib.GluaStringModule.libContent[methodName];
              isUserDefineFunc = true;
              isUserDefinedInTableFunc = true;
              needPopFirstArg = true;
              hasThis = false;
            }
            else if (calledTypeName == typeof(UvmCoreLib.GluaTableModule).FullName)
            {
              // 调用table模块的方法
              targetFuncName = UvmCoreLib.GluaTableModule.libContent[methodName];
              isUserDefineFunc = true;
              isUserDefinedInTableFunc = true;
              needPopFirstArg = true;
              hasThis = false;
            }
            else if (calledTypeName == typeof(UvmCoreLib.GluaMathModule).FullName)
            {
              // 调用math模块的方法
              targetFuncName = UvmCoreLib.GluaMathModule.libContent[methodName];
              isUserDefineFunc = true;
              isUserDefinedInTableFunc = true;
              needPopFirstArg = true;
              hasThis = false;
            }
            else if (calledTypeName == typeof(UvmCoreLib.UvmTimeModule).FullName)
            {
              // 调用time模块的方法
              targetFuncName = UvmCoreLib.UvmTimeModule.libContent[methodName];
              isUserDefineFunc = true;
              isUserDefinedInTableFunc = true;
              needPopFirstArg = true;
              hasThis = false;
            }
            else if (calledTypeName == typeof(UvmCoreLib.UvmJsonModule).FullName)
            {
              // 调用json模块的方法
              targetFuncName = UvmCoreLib.UvmJsonModule.libContent[methodName];
              isUserDefineFunc = true;
              isUserDefinedInTableFunc = true;
              needPopFirstArg = true;
              hasThis = false;
            }

            else if (calledTypeName == typeof(UvmCoreLib.UvmCoreFuncs).FullName)
            {
              if (methodName == "and")
              {
                targetFuncName = "band";
                useOpcode = true;
                hasThis = false;
                MakeArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, true)
                break;
              }
              else if (methodName == "or")
              {
                targetFuncName = "bor";
                useOpcode = true;
                hasThis = false;
                MakeArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, true)
                break;
              }
              else if (methodName == "div")
              {
                targetFuncName = "div";
                useOpcode = true;
                hasThis = false;
                MakeArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, false)
                break;
              }
              else if (methodName == "idiv")
              {
                targetFuncName = "idiv";
                useOpcode = true;
                hasThis = false;
                MakeArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, false)
                break;
              }
              else if (methodName == "not")
              {
                targetFuncName = "not";
                useOpcode = true;
                hasThis = false;
                MakeSingleArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, true)
                break;
              }
              else if (methodName == "neg")
              {
                targetFuncName = "unm";
                useOpcode = true;
                hasThis = false;
                MakeSingleArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, false)
                break;
              }
              else if (methodName == "print")
              {
                targetFuncName = "print";
              }
              else if (methodName == "tostring")
              {
                targetFuncName = "tostring";
              }
              else if(methodName=="tojsonstring")
              {
                targetFuncName = "tojsonstring";
              }
              else if(methodName == "pprint")
              {
                targetFuncName = "pprint";
              }
              else if(methodName == "tointeger")
              {
                targetFuncName = "tointeger";
              }
              else if(methodName == "tonumber")
              {
                targetFuncName = "tonumber";
              }
              else if(methodName == "importModule")
              {
                targetFuncName = "require";
              }
              else if(methodName == "importContract")
              {
                targetFuncName = "import_contract";
              }
              else if(methodName == "Debug")
              {
                result.addRange(DebugEvalStack(proto))
                return result;
              }
              else if(methodName == "set_mock_contract_balance_amount")
              {
                proto.addNotMappedILInstruction(i)
                return result;
              }
              else if(UvmCoreLib.UvmCoreFuncs.GlobalFuncsMapping.ContainsKey(methodName))
              {
                targetFuncName = UvmCoreLib.UvmCoreFuncs.GlobalFuncsMapping[methodName];
              }
              else
              {
                targetFuncName = methodName;
              }
            }
            else if(calledTypeName.StartsWith("UvmCoreLib.GluaArray")
              || calledTypeName.StartsWith("UvmCoreLib.GluaMap"))
            {
              bool isArrayType = calledTypeName.StartsWith("UvmCoreLib.GluaArray")
              if (methodName == "create")
              {
                useOpcode = true;
                targetFuncName = "newtable";
              }
              else if (methodName == "add")
              {
                if (isArrayType)
                {
                  useOpcode = true;
                  targetFuncName = "array.add";
                }
                else
                {
                  useOpcode = true;
                  targetFuncName = "map.add";
                }
              }
              else if (methodName == "Count")
              {
                useOpcode = true;
                targetFuncName = "len";
              }
              else if (methodName == "Set")
              {
                if (isArrayType)
                {
                  useOpcode = true;
                  targetFuncName = "array.set";
                }
                else
                {
                  useOpcode = true;
                  targetFuncName = "map.set";
                }
              }
              else if (methodName == "Get")
              {
                if (isArrayType)
                {
                  useOpcode = true;
                  targetFuncName = "array.get";
                }
                else
                {
                  useOpcode = true;
                  targetFuncName = "map.get";
                }
              }
              else if (methodName == "Pop")
              {
                useOpcode = true;
                targetFuncName = "array.pop";
              }
              else if (methodName == "Pairs" && !isArrayType)
              {
                useOpcode = true;
                targetFuncName = "map.pairs";
              }
              else if (methodName == "Ipairs" && isArrayType)
              {
                useOpcode = true;
                targetFuncName = "array.ipairs";
              }
              else if (calledTypeName.Contains("/MapIterator") && methodName == "Invoke")
              {
                useOpcode = true;
                targetFuncName = "iterator_call";
              }
              else if (calledTypeName.Contains("/ArrayIterator") && methodName == "Invoke")
              {
                useOpcode = true;
                targetFuncName = "iterator_call";
              }
              else
              {
                throw new Exception("Not supported func " + calledTypeName + "::" + methodName)
              }
            }
            else if((calledType is TypeDefinition) && (TranslatorUtils.IsEventEmitterType(calledType as TypeDefinition)))
            {
              var eventName = TranslatorUtils.GetEventNameFromEmitterMethodName(calledMethod)
              if(eventName!=null)
              {
                // 把eventName压栈,调用全局emit函数
                targetFuncName = "emit";
                paramsCount++;
                // 弹出eventArg，压入eventName，然后压回eventArg
                makeGetTopOfEvalStackInst(proto, i, result, proto.tmp2StackTopSlotIndex, commentPrefix)
                subEvalStackSizeInstructions(proto, i, result, commentPrefix)
                MakeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, eventName, commentPrefix)
                addEvalStackSizeInstructions(proto, i, result, commentPrefix)
                MakeSetTopOfEvalStackInst(proto, i, result, proto.tmp1StackTopSlotIndex, commentPrefix)
                addEvalStackSizeInstructions(proto, i, result, commentPrefix)
                MakeSetTopOfEvalStackInst(proto, i, result, proto.tmp2StackTopSlotIndex, commentPrefix)
              }
              else
              {
                throw new Exception("不支持调用方法" + calledType + "::" + methodName)
              }
            }
            var preaddParamsCount = 0; // 前置额外增加的参数，比如this
            if (hasThis)
            {
              paramsCount++;
              preaddParamsCount = 1;
            }

            // 如果methodName是set_XXXX或者get_XXXX，则是C#的属性操作，转换成glua的table属性读写操作
            if (hasThis && methodName.StartsWith("set_") && methodName.Length > 4 && methodParams.Count == 1
              && (targetFuncName == null || targetFuncName == ""))
            {
              // set_XXXX，属性写操作
              var needConvtToBool = methodParams[0].ParameterType.FullName == "System.Boolean";

              var propName = methodName.Substring(4)
              makeSetTablePropInstructions(proto, propName, i, result, commentPrefix, needConvtToBool)
              break;
            }
            else if (hasThis && methodName.StartsWith("get_") && methodName.Length > 4
              && methodParams.Count == 0 && returnCount == 1
              && (targetFuncName == null || targetFuncName == ""))
            {
              // get_XXXX, table属性读操作
              var propName = methodName.Substring(4)
              var needConvtToBool = returnCount == 1 && operand.ReturnType.FullName == "System.Boolean";
              MakeGetTablePropInstructions(proto, propName, i, result, commentPrefix, needConvtToBool)
              break;
            }
            else if(calledTypeName != proto.method.DeclaringType.FullName && (targetFuncName==null || targetFuncName.Length < 1))
            {
              // 调用其他类的方法
              isUserDefineFunc = true;
              targetFuncName = methodName;
              isUserDefinedInTableFunc = true;
            }

            // TODO: 更多内置库的函数支持
            if (targetFuncName.Length < 1)
            {
              throw new Exception("暂时不支持使用方法" + operand.FullName)
            }
            if (paramsCount > proto.tmpMaxStackTopSlotIndex - proto.tmp1StackTopSlotIndex - 1)
            {
              throw new Exception("暂时不支持超过" + (proto.tmpMaxStackTopSlotIndex - proto.tmp1StackTopSlotIndex - 1) + "个参数的C#方法调用")
            }

            // TODO: 把抽取若干个参数的方法剥离成单独函数

            // 消耗eval stack顶部若干个值用来调用相应的本类成员函数或者静态函数, 返回值存入eval stack
            // 不断取出eval-stack中数据(paramsCount)个，倒序翻入tmp stot，然后调用函数
            var argStartSlot = proto.tmp3StackTopSlotIndex;
            for (var c = 0; c < paramsCount; c++)
            {
              // 倒序遍历插入参数,不算this, c是第 index=paramsCount-c-1-preaddParamsCount个参数
              // 栈顶取出的是最后一个参数
              // tmp2 slot用来存放eval stack或者其他值，参数从tmp3开始存放
              var slotIndex = paramsCount - c + argStartSlot - 1; // 存放参数的slot位置
              if (slotIndex >= proto.tmpMaxStackTopSlotIndex)
              {
                throw new Exception("不支持将超过" + (proto.tmpMaxStackTopSlotIndex - proto.tmp2StackTopSlotIndex) + "个参数的C#函数编译到glua字节码")
              }
              int methodParamIndex = paramsCount - c - 1 - preaddParamsCount; // 此参数是参数在方法的.net参数(不包含this)中的索引
              var needConvtToBool = false;
              if (methodParamIndex < methodParams.Count && methodParamIndex >= 0)
              {
                var paramType = methodParams[methodParamIndex].ParameterType;
                if (paramType.FullName == "System.Boolean")
                {
                  needConvtToBool = true;
                }
              }

              makeGetTopOfEvalStackInst(proto, i, result, slotIndex, commentPrefix)

              // 对于布尔类型，因为.net中布尔类型参数加载的时候用的ldc.i，加载的是整数，所以这里要进行类型转换成bool类型，使用 not not a来转换
              if (needConvtToBool)
              {
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_NOT, "not %" + slotIndex + " %" + slotIndex + commentPrefix, i))
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_NOT, "not %" + slotIndex + " %" + slotIndex + commentPrefix, i))
              }
              // 这里暂时用tmpMax存放nil

              MakeLoadNilInst(proto, i, result, proto.tmpMaxStackTopSlotIndex, commentPrefix)
              MakeSetTopOfEvalStackInst(proto, i, result, proto.tmpMaxStackTopSlotIndex, commentPrefix)
              subEvalStackSizeInstructions(proto, i, result, commentPrefix)
            }

            var resultSlotIndex = proto.tmp2StackTopSlotIndex;
            // tmp2StackTopSlotIndex 用来存放函数本身, tmp3是第一个参数
            if (useOpcode && !isUserDefineFunc)
            {
              if (targetFuncName == "concat")
              {
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_CONCAT, "concat %" + resultSlotIndex + " %" + proto.tmp3StackTopSlotIndex + " %" + (proto.tmp3StackTopSlotIndex + 1) + commentPrefix, i))
              }
              else if(targetFuncName == "newtable")
              {
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_NEWTABLE, "newtable %" + resultSlotIndex + " 0 0" + commentPrefix, i))
              }
              else if(targetFuncName=="array.add")
              {
                var tableSlot = argStartSlot;
                var valueSlot = argStartSlot + 1;
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_LEN, "len %" + proto.tmp1StackTopSlotIndex + " %" + tableSlot + commentPrefix, i))
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_ADD, "add %" + proto.tmp1StackTopSlotIndex + " %" + proto.tmp1StackTopSlotIndex + " const 1" + commentPrefix, i))
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_SETTABLE, "settable %" + tableSlot + " %" + proto.tmp1StackTopSlotIndex + " %" + valueSlot + commentPrefix + "array.add", i))
              }
              else if(targetFuncName == "array.set")
              {
                var tableSlot = argStartSlot;
                var keySlot = argStartSlot + 1;
                var valueSlot = argStartSlot + 2;
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_SETTABLE, "settable %" + tableSlot + " %" + keySlot + " %" + valueSlot + commentPrefix + " array.set", i))
              }
              else if(targetFuncName == "map.set")
              {
                var tableSlot = argStartSlot;
                var keySlot = argStartSlot + 1;
                var valueSlot = argStartSlot + 2;
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_SETTABLE, "settable %" + tableSlot + " %" + keySlot + " %" + valueSlot + commentPrefix + " map.set", i))
              }
              else if(targetFuncName == "array.get")
              {
                var tableSlot = argStartSlot;
                var keySlot = argStartSlot + 1;
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_GETTABLE, "gettable %"+ resultSlotIndex + " %" + tableSlot + " %" + keySlot + commentPrefix + " array.set", i))
              }
              else if(targetFuncName == "map.get")
              {
                var tableSlot = argStartSlot;
                var keySlot = argStartSlot + 1;
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_GETTABLE, "gettable %" + resultSlotIndex + " %" + tableSlot + " %" + keySlot + commentPrefix + " map.set", i))
              }
              else if(targetFuncName == "len")
              {
                var tableSlot = argStartSlot;
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_LEN, "len %" + resultSlotIndex + " %" + tableSlot + commentPrefix + "array.len", i))
              }
              else if(targetFuncName == "array.pop")
              {
                var tableSlot = argStartSlot;
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_LEN, "len %" + proto.tmp1StackTopSlotIndex + " %" + tableSlot + commentPrefix, i))
                LoadNilInstruction(proto, resultSlotIndex, i, result, commentPrefix)
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_SETTABLE, "settable %" + tableSlot + " %" + proto.tmp1StackTopSlotIndex + " %" + resultSlotIndex + commentPrefix + "array.pop", i))
              }
              else if(targetFuncName == "map.pairs")
              {
                // 调用pairs函数，返回1个结果，迭代器函数对象
                var tableSlot = argStartSlot;
                var envIndex = proto.internUpvalue("ENV")
                proto.internConstantValue("pairs")
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_GETTABUP, "gettabup %" + proto.tmp1StackTopSlotIndex + " @" + envIndex + " const \"pairs\"" + commentPrefix, i))
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_MOVE, "move %" + proto.tmp2StackTopSlotIndex + " %" + tableSlot + commentPrefix, i))
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_CALL, "call %" + proto.tmp1StackTopSlotIndex + " 2 2" + commentPrefix, i))
                // pairs函数返回1个函数，返回在刚刚函数调用时函数所处slot tmp1
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_MOVE, "move %" + resultSlotIndex + " %" + proto.tmp1StackTopSlotIndex + commentPrefix + " map.pairs", i))
              }
              else if (targetFuncName == "array.ipairs")
              {
                // 调用ipairs函数，返回1个结果，迭代器函数对象
                var tableSlot = argStartSlot;
                var envIndex = proto.internUpvalue("ENV")
                proto.internConstantValue("ipairs")
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_GETTABUP, "gettabup %" + proto.tmp1StackTopSlotIndex + " @" + envIndex + " const \"ipairs\"" + commentPrefix, i))
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_MOVE, "move %" + proto.tmp2StackTopSlotIndex + " %" + tableSlot + commentPrefix, i))
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_CALL, "call %" + proto.tmp1StackTopSlotIndex + " 2 2" + commentPrefix, i))
                // ipairs函数返回1个函数，返回在刚刚函数调用时函数所处slot tmp1
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_MOVE, "move %" + resultSlotIndex + " %" + proto.tmp1StackTopSlotIndex + commentPrefix + " array.ipairs", i))
              }
              else if(targetFuncName == "iterator_call")
              {
                // 调用迭代器函数，接受两个参数(map和上一个key),返回2个结果写入一个新的table中, {"Key": 第一个返回值, "Value": 第二个返回值}
                var iteratorSlot = argStartSlot;
                var tableSlot = argStartSlot +1;
                var keySlot = argStartSlot + 2;
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_CALL, "call %" + iteratorSlot + " 3 3" + commentPrefix, i))
                // 迭代器函数返回2个结果，返回在刚刚函数调用时函数所处slot，用来构造{"Key": 返回值1, "Value": 返回值2}
                var resultKeySlot = iteratorSlot;
                var resultValueSlot = iteratorSlot + 1;
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_NEWTABLE,
                  "newtable %"+proto.tmp1StackTopSlotIndex + " 0 0" + commentPrefix, i))
                MakeLoadConstInst(proto, i, result, proto.tmp2StackTopSlotIndex, "Key", commentPrefix)
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_SETTABLE,
                  "settable %" + proto.tmp1StackTopSlotIndex + " %"+proto.tmp2StackTopSlotIndex + " %" + resultKeySlot + commentPrefix, i))
                MakeLoadConstInst(proto, i, result, proto.tmp2StackTopSlotIndex, "Value", commentPrefix)
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_SETTABLE,
                  "settable %" + proto.tmp1StackTopSlotIndex + " %"+ proto.tmp2StackTopSlotIndex + " %" + resultValueSlot + commentPrefix, i))
                // 把产生的table作为结果
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_MOVE,
                  "move %" + resultSlotIndex + " %" + proto.tmp1StackTopSlotIndex + commentPrefix + " map.iterator_call", i))
              }
              else
              {
                throw new Exception("not supported opcode " + targetFuncName)
              }
            }
            else if (isUserDefineFunc)
            {
              if (!isUserDefinedInTableFunc)
              {
                // 访问本类的其他成员方法，访问的是父proto的局部变量，所以是访问upvalue
                var protoName = TranslatorUtils.MakeProtoName(calledMethod)
                var funcUpvalIndex = proto.internUpvalue(protoName)
                proto.internConstantValue(protoName)
                result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_GETUPVAL,
                  "getupval %" + proto.tmp2StackTopSlotIndex + " @" + funcUpvalIndex + commentPrefix, i))
              }
              else
              {
                // 访问其他类的成员方法，需要gettable取出函数
                var protoName = TranslatorUtils.MakeProtoName(calledMethod)
                var funcUpvalIndex = proto.internUpvalue(protoName)
                proto.internConstantValue(protoName)
                if(targetFuncName == null || targetFuncName.Length<1)
                {
                  targetFuncName = calledMethod.name;
                }
                MakeLoadConstInst(proto, i, result, proto.tmp2StackTopSlotIndex, targetFuncName, commentPrefix)
                if (needPopFirstArg)
                {
                  // object模拟glua module，module信息在calledMethod的this参数中
                  // 这时候eval stack应该是[this], argStart开始的数据应该是this, ...
                  // result.addRange(DebugEvalStack(proto))
                  makeGetTopOfEvalStackInst(proto, i, result, proto.tmp1StackTopSlotIndex, commentPrefix)
                  subEvalStackSizeInstructions(proto, i, result, commentPrefix)
                  result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_GETTABLE,
                    "gettable %" + proto.tmp2StackTopSlotIndex + " %" + proto.tmp1StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
                }
                else
                {
                  result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_GETTABLE,
                    "gettable %" + proto.tmp2StackTopSlotIndex + " %" + argStartSlot + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
                }
              }
            }
            else if (targetModuleName.Length < 1)
            {
              // 全局函数或局部函数
              // TODO: 这里要从上下文查找是否是局部变量，然后分支处理，暂时都当全局函数处理
              var envUp = proto.internUpvalue("ENV")
              proto.internConstantValue(targetFuncName)
              result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_GETTABUP, "gettabup %" + proto.tmp2StackTopSlotIndex + " @" + envUp + " const \"" + targetFuncName + "\"" + commentPrefix, i))
            }
            else
            {
              throw new Exception("not supported yet")
            }
            if (!useOpcode)
            {
              // 调用tmp2位置的函数，函数调用返回结果会存回tmp2开始的slots
              result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_CALL,
                "call %" + proto.tmp2StackTopSlotIndex + " " + (paramsCount + 1) + " " + (returnCount + 1) +
                  commentPrefix, i))
              result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_MOVE, "move %" + proto.tmp3StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
            }
            else if(hasReturn)
            {
              result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_MOVE, "move %" + proto.tmp3StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
            }
            // 把调用结果存回eval-stack
            if (hasReturn)
            {
              // 调用结果在tmp3
              addEvalStackSizeInstructions(proto, i, result, commentPrefix)
              MakeSetTopOfEvalStackInst(proto, i, result, proto.tmp3StackTopSlotIndex, commentPrefix)
            }
          }
          break;
          case Code.Br:
          case Code.Br_S:
          {
            var toJmpToInst = i.Operand as Instruction;

            MakeJmpToInstruction(proto, i, "br", toJmpToInst, result, commentPrefix, onlyNeedResultCount)
          }
          break;
          case Code.Beq:
          case Code.Beq_S:
          case Code.Bgt:
          case Code.Bgt_S:
          case Code.Bgt_Un:
          case Code.Bgt_Un_S:
          case Code.Blt:
          case Code.Blt_S:
          case Code.Blt_Un:
          case Code.Blt_Un_S:
          case Code.Bge:
          case Code.Bge_S:
          case Code.Bge_Un:
          case Code.Bge_Un_S:
          case Code.Ble:
          case Code.Ble_S:
          case Code.Ble_Un:
          case Code.Ble_Un_S:
          case Code.Bne_Un:
          case Code.Bne_Un_S:
          {
            // 比较两个值(top-1和top)，满足一定条件就jmp到目标指令
            // beq: 如果两个值相等，则将控制转移到目标指令
            // bgt: 如果第一个值大于第二个值，则将控制转移到目标指令
            // bge: 如果第一个值大于或等于第二个值，则将控制转移到目标指令
            // blt: 如果第一个值小于第二个值，则将控制转移到目标指令
            // ble: 如果第一个值小于或等于第二个值，则将控制转移到目标指令
            // bne: 当两个无符号整数值或不可排序的浮点型值不相等时，将控制转移到目标指令
            var toJmpToInst = i.Operand as Instruction;
            Console.WriteLine(i)
            var toJmpTooffset = toJmpToInst.offset;
            var opCode = i.OpCode.Code;
            var opName = opCode.ToString().Replace(" ", "_")
            string compareType;
            if (opCode == Code.Beq || opCode == Code.Beq_S)
            {
              compareType = "eq";
            }
            else if (opCode == Code.Bgt || opCode == Code.Bgt_S || opCode == Code.Bgt_Un ||
              opCode == Code.Bgt_Un_S)
            {
              compareType = "gt";
            }
            else if (opCode == Code.Bge || opCode == Code.Bge_S || opCode == Code.Bge_Un ||
              opCode == Code.Bge_Un_S)
            {
              compareType = "ge";
            }
            else if (opCode == Code.Blt || opCode == Code.Blt_S || opCode == Code.Blt_Un ||
              opCode == Code.Blt_Un_S)
            {
              compareType = "lt";
            }
            else if (opCode == Code.Ble || opCode == Code.Ble_S || opCode == Code.Ble_Un ||
              opCode == Code.Ble_Un_S)
            {
              compareType = "le";
            }
            else if (opCode == Code.Bne_Un || opCode == Code.Bne_Un_S)
            {
              compareType = "ne";
            }
            else
            {
              throw new Exception("Not supported opcode " + opCode)
            }
            // 从eval stack弹出两个值(top和top-1)，比较大小，比较结果存入eval stack
            result.add(proto.makeEmptyInstruction(i.ToString()))

            // 消耗eval stack的顶部2个值, 然后比较，比较结果存入eval stack
            // 获取eval stack顶部的值
            proto.internConstantValue(1)
            var arg1SlotIndex = proto.tmp3StackTopSlotIndex + 1; // top-1
            var arg2SlotIndex = proto.tmp3StackTopSlotIndex + 2; // top
            makeGetTopOfEvalStackInst(proto, i, result, arg2SlotIndex, commentPrefix)
            // eval stack弹出1个值
            MakeLoadNilInst(proto, i, result, proto.tmp3StackTopSlotIndex, commentPrefix)
            MakeSetTopOfEvalStackInst(proto, i, result, proto.tmp3StackTopSlotIndex, commentPrefix)
            subEvalStackSizeInstructions(proto, i, result, commentPrefix)

            // 再次获取eval stack栈顶的值
            makeGetTopOfEvalStackInst(proto, i, result, arg1SlotIndex, commentPrefix)
            // eval stack弹出一个值
            MakeSetTopOfEvalStackInst(proto, i, result, proto.tmp3StackTopSlotIndex, commentPrefix)
            subEvalStackSizeInstructions(proto, i, result, commentPrefix)

            // 比较
            if (compareType == "eq")
            {
              // eq: if ((RK(B) == RK(C)) ~= A) then pc++
              result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_ADD,
                "eq " + 0 + " %" + arg1SlotIndex + " %" + arg2SlotIndex +
                  commentPrefix, i))
            }
            else if (compareType == "ne")
            {
              // eq: if ((RK(B) == RK(C)) ~= A) then pc++
              result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_ADD,
                "eq " + 1 + " %" + arg1SlotIndex + " %" + arg2SlotIndex +
                  commentPrefix, i))
            }
            else if (compareType == "gt")
            {
              // lt: if ((RK(B) <  RK(C)) ~= A) then pc++
              result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_ADD,
                "lt " + 1 + " %" + arg1SlotIndex + " %" + arg2SlotIndex +
                  commentPrefix, i))
            }
            else if (compareType == "lt")
            {
              // lt: if ((RK(B) <  RK(C)) ~= A) then pc++
              result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_ADD,
                "lt " + 0 + " %" + arg1SlotIndex + " %" + arg2SlotIndex +
                  commentPrefix, i))
            }
            else if (compareType == "ge")
            {
              // lt: if ((RK(B) <  RK(C)) ~= A) then pc++
              result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_ADD,
                "le " + 1 + " %" + arg1SlotIndex + " %" + arg2SlotIndex +
                  commentPrefix, i))
            }
            else if (compareType == "le")
            {
              // lt: if ((RK(B) <  RK(C)) ~= A) then pc++
              result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_ADD,
                "le " + 0 + " %" + arg1SlotIndex + " %" + arg2SlotIndex +
                  commentPrefix, i))
            }
            else
            {
              throw new Exception("not supported compare type " + i)
            }

            // 不满足条件就执行下条tmp指令，否则执行下下条指令
            var jmpLabel1 = proto.name + "_1_" + opName + "_" + i.offset;
            var offsetOfInst1 = 2; // 如果不满足条件，跳转到本指令后的指令
            jmpLabel1 =
              proto.internNeedLocationLabel(
                offsetOfInst1 + proto.notEmptyCodeInstructions().Count + notEmptyGluaInstructionsCountInList(result), jmpLabel1)
            result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_JMP, "jmp 1 $" + jmpLabel1 + commentPrefix,
              i))

            // 满足条件，跳转到目标指令
            MakeJmpToInstruction(proto, i, opName, toJmpToInst, result, commentPrefix, onlyNeedResultCount)
          }
          break;
          case Code.Brtrue:
          case Code.Brtrue_S:
          case Code.Brfalse:
          case Code.Brfalse_S:
          {
            // Branch to target if value is 1(true) or zero (false)
            var toJmpToInst = i.Operand as Instruction;
            Console.WriteLine(i)
            var toJmpTooffset = toJmpToInst.offset;
            var opCode = i.OpCode.Code;
            var opName = (opCode == Code.Brtrue || opCode == Code.Brtrue_S) ? "brtrue" : "brfalse";

            var eqCmpValue = (opCode == Code.Brtrue || opCode == Code.Brtrue_S) ? 0 : 1;

            // result.addRange(DebugEvalStack(proto))

            // 先判断eval stack top 是否是 1 or zero(根据是brtrue/brfalse决定cmpValue)
            makeGetTopOfEvalStackInst(proto, i, result, proto.tmp2StackTopSlotIndex, commentPrefix)
            MakeLoadConstInst(proto, i, result, proto.tmp3StackTopSlotIndex, 0, commentPrefix)
            // eq: if ((tmp2 == 0) ~= A) then pc++
            result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_ADD,
              "eq " + eqCmpValue + " %" + proto.tmp2StackTopSlotIndex + " %" + proto.tmp3StackTopSlotIndex +
                commentPrefix, i))

            // 为eqCmpValue就执行下条tmp指令，否则执行下下条指令
            var jmpLabel1 = proto.name + "_1_" + opName + "_" + i.offset;
            var offsetOfInst1 = 2; // 如果为eqCmpValue，跳转到目标指令
            jmpLabel1 =
              proto.internNeedLocationLabel(
                offsetOfInst1 + proto.notEmptyCodeInstructions().Count + notEmptyGluaInstructionsCountInList(result), jmpLabel1)
            result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_JMP, "jmp 1 $" + jmpLabel1 + commentPrefix,
              i))

            var jmpLabel2 = proto.name + "_2_" + opName + "_" + i.offset;
            var offsetOfInst2 = 2; // 如果不为eqCmpValue，则跳转到本brtrue/brfalse指令后的指令
            jmpLabel2 = proto.internNeedLocationLabel(offsetOfInst2 + proto.notEmptyCodeInstructions().Count + notEmptyGluaInstructionsCountInList(result), jmpLabel2)
            result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_JMP, "jmp 1 $" + jmpLabel2 + commentPrefix,
              i))

            // 跳转到目标指令
            MakeJmpToInstruction(proto, i, opName, toJmpToInst, result, commentPrefix, onlyNeedResultCount)
          }
          break;
            result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_RETURN, "return %0 1" + commentPrefix + " ret", i))
          }
          break;
          case Code.Newarr:
          {
            // 因为.net数组是0-based，glua的数组是1-based，所以不支持.net数组
            throw new Exception("Not support .net array now")
            // FIXME
            // 创建一个空数组放入eval-stack顶
            // 获取eval stack顶部的值
            result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_NEWTABLE, "newtable %" + proto.tmp2StackTopSlotIndex + " 0 0" + commentPrefix, i))
            addEvalStackSizeInstructions(proto, i, result, commentPrefix)
            MakeSetTopOfEvalStackInst(proto, i, result, proto.tmp2StackTopSlotIndex, commentPrefix + " newarr")
          }
          break;
          case Code.Initobj:
          {
            throw new Exception("not supported " + i)
            // 将位于指定地址的值类型的每个字段初始化为空引用或适当的基元类型的 0
            // TODO: 根据是什么类型进行init，如果是Nullable类型进行initobj，则弹出数据，插入nil
            // TODO: 改成 if判断是否nil，如果是nil，保持，如果不是，设置为nil
            //LoadNilInstruction(proto, proto.tmp1StackTopSlotIndex, i, result, commentPrefix)
            PopFromEvalStackToSlot(proto, proto.tmp2StackTopSlotIndex,  i, result, commentPrefix)
            addEvalStackSizeInstructions(proto, i, result, commentPrefix)
            //result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_SETTABLE, "settable %" + proto.evalStackIndex + " %" + proto.evalStackSizeIndex + " %"+proto.tmp1StackTopSlotIndex, i))
            makeSetTopOfEvalStackInst(proto,i,result,proto.tmp1StackTopSlotIndex,commentPrefix);
            proto.addNotMappedILInstruction(i)
            // return result;
          }
          break;
          case Code.Newobj:
          {
            // 如果是Nullable类型构建，什么都不做
            var operand = i.Operand as MethodReference;
            if(operand.DeclaringType.FullName.StartsWith("System.Nullable"))
            {
              // 什么都不做
              break;
            }


            // 如果是contract类型,调用构造函数，而不是设置各成员函数
            if (operand.DeclaringType is TypeDefinition
              && TranslatorUtils.IsContractType(operand.DeclaringType as TypeDefinition)
              && operand.DeclaringType == this.ContractType
              && this.ContractType != null)
            {
              var protoName = TranslatorUtils.MakeProtoNameOfTypeConstructor(operand.DeclaringType) // 构造函数的名字
              result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_CLOSURE,
                "closure %" + proto.tmp2StackTopSlotIndex + " " + protoName, i))
              result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_CALL,
                "call %" + proto.tmp2StackTopSlotIndex + " 1 2", i))
              // 返回值(新对象处于tmp2 slot)
            }
            else
            {
              // 创建一个空的未初始化对象放入eval-stack顶
              // 获取eval stack顶部的值
              result.add(proto.makeInstructionLine(GluaOpCodeEnums.OP_NEWTABLE, "newtable %" + proto.tmp2StackTopSlotIndex + " 0 0" + commentPrefix, i))
            }

            addEvalStackSizeInstructions(proto, i, result, commentPrefix)
            MakeSetTopOfEvalStackInst(proto, i, result, proto.tmp2StackTopSlotIndex, commentPrefix + " newobj")
          }
          break;
          case Code.Dup:
          {
            // 把eval stack栈顶元素复制一份到eval-stack顶
            // 获取eval stack顶部的值
            makeGetTopOfEvalStackInst(proto, i, result, proto.tmp2StackTopSlotIndex, commentPrefix)
            addEvalStackSizeInstructions(proto, i, result, commentPrefix)
            MakeSetTopOfEvalStackInst(proto, i, result, proto.evalStackSizeIndex, commentPrefix + " dup")
          }
          break;
          case Code.Pop:
          {
            // 移除当前位于计算堆栈顶部的值
            MakeLoadNilInst(proto, i, result, proto.tmp3StackTopSlotIndex, commentPrefix)
            MakeSetTopOfEvalStackInst(proto, i, result, proto.tmp3StackTopSlotIndex, commentPrefix)
            subEvalStackSizeInstructions(proto, i, result, commentPrefix + " pop")
          }
          break;
          case Code.Stelem_Ref:
          {
            throw new Exception("not supported opcode " + i.OpCode + " now")
          }
          break;
          case Code.Cgt:
          case Code.Cgt_Un:
          case Code.Clt:
          case Code.Clt_Un:
          case Code.Ceq:
          {
            var opCode = i.OpCode.Code;
            // 比较两个值(eval stack top-1 和 top)。如果第一个值大于第二个值(如果是Clt/Clt_Un，则是比较是否小于)，则将整数值 1 (int32) 推送到计算堆栈上；反之，将 0 (int32) 推送到计算堆栈上
            //var isComparingUnsignedValues = opCode == Code.Cgt_Un || opCode == Code.Clt_Un; // 是否是比较两个无符号值
            //if (isComparingUnsignedValues)
            //{
            //  throw new Exception("not support compare unsigned values now")
            //}
            string compareType;
            if (opCode == Code.Cgt || opCode == Code.Cgt_Un)
            {
              compareType = "gt";
            }
            else if (opCode == Code.Clt || opCode == Code.Clt_Un)
            {
              compareType = "lt";
            }
            else if (opCode == Code.Ceq)
            {
              compareType = "eq";
            }
            else
            {
              throw new Exception("not supported comparator " + i)
            }

            if(i.Previous!=null && i.Previous.OpCode.Code==Code.Ldnull && compareType != "eq")
            {
              // 因为C#中将 a != null转换成了 ldnull; Gt_Un 指令
              compareType = "ne";
            }

            MakeCompareInstructions(proto, compareType, i, result, commentPrefix)

          }
          break;
          case Code.Ldsfld:
          case Code.Ldsflda:
          {
            // 将静态字段的值推送到计算堆栈上
            // TODO
            throw new Exception("not supported opcode " + i)
          }
          break;
          case Code.Isinst:
          {
            // 测试对象引用（O 类型）是否为特定类的实例. eval-stack栈顶是要测试对象，如果检查失败，弹出并压栈null
            var toCheckType = i.Operand as TypeReference;
            // 这里强行验证通过，因为glua中运行时没有C#中的具体类型信息.
            proto.addNotMappedILInstruction(i)

          }
          break;
          case Code.Conv_I:
          case Code.Conv_I1:
          case Code.Conv_I2:
          case Code.Conv_I4:
          case Code.Conv_I8:
          case Code.Conv_R4:
          case Code.Conv_R8:
          case Code.Conv_R_Un:
          case Code.Conv_U:
          case Code.Conv_U1:
          case Code.Conv_U2:
          case Code.Conv_U4:
          case Code.Conv_U8:
          {
            // 将位于计算堆栈顶部的值转换为其他格式
            proto.addNotMappedILInstruction(i)
          } break;
          default:
          {
            throw new Exception("not supported opcode " + i.OpCode + " now")
          }
        }
        */
        return result
    }

    fun notEmptyGluaInstructionsCountInList(items: MutableList<UvmInstruction>): Int {
        var count = 0;
        for (item in items) {
            if (!(item is UvmEmptyInstruction)) {
                count++;
            }
        }
        return count;
    }

    fun translateJvmMethod(method: MethodDefinition, jvmContentBuilder: StringBuilder,
                           luaAsmBuilder: StringBuilder, parentProto: UvmProto?): UvmProto? {
        if (method.name.equals("<init>") || method.name.equals("<clinit>")) {
            return null;
        }
        var protoName = TranslatorUtils.makeProtoName(method)
        var proto = UvmProto(protoName)
        proto.sizeP = method.signature?.paramTypes?.size ?: 0; // 参数数量
        proto.paramsStartIndex = 0;
        if (!method.isStatic) {
            proto.sizeP++; // this对象作为第一个参数
        }
        proto.isvararg = false;
        proto.parent = parentProto;
        proto.method = method;
        jvmContentBuilder.append("method " + method.fullName() + ", simple name is " + method.name + "\r\n")
        // 在glua的proto开头创建一个table局部变量，模拟evaluation stack
        proto.evalStackIndex = method.maxLocals + 1 // eval stack所在的局部变量的slot index
        var createEvalStackInst = UvmInstruction("newtable %" + proto.evalStackIndex + " 0 0") // 除参数外的第一个局部变量固定用作eval stack
        // createEvalStackInst.LineInSource = method.
        proto.addInstruction(createEvalStackInst)


        proto.evalStackSizeIndex = proto.evalStackIndex + 1; // 固定存储最新eval stack长度的slot
        proto.internConstantValue(0)
        proto.internConstantValue(1)
        proto.addInstruction(proto.makeInstructionLine("loadk %" + proto.evalStackSizeIndex + " const 0", null))

        // 除了eval-stack的额外局部变量slot，额外还要提供2个slot用来存放一个栈顶值，用来做存到eval-stack的中转
        proto.tmp1StackTopSlotIndex = proto.evalStackIndex + 2; // 临时存储，比如存放栈中取出的值或者参数值，返回值等
        proto.tmp2StackTopSlotIndex = proto.tmp1StackTopSlotIndex + 1; // 临时存储，比如存放临时的栈顶值或者参数值等
        proto.tmp3StackTopSlotIndex = proto.tmp2StackTopSlotIndex + 1; // 临时存储，比如存放临时的参数值或者nil等
        proto.tmpMaxStackTopSlotIndex = proto.tmp1StackTopSlotIndex + 17; // 目前最多支持18个临时存储


        proto.callStackStartIndex = proto.tmpMaxStackTopSlotIndex + 10; // 模拟C#的call stack的起始slot索引,+2是为了留位置给tmp区域函数调用的返回值


        proto.numparams = proto.sizeP;
        proto.maxCallStackSize = 0;

        var lastLinenumber = 0;

        // 不需要支持类型的虚函数调用，只支持静态函数
        for (i in method.code) {
            jvmContentBuilder.append("" + i.instLine + "\r\n")
            var commentPrefix = ";"; // 一行指令的行信息的注释前缀
            var hasLineInfo = i.linenumber > 0
            if (hasLineInfo) {
                var startLine = i.linenumber
                if (startLine > 1000000) {
                    startLine = lastLinenumber;
                }
                commentPrefix += "L" + startLine + ";";
                lastLinenumber = startLine;
            } else {
                commentPrefix += "L" + lastLinenumber + ";";
            }
            commentPrefix += ";" + i.instLine.replace('\n', ' ')
            var dotnetOpStr = i.opCodeName()
            // commentPrefix += dotnetOpStr;
            // 关于.net的evaluation stack在glua字节码虚拟机中的实现方式
            // 维护一个evaluation stack的局部变量,，每个proto入口处清空它
            var gluaInstructions = translateJvmInstruction(proto, i, commentPrefix, false)
            for (gluaInst in gluaInstructions) {
                proto.addInstruction(gluaInst)
            }
        }

        // 处理NeededLocationsMap，忽略empty lines
        var notEmptyInstructionsOfProto = proto.notEmptyCodeInstructions()
        for (j in 0..(notEmptyInstructionsOfProto.size - 1)) {
            var inst = notEmptyInstructionsOfProto[j];
            if (proto.neededLocationsMap.containsKey(j)) {
                inst.locationLabel = proto.neededLocationsMap[j];
            }
        }

        //add by zq
        ReduceProtoUvmInsts(proto)


        // TODO: 可能jmp到指令尾部?

        if (proto.codeInstructions.size < 1) {
            proto.addInstructionLine("return %0 1", null)
        }

        proto.maxStackSize = proto.callStackStartIndex + 1 + proto.maxCallStackSize;

        // 函数代码块结尾添加return 0 1指令来结束代码块
        val endBlockInst = UvmInstruction("return %0 1")

        jvmContentBuilder.append("\r\n")
        return proto;
    }

    fun debugEvalStack(proto: UvmProto): MutableList<UvmInstruction> {
        val result: MutableList<UvmInstruction> = mutableListOf()
        // for debug,输出eval stack
        result.add(proto.makeEmptyInstruction("for debug eval stack"))
        var envSlot = proto.internUpvalue("ENV")
        proto.internConstantValue("pprint")
        result.add(proto.makeInstructionLine("gettabup %" + (proto.evalStackIndex + 20) + " @" + envSlot + " const \"pprint\"; for debug eval-stack", null))
        result.add(proto.makeInstructionLine("move %" + (proto.evalStackIndex + 21) + " %" + proto.evalStackIndex + ";  for debug eval-stack", null))
        result.add(proto.makeInstructionLine("call %" + (proto.evalStackIndex + 20) + " 2 1;  for debug eval-stack", null))
        result.add(proto.makeEmptyInstruction(""))
        return result
    }

    // TODO
    /*
  public static string TranslateDotNetDllToGlua(string dllFilepath)
  {
    var readerParameters = new ReaderParameters { ReadSymbols = true };
    var assemblyDefinition = AssemblyDefinition.ReadAssembly(dllFilepath, readerParameters)
    var sampleModule = assemblyDefinition.MainModule; // ModuleDefinition.ReadModule(sampleAssemblyPath)
    var translator = new ILToGluaTranslator()
    var ilContentBuilder = new StringBuilder()
    var luaAsmContentBuilder = new StringBuilder()
    var symbolReader = sampleModule.SymbolReader;
    translator.TranslateModule(sampleModule, ilContentBuilder, luaAsmContentBuilder)
    var ilText = ilContentBuilder.ToString()

    var fileDir = Path.GetDirectoryName(dllFilepath)
    var dllFileName = Path.GetFileNameWithoutExtension(dllFilepath)
    var ilAssFilepath = Path.GetFullPath(Path.Combine(fileDir, dllFileName + ".dotnet_ass.txt"))
    using (var ilAssFile = File.create(ilAssFilepath))
    {
      var bytes = Encoding.UTF8.GetBytes(ilText)
      ilAssFile.Write(bytes, 0, bytes.Length)
    }

    var assText = luaAsmContentBuilder.ToString()

    var gluasOutputFilePath = Path.GetFullPath(Path.Combine(fileDir, dllFileName + ".gluas"))
    using (var outFileStream = File.create(gluasOutputFilePath))
    {
      var bytes = Encoding.UTF8.GetBytes(assText)
      outFileStream.Write(bytes, 0, bytes.Length)
    }

    var metaInfoJson = translator.GetMetaInfoJson()
    using (var os = File.create(Path.GetFullPath(Path.Combine(fileDir, dllFileName + ".meta.json"))))
    {
      var bytes = Encoding.UTF8.GetBytes(metaInfoJson)
      os.Write(bytes, 0, bytes.Length)
    }
    return gluasOutputFilePath;
  }
  */

    //----------------------------reduce code imp---------------------------------------

    //------------------------reduce code imp----------------------------------
    fun checkSlot(slot:String)
    {
        if (!slot.startsWith("%"))
        {
            throw GjavacException("error ReduceUvmInsts,invalid uvm inst:slot")
        }
    }

    fun checkConstStr(str:String)
    {
        if (!str.startsWith("const \"") || !str.endsWith("\""))
        {
            throw GjavacException("error ReduceUvmInsts,invalid uvm inst:conststr")
        }
    }

   fun getLtCount(intlist:MutableList<Int> , inta:Int):Int
    {
        var count = 0
        for(i in intlist)
        {
            if (i < inta) count++
        }
        return count
    }

    fun checkLoctionMoveRight(originalLoc:Int, newLoc:Int, proto: UvmProto)
    {
        if (newLoc < originalLoc && newLoc >= 0)
        {
            var newIdx = 0;
            for(j in 0..(originalLoc - 1))
            {
                if (!(proto.codeInstructions[j] is UvmEmptyInstruction))
                {
                    newIdx++
                }
            }
            if (!proto.codeInstructions[originalLoc].hasLocationLabel()|| newLoc != newIdx)
            {
                throw GjavacException("loc move error,please check!!!!")
            }
        }
    }

    fun ReduceUvmInstsImp(proto: UvmProto):Int
    {
        val notEmptyCodeInstructions:MutableList<UvmInstruction> = mutableListOf()
        for (codeInst in proto.codeInstructions)
        {
            if (!(codeInst is UvmEmptyInstruction))
            {
                notEmptyCodeInstructions.add(codeInst)
            }
        }
        proto.codeInstructions.clear()
        for(inst in notEmptyCodeInstructions)
        {
            proto.codeInstructions.add(inst)
        }

        //check location map first
        for (loc in proto.neededLocationsMap)
        {
            if(!proto.codeInstructions[loc.key].hasLocationLabel()){
                throw GjavacException("location map not match !!!!")
            }
        }

        val CodeInstructions = proto.codeInstructions
        val delIndexes:MutableList<Int>  = mutableListOf()
        val modifyIndexes:MutableList<Int>  = mutableListOf()
        val modifyUvmIns:MutableList<UvmInstruction>  = mutableListOf()
        var AddEvalStackSizeIndex:Int = -1
        var SetEvalStackTopIndex:Int = -1
        var UvmInstCount:Int = CodeInstructions.count()
        var affectedSlot:String = ""
        var uvmInsstr:String = ""
        var commentIndex:Int = -1
        var constStr:String = ""
        var commentPrefix:String = ""

        for(gIndex in 0..(UvmInstCount - 1))
        {
            var uvmIns: UvmInstruction = CodeInstructions[gIndex]
            if (uvmIns.jvmInstruction == null)
            {
                continue
            }

            if (uvmIns.hasLocationLabel())
            {
                AddEvalStackSizeIndex = -1
                SetEvalStackTopIndex = -1
            }

            uvmInsstr = uvmIns.toString().trim()
            commentIndex = uvmInsstr.indexOf(";")
            if (commentIndex >= 0)
            {
                commentPrefix = "" + uvmInsstr.substring(commentIndex)
                uvmInsstr = uvmInsstr.substring(0, commentIndex)
            }
            else
            {
                commentPrefix = ""
            }
            var ss = uvmInsstr.split(" ")

            var evalOp: EvalStackOpEnum = CodeInstructions[gIndex].evalStackOp
            when (evalOp)
            {
                EvalStackOpEnum.valueOf("AddEvalStackSize")->
                {

                    AddEvalStackSizeIndex = gIndex
                    SetEvalStackTopIndex = -1
                }
                EvalStackOpEnum.valueOf("SetEvalStackTop")->
                {
                    if (!uvmInsstr.startsWith("settable"))
                    {
                        throw GjavacException("error ReduceUvmInsts,invalid uvm inst:" + uvmInsstr)
                    }
                    var ssCount = ss.count()
                    if (ssCount == 4)
                    {
                        affectedSlot = ss[3].trim()
                        checkSlot(affectedSlot)
                    }
                    else if (ssCount >= 5)
                    {
                        affectedSlot = ""
                        constStr = ss[3]
                        for(j in 4..(ssCount - 1))
                        {
                            constStr = constStr + " " + ss[j]
                        }
                        checkConstStr(constStr)
                    }
                    else
                    {
                        throw GjavacException("error ReduceUvmInsts,inst count err, invalid uvm inst:" + uvmInsstr)
                    }
                    SetEvalStackTopIndex = gIndex
                    AddEvalStackSizeIndex = -1
                }
                EvalStackOpEnum.valueOf("SubEvalStackSize")->
                {
                    if (AddEvalStackSizeIndex != -1)
                    {
                        if (uvmIns.hasLocationLabel() || CodeInstructions[AddEvalStackSizeIndex].hasLocationLabel())
                        {
                            print("do not remove, locactionmap needed \n")
                        }
                        else
                        {
                            delIndexes.add(AddEvalStackSizeIndex)
                            delIndexes.add(gIndex)
                            print("find add-sub evalsize group, index " + AddEvalStackSizeIndex + "," + gIndex + "\n")
                        }
                    }
                    AddEvalStackSizeIndex = -1
                    SetEvalStackTopIndex = -1
                }
                EvalStackOpEnum.valueOf("GetEvalStackTop")->
                {
                    if (SetEvalStackTopIndex != -1)
                    {
                        if (!uvmInsstr.startsWith("gettable"))
                        {
                            throw GjavacException("error ReduceUvmInsts,invalid uvm inst:" + uvmInsstr)
                        }
                        if (ss.count() != 4)
                        {
                            throw GjavacException("error ReduceUvmInsts,inst count err, invalid uvm inst:" + uvmInsstr)
                        }
                        var targetSlot = ss[1]
                        var inst: UvmInstruction
                        if (affectedSlot.startsWith("%"))
                        {
                            inst = proto.makeInstructionLine("move " + targetSlot + " " + affectedSlot + commentPrefix + ";get from slot", uvmIns.jvmInstruction)
                        }
                        else if (constStr.startsWith("const "))
                        {
                            inst = proto.makeInstructionLine("loadk " + targetSlot + " " + constStr + commentPrefix + ";get from slot", uvmIns.jvmInstruction)
                        }
                        else
                        {
                            throw GjavacException("error ReduceUvmInsts,invalid uvm inst:" + uvmInsstr)
                        }

                        if (CodeInstructions[SetEvalStackTopIndex].hasLocationLabel())
                        {
                            print("do not remove, locactionmap needed \n")
                        }
                        else
                        {
                            delIndexes.add(SetEvalStackTopIndex)
                            modifyIndexes.add(gIndex)
                            modifyUvmIns.add(inst)
                            print("find set-get evaltop group, index " + SetEvalStackTopIndex + "," + gIndex + "\n")
                        }
                    }
                    AddEvalStackSizeIndex = -1
                    SetEvalStackTopIndex = -1
                }
                else ->
                {
                    if (SetEvalStackTopIndex != -1)
                    {
                        if (affectedSlot.length > 1)
                        {
                            if (ss.contains(affectedSlot))
                            {
                                print("do not remove , affect slot:" + affectedSlot + "\n")
                                SetEvalStackTopIndex = -1
                            }
                            else if (ss[0].equals("call")||ss[0].equals("tailcall"))
                            {
                                var slotIndex:Int = ss[1].substring(1).toInt()
                                var argcount:Int = ss[2].toInt()
                                var resultcount:Int = ss[3].toInt()
                                    var j:Int = 0
                                for(j in 0..(resultcount - 2))
                                {
                                    if (affectedSlot.equals("%" + j))
                                    {
                                        print("do not remove , in call inst:" + uvmInsstr + "affect slot:" + affectedSlot + "\n")
                                        SetEvalStackTopIndex = -1
                                    }
                                }
                            }
                            else if (ss[0].equals("return"))
                            {
                                SetEvalStackTopIndex = -1
                                AddEvalStackSizeIndex = -1
                            }
                        }
                    }
                }
            }
        }
        var delcount:Int = delIndexes.count()

        for( mi in modifyIndexes)
        {
            proto.codeInstructions[mi] = modifyUvmIns[modifyIndexes.indexOf(mi)]
        }

        //删除delindex对应的指令
        if (delcount > 0)
        {
            for(j in 0..(delcount-1))
            {
                var instr = proto.codeInstructions[delIndexes[j]].toString()
                proto.codeInstructions[delIndexes[j]] = proto.makeEmptyInstruction(";deleted inst;original inst:" + instr)
            }
        }

        //调整location map
        var needLocCount:Int = proto.neededLocationsMap.count()
        if (needLocCount > 0 && delcount > 0)
        {
            val locationMap: MutableMap<Int, String> = mutableMapOf()
            for (loc in proto.neededLocationsMap)
            {
                var newKey:Int = loc.key - getLtCount(delIndexes, loc.key)
                checkLoctionMoveRight(loc.key, newKey, proto)
                locationMap.put(newKey, loc.value)
            }

            proto.neededLocationsMap.clear()
            for (loc in locationMap)
            {
                proto.neededLocationsMap.put(loc.key, loc.value)
            }
        }
        print("reduce codeslines =" + delIndexes.count() + "\n")
        return delcount
    }

    fun ReduceProtoUvmInsts(proto: UvmProto)
    {
        print("begin reduce: proto name = " + proto.name + " totalLines = " + proto.codeInstructions.count() + "\n")
        var r:Int = 0
        var totalReduceLines:Int = 0
        var idx:Int = 0
        do
        {
            println("idx=" + idx + " , reduce proto begin:" + proto.name)
            r = ReduceUvmInstsImp(proto)
            totalReduceLines = totalReduceLines + r
            idx++
        } while (r > 0)
        println("proto name = " + proto.name + " totalReduceLines = " + totalReduceLines + " , now totalLines = " + proto.codeInstructions.count() + "\n")
    }

}
