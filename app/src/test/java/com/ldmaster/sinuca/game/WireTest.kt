package com.ldmaster.sinuca.game

import com.ldmaster.sinuca.net.NetMessage
import com.ldmaster.sinuca.net.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {

    @Test
    fun `state round trips`() {
        val world = GameWorld()
        world.shoot(0.5, 0.8)
        repeat(5) { world.step(1.0 / 120.0) }
        val encoded = Wire.state(world.snapshot())
        val message = Wire.parse(encoded)

        assertTrue(message is NetMessage.State)
        val snapshot = (message as NetMessage.State).snapshot
        assertEquals(world.turn, snapshot.turn)
        assertEquals(world.winner, snapshot.winner)
        assertEquals(16, snapshot.balls.size)
        assertEquals(world.balls[0].x, snapshot.balls[0].x, 1e-3)
        assertEquals(world.balls[5].z, snapshot.balls[5].z, 1e-3)
        assertEquals(world.balls[9].active, snapshot.balls[9].active)
        assertEquals(world.remainingFor(0), snapshot.remaining0)
        assertEquals(world.remainingFor(1), snapshot.remaining1)
    }

    @Test
    fun `shot round trips`() {
        val message = Wire.parse(Wire.shot(-1.23456, 0.73, 0.4, -0.2))
        assertTrue(message is NetMessage.Shot)
        val shot = message as NetMessage.Shot
        assertEquals(-1.2346, shot.angle, 1e-4)
        assertEquals(0.73, shot.power, 1e-4)
        assertEquals(0.4, shot.strikeX, 1e-4)
        assertEquals(-0.2, shot.strikeY, 1e-4)
    }

    @Test
    fun `unknown messages are ignored`() {
        assertEquals(null, Wire.parse("not json"))
        assertEquals(null, Wire.parse("{\"t\":\"nope\"}"))
    }
}
