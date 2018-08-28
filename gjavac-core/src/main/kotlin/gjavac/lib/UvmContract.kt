package gjavac.lib

abstract class UvmContract<T> {
    var storage: T? = null
    abstract fun init()
    open fun on_deposit(num: Long){

    }

    open fun on_deposit_asset(depositArgJsonStr: String){

    }

    open fun on_upgrade(){

    }

    open fun on_destory(){

    }
}
