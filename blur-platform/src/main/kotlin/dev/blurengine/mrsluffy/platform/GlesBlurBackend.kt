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

package dev.blurengine.mrsluffy.platform

import android.graphics.Color
import android.opengl.GLES20
import androidx.annotation.VisibleForTesting
import dev.blurengine.mrsluffy.algorithms.GaussianKernel
import dev.blurengine.mrsluffy.algorithms.KawasePlanCache
import dev.blurengine.mrsluffy.algorithms.Resampler
import dev.blurengine.mrsluffy.core.BlurAlgorithm
import dev.blurengine.mrsluffy.core.BlurBackend
import dev.blurengine.mrsluffy.core.BlurBackendException
import dev.blurengine.mrsluffy.core.BlurBackendFactory
import dev.blurengine.mrsluffy.core.BlurConfig
import dev.blurengine.mrsluffy.core.BlurInput
import dev.blurengine.mrsluffy.core.BlurMath
import dev.blurengine.mrsluffy.core.BlurOutput
import dev.blurengine.mrsluffy.core.DeviceCapabilities
import dev.blurengine.mrsluffy.platform.gles.ContextLostException
import dev.blurengine.mrsluffy.platform.gles.EglEnvironment
import dev.blurengine.mrsluffy.platform.gles.GlResources
import dev.blurengine.mrsluffy.platform.gles.GlThread
import dev.blurengine.mrsluffy.platform.gles.GlTarget
import dev.blurengine.mrsluffy.platform.gles.Shaders
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * OpenGL ES 2.0 backend for API 29+. Runs Dual Kawase or Gaussian on an offscreen EGL context
 * that it owns, so it never touches the UI renderer's context.
 *
 * Takes [BitmapImage] pairs (ARGB_8888; output mutable). Per frame it uploads the input,
 * renders, and reads the result back into the output bitmap. Textures, framebuffers and
 * programs are kept between frames and reallocated only when sizes change.
 *
 * All GL work runs on a thread the backend owns, created by [initialize] and ended by [release].
 * [initialize], [render] and [release] block until that thread has done the work. So the caller's
 * thread never touches EGL, and the backend can be called from any thread, one call at a time.
 *
 * If the EGL context is lost, the next [render] rebuilds everything and retries once; a second
 * loss throws [BlurBackendException]. [BlurAlgorithm.BOX] is not supported.
 */
public class GlesBlurBackend : BlurBackend {
    override val id: String = ID

    private var config = BlurConfig()
    private var egl: EglEnvironment? = null
    private var gl: GlResources? = null
    private var readback: ByteBuffer? = null

    private val planCache = KawasePlanCache()
    private val gaussWeights = FloatArray(Shaders.MAX_TAPS)
    private val gaussOffsets = FloatArray(Shaders.MAX_TAPS)
    private var gaussCount = 0
    private var gaussRadius = Float.NaN
    private var gaussSpan = Float.NaN

    /** Owns the EGL context: every GL call runs here. Created on initialize, ended on release. */
    private var glThread: GlThread? = null

    // Per-frame handoff to the GL thread without allocating: the images go through fields and
    // the same Runnable is reused for every frame.
    private var pendingInput: BitmapImage? = null
    private var pendingOutput: BitmapImage? = null
    private val renderTask = Runnable { renderOnGlThread(checkNotNull(pendingInput), checkNotNull(pendingOutput)) }

    override fun initialize(config: BlurConfig) {
        updateConfig(config)
        val thread = glThread ?: GlThread("BlurEngine-GL").also { glThread = it }
        try {
            thread.call { if (egl == null) createContext() }
        } catch (e: RuntimeException) {
            // No context, so no thread needed; a later initialize() starts over.
            thread.quit()
            glThread = null
            throw e
        }
    }

    override fun updateConfig(config: BlurConfig) {
        require(supportsAlgorithm(config.resolvedAlgorithm())) { "GLES backend does not support ${config.resolvedAlgorithm()}" }
        this.config = config
    }

    override fun supports(input: BlurInput, output: BlurOutput): Boolean = bitmapPair(input, output)

