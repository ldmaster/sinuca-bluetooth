package com.ldmaster.sinuca.render

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
