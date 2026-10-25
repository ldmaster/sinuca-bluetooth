package com.ldmaster.sinuca.game

import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

data class AiShot(
    val angle: Double,
    val power: Double,
    val features: ShotFeatures? = null,
    val targetNumber: Int = -1
)

enum class AiLevel(
    val label: String,
    val tagline: String,
    val maxCandidates: Int,
    val aimNoiseDegrees: Double,
    val mistakeChance: Double,
    val powerFactor: Double,
    /** Minimum time the player sees "IA PENSANDO" before the shot is taken. */
    val thinkTimeMillis: Long,
    /** How long the search may run inside that window. */
    val searchBudgetMillis: Long,
    /** Local refinement rounds around the best few candidates. */
    val refineRounds: Int,
    /** Extra score per object ball potted beyond the first in the same shot. */
    val multiPotBonus: Double,
    /** How much the per-level learned pot rate influences the search. */
    val learningWeight: Double
) {
    EASY(
        "Fácil",
        "Erra bastante. Boa para aprender a mesa.",
        maxCandidates = 5,
        aimNoiseDegrees = 3.0,
        mistakeChance = 0.35,
        powerFactor = 0.95,
        thinkTimeMillis = 450,
        searchBudgetMillis = 300,
        refineRounds = 0,
        multiPotBonus = 0.0,
        learningWeight = 0.0
    ),
    MEDIUM(
        "Médio",
        "Jogo seguro, encaçapa o óbvio e erra às vezes.",
        maxCandidates = 14,
        aimNoiseDegrees = 0.8,
        mistakeChance = 0.12,
        powerFactor = 0.98,
        thinkTimeMillis = 1100,
        searchBudgetMillis = 800,
        refineRounds = 1,
        multiPotBonus = 60.0,
        learningWeight = 0.0
    ),
    HARD(
        "Difícil",
        "Quase não erra, ajusta força e joga posição.",
        maxCandidates = 40,
        aimNoiseDegrees = 0.0,
        mistakeChance = 0.0,
        powerFactor = 1.0,
        thinkTimeMillis = 2800,
        searchBudgetMillis = 2400,
        refineRounds = 2,
        multiPotBonus = 140.0,
        learningWeight = 1.0
    ),
    EXTREME(
        "Extremo",
        "Procura encaçapar mais de uma bola na mesma tacada e não perdoa.",
        maxCandidates = 110,
        aimNoiseDegrees = 0.0,
        mistakeChance = 0.0,
        powerFactor = 1.0,
        thinkTimeMillis = 5400,
        searchBudgetMillis = 4800,
        refineRounds = 3,
        multiPotBonus = 320.0,
        learningWeight = 1.6
    )
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
    private val memory: AiMemory? = null,
    private val onShot: (AiShot) -> Unit
) {

    @Volatile
    var thinking: Boolean = false
        private set

    private val planner = AiPlanner(level, memory = memory)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sinuca-ai").apply { isDaemon = true }
    }
    @Volatile
    private var released = false
    @Volatile
    private var lastShotAt = 0L
    @Volatile
    private var pending: PendingShot? = null

    fun tick() {
        if (released) return
        val snapshot = synchronized(world) { world.snapshot() }
        resolvePending(snapshot)
        if (thinking) return
        if (System.nanoTime() - lastShotAt < SHOT_COOLDOWN_NS) return
        if (snapshot.winner >= 0 || snapshot.moving || snapshot.turn != player) return

        val ownBefore = countOwn(snapshot)
        thinking = true
        executor.execute {
            val startedAt = System.nanoTime()
            val shot = try {
                planner.plan(snapshot, player, level.searchBudgetMillis)
            } catch (_: Throwable) {
                null
            }
            if (!released) {
                // Hard levels should look like they are studying the table: keep
                // the "IA PENSANDO" badge up for the level's think time even when
                // the search finds its shot sooner.
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000
                val remaining = level.thinkTimeMillis - elapsed
                if (remaining > 0 && !released) {
                    try {
                        Thread.sleep(remaining)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
            if (!released && shot != null) {
                lastShotAt = System.nanoTime()
                shot.features?.let {
                    pending = PendingShot(it, ownBefore, shot.targetNumber)
                }
                onShot(shot)
            }
            thinking = false
        }
    }

    /**
     * Close the loop on the last shot: once the balls settle, compare how many
     * object balls actually dropped with what the shot family predicted and let
     * the memory learn from it.
     */
    private fun resolvePending(snapshot: WorldSnapshot) {
        val shot = pending ?: return
        if (snapshot.moving) {
            shot.seenMoving = true
            return
        }
        if (!shot.seenMoving && shot.ticks++ < SETTLE_TICKS) return
        val ownAfter = countOwn(snapshot)
        // The eight is nobody's group, so also count the intended target ball.
        val targetPotted = snapshot.balls
            .firstOrNull { it.number == shot.targetNumber }
            ?.let { !it.active || it.falling } ?: false
        val potted = maxOf(
            (shot.ownBefore - ownAfter).coerceAtLeast(0),
            if (targetPotted) 1 else 0
        )
        memory?.record(level, shot.features, potted)
        pending = null
    }

    private fun countOwn(snapshot: WorldSnapshot): Int = snapshot.balls.count {
        it.active && !it.falling && groupOf(it.number) == player
    }

    private fun groupOf(number: Int): Int = when (number) {
        0, 8 -> -1
        in 1..7 -> 0
        in 9..15 -> 1
        else -> -1
    }

    fun release() {
        released = true
        thinking = false
        executor.shutdownNow()
    }

    private class PendingShot(
        val features: ShotFeatures,
        val ownBefore: Int,
        val targetNumber: Int
    ) {
        var seenMoving = false
        var ticks = 0
    }

    private companion object {
        const val SHOT_COOLDOWN_NS = 350_000_000L
        const val SETTLE_TICKS = 60
    }
}

class AiPlanner(
    private val level: AiLevel,
    private val random: Random = Random(),
    private val memory: AiMemory? = null
) {

    private val simulation = GameWorld()

    fun plan(snapshot: WorldSnapshot, player: Int, budgetMillis: Long = 900L): AiShot {
        simulation.applySnapshot(snapshot)
        val cue = simulation.balls[0]
        if (!cue.active) return AiShot(0.0, 0.3)
        val deadline = System.nanoTime() + budgetMillis * 1_000_000

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
            if (System.nanoTime() > deadline) break
            simulation.applySnapshot(snapshot)
            if (!simulation.shoot(candidate.angle, candidate.power)) continue
            scored.add(Scored(candidate, simulateAndScore(candidate, player)))
        }
        if (scored.isEmpty()) return AiShot(0.0, 0.3)
        scored.sortByDescending { it.score }

        var best = scored.first()
        if (level.refineRounds > 0) {
            best = refine(snapshot, player, scored, best, deadline)
        }

        val pick = if (best === scored.first() && scored.size > 1 &&
            random.nextDouble() < level.mistakeChance
        ) {
            val last = minOf(scored.size - 1, 3)
            scored[1 + random.nextInt(last)]
        } else {
            best
        }
        val noise = (random.nextDouble() * 2.0 - 1.0) *
            Math.toRadians(level.aimNoiseDegrees)
        val appliedPower = (pick.candidate.power * level.powerFactor).coerceIn(0.20, 0.95)
        return AiShot(
            angle = pick.candidate.angle + noise,
            power = appliedPower,
            features = ShotFeatures(
                cutCos = pick.candidate.cosCut,
                distance = pick.candidate.distance,
                power = appliedPower
            ),
            targetNumber = pick.candidate.targetNumber
        )
    }

    /**
     * Local search around the best shots, with shrinking steps. Each round is
     * cheaper than the previous one, so the budget buys a coarse sweep first and
     * precision only where it matters.
     */
    private fun refine(
        snapshot: WorldSnapshot,
        player: Int,
        scored: List<Scored>,
        initial: Scored,
        deadline: Long
    ): Scored {
        var best = initial
        val angleSteps = doubleArrayOf(1.2, 0.5, 0.2)
        val powerSteps = doubleArrayOf(0.08, 0.04, 0.02)
        val seeds = scored.sortedByDescending { it.score }.take(6)
        for (round in 0 until level.refineRounds) {
            val angleStep = angleSteps[minOf(round, angleSteps.size - 1)]
            val powerStep = powerSteps[minOf(round, powerSteps.size - 1)]
            for (seed in seeds) {
                for (angleSign in doubleArrayOf(-1.0, 1.0)) {
                    for (powerSign in doubleArrayOf(-1.0, 0.0, 1.0)) {
                        if (System.nanoTime() > deadline) return best
                        val angle = seed.candidate.angle +
                            Math.toRadians(angleStep * angleSign)
                        val power = (seed.candidate.power + powerStep * powerSign)
                            .coerceIn(0.20, 0.95)
                        simulation.applySnapshot(snapshot)
                        if (!simulation.shoot(angle, power)) continue
                        val candidate = seed.candidate.copy(angle = angle, power = power)
                        val score = simulateAndScore(candidate, player)
                        if (score > best.score) best = Scored(candidate, score)
                    }
                }
            }
        }
        return best
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

        val lowPower = (basePower - 0.10).coerceAtLeast(0.24)
        val highPower = (basePower + 0.10).coerceAtMost(0.90)
        for (power in doubleArrayOf(lowPower, basePower, highPower)) {
            out.add(
                Candidate(
                    targetNumber = target.number,
                    angle = atan2(ghostZ - cue.z, ghostX - cue.x),
                    power = power,
                    cosCut = cosCut,
                    distance = cueToTarget,
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
        val potted = ownBefore - ownAfter
        var score = 0.0
        if (scratched) score -= 4000.0
        if (!target.active) score += 900.0
        score += potted * 250.0
        if (potted > 1) {
            // Extreme play prefers a shot that drops two balls over two safe ones.
            score += (potted - 1) * level.multiPotBonus
        }
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
        if (memory != null && level.learningWeight > 0.0) {
            val features = ShotFeatures(candidate.cosCut, candidate.distance, candidate.power)
            val learned = memory.expectedPots(level, features)
            score += (learned - ShotFeatures.PRIOR) * level.learningWeight * 600.0
        }
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
        val distance: Double,
        val heuristic: Double
    )

    private data class Scored(val candidate: Candidate, val score: Double)

    private companion object {
        const val MAX_STEPS = 900
        const val STEP_SECONDS = 1.0 / 120.0
        const val MIN_CUT_COS = 0.20
    }
}
