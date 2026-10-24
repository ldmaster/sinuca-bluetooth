package com.ldmaster.sinuca.render

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small shared helpers so the renderer, the shadow map and the post pass agree. */
object Gl {
    private const val TAG = "Gl"

    fun compile(type: Int, source: String): Int {
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, source)
        GLES30.glCompileShader(id)
        val status = IntArray(1)
        GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "shader compile failed: ${GLES30.glGetShaderInfoLog(id)}")
            GLES30.glDeleteShader(id)
            return 0
        }
        return id
    }

    /** Returns 0 instead of throwing: a rejected shader must not crash the game. */
    fun link(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertex == 0 || fragment == 0) {
            if (vertex != 0) GLES30.glDeleteShader(vertex)
            if (fragment != 0) GLES30.glDeleteShader(fragment)
            return 0
        }
        val id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vertex)
        GLES30.glAttachShader(id, fragment)
        GLES30.glLinkProgram(id)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "program link failed: ${GLES30.glGetProgramInfoLog(id)}")
            GLES30.glDeleteProgram(id)
            return 0
        }
        return id
    }

    fun fullScreenQuad(): Int {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        val vertices = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            -1f, 1f,
            1f, -1f,
            1f, 1f
        )
        val buffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(vertices).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, vertices.size * 4, buffer, GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return ids[0]
    }

    fun drawQuad(quadBuffer: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun hasExtension(name: String): Boolean {
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: return false
        return extensions.split(' ').any { it == name }
    }
}