    override fun render(input: BlurInput, output: BlurOutput) {
        check(glThread != null) { "initialize() must be called before render()" }
        require(supports(input, output)) { "GLES backend needs ARGB_8888 BitmapImage input and mutable ARGB_8888 output" }
        BlurMath.requireSameSize(input, output)
        pendingInput = input as BitmapImage
        pendingOutput = output as BitmapImage
        try {
            checkNotNull(glThread) { "initialize() must be called before render()" }.run(renderTask)
        } finally {
            // Do not keep the caller's bitmaps alive between frames.
            pendingInput = null
            pendingOutput = null
        }
    }

    private fun renderOnGlThread(input: BitmapImage, output: BitmapImage) {
        checkNotNull(egl) { "initialize() must be called before render()" }
        try {
            renderOnce(input, output)
        } catch (lost: ContextLostException) {
            recreateContext()
            try {
                renderOnce(input, output)
            } catch (again: ContextLostException) {
                throw BlurBackendException("EGL context lost again after recreating it", again)
            }
        }
    }

    override fun release() {
        val thread = glThread ?: return
        try {
            thread.call { releaseContext() }
        } finally {
            thread.quit()
            glThread = null
        }
    }

    private fun releaseContext() {
        val env = egl ?: return
        try {
            env.makeCurrent()
            gl?.delete()
        } catch (_: ContextLostException) {
            // Objects died with the context.
        } catch (_: BlurBackendException) {
        } finally {
            gl = null
            env.destroy()
            egl = null
            readback = null
        }
    }

    /** Test hook: destroys the EGL context behind the backend's back to exercise recovery. */
    @VisibleForTesting
    internal fun simulateContextLoss() {
        glThread?.call { egl?.destroyContextForTest() }
    }

    private fun createContext() {
        val env = EglEnvironment()
        env.create()
        try {
            env.makeCurrent()
            gl = GlResources()
        } catch (e: ContextLostException) {
            env.destroy()
            throw BlurBackendException("EGL context lost during setup", e)
        } catch (e: RuntimeException) {
            env.destroy()
            throw e as? BlurBackendException ?: BlurBackendException("GLES setup failed", e)
        } finally {
            env.releaseCurrent()
        }
        egl = env
    }

    private fun recreateContext() {
        // The old context's objects are already gone; drop the handles without GL calls.
        gl?.let { it.base.forget(); it.scratch.forget(); it.output.forget(); it.levels.forEach(GlTarget::forget) }
        gl = null
        egl?.destroy()
        egl = null
        createContext()
    }

