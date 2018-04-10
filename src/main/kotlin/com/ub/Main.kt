package com.ub

import com.ub.gjavac.cecil.*
import com.ub.gjavac.lib.*
import com.ub.gjavac.lib.UvmCoreLibs.*
import com.ub.gjavac.translater.JavaToUvmTranslator
import com.ub.gjavac.utils.use
import java.io.*
import com.ub.gjavac.lib.UvmMathModule
import com.ub.gjavac.lib.UvmCoreLibs.importModule


class Storage {
    var name: String? = null
//    var age: Int? = null
}

@Component
class Utils {
    fun sum(a: Int, b: Int): Int {
        print("this is util sum func")
        return a + b
    }
}

@Contract(storage = Storage::class)
class MyContract : UvmContract<Storage>() {
    override fun init() {
        val a: Byte = 123
        print("init of demo Contract " + a)
        this.storage?.name = "my_contract" // TODO: getfield error in chain
        print("storage.name changed")
        pprint(this.storage)
        print(this.storage?.name)
//        this.storage?.age = 25 // FIXME: maybe crash
    }

    fun sayHi(name: String): String {
        print("say hi api called of contract to $name")
        return "Hello" + name
    }

    fun setName(name: String) {
        this.storage?.name = name
        pprint("name changed to " + name)
    }

    fun getName(arg: String) {
        print("storage.name=" + this.storage?.name)
    }

    @Offline
    fun offlineGetInfo(arg: String): String {
        print("offlineGetInfo called with arg " + arg)
//        testUtil() // FIXME: change to get this["testUtil"]()
        val utils = Utils()
        print("3+4=" + utils.sum(3, 4))
        return "hello, offline api"
    }

    private fun testUtil() {
        print("this is util func not in apis")
    }
}

class Person {

    // TODO: event, LCMP, LREM etc.

    fun sayHi(name: String): String {
        print("sayHi func called")
        return "Hello$name java. I love money"
    }

    companion object {
//
//        fun sum(a: String, b: String): String {
//            return a + b
//        }
    }

    fun testIf(): Boolean {
        val a = 1
        val b = 2
        val c = 3
        if(a<b) {
            print("$a < $b")
        }
        if(c>=b) {
            print("$c>=$b")
        }
        if(b>c) {
            print("error here")
        } else {
            print("$b <= $c")
        }
        return true
    }

    fun testFor() {
        print("testFor cases")
        var sum = 0
        // TODO
    }

    fun testWhile() {
        print("testWhile cases")
        var sum = 0
        var i = 0
        while(i<10) {
            sum += i
            i++
            println(i)
        }
        print("sum=$sum")
        var j = 100
        var sum2 = 0
        do {
            sum2 += j
            j--
        }while(j>=0)
        println("sum2=$sum2")
    }

//     fun testWhen() {
//         println("testWhen cases")
//         var i = 0
//         while(i<4) {
//             println(i)
//             when(i) {
//                 0 -> println("$i=0")
//                 1->println("$i=1")
//                 2->println("$i=2")
//                 else -> println("$i>2")
//             }
//             i++
//         }
//     }

    // TODO: test continue, break

    fun test_pprint() {
        UvmCoreLibs.pprint(this)
    }

    fun testNumber() {
        print("testNumber cases:")
        val a = 4
        val b = 5
        val c = 1.23
        val d = a + b
        val e = a + c
        val f = b*c
        val h = c/b
        print(c)
        println("$a + $b = $d")
        println("$a + $c = $e")
        println("$b * $c = $f")
        println("$c / $b = $h")

        println("1 = ${tointeger(c)}")
        println("~ ${a} = ${neg(a as Long)}")
    }

    fun testBooleans() {
        var a = true
        var b = false
        var c = a && b
        print("a && b=$c")
        println("a || b=${a||b}")
        if(a) {
            print("$a=true")
        }
        if(b) {
            print("error happend in boolean test")
        } else {
            print("$b=false")
        }
        var i=0
        while(i<3) {
            if(!(i>1)) {
                print("!($i>1)=true")
            }
            i++
        }
        print("!c=${!c}")
    }

    fun testArray() {
        print("testArray cases")
        val array1 = UvmArray.create<Int?>()
        array1.add(1)
        array1.add(2)
        array1.add(3)
        array1.add(4)
        pprint(array1)
        print("array 1 size is " + array1.size()) // 4
        array1.set(4, 2)
        print("array[4] is " + array1.get(4)) // 2
        array1.pop()
        print("array 1 after changed size is " + array1.size()) // 3
        print("array[3] is " + array1.get(3)) // 3
        pprint(array1)
        for (i in 1..(array1.size())) {
            val item = array1.get(i)
            print("index: " + i)
            print("value: " + item)
        }
        val array1Iter = array1.ipairs()
        var array1keyValuePair = array1Iter(array1, 0)
        while (array1keyValuePair.first != null)
        {
            print("key: " + array1keyValuePair.first)
            print("value: " + array1keyValuePair.second)
            array1keyValuePair = array1Iter(array1, array1keyValuePair.first)
        }
        array1.set(3, null)
        print("array1 size is ${array1.size()} after remove index 3")
        pprint(array1)
    }

