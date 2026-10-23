package com.ldmaster.sinuca.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AiPlannerTest {

    private fun tableWith(cueX: Double, cueZ: Double, number: Int, x: Double, z: Double): GameWorld {
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
    fun `hard ai pots a reachable stripe`() {
        val world = tableWith(-0.60, 0.10, 9, 0.55, 0.10)
        val plan = AiPlanner(AiLevel.HARD, Random(1)).plan(world.snapshot(), 1)

        val simulation = GameWorld()
        simulation.applySnapshot(world.snapshot())
        assertTrue(simulation.shoot(plan.angle, plan.power, 0.0, 0.0))
        repeat(2400) { simulation.step(1.0 / 120.0) }

        assertFalse("the planned shot should pot the target", simulation.balls[9].active)
    }

    @Test
    fun `after clearing the group the ai targets the eight`() {
        val world = tableWith(-0.60, 0.10, 8, 0.55, 0.10)
        val plan = AiPlanner(AiLevel.HARD, Random(2)).plan(world.snapshot(), 1)

        val simulation = GameWorld()
        simulation.applySnapshot(world.snapshot())
        assertTrue(simulation.shoot(plan.angle, plan.power, 0.0, 0.0))
        repeat(2400) { simulation.step(1.0 / 120.0) }

        assertFalse("the eight should be potted", simulation.balls[8].active)
        assertEquals(1, simulation.winner)
    }

    @Test
    fun `difficulty levels tune the search and the error`() {
        assertTrue(AiLevel.EASY.maxCandidates < AiLevel.MEDIUM.maxCandidates)
        assertTrue(AiLevel.MEDIUM.maxCandidates < AiLevel.HARD.maxCandidates)
        assertTrue(AiLevel.EASY.aimNoiseDegrees > AiLevel.MEDIUM.aimNoiseDegrees)
        assertTrue(AiLevel.MEDIUM.aimNoiseDegrees > AiLevel.HARD.aimNoiseDegrees)
    }
}
