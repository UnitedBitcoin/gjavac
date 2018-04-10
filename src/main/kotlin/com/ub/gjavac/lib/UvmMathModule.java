package com.ub.gjavac.lib;

public class UvmMathModule {
    public double abs(double value)
    {
        return Math.abs(value);
    }
    public int abs(int value)
    {
        return Math.abs(value);
    }
    public Integer tointeger(Object value)
    {
        if (value == null)
        {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            return null;
        }
    }
    public long floor(double value)
    {
        return (long)value;
    }
    public long floor(long value)
    {
        return value;
    }
    public double max(double a1, double a2)
    {
        return Math.max(a1, a2);
    }
    public long max(long a1, long a2)
    {
        return Math.max(a1, a2);
    }
    public double min(double a1, double a2)
    {
        return Math.min(a1, a2);
    }
    public long min(long a1, long a2)
    {
        return Math.min(a1, a2);
    }
    public String type(Object value)
    {
        return (value instanceof Integer) ? "int" : "number";
    }
    public final double pi = Math.PI;
    public final long maxinteger = Long.MAX_VALUE;
    public final long mininteger = Long.MIN_VALUE;
}
