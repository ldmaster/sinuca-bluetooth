package com.ldmaster.sinuca.net

import com.ldmaster.sinuca.game.BallSnapshot
import com.ldmaster.sinuca.game.WorldSnapshot
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

sealed class NetMessage {
    data class Hello(val name: String) : NetMessage()
    data class Start(val youAre: Int) : NetMessage()
    data class State(val snapshot: WorldSnapshot) : NetMessage()
    data class Shot(
        val angle: Double,
        val power: Double,
        val strikeX: Double = 0.0,
        val strikeY: Double = 0.0
    ) : NetMessage()
    data object Rematch : NetMessage()
    data object Bye : NetMessage()
}

object Wire {

    fun hello(name: String): String =
        JSONObject().put("t", "hello").put("n", name).toString()

    fun start(youAre: Int): String =
        JSONObject().put("t", "start").put("you", youAre).toString()

    fun shot(angle: Double, power: Double, strikeX: Double, strikeY: Double): String =
        JSONObject()
            .put("t", "shot")
            .put("a", round4(angle))
            .put("p", round4(power))
            .put("sx", round4(strikeX))
            .put("sy", round4(strikeY))
            .toString()

    fun rematch(): String = JSONObject().put("t", "rematch").toString()

    fun bye(): String = JSONObject().put("t", "bye").toString()

    fun state(snapshot: WorldSnapshot): String {
        val balls = JSONArray()
        for (ball in snapshot.balls) {
            balls.put(
                JSONArray()
                    .put(round4(ball.x))
                    .put(round4(ball.z))
                    .put(round4(ball.vx))
                    .put(round4(ball.vz))
                    .put(if (ball.active) 1 else 0)
                    .put(if (ball.falling) 1 else 0)
                    .put(round4(ball.fall.toDouble()))
            )
        }
        return JSONObject()
            .put("t", "state")
            .put("turn", snapshot.turn)
            .put("win", snapshot.winner)
            .put("mv", if (snapshot.moving) 1 else 0)
            .put("msg", snapshot.message)
            .put("r", JSONArray().put(snapshot.remaining0).put(snapshot.remaining1))
            .put("b", balls)
            .toString()
    }

    fun parse(line: String): NetMessage? {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (json.optString("t")) {
            "hello" -> NetMessage.Hello(json.optString("n"))
            "start" -> NetMessage.Start(json.optInt("you", 1))
            "state" -> NetMessage.State(decodeState(json))
            "shot" -> NetMessage.Shot(
                angle = json.optDouble("a", 0.0),
                power = json.optDouble("p", 0.0),
                strikeX = json.optDouble("sx", 0.0),
                strikeY = json.optDouble("sy", 0.0)
            )
            "rematch" -> NetMessage.Rematch
            "bye" -> NetMessage.Bye
            else -> null
        }
    }

    private fun decodeState(json: JSONObject): WorldSnapshot {
        val balls = ArrayList<BallSnapshot>(16)
        val array = json.optJSONArray("b") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONArray(index) ?: continue
            balls.add(
                BallSnapshot(
                    number = index,
                    x = item.optDouble(0, 0.0),
                    z = item.optDouble(1, 0.0),
                    vx = item.optDouble(2, 0.0),
                    vz = item.optDouble(3, 0.0),
                    active = item.optInt(4, 0) == 1,
                    falling = item.optInt(5, 0) == 1,
                    fall = item.optDouble(6, 0.0).toFloat()
                )
            )
        }
        val remaining = json.optJSONArray("r")
        return WorldSnapshot(
            turn = json.optInt("turn", 0),
            winner = json.optInt("win", -1),
            moving = json.optInt("mv", 0) == 1,
            message = json.optString("msg", ""),
            balls = balls,
            remaining0 = remaining?.optInt(0, 0) ?: 0,
            remaining1 = remaining?.optInt(1, 0) ?: 0
        )
    }

    private fun round4(value: Double): Double = round(value * 10000.0) / 10000.0
}
