package com.ldmaster.sinuca.render

import com.ldmaster.sinuca.game.TableSurface
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlbLoaderTest {

    @Test
    fun `indices above 65535 are preserved`() {
        val json = JSONObject(
            """
            {
              "asset": {"version": "2.0"},
              "scene": 0,
              "scenes": [{"nodes": [0]}],
              "nodes": [{"mesh": 0}],
              "meshes": [{"primitives": [{
                "attributes": {"POSITION": 0},
                "indices": 1
              }]}],
              "accessors": [
                {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
                {"bufferView": 1, "componentType": 5125, "count": 3, "type": "SCALAR"}
              ],
              "bufferViews": [
                {"buffer": 0, "byteOffset": 0, "byteLength": 36},
                {"buffer": 0, "byteOffset": 36, "byteLength": 12}
              ],
              "buffers": [{"byteLength": 48}]
            }
            """.trimIndent()
        ).toString()

        val binary = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0f).putFloat(0f).putFloat(0f)
            .putFloat(1f).putFloat(0f).putFloat(0f)
            .putFloat(0f).putFloat(1f).putFloat(0f)
            .putInt(0).putInt(1).putInt(70_000)
            .array()

        val mesh = GlbLoader.parse(glb(json, binary))

        assertEquals(3, mesh.vertexCount)
        assertArrayEquals(intArrayOf(0, 1, 70_000), mesh.indices)
    }

    @Test
    fun `material factors are carried per vertex`() {
        val mesh = GlbLoader.parse(triangleWithMaterial("MAT_Brass", 0.9, 0.28), themeKey = "a")

        assertEquals(3, mesh.materials.size / 3)
        for (index in 0 until mesh.vertexCount) {
            assertEquals(0.9f, mesh.materials[index * 3], 1e-4f)
            assertEquals(0.28f, mesh.materials[index * 3 + 1], 1e-4f)
            assertEquals(TableSurface.METAL, mesh.materials[index * 3 + 2], 1e-4f)
        }
    }

    @Test
    fun `wood slot is metal in arena and wood in heritage`() {
        val bytes = triangleWithMaterial("MAT_Wood", 0.85, 0.32)
        val arena = GlbLoader.parse(bytes, themeKey = "b")
        val heritage = GlbLoader.parse(bytes, themeKey = "a")

        assertEquals(TableSurface.METAL, arena.materials[2], 1e-4f)
        assertEquals(TableSurface.WOOD, heritage.materials[2], 1e-4f)
    }

    @Test
    fun `cloth is cloth regardless of theme`() {
        val mesh = GlbLoader.parse(triangleWithMaterial("MAT_Cloth", 0.0, 0.9), themeKey = "c")

        assertEquals(TableSurface.CLOTH, mesh.materials[2], 1e-4f)
    }

    @Test
    fun `missing material falls back to a safe surface`() {
        val mesh = GlbLoader.parse(triangleWithMaterial(null, null, null))

        assertEquals(3, mesh.materials.size / 3)
        assertEquals(TableSurface.CERAMIC, mesh.materials[2], 1e-4f)
        assertEquals(0.6f, mesh.materials[1], 1e-4f)
    }

    private fun triangleWithMaterial(
        materialName: String?,
        metallic: Double?,
        roughness: Double?
    ): ByteArray {
        val material = if (materialName == null) "" else
            ""","materials": [{
                "name": "$materialName",
                "pbrMetallicRoughness": {
                  "baseColorFactor": [0.2, 0.3, 0.4, 1.0],
                  "metallicFactor": $metallic,
                  "roughnessFactor": $roughness
                }
            }]"""
        val primitiveMaterial = if (materialName == null) "" else ""","material": 0"""
        val json = """
        {
          "asset": {"version": "2.0"},
          "scene": 0,
          "scenes": [{"nodes": [0]}],
          "nodes": [{"mesh": 0}],
          "meshes": [{"primitives": [{
            "attributes": {"POSITION": 0},
            "indices": 1$primitiveMaterial
          }]}],
          "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
            {"bufferView": 1, "componentType": 5125, "count": 3, "type": "SCALAR"}
          ],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 36},
            {"buffer": 0, "byteOffset": 36, "byteLength": 12}
          ],
          "buffers": [{"byteLength": 48}]$material
        }
        """.trimIndent()

        val binary = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0f).putFloat(0f).putFloat(0f)
            .putFloat(1f).putFloat(0f).putFloat(0f)
            .putFloat(0f).putFloat(1f).putFloat(0f)
            .putInt(0).putInt(1).putInt(2)
            .array()

        return glb(json, binary)
    }

    private fun glb(json: String, binary: ByteArray): ByteArray {
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPadding = (4 - jsonBytes.size % 4) % 4
        val binaryPadding = (4 - binary.size % 4) % 4
        val jsonLength = jsonBytes.size + jsonPadding
        val binaryLength = binary.size + binaryPadding
        val total = 12 + 8 + jsonLength + 8 + binaryLength
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x46546C67)
        buffer.putInt(2)
        buffer.putInt(total)
        buffer.putInt(jsonLength)
        buffer.putInt(0x4E4F534A)
        buffer.put(jsonBytes)
        repeat(jsonPadding) { buffer.put(0x20) }
        buffer.putInt(binaryLength)
        buffer.putInt(0x004E4942)
        buffer.put(binary)
        repeat(binaryPadding) { buffer.put(0) }
        return buffer.array()
    }
}
