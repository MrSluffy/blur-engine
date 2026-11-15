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

/** In-place saturation, brightness and tint adjustment. */
public object ColorAdjust {
    /** True if [apply] would change anything for these parameters. */
    public fun isNoop(saturation: Float, brightness: Float, tintAlpha: Float): Boolean =
        saturation == 1f && brightness == 1f && tintAlpha <= 0f

    /**
     * Applies saturation, then brightness, then a tint overlay (source-over with [tintColor]'s RGB
     * at [tintAlpha]). The pixel alpha channel is preserved.
     */
    public fun apply(buffer: PixelBuffer, saturation: Float, brightness: Float, tintColor: Int, tintAlpha: Float) {
        if (isNoop(saturation, brightness, tintAlpha)) return
        val tr = Argb.r(tintColor); val tg = Argb.g(tintColor); val tb = Argb.b(tintColor)
        val ta = tintAlpha.coerceIn(0f, 1f)
        val px = buffer.pixels
        val n = buffer.width * buffer.height
        for (i in 0 until n) {
            val c = px[i]
            var r = Argb.r(c).toFloat(); var g = Argb.g(c).toFloat(); var b = Argb.b(c).toFloat()
            if (saturation != 1f) {
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                r = l + (r - l) * saturation; g = l + (g - l) * saturation; b = l + (b - l) * saturation
            }
            if (brightness != 1f) { r *= brightness; g *= brightness; b *= brightness }
            if (ta > 0f) {
                r += (tr - r) * ta; g += (tg - g) * ta; b += (tb - b) * ta
            }
            px[i] = Argb.pack(Argb.a(c), clamp(r), clamp(g), clamp(b))
        }
    }

    private fun clamp(v: Float): Int = (v + 0.5f).toInt().coerceIn(0, 255)
}
