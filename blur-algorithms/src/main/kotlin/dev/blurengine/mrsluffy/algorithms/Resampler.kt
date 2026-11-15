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

package dev.blurengine.mrsluffy.algorithms

import dev.blurengine.mrsluffy.core.PixelBuffer
import kotlin.math.floor

/** Bilinear sampling and box-filter resampling. */
internal object Sampling {
    /**
     * Samples with texel-center convention: integer coordinates hit pixel centers exactly.
     * Coordinates outside the image are clamped to the edge.
     */
    @JvmStatic
    fun bilinear(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val fx = x.coerceIn(0f, (w - 1).toFloat())
        val fy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val x1 = minOf(x0 + 1, w - 1)
        val y1 = minOf(y0 + 1, h - 1)
        val tx = fx - x0
        val ty = fy - y0
        val c00 = px[y0 * w + x0]; val c10 = px[y0 * w + x1]
        val c01 = px[y1 * w + x0]; val c11 = px[y1 * w + x1]
        val w00 = (1 - tx) * (1 - ty); val w10 = tx * (1 - ty)
        val w01 = (1 - tx) * ty; val w11 = tx * ty
        val a = Argb.a(c00) * w00 + Argb.a(c10) * w10 + Argb.a(c01) * w01 + Argb.a(c11) * w11
        val r = Argb.r(c00) * w00 + Argb.r(c10) * w10 + Argb.r(c01) * w01 + Argb.r(c11) * w11
        val g = Argb.g(c00) * w00 + Argb.g(c10) * w10 + Argb.g(c01) * w01 + Argb.g(c11) * w11
        val b = Argb.b(c00) * w00 + Argb.b(c10) * w10 + Argb.b(c01) * w01 + Argb.b(c11) * w11
        return Argb.pack((a + 0.5f).toInt(), (r + 0.5f).toInt(), (g + 0.5f).toInt(), (b + 0.5f).toInt())
    }
}

/** Downsampling (area average) and bilinear upscaling between differently sized buffers. */
public object Resampler {
    /** Output size for downsampling [size] by [factor], rounding up so edges are kept. */
    public fun scaledSize(size: Int, factor: Int): Int = maxOf(1, (size + factor - 1) / factor)

    /** Area-averages [src] by an integer [factor] into [dst] (resized). */
    public fun downsample(src: PixelBuffer, dst: PixelBuffer, factor: Int) {
        require(factor >= 1) { "factor must be >= 1" }
        if (factor == 1) { copyPixels(src, dst); return }
        val dw = scaledSize(src.width, factor)
        val dh = scaledSize(src.height, factor)
        dst.resize(dw, dh)
        val sp = src.pixels
        val dp = dst.pixels
        for (y in 0 until dh) {
            val y0 = y * factor
            val y1 = minOf(y0 + factor, src.height)
            for (x in 0 until dw) {
                val x0 = x * factor
                val x1 = minOf(x0 + factor, src.width)
                var a = 0; var r = 0; var g = 0; var b = 0
                for (sy in y0 until y1) {
                    val row = sy * src.width
                    for (sx in x0 until x1) {
                        val c = sp[row + sx]
                        a += Argb.a(c); r += Argb.r(c); g += Argb.g(c); b += Argb.b(c)
                    }
                }
                val n = (y1 - y0) * (x1 - x0)
                dp[y * dw + x] = Argb.pack((a + n / 2) / n, (r + n / 2) / n, (g + n / 2) / n, (b + n / 2) / n)
            }
        }
    }

    /** Bilinearly scales [src] to [width] x [height] into [dst] (resized). */
    public fun upscale(src: PixelBuffer, dst: PixelBuffer, width: Int, height: Int) {
        dst.resize(width, height)
        if (src.width == width && src.height == height) {
            System.arraycopy(src.pixels, 0, dst.pixels, 0, width * height)
            return
        }
        val sx = src.width.toFloat() / width
        val sy = src.height.toFloat() / height
        val dp = dst.pixels
        for (y in 0 until height) {
            val fy = (y + 0.5f) * sy - 0.5f
            for (x in 0 until width) {
                dp[y * width + x] = Sampling.bilinear(src.pixels, src.width, src.height, (x + 0.5f) * sx - 0.5f, fy)
            }
        }
    }
}
