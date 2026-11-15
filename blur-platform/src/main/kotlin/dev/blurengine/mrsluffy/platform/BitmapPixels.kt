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

import android.graphics.Bitmap
import dev.blurengine.mrsluffy.core.PixelBuffer

/**
 * Copies between [Bitmap] (ARGB_8888) and [PixelBuffer] without intermediate allocations.
 * Pixels are unpremultiplied, as returned by [Bitmap.getPixels].
 */
public object BitmapPixels {
    /** Reads [bitmap] into [target], resizing it to the bitmap size. */
    public fun read(bitmap: Bitmap, target: PixelBuffer) {
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "Bitmap must be ARGB_8888, was ${bitmap.config}" }
        target.resize(bitmap.width, bitmap.height)
        bitmap.getPixels(target.pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    /** Writes [source] into [bitmap]. Sizes must match and the bitmap must be mutable. */
    public fun write(source: PixelBuffer, bitmap: Bitmap) {
        require(bitmap.isMutable) { "Bitmap must be mutable" }
        require(bitmap.width == source.width && bitmap.height == source.height) {
            "size mismatch: bitmap ${bitmap.width}x${bitmap.height}, buffer ${source.width}x${source.height}"
        }
        bitmap.setPixels(source.pixels, 0, source.width, 0, 0, source.width, source.height)
    }
}
