package gjavac

import gjavac.cecil.ClassDefinitionReader
import gjavac.translater.JavaToUvmTranslator
import gjavac.utils.use
import java.io.*

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
    val uvmAssBuilder = StringBuilder()
    translator.translateModule(moduleDef, jvmContentBuilder, uvmAssBuilder)
    val outFilename = "result.ass"
    use(FileOutputStream(File(outFilename)), { fos ->
        val bw = BufferedWriter(PrintWriter(fos))
        bw.write(uvmAssBuilder.toString())
        bw.flush()
    })
    val metaInfoJson = translator.getMetaInfoJson()
    val metaOutputfilename = "result.meta.json"
    use(FileOutputStream(File(metaOutputfilename)), { fos ->
        val bw = BufferedWriter(PrintWriter(fos))
        bw.write(metaInfoJson)
        bw.flush()
    })
    println("compilation done, result file is $outFilename and $metaOutputfilename")
}

