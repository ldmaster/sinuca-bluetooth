package com.ldmaster.sinuca.render

import android.content.Context
import android.opengl.GLES30
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.abs

/**
 * A rigged GLB with one skin and named animation clips.
 *
 * The table loader flattens every node into a single mesh; a character cannot
 * do that because its vertices are deformed by joints. This loader keeps the
 * node hierarchy, the skin and the clips, and draws with linear blend skinning
 * through the scene shader's `uBones` array.
 */
class SkinnedModel(context: Context, asset: String) {

    class Primitive(
        val vertexBuffer: Int,
        val indexBuffer: Int,
        val count: Int,
        val color: FloatArray,
        val metallic: Float,
        val roughness: Float
    )

    private class Node {
        var parent = -1
        var mesh = -1
        var name = ""
        var t = floatArrayOf(0f, 0f, 0f)
        var r = floatArrayOf(0f, 0f, 0f, 1f)
        var s = floatArrayOf(1f, 1f, 1f)
        var local = FloatArray(16)
        var global = FloatArray(16)
    }

    private class Channel(
        val node: Int,
        val path: String,
        val times: FloatArray,
        val values: FloatArray,
        val step: Boolean
    )

    private class Clip(val name: String, val channels: List<Channel>, val duration: Float)

    private val nodes = ArrayList<Node>()
    private val clips = ArrayList<Clip>()
    private val primitives = ArrayList<Primitive>()
    private val roots = ArrayList<Int>()

    private var jointNodes = IntArray(0)
    private var inverseBind = FloatArray(0)
    private val jointMatrices = FloatArray(MAX_JOINTS * 16)
    private val bindTrs = ArrayList<Triple<FloatArray, FloatArray, FloatArray>>()

    private var current: Clip? = null
    private var clipTime = 0f
    private var loops = true
    /** Scratch for the sampled channel value; posing must not allocate. */
    private val poseValue = FloatArray(4)

    var currentClip: String = ""
        private set

    val clipProgress: Float
        get() {
            val clip = current ?: return 0f
            if (clip.duration <= 0.001f) return 0f
            return (clipTime / clip.duration).coerceIn(0f, 1f)
        }

    val primitivesList: List<Primitive> get() = primitives

    val clipNames: List<String> get() = clips.map { it.name }
    val isReady: Boolean get() = primitives.isNotEmpty()

    private val document: JSONObject
    private val binary: ByteArray

    init {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.int
        buffer.int
        buffer.int
        var json: JSONObject? = null
        var bin = ByteArray(0)
        while (buffer.remaining() >= 8) {
            val length = buffer.int
            val type = buffer.int
            if (length <= 0 || length > buffer.remaining()) break
            val chunk = ByteArray(length)
            buffer.get(chunk)
            if (type == 0x4E4F534A) json = JSONObject(String(chunk, Charsets.UTF_8))
            if (type == 0x004E4942) bin = chunk
        }
        document = requireNotNull(json) { "no JSON chunk in $asset" }
        binary = bin
        parse()
    }

    // --- parsing ------------------------------------------------------------

