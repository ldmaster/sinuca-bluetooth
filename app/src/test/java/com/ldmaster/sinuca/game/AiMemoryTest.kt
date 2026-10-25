package com.ldmaster.sinuca.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMemoryTest {

    private val features = ShotFeatures(cutCos = 0.9, distance = 0.5, power = 0.5)

    @Test
    fun `an unplayed family expects the neutral prior`() {
        val memory = AiMemory(InMemoryStore())

        assertEquals(ShotFeatures.PRIOR, memory.expectedPots(AiLevel.HARD, features), 1e-9)
        assertEquals(0, memory.attempts(AiLevel.HARD))
    }

    @Test
    fun `successful shots raise the expectation and failures lower it`() {
        val memory = AiMemory(InMemoryStore())
        repeat(8) { memory.record(AiLevel.HARD, features, 1) }
        val afterSuccess = memory.expectedPots(AiLevel.HARD, features)

        val other = AiMemory(InMemoryStore())
        repeat(8) { other.record(AiLevel.HARD, features, 0) }
        val afterFailure = other.expectedPots(AiLevel.HARD, features)

        assertTrue("success should learn up", afterSuccess > ShotFeatures.PRIOR + 0.2)
        assertTrue("failure should learn down", afterFailure < ShotFeatures.PRIOR - 0.2)
    }

    @Test
    fun `learning is kept per level`() {
        val memory = AiMemory(InMemoryStore())
        repeat(6) { memory.record(AiLevel.EXTREME, features, 1) }

        assertTrue(memory.expectedPots(AiLevel.EXTREME, features) > 0.7)
        assertEquals(ShotFeatures.PRIOR, memory.expectedPots(AiLevel.HARD, features), 1e-9)
        assertEquals(0, memory.attempts(AiLevel.HARD))
    }

    @Test
    fun `easy and medium do not learn`() {
        val memory = AiMemory(InMemoryStore())
        repeat(5) { memory.record(AiLevel.EASY, features, 1) }
        repeat(5) { memory.record(AiLevel.MEDIUM, features, 1) }

        assertEquals(0, memory.attempts(AiLevel.EASY))
        assertEquals(0, memory.attempts(AiLevel.MEDIUM))
    }

    @Test
    fun `multi pot shots count more than one`() {
        val memory = AiMemory(InMemoryStore())
        repeat(4) { memory.record(AiLevel.HARD, features, 2) }

        assertTrue(memory.pots(AiLevel.HARD) == 8)
        assertTrue(memory.potRate(AiLevel.HARD) > 1.0)
    }

    @Test
    fun `the model survives a reload`() {
        val store = InMemoryStore()
        val memory = AiMemory(store)
        repeat(10) { memory.record(AiLevel.EXTREME, features, 1) }
        val learned = memory.expectedPots(AiLevel.EXTREME, features)

        val reloaded = AiMemory(store)

        assertEquals(10, reloaded.attempts(AiLevel.EXTREME))
        assertEquals(learned, reloaded.expectedPots(AiLevel.EXTREME, features), 1e-9)
    }

    @Test
    fun `reset clears one level without touching the others`() {
        val store = InMemoryStore()
        val memory = AiMemory(store)
        repeat(4) { memory.record(AiLevel.HARD, features, 1) }
        repeat(4) { memory.record(AiLevel.EXTREME, features, 1) }

        memory.reset(AiLevel.HARD)

        assertEquals(0, memory.attempts(AiLevel.HARD))
        assertEquals(4, memory.attempts(AiLevel.EXTREME))
    }

    @Test
    fun `shot features bucket into distinct cells`() {
        val thin = ShotFeatures(cutCos = 0.25, distance = 0.2, power = 0.3)
        val square = ShotFeatures(cutCos = 0.95, distance = 2.4, power = 0.9)

        assertTrue(thin.key != square.key)
        assertTrue(thin.key in 0 until ShotFeatures.CELLS)
        assertTrue(square.key in 0 until ShotFeatures.CELLS)
    }
}
