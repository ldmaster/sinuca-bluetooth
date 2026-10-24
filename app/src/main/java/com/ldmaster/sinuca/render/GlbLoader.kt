package com.ldmaster.sinuca.render

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val colors: FloatArray,
    val indices: IntArray,
    val uvs: FloatArray = FloatArray((positions.size / 3) * 2)
) {
    val vertexCount: Int get() = positions.size / 3
}

object GlbLoader {

    fun load(context: Context, assetPath: String): MeshData {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        return parse(bytes, recenter = assetPath.contains("cue"))
    }

    fun parse(bytes: ByteArray, recenter: Boolean = false): MeshData {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == 0x46546C67) { "not a GLB file" }
        require(buffer.int == 2) { "unsupported GLB version" }
        buffer.int

        var json: JSONObject? = null
        var binary = ByteArray(0)
        while (buffer.remaining() >= 8) {
            val length = buffer.int
            val type = buffer.int
            if (length < 0 || length > buffer.remaining()) break
            val chunk = ByteArray(length)
            buffer.get(chunk)
            if (type == 0x4E4F534A) {
                json = JSONObject(String(chunk, Charsets.UTF_8))
            } else if (type == 0x004E4942) {
                binary = chunk
            }
        }
        val document = requireNotNull(json) { "GLB has no JSON chunk" }
        val accessors = document.getJSONArray("accessors")
        val views = document.getJSONArray("bufferViews")
        val reader = AccessorReader(accessors, views, binary)

        val nodes = document.optJSONArray("nodes") ?: JSONArray()
        val parented = BooleanArray(nodes.length())
        for (i in 0 until nodes.length()) {
            val children = nodes.getJSONObject(i).optJSONArray("children") ?: continue
            for (c in 0 until children.length()) {
                val child = children.getInt(c)
                if (child in parented.indices) parented[child] = true
            }
        }

        val roots = ArrayList<Int>()
        val sceneNodes = document.optJSONArray("scenes")
            ?.optJSONObject(document.optInt("scene", 0))
            ?.optJSONArray("nodes")
        if (sceneNodes != null) {
            for (i in 0 until sceneNodes.length()) roots.add(sceneNodes.getInt(i))
        } else {
            for (i in 0 until nodes.length()) if (!parented[i]) roots.add(i)
        }

