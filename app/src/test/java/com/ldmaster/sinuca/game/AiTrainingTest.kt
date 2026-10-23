package com.ldmaster.sinuca.game

import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AiTrainingTest {

    private data class Scenario(
        val id: String,
        val cueX: Double,
        val cueZ: Double,
        val target: Int,
        val targetX: Double,
        val targetZ: Double,
        val obstacles: List<Triple<Int, Double, Double>>,
        val expected: String
    )

    private fun load(): List<Scenario> {
        val raw = javaClass.classLoader
            .getResourceAsStream("ai_training.json")!!
            .bufferedReader()
            .use { it.readText() }
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val cue = item.getJSONArray("cue")
            val targetPos = item.getJSONArray("targetPos")
            val obstacles = ArrayList<Triple<Int, Double, Double>>()
            val obstacleJson = item.optJSONArray("obstacles") ?: JSONArray()
            for (i in 0 until obstacleJson.length()) {
                val obstacle = obstacleJson.getJSONArray(i)
                obstacles.add(
                    Triple(
                        obstacle.getInt(0),
                        obstacle.getDouble(1),
                        obstacle.getDouble(2)
                    )
                )
            }
            Scenario(
                id = item.getString("id"),
                cueX = cue.getDouble(0),
                cueZ = cue.getDouble(1),
                target = item.getInt("target"),
                targetX = targetPos.getDouble(0),
                targetZ = targetPos.getDouble(1),
                obstacles = obstacles,
                expected = item.getString("expected")
            )
        }
    }

    private fun tableWith(scenario: Scenario): GameWorld {
        val world = GameWorld()
        for (ball in world.balls) {
            if (ball.number != 0) {
                ball.active = false
                ball.stop()
            }
        }
        val cue = world.balls[0]
        cue.x = scenario.cueX
        cue.z = scenario.cueZ
        cue.active = true
        cue.falling = false
        cue.fall = 0f
        cue.stop()

        val target = world.balls[scenario.target]
        target.active = true
        target.falling = false
        target.fall = 0f
        target.x = scenario.targetX
        target.z = scenario.targetZ
        target.stop()

        for ((number, x, z) in scenario.obstacles) {
            val obstacle = world.balls[number]
            obstacle.active = true
            obstacle.falling = false
            obstacle.fall = 0f
            obstacle.x = x
            obstacle.z = z
            obstacle.stop()
        }
        world.resolvePots(emptyList())
        return world
    }

    @Test
    fun `hard ai passes the offline training pack`() {
        val scenarios = load()
        var potCases = 0
        var pots = 0
        var legalCases = 0
        var legal = 0
        var scratches = 0
        var eightCases = 0
        var eights = 0

        scenarios.forEachIndexed { index, scenario ->
            val world = tableWith(scenario)
            val plan = AiPlanner(AiLevel.HARD, Random(index.toLong() + 1))
                .plan(world.snapshot(), 1)
            val simulation = GameWorld()
            simulation.applySnapshot(world.snapshot())
            assertTrue("shot should be accepted for ${scenario.id}", simulation.shoot(plan.angle, plan.power, 0.0, 0.0))
            repeat(2400) { simulation.step(1.0 / 120.0) }

            val cue = simulation.balls[0]
            val scratched = cue.falling || !cue.active
            val targetPotted = !simulation.balls[scenario.target].active
            val cueMoved = cue.x != scenario.cueX || cue.z != scenario.cueZ
            val legalShot = !scratched && (cueMoved || targetPotted)
            if (scratched) scratches++

            when (scenario.expected) {
                "pot" -> {
                    potCases++
                    if (targetPotted) pots++
                }
                "legal" -> {
                    legalCases++
                    if (legalShot) legal++
                }
                "eight" -> {
                    eightCases++
                    if (!simulation.balls[8].active) eights++
                }
            }
            println(
                "training ${scenario.id} expected=${scenario.expected} " +
                    "potted=$targetPotted scratched=$scratched legal=$legalShot " +
                    "angle=%.2f power=%.3f".format(Math.toDegrees(plan.angle), plan.power)
            )
        }

        println(
            "hard training: pot $pots/$potCases, legal $legal/$legalCases, " +
                "eight $eights/$eightCases, scratch $scratches"
        )
        assertTrue("hard pot rate should be >= 8/9", pots >= potCases - 1)
        assertTrue("hard should make every safety legal", legal >= legalCases)
        assertTrue("hard should not scratch in training", scratches == 0)
        assertTrue("hard should finish the 8", eights == eightCases)
    }
}
