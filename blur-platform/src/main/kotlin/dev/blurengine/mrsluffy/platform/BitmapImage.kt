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
import dev.blurengine.mrsluffy.core.BlurInput
import dev.blurengine.mrsluffy.core.BlurOutput

/**
 * A [Bitmap] as blur input or output.
 *
 * Backends need `ARGB_8888`. Outputs must also be mutable. Backends do not keep a reference
 * after `render` returns, so the caller keeps ownership (and recycling) of the bitmap.
 */
public class BitmapImage(public val bitmap: Bitmap) : BlurInput, BlurOutput {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    internal val isReadableArgb: Boolean
        get() = !bitmap.isRecycled && bitmap.config == Bitmap.Config.ARGB_8888

    internal val isWritableArgb: Boolean
        get() = isReadableArgb && bitmap.isMutable
}

internal fun bitmapPair(input: Any, output: Any): Boolean =
    input is BitmapImage && output is BitmapImage && input.isReadableArgb && output.isWritableArgb