        val out = Builder()
        for (root in roots) {
            walk(root, Mat4.identity(), document, nodes, reader, out)
        }
        if (out.positions.isEmpty()) {
            out.addVertex(0f, 0f, 0f, 0f, 1f, 0f, 0.8f, 0.8f, 0.8f)
            out.uvs.add(0f)
            out.uvs.add(0f)
            out.indices.add(0)
        }
        val mesh = out.build()
        return if (recenter) recenter(mesh) else mesh
    }

    private fun walk(
        index: Int,
        parent: FloatArray,
        document: JSONObject,
        nodes: JSONArray,
        reader: AccessorReader,
        out: Builder
    ) {
        val node = nodes.getJSONObject(index)
        val local = node.optJSONArray("matrix")?.let { Mat4.fromJson(it) }
            ?: Mat4.fromTrs(
                node.optJSONArray("translation"),
                node.optJSONArray("rotation"),
                node.optJSONArray("scale")
            )
        val world = Mat4.multiply(parent, local)

        val meshIndex = node.optInt("mesh", -1)
        if (meshIndex >= 0) {
            val mesh = document.getJSONArray("meshes").getJSONObject(meshIndex)
            val primitives = mesh.optJSONArray("primitives") ?: JSONArray()
            for (p in 0 until primitives.length()) {
                addPrimitive(primitives.getJSONObject(p), world, document, reader, out)
            }
        }

        val children = node.optJSONArray("children") ?: return
        for (c in 0 until children.length()) {
            walk(children.getInt(c), world, document, nodes, reader, out)
        }
    }

    private fun addPrimitive(
        primitive: JSONObject,
        world: FloatArray,
        document: JSONObject,
        reader: AccessorReader,
        out: Builder
    ) {
        val attributes = primitive.optJSONObject("attributes") ?: return
        if (!attributes.has("POSITION")) return
        val positions = reader.floats(attributes.getInt("POSITION"))
        val normals = if (attributes.has("NORMAL")) {
            reader.floats(attributes.getInt("NORMAL"))
        } else {
            null
        }
        val uvs = if (attributes.has("TEXCOORD_0")) {
            reader.floats(attributes.getInt("TEXCOORD_0"))
        } else {
            null
        }
        val color = materialColor(primitive, document)
        val normalMatrix = Mat4.normalMatrix(world)
        val base = out.positions.size / 3
        val count = positions.size / 3

        for (i in 0 until count) {
            val px = positions[i * 3]
            val py = positions[i * 3 + 1]
            val pz = positions[i * 3 + 2]
            val point = Mat4.transformPoint(world, px, py, pz)

            var nx: Float
            var ny: Float
            var nz: Float
            if (normals != null) {
                nx = normalMatrix[0] * normals[i * 3] +
                    normalMatrix[3] * normals[i * 3 + 1] +
                    normalMatrix[6] * normals[i * 3 + 2]
                ny = normalMatrix[1] * normals[i * 3] +
                    normalMatrix[4] * normals[i * 3 + 1] +
                    normalMatrix[7] * normals[i * 3 + 2]
                nz = normalMatrix[2] * normals[i * 3] +
                    normalMatrix[5] * normals[i * 3 + 1] +
                    normalMatrix[8] * normals[i * 3 + 2]
                val length = sqrt(nx * nx + ny * ny + nz * nz)
                if (length > 12e-8f) {
                    nx /= length
                    ny /= length
                    nz /= length
                } else {
                    nx = 0f
                    ny = 1f
                    nz = 0f
                }
            } else {
                nx = 0f
                ny = 1f
                nz = 0f
            }
            out.addVertex(point[0], point[1], point[2], nx, ny, nz, color[0], color[1], color[2])
            if (uvs != null) {
                out.uvs.add(uvs[i * 2])
                out.uvs.add(uvs[i * 2 + 1])
            } else {
                out.uvs.add(0f)
                out.uvs.add(0f)
            }
        }

        val indices = primitive.optInt("indices", -1)
        if (indices >= 0) {
            val values = reader.ints(indices)
            for (value in values) out.indices.add(base + value)
        } else {
            for (i in 0 until count) out.indices.add(base + i)
        }
    }

    private fun materialColor(primitive: JSONObject, document: JSONObject): FloatArray {
        val fallback = floatArrayOf(0.75f, 0.75f, 0.75f)
        val materialIndex = primitive.optInt("material", -1)
        if (materialIndex < 0) return fallback
        val materials = document.optJSONArray("materials") ?: return fallback
        if (materialIndex >= materials.length()) return fallback
        val material = materials.getJSONObject(materialIndex)
        val pbr = material.optJSONObject("pbrMetallicRoughness") ?: return fallback
        val factor = pbr.optJSONArray("baseColorFactor") ?: return fallback
        return floatArrayOf(
            factor.optDouble(0, 0.75).toFloat(),
            factor.optDouble(1, 0.75).toFloat(),
            factor.optDouble(2, 0.75).toFloat()
        )
    }

    private fun recenter(mesh: MeshData): MeshData {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (i in 0 until mesh.vertexCount) {
            val x = mesh.positions[i * 3]
            val y = mesh.positions[i * 3 + 1]
            val z = mesh.positions[i * 3 + 2]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if (z < minZ) minZ = z
            if (z > maxZ) maxZ = z
        }
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val cz = (minZ + maxZ) / 2f
        val positions = FloatArray(mesh.positions.size) { index ->
            when (index % 3) {
                0 -> mesh.positions[index] - cx
                1 -> mesh.positions[index] - cy
                else -> mesh.positions[index] - cz
            }
        }
        return MeshData(positions, mesh.normals, mesh.colors, mesh.indices, mesh.uvs)
    }

    private class Builder {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val colors = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val indices = ArrayList<Int>()

        fun addVertex(
            x: Float, y: Float, z: Float,
            nx: Float, ny: Float, nz: Float,
            r: Float, g: Float, b: Float
        ) {
            positions.add(x)
            positions.add(y)
            positions.add(z)
            normals.add(nx)
            normals.add(ny)
            normals.add(nz)
            colors.add(r)
            colors.add(g)
            colors.add(b)
        }

        fun build(): MeshData {
            return MeshData(
                positions.toFloatArray(),
                normals.toFloatArray(),
                colors.toFloatArray(),
                indices.toIntArray(),
                uvs.toFloatArray()
            )
        }
    }

    private class AccessorReader(
        private val accessors: JSONArray,
        private val views: JSONArray,
        private val binary: ByteArray
    ) {
        fun floats(index: Int): FloatArray {
            val accessor = accessors.getJSONObject(index)
            val view = views.getJSONObject(accessor.getInt("bufferView"))
            val components = components(accessor.getString("type"))
            val count = accessor.getInt("count")
            val stride = view.optInt("byteStride", components * 4)
            val offset = view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
            val buffer = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN)
            val result = FloatArray(count * components)
            for (i in 0 until count) {
                for (c in 0 until components) {
                    result[i * components + c] = buffer.getFloat(offset + i * stride + c * 4)
                }
            }
            return result
        }

        fun ints(index: Int): IntArray {
            val accessor = accessors.getJSONObject(index)
            val view = views.getJSONObject(accessor.getInt("bufferView"))
            val count = accessor.getInt("count")
            val type = accessor.getInt("componentType")
            val size = when (type) {
                5121 -> 1
                5123 -> 2
                5125 -> 4
                else -> error("unsupported index component type $type")
            }
            val stride = view.optInt("byteStride", size)
            val offset = view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
            val buffer = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN)
            return IntArray(count) { i ->
                val at = offset + i * stride
                when (type) {
                    5121 -> buffer.get(at).toInt() and 0xFF
                    5123 -> buffer.getShort(at).toInt() and 0xFFFF
                    else -> buffer.getInt(at)
                }
            }
        }

        private fun components(type: String): Int = when (type) {
            "SCALAR" -> 1
            "VEC2" -> 2
            "VEC3" -> 3
            "VEC4" -> 4
            "MAT2" -> 4
            "MAT3" -> 9
            "MAT4" -> 16
            else -> error("unsupported accessor type $type")
        }
    }
}

