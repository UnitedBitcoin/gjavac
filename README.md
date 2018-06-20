# gjavac

Java and Kotlin compiler for uvm

# Dependencies

* JDK1.8+
* Maven 3

# Examples

* You can find a kotlin demo contract and a java demo contract in `gjavactestjavacontract` directory and `gjavatestkotlincontract` directory

# Usage

* need to add reference to `gjavac-core` as lib in you java contract project
* `mvn pacakge` to generate `gjavac-compiler/target/gjavac-${version}-jar-with-dependencies.jar` to generate gjavac.jar
* `java -classpath "classesOfYourContract:pathOf-gjavac.jar" gjavac.MainKt path-of-need-.class-files` to generate contract's assembler file(*.ass file)
* `uvm_ass path-of-.ass-file` to generate bytecode file(*.out) and metadata file(*.meta.json)
* `package_gpc path-of-bytecode-file path-of-metadata-json-file` to generate contract file(*.gpc)
* now you can use *.gpc file to register contract in the blockchain
