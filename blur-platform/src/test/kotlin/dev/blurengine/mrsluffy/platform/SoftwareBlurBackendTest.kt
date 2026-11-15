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

import dev.blurengine.mrsluffy.core.BlurAlgorithm
import dev.blurengine.mrsluffy.core.BlurConfig
import dev.blurengine.mrsluffy.core.PixelBuffer
import dev.blurengine.mrsluffy.core.PixelBufferImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SoftwareBlurBackendTest {
    private val size = 64

    private fun halfBlackHalfWhite() = PixelBuffer(size, size).also {
        for (y in 0 until size) for (x in 0 until size) {
            val v = if (x < size / 2) 0 else 255
            it.pixels[y * size + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    @Test fun blursWithEveryAlgorithmAndAppliesTint() {
        val input = PixelBufferImage(halfBlackHalfWhite())
        val backend = SoftwareBlurBackend()
        for (alg in BlurAlgorithm.values()) {
            backend.initialize(BlurConfig(radius = 16f, algorithm = alg, downsample = 2))
            val out = PixelBufferImage(PixelBuffer(size, size))
            backend.render(input, out)
            val edge = out.buffer.pixels[(size / 2) * size + size / 2 - 1] and 0xFF
            assertTrue(edge in 20..235, "$alg edge=$edge")
        }
        backend.updateConfig(BlurConfig(radius = 16f, tintColor = 0xFFFF0000.toInt(), tintAlpha = 1f))
        val out = PixelBufferImage(PixelBuffer(size, size))
        backend.render(input, out)
        assertEquals(0xFFFF0000.toInt(), out.buffer.pixels[0])
        backend.release()
    }

    @Test fun rejectsMismatchedSizes() {
        val backend = SoftwareBlurBackend().apply { initialize(BlurConfig()) }
        assertFailsWith<IllegalArgumentException> {
            backend.render(PixelBufferImage(PixelBuffer(4, 4)), PixelBufferImage(PixelBuffer(4, 5)))
        }
    }

    @Test fun renderBeforeInitializeFails() {
        assertFailsWith<IllegalStateException> {
            SoftwareBlurBackend().render(PixelBufferImage(PixelBuffer(2, 2)), PixelBufferImage(PixelBuffer(2, 2)))
        }
    }
}
