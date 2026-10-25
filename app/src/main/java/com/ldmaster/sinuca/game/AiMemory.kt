package com.ldmaster.sinuca.game

import android.content.Context
import org.json.JSONObject

/**
 * A shot family: how thin the cut is, how far the balls are and how hard the
 * cue is hit. Layouts never repeat exactly, but these three numbers are what
 * decides whether a shot type is reliable.
 */
data class ShotFeatures(
    val cutCos: Double,
    val distance: Double,
    val power: Double
) {
    val cutBucket: Int get() = bucket(cutCos, 0.20, 1.0, CUT_BUCKETS)
    val distanceBucket: Int get() = bucket(distance, 0.0, 3.0, DISTANCE_BUCKETS)
    val powerBucket: Int get() = bucket(power, 0.20, 1.0, POWER_BUCKETS)
    val key: Int get() = (cutBucket * DISTANCE_BUCKETS + distanceBucket) * POWER_BUCKETS + powerBucket

    private fun bucket(value: Double, min: Double, max: Double, count: Int): Int {
        val normalized = (value - min) / (max - min)
        return (normalized * count).toInt().coerceIn(0, count - 1)
    }

    companion object {
        const val CUT_BUCKETS = 5
        const val DISTANCE_BUCKETS = 4
        const val POWER_BUCKETS = 3
        const val CELLS = CUT_BUCKETS * DISTANCE_BUCKETS * POWER_BUCKETS

        /** No data yet: neutral expectation of pots per shot. */
        const val PRIOR = 0.45
    }
}

/** Storage seam so the learning model is testable without Android. */
interface MemoryStore {
    fun read(): String?
    fun write(value: String)
}

class PrefsMemoryStore(context: Context) : MemoryStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("sinuca_ai_memory", Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(value: String) {
        prefs.edit().putString(KEY, value).apply()
    }

    private companion object {
        const val KEY = "model"
    }
}

class InMemoryStore(private var value: String? = null) : MemoryStore {
    override fun read(): String? = value
    override fun write(value: String) {
        this.value = value
    }
}

/**
 * Online, per-level model of how many object balls a shot family actually pots.
 *
 * It is a smoothed average (Beta-like prior) over [ShotFeatures] cells, updated
 * after every shot settles. Hard and Extreme use it to bias the search toward
 * families that work on this table and against the ones that keep failing.
 */
class AiMemory(private val store: MemoryStore) {

    private val cells = LinkedHashMap<String, IntArray>()

    init {
        load()
    }

    fun record(level: AiLevel, features: ShotFeatures, potted: Int) {
        if (level.learningWeight <= 0.0) return
        val key = key(level, features)
        val cell = cells.getOrPut(key) { IntArray(2) }
        cell[0] += 1
        cell[1] += potted.coerceIn(0, 3)
        save()
    }

    /** Smoothed expected pots per shot for this family (0..3). */
    fun expectedPots(level: AiLevel, features: ShotFeatures): Double {
        val cell = cells[key(level, features)] ?: return ShotFeatures.PRIOR
        return (cell[1] + ShotFeatures.PRIOR) / (cell[0] + 1.0)
    }

    fun attempts(level: AiLevel): Int {
        val prefix = "${level.name}:"
        return cells.entries.filter { it.key.startsWith(prefix) }.sumOf { it.value[0] }
    }

    fun pots(level: AiLevel): Int {
        val prefix = "${level.name}:"
        return cells.entries.filter { it.key.startsWith(prefix) }.sumOf { it.value[1] }
    }

    /** Pots per shot over everything the level has learned so far. */
    fun potRate(level: AiLevel): Double {
        val attempts = attempts(level)
        return if (attempts == 0) 0.0 else pots(level).toDouble() / attempts
    }

    fun learnedFamilies(level: AiLevel): Int {
        val prefix = "${level.name}:"
        return cells.keys.count { it.startsWith(prefix) }
    }

    fun reset(level: AiLevel? = null) {
        if (level == null) {
            cells.clear()
        } else {
            cells.keys.removeAll { it.startsWith("${level.name}:") }
        }
        save()
    }

    private fun key(level: AiLevel, features: ShotFeatures): String =
        "${level.name}:${features.key}"

    private fun load() {
        val raw = store.read() ?: return
        runCatching {
            val root = JSONObject(raw)
            for (levelName in root.keys()) {
                val level = root.getJSONObject(levelName)
                for (cellKey in level.keys()) {
                    val value = level.getJSONArray(cellKey)
                    cells["$levelName:$cellKey"] = intArrayOf(value.getInt(0), value.getInt(1))
                }
            }
        }
    }

    private fun save() {
        val root = JSONObject()
        for ((key, value) in cells) {
            val parts = key.split(':', limit = 2)
            val level = root.optJSONObject(parts[0]) ?: JSONObject().also { root.put(parts[0], it) }
            level.put(parts[1], org.json.JSONArray().put(value[0]).put(value[1]))
        }
        store.write(root.toString())
    }
}
