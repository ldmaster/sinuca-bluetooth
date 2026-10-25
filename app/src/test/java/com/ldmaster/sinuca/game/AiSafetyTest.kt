package com.ldmaster.sinuca.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AiSafetyTest {

    /** The cue ball's only object ball is boxed in by opponent balls. */
    private fun trappedLayout(): GameWorld {
        val world = GameWorld()
        for (ball in world.balls) {
            ball.active = false
            ball.stop()
        }
        val cue = world.balls[0]
        cue.active = true
        cue.x = -0.80
        cue.z = 0.00
        cue.stop()

        val target = world.balls[9]
        target.active = true
        target.falling = false
        target.fall = 0f
        target.x = 0.55
        target.z = 0.00
        target.stop()

        // Opponent balls box the only object ball in.
        val blockerPositions = listOf(0.46 to 0.0, 0.64 to 0.0, 0.55 to 0.075, 0.55 to -0.075)
        blockerPositions.forEachIndexed { index, (x, z) ->
            val ball = world.balls[1 + index]
            ball.active = true
            ball.falling = false
            ball.fall = 0f
            ball.x = x
            ball.z = z
            ball.stop()
        }
        world.resolvePots(emptyList())
        return world
    }

    @Test
    fun `hard ai plays a safety when no pot is available`() {
        val world = trappedLayout()
        val plan = AiPlanner(AiLevel.HARD, Random(7))
            .plan(world.snapshot(), 1, budgetMillis = 700L)

        assertEquals(AiShotKind.SAFETY, plan.kind)
        assertTrue(plan.power in 0.20..0.40)
    }

    @Test
    fun `easy ai keeps the simple fallback instead of a safety`() {
        val world = trappedLayout()
        val plan = AiPlanner(AiLevel.EASY, Random(7))
            .plan(world.snapshot(), 1, budgetMillis = 300L)

        assertEquals(AiShotKind.POT, plan.kind)
    }

    @Test
    fun `safety memory learns which defensive families work`() {
        val memory = AiMemory(InMemoryStore())
        val features = ShotFeatures(cutCos = 0.0, distance = 0.0, power = 0.30)

        repeat(6) { memory.recordSafety(AiLevel.HARD, features, success = true) }
        repeat(2) { memory.recordSafety(AiLevel.HARD, features, success = false) }

        assertEquals(8, memory.safetyAttempts(AiLevel.HARD))
        val expected = memory.expectedSafety(AiLevel.HARD, features)
        assertTrue("a family that usually works should be above neutral", expected > 0.55)
    }

    @Test
    fun `safety learning is per level`() {
        val memory = AiMemory(InMemoryStore())
        val features = ShotFeatures(cutCos = 0.0, distance = 0.0, power = 0.30)
        repeat(4) { memory.recordSafety(AiLevel.EXTREME, features, success = true) }

        assertTrue(memory.expectedSafety(AiLevel.EXTREME, features) > 0.55)
        assertEquals(0.5, memory.expectedSafety(AiLevel.HARD, features), 1e-9)
    }
}
