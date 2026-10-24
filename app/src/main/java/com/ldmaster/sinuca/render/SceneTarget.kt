package com.ldmaster.sinuca.render

import android.opengl.GLES30
import android.util.Log

/**
 * Offscreen scene target. The scene is rendered with MSAA into renderbuffers
 * and resolved into a texture that the post pass can read, which is what makes
 * tone mapping and bloom possible.
 *
 * Multisample resolve is only guaranteed for colour in GLES 3.0 and only when
 * the source and destination formats are identical, so the resolve texture is
 * created with the same internal format as the renderbuffer. Depth is never
 * resolved: ambient occlusion comes from the shadow map and the detail atlas.
 */
class SceneTarget {

    var width = 1
        private set
    var height = 1
        private set
    var samples = 0
        private set

    var texture = 0
        private set

    private var sceneFbo = 0
    private var resolveFbo = 0
    private var colorRenderbuffer = 0
    private var depthRenderbuffer = 0
    private var colorIsTexture = false
    private var internalFormat = GLES30.GL_RGBA8

    private var maxSamples = 1
    private var maxRenderbuffer = 2048

    fun create(width: Int, height: Int, requestedSamples: Int, hdr: Boolean) {
        release()
        readLimits()
        this.width = width.coerceIn(1, maxRenderbuffer)
        this.height = height.coerceIn(1, maxRenderbuffer)
        internalFormat = if (hdr && supportsFloatTarget()) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
        samples = requestedSamples.coerceIn(1, maxSamples)
        if (samples >= 2) {
            buildMultisample()
        }
        if (samples < 2 || !isComplete(sceneFbo)) {
            releaseObjects()
            samples = 1
            buildSingleSample()
        }
        Log.i(
            TAG,
            "scene target ${this.width}x${this.height} samples=$samples " +
                "format=${if (internalFormat == GLES30.GL_RGBA16F) "rgba16f" else "rgba8"}"
        )
    }

    fun resize(width: Int, height: Int) {
        val hdr = internalFormat == GLES30.GL_RGBA16F
        val currentSamples = samples
        create(width, height, currentSamples, hdr)
    }

    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glViewport(0, 0, width, height)
    }

    fun resolve(): Int {
        if (samples < 2) return texture
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, resolveFbo)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, sceneFbo)
        GLES30.glBlitFramebuffer(
            0, 0, width, height,
            0, 0, width, height,
            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST
        )
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        return texture
    }

    fun release() {
        releaseObjects()
    }

    private fun releaseObjects() {
        if (colorRenderbuffer != 0) {
            GLES30.glDeleteRenderbuffers(1, intArrayOf(colorRenderbuffer), 0)
            colorRenderbuffer = 0
        }
        if (depthRenderbuffer != 0) {
            GLES30.glDeleteRenderbuffers(1, intArrayOf(depthRenderbuffer), 0)
            depthRenderbuffer = 0
        }
        if (colorIsTexture && texture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        }
        texture = 0
        colorIsTexture = false
        if (sceneFbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0)
            sceneFbo = 0
        }
        if (resolveFbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(resolveFbo), 0)
            resolveFbo = 0
        }
    }

    private fun buildMultisample() {
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        sceneFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)

        GLES30.glGenRenderbuffers(1, ids, 0)
        colorRenderbuffer = ids[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, colorRenderbuffer)
        GLES30.glRenderbufferStorageMultisample(
            GLES30.GL_RENDERBUFFER, samples, internalFormat, width, height
        )
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_RENDERBUFFER, colorRenderbuffer
        )

        GLES30.glGenRenderbuffers(1, ids, 0)
        depthRenderbuffer = ids[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRenderbuffer)
        GLES30.glRenderbufferStorageMultisample(
            GLES30.GL_RENDERBUFFER, samples, GLES30.GL_DEPTH_COMPONENT24, width, height
        )
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_RENDERBUFFER, depthRenderbuffer
        )
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)

        if (!isComplete(sceneFbo)) {
            Log.w(TAG, "multisample target incomplete, falling back")
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return
        }

        GLES30.glGenFramebuffers(1, ids, 0)
        resolveFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, resolveFbo)
        texture = createTexture(internalFormat, width, height)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, texture, 0
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun buildSingleSample() {
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        sceneFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)

        texture = createTexture(internalFormat, width, height)
        colorIsTexture = true
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, texture, 0
        )

        GLES30.glGenRenderbuffers(1, ids, 0)
        depthRenderbuffer = ids[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRenderbuffer)
        GLES30.glRenderbufferStorage(
            GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, width, height
        )
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_RENDERBUFFER, depthRenderbuffer
        )
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun createTexture(format: Int, width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, format, width, height, 0,
            GLES30.GL_RGBA, if (format == GLES30.GL_RGBA16F) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE,
            null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private fun isComplete(framebuffer: Int): Boolean {
        val previous = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, previous, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, previous[0])
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "framebuffer status 0x${Integer.toHexString(status)}")
            return false
        }
        return true
    }

    private fun readLimits() {
        val samples = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, samples, 0)
        maxSamples = samples[0].coerceAtLeast(1)
        val renderbuffer = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_RENDERBUFFER_SIZE, renderbuffer, 0)
        maxRenderbuffer = renderbuffer[0].coerceAtLeast(1024)
    }

    private fun supportsFloatTarget(): Boolean {
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: return false
        return extensions.contains("EXT_color_buffer_float") ||
            extensions.contains("EXT_color_buffer_half_float")
    }

    companion object {
        private const val TAG = "SceneTarget"
    }
}
