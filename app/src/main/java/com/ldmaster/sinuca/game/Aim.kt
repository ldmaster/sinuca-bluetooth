package com.ldmaster.sinuca.game

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

data class Contact(
    val target: Ball,
    val ghostX: Double,
    val ghostZ: Double,
    val normalX: Double,
    val normalZ: Double,
    val distance: Double
)

object Aim {

    fun firstContact(
        cue: Ball,
        balls: List<Ball>,
        directionX: Double,
        directionZ: Double
    ): Contact? {
        val length = hypot(directionX, directionZ)
        if (length < 1e-9) return null
        val dx = directionX / length
        val dz = directionZ / length
        val diameter = Table.BALL_RADIUS * 2.0
        var best: Contact? = null

        for (ball in balls) {
            if (ball.number == 0 || !ball.active || ball.falling) continue
            val tx = ball.x - cue.x
            val tz = ball.z - cue.z
            val along = tx * dx + tz * dz
            if (along <= 0.0) continue

            val cross = tx * dz - tz * dx
            if (abs(cross) >= diameter) continue

            val hit = along - sqrt(diameter * diameter - cross * cross)
            if (hit < 0.0) continue

            val ghostX = cue.x + dx * hit
            val ghostZ = cue.z + dz * hit
            val nx = ball.x - ghostX
            val nz = ball.z - ghostZ
            val normalLength = hypot(nx, nz)
            if (normalLength < 1e-9) continue

            val contact = Contact(
                target = ball,
                ghostX = ghostX,
                ghostZ = ghostZ,
                normalX = nx / normalLength,
                normalZ = nz / normalLength,
                distance = hit
            )
            if (best == null || hit < best.distance) best = contact
        }
        return best
    }
}
