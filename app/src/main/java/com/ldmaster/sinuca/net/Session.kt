package com.ldmaster.sinuca.net

object Session {
    @Volatile
    var service: BluetoothGame? = null

    @Volatile
    var role: String = "practice"
}
