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

import dev.blurengine.mrsluffy.core.BlurMath
import dev.blurengine.mrsluffy.core.PixelBuffer
import kotlin.math.ceil
import kotlin.math.exp

/** Gaussian kernel weights shared by the CPU and GPU implementations. */
public object GaussianKernel {
    /**
     * Normalized weights of length 2 * half + 1 (index half is the centre), with
     * sigma = radius / 2 and half = max(1, ceil(sigma * sigmaSpan)).
     */
    public fun weights(radius: Float, sigmaSpan: Float): FloatArray {
        require(radius > 0f && sigmaSpan > 0f) { "radius and sigmaSpan must be > 0" }
        val sigma = BlurMath.sigmaForRadius(radius)
        val half = maxOf(1, ceil(sigma * sigmaSpan).toInt())
        val k = FloatArray(2 * half + 1)
        var sum = 0f
        for (i in -half..half) {
            val v = exp(-(i * i) / (2f * sigma * sigma))
            k[i + half] = v
            sum += v
        }
        for (i in k.indices) k[i] /= sum
        return k
    }
}

/**
 * Separable Gaussian blur with edge clamping. [radius] is used as the standard deviation
 * times 2 (sigma = radius / 2); the kernel spans [sigmaSpan] standard deviations each side.
 * The kernel is cached per (radius, span) so repeated frames do not allocate.
 */
public class GaussianBlur(private val sigmaSpan: Float = 3f) : BlurKernel {
    private var tmp = IntArray(0)
    private var kernel = FloatArray(0)
    private var kernelRadius = Float.NaN

    init { require(sigmaSpan > 0f) { "sigmaSpan must be > 0" } }

    override fun blur(src: PixelBuffer, dst: PixelBuffer, radius: Float) {
        if (radius < 0.5f) { copyPixels(src, dst); return }
        val w = src.width
        val h = src.height
        dst.resize(w, h)
        tmp = ensureCapacity(tmp, w * h)
        buildKernel(radius)
        val half = kernel.size / 2
        horizontal(src.pixels, tmp, w, h, half)
        vertical(tmp, dst.pixels, w, h, half)
    }

    private fun buildKernel(radius: Float) {
        if (radius == kernelRadius) return
        kernel = GaussianKernel.weights(radius, sigmaSpan)
        kernelRadius = radius
    }

    private fun horizontal(src: IntArray, out: IntArray, w: Int, h: Int, half: Int) {
        val k = kernel
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (i in -half..half) {
                    val c = src[row + (x + i).coerceIn(0, w - 1)]
                    val wt = k[i + half]
                    a += Argb.a(c) * wt; r += Argb.r(c) * wt; g += Argb.g(c) * wt; b += Argb.b(c) * wt
                }
                out[row + x] = Argb.pack(round(a), round(r), round(g), round(b))
            }
        }
    }

    private fun vertical(src: IntArray, out: IntArray, w: Int, h: Int, half: Int) {
        val k = kernel
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (i in -half..half) {
                    val c = src[(y + i).coerceIn(0, h - 1) * w + x]
                    val wt = k[i + half]
                    a += Argb.a(c) * wt; r += Argb.r(c) * wt; g += Argb.g(c) * wt; b += Argb.b(c) * wt
                }
                out[y * w + x] = Argb.pack(round(a), round(r), round(g), round(b))
            }
        }
    }

    private fun round(v: Float): Int = (v + 0.5f).toInt().coerceIn(0, 255)

    override fun release() {
        tmp = IntArray(0)
        kernel = FloatArray(0)
        kernelRadius = Float.NaN
    }
}
