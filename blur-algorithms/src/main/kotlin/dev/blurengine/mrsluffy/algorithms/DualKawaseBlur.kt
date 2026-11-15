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

/** Pass count and sample offset for one Dual Kawase blur. */
public data class KawasePlan(val iterations: Int, val offset: Float)

/**
 * Remembers the last [DualKawaseBlur.plan], so steady frames (same radius and size) do not
 * allocate a new [KawasePlan]. Not thread-safe.
 */
public class KawasePlanCache {
    private var radius = Float.NaN
    private var width = -1
    private var height = -1
    private var maxIterations = -1
    private var plan = KawasePlan(1, 1f)

    public fun get(radius: Float, width: Int, height: Int, maxIterations: Int): KawasePlan {
        if (radius != this.radius || width != this.width || height != this.height || maxIterations != this.maxIterations) {
            plan = DualKawaseBlur.plan(radius, width, height, maxIterations)
            this.radius = radius; this.width = width; this.height = height; this.maxIterations = maxIterations
        }
        return plan
    }
}

/**
 * Dual Kawase blur (Bjorge, SIGGRAPH 2015): a chain of half-resolution downsample passes
 * followed by the same number of upsample passes. Cost scales with pixel count, not radius.
 *
 * [plan] picks iterations and offset from a measured table so the result has the same spread
 * as [GaussianBlur] at the same radius (sigma = radius / 2). GPU backends use the same plan.
 */
public class DualKawaseBlur(private val maxIterations: Int = 4) : BlurKernel {
    private var levels = ArrayList<PixelBuffer>()

    init { require(maxIterations >= 1) { "maxIterations must be >= 1" } }

    private val planCache = KawasePlanCache()

    override fun blur(src: PixelBuffer, dst: PixelBuffer, radius: Float) {
        if (radius < 0.5f) { copyPixels(src, dst); return }
        val p = planCache.get(radius, src.width, src.height, maxIterations)
        run(src, dst, p.iterations, p.offset)
    }

    /** Runs exactly [iterations] down/up levels with sample [offset] (in source pixels of each level). */
    internal fun run(src: PixelBuffer, dst: PixelBuffer, iterations: Int, offset: Float) {
        // levels[0] is src's first half-res target; levels[i] is the i+1'th.
        var prev = src
        for (i in 0 until iterations) {
            val level = levelBuffer(i, Resampler.scaledSize(prev.width, 2), Resampler.scaledSize(prev.height, 2))
            down(prev, level, offset)
            prev = level
        }
        for (i in iterations - 1 downTo 0) {
            // Upsampling writes over the matching down level, which is no longer needed.
            val target = if (i == 0) dst else levels[i - 1]
            if (i == 0) dst.resize(src.width, src.height)
            up(prev, target, offset)
            prev = target
        }
    }

    private fun levelBuffer(index: Int, w: Int, h: Int): PixelBuffer {
        while (levels.size <= index) levels.add(PixelBuffer(1, 1))
        return levels[index].also { it.resize(w, h) }
    }

    private fun down(src: PixelBuffer, dst: PixelBuffer, o: Float) {
        val sp = src.pixels; val sw = src.width; val sh = src.height
        val dp = dst.pixels; val dw = dst.width; val dh = dst.height
        for (y in 0 until dh) {
            val cy = 2f * y + 0.5f
            for (x in 0 until dw) {
                val cx = 2f * x + 0.5f
                val c = Sampling.bilinear(sp, sw, sh, cx, cy)
                val c1 = Sampling.bilinear(sp, sw, sh, cx - o, cy - o)
                val c2 = Sampling.bilinear(sp, sw, sh, cx + o, cy - o)
                val c3 = Sampling.bilinear(sp, sw, sh, cx - o, cy + o)
                val c4 = Sampling.bilinear(sp, sw, sh, cx + o, cy + o)
                dp[y * dw + x] = combine(c, 4, c1, 1, c2, 1, c3, 1, c4, 1, 8)
            }
        }
    }

