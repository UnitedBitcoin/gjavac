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
    var i = 0
    var outputPath = ""
    while(i <=(args.size-1)) {
        var path = args[i]
        if(path == "-o"){
            i++;
            if(i<=(args.size-1)){
                outputPath = args[i]
            }
        }
        else if(!path.endsWith(".class")){
            path = path +".class"
            classesPaths.add(path)
        }
        else{
            classesPaths.add(path)
        }
        i++;
    }
    val moduleDef = classDefReader.readClass(classesPaths)
    val translator = JavaToUvmTranslator()
    val jvmContentBuilder = StringBuilder()
    val uvmAssBuilder = StringBuilder()
    translator.translateModule(moduleDef, jvmContentBuilder, uvmAssBuilder)
    var outFilename = "result.ass"
    if(outputPath.length>0){
        outFilename = outputPath + "/" + outFilename
    }
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

