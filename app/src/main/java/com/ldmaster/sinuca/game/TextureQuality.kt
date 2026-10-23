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
    DETAILED_540("540", "540 detalhado", "Mapa 540², para comparar o ganho de resolução"),
    DETAILED_720("720", "720 detalhado", "Mapa 720², intermediário"),
    DETAILED_1080("1080", "1080 detalhado", "Mapa 1080² com relevo natural, sem topografia"),
    DETAILED_2K("2k", "2K detalhado", "Mapa 2048² com relevo ampliado, visível no celular"),
    LITERAL("literal", "4K literal", "Mapas 4096² reais embutidos no APK"),
    PROCEDURAL("procedural", "4K procedural", "Detalhe calculado no shader, APK leve");

    companion object {
        const val PREFS = "sinuca"
        const val KEY = "table_texture_quality"
        val DEFAULT = DETAILED_1080

        fun fromKey(key: String?): TextureQuality =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
