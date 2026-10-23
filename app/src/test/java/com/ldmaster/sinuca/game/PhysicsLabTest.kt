package com.ldmaster.sinuca.game

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Deterministic physics gate. Every number here is a property of the table
 * model: ball direction must match the ghost-ball prediction, roll distances
 * must follow the stroke power, cushions must keep the angle and pockets must
 * accept exactly the WPA mouth width.
 */
class PhysicsLabTest {

    private val report = StringBuilder()

    private fun line(text: String = "") {
        report.append(text).append('\n')
    }

    private fun emptyTable(): GameWorld {
        val world = GameWorld()
        for (ball in world.balls) {
            if (ball.number != 0) {
                ball.active = false
                ball.stop()
            }
        }
        val cue = world.balls[0]
        cue.x = 0.0
        cue.z = 0.0
        return world
    }

    private fun onlyCueAnd(number: Int): Pair<GameWorld, Ball> {
        val world = GameWorld()
        for (ball in world.balls) {
            if (ball.number != 0 && ball.number != number) {
                ball.active = false
                ball.stop()
            }
        }
        val target = world.balls[number]
        target.active = true
        return world to target
    }

    private fun advanceUntilRest(world: GameWorld, seconds: Double): Double {
        val step = 1.0 / 600.0
        var path = 0.0
        var elapsed = 0.0
        while (world.moving && elapsed < seconds) {
            world.step(step)
            path += world.balls[0].speed * step
            elapsed += step
        }
        return path
    }

    private fun advanceUntilMoving(world: GameWorld, target: Ball): Pair<Double, Double> {
        repeat(6000) {
            world.step(1.0 / 1000.0)
            if (target.speed > 0.03) return Pair(target.vx, target.vz)
        }
        return Pair(0.0, 0.0)
    }

    private fun placeAndLaunch(
        world: GameWorld,
        x: Double,
        z: Double,
        vx: Double,
        vz: Double
    ) {
        val cue = world.balls[0]
        cue.x = x
        cue.z = z
        world.shoot(atan2(vz, vx), 0.05)
        cue.vx = vx
        cue.vz = vz
        cue.spinSide = 0.0
        cue.spinForward = 0.0
    }

    // --------------------------------------------------------------------- //
    // 1. Ghost-ball direction accuracy
    // --------------------------------------------------------------------- //
    private fun ghostDirection() {
        line("## 1. Ghost-ball direction")
        line()
        line("| impact offset (mm) | predicted (deg) | actual (deg) | error (deg) |")
        line("|---:|---:|---:|---:|")

        var worst = 0.0
        for (offsetMm in intArrayOf(0, 8, 16, 24, 32, 40, 48, 54)) {
            val (world, target) = onlyCueAnd(1)
            val cue = world.balls[0]
            cue.x = -0.50
            cue.z = 0.0
            target.x = 0.0
            target.z = 0.06
            val distance = hypot(target.x - cue.x, target.z - cue.z)
            val offset = offsetMm / 1000.0
            val center = atan2(target.z - cue.z, target.x - cue.x)
            val aim = center + asin(offset / distance)
            val contact = Aim.firstContact(cue, world.balls, cos(aim), sin(aim))
            assertTrue("contact must exist at $offsetMm mm", contact != null)
            val predicted = Math.toDegrees(atan2(contact!!.normalZ, contact.normalX))
            world.shoot(aim, 0.35)
            val (vx, vz) = advanceUntilMoving(world, target)
            val actual = Math.toDegrees(atan2(vz, vx))
            val error = abs(actual - predicted)
            worst = maxOf(worst, error)
            line(
                String.format(
                    "| %.0f | %.3f | %.3f | %.3f |",
                    offsetMm.toDouble(), predicted, actual, error
                )
            )
        }
        line()
        assertTrue("ghost direction error must stay below 0.6 deg, got $worst", worst < 0.6)
    }

    // --------------------------------------------------------------------- //
    // 2. Roll distance versus stroke power
    // --------------------------------------------------------------------- //
    private fun rollDistance() {
        line("## 2. Roll distance")
        line()
        line("| power | speed (m/s) | path (m) | expected band (m) |")
        line("|---:|---:|---:|---|")

        val bands = listOf(
            Triple(0.03, 0.15..0.40, "tap"),
            Triple(0.10, 0.65..1.15, "soft"),
            Triple(0.25, 2.10..3.60, "medium"),
            Triple(0.50, 4.20..8.00, "firm"),
            Triple(1.00, 7.00..14.0, "break")
        )
        var previous = -1.0
        for ((power, band, name) in bands) {
            val world = emptyTable()
            val speed = 0.10 + 6.10 * Math.pow(power, 0.80)
            world.shoot(0.0, power)
            val path = advanceUntilRest(world, 60.0)
            line(
                String.format(
                    "| %.2f | %.2f | %.2f | %.2f - %.2f |",
                    power, speed, path, band.start, band.endInclusive
                )
            )
            assertTrue("$name shot rolls $path m, outside $band", path in band)
            assertTrue("distance must increase with power", path > previous)
            previous = path
            assertTrue("cue must stop", !world.moving)
        }
        line()
    }

