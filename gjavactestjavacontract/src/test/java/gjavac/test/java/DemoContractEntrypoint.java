package gjavac.test.java;

import gjavac.lib.UvmContract;

import static gjavac.lib.UvmCoreLibs.print;

public class DemoContractEntrypoint {
    public UvmContract main() {
        print("hello java");
        DemoContract contract = new DemoContract();
        contract.setStorage(new Storage());
        print(contract);
//        contract.init();

        return contract;
    }
}
