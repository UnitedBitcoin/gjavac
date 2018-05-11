package gjavac.utils

import gjavac.cecil.ClassDefinition
import gjavac.cecil.MethodDefinition
import gjavac.cecil.MethodInfo
import gjavac.core.UvmTypeInfoEnum
import gjavac.core.StorageValueTypes
import gjavac.exceptions.GjavacException
import gjavac.lib.Component
import gjavac.lib.UvmContract
import gjavac.lib.IUvmEventEmitter
import gjavac.lib.Offline
import org.apache.commons.lang3.StringEscapeUtils
import kotlin.reflect.jvm.jvmName

class TranslatorUtils {
    companion object {
        fun isMainClass(typeDefinition: ClassDefinition): Boolean {
            if (typeDefinition.methods.size < 1) {
                return false
            }
            for (m in typeDefinition.methods) {
                if (m.name == "main" && !m.isStatic) {
                    return true
                }
            }
            return false
        }

        fun isComponentClass(typeDefinition: ClassDefinition): Boolean {
            return typeDefinition.annotations.firstOrNull { Component::class.java.isAssignableFrom(it.typeClass()) } != null
        }

        fun isComponentClass(type: Class<*>): Boolean {
            return type.annotations.firstOrNull { Component::class.java.isAssignableFrom(it.javaClass) } != null
        }

        fun isEventEmitterType(typeDefinition: ClassDefinition): Boolean {
            var interfaces = typeDefinition.interfaces
            for (item in interfaces) {
                if (item == IUvmEventEmitter::class.jvmName) {
                    return true;
                }
            }
            return false;
        }

        fun isContractType(typeDefinition: ClassDefinition): Boolean {
            val uvmContractClassName = UvmContract::class.jvmName
            if (uvmContractClassName == null)
                throw GjavacException("can't find UvmContract class")
            val typeDefSuperClassName = typeDefinition.superClassName
            return typeDefSuperClassName?.startsWith(uvmContractClassName) ?: false
        }

        fun getEventNameFromEmitterMethodName(method: MethodDefinition): String? {
            val methodName = method.name;
            if (methodName != null && methodName.startsWith("emit") && methodName.length > "emit".length) {
                // 方法名称符合 EmitXXXX
                if (!method.isStatic) {
                    throw GjavacException("emit事件的方法必须是static的");
                }
                if (method.signature?.paramTypes?.size != 1 || method.signature?.paramTypes?.get(0)?.fullName() != String::class.jvmName) {
                    throw GjavacException("emit事件的方法必须是有且只有一个字符串类型的参数");
                }
                var eventName = methodName.substring("emit".length);
                return eventName;
            } else {
                return null;
            }
        }

        fun getEmitEventNamesFromEventEmitterType(typeDefinition: ClassDefinition): MutableList<String> {
            val result: MutableList<String> = mutableListOf()
            for (methodDefinition in typeDefinition.methods) {
                var methodName = methodDefinition.name;
                var eventName = getEventNameFromEmitterMethodName(methodDefinition);
                if (eventName != null) {
                    result.add(eventName);
                }
            }
            return result;
        }

        fun isContructor(method: MethodDefinition): Boolean {
            return method.name == "<init>" // TODO
        }

        fun isContractApiMethod(method: MethodDefinition): Boolean {
            if (isContructor(method)) {
                return false // 构造函数不算API
            }
//            if (!method.isPublic) {
//                return false // 非public方法不算API
//            }
            // 要求公开且非构造函数的方法必须都是API
            return true
        }

        fun loadContractTypeInfo(contractType: ClassDefinition,
                                 contractApiNames: MutableList<String>, contractOfflineApiNames: MutableList<String>,
                                 contractApiArgsTypes: MutableMap<String, MutableList<UvmTypeInfoEnum>>) {
            for (methodDefinition in contractType.methods) {
                if (!isContractApiMethod(methodDefinition)) {
                    continue
                }
                val methodName = methodDefinition.name ?: continue
                // 要求公开且非构造函数的方法必须都是API
                contractApiNames.add(methodName)
                if(methodDefinition.annotations.firstOrNull {t -> Offline::class.java.isAssignableFrom(t.typeClass())}!=null) {
                    contractOfflineApiNames.add(methodName)
                }
                // api的参数列表信息
                var methodParams = methodDefinition.signature?.paramTypes
                var apiArgs: MutableList<UvmTypeInfoEnum> = mutableListOf()
                if (methodParams == null)
                    continue
                for (methodParam in methodParams) {
                    apiArgs.add(getUvmTypeInfoFromType(methodParam))
                }
                contractApiArgsTypes[methodName] = apiArgs
            }
        }

        fun makeProtoName(method: MethodDefinition): String {
            var protoName = method.definitionClass.name + "__" + method.name;
            protoName = protoName.replace('.', '_');
            protoName = protoName.replace('`', '_');
            protoName = protoName.replace('<', '_');
            protoName = protoName.replace('>', '_');
            protoName = protoName.replace("/", "_")
            return protoName;
        }

        fun makeProtoName(method: MethodInfo): String {
            var protoName = method.owner + "__" + method.name
            protoName = protoName.replace('.', '_');
            protoName = protoName.replace('`', '_');
            protoName = protoName.replace('<', '_');
            protoName = protoName.replace('>', '_');
            protoName = protoName.replace("/", "_")
            return protoName;
        }

        fun makeProtoNameOfTypeConstructor(typeRef: ClassDefinition): String {
            var protoName = typeRef.name;
            protoName = protoName.replace('.', '_');
            protoName = protoName.replace('`', '_');
            protoName = protoName.replace('<', '_');
            protoName = protoName.replace('>', '_');
            protoName = protoName.replace("/", "_")
            return protoName;
        }

        fun makeProtoNameOfTypeConstructorByTypeName(typeName: String): String {
            var protoName = typeName;
            protoName = protoName.replace('.', '_');
            protoName = protoName.replace('`', '_');
            protoName = protoName.replace('<', '_');
            protoName = protoName.replace('>', '_');
            protoName = protoName.replace("/", "_")
            return protoName;
        }

        fun getFieldNameFromProperty(propName: String): String {
            val remaining = propName.substring(3)
            val fieldName = (remaining[0] + "").toLowerCase() + remaining.substring(1)
            return fieldName
        }

        fun getStorageValueTypeFromTypeName(typeFullName: String): StorageValueTypes {
            if (typeFullName == String::class.jvmName) {
                return StorageValueTypes.storage_value_string
            }
            if (typeFullName == "int" || typeFullName == "java.lang.Integer" || typeFullName == "long" || typeFullName == "java.lang.Long") {
                return StorageValueTypes.storage_value_int
            }
            if (typeFullName == "float" || typeFullName == "java.lang.Float" || typeFullName == "double" || typeFullName == "java.lang.Double") {
                return StorageValueTypes.storage_value_number;
            }
            if (typeFullName == "boolean" || typeFullName == "java.lang.Boolean") {
                return StorageValueTypes.storage_value_bool;
            }
            throw GjavacException("not supported storage value type $typeFullName now");
        }

        fun getStorageValueTypeFromType(typeRef: ClassDefinition): StorageValueTypes {
            val typeFullName = typeRef.name
            return getStorageValueTypeFromTypeName(typeFullName)
        }

        fun getUvmTypeInfoFromType(typeRef: TypeInfo): UvmTypeInfoEnum {
            var typeFullName = typeRef.fullName();
            if (typeFullName == String::class.jvmName) {
                return UvmTypeInfoEnum.LTI_STRING;
            }
            if (typeFullName == "int" || typeFullName == "java.lang.Integer" || typeFullName == "long" || typeFullName == "java.lang.Long") {
                return UvmTypeInfoEnum.LTI_INT;
            }
            if (typeFullName == "float" || typeFullName == "java.lang.Float" || typeFullName == "double" || typeFullName == "java.lang.Double") {
                return UvmTypeInfoEnum.LTI_NUMBER;
            }
            if (typeFullName == "boolean" || typeFullName == "java.lang.Boolean") {
                return UvmTypeInfoEnum.LTI_BOOL;
            }
            throw GjavacException("not supported storage value type $typeRef now");
        }

        fun escape(input: String): String {
            return StringEscapeUtils.escapeJson(input)
        }
    }
}
