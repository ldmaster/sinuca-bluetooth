package com.ldmaster.sinuca.game

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

data class BallSnapshot(
    val number: Int,
    val x: Double,
    val z: Double,
    val vx: Double,
    val vz: Double,
    val active: Boolean,
    val falling: Boolean = false,
    val fall: Float = 0f
)

data class WorldSnapshot(
    val turn: Int,
    val winner: Int,
    val moving: Boolean,
    val message: String,
    val balls: List<BallSnapshot>,
    val remaining0: Int,
    val remaining1: Int
) {
    val potted0: List<Int>
        get() = balls
            .filter { it.number in 1..7 && (!it.active || it.falling) }
            .map { it.number }

    val potted1: List<Int>
        get() = balls
            .filter { it.number in 9..15 && (!it.active || it.falling) }
            .map { it.number }
}

class GameWorld(val practice: Boolean = false) {

    val balls = ArrayList<Ball>(16)
    var turn = 0
        private set
    var winner = -1
        private set
    var moving = false
        private set
    var message = ""
        private set
    var aimAngle = 0.0

    private val pendingPotted = ArrayList<Int>(4)

    init {
        rack()
    }

    fun rack() {
        balls.clear()
        for (number in 0..15) balls.add(Ball(number, 0.0, 0.0, active = false))
        turn = 0
        winner = -1
        moving = false
        message = "Jogador 1 começa."
        pendingPotted.clear()

        val cue = balls[0]
        cue.x = Table.CUE_SPOT[0]
        cue.z = Table.CUE_SPOT[1]
        cue.active = true
        cue.falling = false
        cue.fall = 0f
        cue.spinForward = 0.0
        cue.spinSide = 0.0
        cue.stop()

        val rows = listOf(
            intArrayOf(1),
            intArrayOf(9, 2),
            intArrayOf(10, 8, 3),
            intArrayOf(11, 4, 12, 5),
            intArrayOf(6, 13, 14, 7, 15)
        )
        val spacing = Table.BALL_RADIUS * 2.0 + 0.0004
        val rowStep = spacing * 0.8660254
        val apexX = 0.635
        for (row in rows.indices) {
            val x = apexX + row * rowStep
            for (index in rows[row].indices) {
                val number = rows[row][index]
                val z = (index - row / 2.0) * spacing
                val ball = balls[number]
                ball.x = x
                ball.z = z
                ball.active = true
                ball.falling = false
                ball.fall = 0f
                ball.stop()
            }
        }
    }

    fun canShoot(playerId: Int): Boolean {
        if (winner >= 0 || moving) return false
        return practice || playerId == turn
    }

    fun cueBall(): Ball = balls[0]

    fun shoot(
        angle: Double,
        power: Double,
        strikeX: Double = 0.0,
        strikeY: Double = 0.0
    ): Boolean {
        if (winner >= 0 || moving) return false
        val cue = balls[0]
        if (!cue.active) return false
        val p = power.coerceIn(0.0, 1.0)
        val speed = MIN_SHOT_SPEED + (MAX_SHOT_SPEED - MIN_SHOT_SPEED) *
            Math.pow(p, POWER_EXPONENT)
        cue.vx = cos(angle) * speed
        cue.vz = sin(angle) * speed
        cue.spinSide = strikeX.coerceIn(-1.0, 1.0)
        cue.spinForward = (-strikeY).coerceIn(-1.0, 1.0)
        cue.shotDirX = cos(angle)
        cue.shotDirZ = sin(angle)
        moving = true
        pendingPotted.clear()
        return true
    }

    fun step(dt: Double) {
        if (!moving) return
        val frame = dt.coerceIn(0.0, 0.05)
        if (frame <= 0.0) return
        advanceFalls(frame)
        var topSpeed = 0.0
        for (ball in balls) {
            if (ball.active && !ball.falling && ball.speed > topSpeed) topSpeed = ball.speed
        }
        val substeps = ceil(topSpeed * frame / MAX_SUBSTEP_DISTANCE)
            .toInt()
            .coerceIn(1, MAX_SUBSTEPS)
        val h = frame / substeps
        repeat(substeps) { integrate(h) }
        if (balls.all { (!it.active || it.speed < STOP_SPEED) && !it.falling }) {
            for (ball in balls) if (ball.speed < STOP_SPEED) ball.stop()
            moving = false
            resolvePots(pendingPotted.toList())
            pendingPotted.clear()
        }
    }

    private fun advanceFalls(dt: Double) {
        for (ball in balls) {
            if (!ball.falling) continue
            ball.fall = (ball.fall + (dt / FALL_DURATION).toFloat()).coerceAtMost(1f)
            val pull = (dt * 12.0).coerceAtMost(1.0)
            ball.x += (ball.dropX - ball.x) * pull
            ball.z += (ball.dropZ - ball.z) * pull
            if (ball.fall >= 1f) {
                ball.falling = false
                ball.active = false
            }
        }
    }