    private fun renderOnce(input: BitmapImage, output: BitmapImage) {
        val env = checkNotNull(egl)
        env.makeCurrent()
        val w = input.width
        val h = input.height
        val buffer: ByteBuffer
        try {
            val s = checkNotNull(gl)
            s.upload(input.bitmap)

            val factor = config.downsample
            s.base.ensure(Resampler.scaledSize(w, factor), Resampler.scaledSize(h, factor))
            s.begin(s.downsample, s.inputTextureId, s.base)
            val tap = if (factor == 1) 0f else factor / 4f
            GLES20.glUniform2f(s.downsample.uniform("uOffset"), tap / w, tap / h)
            s.drawQuad()

            val radius = config.effectiveRadius
            if (radius >= 0.5f) {
                when (config.resolvedAlgorithm()) {
                    BlurAlgorithm.GAUSSIAN -> gaussian(s, radius)
                    else -> kawase(s, radius)
                }
            }

            s.output.ensure(w, h)
            s.begin(s.finish, s.base.texture, s.output)
            GLES20.glUniform1f(s.finish.uniform("uSaturation"), config.saturation)
            GLES20.glUniform1f(s.finish.uniform("uBrightness"), config.brightness)
            val tint = config.tintColor
            GLES20.glUniform3f(s.finish.uniform("uTint"), Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f)
            GLES20.glUniform1f(s.finish.uniform("uTintAlpha"), config.tintAlpha)
            s.drawQuad()

            buffer = readbackBuffer(w * h * 4)
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) throw BlurBackendException("GL error 0x${error.toString(16)}")
        } finally {
            env.releaseCurrent()
        }
        // Bitmap memory is RGBA bytes, premultiplied, which is exactly what glReadPixels returned.
        buffer.position(0)
        output.bitmap.copyPixelsFromBuffer(buffer)
    }

    private fun kawase(s: GlResources, radius: Float) {
        val plan = planCache.get(radius, s.base.width, s.base.height, config.quality.kawaseMaxIterations)
        var prev: GlTarget = s.base
        for (i in 0 until plan.iterations) {
            val level = s.level(i)
            level.ensure(Resampler.scaledSize(prev.width, 2), Resampler.scaledSize(prev.height, 2))
            s.begin(s.kawaseDown, prev.texture, level)
            GLES20.glUniform2f(s.kawaseDown.uniform("uOffset"), plan.offset / prev.width, plan.offset / prev.height)
            s.drawQuad()
            prev = level
        }
        for (i in plan.iterations - 1 downTo 0) {
            val target = if (i == 0) s.base else s.level(i - 1)
            s.begin(s.kawaseUp, prev.texture, target)
            GLES20.glUniform2f(s.kawaseUp.uniform("uOffset"), plan.offset / prev.width, plan.offset / prev.height)
            s.drawQuad()
            prev = target
        }
    }

    private fun gaussian(s: GlResources, radius: Float) {
        buildGaussianTaps(radius, config.quality.gaussianSigmaSpan)
        val p = s.gaussian
        s.scratch.ensure(s.base.width, s.base.height)
        s.begin(p, s.base.texture, s.scratch)
        setGaussianUniforms(p.uniform("uWeights"), p.uniform("uOffsets"), p.uniform("uCount"))
        GLES20.glUniform2f(p.uniform("uStep"), 1f / s.base.width, 0f)
        s.drawQuad()
        s.begin(p, s.scratch.texture, s.base)
        setGaussianUniforms(p.uniform("uWeights"), p.uniform("uOffsets"), p.uniform("uCount"))
        GLES20.glUniform2f(p.uniform("uStep"), 0f, 1f / s.base.height)
        s.drawQuad()
    }

    private fun setGaussianUniforms(weights: Int, offsets: Int, count: Int) {
        GLES20.glUniform1fv(weights, Shaders.MAX_TAPS, gaussWeights, 0)
        GLES20.glUniform1fv(offsets, Shaders.MAX_TAPS, gaussOffsets, 0)
        GLES20.glUniform1i(count, gaussCount)
    }

    /**
     * Folds the CPU kernel into at most [Shaders.MAX_TAPS] taps per side. Runs of 2 weights
     * are exact with bilinear filtering; longer runs (very large kernels) approximate.
     */
    private fun buildGaussianTaps(radius: Float, span: Float) {
        if (radius == gaussRadius && span == gaussSpan) return
        val k = GaussianKernel.weights(radius, span)
        val half = k.size / 2
        val run = maxOf(2, ceil(half / (Shaders.MAX_TAPS - 1).toFloat()).toInt())
        gaussWeights[0] = k[half]
        gaussOffsets[0] = 0f
        var count = 1
        var i = 1
        while (i <= half) {
            val end = minOf(half, i + run - 1)
            var weight = 0f
            var moment = 0f
            for (j in i..end) {
                weight += k[half + j]
                moment += j * k[half + j]
            }
            gaussWeights[count] = weight
            gaussOffsets[count] = moment / weight
            count++
            i = end + 1
        }
        gaussCount = count
        gaussRadius = radius
        gaussSpan = span
    }

    private fun readbackBuffer(bytes: Int): ByteBuffer {
        val current = readback
        if (current != null && current.capacity() >= bytes) {
            current.clear()
            current.limit(bytes)
            return current
        }
        return ByteBuffer.allocateDirect(bytes).also { readback = it }
    }

    public companion object {
        public const val ID: String = "gles"

        internal fun supportsAlgorithm(algorithm: BlurAlgorithm): Boolean =
            algorithm == BlurAlgorithm.DUAL_KAWASE || algorithm == BlurAlgorithm.GAUSSIAN

        public val factory: BlurBackendFactory = object : BlurBackendFactory {
            override val id: String = ID
            override fun isSupported(config: BlurConfig, capabilities: DeviceCapabilities): Boolean =
                capabilities.supportsGles && supportsAlgorithm(config.resolvedAlgorithm())
            override fun create(): BlurBackend = GlesBlurBackend()
        }
    }
}
