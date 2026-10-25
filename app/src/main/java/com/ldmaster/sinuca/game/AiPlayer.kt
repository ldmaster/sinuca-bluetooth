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
    val targetNumber: Int = -1,
    val kind: AiShotKind = AiShotKind.POT
)

enum class AiShotKind { POT, SAFETY }

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
    val learningWeight: Double,
    /** When no pot is available, look for a defensive shot instead. */
    val safetyEnabled: Boolean
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
        learningWeight = 0.0,
        safetyEnabled = false
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
        learningWeight = 0.0,
        safetyEnabled = false
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
        learningWeight = 1.0,
        safetyEnabled = true
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
        learningWeight = 1.6,
        safetyEnabled = true
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
    @Volatile
    private var pendingSafety: PendingSafety? = null

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
                if (shot.kind == AiShotKind.SAFETY) {
                    shot.features?.let {
                        pendingSafety = PendingSafety(it, countOpponent(snapshot))
                    }
                } else {
                    shot.features?.let {
                        pending = PendingShot(it, ownBefore, shot.targetNumber)
                    }
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
        resolvePendingPot(snapshot)
        resolvePendingSafety(snapshot)
    }

    private fun resolvePendingPot(snapshot: WorldSnapshot) {
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

    /**
     * A safety succeeds when the opponent's visit ends without potting. The
     * model only learns after the opponent actually played, so the signal is
     * real and not just "the shot did not scratch".
     */
    private fun resolvePendingSafety(snapshot: WorldSnapshot) {
        val safety = pendingSafety ?: return
        if (snapshot.winner >= 0) {
            memory?.recordSafety(level, safety.features, snapshot.winner == player)
            pendingSafety = null
            return
        }
        if (snapshot.turn != player) {
            safety.opponentSeen = true
            safety.ticks = 0
            return
        }
        if (!safety.opponentSeen) {
            if (safety.ticks++ > SETTLE_TICKS) pendingSafety = null
            return
        }
        if (snapshot.moving) return
        val opponentAfter = countOpponent(snapshot)
        memory?.recordSafety(level, safety.features, opponentAfter >= safety.opponentBefore)
        pendingSafety = null
    }

    private fun countOpponent(snapshot: WorldSnapshot): Int =
        snapshot.balls.count { it.active && !it.falling && groupOf(it.number) == 1 - player }

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

    private class PendingSafety(val features: ShotFeatures, val opponentBefore: Int) {
        var opponentSeen = false
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
            if (level.safetyEnabled) {
                val safety = planSafety(snapshot, player, deadline)
                if (safety != null) return toShot(safety, AiShotKind.SAFETY)
            }
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
            val result = simulateAndScore(candidate, player)
            scored.add(Scored(candidate, result.first, result.second))
        }
        if (scored.isEmpty()) return AiShot(0.0, 0.3)
        scored.sortByDescending { it.score }

        var best = scored.first()
        if (level.refineRounds > 0) {
            best = refine(snapshot, player, scored, best, deadline)
        }

        // No pot available: a defensive shot that hides the cue or blocks the
        // opponent is worth more than a hopeful miss.
        if (level.safetyEnabled && best.potted == 0) {
            val safety = planSafety(snapshot, player, deadline)
            if (safety != null) return toShot(safety, AiShotKind.SAFETY)
        }

        val pick = if (best === scored.first() && scored.size > 1 &&
            random.nextDouble() < level.mistakeChance
        ) {
            val last = minOf(scored.size - 1, 3)
            scored[1 + random.nextInt(last)]
        } else {
            best
        }
        return toShot(pick, AiShotKind.POT)
    }

    private fun toShot(pick: Scored, kind: AiShotKind): AiShot {
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
            targetNumber = pick.candidate.targetNumber,
            kind = kind
        )
    }

    /**
     * Defensive candidates: nudge an own ball softly, or send the cue to a rail.
     * The score rewards leaving the opponent with no clean line and the cue
     * tucked against a cushion, which is the "trap" the player feels.
     */
    private fun planSafety(
        snapshot: WorldSnapshot,
        player: Int,
        deadline: Long
    ): Scored? {
        simulation.applySnapshot(snapshot)
        val cue = simulation.balls[0]
        val ownGroup = if (player == 0) 1..7 else 9..15
        val own = simulation.balls.filter { it.active && !it.falling && it.number in ownGroup }
        val targets = if (own.isEmpty()) {
            simulation.balls.filter { it.number == 8 && it.active }
        } else {
            own
        }
        if (targets.isEmpty()) return null

        val angles = ArrayList<Double>(24)
        for (target in targets.take(3)) {
            val base = atan2(target.z - cue.z, target.x - cue.x)
            angles.add(base)
            angles.add(base + Math.toRadians(5.0))
            angles.add(base - Math.toRadians(5.0))
        }
        for (step in 0 until 8) angles.add(Math.PI * 2.0 * step / 8.0)

        var best: Scored? = null
        for (angle in angles) {
            for (power in doubleArrayOf(0.22, 0.30)) {
                if (System.nanoTime() > deadline) return best
                simulation.applySnapshot(snapshot)
                if (!simulation.shoot(angle, power)) continue
                settle()
                val score = scoreSafety(player, angle, power)
                val candidate = Candidate(
                    targetNumber = -1,
                    angle = angle,
                    power = power,
                    cosCut = 0.0,
                    distance = 0.0,
                    heuristic = 0.0
                )
                if (best == null || score > best.score) {
                    best = Scored(candidate, score, 0)
                }
            }
        }
        return best
    }

    private fun scoreSafety(player: Int, angle: Double, power: Double): Double {
        val cue = simulation.balls[0]
        if (cue.falling) return -5000.0
        val opponent = 1 - player
        val options = cleanShots(opponent)
        val hidden = hiddenOpponentBalls(opponent)
        val rail = minOf(
            Table.HALF_LENGTH - kotlin.math.abs(cue.x),
            Table.HALF_WIDTH - kotlin.math.abs(cue.z)
        ).coerceAtLeast(0.0)
        var score = 400.0 -
            options * 320.0 +
            hidden * 70.0 +
            rail.coerceIn(0.0, 0.15) * 1500.0
        if (memory != null && level.learningWeight > 0.0) {
            val features = ShotFeatures(0.0, 0.0, power)
            val learned = memory.expectedSafety(level, features)
            score += (learned - 0.5) * level.learningWeight * 500.0
        }
        return score
    }

    /** How many of the opponent's balls still have a direct pocket line. */
    private fun cleanShots(player: Int): Int {
        val cue = simulation.balls[0]
        val ownGroup = if (player == 0) 1..7 else 9..15
        val own = simulation.balls.filter { it.active && !it.falling && it.number in ownGroup }
        val targets = if (own.isEmpty()) {
            simulation.balls.filter { it.number == 8 && it.active }
        } else {
            own
        }
        return targets.count { hasCleanLine(cue, it) }
    }

    private fun hasCleanLine(cue: Ball, target: Ball): Boolean {
        for (pocket in Table.POCKETS) {
            val dx = pocket.x - target.x
            val dz = pocket.z - target.z
            val toPocket = hypot(dx, dz)
            if (toPocket < Table.BALL_RADIUS * 2.0) continue
            val ux = dx / toPocket
            val uz = dz / toPocket
            val ghostX = target.x - ux * Table.BALL_RADIUS * 2.0
            val ghostZ = target.z - uz * Table.BALL_RADIUS * 2.0
            val ax = ghostX - cue.x
            val az = ghostZ - cue.z
            val toGhost = hypot(ax, az)
            if (toGhost < Table.BALL_RADIUS * 2.0) continue
            val cosCut = (ax / toGhost) * ux + (az / toGhost) * uz
            if (cosCut < MIN_CUT_COS) continue
            if (!pathClear(cue.x, cue.z, ghostX, ghostZ, target.number)) continue
            if (!pathClear(target.x, target.z, pocket.x, pocket.z, target.number)) continue
            return true
        }
        return false
    }

    private fun hiddenOpponentBalls(player: Int): Int {
        val cue = simulation.balls[0]
        val ownGroup = if (player == 0) 1..7 else 9..15
        return simulation.balls.count { ball ->
            ball.active && !ball.falling && ball.number in ownGroup &&
                !pathClear(cue.x, cue.z, ball.x, ball.z, ball.number)
        }
    }

    private fun settle() {
        var steps = 0
        while (simulation.moving && steps < MAX_STEPS) {
            simulation.step(STEP_SECONDS)
            steps++
        }
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
                        val result = simulateAndScore(candidate, player)
                        if (result.first > best.score) {
                            best = Scored(candidate, result.first, result.second)
                        }
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

    private fun simulateAndScore(candidate: Candidate, player: Int): Pair<Double, Int> {
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

        // A finished rack is a converted shot even when the winning ball was the
        // eight, which belongs to no group.
        if (simulation.winner == player) return 1_000_000.0 to 1
        if (simulation.winner >= 0) return -1_000_000.0 to 1

        val ownAfter = countGroup(player)
        val opponentAfter = countGroup(1 - player)
        val potted = (ownBefore - ownAfter).coerceAtLeast(0)
        // The eight is nobody's group, so a converted target also counts as a pot
        // for the "is there a pot available?" decision.
        val converted = if (!target.active || target.falling) maxOf(potted, 1) else potted
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
        return score to converted
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

    private data class Scored(
        val candidate: Candidate,
        val score: Double,
        val potted: Int
    )

    private companion object {
        const val MAX_STEPS = 900
        const val STEP_SECONDS = 1.0 / 120.0
        const val MIN_CUT_COS = 0.20
    }
}
