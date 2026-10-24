package com.ldmaster.sinuca.game

/**
 * Who is who inside each theme. The four themes reuse the same material slots
 * for very different finishes: MAT_Wood is walnut in Heritage, brushed
 * aluminium in Arena, steel in Foundry and ceramic in Neo. The glTF only
 * carries a flat base colour per slot, so the surface class has to come from
 * the theme, not from the name alone.
 */
object TableSurface {
    const val WOOD = 0f
    const val METAL = 1f
    const val CLOTH = 2f
    const val CERAMIC = 3f
    const val STONE = 4f
    const val SIGHT = 5f

    fun classify(themeKey: String?, materialName: String): Float {
        val name = materialName.lowercase()
        return when {
            name.contains("cloth") || name.contains("cushion") -> CLOTH
            name.contains("sight") -> SIGHT
            name.contains("slate") -> STONE
            name.contains("facing") || name.contains("liner") -> CERAMIC
            name.contains("brass") || name.contains("pocket") -> METAL
            name.contains("wood") -> when (themeKey) {
                "b", "c" -> METAL
                "d" -> CERAMIC
                else -> WOOD
            }
            else -> CERAMIC
        }
    }
}
