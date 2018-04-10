package com.ub.gjavac.lib;

import java.util.HashMap;
import java.util.Map;

public class UvmStringModule {
    public static final Map<String, String> libContent;

    static {
        libContent = new HashMap<String, String>();
        libContent.put("firstByte", "byte");
        libContent.put("charOfCode", "char");
    }

    public int len(String str) {
        return str != null ? str.length() : 0;
    }

    public int firstByte(String str) {
        if (str == null || str.length() < 1) {
            return 0;
        }
        return (int) str.charAt(0);
    }

    public String charOfCode(int i) {
        return "" + (char) i;
    }

    public String dump(Object toDumpFunction, boolean strip) {
        return "mock of dump function";
    }

    public Integer find(String text, String pattern) {
        return find(text, pattern, 0);
    }

    public Integer find(String text, String pattern, int init) {
        return find(text, pattern, init, false);
    }

    public Integer find(String text, String pattern, int init, boolean plain) {
        if (text == null || pattern == null) {
            return null;
        }
        if (plain) {
            if (!text.contains(pattern)) {
                return null;
            }
            int index = text.indexOf(pattern);
            if (index < init) {
                return null;
            }
            return index;
        } else {
            // pattern 是包含类似 . %s %d %s %w %x 等模式
            throw new RuntimeException("暂时没提供Java中模式字符串库的mock"); // TODO
        }
    }

    public String format(String format, Object arg1) {
        // TODO: 暂时不支持多参数format
        return String.format(format, arg1);
    }

    public IteratorFunc gmatch(String text, String pattern) {
        throw new RuntimeException("暂时不支持Java中模式字符串库的mock"); // TODO
    }

    public String gsub(String src, String pattern, String replacer) {
        return gsub(src, pattern, replacer, null);
    }

    public String gsub(String src, String pattern, String replacer, Integer n) {
        throw new RuntimeException("暂时不支持Java中模式字符串库的mock"); // TODO
    }

    public UvmArray<String> split(String str, String sep) {
        UvmArray<String> result = UvmArray.create();
        if (str == null || sep == null) {
            return result;
        }
        String[] splited = str.split("" + sep.charAt(0));
        for (int i = 0; i < splited.length; i++) {
            result.add(splited[i]);
        }
        return result;
    }

    public String lower(String text) {
        return text.toLowerCase();
    }

    public String match() {
        throw new RuntimeException("暂时不支持Java中模式字符串库的mock"); // TODO
    }

    // 返回str字符串重复n次的结果，间隔符是字符串sep
    public String rep(String str, int n) {
        return rep(str, n, "");
    }

    public String rep(String str, int n, String sep) {
        StringBuilder result = new StringBuilder();
        if (str == null || n < 1) {
            return result.toString();
        }
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                if (sep != null) {
                    result.append(sep);
                }
            }
            result.append(str);
        }
        return result.toString();
    }

    public String reverse(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            result.append(text.charAt(text.length() - 1 - i));
        }
        return result.toString();
    }

    // 获取str字符串的子字符串，从第i个字符开始，到第j个字符结束（包含第i和第j个字符），i和j可以是负数，表示从str反方向开始的第-i/-j个字符
    public String sub(String str, int i) {
        return sub(str, i, -1);
    }

    public String sub(String str, int i, int j) {
        if (j >= 0) {
            return str.substring(i, j - i);
        } else {
            return reverse(str).substring(i, -j - i);
        }
    }

    public String upper(String str) {
        return str.toUpperCase();
    }

    public String pack(String str) {
        throw new RuntimeException("暂时不提供此函数在Java中的mock"); // TODO
    }

    public String packsize(String str) {
        throw new RuntimeException("暂时不提供此函数在Java中的mock"); // TODO
    }

    public Object unpack(String format, String data) {
        throw new RuntimeException("暂时不提供此函数在Java中的mock"); // TODO
    }
}
