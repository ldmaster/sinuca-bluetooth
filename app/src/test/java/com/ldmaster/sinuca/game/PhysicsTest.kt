package com.ldmaster.sinuca.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PhysicsTest {

    private fun emptyTable(): GameWorld {
        val world = GameWorld()
        for (ball in world.balls) {
            if (ball.number != 0) {
                ball.active = false
                ball.stop()
            }
        }
        return world
    }

    @Test
    fun `head on collision transfers motion`() {
        val world = emptyTable()
        val cue = world.balls[0]
        val target = world.balls[1]
        cue.x = -0.5
        cue.z = 0.0
        target.x = -0.5 + Table.BALL_RADIUS * 2.0 + 0.001
        target.z = 0.0
        target.active = true

        assertTrue(world.shoot(0.0, 0.15))
        repeat(30) { world.step(1.0 / 120.0) }

        assertTrue("target should move forward", target.x > -0.5 + Table.BALL_RADIUS * 2.0)
        assertTrue("target should carry the speed", target.vx > cue.vx)
    }

    @Test
    fun `cushion reverses velocity`() {
        val world = emptyTable()
        val cue = world.balls[0]
        cue.x = 1.20
        cue.z = 0.0

        assertTrue(world.shoot(0.0, 0.2))
        repeat(40) { world.step(1.0 / 120.0) }

        assertTrue("ball must bounce back", cue.vx < 0.0)
        assertTrue(cue.x <= Table.HALF_LENGTH - Table.BALL_RADIUS + 1e-6)
    }

    @Test
    fun `ball drops into a pocket and cue respawns`() {
        val world = emptyTable()
        val cue = world.balls[0]
        cue.x = 1.26
        cue.z = 0.62

        assertTrue(world.shoot(0.0, 0.1))
        repeat(240) { world.step(1.0 / 120.0) }

        assertFalse(world.moving)
        assertEquals(1, world.turn)
        assertTrue(world.balls[0].active)
        assertTrue(hypot(world.balls[0].x - Table.CUE_SPOT[0], world.balls[0].z - Table.CUE_SPOT[1]) < 1e-6)
    }

    @Test
    fun `friction brings every ball to rest`() {
        val world = emptyTable()
        assertTrue(world.shoot(0.3, 0.01))
        repeat(2000) { world.step(1.0 / 120.0) }
        assertFalse(world.moving)
        assertTrue(world.balls[0].speed < 1e-9)
    }
}
