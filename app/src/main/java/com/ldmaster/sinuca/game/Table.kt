package com.ldmaster.sinuca.game

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

    data class Pocket(
        val x: Double,
        val z: Double,
        val radius: Double,
        val corner: Boolean
    )

    data class Cushion(
        val x1: Double,
        val z1: Double,
        val x2: Double,
        val z2: Double
    )

    val POCKETS: List<Pocket> = listOf(
        Pocket(HALF_LENGTH, HALF_WIDTH, 0.068, true),
        Pocket(HALF_LENGTH, -HALF_WIDTH, 0.068, true),
        Pocket(-HALF_LENGTH, HALF_WIDTH, 0.068, true),
        Pocket(-HALF_LENGTH, -HALF_WIDTH, 0.068, true),
        Pocket(0.0, HALF_WIDTH, 0.070, false),
        Pocket(0.0, -HALF_WIDTH, 0.070, false)
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

    val CUE_SPOT = doubleArrayOf(-0.70, 0.0)
}