    private fun integrate(h: Double) {
        for (ball in balls) {
            if (!ball.active || ball.falling) continue
            val speed = ball.speed
            if (speed > 0.0) {
                val decel = ROLL_DECELERATION +
                    LINEAR_DRAG * speed +
                    QUADRATIC_DRAG * speed * speed
                val next = max(0.0, speed - decel * h)
                ball.vx = ball.vx / speed * next
                ball.vz = ball.vz / speed * next
            }
            ball.x += ball.vx * h
            ball.z += ball.vz * h

            if (ball.number == 0 && kotlin.math.abs(ball.spinSide) > 0.02 && speed > 0.05) {
                val perpX = ball.vz / speed
                val perpZ = -ball.vx / speed
                val curve = ball.spinSide * SIDE_SPIN_CURVE * h
                ball.vx += perpX * curve
                ball.vz += perpZ * curve
                ball.spinSide *= kotlin.math.exp(-0.35 * h)
            }

            if (pocketed(ball)) continue
            collideCushions(ball)
        }
        collideBalls()
    }

    private fun collideCushions(ball: Ball) {
        val radius = Table.BALL_RADIUS
        for (cushion in Table.CUSHIONS) {
            val ex = cushion.x2 - cushion.x1
            val ez = cushion.z2 - cushion.z1
            val lengthSquared = ex * ex + ez * ez
            var t = 0.0
            if (lengthSquared > 1e-12) {
                t = ((ball.x - cushion.x1) * ex + (ball.z - cushion.z1) * ez) / lengthSquared
                t = t.coerceIn(0.0, 1.0)
            }
            val pointX = cushion.x1 + ex * t
            val pointZ = cushion.z1 + ez * t
            var dx = ball.x - pointX
            var dz = ball.z - pointZ
            val distanceSquared = dx * dx + dz * dz
            if (distanceSquared >= radius * radius || distanceSquared < 1e-12) continue

            val distance = kotlin.math.sqrt(distanceSquared)
            val nx = dx / distance
            val nz = dz / distance
            ball.x = pointX + nx * radius
            ball.z = pointZ + nz * radius

            val normalSpeed = ball.vx * nx + ball.vz * nz
            if (normalSpeed >= 0.0) continue
            val tangentX = ball.vx - normalSpeed * nx
            val tangentZ = ball.vz - normalSpeed * nz
            ball.vx = tangentX * CUSHION_FRICTION - nx * normalSpeed * CUSHION_RESTITUTION
            ball.vz = tangentZ * CUSHION_FRICTION - nz * normalSpeed * CUSHION_RESTITUTION
            cushionSpin(ball, nx, nz)
        }
    }

    private fun cushionSpin(ball: Ball, nx: Double, nz: Double) {
        if (ball.number != 0 || kotlin.math.abs(ball.spinSide) < 0.05) return
        val tangentX = -nz
        val tangentZ = nx
        val component = ball.vx * tangentX + ball.vz * tangentZ
        val kick = ball.spinSide * kotlin.math.abs(component) * CUSHION_SPIN
        ball.vx += tangentX * kick
        ball.vz += tangentZ * kick
        ball.spinSide *= 0.55
    }

    internal fun pocketed(ball: Ball): Boolean {
        for (pocket in Table.POCKETS) {
            if (!Table.captured(pocket, ball.x, ball.z)) continue

            ball.falling = true
            ball.fall = 0f
            ball.dropX = pocket.x
            ball.dropZ = pocket.z
            ball.stop()
            pendingPotted.add(ball.number)
            return true
        }
        return false
    }

    private fun collideBalls() {
        val diameter = Table.BALL_RADIUS * 2.0
        for (i in balls.indices) {
            val a = balls[i]
            if (!a.active || a.falling) continue
            for (j in i + 1 until balls.size) {
                val b = balls[j]
                if (!b.active || b.falling) continue
                val dx = b.x - a.x
                val dz = b.z - a.z
                val distanceSquared = dx * dx + dz * dz
                if (distanceSquared >= diameter * diameter || distanceSquared < 1e-12) continue
                val distance = kotlin.math.sqrt(distanceSquared)
                val nx = dx / distance
                val nz = dz / distance
                val overlap = diameter - distance
                a.x -= nx * overlap * 0.5
                a.z -= nz * overlap * 0.5
                b.x += nx * overlap * 0.5
                b.z += nz * overlap * 0.5

                val cue = when {
                    a.number == 0 -> a
                    b.number == 0 -> b
                    else -> null
                }
                val cueSpeedBefore = cue?.speed ?: 0.0

                val relative = (b.vx - a.vx) * nx + (b.vz - a.vz) * nz
                if (relative < 0.0) {
                    val impulse = -(1.0 + BALL_RESTITUTION) * relative / 2.0
                    a.vx -= nx * impulse
                    a.vz -= nz * impulse
                    b.vx += nx * impulse
                    b.vz += nz * impulse
                }

                if (cue != null && cue.spinForward != 0.0) {
                    val gain = cueSpeedBefore * DRAW_FOLLOW_GAIN * cue.spinForward
                    cue.vx += cue.shotDirX * gain
                    cue.vz += cue.shotDirZ * gain
                    cue.spinForward = 0.0
                }
            }
        }
    }