    // --------------------------------------------------------------------- //
    // 3. Head-on transfer
    // --------------------------------------------------------------------- //
    private fun headOnTransfer() {
        line("## 3. Head-on transfer")
        line()
        val (world, target) = onlyCueAnd(1)
        val cue = world.balls[0]
        cue.x = -0.50
        cue.z = 0.0
        target.x = 0.0
        target.z = 0.0
        world.shoot(0.0, 0.35, 0.0, 0.0)
        var steps = 0
        while (target.speed < 0.05 && steps < 4000) {
            world.step(1.0 / 1000.0)
            steps++
        }
        val transfer = target.speed
        val cueSpeed = cue.speed
        line(String.format("object speed %.3f m/s, cue speed %.3f m/s", transfer, cueSpeed))
        line()
        assertTrue("the object ball must carry most of the speed", transfer > 1.2)
        assertTrue("a centre hit must leave the cue nearly stopped", cueSpeed < 0.20)
    }

    // --------------------------------------------------------------------- //
    // 4. Cushion rebound
    // --------------------------------------------------------------------- //
    private fun cushionRebound() {
        line("## 4. Cushion rebound")
        line()
        line("| incidence (deg) | rebound (deg) | speed ratio |")
        line("|---:|---:|---:|")
        for (incidence in intArrayOf(20, 30, 45, 60, 70)) {
            val world = emptyTable()
            val cue = world.balls[0]
            cue.x = -0.65
            cue.z = 0.15
            world.shoot(Math.toRadians(incidence.toDouble()), 0.30)
            var incidentSpeed = cue.speed
            var previousVz = cue.vz
            var steps = 0
            while (cue.vz > -0.001 && steps < 20000) {
                incidentSpeed = cue.speed
                previousVz = cue.vz
                world.step(1.0 / 2000.0)
                steps++
            }
            assertTrue("ball must rebound off the top cushion", previousVz > 0.0)
            val outgoing = Math.toDegrees(atan2(-cue.vz, cue.vx))
            val incident = Math.toDegrees(atan2(previousVz, cue.vx))
            val ratio = cue.speed / incidentSpeed
            line(String.format("| %.1f | %.2f | %.3f |", incident, outgoing, ratio))
            assertTrue("rebound must keep the angle", abs(incident - outgoing) < 4.0)
            assertTrue("cushion must lose the expected energy", ratio in 0.85..0.99)
        }
        line()
    }

    // --------------------------------------------------------------------- //
    // 5. Pocket acceptance
    // --------------------------------------------------------------------- //
    private fun pocketAcceptance() {
        line("## 5. Pocket acceptance")
        line()
        var accepted = 0
        var widest = 0.0
        var offset = -0.10
        while (offset <= 0.10) {
            val world = emptyTable()
            placeAndLaunch(
                world,
                offset,
                Table.HALF_WIDTH - 0.10,
                0.0,
                0.60
            )
            repeat(400) { world.step(1.0 / 1000.0) }
            val ball = world.balls[0]
            if (ball.falling) {
                accepted++
                widest = maxOf(widest, abs(offset))
            }
            offset += 0.002
        }
        line("side pocket accepted ${accepted} of 101 offsets, half-width ${"%.1f".format(widest * 1000)} mm")
        assertTrue("the side pocket must accept a centred ball", accepted > 20)
        assertTrue(
            "side mouth is 127 mm, acceptance must match, got ${widest * 1000} mm",
            widest in 0.055..0.072
        )

        var cornerFallen = 0
        var cornerWidth = 0.0
        var lateral = 0.0
        while (lateral <= 0.06) {
            val world = emptyTable()
            placeAndLaunch(
                world,
                Table.HALF_LENGTH - 0.22,
                Table.HALF_WIDTH - Table.BALL_RADIUS - 0.001 - lateral,
                0.75,
                0.0
            )
            repeat(500) { world.step(1.0 / 1000.0) }
            if (world.balls[0].falling) {
                cornerFallen++
                cornerWidth = maxOf(cornerWidth, lateral)
            }
            lateral += 0.002
        }
        line("corner accepted ${cornerFallen} rail-hugging offsets, half-width ${"%.1f".format(cornerWidth * 1000)} mm")
        line()
        assertTrue("a ball rolling on the cushion must drop in the corner", cornerFallen > 8)
    }

