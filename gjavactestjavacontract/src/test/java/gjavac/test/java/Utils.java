package gjavac.test.java;

import gjavac.lib.Component;
import gjavac.lib.*;
import kotlin.Pair;

import static gjavac.lib.UvmCoreLibs.get_current_contract_address;
import static gjavac.lib.UvmCoreLibs.print;

@Component
public class Utils {
    public String NOT_INITED() {
        return "NOT_INITED";
    }


    public String COMMON() {
        return "COMMON";
    }


    public String PAUSED() {
        return "PAUSED";
    }


    public String STOPPED() {
        return "STOPPED";
    }

    public final void checkAdmin( DemoContract self) {
        String fromAddr = getFromAddress();
                if (self.getStorage().admin!=fromAddr) {
                    UvmCoreLibs.error("you are not admin, can't call this function");
                }
    }

    public final void checkCallerFrameValid( UvmContract self) {
        String prev_contract_id = UvmCoreLibs.get_prev_call_frame_contract_address();
        String prev_api_name = UvmCoreLibs.get_prev_call_frame_api_name();
        if (prev_contract_id != null && prev_contract_id!="") {
            if (prev_contract_id != get_current_contract_address()) {
                UvmCoreLibs.error("this api can't called by invalid contract:" + prev_contract_id);
            }
        }
    }


    public UvmArray<String> parseArgs(String arg, int count,  String errorMsg) {
        UvmArray var10000;
        if (arg == null) {
            UvmCoreLibs.error(errorMsg);
            var10000 = UvmArray.create();
            return var10000;
        } else {
            UvmStringModule stringModule = (UvmStringModule)UvmCoreLibs.importModule(UvmStringModule.class, "string");
            UvmArray parsed = stringModule.split(arg, ",");
            if (parsed != null && parsed.size() == count) {
                return parsed;
            } else {
                UvmCoreLibs.error(errorMsg);
                var10000 = UvmArray.create();
                return var10000;
            }
        }
    }


    public UvmArray<String> parseAtLeastArgs(String arg, int count,  String errorMsg) {
        UvmArray var10000;
        if (arg == null) {
            UvmCoreLibs.error(errorMsg);
            var10000 = UvmArray.create();
            return var10000;
        } else {
            UvmStringModule stringModule = (UvmStringModule)UvmCoreLibs.importModule(UvmStringModule.class, "string");
            UvmArray parsed = stringModule.split(arg, ",");
            if (parsed != null && parsed.size() >= count) {
                return parsed;
            } else {
                UvmCoreLibs.error(errorMsg);
                return UvmArray.create();
            }
        }
    }

    public boolean arrayContains(UvmArray col, Object item) {
        if (col != null && item != null) {
            ArrayIterator colIter = col.ipairs();
            for(Pair colKeyValuePair = (Pair)colIter.invoke(col, 0); colKeyValuePair.getFirst() != null; colKeyValuePair = (Pair)colIter.invoke(col, colKeyValuePair.getFirst())) {
                if (colKeyValuePair != null && colKeyValuePair.getSecond()==item) {
                    return true;
                }
            }
            return false;
        } else {
            return false;
        }
    }


    public String getFromAddress() {
        String prev_contract_id = UvmCoreLibs.get_prev_call_frame_contract_address();
        String fromAddress;
        if (prev_contract_id != null && UvmCoreLibs.is_valid_contract_address(prev_contract_id)) {
            fromAddress = prev_contract_id;
        } else {
            fromAddress = UvmCoreLibs.caller_address();
        }
        return fromAddress;
    }


    public void checkState( DemoContract self) {
            String state = self.getStorage().getState();
            if (state==this.NOT_INITED()) {
                UvmCoreLibs.error("contract token not inited");
            } else if (state==this.PAUSED()) {
                UvmCoreLibs.error("contract paused");
            } else if (state==this.STOPPED()) {
                UvmCoreLibs.error("contract stopped");
            }
    }

    public void checkStateInited( DemoContract self) {
                if (self.getStorage().state==this.NOT_INITED()) {
                    UvmCoreLibs.error("contract token not inited");
                }
                return;
    }

    public boolean checkAddress( String addr) {
        boolean result = UvmCoreLibs.is_valid_address(addr);
        if (!result) {
            UvmCoreLibs.error("address format error");
            return false;
        } else {
            return true;
        }
    }


    public String getBalanceOfUser( DemoContract self,  String addr) {
        Object balance = UvmCoreLibs.fast_map_get("users", addr);
        if (balance == null) {
            return "0";
        } else {
            return UvmCoreLibs.tostring(balance);
        }
    }

}