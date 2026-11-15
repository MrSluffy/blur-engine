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
import kotlin.math.roundToInt

/**
 * Separable box blur using a running sum (O(1) per pixel regardless of radius).
 * Edges are clamped. Multiple [passes] approximate a Gaussian.
 */
public class BoxBlur(private val passes: Int = 3) : BlurKernel {
    internal var tmp: IntArray = IntArray(0)

    init { require(passes >= 1) { "passes must be >= 1" } }

    override fun blur(src: PixelBuffer, dst: PixelBuffer, radius: Float) {
        val r = radius.roundToInt()
        if (r <= 0) { copyPixels(src, dst); return }
        blurPasses(src, dst, passes) { r }
    }

    /** Runs [count] box passes; pass i uses radius [radiusOf](i). Radii of 0 are skipped. */
    internal inline fun blurPasses(src: PixelBuffer, dst: PixelBuffer, count: Int, radiusOf: (Int) -> Int) {
        val w = src.width
        val h = src.height
        dst.resize(w, h)
        tmp = ensureCapacity(tmp, w * h)
        // Ping-pong between dst and tmp. After each full pass the result lives in dst.
        var input = src.pixels
        var any = false
        for (i in 0 until count) {
            val r = radiusOf(i)
            if (r <= 0) continue
            horizontal(input, tmp, w, h, r)
            vertical(tmp, dst.pixels, w, h, r)
            input = dst.pixels
            any = true
        }
        if (!any) copyPixels(src, dst)
    }

    internal fun horizontal(src: IntArray, out: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = src[row + i.coerceIn(0, w - 1)]
                sa += Argb.a(c); sr += Argb.r(c); sg += Argb.g(c); sb += Argb.b(c)
            }
            for (x in 0 until w) {
                out[row + x] = Argb.pack((sa + div / 2) / div, (sr + div / 2) / div, (sg + div / 2) / div, (sb + div / 2) / div)
                val add = src[row + minOf(x + r + 1, w - 1)]
                val rem = src[row + maxOf(x - r, 0)]
                sa += Argb.a(add) - Argb.a(rem); sr += Argb.r(add) - Argb.r(rem)
                sg += Argb.g(add) - Argb.g(rem); sb += Argb.b(add) - Argb.b(rem)
            }
        }
    }

    internal fun vertical(src: IntArray, out: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = src[i.coerceIn(0, h - 1) * w + x]
                sa += Argb.a(c); sr += Argb.r(c); sg += Argb.g(c); sb += Argb.b(c)
            }
            for (y in 0 until h) {
                out[y * w + x] = Argb.pack((sa + div / 2) / div, (sr + div / 2) / div, (sg + div / 2) / div, (sb + div / 2) / div)
                val add = src[minOf(y + r + 1, h - 1) * w + x]
                val rem = src[maxOf(y - r, 0) * w + x]
                sa += Argb.a(add) - Argb.a(rem); sr += Argb.r(add) - Argb.r(rem)
                sg += Argb.g(add) - Argb.g(rem); sb += Argb.b(add) - Argb.b(rem)
            }
        }
    }

    override fun release() {
        tmp = IntArray(0)
    }
}

/**
 * Fast Gaussian approximation: three box passes whose widths are chosen so their combined
 * variance equals sigma = radius / 2 (W. Jarosz / I. Kutskir "boxes for Gaussian"). Costs the same
 * as [BoxBlur] at any radius, versus O(radius) per pixel for [GaussianBlur]. Used by the software
 * fallback; output differs slightly from a true Gaussian.
 */
public class FastGaussianBlur : BlurKernel {
    private val box = BoxBlur(PASSES)
    private val radii = IntArray(PASSES)
    private var radiiFor = Float.NaN

    override fun blur(src: PixelBuffer, dst: PixelBuffer, radius: Float) {
        if (radius < 0.5f) { copyPixels(src, dst); return }
        if (radius != radiiFor) {
            boxRadii(radius / 2f, radii)
            radiiFor = radius
        }
        box.blurPasses(src, dst, PASSES) { radii[it] }
    }

    override fun release() {
        box.release()
        radiiFor = Float.NaN
    }

    public companion object {
        private const val PASSES = 3

        /** Box radii for [out].size passes whose total variance is [sigma]^2. */
        public fun boxRadii(sigma: Float, out: IntArray) {
            val n = out.size
            val s2 = sigma.toDouble() * sigma
            val ideal = kotlin.math.sqrt(12 * s2 / n + 1)
            var wl = kotlin.math.floor(ideal).toInt()
            if (wl % 2 == 0) wl--
            if (wl < 1) wl = 1
            val wu = wl + 2
            val m = ((12 * s2 - n * wl * wl - 4 * n * wl - 3 * n) / (-4.0 * wl - 4)).roundToInt().coerceIn(0, n)
            for (i in 0 until n) out[i] = ((if (i < m) wl else wu) - 1) / 2
        }
    }
}

