package gjavac.test;

import gjavac.MainKt;
import org.junit.Test;

public class JavaContractTest {

    private static final String testClassesDir = "target/test-classes";

    @Test
    public void testJavaContractCompile() {
        String class1 = testClassesDir + "/gjavac/test/java/DemoContract";
        String class2 = testClassesDir + "/gjavac/test/java/Utils";
        String class3 = testClassesDir + "/gjavac/test/java/DemoContractEntrypoint";
        String class4 = testClassesDir + "/gjavac/test/java/Storage";
        String[] classesToCompile = new String[] {class1, class2, class3, class4};
        MainKt.main(classesToCompile);
    }
}