    private fun jawRejection() {
        val world = emptyTable()
        placeAndLaunch(world, 0.10, Table.HALF_WIDTH - 0.10, 0.0, 0.60)
        repeat(1600) { world.step(1.0 / 1000.0) }
        val ball = world.balls[0]
        line("### Jaw rejection")
        line("ball entering 100 mm off centre active=${ball.active} falling=${ball.falling}")
        line()
        assertTrue("a ball 100 mm off centre must not drop", ball.active && !ball.falling)
    }

    // --------------------------------------------------------------------- //
    // 6. Draw and follow
    // --------------------------------------------------------------------- //
    private fun spinControl() {
        line("## 6. Spin control")
        line()
        line("| shot | cue travel from the contact point (m) |")
        line("|---|---:|")

        fun travel(power: Double, strikeY: Double): Double {
            val (world, target) = onlyCueAnd(1)
            val cue = world.balls[0]
            cue.x = -0.50
            cue.z = 0.0
            target.x = 0.0
            target.z = 0.0
            world.shoot(0.0, power, 0.0, strikeY)
            var contactX = cue.x
            var drawBack = 0.0
            var followThrough = 0.0
            var seen = false
            repeat(900) {
                world.step(1.0 / 600.0)
                if (!seen && target.speed > 0.01) {
                    seen = true
                    contactX = cue.x
                }
                if (seen) {
                    drawBack = maxOf(drawBack, contactX - cue.x)
                    followThrough = maxOf(followThrough, cue.x - contactX)
                }
            }
            return if (strikeY > 0.0) -drawBack else followThrough
        }

        val softDraw = travel(0.10, 0.8)
        val firmDraw = travel(0.35, 0.8)
        val follow = travel(0.35, -0.8)
        line(String.format("| draw 10%% | %.2f |", softDraw))
        line(String.format("| draw 35%% | %.2f |", firmDraw))
        line(String.format("| follow 35%% | %.2f |", follow))
        line()
        assertTrue("a draw shot must come back", firmDraw < -0.20)
        assertTrue("a firmer draw must come back further", firmDraw < softDraw - 0.10)
        assertTrue("a follow shot must move forward", follow > 0.20)
    }

    // --------------------------------------------------------------------- //
    // 7. Stability
    // --------------------------------------------------------------------- //
    private fun stability() {
        line("## 7. Stability")
        line()
        run {
            val (world, target) = onlyCueAnd(1)
            val cue = world.balls[0]
            cue.x = -0.5
            cue.z = 0.0
            target.x = 0.0
            target.z = 0.0
            world.shoot(0.0, 1.0)
            var peak = 0.0
            repeat(120) {
                world.step(1.0 / 120.0)
                peak = maxOf(peak, target.speed)
            }
            line(String.format(
                "object peak speed after a 6.2 m/s hit: %.2f m/s (ends at %.2f, %.2f)",
                peak, target.x, target.z
            ))
            assertTrue("a 6.2 m/s ball must not pass through", peak > 5.0)
        }

        val world = GameWorld()
        world.shoot(0.0, 1.0)
        var worstOverlap = 0.0
        repeat(1200) {
            world.step(1.0 / 240.0)
            for (a in world.balls) {
                if (!a.active || a.falling) continue
                for (b in world.balls) {
                    if (a === b || !b.active || b.falling) continue
                    val overlap = Table.BALL_RADIUS * 2.0 - hypot(a.x - b.x, a.z - b.z)
                    if (overlap > worstOverlap) worstOverlap = overlap
                }
                assertTrue(
                    "ball ${a.number} escaped the frame at (${a.x}, ${a.z})",
                    abs(a.x) < Table.HALF_LENGTH + 0.09 &&
                        abs(a.z) < Table.HALF_WIDTH + 0.09
                )
            }
        }
        line(String.format("worst overlap during the break: %.3f mm", worstOverlap * 1000))
        line()
        assertTrue("balls must never overlap visibly", worstOverlap < 0.0012)
    }

    @Test
    fun `physics lab`() {
        line("# Physics lab")
        line()
        line("Generated by `./gradlew :app:testDebugUnitTest`.")
        line()
        try {
            ghostDirection()
            rollDistance()
            headOnTransfer()
            cushionRebound()
            pocketAcceptance()
            jawRejection()
            spinControl()
            stability()
        } finally {
            val file = File("build/reports/physics-lab.md")
            file.parentFile?.mkdirs()
            file.writeText(report.toString())
        }
    }
}
