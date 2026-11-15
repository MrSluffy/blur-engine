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

import dev.blurengine.mrsluffy.algorithms.BlurKernel
import dev.blurengine.mrsluffy.algorithms.BoxBlur
import dev.blurengine.mrsluffy.algorithms.ColorAdjust
import dev.blurengine.mrsluffy.algorithms.DualKawaseBlur
import dev.blurengine.mrsluffy.algorithms.FastGaussianBlur
import dev.blurengine.mrsluffy.algorithms.GaussianBlur
import dev.blurengine.mrsluffy.algorithms.Resampler
import dev.blurengine.mrsluffy.core.BlurAlgorithm
import dev.blurengine.mrsluffy.core.BlurBackend
import dev.blurengine.mrsluffy.core.BlurBackendFactory
import dev.blurengine.mrsluffy.core.BlurConfig
import dev.blurengine.mrsluffy.core.BlurInput
import dev.blurengine.mrsluffy.core.BlurMath
import dev.blurengine.mrsluffy.core.BlurOutput
import dev.blurengine.mrsluffy.core.BlurQuality
import dev.blurengine.mrsluffy.core.DeviceCapabilities
import dev.blurengine.mrsluffy.core.PixelBuffer
import dev.blurengine.mrsluffy.core.PixelBufferImage

/**
 * CPU backend: downsample, blur, upscale, color adjust and tint. Works on every API level and
 * supports every algorithm, so it is the last-resort fallback. Slow for large images; call it
 * off the main thread.
 *
 * Accepts [PixelBufferImage] or [BitmapImage] pairs. Bitmap pixels are processed unpremultiplied.
 */
public class SoftwareBlurBackend(
    /**
     * When true, GAUSSIAN and DUAL_KAWASE use [FastGaussianBlur] (three sigma-matched box passes):
     * about the cost of BOX at any radius, with a close but not exact Gaussian. Meant for the
     * fallback path; the default exact kernels are the reference for GPU comparisons.
     */
    private val fastApproximation: Boolean = false,
) : BlurBackend {
    override val id: String = ID

    private var config: BlurConfig = BlurConfig()
    private var kernel: BlurKernel? = null
    private var kernelAlgorithm: BlurAlgorithm? = null
    private var kernelQuality: BlurQuality? = null
    private val small = PixelBuffer(1, 1)
    private val blurred = PixelBuffer(1, 1)
    private val bitmapIn = PixelBuffer(1, 1)
    private val bitmapOut = PixelBuffer(1, 1)

    override fun initialize(config: BlurConfig): Unit = updateConfig(config)

    override fun updateConfig(config: BlurConfig) {
        this.config = config
        val algorithm = config.resolvedAlgorithm()
        // Kernel parameters depend on quality; rebuild only when those change.
        if (algorithm != kernelAlgorithm || config.quality != kernelQuality) {
            kernel?.release()
            kernel = createKernel(algorithm, config.quality)
            kernelAlgorithm = algorithm
            kernelQuality = config.quality
        }
    }

    override fun supports(input: BlurInput, output: BlurOutput): Boolean =
        (input is PixelBufferImage && output is PixelBufferImage) || bitmapPair(input, output)

    override fun render(input: BlurInput, output: BlurOutput) {
        val k = checkNotNull(kernel) { "initialize() must be called before render()" }
        require(supports(input, output)) { "unsupported images: ${input::class.simpleName} -> ${output::class.simpleName}" }
        BlurMath.requireSameSize(input, output)
        if (input is PixelBufferImage && output is PixelBufferImage) {
            renderBuffers(k, input.buffer, output.buffer)
        } else {
            BitmapPixels.read((input as BitmapImage).bitmap, bitmapIn)
            renderBuffers(k, bitmapIn, bitmapOut)
            BitmapPixels.write(bitmapOut, (output as BitmapImage).bitmap)
        }
    }

    private fun renderBuffers(k: BlurKernel, input: PixelBuffer, output: PixelBuffer) {
        Resampler.downsample(input, small, config.downsample)
        k.blur(small, blurred, config.effectiveRadius)
        Resampler.upscale(blurred, output, input.width, input.height)
        ColorAdjust.apply(output, config.saturation, config.brightness, config.tintColor, config.tintAlpha)
    }

    override fun release() {
        kernel?.release()
        kernel = null
        kernelAlgorithm = null
        kernelQuality = null
    }

    private fun createKernel(algorithm: BlurAlgorithm, quality: BlurQuality): BlurKernel = when (algorithm) {
        BlurAlgorithm.GAUSSIAN, BlurAlgorithm.DUAL_KAWASE, BlurAlgorithm.AUTO ->
            if (fastApproximation) FastGaussianBlur() else exactKernel(algorithm, quality)
        BlurAlgorithm.BOX -> BoxBlur(quality.boxPasses)
    }

    private fun exactKernel(algorithm: BlurAlgorithm, quality: BlurQuality): BlurKernel =
        if (algorithm == BlurAlgorithm.GAUSSIAN) GaussianBlur(quality.gaussianSigmaSpan)
        else DualKawaseBlur(quality.kawaseMaxIterations)

    public companion object {
        public const val ID: String = "software"

        public val factory: BlurBackendFactory = object : BlurBackendFactory {
            override val id: String = ID
            override fun isSupported(config: BlurConfig, capabilities: DeviceCapabilities): Boolean = true
            override fun create(): BlurBackend = SoftwareBlurBackend()
        }

        /** Like [factory], with [fastApproximation] on. Used by BlurView and Compose when GLES is unavailable. */
        public val fastFactory: BlurBackendFactory = object : BlurBackendFactory {
            override val id: String = ID
            override fun isSupported(config: BlurConfig, capabilities: DeviceCapabilities): Boolean = true
            override fun create(): BlurBackend = SoftwareBlurBackend(fastApproximation = true)
        }
    }
}
