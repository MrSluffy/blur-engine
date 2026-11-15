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

/**
 * Reuses [PixelBuffer]s to avoid per-frame allocations. Not thread-safe; guard externally
 * or use one pool per render thread. Retained memory is bounded by [maxBytes].
 */
public class PixelBufferPool(private val maxBytes: Long = 32L * 1024 * 1024) {
    private val free = ArrayList<PixelBuffer>()
    private var retainedBytes = 0L

    /** Returns a buffer of exactly [width] x [height]; contents are undefined. */
    public fun acquire(width: Int, height: Int): PixelBuffer {
        val needed = width.toLong() * height
        var best = -1
        for (i in free.indices) {
            val cap = free[i].pixels.size
            if (cap >= needed && (best < 0 || cap < free[best].pixels.size)) best = i
        }
        if (best >= 0) {
            val b = free.removeAt(best)
            retainedBytes -= b.pixels.size * 4L
            b.resize(width, height)
            return b
        }
        return PixelBuffer(width, height)
    }

    /** Returns [buffer] to the pool, or drops it if the pool is full. */
    public fun release(buffer: PixelBuffer) {
        val bytes = buffer.pixels.size * 4L
        if (retainedBytes + bytes > maxBytes) return
        free.add(buffer)
        retainedBytes += bytes
    }

    public fun clear() {
        free.clear()
        retainedBytes = 0
    }

    public val retainedByteCount: Long get() = retainedBytes
}
