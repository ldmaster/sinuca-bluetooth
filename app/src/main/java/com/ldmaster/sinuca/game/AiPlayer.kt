package com.ldmaster.sinuca.game

import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

data class AiShot(val angle: Double, val power: Double)

enum class AiLevel(
    val label: String,
    val maxCandidates: Int,
    val aimNoiseDegrees: Double,
    val mistakeChance: Double,
    val powerFactor: Double
) {
    EASY("Fácil", 4, 3.0, 0.35, 0.95),
    MEDIUM("Médio", 8, 0.9, 0.15, 0.98),
    HARD("Difícil", 12, 0.15, 0.0, 1.0)
}

/**
 * Local opponent. It plans on a worker thread from an immutable snapshot and
 * never touches the network. Candidates are screened geometrically, then the
 * best few are played out with the real physics and scored.
 */
class AiPlayer(
    private val world: GameWorld,
    private val player: Int,
    private val level: AiLevel,
    private val onShot: (AiShot) -> Unit
) {

    @Volatile
    var thinking: Boolean = false
        private set

    private val planner = AiPlanner(level)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sinuca-ai").apply { isDaemon = true }
    }
    @Volatile
    private var released = false
    @Volatile
    private var lastShotAt = 0L

    fun tick() {
        if (released || thinking) return
        if (System.nanoTime() - lastShotAt < SHOT_COOLDOWN_NS) return
        val snapshot = synchronized(world) { world.snapshot() }
        if (snapshot.winner >= 0 || snapshot.moving || snapshot.turn != player) return

        thinking = true
        executor.execute {
            val shot = try {
                planner.plan(snapshot, player)
            } catch (_: Throwable) {
                null
            }
            if (!released && shot != null) {
                lastShotAt = System.nanoTime()
                onShot(shot)
            }
            thinking = false
        }
    }

    fun release() {
        released = true
        thinking = false
        executor.shutdownNow()
    }

    private companion object {
        const val SHOT_COOLDOWN_NS = 350_000_000L
    }
}