    fun testMap() {
        val map1 = UvmMap.create<String>()
        map1.set("name", "C#")
        map1.set("country", "China")
        print("map1's name is " + map1.get("name"))
        print("map1's country is " + map1.get("country"))
        // 遍历map的demo
        val map1PairsIter = map1.pairs()
        pprint(map1PairsIter)
        var keyValuePair = map1PairsIter(map1, null)
        pprint(keyValuePair)
        print(keyValuePair.first)
        print(keyValuePair.second)
        while (keyValuePair.first != null)
        {
            print("key: " + tostring(keyValuePair.first))
            print("value: " + tostring(keyValuePair.second))
//            debug()
            if (keyValuePair.first == "name")
            {
                print("found key==name pair")
            }
            keyValuePair = map1PairsIter(map1, keyValuePair.first)
        }
        val table1 = map1 as UvmTable
        pprint("cast C#=" + (table1 as UvmMap<String>).get("name"))
    }

    fun testEvent() {
        emit("Hello", "World") // FIXME: change to emitEventMethod
    }

    fun testModules() {
        print("testModules cases")
        val strModule = importModule(UvmStringModule::class.java, "string")
        val len = strModule.len("Hello")
        print("Hello length is $len")

        var tableModule = importModule<UvmTableModule>(UvmTableModule::class.java, "table")
        var table1 = UvmArray.create<String>()
        table1.add("a")
        tableModule.append(table1, "b")
        var table1Count = tableModule.length(table1)
        print("table1 size is: " + table1Count)

        val mathModule = importModule<UvmMathModule>(UvmMathModule::class.java, "math")
        val floor1 = mathModule.floor(3.3)
        print("floor(3.3) = " + floor1)
        val abs1 = mathModule.abs(-4)
        print("abs(-4) = " + abs1)
        val pi = mathModule.pi
        print("pi = " + pi)
        val stoi1 = mathModule.tointeger("123")
        print("123=$stoi1")

        var timeModule = importModule<UvmTimeModule>(UvmTimeModule::class.java, "time");
        print("date: " + timeModule.tostr(1494301754))

        val jsonModule = importModule<UvmJsonModule>(UvmJsonModule::class.java, "json");
        print("dumps of json module is: " + jsonModule.dumps(jsonModule))
    }

    fun testImportContract() {
        // TODO
        val utilsContract = importContract(Utils::class.java, "utils")
        print("3+4=${utilsContract.sum(3,4)}")
    }

    fun main(): MyContract {
        // entry point of contract
        print("hello glua")
        val contract = MyContract()
        // don't init contract when compile to gpc, used as contract
//        if (contract is MyContract) {
//            print("contract is contract")
//            print(contract.sayHi("contract-name"))
//            contract.storage = Storage()
//            contract.init()
//            print("name="+contract.storage?.name)
//            val offlineApiRes = contract.offlineGetInfo("hi")
//            print("offline api res is $offlineApiRes")
//        }

        testIf()
        testNumber()
        testWhile()
//        testWhen()
        test_pprint()
        testBooleans()
        testArray()
        testMap()
        testEvent()
        testModules()
//        testImportContract()
        print(sayHi("hi-name"))
        return contract
    }
}

/**
 * example: target/classes/com/ub/MainKt target/classes/com/ub/Person target/classes/com/ub/Person$Companion target/classes/com/ub/MyContract target/classes/com/ub/Storage target/classes/com/ub/Utils
 */
fun main(args: Array<String>) {
    val classDefReader = ClassDefinitionReader()
    val classesPaths = mutableListOf<String>()
    if(args.isEmpty()) {
        println("need pass to compile java bytecode class filepaths as argument")
        return
    }
    for(i in 0..(args.size-1)) {
        var path = args[i]
        if(!path.endsWith(".class"))
            path += ".class"
        classesPaths.add(path)
    }
    val moduleDef = classDefReader.readClass(classesPaths)
    val translator = JavaToUvmTranslator()
    val jvmContentBuilder = StringBuilder()
    val gluaAssBuilder = StringBuilder()
    translator.translateModule(moduleDef, jvmContentBuilder, gluaAssBuilder)
    val outFilename = "result.ass"
    use(FileOutputStream(File(outFilename)), { fos ->
        val bw = BufferedWriter(PrintWriter(fos))
        bw.write(gluaAssBuilder.toString())
        bw.flush()
    })
    val metaInfoJson = translator.getMetaInfoJson()
    val metaOutputfilename = "result.meta.json"
    use(FileOutputStream(File(metaOutputfilename)), {fos ->
        val bw = BufferedWriter(PrintWriter(fos))
        bw.write(metaInfoJson)
        bw.flush()
    })
    println("compilation done, result file is $outFilename and $metaOutputfilename")
}

