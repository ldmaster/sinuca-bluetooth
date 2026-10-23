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
    var falling: Boolean = false
    var fall: Float = 0f
    var dropX: Double = 0.0
    var dropZ: Double = 0.0
    var spinForward: Double = 0.0
    var spinSide: Double = 0.0
    var shotDirX: Double = 0.0
    var shotDirZ: Double = 0.0

    val visualRotation: FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    val speed: Double
        get() = hypot(vx, vz)

    fun stop() {
        vx = 0.0
        vz = 0.0
    }
}
