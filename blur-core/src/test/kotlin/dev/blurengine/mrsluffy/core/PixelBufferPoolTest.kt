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

package dev.blurengine.mrsluffy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PixelBufferPoolTest {
    @Test fun reusesReleasedBuffer() {
        val pool = PixelBufferPool()
        val a = pool.acquire(100, 100)
        pool.release(a)
        val b = pool.acquire(50, 50)
        assertSame(a, b)
        assertEquals(50, b.width)
        assertEquals(0L, pool.retainedByteCount)
    }

    @Test fun boundsRetainedMemory() {
        val pool = PixelBufferPool(maxBytes = 1000)
        pool.release(PixelBuffer(100, 100)) // 40000 bytes > limit, dropped
        assertEquals(0L, pool.retainedByteCount)
        val small = PixelBuffer(10, 10)
        pool.release(small)
        assertTrue(pool.retainedByteCount in 1..1000)
    }

    @Test fun resizeKeepsCapacity() {
        val b = PixelBuffer(10, 10)
        val arr = b.pixels
        b.resize(5, 5)
        assertSame(arr, b.pixels)
    }
}
