package com.ldmaster.sinuca.game

import kotlin.math.hypot

class Ball(
    val number: Int,
    var x: Double,
    var z: Double,
    var vx: Double = 0.0,
    var vz: Double = 0.0,
    var active: Boolean = true
) {
    val speed: Double
        get() = hypot(vx, vz)

    fun stop() {
        vx = 0.0
        vz = 0.0
    }
}
