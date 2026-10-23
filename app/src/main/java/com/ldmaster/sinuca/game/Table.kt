package com.ldmaster.sinuca.game

import kotlin.math.abs

object Table {
    const val HALF_LENGTH = 1.27
    const val HALF_WIDTH = 0.635
    const val BALL_RADIUS = 0.028575
    const val CLOTH_TOP = 0.75
    const val BALL_Y = CLOTH_TOP + BALL_RADIUS

    const val CORNER_MOUTH = 0.116
    const val SIDE_MOUTH = 0.127
    const val CORNER_TIP = CORNER_MOUTH / 1.4142135623730951
    const val SIDE_TIP = SIDE_MOUTH / 2.0

    const val CORNER_HOLE_RADIUS = 0.068
    const val SIDE_HOLE_RADIUS = 0.070
    const val CORNER_HOLE_OFFSET = CORNER_HOLE_RADIUS / 1.4142135623730951
    const val SIDE_HOLE_OFFSET = SIDE_HOLE_RADIUS

    data class Pocket(
        val x: Double,
        val z: Double,
        val corner: Boolean
    ) {
        val sx: Double = if (x >= 0.0) 1.0 else -1.0
        val sz: Double = if (z >= 0.0) 1.0 else -1.0
    }

    data class Cushion(
        val x1: Double,
        val z1: Double,
        val x2: Double,
        val z2: Double
    )

    /**
     * Visible hole centres. They sit just outside the mouth so the hole is a
     * complete circle tangent to the cushion line; the mouth itself is the
     * capture line used by [captured].
     */
    val POCKETS: List<Pocket> = listOf(
        Pocket(HALF_LENGTH + CORNER_HOLE_OFFSET, HALF_WIDTH + CORNER_HOLE_OFFSET, true),
        Pocket(HALF_LENGTH + CORNER_HOLE_OFFSET, -HALF_WIDTH - CORNER_HOLE_OFFSET, true),
        Pocket(-HALF_LENGTH - CORNER_HOLE_OFFSET, HALF_WIDTH + CORNER_HOLE_OFFSET, true),
        Pocket(-HALF_LENGTH - CORNER_HOLE_OFFSET, -HALF_WIDTH - CORNER_HOLE_OFFSET, true),
        Pocket(0.0, HALF_WIDTH + SIDE_HOLE_OFFSET, false),
        Pocket(0.0, -HALF_WIDTH - SIDE_HOLE_OFFSET, false)
    )

    val CUSHIONS: List<Cushion> = buildList {
        val end = HALF_LENGTH - CORNER_TIP
        val side = SIDE_TIP
        for (sign in doubleArrayOf(1.0, -1.0)) {
            val z = sign * HALF_WIDTH
            add(Cushion(-end, z, -side, z))
            add(Cushion(side, z, end, z))
        }
        val short = HALF_WIDTH - CORNER_TIP
        for (sign in doubleArrayOf(1.0, -1.0)) {
            val x = sign * HALF_LENGTH
            add(Cushion(x, -short, x, short))
        }
    }

    /**
     * The pocket is behind the mouth line, so a ball is captured only after
     * its centre crosses that line: the chord between the cushion tips for a
     * corner pocket, or the cushion line for a side pocket. The green cloth is
     * never cut inside the playing surface.
     */
    fun captured(pocket: Pocket, x: Double, z: Double): Boolean {
        if (pocket.corner) {
            if (x * pocket.sx <= 0.0 || z * pocket.sz <= 0.0) return false
            val u = HALF_LENGTH - abs(x)
            val v = HALF_WIDTH - abs(z)
            return u + v <= CORNER_TIP
        }
        return abs(x) <= SIDE_TIP &&
            z * pocket.sz >= HALF_WIDTH - 0.0001
    }

    val CUE_SPOT = doubleArrayOf(-0.70, 0.0)
}
