#gjavac

Java and Kotlin compiler for uvm

# Dependencies

* JDK1.8+
* Maven 3
* only support Windows now

# Usage

* `mvn pacakge` to generate `target/gjavac-${version}-jar-with-dependencies.jar` to generate gjavac.jar
* `gjavac.jar path-of-need-.class-files` to generate contract's assembler file(*.ass file)
* `uvm_ass path-of-.ass-file` to generate bytecode file(*.out) and metadata file(*.meta.json)
* `package_gpc path-of-bytecode-file path-of-metadata-json-file` to generate contract file(*.gpc)
* now you can use *.gpc file to register contract in the blockchain
