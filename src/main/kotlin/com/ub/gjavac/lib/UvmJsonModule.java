package com.ub.gjavac.lib;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

public class UvmJsonModule {
    private static UvmMap<Object> jsonToUvmMap(JSONObject jsonObject) {
        if(jsonObject==null) {
            return null;
        }
        UvmMap<Object> result = UvmMap.create();
        for (String p : jsonObject.keySet()) {
            Object item = jsonObject.get(p);
            if(item == null) {
                result.set(p, item);
                continue;
            }
            if(item instanceof JSONObject) {
                result.set(p, jsonToUvmMap((JSONObject) item));
            } else if(item instanceof JSONArray) {
                result.set(p, jsonToUvmArray((JSONArray) item));
            } else {
                result.set(p, item);
            }
        }
        return result;
    }
    private static UvmArray<Object> jsonToUvmArray(JSONArray jsonArray) {
        if(jsonArray==null) {
            return null;
        }
        UvmArray<Object> result = UvmArray.create();
        for(int i=0;i<jsonArray.size();i++) {
            Object item = jsonArray.get(i);
            Object value;
            if(item == null) {
                value = null;
            } else if(item instanceof JSONObject) {
                value = jsonToUvmMap((JSONObject) item);
            } else if(item instanceof JSONArray) {
                value = jsonToUvmArray((JSONArray) item);
            } else {
                value = item;
            }
            result.add(value);
        }
        return result;
    }

    public Object loads(String jsonStr)
    {
        if (jsonStr == null || jsonStr.length() < 1)
        {
            return null;
        }
        if (jsonStr.charAt(0) == '{')
        {
            // loads to GluaMap
            JSONObject jsonObject = JSON.parseObject(jsonStr);
            return jsonToUvmMap(jsonObject);
        }
        else if (jsonStr.charAt(0) == '[')
        {
            // loads to GluaArray
            JSONArray jarray = JSON.parseArray(jsonStr);
            return jsonToUvmArray(jarray);
        }
        else
        {
            return JSON.parse(jsonStr);
        }
    }
    public String dumps(Object value)
    {
        return JSON.toJSONString(value);
    }
}
