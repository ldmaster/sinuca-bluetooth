package com.ldmaster.sinuca.game

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AiStrengthTest {

    private fun tableWith(
        cueX: Double,
        cueZ: Double,
        number: Int,
        x: Double,
        z: Double
    ): GameWorld {
        val world = GameWorld()
        for (ball in world.balls) {
            if (ball.number != 0 && ball.number != number) {
                ball.active = false
                ball.stop()
            }
        }
        val cue = world.balls[0]
        cue.x = cueX
        cue.z = cueZ
        cue.active = true
        cue.falling = false
        cue.stop()
        val target = world.balls[number]
        target.active = true
        target.falling = false
        target.fall = 0f
        target.x = x
        target.z = z
        target.stop()
        world.resolvePots(emptyList())
        return world
    }

    @Test
    fun `hard ai pot rate on spread layouts`() {
        val layouts = listOf(
            doubleArrayOf(-0.90, -0.40, 0.50, 0.25),
            doubleArrayOf(-0.70, 0.30, 0.80, -0.20),
            doubleArrayOf(0.90, 0.40, -0.50, -0.10),
            doubleArrayOf(-0.90, 0.50, 0.90, 0.50),
            doubleArrayOf(0.00, 0.00, 0.90, 0.40),
            doubleArrayOf(0.00, 0.40, -0.90, -0.40),
            doubleArrayOf(-0.80, -0.50, 0.20, 0.00),
            doubleArrayOf(0.80, -0.50, -0.20, 0.20),
            doubleArrayOf(-0.50, -0.30, 0.70, 0.30),
            doubleArrayOf(0.50, 0.30, -0.70, -0.30)
        )
        var pots = 0
        layouts.forEachIndexed { index, layout ->
            val world = tableWith(layout[0], layout[1], 9, layout[2], layout[3])
            val plan = AiPlanner(AiLevel.HARD, Random(index.toLong() + 1))
                .plan(world.snapshot(), 1)
            val simulation = GameWorld()
            simulation.applySnapshot(world.snapshot())
            assertTrue(simulation.shoot(plan.angle, plan.power, 0.0, 0.0))
            repeat(2400) { simulation.step(1.0 / 120.0) }
            val potted = !simulation.balls[9].active
            if (potted) pots++
            println(
                "layout $index potted=$potted angle=%.4f power=%.3f".format(
                    Math.toDegrees(plan.angle),
                    plan.power
                )
            )
        }
        println("hard pot rate $pots/${layouts.size}")
        assertTrue("hard AI should pot at least 8/10", pots >= 8)
    }
}