    private fun parse() {
        val nodeArray = document.optJSONArray("nodes") ?: JSONArray()
        val parented = BooleanArray(nodeArray.length())
        for (index in 0 until nodeArray.length()) {
            nodes.add(Node())
            val children = nodeArray.getJSONObject(index).optJSONArray("children")
                ?: continue
            for (c in 0 until children.length()) parented[children.getInt(c)] = true
        }
        for (index in 0 until nodeArray.length()) {
            val json = nodeArray.getJSONObject(index)
            val node = nodes[index]
            node.name = json.optString("name", "")
            node.mesh = json.optInt("mesh", -1)
            val matrix = json.optJSONArray("matrix")
            if (matrix != null) {
                val m = Mat4.fromJson(matrix)
                decompose(m, node)
            } else {
                readVector(json.optJSONArray("translation"), node.t)
                readVector(json.optJSONArray("rotation"), node.r)
                readVector(json.optJSONArray("scale"), node.s)
            }
            node.local = trs(node.t, node.r, node.s)
            val children = json.optJSONArray("children") ?: continue
            for (c in 0 until children.length()) {
                val child = children.getInt(c)
                if (child in nodes.indices) nodes[child].parent = index
            }
        }
        val scene = document.optJSONArray("scenes")
            ?.optJSONObject(document.optInt("scene", 0))
            ?.optJSONArray("nodes")
        if (scene != null) {
            for (i in 0 until scene.length()) roots.add(scene.getInt(i))
        } else {
            for (i in nodes.indices) if (!parented[i]) roots.add(i)
        }

        for (node in nodes) {
            bindTrs.add(Triple(node.t.copyOf(), node.r.copyOf(), node.s.copyOf()))
        }

        document.optJSONArray("skins")?.optJSONObject(0)?.let { skin ->
            val joints = skin.optJSONArray("joints") ?: JSONArray()
            jointNodes = IntArray(joints.length()) { joints.getInt(it) }
            val ibm = skin.optInt("inverseBindMatrices", -1)
            if (ibm >= 0) inverseBind = floats(ibm)
            if (jointNodes.size > MAX_JOINTS) {
                jointNodes = jointNodes.copyOf(MAX_JOINTS)
            }
        }

        document.optJSONArray("animations")?.let { animations ->
            for (index in 0 until animations.length()) {
                val anim = animations.getJSONObject(index)
                parseAnimation(anim)
            }
        }

        val meshes = document.optJSONArray("meshes") ?: JSONArray()
        val materials = document.optJSONArray("materials") ?: JSONArray()
        val nodeArray2 = document.optJSONArray("nodes") ?: JSONArray()
        for (index in nodeArray2.length() - 1 downTo 0) {
            val node = nodes[index]
            if (node.mesh < 0) continue
            val mesh = meshes.getJSONObject(node.mesh)
            val prim = mesh.optJSONArray("primitives") ?: continue
            for (p in 0 until prim.length()) {
                val primitive = prim.getJSONObject(p)
                val material = materials.optJSONObject(primitive.optInt("material", -1))
                buildPrimitive(primitive, material)
            }
        }

        updateGlobals()
        if (clips.isNotEmpty()) setClip(clips.first().name, true)
        update(0f)
    }

