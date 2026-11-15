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
 * A reusable ARGB_8888 pixel buffer (one Int per pixel, row-major, no stride padding).
 * Pure JVM so algorithms can be tested without Android.
 *
 * Channels are treated independently by the blur kernels, so buffers should hold
 * the same alpha convention (premultiplied or not) end to end.
 */
public class PixelBuffer(width: Int, height: Int) {
    public var width: Int = width
        private set
    public var height: Int = height
        private set
    public var pixels: IntArray = IntArray(checkedSize(width, height))
        private set

    /** Resizes the logical dimensions, reallocating only when capacity is insufficient. */
    public fun resize(newWidth: Int, newHeight: Int) {
        val size = checkedSize(newWidth, newHeight)
        if (pixels.size < size) pixels = IntArray(size)
        width = newWidth
        height = newHeight
    }

    public fun copyFrom(other: PixelBuffer) {
        resize(other.width, other.height)
        System.arraycopy(other.pixels, 0, pixels, 0, width * height)
    }

    public companion object {
        private fun checkedSize(w: Int, h: Int): Int {
            require(w > 0 && h > 0) { "dimensions must be positive: ${w}x$h" }
            val size = w.toLong() * h
            require(size <= Int.MAX_VALUE) { "buffer too large: ${w}x$h" }
            return size.toInt()
        }
    }
}
