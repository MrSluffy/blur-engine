/*
 * Copyright 2026 MrSluffy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.blurengine.mrsluffy.platform.gles

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import dev.blurengine.mrsluffy.core.BlurBackendException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** A texture with a framebuffer attached, reallocated only when its size changes. */
internal class GlTarget {
    var texture = 0
        private set
    var framebuffer = 0
        private set
    var width = 0
        private set
    var height = 0
        private set

    fun ensure(w: Int, h: Int) {
        if (texture == 0) {
            texture = GlResources.createTexture()
            val ids = IntArray(1)
            GLES20.glGenFramebuffers(1, ids, 0)
            framebuffer = ids[0]
        }
        if (w == width && h == height) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw BlurBackendException("framebuffer ${w}x$h incomplete: 0x${status.toString(16)}")
        }
        width = w
        height = h
    }

    fun delete() {
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        forget()
    }

    /** Drops handles without GL calls (the context that owned them is gone). */
    fun forget() {
        texture = 0; framebuffer = 0; width = 0; height = 0
    }
}

/** A compiled program and its uniform locations. */
internal class GlProgram(vertex: String, fragment: String, uniformNames: List<String>) {
    val id: Int = GlResources.link(vertex, fragment)
    private val locations = uniformNames.associateWith { GLES20.glGetUniformLocation(id, it) }

    fun uniform(name: String): Int = locations.getValue(name)
}

/**
 * Programs, textures and framebuffers for one EGL context. Every method must run with that
 * context current.
 */
internal class GlResources {
    val downsample = GlProgram(Shaders.VERTEX, Shaders.DOWNSAMPLE, listOf("uTex", "uOffset"))
    val kawaseDown = GlProgram(Shaders.VERTEX, Shaders.KAWASE_DOWN, listOf("uTex", "uOffset"))
    val kawaseUp = GlProgram(Shaders.VERTEX, Shaders.KAWASE_UP, listOf("uTex", "uOffset"))
    val gaussian = GlProgram(Shaders.VERTEX, Shaders.GAUSSIAN, listOf("uTex", "uStep", "uWeights", "uOffsets", "uCount"))
    val finish = GlProgram(Shaders.VERTEX, Shaders.FINISH, listOf("uTex", "uSaturation", "uBrightness", "uTint", "uTintAlpha"))
    private val programs = listOf(downsample, kawaseDown, kawaseUp, gaussian, finish)

    private var inputTexture = createTexture()
    private var inputWidth = 0
    private var inputHeight = 0
    val inputTextureId: Int get() = inputTexture

    /** [base] holds the downsampled image and, after blurring, the blurred result. */
    val base = GlTarget()
    val scratch = GlTarget()
    val output = GlTarget()
    val levels = ArrayList<GlTarget>()

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
    }

    init {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_DITHER)
    }

    /** Uploads [bitmap] (ARGB_8888, premultiplied) to the input texture, reusing storage when the size is unchanged. */
    fun upload(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        if (bitmap.width == inputWidth && bitmap.height == inputHeight) {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        } else {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            inputWidth = bitmap.width
            inputHeight = bitmap.height
        }
    }

    fun level(index: Int): GlTarget {
        while (levels.size <= index) levels.add(GlTarget())
        return levels[index]
    }

    /** Binds [target] and [sourceTexture] and selects [program]; set uniforms, then call [drawQuad]. */
    fun begin(program: GlProgram, sourceTexture: Int, target: GlTarget) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glViewport(0, 0, target.width, target.height)
        GLES20.glUseProgram(program.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
        GLES20.glUniform1i(program.uniform("uTex"), 0)
    }

    fun drawQuad() {
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun delete() {
        programs.forEach { GLES20.glDeleteProgram(it.id) }
        GLES20.glDeleteTextures(1, intArrayOf(inputTexture), 0)
        base.delete(); scratch.delete(); output.delete()
        levels.forEach { it.delete() }
        levels.clear()
    }

    companion object {
        fun createTexture(): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return ids[0]
        }

        fun link(vertex: String, fragment: String): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glBindAttribLocation(program, 0, "aPos")
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                throw BlurBackendException("program link failed: $log")
            }
            return program
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw BlurBackendException("shader compile failed: $log")
            }
            return shader
        }
    }
}
