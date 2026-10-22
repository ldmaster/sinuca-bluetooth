package com.ldmaster.sinuca.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {

    private fun world(): GameWorld = GameWorld()

    private fun pot(world: GameWorld, vararg numbers: Int) {
        for (number in numbers) {
            world.balls[number].active = false
            world.balls[number].stop()
        }
    }

    @Test
    fun `potting own ball keeps the turn`() {
        val world = world()
        pot(world, 1)
        world.resolvePots(listOf(1))
        assertEquals(0, world.turn)
        assertEquals(-1, world.winner)
    }

    @Test
    fun `missing every ball passes the turn`() {
        val world = world()
        world.resolvePots(emptyList())
        assertEquals(1, world.turn)
    }

    @Test
    fun `cue ball foul passes the turn and respawns`() {
        val world = world()
        pot(world, 0)
        world.resolvePots(listOf(0))
        assertEquals(1, world.turn)
        assertTrue(world.balls[0].active)
        assertEquals(Table.CUE_SPOT[0], world.balls[0].x, 1e-9)
    }

    @Test
    fun `potting an opponent ball does not keep the turn`() {
        val world = world()
        pot(world, 9)
        world.resolvePots(listOf(9))
        assertEquals(1, world.turn)
    }

    @Test
    fun `eight ball too early loses the game`() {
        val world = world()
        pot(world, 8)
        world.resolvePots(listOf(8))
        assertEquals(1, world.winner)
    }

    @Test
    fun `eight ball after clearing the group wins`() {
        val world = world()
        pot(world, 1, 2, 3, 4, 5, 6, 7, 8)
        world.resolvePots(listOf(8))
        assertEquals(0, world.winner)
    }

    @Test
    fun `cue foul on the eight ball still resolves the eight`() {
        val world = world()
        pot(world, 0, 8)
        world.resolvePots(listOf(0, 8))
        assertEquals(1, world.winner)
    }

    @Test
    fun `second player keeps the turn after potting a stripe`() {
        val world = world()
        world.resolvePots(emptyList())
        assertEquals(1, world.turn)

        pot(world, 15)
        world.resolvePots(listOf(15))
        assertEquals(1, world.turn)
        assertEquals(-1, world.winner)
    }
}
