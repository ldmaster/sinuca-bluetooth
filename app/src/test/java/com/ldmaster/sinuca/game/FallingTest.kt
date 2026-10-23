package com.ldmaster.sinuca.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallingTest {

    private fun worldWithCueAndTarget(): Triple<GameWorld, Ball, Ball> {
        val world = GameWorld()
        for (ball in world.balls) {
            if (ball.number != 0 && ball.number != 9) ball.active = false
        }
        val cue = world.balls[0]
        cue.x = -0.7
        cue.z = 0.0
        val target = world.balls[9]
        target.x = 1.26
        target.z = 0.62
        target.vx = 0.2
        target.active = true
        return Triple(world, cue, target)
    }

    @Test
    fun `pocketed ball animates down before deactivating`() {
        val (world, _, target) = worldWithCueAndTarget()
        world.shoot(0.0, 0.01)
        world.step(1.0 / 120.0)

        assertTrue("ball should be falling", target.falling)
        assertTrue("ball stays active during the fall", target.active)

        repeat(10) { world.step(1.0 / 120.0) }
        assertTrue("fall progress should advance", target.fall > 0f)

        repeat(60) { world.step(1.0 / 120.0) }
        assertFalse(target.falling)
        assertFalse(target.active)

        repeat(2000) { world.step(1.0 / 120.0) }
        assertFalse(world.moving)
        assertEquals(1, world.turn)
    }

    @Test
    fun `fall progress is part of the snapshot`() {
        val (world, _, target) = worldWithCueAndTarget()
        world.shoot(0.0, 0.01)
        world.step(1.0 / 120.0)
        repeat(10) { world.step(1.0 / 120.0) }

        val snapshot = world.snapshot()
        assertTrue(snapshot.balls[9].falling)
        assertTrue("falling ball already shows as potted", 9 in snapshot.potted1)
        val copy = GameWorld()
        copy.applySnapshot(snapshot)
        assertTrue(copy.balls[9].falling)
        assertEquals(target.fall, copy.balls[9].fall, 1e-6f)
    }
}
