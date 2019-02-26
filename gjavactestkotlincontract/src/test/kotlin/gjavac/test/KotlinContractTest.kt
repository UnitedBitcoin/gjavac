package gjavac.test

import gjavac.main
import org.junit.Test

class KotlinContractTest {

    private val testClassesDir = "target/test-classes"

    @Test
    fun testKotlinContract() {
        val class1 = "$testClassesDir/gjavac/test/kotlin/TokenContractLoader"
        val class2 = "$testClassesDir/gjavac/test/kotlin/MultiOwnedContractSimpleInterface"
        val class3 = "$testClassesDir/gjavac/test/kotlin/TokenContract"
        val class4 = "$testClassesDir/gjavac/test/kotlin/Storage"
        val class5 = "$testClassesDir/gjavac/test/kotlin/Utils"

        val classesToCompile = arrayOf(class1, class2, class3, class4, class5)
        main(classesToCompile)
    }
}