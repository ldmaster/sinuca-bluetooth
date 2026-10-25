package com.ldmaster.sinuca.ui

import android.content.Context
import com.ldmaster.sinuca.game.AiLevel

/**
 * Small persistent scoreboard for the hall: how many games were won, the
 * current streak and which AI levels were already beaten.
 */
class HallStats(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val wins: Int get() = prefs.getInt(KEY_WINS, 0)
    val losses: Int get() = prefs.getInt(KEY_LOSSES, 0)
    val streak: Int get() = prefs.getInt(KEY_STREAK, 0)
    val bestStreak: Int get() = prefs.getInt(KEY_BEST_STREAK, 0)

    fun winsAgainst(level: AiLevel): Int =
        prefs.getInt(keyFor(level), 0)

    fun beaten(level: AiLevel): Boolean = winsAgainst(level) > 0

    fun recordWin(level: AiLevel) {
        val nextStreak = streak + 1
        prefs.edit()
            .putInt(KEY_WINS, wins + 1)
            .putInt(keyFor(level), winsAgainst(level) + 1)
            .putInt(KEY_STREAK, nextStreak)
            .putInt(KEY_BEST_STREAK, maxOf(bestStreak, nextStreak))
            .apply()
    }

    fun recordLoss() {
        prefs.edit()
            .putInt(KEY_LOSSES, losses + 1)
            .putInt(KEY_STREAK, 0)
            .apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    private fun keyFor(level: AiLevel): String = "wins_${level.name.lowercase()}"

    private companion object {
        const val PREFS = "sinuca_hall"
        const val KEY_WINS = "wins"
        const val KEY_LOSSES = "losses"
        const val KEY_STREAK = "streak"
        const val KEY_BEST_STREAK = "best_streak"
    }
}
