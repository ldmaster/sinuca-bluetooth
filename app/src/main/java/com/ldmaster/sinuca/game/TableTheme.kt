package com.ldmaster.sinuca.game

/**
 * The four table directions. Each one has its own table-only GLB, built from
 * the Blender base (classic turned legs or modern cylinders) with the theme
 * materials baked into the vertex colors by the exporter.
 */
enum class TableTheme(
    val key: String,
    val label: String,
    val description: String,
    val asset: String
) {
    HERITAGE("a", "Heritage Club", "Nogueira, latão e pano verde", "models/table_a.glb"),
    ARENA("b", "Arena Matte", "Preto fosco, alumínio e pano pétroleo", "models/table_b.glb"),
    FOUNDRY("c", "Foundry Loft", "Aço, oxblood e ferrugem", "models/table_c.glb"),
    NEO("d", "Neo Sport", "Cerâmica, titânio e pano azul", "models/table_d.glb");

    companion object {
        const val PREFS = "sinuca"
        const val KEY = "table_theme"
        val DEFAULT = HERITAGE

        fun fromKey(key: String?): TableTheme =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