internal object Mat4 {

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    fun fromJson(values: JSONArray): FloatArray = FloatArray(16) {
        values.optDouble(it, 0.0).toFloat()
    }

    fun fromTrs(translation: JSONArray?, rotation: JSONArray?, scale: JSONArray?): FloatArray {
        val tx = translation?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val ty = translation?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val tz = translation?.optDouble(2, 0.0)?.toFloat() ?: 0f
        val sx = scale?.optDouble(0, 1.0)?.toFloat() ?: 1f
        val sy = scale?.optDouble(1, 1.0)?.toFloat() ?: 1f
        val sz = scale?.optDouble(2, 1.0)?.toFloat() ?: 1f

        var qx = rotation?.optDouble(0, 0.0)?.toFloat() ?: 0f
        var qy = rotation?.optDouble(1, 0.0)?.toFloat() ?: 0f
        var qz = rotation?.optDouble(2, 0.0)?.toFloat() ?: 0f
        var qw = rotation?.optDouble(3, 1.0)?.toFloat() ?: 1f
        val length = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        if (length < 1e-8f) {
            qx = 0f
            qy = 0f
            qz = 0f
            qw = 1f
        } else {
            qx /= length
            qy /= length
            qz /= length
            qw /= length
        }

        val xx = qx * qx
        val yy = qy * qy
        val zz = qz * qz
        val xy = qx * qy
        val xz = qx * qz
        val yz = qy * qz
        val wx = qw * qx
        val wy = qw * qy
        val wz = qw * qz

        return floatArrayOf(
            (1f - 2f * (yy + zz)) * sx, 2f * (xy + wz) * sx, 2f * (xz - wy) * sx, 0f,
            2f * (xy - wz) * sy, (1f - 2f * (xx + zz)) * sy, 2f * (yz + wx) * sy, 0f,
            2f * (xz + wy) * sz, 2f * (yz - wx) * sz, (1f - 2f * (xx + yy)) * sz, 0f,
            tx, ty, tz, 1f
        )
    }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(16)
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[column * 4 + k]
                result[column * 4 + row] = sum
            }
        }
        return result
    }

    fun transformPoint(matrix: FloatArray, x: Float, y: Float, z: Float): FloatArray =
        floatArrayOf(
            matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12],
            matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13],
            matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]
        )

    fun normalMatrix(matrix: FloatArray): FloatArray {
        val a00 = matrix[0]
        val a01 = matrix[4]
        val a02 = matrix[8]
        val a10 = matrix[1]
        val a11 = matrix[5]
        val a12 = matrix[9]
        val a20 = matrix[2]
        val a21 = matrix[6]
        val a22 = matrix[10]

        val determinant = a00 * (a11 * a22 - a12 * a21) -
            a01 * (a10 * a22 - a12 * a20) +
            a02 * (a10 * a21 - a11 * a20)
        if (kotlin.math.abs(determinant) < 1e-9f) return identity()
        val inverse = 1f / determinant

        return floatArrayOf(
            (a11 * a22 - a12 * a21) * inverse,
            (a12 * a20 - a10 * a22) * inverse,
            (a10 * a21 - a11 * a20) * inverse,
            (a02 * a21 - a01 * a22) * inverse,
            (a00 * a22 - a02 * a20) * inverse,
            (a01 * a20 - a00 * a21) * inverse,
            (a01 * a12 - a02 * a11) * inverse,
            (a02 * a10 - a00 * a12) * inverse,
            (a00 * a11 - a01 * a10) * inverse
        )
    }
}
