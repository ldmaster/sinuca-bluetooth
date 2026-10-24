package com.ldmaster.sinuca.render

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import kotlin.math.sqrt

/**
 * One directional shadow map for the single key light. The ortho box is sized
 * from the geometry that actually casts onto the floor, not from the table
 * bounding box, so the apron shadow is not clipped.
 */
class ShadowMap {

    var size = 2048
        private set
    var texture = 0
        private set
    var texelWorld = 0.01f
        private set

    val matrix = FloatArray(16)

    private var framebuffer = 0
    private var program = 0
    private var uMvp = 0
    private var previousViewport = IntArray(4)

    fun create(desiredSize: Int, lightDirection: FloatArray, halfWidth: Float, halfHeight: Float) {
        release()
        size = desiredSize
        program = Gl.link(Shaders.DEPTH_VERTEX, Shaders.DEPTH_FRAGMENT)
        if (program == 0) return
        uMvp = GLES30.glGetUniformLocation(program, "uMVP")

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, size, size, 0,
            GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_TEXTURE_2D, texture, 0
        )
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "shadow framebuffer incomplete 0x${Integer.toHexString(status)}")
        }

        buildMatrix(lightDirection, halfWidth, halfHeight)
        texelWorld = (2f * halfWidth) / size
        Log.i(TAG, "shadow map $size texel=${"%.2f".format(texelWorld * 1000f)}mm")
    }

    private fun buildMatrix(lightDirection: FloatArray, halfWidth: Float, halfHeight: Float) {
        val length = sqrt(
            lightDirection[0] * lightDirection[0] +
                lightDirection[1] * lightDirection[1] +
                lightDirection[2] * lightDirection[2]
        )
        val dx = lightDirection[0] / length
        val dy = lightDirection[1] / length
        val dz = lightDirection[2] / length
        val centerX = 0f
        val centerY = 0.45f
        val centerZ = 0f
        val distance = 9f
        val projection = FloatArray(16)
        val view = FloatArray(16)
        Matrix.orthoM(projection, 0, -halfWidth, halfWidth, -halfHeight, halfHeight, 0.5f, 18f)
        Matrix.setLookAtM(
            view, 0,
            centerX + dx * distance, centerY + dy * distance, centerZ + dz * distance,
            centerX, centerY, centerZ,
            0f, 1f, 0f
        )
        Matrix.multiplyMM(matrix, 0, projection, 0, view, 0)
    }

    /** Binds the depth target, clears it and sets the GL state for a depth-only pass. */
    fun begin() {
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, previousViewport, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, size, size)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_FRONT)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, matrix, 0)
    }

    fun useProgram() {
        GLES30.glUseProgram(program)
    }

    fun setMvp(mvp: FloatArray) {
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
    }

    fun end() {
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glUseProgram(0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
    }

    fun release() {
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
        if (framebuffer != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            framebuffer = 0
        }
        if (texture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            texture = 0
        }
    }

    companion object {
        private const val TAG = "ShadowMap"
    }
}
