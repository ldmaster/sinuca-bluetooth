package com.ldmaster.sinuca.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AiExtremeTest {

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
    fun `extreme thinks longer than hard and rewards multi pots`() {
        assertTrue(AiLevel.EXTREME.thinkTimeMillis > AiLevel.HARD.thinkTimeMillis)
        assertTrue(AiLevel.EXTREME.searchBudgetMillis >= AiLevel.HARD.searchBudgetMillis)
        assertTrue(AiLevel.EXTREME.multiPotBonus > AiLevel.HARD.multiPotBonus)
        assertEquals(0.0, AiLevel.EXTREME.aimNoiseDegrees, 1e-9)
        assertEquals(0.0, AiLevel.EXTREME.mistakeChance, 1e-9)
    }

    @Test
    fun `extreme pots a reachable stripe`() {
        val world = tableWith(-0.60, 0.10, 9, 0.55, 0.10)
        val plan = AiPlanner(AiLevel.EXTREME, Random(3))
            .plan(world.snapshot(), 1, budgetMillis = 1200L)

        val simulation = GameWorld()
        simulation.applySnapshot(world.snapshot())
        assertTrue(simulation.shoot(plan.angle, plan.power, 0.0, 0.0))
        repeat(2400) { simulation.step(1.0 / 120.0) }

        assertFalse("the planned shot should pot the target", simulation.balls[9].active)
    }

    @Test
    fun `extreme keeps the pot rate of the hard level`() {
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
            val plan = AiPlanner(AiLevel.EXTREME, Random(index.toLong() + 1))
                .plan(world.snapshot(), 1, budgetMillis = 700L)
            val simulation = GameWorld()
            simulation.applySnapshot(world.snapshot())
            assertTrue(simulation.shoot(plan.angle, plan.power, 0.0, 0.0))
            repeat(2400) { simulation.step(1.0 / 120.0) }
            if (!simulation.balls[9].active) pots++
        }
        println("extreme pot rate $pots/${layouts.size}")
        assertTrue("extreme AI should pot at least 9/10", pots >= 9)
    }
}