    private fun up(src: PixelBuffer, dst: PixelBuffer, o: Float) {
        val sp = src.pixels; val sw = src.width; val sh = src.height
        val dp = dst.pixels; val dw = dst.width; val dh = dst.height
        val ax = o        // axis taps: one low-res texel
        val dg = o * 0.5f // diagonal taps: half a texel
        for (y in 0 until dh) {
            val cy = (y + 0.5f) * 0.5f - 0.5f
            for (x in 0 until dw) {
                val cx = (x + 0.5f) * 0.5f - 0.5f
                var a = 0; var r = 0; var g = 0; var b = 0
                // axis taps, weight 1
                var c = Sampling.bilinear(sp, sw, sh, cx - ax, cy); a += Argb.a(c); r += Argb.r(c); g += Argb.g(c); b += Argb.b(c)
                c = Sampling.bilinear(sp, sw, sh, cx + ax, cy); a += Argb.a(c); r += Argb.r(c); g += Argb.g(c); b += Argb.b(c)
                c = Sampling.bilinear(sp, sw, sh, cx, cy - ax); a += Argb.a(c); r += Argb.r(c); g += Argb.g(c); b += Argb.b(c)
                c = Sampling.bilinear(sp, sw, sh, cx, cy + ax); a += Argb.a(c); r += Argb.r(c); g += Argb.g(c); b += Argb.b(c)
                // diagonal taps, weight 2
                c = Sampling.bilinear(sp, sw, sh, cx - dg, cy - dg); a += 2 * Argb.a(c); r += 2 * Argb.r(c); g += 2 * Argb.g(c); b += 2 * Argb.b(c)
                c = Sampling.bilinear(sp, sw, sh, cx + dg, cy - dg); a += 2 * Argb.a(c); r += 2 * Argb.r(c); g += 2 * Argb.g(c); b += 2 * Argb.b(c)
                c = Sampling.bilinear(sp, sw, sh, cx - dg, cy + dg); a += 2 * Argb.a(c); r += 2 * Argb.r(c); g += 2 * Argb.g(c); b += 2 * Argb.b(c)
                c = Sampling.bilinear(sp, sw, sh, cx + dg, cy + dg); a += 2 * Argb.a(c); r += 2 * Argb.r(c); g += 2 * Argb.g(c); b += 2 * Argb.b(c)
                dp[y * dw + x] = Argb.pack((a + 6) / 12, (r + 6) / 12, (g + 6) / 12, (b + 6) / 12)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun combine(
        c0: Int, w0: Int, c1: Int, w1: Int, c2: Int, w2: Int, c3: Int, w3: Int, c4: Int, w4: Int, div: Int,
    ): Int {
        val a = Argb.a(c0) * w0 + Argb.a(c1) * w1 + Argb.a(c2) * w2 + Argb.a(c3) * w3 + Argb.a(c4) * w4
        val r = Argb.r(c0) * w0 + Argb.r(c1) * w1 + Argb.r(c2) * w2 + Argb.r(c3) * w3 + Argb.r(c4) * w4
        val g = Argb.g(c0) * w0 + Argb.g(c1) * w1 + Argb.g(c2) * w2 + Argb.g(c3) * w3 + Argb.g(c4) * w4
        val b = Argb.b(c0) * w0 + Argb.b(c1) * w1 + Argb.b(c2) * w2 + Argb.b(c3) * w3 + Argb.b(c4) * w4
        val h = div / 2
        return Argb.pack((a + h) / div, (r + h) / div, (g + h) / div, (b + h) / div)
    }

    override fun release() {
        levels = ArrayList()
    }

    public companion object {
        /** Sample offsets the calibration table was measured at. */
        private val OFFSETS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

        /**
         * Measured spread (sigma, in pixels) of a full down/up chain, per iteration count (rows)
         * and offset (columns). Measured on step edges with KawaseCalibrationProbe.
         */
        private val SIGMA = arrayOf(
            floatArrayOf(1.199f, 1.437f, 1.695f, 1.898f, 2.132f, 2.552f, 2.945f, 3.610f, 4.188f),
            floatArrayOf(2.364f, 3.106f, 3.773f, 4.312f, 4.901f, 5.699f, 6.480f, 7.855f, 9.343f),
            floatArrayOf(4.570f, 6.259f, 7.732f, 8.901f, 10.097f, 11.674f, 13.216f, 16.019f, 19.170f),
            floatArrayOf(8.972f, 12.479f, 15.504f, 17.901f, 20.256f, 23.416f, 26.603f, 32.128f, 38.494f),
            floatArrayOf(18.011f, 25.038f, 31.030f, 35.794f, 40.571f, 46.966f, 53.235f, 64.096f, 76.918f),
            floatArrayOf(33.950f, 49.390f, 62.149f, 71.592f, 81.178f, 94.133f, 106.375f, 128.297f, 153.805f),
        )

        /** Column of offset 2.0: preferred upper bound before adding an iteration (larger offsets alias more). */
        private const val PREFERRED_MAX_COLUMN = 6

        /** Most iterations the table covers. */
        public val MAX_ITERATIONS: Int get() = SIGMA.size

        /**
         * Picks the fewest iterations whose spread at offset 2.0 reaches sigma = radius / 2, then
         * interpolates the offset. Iterations are capped by [maxIterations] and by the image size
         * (no level smaller than 2 px); when capped, the blur is weaker than requested.
         */
        public fun plan(radius: Float, width: Int, height: Int, maxIterations: Int): KawasePlan {
            val target = BlurMath.sigmaForRadius(radius)
            var cap = maxIterations.coerceIn(1, SIGMA.size)
            val minSide = minOf(width, height)
            while (cap > 1 && (minSide shr cap) < 2) cap--

            var n = 1
            while (n < cap && SIGMA[n - 1][PREFERRED_MAX_COLUMN] < target) n++
            val row = SIGMA[n - 1]
            if (target <= row[0]) return KawasePlan(n, OFFSETS[0])
            for (j in 1 until row.size) {
                if (target <= row[j]) {
                    val t = (target - row[j - 1]) / (row[j] - row[j - 1])
                    return KawasePlan(n, OFFSETS[j - 1] + t * (OFFSETS[j] - OFFSETS[j - 1]))
                }
            }
            return KawasePlan(n, OFFSETS.last())
        }
    }
}

