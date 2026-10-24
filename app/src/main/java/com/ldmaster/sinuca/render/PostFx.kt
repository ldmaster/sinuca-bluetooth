package com.ldmaster.sinuca.render

import android.opengl.GLES30
import android.util.Log

/**
 * Bloom + tone map + vignette + grain, run after the scene is resolved to a
 * texture. Everything here writes display-referred sRGB: this is the only
 * place where gamma is applied.
 */
class PostFx {

    var exposure = 1.05f
    var bloomStrength = 0.55f
    var vignette = 0.34f
    var grain = 0.0025f

    private var quad = 0
    private var brightProgram = 0
    private var blurProgram = 0
    private var compositeProgram = 0

    private var uBrightScene = 0
    private var uBrightThreshold = 0
    private var uBlurImage = 0
    private var uBlurDirection = 0

    private var uScene = 0
    private var uBloom0 = 0
    private var uBloom1 = 0
    private var uBloom2 = 0
    private var uBloomStrength = 0
    private var uExposure = 0
    private var uVignette = 0
    private var uGrain = 0
    private var uTime = 0

    private val levels = 3
    private var levelCount = 0
    private val framebuffers = IntArray(levels)
    private val sourceTextures = IntArray(levels)
    private val blurTextures = IntArray(levels)
    private val levelWidth = IntArray(levels)
    private val levelHeight = IntArray(levels)

    val ready: Boolean get() = compositeProgram != 0 && quad != 0

    fun create(width: Int, height: Int) {
        release()
        quad = Gl.fullScreenQuad()
        brightProgram = Gl.link(Shaders.QUAD_VERTEX, Shaders.BRIGHT_FRAGMENT)
        blurProgram = Gl.link(Shaders.QUAD_VERTEX, Shaders.BLUR_FRAGMENT)
        compositeProgram = Gl.link(Shaders.QUAD_VERTEX, Shaders.COMPOSITE_FRAGMENT)
        if (brightProgram != 0) {
            uBrightScene = GLES30.glGetUniformLocation(brightProgram, "uScene")
            uBrightThreshold = GLES30.glGetUniformLocation(brightProgram, "uThreshold")
        }
        if (blurProgram != 0) {
            uBlurImage = GLES30.glGetUniformLocation(blurProgram, "uImage")
            uBlurDirection = GLES30.glGetUniformLocation(blurProgram, "uDirection")
        }
        if (compositeProgram != 0) {
            uScene = GLES30.glGetUniformLocation(compositeProgram, "uScene")
            uBloom0 = GLES30.glGetUniformLocation(compositeProgram, "uBloom0")
            uBloom1 = GLES30.glGetUniformLocation(compositeProgram, "uBloom1")
            uBloom2 = GLES30.glGetUniformLocation(compositeProgram, "uBloom2")
            uBloomStrength = GLES30.glGetUniformLocation(compositeProgram, "uBloomStrength")
            uExposure = GLES30.glGetUniformLocation(compositeProgram, "uExposure")
            uVignette = GLES30.glGetUniformLocation(compositeProgram, "uVignette")
            uGrain = GLES30.glGetUniformLocation(compositeProgram, "uGrain")
            uTime = GLES30.glGetUniformLocation(compositeProgram, "uTime")
        }
        if (!ready) Log.e(TAG, "post programs failed to link")
        resize(width, height)
    }

    fun resize(width: Int, height: Int) {
        releaseTargets()
        levelCount = 0
        var w = (width / 2).coerceAtLeast(1)
        var h = (height / 2).coerceAtLeast(1)
        for (level in 0 until levels) {
            levelWidth[level] = w
            levelHeight[level] = h
            attach(level)
            levelCount++
            w = (w / 2).coerceAtLeast(1)
            h = (h / 2).coerceAtLeast(1)
        }
    }

    private fun attach(level: Int) {
        val ids = IntArray(1)
        if (framebuffers[level] == 0) {
            GLES30.glGenFramebuffers(1, ids, 0)
            framebuffers[level] = ids[0]
        }
        sourceTextures[level] = texture(levelWidth[level], levelHeight[level])
        blurTextures[level] = texture(levelWidth[level], levelHeight[level])
    }

    private fun texture(width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    fun render(sceneTexture: Int, screenWidth: Int, screenHeight: Int, seconds: Float) {
        if (!ready || levelCount == 0) return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)

        GLES30.glUseProgram(brightProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTexture)
        GLES30.glUniform1i(uBrightScene, 0)
        GLES30.glUniform1f(uBrightThreshold, 0.72f)
        draw(0, levelWidth[0], levelHeight[0], sourceTextures[0])

        for (level in 1 until levelCount) {
            GLES30.glUseProgram(blurProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTextures[level - 1])
            GLES30.glUniform1i(uBlurImage, 0)
            GLES30.glUniform2f(uBlurDirection, 0f, 0f)
            draw(level, levelWidth[level], levelHeight[level], sourceTextures[level])
        }

        for (level in 0 until levelCount) {
            GLES30.glUseProgram(blurProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTextures[level])
            GLES30.glUniform1i(uBlurImage, 0)
            GLES30.glUniform2f(uBlurDirection, 1f / levelWidth[level], 0f)
            draw(level, levelWidth[level], levelHeight[level], blurTextures[level])

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTextures[level])
            GLES30.glUniform2f(uBlurDirection, 0f, 1f / levelHeight[level])
            draw(level, levelWidth[level], levelHeight[level], sourceTextures[level])
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenWidth, screenHeight)
        GLES30.glUseProgram(compositeProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTexture)
        GLES30.glUniform1i(uScene, 0)
        bindUnit(1, sourceTextures[0], uBloom0)
        bindUnit(2, sourceTextures[1], uBloom1)
        bindUnit(3, sourceTextures[2], uBloom2)
        GLES30.glUniform1f(uBloomStrength, bloomStrength)
        GLES30.glUniform1f(uExposure, exposure)
        GLES30.glUniform1f(uVignette, vignette)
        GLES30.glUniform1f(uGrain, grain)
        GLES30.glUniform1f(uTime, seconds)
        Gl.drawQuad(quad)
        for (unit in 0..3) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun bindUnit(index: Int, texture: Int, location: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + index)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(location, index)
    }

    private fun draw(level: Int, width: Int, height: Int, target: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[level])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, target, 0
        )
        GLES30.glViewport(0, 0, width, height)
        Gl.drawQuad(quad)
    }

    private fun releaseTargets() {
        for (level in 0 until levels) {
            if (sourceTextures[level] != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(sourceTextures[level]), 0)
                sourceTextures[level] = 0
            }
            if (blurTextures[level] != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(blurTextures[level]), 0)
                blurTextures[level] = 0
            }
            if (framebuffers[level] != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffers[level]), 0)
                framebuffers[level] = 0
            }
        }
    }

    fun release() {
        releaseTargets()
        if (brightProgram != 0) {
            GLES30.glDeleteProgram(brightProgram)
            brightProgram = 0
        }
        if (blurProgram != 0) {
            GLES30.glDeleteProgram(blurProgram)
            blurProgram = 0
        }
        if (compositeProgram != 0) {
            GLES30.glDeleteProgram(compositeProgram)
            compositeProgram = 0
        }
        if (quad != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(quad), 0)
            quad = 0
        }
    }

    companion object {
        private const val TAG = "PostFx"
    }
}
