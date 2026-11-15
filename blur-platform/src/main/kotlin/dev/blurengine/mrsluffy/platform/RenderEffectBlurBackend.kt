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

import android.graphics.BlendMode
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi
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
import java.nio.ByteBuffer

/**
 * Bitmap-to-bitmap blur using the platform [RenderEffect] (Gaussian) on API 31+.
 *
 * The input is drawn downsampled into a [RenderNode] carrying the effect, rendered offscreen by
 * a [HardwareRenderer] into an [ImageReader], read back, and scaled up into the output.
 *
 * This backend is for blurring bitmaps. To blur a view that is already on screen, apply
 * [BlurRenderEffects.create] with View.setRenderEffect instead; that needs no readback.
 * Supports [BlurAlgorithm.GAUSSIAN] and [BlurAlgorithm.AUTO].
 */
@RequiresApi(Build.VERSION_CODES.S)
public class RenderEffectBlurBackend : BlurBackend {
    override val id: String = ID

    private var config = BlurConfig()
    private var effect: RenderEffect? = null
    private var renderer: HardwareRenderer? = null
    private val node = RenderNode("blur-engine")
    private var reader: ImageReader? = null
    private var small: Bitmap? = null
    private var packed: ByteBuffer? = null

    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val copyPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.SRC }
    private val rect = Rect()
    private val canvas = Canvas()

    override fun initialize(config: BlurConfig) {
        updateConfig(config)
        if (renderer == null) {
            renderer = HardwareRenderer().apply {
                setContentRoot(node)
                isOpaque = false
            }
        }
    }

    override fun updateConfig(config: BlurConfig) {
        require(supportsAlgorithm(config.algorithm)) { "RenderEffect backend does not support ${config.algorithm}" }
        this.config = config
        effect = BlurRenderEffects.create(config, config.effectiveRadius)
    }

    override fun supports(input: BlurInput, output: BlurOutput): Boolean =
        input is BitmapImage && !input.bitmap.isRecycled &&
            (input.bitmap.config == Bitmap.Config.ARGB_8888 || input.bitmap.config == Bitmap.Config.HARDWARE) &&
            output is BitmapImage && output.isWritableArgb

    override fun render(input: BlurInput, output: BlurOutput) {
        val r = checkNotNull(renderer) { "initialize() must be called before render()" }
        require(supports(input, output)) { "RenderEffect backend needs BitmapImage input and mutable ARGB_8888 output" }
        BlurMath.requireSameSize(input, output)
        input as BitmapImage
        output as BitmapImage

        val sw = Resampler.scaledSize(input.width, config.downsample)
        val sh = Resampler.scaledSize(input.height, config.downsample)
        val target = ensureSurface(r, sw, sh)

        node.setRenderEffect(effect)
        val recording = node.beginRecording(sw, sh)
        rect.set(0, 0, sw, sh)
        recording.drawBitmap(input.bitmap, null, rect, drawPaint)
        node.endRecording()

        val result = r.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val failed = HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or
            HardwareRenderer.SYNC_CONTEXT_IS_STOPPED or HardwareRenderer.SYNC_FRAME_DROPPED
        if (result and failed != 0) throw BlurBackendException("HardwareRenderer did not draw (sync result $result)")

        val image = reader?.acquireLatestImage() ?: throw BlurBackendException("HardwareRenderer produced no frame")
        try {
            copyToBitmap(image, target)
        } finally {
            image.close()
        }

        canvas.setBitmap(output.bitmap)
        rect.set(0, 0, output.width, output.height)
        canvas.drawBitmap(target, null, rect, copyPaint)
        canvas.setBitmap(null)
    }

    override fun release() {
        renderer?.destroy()
        renderer = null
        node.discardDisplayList()
        reader?.close()
        reader = null
        small?.recycle()
        small = null
        packed = null
    }

    /** (Re)creates the reader and small bitmap when the downsampled size changes. */
    private fun ensureSurface(r: HardwareRenderer, w: Int, h: Int): Bitmap {
        val current = reader
        val bitmap = small
        if (current != null && bitmap != null && current.width == w && current.height == h) return bitmap
        current?.close()
        small?.recycle()
        val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
            HardwareBuffer.USAGE_CPU_READ_OFTEN
        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2, usage)
        reader = newReader
        r.setSurface(newReader.surface)
        node.setPosition(0, 0, w, h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { small = it }
    }

    /** Copies RGBA rows from [image] into [target], dropping row padding if the stride is wider. */
    private fun copyToBitmap(image: Image, target: Bitmap) {
        val plane = image.planes[0]
        val source = plane.buffer
        val rowBytes = target.width * 4
        if (plane.rowStride == rowBytes) {
            source.position(0)
            target.copyPixelsFromBuffer(source)
            return
        }
        val bytes = rowBytes * target.height
        val dest = packed?.takeIf { it.capacity() >= bytes } ?: ByteBuffer.allocateDirect(bytes).also { packed = it }
        dest.clear()
        for (y in 0 until target.height) {
            source.clear()
            source.limit(y * plane.rowStride + rowBytes)
            source.position(y * plane.rowStride)
            dest.put(source)
        }
        dest.flip()
        target.copyPixelsFromBuffer(dest)
    }

    public companion object {
        public const val ID: String = RenderEffectBackendFactory.ID

        internal fun supportsAlgorithm(algorithm: BlurAlgorithm): Boolean =
            algorithm == BlurAlgorithm.AUTO || algorithm == BlurAlgorithm.GAUSSIAN
    }
}

/**
 * Factory for [RenderEffectBlurBackend]. Kept outside that class so API 29-30 devices can
 * reference it without loading API 31 types.
 */
public object RenderEffectBackendFactory : BlurBackendFactory {
    /** Backend id. Defined here, not on the API 31 class, so reading it never touches API 31 types. */
    public const val ID: String = "render-effect"

    override val id: String = ID

    override fun isSupported(config: BlurConfig, capabilities: DeviceCapabilities): Boolean =
        capabilities.supportsRenderEffect && capabilities.hardwareAccelerated &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (config.algorithm == BlurAlgorithm.AUTO || config.algorithm == BlurAlgorithm.GAUSSIAN)

    override fun create(): BlurBackend =
        // An explicit SDK_INT branch (not check()) so lint can see the API 31 guard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RenderEffectBlurBackend()
        else throw IllegalStateException("RenderEffect needs API 31")
}