class AiPlanner(
    private val level: AiLevel,
    private val random: Random = Random()
) {

    private val simulation = GameWorld()

    fun plan(snapshot: WorldSnapshot, player: Int): AiShot {
        simulation.applySnapshot(snapshot)
        val cue = simulation.balls[0]
        if (!cue.active) return AiShot(0.0, 0.3)

        val ownGroup = if (player == 0) 1..7 else 9..15
        val groupActive = simulation.balls.any {
            it.active && !it.falling && it.number in ownGroup
        }
        val targets = if (groupActive) {
            simulation.balls.filter { it.active && !it.falling && it.number in ownGroup }
        } else {
            simulation.balls.filter { it.active && !it.falling && it.number == 8 }
        }
        if (targets.isEmpty()) return AiShot(0.0, 0.3)

        val candidates = ArrayList<Candidate>(96)
        for (target in targets) {
            for (pocket in Table.POCKETS) {
                addCandidates(cue, target, pocket, candidates)
            }
        }
        if (candidates.isEmpty()) {
            val target = targets.minByOrNull {
                hypot(it.x - cue.x, it.z - cue.z)
            } ?: return AiShot(0.0, 0.3)
            return AiShot(
                atan2(target.z - cue.z, target.x - cue.x),
                0.35
            )
        }

        candidates.sortBy { it.heuristic }
        val scored = ArrayList<Scored>(level.maxCandidates)
        for (candidate in candidates.take(level.maxCandidates)) {
            simulation.applySnapshot(snapshot)
            if (!simulation.shoot(candidate.angle, candidate.power)) continue
            scored.add(Scored(candidate, simulateAndScore(candidate, player)))
        }
        if (scored.isEmpty()) return AiShot(0.0, 0.3)
        scored.sortByDescending { it.score }

        val pick = if (scored.size > 1 && random.nextDouble() < level.mistakeChance) {
            val last = minOf(scored.size - 1, 3)
            scored[1 + random.nextInt(last)]
        } else {
            scored.first()
        }
        val noise = (random.nextDouble() * 2.0 - 1.0) *
            Math.toRadians(level.aimNoiseDegrees)
        return AiShot(
            pick.candidate.angle + noise,
            (pick.candidate.power * level.powerFactor).coerceIn(0.20, 0.95)
        )
    }

    private fun addCandidates(
        cue: Ball,
        target: Ball,
        pocket: Table.Pocket,
        out: ArrayList<Candidate>
    ) {
        val dx = pocket.x - target.x
        val dz = pocket.z - target.z
        val targetToPocket = hypot(dx, dz)
        if (targetToPocket < Table.BALL_RADIUS * 2.0) return
        val ux = dx / targetToPocket
        val uz = dz / targetToPocket

        val ghostX = target.x - ux * Table.BALL_RADIUS * 2.0
        val ghostZ = target.z - uz * Table.BALL_RADIUS * 2.0
        if (abs(ghostX) > Table.HALF_LENGTH - Table.BALL_RADIUS ||
            abs(ghostZ) > Table.HALF_WIDTH - Table.BALL_RADIUS
        ) return

        val ax = ghostX - cue.x
        val az = ghostZ - cue.z
        val cueToTarget = hypot(ax, az)
        if (cueToTarget < Table.BALL_RADIUS * 2.0) return
        val cx = ax / cueToTarget
        val cz = az / cueToTarget
        val cosCut = cx * ux + cz * uz
        if (cosCut < MIN_CUT_COS) return

        if (!pathClear(cue.x, cue.z, ghostX, ghostZ, target.number)) return
        if (!pathClear(target.x, target.z, pocket.x, pocket.z, target.number)) return

        val needed = hypot(target.x - cue.x, target.z - cue.z) + targetToPocket
        val basePower = (0.26 + 0.55 * (needed / 3.0)).coerceIn(0.28, 0.85)
        val heuristic = (1.0 - cosCut) * 140.0 +
            needed * 18.0 +
            (if (pocket.corner) 6.0 else 0.0)

        val lowPower = (basePower - 0.08).coerceAtLeast(0.24)
        val highPower = (basePower + 0.08).coerceAtMost(0.90)
        for (power in doubleArrayOf(lowPower, highPower)) {
            out.add(
                Candidate(
                    targetNumber = target.number,
                    angle = atan2(ghostZ - cue.z, ghostX - cue.x),
                    power = power,
                    cosCut = cosCut,
                    heuristic = heuristic + abs(power - basePower) * 2.0
                )
            )
        }
    }

    private fun pathClear(
        x1: Double,
        z1: Double,
        x2: Double,
        z2: Double,
        targetNumber: Int
    ): Boolean {
        for (ball in simulation.balls) {
            if (!ball.active || ball.falling) continue
            if (ball.number == 0 || ball.number == targetNumber) continue
            val distance = pointSegmentDistance(ball.x, ball.z, x1, z1, x2, z2)
            if (distance < Table.BALL_RADIUS * 2.0 - 0.001) return false
        }
        return true
    }

    private fun simulateAndScore(candidate: Candidate, player: Int): Double {
        val cue = simulation.balls[0]
        val target = simulation.balls[candidate.targetNumber]
        val ownBefore = countGroup(player)
        val opponentBefore = countGroup(1 - player)

        var scratched = false
        var steps = 0
        while (simulation.moving && steps < MAX_STEPS) {
            simulation.step(STEP_SECONDS)
            if (cue.falling) scratched = true
            steps++
        }

        if (simulation.winner == player) return 1_000_000.0
        if (simulation.winner >= 0) return -1_000_000.0

        val ownAfter = countGroup(player)
        val opponentAfter = countGroup(1 - player)
        var score = 0.0
        if (scratched) score -= 4000.0
        if (!target.active) score += 900.0
        score += (ownBefore - ownAfter) * 250.0
        score -= (opponentBefore - opponentAfter) * 220.0
        if (simulation.turn == player) score += 350.0
        if (cue.active && !scratched) {
            val rail = minOf(
                Table.HALF_LENGTH - abs(cue.x),
                Table.HALF_WIDTH - abs(cue.z)
            )
            score += rail.coerceIn(0.0, 0.30) * 800.0
        }
        score += (1.0 - candidate.cosCut) * 80.0
        return score
    }

    private fun countGroup(player: Int): Int = simulation.balls.count {
        it.active && !it.falling && groupOf(it.number) == player
    }

    private fun groupOf(number: Int): Int = when (number) {
        0, 8 -> -1
        in 1..7 -> 0
        in 9..15 -> 1
        else -> -1
    }

    private fun pointSegmentDistance(
        px: Double,
        pz: Double,
        x1: Double,
        z1: Double,
        x2: Double,
        z2: Double
    ): Double {
        val ex = x2 - x1
        val ez = z2 - z1
        val lengthSquared = ex * ex + ez * ez
        val t = if (lengthSquared < 1e-12) 0.0 else {
            (((px - x1) * ex + (pz - z1) * ez) / lengthSquared).coerceIn(0.0, 1.0)
        }
        val cx = x1 + ex * t
        val cz = z1 + ez * t
        return hypot(px - cx, pz - cz)
    }

    private data class Candidate(
        val targetNumber: Int,
        val angle: Double,
        val power: Double,
        val cosCut: Double,
        val heuristic: Double
    )

    private data class Scored(val candidate: Candidate, val score: Double)

    private companion object {
        const val MAX_STEPS = 900
        const val STEP_SECONDS = 1.0 / 120.0
        const val MIN_CUT_COS = 0.20
    }
}
