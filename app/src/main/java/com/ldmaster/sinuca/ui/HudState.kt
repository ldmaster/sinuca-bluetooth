package com.ldmaster.sinuca.ui

data class HudState(
    val turn: Int,
    val localPlayer: Int,
    val practice: Boolean,
    val potted0: List<Int>,
    val potted1: List<Int>,
    val winner: Int,
    val moving: Boolean,
    val message: String,
    val started: Boolean,
    val canShoot: Boolean,
    val cameraMode: Int,
    val aimAngle: Float = 0f,
    val aiThinking: Boolean = false,
    val aiLevel: String = ""
)
