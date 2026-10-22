package com.ldmaster.sinuca.game

object Table {
    const val HALF_LENGTH = 1.27
    const val HALF_WIDTH = 0.635
    const val BALL_RADIUS = 0.028575
    const val CLOTH_TOP = 0.75
    const val BALL_Y = CLOTH_TOP + BALL_RADIUS
    const val POCKET_RADIUS = 0.062

    val POCKETS: List<DoubleArray> = listOf(
        doubleArrayOf(-1.27, -0.635),
        doubleArrayOf(-1.27, 0.635),
        doubleArrayOf(1.27, -0.635),
        doubleArrayOf(1.27, 0.635),
        doubleArrayOf(0.0, -0.635),
        doubleArrayOf(0.0, 0.635)
    )

    val CUE_SPOT = doubleArrayOf(-0.70, 0.0)
}
