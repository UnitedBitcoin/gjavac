package gjavac.lib;

class UvmBigIntImpl extends UvmBigInt{
    UvmBigIntImpl(Object o){
        _o = o;
    }
}
public class UvmSafeMathModule {

    public UvmBigInt bigint(Object value) { return (UvmBigInt)new UvmBigIntImpl(value); }

    public UvmBigInt add(UvmBigInt value1, UvmBigInt value2)
    {
        return new UvmBigIntImpl(UvmCoreLibs.tointeger(value1) + UvmCoreLibs.tointeger(value2));
    }
    public UvmBigInt sub(UvmBigInt value1, UvmBigInt value2)
    {
        return new UvmBigIntImpl(UvmCoreLibs.tointeger(value1) - UvmCoreLibs.tointeger(value2));
    }
    public UvmBigInt mul(UvmBigInt value1, UvmBigInt value2)
    {
        return new UvmBigIntImpl(UvmCoreLibs.tointeger(value1) * UvmCoreLibs.tointeger(value2));
    }
    public UvmBigInt div(UvmBigInt value1, UvmBigInt value2)
    {
        return new UvmBigIntImpl(UvmCoreLibs.tointeger(value1) / UvmCoreLibs.tointeger(value2));
    }
    public UvmBigInt pow(UvmBigInt value1, UvmBigInt value2)
    {
        return new UvmBigIntImpl(Math.pow(UvmCoreLibs.tointeger(value1),UvmCoreLibs.tointeger(value1)));
    }
    public UvmBigInt rem(UvmBigInt value1, UvmBigInt value2)
    {
        return new UvmBigIntImpl(UvmCoreLibs.tointeger(value1)%UvmCoreLibs.tointeger(value1));
    }

    public String tohex(UvmBigInt value)
    {
        return UvmCoreLibs.tostring(value) ;
    }
    public long toint(UvmBigInt value)
    {
        return UvmCoreLibs.tointeger(value) ;
    }
    public String tostring(UvmBigInt value)
    {
        return UvmCoreLibs.tostring(value) ;
    }


    public boolean gt(UvmBigInt value1, UvmBigInt value2)
    {
        return UvmCoreLibs.tointeger(value1)> UvmCoreLibs.tointeger(value2);
    }
    public boolean ge(UvmBigInt value1, UvmBigInt value2)
    {
        return UvmCoreLibs.tointeger(value1)>= UvmCoreLibs.tointeger(value2);
    }

    public boolean lt(UvmBigInt value1, UvmBigInt value2)
    {
        return UvmCoreLibs.tointeger(value1)< UvmCoreLibs.tointeger(value2);
    }
    public boolean le(UvmBigInt value1, UvmBigInt value2)
    {
        return UvmCoreLibs.tointeger(value1)<= UvmCoreLibs.tointeger(value2);
    }

    public boolean eq(UvmBigInt value1, UvmBigInt value2)
    {
        return UvmCoreLibs.tointeger(value1)== UvmCoreLibs.tointeger(value2);
    }
    public boolean ne(UvmBigInt value1, UvmBigInt value2)
    {
        return UvmCoreLibs.tointeger(value1)!= UvmCoreLibs.tointeger(value2);
    }

    public UvmBigInt max(UvmBigInt value1, UvmBigInt value2)
    {
        long v1 = UvmCoreLibs.tointeger(value1);
        long v2 = UvmCoreLibs.tointeger(value2);
        return v1>v2 ? value1:value2 ;
    }
    public UvmBigInt min(UvmBigInt value1, UvmBigInt value2)
    {
        long v1 = UvmCoreLibs.tointeger(value1);
        long v2 = UvmCoreLibs.tointeger(value2);
        return v1<v2 ? value1:value2 ;
    }


}


