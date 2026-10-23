package com.ldmaster.sinuca.game

/**
 * How the 4K surface detail is delivered. Literal uses real 4096x4096 maps
 * shipped in the APK; procedural keeps the shader-generated detail. Both keep
 * the same 5 texture styles and 4 themes.
 */
enum class TextureQuality(
    val key: String,
    val label: String,
    val description: String
) {
    LITERAL("literal", "4K literal", "Mapas 4096² reais embutidos no APK"),
    PROCEDURAL("procedural", "4K procedural", "Detalhe calculado no shader, APK leve");

    companion object {
        const val PREFS = "sinuca"
        const val KEY = "table_texture_quality"
        val DEFAULT = LITERAL

        fun fromKey(key: String?): TextureQuality =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