    internal fun resolvePots(potted: List<Int>) {
        val shooter = turn
        val pottedSet = potted.toSet()
        val cuePotted = 0 in pottedSet
        if (cuePotted) respawnCue()

        if (8 in pottedSet) {
            val cleared = remainingFor(shooter) == 0
            winner = if (cleared) shooter else 1 - shooter
            message = if (cleared) {
                "Jogador ${shooter + 1} encaçapou a 8 e venceu!"
            } else {
                "A 8 caiu antes da hora. Jogador ${2 - shooter} venceu!"
            }
            return
        }

        if (cuePotted) {
            turn = 1 - turn
            message = "Falta: bola branca encaçapada. Vez do jogador ${turn + 1}."
            return
        }

        val ownBall = pottedSet.any { groupOf(it) == shooter }
        if (ownBall) {
            message = "Boa! Jogador ${shooter + 1} continua."
        } else {
            turn = 1 - turn
            message = if (pottedSet.isEmpty()) {
                "Nada encaçapado. Vez do jogador ${turn + 1}."
            } else {
                "Vez do jogador ${turn + 1}."
            }
        }
    }

    private fun respawnCue() {
        val cue = balls[0]
        var spotX = Table.CUE_SPOT[0]
        val spotZ = Table.CUE_SPOT[1]
        var guard = 0
        while (guard++ < 60) {
            val occupied = balls.any {
                it.number != 0 && it.active &&
                    hypot(it.x - spotX, it.z - spotZ) < Table.BALL_RADIUS * 2.05
            }
            if (!occupied) break
            spotX -= Table.BALL_RADIUS * 2.2
        }
        cue.x = spotX
        cue.z = spotZ
        cue.active = true
        cue.falling = false
        cue.fall = 0f
        cue.spinForward = 0.0
        cue.spinSide = 0.0
        cue.stop()
    }

    fun remainingFor(player: Int): Int =
        balls.count { it.active && groupOf(it.number) == player }

    private fun groupOf(number: Int): Int = when (number) {
        0 -> -1
        8 -> 2
        in 1..7 -> 0
        else -> 1
    }

    fun snapshot(): WorldSnapshot = synchronized(this) {
        WorldSnapshot(
            turn = turn,
            winner = winner,
            moving = moving,
            message = message,
            balls = balls.map {
                BallSnapshot(
                    number = it.number,
                    x = it.x,
                    z = it.z,
                    vx = it.vx,
                    vz = it.vz,
                    active = it.active,
                    falling = it.falling,
                    fall = it.fall
                )
            },
            remaining0 = remainingFor(0),
            remaining1 = remainingFor(1)
        )
    }

    fun applySnapshot(snapshot: WorldSnapshot) = synchronized(this) {
        for (state in snapshot.balls) {
            val ball = balls.getOrNull(state.number) ?: continue
            ball.x = state.x
            ball.z = state.z
            ball.vx = state.vx
            ball.vz = state.vz
            ball.active = state.active
            ball.falling = state.falling
            ball.fall = state.fall
            ball.dropX = state.x
            ball.dropZ = state.z
        }
        turn = snapshot.turn
        winner = snapshot.winner
        moving = snapshot.moving
        message = snapshot.message
    }

    companion object {
        private const val MIN_SHOT_SPEED = 0.10
        private const val MAX_SHOT_SPEED = 6.20
        private const val POWER_EXPONENT = 0.80

        private const val ROLL_DECELERATION = 0.46
        private const val LINEAR_DRAG = 0.26
        private const val QUADRATIC_DRAG = 0.040

        private const val CUSHION_RESTITUTION = 0.88
        private const val CUSHION_FRICTION = 0.99
        private const val BALL_RESTITUTION = 0.96
        private const val STOP_SPEED = 0.008
        private const val FALL_DURATION = 0.45

        private const val SIDE_SPIN_CURVE = 0.22
        private const val CUSHION_SPIN = 0.38
        private const val DRAW_FOLLOW_GAIN = 0.80

        private const val MAX_SUBSTEP_DISTANCE = 0.00045
        private const val MAX_SUBSTEPS = 512
    }
}
