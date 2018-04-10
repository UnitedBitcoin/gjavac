package com.ub.gjavac.lib

abstract class UvmContract<T> {
    var storage: T? = null
    abstract fun init()
    fun on_deposit(num: Int) {

    }

    fun on_deposit_asset(depositArgJsonStr: String) {

    }

    fun on_upgrade() {

    }

    fun on_destroy() {

    }
}
