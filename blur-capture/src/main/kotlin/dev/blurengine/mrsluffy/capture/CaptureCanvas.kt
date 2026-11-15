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

package dev.blurengine.mrsluffy.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Software canvas for captures that can draw HARDWARE bitmaps, which plain software canvases
 * reject ("Software rendering doesn't support hardware bitmaps"). Image loaders such as Coil and
 * Glide use HARDWARE bitmaps by default.
 *
 * Each HARDWARE bitmap is copied to a software bitmap once, downscaled to [copyScale] (the capture
 * resolution, so the copy is small), and cached until the original is garbage-collected or the
 * cache exceeds [maxCacheBytes]. HARDWARE bitmaps are immutable, so a copy never goes stale.
 *
 * Covers the drawBitmap calls used by ImageView, BitmapDrawable and Compose Image. Not covered:
 * HARDWARE bitmaps inside a BitmapShader (the capture still fails there and reports it).
 */
internal class CaptureCanvas(private val maxCacheBytes: Long = 16L * 1024 * 1024) : Canvas() {
    /** Scale for new copies, e.g. 0.25 for downsample 4. Set before each capture. */
    var copyScale: Float = 1f

    private val copies = WeakHashMap<Bitmap, Bitmap>()
    private var cachedBytes = 0L
    private val srcRect = Rect()
    private val matrix = Matrix()

    override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
        val copy = softwareCopy(bitmap) ?: return super.drawBitmap(bitmap, left, top, paint)
        matrix.setScale(bitmap.width / copy.width.toFloat(), bitmap.height / copy.height.toFloat())
        matrix.postTranslate(left, top)
        super.drawBitmap(copy, matrix, paint)
    }

    override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
        val copy = softwareCopy(bitmap) ?: return super.drawBitmap(bitmap, src, dst, paint)
        super.drawBitmap(copy, scaledSrc(bitmap, copy, src), dst, paint)
    }

    override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) {
        val copy = softwareCopy(bitmap) ?: return super.drawBitmap(bitmap, src, dst, paint)
        super.drawBitmap(copy, scaledSrc(bitmap, copy, src), dst, paint)
    }

    override fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
        val copy = softwareCopy(bitmap) ?: return super.drawBitmap(bitmap, matrix, paint)
        this.matrix.setScale(bitmap.width / copy.width.toFloat(), bitmap.height / copy.height.toFloat())
        this.matrix.postConcat(matrix)
        super.drawBitmap(copy, this.matrix, paint)
    }

    /** Maps [src] (in original pixels) into the copy's pixels; null stays null (whole bitmap). */
    private fun scaledSrc(original: Bitmap, copy: Bitmap, src: Rect?): Rect? {
        if (src == null) return null
        val sx = copy.width / original.width.toFloat()
        val sy = copy.height / original.height.toFloat()
        srcRect.set(
            (src.left * sx).roundToInt(), (src.top * sy).roundToInt(),
            (src.right * sx).roundToInt().coerceAtLeast((src.left * sx).roundToInt() + 1),
            (src.bottom * sy).roundToInt().coerceAtLeast((src.top * sy).roundToInt() + 1),
        )
        return srcRect
    }

    /** A cached software copy for HARDWARE bitmaps, or null for bitmaps the canvas can draw as is. */
    private fun softwareCopy(bitmap: Bitmap): Bitmap? {
        if (bitmap.config != Bitmap.Config.HARDWARE || bitmap.isRecycled) return null
        copies[bitmap]?.let { return it }
        val full = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        val w = (bitmap.width * copyScale).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * copyScale).roundToInt().coerceAtLeast(1)
        val copy = if (w < full.width || h < full.height) {
            Bitmap.createScaledBitmap(full, w, h, true).also { if (it !== full) full.recycle() }
        } else {
            full
        }
        val bytes = copy.allocationByteCount.toLong()
        if (cachedBytes + bytes > maxCacheBytes) {
            copies.clear() // simple bound; copies are cheap to recreate at capture resolution
            cachedBytes = 0
        }
        copies[bitmap] = copy
        cachedBytes += bytes
        return copy
    }

    /** Drops all cached copies. */
    fun clearCache() {
        copies.clear()
        cachedBytes = 0
    }
}
