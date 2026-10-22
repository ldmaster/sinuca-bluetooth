package com.ldmaster.sinuca.ui

data class HudState(
    val turn: Int,
    val localPlayer: Int,
    val practice: Boolean,
    val remaining0: Int,
    val remaining1: Int,
    val winner: Int,
    val moving: Boolean,
    val message: String,
    val started: Boolean,
    val canShoot: Boolean
)
