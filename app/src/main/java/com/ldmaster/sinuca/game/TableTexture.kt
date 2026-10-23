package com.ldmaster.sinuca.game

/**
 * The five texture treatments from the study. The theme selects the palette and
 * table geometry; this selects the surface treatment. They combine into 20
 * looks (4 themes × 5 textures).
 */
enum class TableTexture(
    val key: String,
    val label: String,
    val description: String
) {
    SCAN("scan", "Scan Fidelity", "4K · veio, poro e metal detalhados"),
    WEAR("wear", "Cinematic Wear", "4K · riscos, giz e vincos"),
    CLEAN("clean", "Clean Premium", "4K · superfícies limpas e specular"),
    PROCEDURAL("procedural", "Procedural Infinite", "4K · variação macro sem repetição"),
    ATLAS("atlas", "Mobile Atlas 4K", "4K · atlas otimizado para 60 fps");

    companion object {
        const val PREFS = "sinuca"
        const val KEY = "table_texture"
        val DEFAULT = SCAN

        fun fromKey(key: String?): TableTexture =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
