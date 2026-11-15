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
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measures blur spread from the derivative of a blurred step edge. For a Gaussian this
 * returns sigma; for other kernels it is the equivalent standard deviation.
 */
internal object Spread {
    fun measure(blur: (PixelBuffer, PixelBuffer) -> Unit): Double =
        listOf(512, 513, 515, 519, 527).map { edge(blur, it) }.average()

    private fun edge(blur: (PixelBuffer, PixelBuffer) -> Unit, edgeX: Int): Double {
        val w = 1024; val h = 256
        val src = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = if (x < edgeX) 0 else 255
            src.pixels[y * w + x] = Argb.pack(255, v, v, v)
        }
        val out = PixelBuffer(w, h)
        blur(src, out)
        val row = (h / 2) * w
        fun d(x: Int) = (Argb.r(out.pixels[row + x + 1]) - Argb.r(out.pixels[row + x])).toDouble()
        var sum = 0.0; var mean = 0.0
        for (x in 0 until w - 1) { sum += d(x); mean += d(x) * (x + 0.5) }
        mean /= sum
        var v = 0.0
        for (x in 0 until w - 1) v += d(x) * (x + 0.5 - mean) * (x + 0.5 - mean)
        return sqrt(v / sum)
    }
}

class KawaseCalibrationTest {
    /** Dual Kawase must spread like a Gaussian with sigma = radius / 2, so AUTO and backends look alike. */
    @Test fun kawaseSpreadMatchesGaussianTarget() {
        val k = DualKawaseBlur(DualKawaseBlur.MAX_ITERATIONS)
        for (radius in listOf(3f, 6f, 10f, 16f, 24f, 40f, 64f, 100f)) {
            val target = radius / 2.0
            val measured = Spread.measure { s, d -> k.blur(s, d, radius) }
            assertTrue(abs(measured - target) / target < 0.12, "radius=$radius target=$target measured=%.2f".format(measured))
        }
    }

    @Test fun gaussianSpreadIsHalfRadius() {
        val g = GaussianBlur(3f)
        for (radius in listOf(4f, 16f)) {
            val measured = Spread.measure { s, d -> g.blur(s, d, radius) }
            assertTrue(abs(measured - radius / 2.0) < 0.15, "radius=$radius measured=$measured")
        }
    }

    /** The fast software fallback must spread like the Gaussian it stands in for. */
    @Test fun fastGaussianSpreadMatchesTarget() {
        val k = FastGaussianBlur()
        for (radius in listOf(6f, 10f, 16f, 24f, 40f, 64f, 100f)) {
            val target = radius / 2.0
            val measured = Spread.measure { s, d -> k.blur(s, d, radius) }
            assertTrue(abs(measured - target) / target < 0.08, "radius=$radius target=$target measured=%.2f".format(measured))
        }
    }

    @Test fun boxRadiiSumToTheTargetVariance() {
        val radii = IntArray(3)
        for (sigma in listOf(3f, 8f, 20f, 50f)) {
            FastGaussianBlur.boxRadii(sigma, radii)
            val variance = radii.sumOf { r -> ((2 * r + 1) * (2 * r + 1) - 1) / 12.0 }
            assertTrue(abs(variance - sigma * sigma) / (sigma * sigma) < 0.15, "sigma=$sigma radii=${radii.toList()} var=$variance")
        }
    }

    @Test fun kawasePlanCacheReusesThePlan() {
        val cache = KawasePlanCache()
        val a = cache.get(20f, 270, 600, 4)
        assertTrue(a === cache.get(20f, 270, 600, 4), "same inputs must return the cached plan")
        assertTrue(a !== cache.get(21f, 270, 600, 4))
    }

    @Test fun planRespectsCaps() {
        val p = DualKawaseBlur.plan(radius = 500f, width = 1000, height = 1000, maxIterations = 3)
        assertTrue(p.iterations == 3 && p.offset == 3f, "$p")
        val small = DualKawaseBlur.plan(radius = 500f, width = 9, height = 9, maxIterations = 6)
        assertTrue(small.iterations == 2, "$small") // 9 >> 2 = 2, 9 >> 3 = 1
    }
}
