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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlurKernelTest {
    private fun solid(w: Int, h: Int, color: Int) = PixelBuffer(w, h).also { it.pixels.fill(color) }

    private fun impulse(size: Int): PixelBuffer {
        val b = solid(size, size, 0xFF000000.toInt())
        b.pixels[(size / 2) * size + size / 2] = 0xFFFFFFFF.toInt()
        return b
    }

    private fun lum(c: Int) = Argb.r(c)

    private fun kernels(): Map<String, BlurKernel> = mapOf(
        "box" to BoxBlur(3), "gaussian" to GaussianBlur(), "kawase" to DualKawaseBlur(4),
    )

    @Test fun solidColorStaysSolid() {
        val color = 0xFF336699.toInt()
        for ((name, k) in kernels()) for (size in listOf(7, 33, 64)) {
            val out = PixelBuffer(1, 1)
            k.blur(solid(size, size + 3, color), out, 12f)
            assertEquals(size, out.width, name)
            assertEquals(size + 3, out.height, name)
            for (p in out.pixels.take(out.width * out.height)) {
                assertTrue(abs(Argb.r(p) - 0x33) <= 1 && abs(Argb.g(p) - 0x66) <= 1 && abs(Argb.b(p) - 0x99) <= 1, "$name: ${p.toString(16)}")
                assertEquals(0xFF, Argb.a(p), name)
            }
        }
    }

    @Test fun zeroRadiusCopies() {
        val src = impulse(9)
        for ((name, k) in kernels()) {
            val out = PixelBuffer(1, 1)
            k.blur(src, out, 0f)
            assertTrue(src.pixels.contentEquals(out.pixels.copyOf(81)), name)
        }
    }

    @Test fun blockSpreadsAndPreservesEnergyRoughly() {
        // A 9x9 white block, not a single pixel: a lone 255 spread over hundreds of
        // pixels rounds to 0 in 8-bit channels, which would test quantization instead.
        val size = 65
        val src = solid(size, size, 0xFF000000.toInt())
        val c = size / 2
        for (y in c - 4..c + 4) for (x in c - 4..c + 4) src.pixels[y * size + x] = 0xFFFFFFFF.toInt()
        val sourceEnergy = 81 * 255
        for ((name, k) in kernels()) {
            val out = PixelBuffer(1, 1)
            k.blur(src, out, 8f)
            val centre = lum(out.pixels[c * size + c])
            assertTrue(centre in 1..254, "$name centre=$centre")
            assertTrue(lum(out.pixels[c * size + c + 8]) > 0, "$name did not spread")
            val total = out.pixels.take(size * size).sumOf { lum(it) }
            assertTrue(abs(total - sourceEnergy) < sourceEnergy * 0.1, "$name total=$total vs $sourceEnergy")
        }
    }

    @Test fun reusingKernelGivesIdenticalResults() {
        val src = PixelBuffer(40, 30)
        for (i in src.pixels.indices) src.pixels[i] = (0xFF shl 24) or ((i * 37) and 0xFFFFFF)
        for ((name, k) in kernels()) {
            val a = PixelBuffer(1, 1); val b = PixelBuffer(1, 1)
            k.blur(src, a, 6f); k.blur(src, b, 6f)
            assertTrue(a.pixels.copyOf(1200).contentEquals(b.pixels.copyOf(1200)), name)
        }
    }

    @Test fun kawaseApproximatesGaussian() {
        val size = 96
        val src = PixelBuffer(size, size)
        for (y in 0 until size) for (x in 0 until size) {
            val v = if ((x / 12 + y / 12) % 2 == 0) 255 else 0
            src.pixels[y * size + x] = Argb.pack(255, v, v, v)
        }
        val g = PixelBuffer(1, 1); val kw = PixelBuffer(1, 1)
        GaussianBlur().blur(src, g, 12f)
        DualKawaseBlur(5).blur(src, kw, 12f)
        var err = 0L
        for (i in 0 until size * size) err += abs(lum(g.pixels[i]) - lum(kw.pixels[i]))
        val meanErr = err.toDouble() / (size * size)
        assertTrue(meanErr < 30, "mean abs diff vs gaussian = $meanErr")
    }

    @Test fun tinyImagesDoNotCrash() {
        for ((name, k) in kernels()) for ((w, h) in listOf(1 to 1, 2 to 3, 1 to 50)) {
            val out = PixelBuffer(1, 1)
            k.blur(solid(w, h, 0xFF808080.toInt()), out, 30f)
            assertEquals(w, out.width, name); assertEquals(h, out.height, name)
        }
    }
}

class ResamplerAndColorTest {
    @Test fun downsampleAveragesBlocks() {
        val src = PixelBuffer(4, 4)
        for (i in 0 until 16) src.pixels[i] = Argb.pack(255, if (i % 4 < 2) 0 else 200, 0, 0)
        val dst = PixelBuffer(1, 1)
        Resampler.downsample(src, dst, 2)
        assertEquals(2, dst.width); assertEquals(2, dst.height)
        assertEquals(0, Argb.r(dst.pixels[0])); assertEquals(200, Argb.r(dst.pixels[1]))
    }

    @Test fun downsampleOddSizeKeepsEdges() {
        val dst = PixelBuffer(1, 1)
        Resampler.downsample(PixelBuffer(5, 7).also { it.pixels.fill(-1) }, dst, 4)
        assertEquals(2, dst.width); assertEquals(2, dst.height)
        assertEquals(-1, dst.pixels[3])
    }

    @Test fun upscaleOfSolidIsSolid() {
        val src = PixelBuffer(3, 3).also { it.pixels.fill(0xFF102030.toInt()) }
        val dst = PixelBuffer(1, 1)
        Resampler.upscale(src, dst, 10, 7)
        assertTrue(dst.pixels.take(70).all { it == 0xFF102030.toInt() })
    }

    @Test fun grayscaleRemovesChroma() {
        val b = PixelBuffer(1, 1).also { it.pixels[0] = Argb.pack(255, 255, 0, 0) }
        ColorAdjust.apply(b, 0f, 1f, 0, 0f)
        val p = b.pixels[0]
        assertEquals(Argb.r(p), Argb.g(p)); assertEquals(Argb.g(p), Argb.b(p))
        assertEquals(255, Argb.a(p))
    }

    @Test fun tintBlendsTowardsColor() {
        val b = PixelBuffer(1, 1).also { it.pixels[0] = Argb.pack(255, 0, 0, 0) }
        ColorAdjust.apply(b, 1f, 1f, 0xFFFFFFFF.toInt(), 0.5f)
        assertEquals(128, Argb.r(b.pixels[0]))
    }

    @Test fun noopLeavesPixels() {
        val b = PixelBuffer(1, 1).also { it.pixels[0] = 0x12345678 }
        ColorAdjust.apply(b, 1f, 1f, 0xFFFFFFFF.toInt(), 0f)
        assertEquals(0x12345678, b.pixels[0])
    }
}
