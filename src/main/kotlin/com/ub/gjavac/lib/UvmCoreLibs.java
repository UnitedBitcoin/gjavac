package com.ub.gjavac.lib;

import com.google.gson.Gson;

import java.util.*;

public class UvmCoreLibs {

    public static void print(Object obj) {
        System.out.println(tostring(obj));
    }

    public static String tostring(Object obj) {
        if(obj == null) {
            return "nil";
        } else {
            return obj.toString();
        }
    }

    public static String tojsonstring(Object obj) {
        if(obj==null) {
            return "null";
        } else {
            Gson gson = new Gson();
            return gson.toJson(obj);
        }
    }

    public static void pprint(Object obj) {
        System.out.println(tojsonstring(obj));
    }

    public static boolean and(boolean a, boolean b) {
        return a &&b;
    }

    public static boolean or(boolean a, boolean b) {
        return a || b;
    }

    public static int idiv(int a, int b) {
        return a/b;
    }
    public static int idiv(int a, float b) {
        return (int)(a/b);
    }
    public static int idiv(float a, int b) {
        return (int)(a/b);
    }
    public static int idiv(float a, float b) {
        return (int)(a/b);
    }

    public static Long tointeger(Object value) {
        if(value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public static Double tonumber(Object value) {
        if(value == null) {
            return null;
        }
        try {
            return Double.valueOf(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public static Boolean toboolean(Object value) {
        if(value == null) {
            return null;
        }
        return "true".equals(value.toString()) || "1".equals(value.toString());
    }

    public static UvmTable totable(Object value) {
        if(value == null) {
            return null;
        }
        if(value instanceof UvmTable) {
            return (UvmTable) value;
        } else {
            return null;
        }
    }

    public static long neg(long n) {
        return ~n;
    }

    public static <T> T importContractFromAddress(Class<T> contractClass, String contractName) {
        try {
            return contractClass.newInstance();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static <T> T importContract(Class<T> contractClass, String contractName) {
        try {
            return contractClass.newInstance();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static <T> T importModule(Class<T> moduleClass, String moduleName) {
        List<String> innerModules = Arrays.asList(
      "string", "table", "math", "time", "json", "os", "net", "http", "jsonrpc"
        );
        if (innerModules.contains(moduleName))
        {
            try {
                return moduleClass.newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        else
        {
            throw new RuntimeException("not supported module " + moduleName);
        }
    }

    public static void debug() {
        System.out.println("debug point");
    }

    public static void debugStackSize() {
        System.out.println("debug point with stack size");
    }

    public static String type(Object value) {
        if(value == null) {
            return "nil";
        } else if(value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double) {
            return "number";
        } else if(value instanceof Boolean) {
            return "boolean";
        } else if(value instanceof UvmTable) {
            return "table";
        } else {
            return "object"; // TODO: function type
        }
    }

    public static void exit(int exitCode) {
        System.out.println("exit with code " + exitCode);
        throw new RuntimeException("application exit with code " + exitCode);
    }

    public static void error(String errorMsg) {
        System.out.println("exit with error " + errorMsg);
        throw new RuntimeException("application exit with error " + errorMsg);
    }

    public static UvmMap<Object> getmetatable(Object value) {
        throw new RuntimeException("not supported in mock mode");
    }

    public static void setmetatable(Object value, UvmMap<Object> metatable) {
        throw new RuntimeException("not supported in mock mode");
    }

    public static boolean rawequal(Object a, Object b) {
        return a == b;
    }

    public static int rawlen(Object value) {
        if(value == null) {
            return 0;
        }
        // TODO: when value is instance of GluaArray
        return 0;
    }

    public static void rawset(Object table, Object key, Object value) {
        if(table == null || key == null) {
            return;
        }
        if(table instanceof UvmArray) {
            UvmArray<Object> array = (UvmArray<Object>) table;
            if(key instanceof Integer || key instanceof Long) {
                array.set(Integer.valueOf(key.toString()), value);
            }
        } else if(table instanceof UvmMap) {
            if(key instanceof String) {
                ((UvmMap<Object>) table).set((String) key, value);
            }
        }
    }

    public static Object rawget(Object table, Object key) {
        if(table == null || key == null) {
            return null;
        }
        if(table instanceof UvmArray) {
            if(key instanceof Integer || key instanceof Long) {
                return ((UvmArray<Object>)table).get(Integer.valueOf(key.toString()));
            }
            return null;
        } else if(table instanceof UvmMap) {
            if(key instanceof String) {
                return ((UvmMap<Object>) table).get((String) key);
            }
            return null;
        } else {
            return null;
        }
    }

    public static int transfer_from_contract_to_address(String address,
                                                        String assetName, long amount)
    {
        System.out.println("this is C# mock of transfer_from_contract_to_address " + address + " " + amount + assetName);
        return 0;
    }

    private static HashMap<String, Long> _cacheOfContractBalanceMock = new HashMap<String, Long>();
    /**
     * 模拟修改合约的余额，用来在C#调试中使用。这个函数的调用实际不会调用，但是还是会产生几行字节码，所以建议上链前注释掉
     */
    public static void set_mock_contract_balance_amount(String contractAddress, String assetName, long amount)
    {
        String key = contractAddress + "$" + assetName;
        _cacheOfContractBalanceMock.put(key, amount);
    }
    // get_contract_balance_amount
    public static long get_contract_balance_amount(String contractAddress, String assetName)
    {
        System.out.println("this is a Java mock of get_contract_balance_amount, contract: " + contractAddress + ", asset name: " + assetName);
        String key = contractAddress + "$" + assetName;
        if (_cacheOfContractBalanceMock.containsKey(key))
        {
            return _cacheOfContractBalanceMock.get(key);
        }
        else
        {
            return 0;
        }
    }
    // get_chain_now
    public static long get_chain_now()
    {
        System.out.println("this is a Java mock of get_chain_now");
        return new Date().getTime();
    }
    // get_chain_random
    public static long get_chain_random()
    {
        System.out.println("this is a Java mock of get_chain_random");
        return new Random().nextInt(10000000);
    }
    // get_header_block_num
    public static long get_header_block_num()
    {
        System.out.println("this is a Java mock of get_header_block_num");
        return 10086; // this is mock value
    }
    // get_waited
    public static long get_waited(long num)
    {
        System.out.println("this is a Java mock of get_waited");
        return 10086; // this is mock value
    }
    // get_current_contract_address
    public static String get_current_contract_address()
    {
        return "mock_dotnet_contract_address"; // this is mock value
    }

    public static String caller()
    {
        return "mock_dotnet_caller";
    }

    public static String caller_address()
    {
        return "mock_dotnet_caller_address";
    }
    // get_transaction_fee
    public static long get_transaction_fee()
    {
        System.out.println("this is a Java mock of get_transaction_fee");
        return 1; // this is mock value
    }

    // transfer_from_contract_to_public_account
    public static long transfer_from_contract_to_public_account(String to_account_name, String assertName,
                                                                long amount)
    {
        System.out.println("this is a Java mock of transfer_from_contract_to_public_account," +
                " " + amount + assertName + " to account " + to_account_name);
        return 0;
    }
    public static boolean is_valid_address(String addr)
    {
        System.out.println("this is a Java mock of is_valid_address");
        return true;
    }

    public static boolean is_valid_contract_address(String addr) {
        System.out.println("this is a Java mock of is_valid_contract_address");
        return true;
    }

    public static String get_prev_call_frame_contract_address() {
        System.out.println("this is a Java mock of get_prev_call_frame_contract_address");
        return "prev_frame_id";
    }

    private static String prev_call_frame_api_name = "test";

    public static void set_prev_call_frame_api_name_for_mock(String name) {
        prev_call_frame_api_name = name;
    }

    public static String get_prev_call_frame_api_name() {
        return prev_call_frame_api_name;
    }

    private static int contract_call_frame_stack_size = 1;

    public static void set_contract_call_frame_stack_size_for_mock(int size) {
        contract_call_frame_stack_size = size;
    }

    public static int get_contract_call_frame_stack_size() {
        return contract_call_frame_stack_size;
    }

    public static String get_system_asset_symbol()
    {
        return "TEST";
    }

    private static int system_asset_precision = 8;

    public static void set_system_asset_precision_for_mock(int precision) {
        system_asset_precision = precision;
    }

    public static int get_system_asset_precision() {
        return system_asset_precision;
    }

    public static void emit(String eventName, String eventArg) {
        System.out.println("emited event " + eventName + " with event argument " + eventArg);
    }

}
