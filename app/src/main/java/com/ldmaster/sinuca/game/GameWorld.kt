package com.ldmaster.sinuca.game

import kotlin.math.atan2
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
    val active: Boolean
)

data class WorldSnapshot(
    val turn: Int,
    val winner: Int,
    val moving: Boolean,
    val message: String,
    val balls: List<BallSnapshot>,
    val remaining0: Int,
    val remaining1: Int
)

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
                ball.stop()
            }
        }
    }

    fun canShoot(playerId: Int): Boolean {
        if (winner >= 0 || moving) return false
        return practice || playerId == turn
    }

    fun cueBall(): Ball = balls[0]

    fun shoot(angle: Double, power: Double): Boolean {
        if (winner >= 0 || moving) return false
        val cue = balls[0]
        if (!cue.active) return false
        val speed = 0.9 + 4.6 * power.coerceIn(0.0, 1.0)
        cue.vx = cos(angle) * speed
        cue.vz = sin(angle) * speed
        moving = true
        pendingPotted.clear()
        return true
    }

    fun step(dt: Double) {
        if (!moving) return
        val frame = dt.coerceIn(0.0, 0.05)
        if (frame <= 0.0) return
        val topSpeed = balls.filter { it.active }.maxOfOrNull { it.speed } ?: 0.0
        val substeps = ceil(topSpeed * frame / (Table.BALL_RADIUS * 0.35))
            .toInt()
            .coerceIn(1, 12)
        val h = frame / substeps
        repeat(substeps) { integrate(h) }
        if (balls.all { !it.active || it.speed < STOP_SPEED }) {
            for (ball in balls) if (ball.speed < STOP_SPEED) ball.stop()
            moving = false
            resolvePots(pendingPotted.toList())
            pendingPotted.clear()
        }
    }

    private fun integrate(h: Double) {
        for (ball in balls) {
            if (!ball.active) continue
            val speed = ball.speed
            if (speed > 0.0) {
                val next = max(0.0, speed - ROLL_DECELERATION * h)
                ball.vx = ball.vx / speed * next
                ball.vz = ball.vz / speed * next
            }
            ball.x += ball.vx * h
            ball.z += ball.vz * h

            if (pocketed(ball)) continue

            val limitX = Table.HALF_LENGTH - Table.BALL_RADIUS
            val limitZ = Table.HALF_WIDTH - Table.BALL_RADIUS
            if (ball.x > limitX) {
                ball.x = limitX
                ball.vx = -ball.vx * CUSHION_RESTITUTION
            } else if (ball.x < -limitX) {
                ball.x = -limitX
                ball.vx = -ball.vx * CUSHION_RESTITUTION
            }
            if (ball.z > limitZ) {
                ball.z = limitZ
                ball.vz = -ball.vz * CUSHION_RESTITUTION
            } else if (ball.z < -limitZ) {
                ball.z = -limitZ
                ball.vz = -ball.vz * CUSHION_RESTITUTION
            }
        }
        collideBalls()
    }

    private fun pocketed(ball: Ball): Boolean {
        for (pocket in Table.POCKETS) {
            if (hypot(ball.x - pocket[0], ball.z - pocket[1]) < Table.POCKET_RADIUS) {
                ball.active = false
                ball.stop()
                pendingPotted.add(ball.number)
                return true
            }
        }
        return false
    }

    private fun collideBalls() {
        val diameter = Table.BALL_RADIUS * 2.0
        for (i in balls.indices) {
            val a = balls[i]
            if (!a.active) continue
            for (j in i + 1 until balls.size) {
                val b = balls[j]
                if (!b.active) continue
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

                val relative = (b.vx - a.vx) * nx + (b.vz - a.vz) * nz
                if (relative < 0.0) {
                    val impulse = -(1.0 + BALL_RESTITUTION) * relative / 2.0
                    a.vx -= nx * impulse
                    a.vz -= nz * impulse
                    b.vx += nx * impulse
                    b.vz += nz * impulse
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
                BallSnapshot(it.number, it.x, it.z, it.vx, it.vz, it.active)
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
        }
        turn = snapshot.turn
        winner = snapshot.winner
        moving = snapshot.moving
        message = snapshot.message
    }

    fun aimAt(targetX: Double, targetZ: Double) {
        val cue = balls[0]
        aimAngle = atan2(targetZ - cue.z, targetX - cue.x)
    }

    companion object {
        private const val ROLL_DECELERATION = 0.32
        private const val CUSHION_RESTITUTION = 0.72
        private const val BALL_RESTITUTION = 0.96
        private const val STOP_SPEED = 0.012
    }
}
