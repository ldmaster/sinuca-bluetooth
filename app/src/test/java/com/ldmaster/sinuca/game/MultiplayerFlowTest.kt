package com.ldmaster.sinuca.game

import com.ldmaster.sinuca.net.NetMessage
import com.ldmaster.sinuca.net.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiplayerFlowTest {

    @Test
    fun `host and client stay in sync through a shot`() {
        val host = GameWorld()
        val client = GameWorld()

        val shot = Wire.parse(Wire.shot(0.42, 0.7, 0.3, -0.2)) as NetMessage.Shot
        host.shoot(shot.angle, shot.power, shot.strikeX, shot.strikeY)
        client.shoot(shot.angle, shot.power, shot.strikeX, shot.strikeY)

        repeat(180) {
            host.step(1.0 / 120.0)
            client.step(1.0 / 120.0)
        }

        val state = Wire.parse(Wire.state(host.snapshot())) as NetMessage.State
        client.applySnapshot(state.snapshot)

        assertEquals(host.turn, client.turn)
        assertEquals(host.winner, client.winner)
        assertEquals(host.moving, client.moving)
        for (index in 0 until 16) {
            assertEquals(host.balls[index].x, client.balls[index].x, 1e-3)
            assertEquals(host.balls[index].z, client.balls[index].z, 1e-3)
            assertEquals(host.balls[index].active, client.balls[index].active)
        }
    }

    @Test
    fun `turn changes are mirrored by the snapshot`() {
        val host = GameWorld()
        val client = GameWorld()

        host.shoot(0.0, 0.35)
        repeat(3000) { host.step(1.0 / 120.0) }
        assertTrue(!host.moving)

        val state = Wire.parse(Wire.state(host.snapshot())) as NetMessage.State
        client.applySnapshot(state.snapshot)
        assertEquals(host.turn, client.turn)
        assertEquals(host.remainingFor(0), client.remainingFor(0))
        assertEquals(host.remainingFor(1), client.remainingFor(1))
    }
}
