package gjavac.translater

import com.google.gson.Gson
import gjavac.exceptions.GjavacException
import gjavac.cecil.*
import gjavac.core.*
import gjavac.lib.*
import gjavac.utils.TranslatorUtils
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
            throw GjavacException("must provide only one class with only one non-static main method")
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
        
        val topProto = translateTopJvmType(mainTypes[0],jvmContentBuilder,luaAsmBuilder,utilTypes,contractType_)
        luaAsmBuilder.append(topProto.toUvmAss(true))
    }

    fun translateTopJvmType(topType:ClassDefinition,jvmContentBuilder: StringBuilder,
                         luaAsmBuilder: StringBuilder, utilTypes:List<ClassDefinition>,contractType:ClassDefinition): UvmProto{
        if(topType==null || contractType==null){
            throw GjavacException("topType null or contractType null")
        }
        var topProto: UvmProto? = null
        val proto = UvmProto(TranslatorUtils.makeProtoNameOfTypeConstructor(topType)) //合约内main方法所属的class作为整个合约的mainproto
        proto.internUpvalue("ENV")
        topProto = proto

        topProto.sizeP = 0;
        topProto.numparams = 1;//this table


        var codeMainProto: UvmProto? = null  //合约内的main方法
        var tableSlot = 0;
        var tempslot = utilTypes.size + 1;
        topProto.addInstructionLine("newtable %" + tableSlot + " 0 0", null)
        for (utilType in utilTypes) {
            //utilProto直属于mainProto
            val utilProto = translateJvmType(utilType, jvmContentBuilder, luaAsmBuilder,  topProto)
            //utilProto.parent = mainProto
            // 将utilProto在mainProto里closure化，作为mainProto的一个locvar，之后contractProto里可通过upval方式访问到
            topProto.internConstantValue(utilProto.name)
            var slotIndex = topProto.numparams + topProto.subProtos.size
            topProto.addInstructionLine("closure %" + slotIndex + " " + utilProto.name, null)
            topProto.addInstructionLine("call %" + slotIndex + " " + ( 1) + " " + (2), null)
            //topProto.addInstructionLine("move %" + slotIndex + " %" + tempslot, null)
            val subProtoName = utilProto.name
            if (subProtoName == null) {
                throw GjavacException("null method proto name")
            }
            topProto.locvars.add(UvmLocVar(subProtoName, slotIndex))
            topProto.subProtos.add(utilProto)
        }
        /* top proto*/
        var mainFullName = "";
        var tmp1Slot = topType.methods.size + topProto.subProtos.size + 1;
        for (m in topType.methods) {
            var methodProto = translateJvmMethod(m, jvmContentBuilder, luaAsmBuilder, topProto)
            if (methodProto == null) {
                continue
            }
            // 把各成员函数加入slots
            topProto.internConstantValue(methodProto.name)
            var slotIndex = topProto.numparams +  topProto.subProtos.size
            topProto.addInstructionLine("closure %" + slotIndex + " " + methodProto.name, null)
            topProto.internConstantValue(m.name)
            topProto.addInstructionLine("loadk %" + tmp1Slot + " const \"" + m.name + "\"", null)
            topProto.addInstructionLine(
                    "settable %" + tableSlot + " %" + tmp1Slot + " %" + slotIndex, null)
            val methodProtoName = methodProto.name
            if (methodProtoName == null) {
                throw GjavacException("null method proto name")
            }
            if(m.name =="main"){
                mainFullName = methodProtoName
                codeMainProto = methodProto
            }
            topProto.locvars.add(UvmLocVar(methodProtoName, slotIndex))
            topProto.subProtos.add(methodProto)
        }

        topProto.maxStackSize = tmp1Slot + 4;
        var mainFuncSlot = topProto.numparams + topProto.subProtos.size + 1; // proto.SubProtos.IndexOf(mainProto) + 1;
        topProto.addInstructionLine("loadk %" + (mainFuncSlot+1) + " const \"main\"", null)
        topProto.addInstructionLine("gettable %" + mainFuncSlot + " %0 %" + (mainFuncSlot+1), null)
        topProto.addInstructionLine("move %" + (mainFuncSlot + 1) + " %0", null)
        var returnCount = 1;
        var argsCount = 1;
        topProto.addInstructionLine("call %" + mainFuncSlot + " " + (argsCount + 1) + " " + (returnCount + 1), null)
        if (returnCount > 0) {
            topProto.addInstructionLine("return %" + mainFuncSlot + " " + (returnCount + 1), null)
        }
        topProto.addInstructionLine("return %0 1", null)
        /**/

        var contractProto: UvmProto? = null
        if (contractType != null) {
            contractProto = translateJvmType(contractType, jvmContentBuilder, luaAsmBuilder, codeMainProto)
            codeMainProto?.subProtos?.add(contractProto) //合约class的proto从属于main函数的proto
        }

        topProto.sizeLocVars = topProto.locvars.size
        topProto.sizek = topProto.constantValues.size
        topProto.sizeCode = topProto.codeInstructions.size
        return topProto;

    }

    fun translateJvmType(typeDefinition: ClassDefinition, jvmContentBuilder: StringBuilder,
                         luaAsmBuilder: StringBuilder,  parentProto: UvmProto?): UvmProto {
        val proto = UvmProto(TranslatorUtils.makeProtoNameOfTypeConstructor(typeDefinition))
        proto.parent = parentProto

        // 把类型转换成的proto被做成有一些slot指向成员函数的构造函数，保留slot指向成员函数是为了方便子对象upval访问(不一定需要)
        var tableSlot = 0;
        proto.addInstructionLine("newtable %" + tableSlot + " 0 0", null)

        proto.sizeP = 0;
        proto.numparams = 1; //this

        var tmp1Slot = typeDefinition.methods.size + 1;
        for (m in typeDefinition.methods) {
            var methodProto = translateJvmMethod(m, jvmContentBuilder, luaAsmBuilder, proto)
            if (methodProto == null) {
                continue
            }
            // 把各成员函数加入slots
            proto.internConstantValue(methodProto.name)
            var slotIndex = proto.numparams + proto.subProtos.size
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

        proto.addInstructionLine("return %" + tableSlot + " 2", null) // 构造函数的返回值
        proto.addInstructionLine("return %0 1", null)

        proto.sizeLocVars = proto.locvars.size
        proto.sizek = proto.constantValues.size
        proto.sizeCode = proto.codeInstructions.size
        return proto
    }

    fun jvmVariableNameFromDefinition(varInfo: String): String {
        return varInfo
    }

    fun jvmParamterNameFromDefinition(argInfo: String): String {
        return argInfo
    }

    fun makeJmpToInstruction(proto: UvmProto, i: Instruction, opName: String,
                             toJmpToInst: Instruction, result: MutableList<UvmInstruction>, commentPrefix: String, onlyNeedResultCount: Boolean,needTranslateResult2Boolean:Boolean) {
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
                        "", true,needTranslateResult2Boolean) // 因为可能有嵌套情况，这里只需要获取准确的指令数量不需要准确的指令内容
                proto.inNotAffectMode = oldNotAffectMode;
                var notEmptyUvmInstsCount = (uvmInsts.filter
                {
                    !(it is UvmEmptyInstruction)
                }).size
                toJmpUvmInstsCount += notEmptyUvmInstsCount
            }
            jmpLabel = proto.internNeedLocationLabel(toJmpUvmInstsCount + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), jmpLabel)
            result.add(proto.makeInstructionLine("jmp 1 $$jmpLabel$commentPrefix $opName",
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

    fun makeArithmeticInstructions(proto: UvmProto, uvmOpName: String, i: Instruction, result: MutableList<UvmInstruction>,
                                   commentPrefix: String, convertResultBool2Javaboolean: Boolean) {
        result.add(proto.makeEmptyInstruction(i.toString()))
        proto.internConstantValue(1)

        var arg1SlotIndex = proto.tmp3StackTopSlotIndex + 1; // top-1
        var arg2SlotIndex = proto.tmp3StackTopSlotIndex + 2; // top

        //loadNilInstruction(proto, proto.tmp3StackTopSlotIndex, i, result, commentPrefix)

        popFromEvalStackToSlot(proto, arg2SlotIndex, i, result, commentPrefix)
        popFromEvalStackToSlot(proto, arg1SlotIndex, i, result, commentPrefix)

        // 执行算术操作符，结果存入tmp2
        result.add(proto.makeInstructionLine(uvmOpName + " %" + proto.tmp2StackTopSlotIndex + " %" + arg1SlotIndex + " %" + arg2SlotIndex + commentPrefix, i))

        if (convertResultBool2Javaboolean) {
            convertLuaBool2Javaboolean(proto,proto.tmp2StackTopSlotIndex,i,commentPrefix,result)
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
        // uvm的lt指令: if ((RK(B) <  RK(C)) ~= A) then pc++
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
        jmpLabel1 = proto.internNeedLocationLabel(offsetOfInst1 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), jmpLabel1)
        result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel1 + commentPrefix,
                i))

        var jmpLabel2 = proto.name + "_2_cmp_" + i.offset;
        var offsetOfInst2 = 5; // 如果比较成功，跳转到把1压eval-stack栈的指令
        jmpLabel2 = proto.internNeedLocationLabel(offsetOfInst2 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), jmpLabel2)
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
        jmpLabel3 = proto.internNeedLocationLabel(offsetOfInst3 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), jmpLabel3)
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
            convertInt2LuaBoolean(proto,valueSlot,i,commentPrefix,result)
            //result.add(proto.makeInstructionLine("not %" + valueSlot + " %" + valueSlot + commentPrefix, i))
            //result.add(proto.makeInstructionLine("not %" + valueSlot + " %" + valueSlot + commentPrefix, i))
        }

        // 加载table
        //result.add(proto.makeInstructionLine("gettable %" + tableSlot + " %" + proto.evalStackIndex + " %" + proto.evalStackSizeIndex + commentPrefix, i))

        popFromEvalStackToSlot(proto,tableSlot,i,result,commentPrefix)

        // settable
        result.add(proto.makeInstructionLine(
                "loadk %" + proto.tmp2StackTopSlotIndex + " const \"" + propName + "\"" + commentPrefix, i))
        result.add(proto.makeInstructionLine(
                "settable %" + tableSlot + " %" + proto.tmp2StackTopSlotIndex + " %" + valueSlot + commentPrefix, i))
    }

    /**
     * 读取eval stack top(table)值，执行table读属性操作,读取结果放入eval stack
     */
    fun makeGetTablePropInstructions(proto: UvmProto, propName: String, i: Instruction, result: MutableList<UvmInstruction>,
                                     commentPrefix: String, needConvtToJavaboolean: Boolean) {
        proto.internConstantValue(propName)
        var tableSlot = proto.tmp2StackTopSlotIndex + 1
        var valueSlot = proto.tmp2StackTopSlotIndex + 2

        // 加载table
        popFromEvalStackToSlot(proto,tableSlot,i,result,commentPrefix)

        // gettable
        result.add(proto.makeInstructionLine(
                "loadk %" + proto.tmp2StackTopSlotIndex + " const \"" + propName + "\"" + commentPrefix, i))
        result.add(proto.makeInstructionLine(
                "gettable %" + valueSlot + " %" + tableSlot + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))

        //
        if (needConvtToJavaboolean) {
            convertLuaBool2Javaboolean(proto,valueSlot,i,commentPrefix,result)
        }
        proto.internConstantValue(1)
        // value放回eval stack
        pushIntoEvalStackTopSlot(proto,valueSlot,i,result,commentPrefix)

    }

    /**
     * 单元操作符转换成指令
     */
    fun makeSingleArithmeticInstructions(proto: UvmProto, uvmOpName: String, i: Instruction, result: MutableList<UvmInstruction>,
                                         commentPrefix: String, convertResultBool2Javaboolean: Boolean) {
        result.add(proto.makeEmptyInstruction(i.toString()))
        proto.internConstantValue(1)
        val arg1SlotIndex = proto.tmp3StackTopSlotIndex + 1 // top

        popFromEvalStackToSlot(proto,arg1SlotIndex,i,result,commentPrefix)

        // 执行算术操作符，结果存入tmp2
        result.add(proto.makeInstructionLine(uvmOpName + " %" + proto.tmp2StackTopSlotIndex + " %" + arg1SlotIndex + commentPrefix, i))

        if (convertResultBool2Javaboolean) {
            // 判断是否是0，如果是就是false，需要使用jmp
            convertLuaBool2Javaboolean(proto,proto.tmp2StackTopSlotIndex,i,commentPrefix,result)
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
            val literalValueInUvms = TranslatorUtils.escape(value as String)
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

    //convert false,true to 0,1 (java bool is int 0,1)
    fun convertLuaBool2Javaboolean(proto: UvmProto, slotresult:Int, i: Instruction,commentPrefix: String,result:MutableList<UvmInstruction>){
        proto.internConstantValue(0)
        proto.internConstantValue(false)

        val slotint = proto.tmpMaxStackTopSlotIndex - 1
        val slotTemp = proto.tmpMaxStackTopSlotIndex
        //java合约API如果返回boolean类型数据  需要手动用Not not转换

        result.add(proto.makeInstructionLine("loadk %" + slotint + " const 0" + commentPrefix, i))
        result.add(proto.makeInstructionLine("loadk %" + slotTemp + " const false" + commentPrefix, i))
        // if slotresult==false then pc++
        result.add(proto.makeInstructionLine("eq 0 %" + slotresult + " %" + slotTemp + commentPrefix, i))

        var labelWhenTrue = proto.name + "_1_" + i.offset;
        var labelWhenFalse = proto.name + "_0_" + i.offset;
        labelWhenTrue = proto.internNeedLocationLabel(
                2 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), labelWhenTrue)

        result.add(proto.makeInstructionLine("jmp 1 $" + labelWhenTrue + commentPrefix, i))
        labelWhenFalse =
                proto.internNeedLocationLabel(
                        2 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), labelWhenFalse)
        result.add(proto.makeInstructionLine("jmp 1 $" + labelWhenFalse + commentPrefix, i))

        result.add(proto.makeInstructionLine("loadk %" + slotint + " const 1" + commentPrefix, i))
        result.add(proto.makeInstructionLine("move %" + slotresult + " %" + slotint + commentPrefix, i))
    }

    //convert 0,1 to false,true (java bool is int 0,1)
    fun convertInt2LuaBoolean(proto: UvmProto, slotresult:Int, i: Instruction,commentPrefix: String,result:MutableList<UvmInstruction>){
        proto.internConstantValue(0)
        proto.internConstantValue(false)
        proto.internConstantValue(true)

        val slotLuaBool = proto.tmpMaxStackTopSlotIndex - 1
        val slotTemp = proto.tmpMaxStackTopSlotIndex
        //java合约API如果返回boolean类型数据  需要手动用Not not转换

        result.add(proto.makeInstructionLine("loadk %" + slotLuaBool + " const false" + commentPrefix, i))
        result.add(proto.makeInstructionLine("loadk %" + slotTemp + " const 0" + commentPrefix, i))
        // if slotresult==false then pc++  // 判断是否是0，如果是就是false，需要使用jmp
        result.add(proto.makeInstructionLine("eq 0 %" + slotresult + " %" + slotTemp + commentPrefix, i))

        var labelWhenTrue = proto.name + "_true_" + i.offset;
        var labelWhenFalse = proto.name + "_false_" + i.offset;
        labelWhenTrue = proto.internNeedLocationLabel(
                2 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), labelWhenTrue)

        result.add(proto.makeInstructionLine("jmp 1 $" + labelWhenTrue + commentPrefix, i))
        labelWhenFalse =
                proto.internNeedLocationLabel(
                        2 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), labelWhenFalse)
        result.add(proto.makeInstructionLine("jmp 1 $" + labelWhenFalse + commentPrefix, i))

        result.add(proto.makeInstructionLine("loadk %" + slotLuaBool + " const true" + commentPrefix, i))
        result.add(proto.makeInstructionLine("move %" + slotresult + " %" + slotLuaBool + commentPrefix, i))
    }

    fun translateJvmInstruction(proto: UvmProto, i: Instruction, commentPrefix: String, onlyNeedResultCount: Boolean, needTranslateResult2Boolean :Boolean ): MutableList<UvmInstruction> {
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
            Opcodes.POP -> {  //?? 栈顶数值出栈 (该栈顶数值不能是long或double型)
                popFromEvalStackToSlot(proto,proto.tmpMaxStackTopSlotIndex,i,result,commentPrefix)
            }
//            Opcodes.POP2 -> { //?? 栈顶的一个（如果是long、double型的)或两个（其它类型的）数值出栈
//                popFromEvalStackToSlot(proto,proto.tmpMaxStackTopSlotIndex,i,result,commentPrefix)
//            }
            Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 -> {
                // push integer to operand stack
                val value = i.opCode - Opcodes.ICONST_0
                makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, value, commentPrefix)
                pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix + " ldc " + value)
            }
            Opcodes.LCONST_0, Opcodes.LCONST_1-> {
                // push integer to operand stack
                val value = i.opCode - Opcodes.LCONST_0
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

                //add start pc
                proto.locvars[slotIndex].startPc = proto.notEmptyCodeInstructions().size + result.size
                if(proto.locvars[slotIndex].slotIndex!=slotIndex){
                    throw GjavacException("loc slotidx wrong")
                }
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
                    if (needTranslateResult2Boolean)
                    {
                        convertInt2LuaBoolean(proto, proto.tmp1StackTopSlotIndex, i, commentPrefix, result);
                    }

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
            Opcodes.LAND,Opcodes.IAND -> {
                makeArithmeticInstructions(proto, "band", i, result, commentPrefix, false)
            }
            Opcodes.LOR,Opcodes.IOR -> {
                makeArithmeticInstructions(proto, "bor", i, result, commentPrefix, false)
            }
            Opcodes.LXOR,Opcodes.IXOR -> {
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
                jmpLabel1 = proto.internNeedLocationLabel(offsetOfLabel1 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), jmpLabel1)
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel1 + commentPrefix,
                        i))

                var jmpLabel2 = proto.name + "_2_cmp_" + i.offset;
                var offsetOfInst2 = 6; // 跳转到把1压eval-stack栈的指令
                jmpLabel2 = proto.internNeedLocationLabel(offsetOfInst2 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), jmpLabel2)
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel2 + commentPrefix,
                        i))

                // 区分等于还是小于
                // if ((RK(B) eq  RK(C)) ~= A) then pc++
                result.add(proto.makeInstructionLine("eq 1 %" + proto.tmp1StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
                var jmpLabel3 = proto.name + "_3_cmp_" + i.offset
                val offsetOfLabel3 = 2 // 跳转到把0压operand stack栈
                jmpLabel3 = proto.internNeedLocationLabel(offsetOfLabel3 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), jmpLabel3)
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel3 + commentPrefix,
                        i))

                var jmpLabel4 = proto.name + "_4_cmp_" + i.offset;
                var offsetOfInst4 = 5; // 如果比较成功，跳转到把-1压eval-stack栈的指令
                jmpLabel4 = proto.internNeedLocationLabel(offsetOfInst4 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), jmpLabel4)
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel4 + commentPrefix, i))

                var jmpLabel5 = proto.name + "_5_cmp_" + i.offset
                var offsetOfInst5 = 5; // 跳转到本jvm指令的end
                jmpLabel5 = proto.internNeedLocationLabel(offsetOfInst5 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), jmpLabel5)

                result.add(proto.makeInstructionLine("loadk %" + proto.tmp3StackTopSlotIndex + " const 0" + commentPrefix, i))
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel5 + commentPrefix, i))

                result.add(proto.makeInstructionLine("loadk %" + proto.tmp3StackTopSlotIndex + " const " + (if (op == "gt") 1 else -1) + commentPrefix, i))
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel5 + commentPrefix, i))

                result.add(proto.makeInstructionLine("loadk %" + proto.tmp3StackTopSlotIndex + " const " + (if (op == "gt") -1 else 1) + commentPrefix, i))

                pushIntoEvalStackTopSlot(proto,proto.tmp3StackTopSlotIndex,i,result,commentPrefix)
            }
            Opcodes.LCMP -> {
                // compare double
                // operand stack: ..., value1, value2 -> operand stack: ..., result
                // when <t>cmpg, if value1 > value2, then result = 1; else if value 1 == value2, then result = 0; else result = -1
                // TODO
                val op: String

                popFromEvalStackToSlot(proto,proto.tmp2StackTopSlotIndex,i,result,commentPrefix)//??
                popFromEvalStackToSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)

                proto.internConstantValue(0)
                proto.internConstantValue(1)
                proto.internConstantValue(-1)

                // 注释以op="lt"为例
                // if ((RK(B) <  RK(C)) ~= A) then pc++
                result.add(proto.makeInstructionLine("lt 0 %" + proto.tmp1StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
                var jmpLabel1 = proto.name + "_1_cmp_" + i.offset
                val offsetOfLabel1 = 2 // 跳转到区分 = 还是 > 的判断
                jmpLabel1 = proto.internNeedLocationLabel(offsetOfLabel1 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), jmpLabel1)
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel1 + commentPrefix,
                        i))

                var jmpLabel2 = proto.name + "_2_cmp_" + i.offset;
                var offsetOfInst2 = 6; // 跳转到把-1压eval-stack栈的指令
                jmpLabel2 = proto.internNeedLocationLabel(offsetOfInst2 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), jmpLabel2)
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel2 + commentPrefix,
                        i))

                // 区分等于还是大于
                // if ((RK(B) eq  RK(C)) ~= A) then pc++
                result.add(proto.makeInstructionLine("eq 1 %" + proto.tmp1StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
                var jmpLabel3 = proto.name + "_3_cmp_" + i.offset
                val offsetOfLabel3 = 2 // 跳转到把0压operand stack栈
                jmpLabel3 = proto.internNeedLocationLabel(offsetOfLabel3 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), jmpLabel3)
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel3 + commentPrefix,
                        i))

                var jmpLabel4 = proto.name + "_4_cmp_" + i.offset;
                var offsetOfInst4 = 5; // 如果比较成功，跳转到把1压eval-stack栈的指令
                jmpLabel4 = proto.internNeedLocationLabel(offsetOfInst4 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), jmpLabel4)
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel4 + commentPrefix, i))

                var jmpLabel5 = proto.name + "_5_cmp_" + i.offset
                var offsetOfInst5 = 5; // 跳转到本jvm指令的end
                jmpLabel5 = proto.internNeedLocationLabel(offsetOfInst5 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), jmpLabel5)

                result.add(proto.makeInstructionLine("loadk %" + proto.tmp3StackTopSlotIndex + " const 0" + commentPrefix, i))
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel5 + commentPrefix, i))

                result.add(proto.makeInstructionLine("loadk %" + proto.tmp3StackTopSlotIndex + " const -1" + commentPrefix, i))
                result.add(proto.makeInstructionLine("jmp 1 $" + jmpLabel5 + commentPrefix, i))

                result.add(proto.makeInstructionLine("loadk %" + proto.tmp3StackTopSlotIndex + " const 1" + commentPrefix, i))

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
                        && this.contractType != null) /*|| (TranslatorUtils.isComponentClass(Class.forName((operand as String).replace("/", "."))))*/)) {
                    val protoName = TranslatorUtils.makeProtoNameOfTypeConstructorByTypeName(operand as String) // 构造函数的名字
                    result.add(proto.makeInstructionLine(
                            "closure %" + proto.tmp2StackTopSlotIndex + " " + protoName, i))
                    result.add(proto.makeInstructionLine(
                            "call %" + proto.tmp2StackTopSlotIndex + " 1 2", i))
                    // 返回值(新对象处于tmp2 slot)
                }
                else if(operand != null && operand.javaClass == String::class.java &&(TranslatorUtils.isComponentClass(Class.forName((operand as String).replace("/", "."))))){
                    //result.add(proto.makeInstructionLine("newtable %" + proto.tmp2StackTopSlotIndex + " 0 0" + commentPrefix, i))
                    //new 工具类实例 ， 不需要new ,从upvalue取就可以， 顶层proto的局部变量（table）
                    val calledTypeName = (operand as String).replace("/", ".")
                    var protoName = TranslatorUtils.makeProtoNameOfTypeConstructorByTypeName(calledTypeName)
                    var funcUpvalIndex = proto.internUpvalue(protoName)
                    proto.internConstantValue(protoName)
                    //makeLoadConstInst(proto, i, result, proto.tmpMaxStackTopSlotIndex, targetFuncName, commentPrefix)
                    //result.add(proto.makeInstructionLine(
                      //      "gettabup %" + proto.tmp2StackTopSlotIndex + " @" + funcUpvalIndex + " %"+proto.tmpMaxStackTopSlotIndex + commentPrefix, i))
                    result.add(proto.makeInstructionLine("getupval %" + proto.tmp2StackTopSlotIndex + " @" + funcUpvalIndex + commentPrefix, i))
                } else{
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
//            Opcodes.GETSTATIC -> {
                //add support UvmBoolean
//                val operand = i.opArgs[0] as FieldInfo
//                if(operand.owner.endsWith("UvmBoolean")){
//                    var boolvalue = operand.name
//                    if(boolvalue == "uvm_true"){
//                        makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, true, commentPrefix)
//                    }
//                    else if(boolvalue == "uvm_false"){
//                        makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, false, commentPrefix)
//                    }
//                    else{
//                        throw GjavacException("no UvmBoolean："+boolvalue)
//                    }
//                    pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix + i.instLine)
//                    return result
//                }
//
//                result.add(proto.makeInstructionLine("newtable %" + proto.tmp1StackTopSlotIndex + " 0 0" + commentPrefix, i))
//                pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix + i.instLine)
//                return result
//            }
            Opcodes.GETFIELD -> {
                val fieldInfo = i.opArgs[0] as FieldInfo
                val fieldName = fieldInfo.name
                val needConvToBool = fieldInfo.desc == "Z"
                proto.internConstantValue(fieldName)
                makeGetTablePropInstructions(proto, fieldName, i, result, commentPrefix, needConvToBool)
            }
            Opcodes.PUTFIELD -> {
                val fieldInfo = i.opArgs[0] as FieldInfo
                val fieldName = fieldInfo.name
                val needConvToBool = fieldInfo.desc == "Z"
                proto.internConstantValue(fieldName)
                makeSetTablePropInstructions(proto, fieldName, i, result, commentPrefix, needConvToBool)
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
                var resultBool2IntValue = false;
                //externalMethod指的是非本合约的方法， 即corelib func, other contract function
                //java里面的boolean其实就是int的0、1,当调用externalMethod时 bool参数需要将int转为lua的bool，返回结果需要把lua的bool转为int的0,1
                var isExternalMethod = true;
                var paramsCount = methodParams.size
                var hasThis = i.opCode != Opcodes.INVOKESTATIC
                val hasReturn = methodInfo.methodReturnType != null && !methodInfo.methodReturnType?.desc.equals("V")
                if(hasReturn && methodInfo.methodReturnType?.desc.equals("Z")){ // Z表示boolean   如果返回Boolean则不是Z
                    resultBool2IntValue = true;
                }
                var needPopFirstArg = false // 一些函数，比如import module的函数，因为用object成员函数模拟，而在uvm中是table中属性的函数，所以.net中多传了个this对象
                var returnCount = if (hasReturn) 1 else 0
                var isUserDefineFunc = false; // 如果是本类中要生成uvm字节码的方法，这里标记为true
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
                    if (methodName == "checkParameterIsNotNull" || methodName == "checkExpressionValueIsNotNull") {
                        //makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, 1, commentPrefix)
                        //pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix + " checkParameterIsNotNull")
                        popFromEvalStackToSlot(proto,proto.tmpMaxStackTopSlotIndex,i,result,commentPrefix)
                        popFromEvalStackToSlot(proto,proto.tmpMaxStackTopSlotIndex,i,result,commentPrefix)
                    } else if (methodName == "areEqual") {  //fix   compare  eq compare

//                        popFromEvalStackToSlot(proto,proto.tmpMaxStackTopSlotIndex,i,result,commentPrefix)
//                        popFromEvalStackToSlot(proto,proto.tmpMaxStackTopSlotIndex,i,result,commentPrefix)
//                        makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, 1, commentPrefix)//true改为1 java里面boolean其实是Int，之后对boolean数据比较可能会使用ixor....
//                        pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)

                        proto.internConstantValue(true)
                        proto.internConstantValue(false)
                        result.add(proto.makeInstructionLine("loadk %" + proto.tmp1StackTopSlotIndex + " const 0" + commentPrefix, i))

                        var arg1SlotIndex = proto.tmp3StackTopSlotIndex + 1; // top-1
                        var arg2SlotIndex = proto.tmp3StackTopSlotIndex + 2; // top

                        popFromEvalStackToSlot(proto, arg2SlotIndex, i, result, commentPrefix)
                        popFromEvalStackToSlot(proto, arg1SlotIndex, i, result, commentPrefix)

                        result.add(proto.makeInstructionLine("eq 1 %" + arg1SlotIndex + " %" + arg2SlotIndex + commentPrefix, i))

                        var labelWhenTrue = proto.name + "_true_" + i.offset;
                        var labelWhenFalse = proto.name + "_false_" + i.offset;
                        labelWhenTrue = proto.internNeedLocationLabel(
                                2 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), labelWhenTrue)

                        result.add(proto.makeInstructionLine("jmp 1 $" + labelWhenTrue + commentPrefix, i))

                        labelWhenFalse =
                                proto.internNeedLocationLabel(
                                        2 + proto.notEmptyCodeInstructions().size + notEmptyUvmInstructionsCountInList(result), labelWhenFalse)
                        result.add(proto.makeInstructionLine("jmp 1 $" + labelWhenFalse + commentPrefix, i))

                        result.add(proto.makeInstructionLine("loadk %" + proto.tmp1StackTopSlotIndex + " const 1" + commentPrefix, i))

                        pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)

                    }
                    else if (methodName == "throwNpe") {  //fix   compare  eq compare
                        makeLoadConstInst(proto, i, result, proto.tmp2StackTopSlotIndex, "exception", commentPrefix)
                        val envSlot = proto.internUpvalue("ENV")
                        proto.internConstantValue("error")
                        result.add(proto.makeInstructionLine("gettabup %" + proto.tmp1StackTopSlotIndex + " @" + envSlot + " const \"error\"" + commentPrefix, i))
                        result.add(proto.makeInstructionLine("call %" + proto.tmp1StackTopSlotIndex + " 2 1" + commentPrefix, i))
                    }else {
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
                        || calledTypeName == java.lang.Number::class.java.canonicalName
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
                    } else if (methodName == "length") {
                        targetFuncName = "len";
                        useOpcode = true;
                        hasThis = true;
                    } else {
                        throw GjavacException("not supported method " + calledTypeName + "::" + methodName)
                    }
                    // TODO: 其他字符串特殊函数
                } else if (calledTypeName.equals(proto.method?.signature?.classDef?.name?.replace('/', '.'))) {
                    // 调用本类型的方法
                    isExternalMethod = false
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
                            makeArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, false)//modify
                            return result
                        }
                        "or" -> {
                            targetFuncName = "bor"
                            useOpcode = true
                            hasThis = false
                            makeArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, false)
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
                            makeSingleArithmeticInstructions(proto, targetFuncName, i, result, commentPrefix, false)
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
                    //isUserDefinedInTableFunc = true
                    needPopFirstArg = true
                    hasThis = false
                } else if (calledTypeName == UvmMathModule::class.java.canonicalName) {
                    // math module's function
                    targetFuncName = methodName
                    isUserDefineFunc = true
                    //isUserDefinedInTableFunc = true
                    needPopFirstArg = true
                    hasThis = false
                }else if (calledTypeName == UvmSafeMathModule::class.java.canonicalName) {  //add safemath
                    // math module's function
                    targetFuncName = methodName
                    isUserDefineFunc = true
                    //isUserDefinedInTableFunc = true
                    needPopFirstArg = true
                    hasThis = false
                } else if (calledTypeName == UvmTableModule::class.java.canonicalName) {
                    // table module's function
                    targetFuncName = UvmTableModule.libContent[methodName] ?: methodName
                    isUserDefineFunc = true
                    //isUserDefinedInTableFunc = true
                    needPopFirstArg = true
                    hasThis = false
                } else if (calledTypeName == UvmJsonModule::class.java.canonicalName) {
                    // json module's function
                    targetFuncName = methodName
                    isUserDefineFunc = true
                    //isUserDefinedInTableFunc = true
                    needPopFirstArg = true
                    hasThis = false
                } else if (calledTypeName == UvmTimeModule::class.java.canonicalName) {
                    // time module's function
                    targetFuncName = methodName
                    isUserDefineFunc = true
                    //isUserDefinedInTableFunc = true
                    needPopFirstArg = true
                    hasThis = false
                }
                else if(TranslatorUtils.isComponentClass(Class.forName(calledTypeName))&& targetFuncName.length < 1){ //调用工具类
                    isExternalMethod = false
                    var protoMethodClassName = proto.method?.signature?.classDef?.name?.replace('/', '.')
                    if(calledTypeName.equals(protoMethodClassName)){  //工具类function调用本工具类function,通过从自己table中获取
                        isUserDefineFunc = true
                        targetFuncName = methodName
                        isUserDefinedInTableFunc = false  //调用工具类
                        needPopFirstArg = false
                    }
                    else if(protoMethodClassName.equals(this.contractType?.name)){ //合约function调用工具类,通过gettabup来取
                        isUserDefineFunc = true
                        targetFuncName = methodName
                        isUserDefinedInTableFunc = true  //调用工具类
                        needPopFirstArg = false
                    }
                    else{ //工具类调用其他工具类 ....通过gettabup来取
                        isUserDefineFunc = true
                        targetFuncName = methodName
                        isUserDefinedInTableFunc = true  //调用工具类
                        needPopFirstArg = false
                    }
                }


                var preaddParamsCount = 0 // 前置额外增加的参数，比如this
                if (hasThis) {
                    paramsCount++
                    preaddParamsCount = 1
                }

                if(!TranslatorUtils.isComponentClass(Class.forName(calledTypeName))) {
                    // 如果methodName是setXXXX或者getXXXX，则是java的属性操作，转换成uvm的table属性读写操作
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
                    }
                }

                if (targetFuncName.isEmpty() && calledType.isInterface()) {
                    //通过interface 调用其他合约方法
                    isUserDefineFunc = true
                    targetFuncName = methodName
                    isUserDefinedInTableFunc = false
                }

                //java boolean 转为Boolean
                if (targetFuncName.isEmpty() && methodName.equals("valueOf") && methodInfo.fullName().equals("(boolean)java.lang.Boolean") ) {
                    var slotIndex = proto.tmp2StackTopSlotIndex
                    popFromEvalStackToSlot(proto,slotIndex,i,result,commentPrefix)
                    // 对于布尔类型，因为.net中布尔类型参数加载的时候用的ldc.i，加载的是整数，所以这里要进行类型转换成bool类型，使用 not not a来转换
                    //result.add(proto.makeInstructionLine("not %" + slotIndex + " %" + slotIndex + commentPrefix, i))
                    //result.add(proto.makeInstructionLine("not %" + slotIndex + " %" + slotIndex + commentPrefix, i))
                    convertInt2LuaBoolean(proto,slotIndex,i,commentPrefix,result)

                    pushIntoEvalStackTopSlot(proto,slotIndex,i,result,commentPrefix)
                    return result
                }

                //java Boolean 转为 boolean
                if (targetFuncName.isEmpty() && methodName.equals("booleanValue") && methodInfo.fullName().equals("()boolean") ) {
                    var slotIndex = proto.tmp2StackTopSlotIndex
                    popFromEvalStackToSlot(proto,slotIndex,i,result,commentPrefix)
                    convertLuaBool2Javaboolean(proto,slotIndex,i,commentPrefix,result)
                    pushIntoEvalStackTopSlot(proto,slotIndex,i,result,commentPrefix)
                    return result
                }


                // TODO: 更多内置库的函数支持
                if (targetFuncName.isEmpty()) {
                    throw GjavacException("not support method $methodName ${methodInfo.fullName()} now")
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
                        throw GjavacException("不支持将超过" + (proto.tmpMaxStackTopSlotIndex - proto.tmp2StackTopSlotIndex) + "个参数的C#函数编译到uvm字节码")
                    }
                    var methodParamIndex = paramsCount - c - 1 - preaddParamsCount; // 此参数是参数在方法的.net参数(不包含this)中的索引
                    var needConvtToBool = false
                    if (methodParamIndex < methodParams.size && methodParamIndex >= 0) {
                        var paramType = methodParams[methodParamIndex]
                        if ((paramType.fullName() == "System.Boolean") && isExternalMethod ) {
                            needConvtToBool = true;
                        }
                    }

                    popFromEvalStackToSlot(proto,slotIndex,i,result,commentPrefix)
                    // 对于布尔类型，因为.net中布尔类型参数加载的时候用的ldc.i，加载的是整数，所以这里要进行类型转换成bool类型，使用 not not a来转换
                    if (needConvtToBool) {
                        convertInt2LuaBoolean(proto,slotIndex,i,commentPrefix,result)
                        //result.add(proto.makeInstructionLine("not %" + slotIndex + " %" + slotIndex + commentPrefix, i))
                        //result.add(proto.makeInstructionLine("not %" + slotIndex + " %" + slotIndex + commentPrefix, i))
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
                    if (isUserDefinedInTableFunc) {
                         //访问工具类的成员方法，访问的是父proto的局部变量，所以是访问upvalue
//                        var protoName = TranslatorUtils.makeProtoName(calledMethod)
//                        var funcUpvalIndex = proto.internUpvalue(protoName)
//                        proto.internConstantValue(protoName)
//                        result.add(proto.makeInstructionLine(
//                                "getupval %" + proto.tmp2StackTopSlotIndex + " @" + funcUpvalIndex + commentPrefix, i))
                        var protoName = TranslatorUtils.makeProtoNameOfTypeConstructorByTypeName(calledTypeName)
                        var funcUpvalIndex = proto.internUpvalue(protoName)
                        proto.internConstantValue(protoName)
                        makeLoadConstInst(proto, i, result, proto.tmpMaxStackTopSlotIndex, targetFuncName, commentPrefix)
                        result.add(proto.makeInstructionLine(
                                "gettabup %" + proto.tmp2StackTopSlotIndex + " @" + funcUpvalIndex + " %"+proto.tmpMaxStackTopSlotIndex + commentPrefix, i))
                    } else {
                        // 访问本类的成员方法，或者本地引用模块（如math模块）的方法，需要gettable取出函数
                        var protoName = TranslatorUtils.makeProtoName(calledMethod)
                        //var funcUpvalIndex = proto.internUpvalue(protoName)   //从table里面取
                        proto.internConstantValue(protoName)
                        if (targetFuncName == null || targetFuncName.length < 1) {
                            targetFuncName = calledMethod.name;
                        }
                        makeLoadConstInst(proto, i, result, proto.tmp2StackTopSlotIndex, targetFuncName, commentPrefix)
                        if (needPopFirstArg) {
                            // object模拟uvm module，module信息在calledMethod的this参数中
                            // 这时候eval stack应该是[this], argStart开始的数据应该是this, ...
                            // result.addRange(DebugEvalStack(proto))
                            popFromEvalStackToSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)

                            result.add(proto.makeInstructionLine(
                                    "gettable %" + proto.tmp2StackTopSlotIndex + " %" + proto.tmp1StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
                        } else {
                            result.add(proto.makeInstructionLine(
                                    "gettable %" + proto.tmp2StackTopSlotIndex + " %" + argStartSlot + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
                        }
                    }
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
                    if(resultBool2IntValue){
                        convertLuaBool2Javaboolean(proto,proto.tmp3StackTopSlotIndex,i,commentPrefix,result)
                        }

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
                makeJmpToInstruction(proto, i, "goto", toJmpToInst, result, commentPrefix, onlyNeedResultCount,needTranslateResult2Boolean)
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
                    "eq","ne" -> { //eq   ne    IFEQ IFNE  比较boolean
                        makeLoadConstInst(proto, i, result, proto.tmp2StackTopSlotIndex, 0, commentPrefix)//mdify
                    }
                    else -> {  //le lt ge gt  比较数字
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
                makeJmpToInstruction(proto, i, i.opCodeName(), toJmpToInst, result, commentPrefix, onlyNeedResultCount,needTranslateResult2Boolean)
            }
            Opcodes.INSTANCEOF -> {
                makeLoadConstInst(proto, i, result, proto.tmp1StackTopSlotIndex, true, commentPrefix)
                //makeSetTopOfEvalStackInst(proto, i, result, proto.tmp1StackTopSlotIndex, commentPrefix)
                popFromEvalStackToSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)
                pushIntoEvalStackTopSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix )
            }
            Opcodes.IREM,Opcodes.LREM -> {
                popFromEvalStackToSlot(proto,proto.tmp2StackTopSlotIndex,i,result,commentPrefix)
                popFromEvalStackToSlot(proto,proto.tmp1StackTopSlotIndex,i,result,commentPrefix)
                result.add(proto.makeInstructionLine("mod %" + proto.tmp3StackTopSlotIndex + " %"+ proto.tmp1StackTopSlotIndex + " %" + proto.tmp2StackTopSlotIndex + commentPrefix, i))
                pushIntoEvalStackTopSlot(proto,proto.tmp3StackTopSlotIndex,i,result,commentPrefix )

            }
        // TODO: other opcodes
            else -> {
//                println("not supported jvm opcde ${i.opCodeName()}")
                throw GjavacException("not supported jvm opcode " + i.opCodeName() + " to compile to uvm instruction")
            }
        }
        return result
    }

    fun notEmptyUvmInstructionsCountInList(items: MutableList<UvmInstruction>): Int {
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

        //if(hasReturn && methodInfo.methodReturnType?.desc.equals("Z")){
        //var needConvtToBool = returnCount == 1 && (method.signature?.returnType?.isBoolean() ?: false)
        //method.signature?.returnType
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
        // 在uvm的proto开头创建一个table局部变量，模拟evaluation stack
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
        var needTranslateResult2Boolean = false;
        if(method.signature?.returnType?.signature == "Z") //return boolean
        {
            needTranslateResult2Boolean = true;
        }

        var tempi = 0
        var params = proto.sizeP
        if (!method.isStatic) {
            proto.locvars.add(UvmLocVar("this", tempi))
            tempi++
            params--

        }
        if(params>0){
            for(idx in 0..(params-1)){
                proto.locvars.add(UvmLocVar("Vparam_"+idx, tempi))
                tempi++
            }
        }
        var locsnum = method.maxLocals - proto.sizeP
        if(locsnum>0){
            for(idx in 0..(locsnum-1)){
                proto.locvars.add(UvmLocVar("Vloc_"+idx, tempi))
                tempi++
            }
        }


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
            // 关于java的evaluation stack在uvm字节码虚拟机中的实现方式
            // 维护一个evaluation stack的局部变量,，每个proto入口处清空它
            var uvmInstructions = translateJvmInstruction(proto, i, commentPrefix, false,needTranslateResult2Boolean)
            for (uvmInst in uvmInstructions) {
                proto.addInstruction(uvmInst)
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
        proto.sizeCode = proto.codeInstructions.size
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
                            //print("do not remove, locactionmap needed \n")
                        }
                        else
                        {
                            delIndexes.add(AddEvalStackSizeIndex)
                            delIndexes.add(gIndex)
                            //print("find add-sub evalsize group, index " + AddEvalStackSizeIndex + "," + gIndex + "\n")
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
                            //print("do not remove, locactionmap needed \n")
                        }
                        else
                        {
                            delIndexes.add(SetEvalStackTopIndex)
                            modifyIndexes.add(gIndex)
                            modifyUvmIns.add(inst)
                            //print("find set-get evaltop group, index " + SetEvalStackTopIndex + "," + gIndex + "\n")
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
                                //print("do not remove , affect slot:" + affectedSlot + "\n")
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
                                        //print("do not remove , in call inst:" + uvmInsstr + "affect slot:" + affectedSlot + "\n")
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

        //调整localval startpc
        for (locval in proto.locvars)
        {
            if (locval.startPc > 0)
            {
                var newPc = locval.startPc - getLtCount(delIndexes, locval.startPc);
                locval.startPc = newPc;
            }
        }
        //print("reduce codeslines =" + delIndexes.count() + "\n")
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
            //println("idx=" + idx + " , reduce proto begin:" + proto.name)
            r = ReduceUvmInstsImp(proto)
            totalReduceLines = totalReduceLines + r
            idx++
        } while (r > 0)
        println("proto name = " + proto.name + " totalReduceLines = " + totalReduceLines + " , now totalLines = " + proto.codeInstructions.count() + "\n")
    }

}
