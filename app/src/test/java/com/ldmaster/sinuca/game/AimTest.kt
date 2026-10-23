package com.ldmaster.sinuca.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

class AimTest {

    private fun fixture(): Triple<GameWorld, Ball, Ball> {
        val world = GameWorld()
        for (ball in world.balls) {
            if (ball.number != 0 && ball.number != 1) ball.active = false
        }
        val cue = world.balls[0]
        cue.x = 0.0
        cue.z = 0.0
        val target = world.balls[1]
        target.x = 0.2
        target.z = 0.0
        target.active = true
        return Triple(world, cue, target)
    }

    @Test
    fun `center hit sends the object ball straight`() {
        val (world, cue, _) = fixture()
        val contact = Aim.firstContact(cue, world.balls, 1.0, 0.0)
        assertNotNull(contact)
        assertEquals(1.0, contact!!.normalX, 1e-6)
        assertEquals(0.0, contact.normalZ, 1e-6)
    }

    @Test
    fun `edge hit changes the object ball direction`() {
        val (world, cue, _) = fixture()
        val straight = Aim.firstContact(cue, world.balls, 1.0, 0.0)!!
        val angle = 0.2
        val cut = Aim.firstContact(cue, world.balls, cos(angle), sin(angle))!!
        assertTrue("the cut must change the normal", cut.normalZ < -0.2)
        assertTrue("the cut must reduce the forward component", cut.normalX < straight.normalX)
        assertTrue("the ghost must be the contact point", cut.ghostZ > 0.0)
    }

    @Test
    fun `grazing the far edge points almost sideways`() {
        val (world, cue, target) = fixture()
        val angle = asin(0.056 / 0.2)
        val contact = Aim.firstContact(cue, world.balls, cos(angle), sin(angle))!!
        assertEquals(target, contact.target)
        assertTrue("grazing hit must be a strong cut", abs(contact.normalZ) > 0.7)
    }

    @Test
    fun `no contact when aiming away`() {
        val (world, cue, _) = fixture()
        assertNull(Aim.firstContact(cue, world.balls, -1.0, 0.0))
    }
}
