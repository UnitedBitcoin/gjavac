package com.ub.test.test;

import com.ub.gjavac.lib.Component;
import com.ub.gjavac.lib.Contract;
import com.ub.gjavac.lib.Offline;
import com.ub.gjavac.lib.UvmContract;

import static com.ub.gjavac.lib.UvmCoreLibs.*;

@Contract(storage = MyContract.Storage.class)
class MyContract extends UvmContract<MyContract.Storage> {

    public static class Storage {
        public String name;
    }

    @Component
    public static class Utils {
        public int sum(int a, int b) {
            print("this is util sum func");
            return a + b;
        }
    }

    @Override
    public void init() {
        print("init of demo Contract");
        this.getStorage().name = "my_contract";
        print("storage.name changed");
        pprint(this.getStorage());
    }

    public String sayHi(String name) {
        print("say hi api called of contract to " + name);
        return "Hello" + name;
    }

    public void setName(String name) {
        this.getStorage().name = name;
        pprint("name changed to " + name);
    }

    public String getName(String arg) {
        print("storage.name=" + this.getStorage().name);
        String name = this.getStorage().name;
        return name;
    }

    @Offline
    public String offlineGetInfo(String arg) {
        print("offlineGetInfo called with arg " + arg);
        Utils utils = new Utils();
        print("3+4=" + utils.sum(3, 4));
        return "hello, offline api";
    }
}