    private fun decompose(matrix: FloatArray, node: Node) {
        node.t = floatArrayOf(matrix[12], matrix[13], matrix[14])
        val sx = length(matrix[0], matrix[1], matrix[2])
        val sy = length(matrix[4], matrix[5], matrix[6])
        val sz = length(matrix[8], matrix[9], matrix[10])
        node.s = floatArrayOf(sx, sy, sz)
        if (sx == 0f || sy == 0f || sz == 0f) return
        val m00 = matrix[0] / sx
        val m01 = matrix[1] / sx
        val m02 = matrix[2] / sx
        val m10 = matrix[4] / sy
        val m11 = matrix[5] / sy
        val m12 = matrix[6] / sy
        val m20 = matrix[8] / sz
        val m21 = matrix[9] / sz
        val m22 = matrix[10] / sz
        val trace = m00 + m11 + m22
        val q = when {
            trace > 0f -> {
                val s = kotlin.math.sqrt(trace + 1f) * 2f
                floatArrayOf((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
            }
            m00 > m11 && m00 > m22 -> {
                val s = kotlin.math.sqrt(1f + m00 - m11 - m22) * 2f
                floatArrayOf(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
            }
            m11 > m22 -> {
                val s = kotlin.math.sqrt(1f + m11 - m00 - m22) * 2f
                floatArrayOf((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
            }
            else -> {
                val s = kotlin.math.sqrt(1f + m22 - m00 - m11) * 2f
                floatArrayOf((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
            }
        }
        node.r = normalize(q)
    }

    private fun parseAnimation(anim: JSONObject) {
        val name = anim.optString("name", "clip")
        val samplers = anim.optJSONArray("samplers") ?: return
        val channels = ArrayList<Channel>()
        var duration = 0f
        val channelArray = anim.optJSONArray("channels") ?: return
        for (index in 0 until channelArray.length()) {
            val channel = channelArray.getJSONObject(index)
            val target = channel.optJSONObject("target") ?: continue
            val node = target.optInt("node", -1)
            val path = target.optString("path", "")
            if (node < 0 || path.isEmpty()) continue
            val sampler = samplers.optJSONObject(channel.optInt("sampler", 0)) ?: continue
            val times = floats(sampler.optInt("input", -1))
            val values = floats(sampler.optInt("output", -1))
            if (times.isEmpty() || values.isEmpty()) continue
            duration = maxOf(duration, times.last())
            channels.add(
                Channel(node, path, times, values, sampler.optString("interpolation") == "STEP")
            )
        }
        if (channels.isNotEmpty()) clips.add(Clip(name, channels, duration))
    }

    private fun buildPrimitive(primitive: JSONObject, material: JSONObject?) {
        val attributes = primitive.optJSONObject("attributes") ?: return
        if (!attributes.has("POSITION")) return
        val positions = floats(attributes.getInt("POSITION"))
        val normals = if (attributes.has("NORMAL")) floats(attributes.getInt("NORMAL")) else null
        val uvs = if (attributes.has("TEXCOORD_0")) floats(attributes.getInt("TEXCOORD_0")) else null
        val joints = if (attributes.has("JOINTS_0")) integers(attributes.getInt("JOINTS_0")) else null
        val weights = if (attributes.has("WEIGHTS_0")) floats(attributes.getInt("WEIGHTS_0")) else null
        val indices = if (primitive.has("indices")) integers(primitive.getInt("indices")) else IntArray(0)
        val vertexCount = positions.size / 3
        val pbr = material?.optJSONObject("pbrMetallicRoughness")
        val factor = pbr?.optJSONArray("baseColorFactor")
        val color = floatArrayOf(
            factor?.optDouble(0, 0.75)?.toFloat() ?: 0.75f,
            factor?.optDouble(1, 0.75)?.toFloat() ?: 0.75f,
            factor?.optDouble(2, 0.75)?.toFloat() ?: 0.75f
        )
        val metallic = pbr?.optDouble("metallicFactor", 0.0)?.toFloat() ?: 0f
        val roughness = pbr?.optDouble("roughnessFactor", 0.6)?.toFloat() ?: 0.6f

        // position(3) + normal(3) + color(3) + uv(2) + joints(4) + weights(4)
        val stride = 19
        val data = FloatArray(vertexCount * stride)
        var at = 0
        for (i in 0 until vertexCount) {
            data[at++] = positions[i * 3]
            data[at++] = positions[i * 3 + 1]
            data[at++] = positions[i * 3 + 2]
            if (normals != null) {
                data[at++] = normals[i * 3]
                data[at++] = normals[i * 3 + 1]
                data[at++] = normals[i * 3 + 2]
            } else {
                data[at++] = 0f
                data[at++] = 1f
                data[at++] = 0f
            }
            data[at++] = color[0]
            data[at++] = color[1]
            data[at++] = color[2]
            if (uvs != null) {
                data[at++] = uvs[i * 2]
                data[at++] = uvs[i * 2 + 1]
            } else {
                data[at++] = 0f
                data[at++] = 0f
            }
            for (k in 0 until 4) {
                data[at++] = joints?.getOrNull(i * 4 + k)?.toFloat() ?: 0f
            }
            for (k in 0 until 4) {
                data[at++] = weights?.getOrNull(i * 4 + k) ?: 0f
            }
        }

        val vertexBuffer = IntArray(1)
        val indexBuffer = IntArray(1)
        GLES30.glGenBuffers(1, vertexBuffer, 0)
        GLES30.glGenBuffers(1, indexBuffer, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, data.size * 4, floatBuffer(data), GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer[0])
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 4,
            intBuffer(indices), GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        primitives.add(
            Primitive(vertexBuffer[0], indexBuffer[0], indices.size, color, metallic, roughness)
        )
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(values).position(0)
        return buffer
    }

    private fun intBuffer(values: IntArray): IntBuffer {
        val buffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
        buffer.put(values).position(0)
        return buffer
    }

    /** Local TRS matrix from float arrays (the glTF helper takes JSON arrays). */
    private fun trs(t: FloatArray, r: FloatArray, s: FloatArray): FloatArray =
        FloatArray(16).also { trsInto(t, r, s, it) }

    private fun trsInto(t: FloatArray, r: FloatArray, s: FloatArray, out: FloatArray) {
        var qx = r[0]
        var qy = r[1]
        var qz = r[2]
        var qw = r[3]
        val length = kotlin.math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        if (length < 1e-8f) {
            qx = 0f; qy = 0f; qz = 0f; qw = 1f
        } else {
            qx /= length; qy /= length; qz /= length; qw /= length
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
        out[0] = (1f - 2f * (yy + zz)) * s[0]
        out[1] = 2f * (xy + wz) * s[0]
        out[2] = 2f * (xz - wy) * s[0]
        out[3] = 0f
        out[4] = 2f * (xy - wz) * s[1]
        out[5] = (1f - 2f * (xx + zz)) * s[1]
        out[6] = 2f * (yz + wx) * s[1]
        out[7] = 0f
        out[8] = 2f * (xz + wy) * s[2]
        out[9] = 2f * (yz - wx) * s[2]
        out[10] = (1f - 2f * (xx + yy)) * s[2]
        out[11] = 0f
        out[12] = t[0]
        out[13] = t[1]
        out[14] = t[2]
        out[15] = 1f
    }

    private fun readVector(array: JSONArray?, target: FloatArray) {
        if (array == null) return
        val count = minOf(array.length(), target.size)
        for (i in 0 until count) target[i] = array.optDouble(i, target[i].toDouble()).toFloat()
    }

    private fun floats(index: Int): FloatArray {
        if (index < 0) return FloatArray(0)
        val accessor = document.getJSONArray("accessors").getJSONObject(index)
        val view = document.getJSONArray("bufferViews")
            .getJSONObject(accessor.getInt("bufferView"))
        val components = componentCount(accessor.getString("type"))
        val count = accessor.getInt("count")
        val stride = view.optInt("byteStride", components * 4)
        val offset = view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
        val buffer = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count * components) { i ->
            val base = offset + (i / components) * stride + (i % components) * 4
            buffer.getFloat(base)
        }
    }

    private fun integers(index: Int): IntArray {
        if (index < 0) return IntArray(0)
        val accessor = document.getJSONArray("accessors").getJSONObject(index)
        val view = document.getJSONArray("bufferViews")
            .getJSONObject(accessor.getInt("bufferView"))
        val components = componentCount(accessor.getString("type"))
        val count = accessor.getInt("count")
        val type = accessor.getInt("componentType")
        val size = when (type) {
            5120, 5121 -> 1
            5122, 5123 -> 2
            else -> 4
        }
        val stride = view.optInt("byteStride", components * size)
        val offset = view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
        val buffer = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(count * components) { i ->
            val base = offset + (i / components) * stride + (i % components) * size
            when (type) {
                5120 -> buffer.get(base).toInt()
                5121 -> buffer.get(base).toInt() and 0xFF
                5122 -> buffer.getShort(base).toInt()
                5123 -> buffer.getShort(base).toInt() and 0xFFFF
                else -> buffer.getInt(base)
            }
        }
    }

    private fun componentCount(type: String): Int = when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4" -> 4
        "MAT4" -> 16
        else -> 1
    }

    // --- animation ----------------------------------------------------------

    fun setClip(name: String, loop: Boolean) {
        val clip = clips.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return
        current = clip
        currentClip = clip.name
        clipTime = 0f
        loops = loop
        applyPose(0f)
    }

    fun update(deltaSeconds: Float) {
        val clip = current ?: return
        clipTime += deltaSeconds
        if (clipTime > clip.duration) {
            clipTime = if (loops) clipTime % maxOf(clip.duration, 0.01f) else clip.duration
        }
        applyPose(clipTime)
    }

    private fun applyPose(time: Float) {
        val clip = current ?: return
        for (index in nodes.indices) {
            val node = nodes[index]
            val bind = bindTrs[index]
            System.arraycopy(bind.first, 0, node.t, 0, 3)
            System.arraycopy(bind.second, 0, node.r, 0, 4)
            System.arraycopy(bind.third, 0, node.s, 0, 3)
        }
        for (channel in clip.channels) {
            val node = nodes.getOrNull(channel.node) ?: continue
            val stride = sampleInto(channel, time, poseValue)
            when (channel.path) {
                "translation" -> System.arraycopy(poseValue, 0, node.t, 0, minOf(stride, 3))
                "scale" -> System.arraycopy(poseValue, 0, node.s, 0, minOf(stride, 3))
                "rotation" -> System.arraycopy(poseValue, 0, node.r, 0, minOf(stride, 4))
            }
        }
        for (index in nodes.indices) {
            val node = nodes[index]
            trsInto(node.t, node.r, node.s, node.local)
        }
        updateGlobals()
        computeJoints()
    }

    /** Samples a track into [out] and returns the component stride. */
    private fun sampleInto(channel: Channel, time: Float, out: FloatArray): Int {
        val times = channel.times
        val values = channel.values
        val stride = values.size / times.size
        if (time <= times.first()) {
            System.arraycopy(values, 0, out, 0, stride)
            return stride
        }
        if (time >= times.last()) {
            System.arraycopy(values, values.size - stride, out, 0, stride)
            return stride
        }
        var index = 0
        while (index < times.size - 1 && times[index + 1] < time) index++
        val t0 = times[index]
        val t1 = times[index + 1]
        val span = (t1 - t0).coerceAtLeast(1e-5f)
        val alpha = ((time - t0) / span).coerceIn(0f, 1f)
        val a = index * stride
        val b = (index + 1) * stride
        if (channel.step || alpha <= 0f) {
            System.arraycopy(values, a, out, 0, stride)
            return stride
        }
        if (channel.path == "rotation" && stride == 4) {
            slerpInto(values, a, b, alpha, out)
            return 4
        }
        for (k in 0 until stride) {
            out[k] = values[a + k] + (values[b + k] - values[a + k]) * alpha
        }
        return stride
    }

    private fun slerpInto(values: FloatArray, a: Int, b: Int, alpha: Float, out: FloatArray) {
        var ax = values[a]
        var ay = values[a + 1]
        var az = values[a + 2]
        var aw = values[a + 3]
        var bx = values[b]
        var by = values[b + 1]
        var bz = values[b + 2]
        var bw = values[b + 3]
        var dot = ax * bx + ay * by + az * bz + aw * bw
        if (dot < 0f) {
            bx = -bx; by = -by; bz = -bz; bw = -bw
            dot = -dot
        }
        if (dot > 0.9995f) {
            out[0] = ax + (bx - ax) * alpha
            out[1] = ay + (by - ay) * alpha
            out[2] = az + (bz - az) * alpha
            out[3] = aw + (bw - aw) * alpha
            normalizeInPlace(out)
            return
        }
        val theta = kotlin.math.acos(dot.coerceIn(-1f, 1f))
        val sinTheta = kotlin.math.sin(theta)
        val wa = kotlin.math.sin((1f - alpha) * theta) / sinTheta
        val wb = kotlin.math.sin(alpha * theta) / sinTheta
        out[0] = ax * wa + bx * wb
        out[1] = ay * wa + by * wb
        out[2] = az * wa + bz * wb
        out[3] = aw * wa + bw * wb
    }

    private fun updateGlobals() {
        fun walk(index: Int, parent: FloatArray) {
            val node = nodes[index]
            Mat4.multiplyInto(parent, node.local, node.global)
            for (child in nodes.indices) {
                if (nodes[child].parent == index) walk(child, node.global)
            }
        }
        for (root in roots) walk(root, IDENTITY)
    }

    private fun computeJoints() {
        for (index in jointNodes.indices) {
            val node = nodes[jointNodes[index]]
            if ((index + 1) * 16 <= inverseBind.size) {
                Mat4.multiplyInto(
                    node.global, 0,
                    inverseBind, index * 16,
                    jointMatrices, index * 16
                )
            } else {
                System.arraycopy(node.global, 0, jointMatrices, index * 16, 16)
            }
        }
    }

    private fun normalizeInPlace(q: FloatArray) {
        val length = kotlin.math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (length < 1e-8f) {
            q[0] = 0f
            q[1] = 0f
            q[2] = 0f
            q[3] = 1f
            return
        }
        q[0] /= length
        q[1] /= length
        q[2] /= length
        q[3] /= length
    }

    // --- drawing ------------------------------------------------------------

    fun uploadBones(program: Int, location: Int) {
        if (location < 0) return
        GLES30.glUniformMatrix4fv(
            location, jointNodes.size, false, jointMatrices, 0
        )
    }

    fun draw(primitive: Primitive, program: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, primitive.vertexBuffer)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, primitive.indexBuffer)
        val stride = 19 * 4
        bind(0, 3, stride, 0)
        bind(1, 3, stride, 3 * 4)
        bind(2, 3, stride, 6 * 4)
        bind(3, 2, stride, 9 * 4)
        bind(5, 4, stride, 11 * 4)
        bind(6, 4, stride, 15 * 4)
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES, primitive.count, GLES30.GL_UNSIGNED_INT, 0
        )
        for (index in intArrayOf(0, 1, 2, 3, 5, 6)) {
            GLES30.glDisableVertexAttribArray(index)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun bind(location: Int, size: Int, stride: Int, offset: Int) {
        GLES30.glEnableVertexAttribArray(location)
        GLES30.glVertexAttribPointer(
            location, size, GLES30.GL_FLOAT, false, stride, offset
        )
    }

    private fun length(x: Float, y: Float, z: Float): Float =
        kotlin.math.sqrt(x * x + y * y + z * z)

    private fun normalize(q: FloatArray): FloatArray {
        val length = kotlin.math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (length < 1e-8f) return floatArrayOf(0f, 0f, 0f, 1f)
        return floatArrayOf(q[0] / length, q[1] / length, q[2] / length, q[3] / length)
    }

    companion object {
        const val MAX_JOINTS = 24
        const val PRIMITIVE_STRIDE = 19
        private val IDENTITY = FloatArray(16) { if (it % 5 == 0) 1f else 0f }
    }
}
