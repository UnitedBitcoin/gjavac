package gjavac.test.java;

import gjavac.lib.Component;

import static gjavac.lib.UvmCoreLibs.print;

@Component
public class Utils {
    public int sum(int a, int b) {
        print("this is util sum func");
        return a + b;
    }
}