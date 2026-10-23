package com.ldmaster.sinuca.game

import android.os.Process
import android.util.Log

data class TrajectoryPrediction(
    val key: Long,
    val cuePath: FloatArray,
    val targetPath: FloatArray
)

/**
 * Runs the shot simulation for the trajectory overlay on a dedicated worker
 * thread. The render thread only publishes the latest snapshot and draws the
 * most recent result, so dragging the aim never blocks rendering. Requests are
 * coalesced: while one simulation runs, newer requests replace each other and
 * are simulated as soon as the current one ends.
 */
class TrajectoryPredictor {

    private data class Request(
        val key: Long,
        val snapshot: WorldSnapshot,
        val angle: Double,
        val power: Double,
        val strikeX: Double,
        val strikeY: Double
    )

    private val lock = Object()
    private val simulation = GameWorld()
    private var pending: Request? = null
    private var running = true

    @Volatile
    var latest: TrajectoryPrediction? = null
        private set

    private val worker = Thread({ runLoop() }, "sinuca-trajectory").apply {
        isDaemon = true
        start()
    }

    fun request(
        key: Long,
        snapshot: WorldSnapshot,
        angle: Double,
        power: Double,
        strikeX: Double,
        strikeY: Double
    ) {
        synchronized(lock) {
            if (!running) return
            pending = Request(key, snapshot, angle, power, strikeX, strikeY)
            lock.notifyAll()
        }
    }

    fun release() {
        synchronized(lock) {
            running = false
            pending = null
            lock.notifyAll()
        }
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
        while (true) {
            val request = synchronized(lock) {
                while (pending == null && running) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                if (!running) return
                val next = pending
                pending = null
                next
            } ?: continue

            val started = System.nanoTime()
            val prediction = simulate(request)
            latest = prediction
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                val elapsed = (System.nanoTime() - started) / 1_000_000
                Log.d(
                    TAG,
                    "prediction ${elapsed}ms cue=${prediction.cuePath.size / 3} " +
                        "target=${prediction.targetPath.size / 3}"
                )
            }
        }
    }

    private fun simulate(request: Request): TrajectoryPrediction {
        simulation.applySnapshot(request.snapshot)
        if (!simulation.shoot(request.angle, request.power, request.strikeX, request.strikeY)) {
            return TrajectoryPrediction(request.key, FloatArray(0), FloatArray(0))
        }

        val cue = simulation.balls[0]
        val cuePoints = ArrayList<Float>(256)
        val targetPoints = ArrayList<Float>(256)
        addPoint(cuePoints, cue)
        var target: Ball? = null
        var steps = 0
        while (simulation.moving && steps < MAX_STEPS) {
            simulation.step(STEP_SECONDS)
            if (target == null) {
                target = simulation.balls.firstOrNull {
                    it.number != 0 && it.active && it.speed > 0.02
                }
            }
            if (steps % 2 == 0) {
                if (cue.active || cue.falling) addPoint(cuePoints, cue)
                target?.let { if (it.active || it.falling) addPoint(targetPoints, it) }
            }
            steps++
        }
        return TrajectoryPrediction(
            request.key,
            cuePoints.toFloatArray(),
            targetPoints.toFloatArray()
        )
    }

    private fun addPoint(points: ArrayList<Float>, ball: Ball) {
        points.add(ball.x.toFloat())
        points.add(Table.BALL_Y.toFloat())
        points.add(ball.z.toFloat())
    }

    private companion object {
        private const val TAG = "TrajectoryPredictor"
        private const val STEP_SECONDS = 1.0 / 120.0
        private const val MAX_STEPS = 600
    }
}
