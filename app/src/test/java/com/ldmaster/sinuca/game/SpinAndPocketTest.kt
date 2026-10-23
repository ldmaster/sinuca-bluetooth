package com.ldmaster.sinuca.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class SpinAndPocketTest {

    private fun tableWithCueAndBall(): Triple<GameWorld, Ball, Ball> {
        val world = GameWorld()
        for (ball in world.balls) {
            if (ball.number != 0 && ball.number != 1) ball.active = false
        }
        val cue = world.balls[0]
        cue.x = -0.55
        cue.z = 0.0
        val target = world.balls[1]
        target.x = -0.40
        target.z = 0.0
        target.active = true
        return Triple(world, cue, target)
    }

    @Test
    fun `center hit stops the cue ball`() {
        val (world, cue, _) = tableWithCueAndBall()
        world.shoot(0.0, 0.3, 0.0, 0.0)
        repeat(40) { world.step(1.0 / 120.0) }
        assertTrue("center hit should leave the cue nearly still", kotlin.math.abs(cue.vx) < 0.35)
    }

    @Test
    fun `bottom hit draws the cue ball back`() {
        val (world, cue, _) = tableWithCueAndBall()
        world.shoot(0.0, 0.3, 0.0, 0.9)
        repeat(40) { world.step(1.0 / 120.0) }
        assertTrue("draw shot should reverse the cue ball", cue.vx < -0.2)
    }

    @Test
    fun `top hit follows through`() {
        val (world, cue, _) = tableWithCueAndBall()
        world.shoot(0.0, 0.3, 0.0, -0.9)
        repeat(40) { world.step(1.0 / 120.0) }
        assertTrue("follow shot should keep moving forward", cue.vx > 0.2)
    }

    @Test
    fun `side spin curves the cue ball`() {
        val world = GameWorld()
        for (ball in world.balls) {
            if (ball.number != 0) ball.active = false
        }
        val cue = world.balls[0]
        cue.x = -0.7
        cue.z = 0.0
        world.shoot(0.0, 0.5, 1.0, 0.0)
        repeat(60) { world.step(1.0 / 120.0) }
        assertTrue("side spin should bend the path", kotlin.math.abs(cue.vz) > 0.02)
    }

    @Test
    fun `a ball on the cloth outside the mouth is not captured`() {
        val world = GameWorld()
        val ball = world.balls[1]
        ball.active = true
        ball.x = 1.20
        ball.z = 0.60
        ball.vx = 0.0
        ball.vz = 0.0
        val potted = world.pocketed(ball)
        assertFalse(potted)
        assertFalse(ball.falling)
    }

    @Test
    fun `fast centered ball still drops`() {
        val world = GameWorld()
        val ball = world.balls[1]
        ball.active = true
        ball.x = 1.27 + 0.01
        ball.z = 0.635
        ball.vx = -4.5
        assertTrue(world.pocketed(ball))
        assertTrue(ball.falling)
    }

    @Test
    fun `slow ball drops even near the jaw`() {
        val world = GameWorld()
        val ball = world.balls[1]
        ball.active = true
        ball.x = 1.27 + 0.05
        ball.z = 0.635
        ball.vx = -0.4
        assertTrue(world.pocketed(ball))
        assertTrue(ball.falling)
    }
}
