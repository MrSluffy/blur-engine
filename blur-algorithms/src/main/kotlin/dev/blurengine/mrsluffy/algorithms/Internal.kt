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

/** Packed ARGB helpers. Kept internal and allocation-free. */
internal object Argb {
    @JvmStatic fun a(c: Int) = c ushr 24
    @JvmStatic fun r(c: Int) = (c shr 16) and 0xFF
    @JvmStatic fun g(c: Int) = (c shr 8) and 0xFF
    @JvmStatic fun b(c: Int) = c and 0xFF
    @JvmStatic fun pack(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
}

internal fun copyPixels(src: PixelBuffer, dst: PixelBuffer) {
    dst.resize(src.width, src.height)
    System.arraycopy(src.pixels, 0, dst.pixels, 0, src.width * src.height)
}

/** Grows an IntArray scratch only when needed. */
internal fun ensureCapacity(current: IntArray, size: Int): IntArray =
    if (current.size >= size) current else IntArray(size)
